import axios from 'axios'

// ═══════════════════════════════════════════
// 类型定义
// ═══════════════════════════════════════════

/** 统一 API 错误类，替代各模块散落的 `new Error(message)` 模式 */
export class ApiError extends Error {
  status: number
  /** 后端返回的错误消息原文（message / detail / error 字段的值） */
  detail?: string
  /** 后端返回的完整响应体，供调用方自行解析 */
  raw?: unknown

  constructor(status: number, message: string, detail?: string, raw?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.detail = detail
    this.raw = raw
  }
}

/** 每个请求可覆盖的选项 */
export interface RequestOptions {
  /** 单次请求超时毫秒，覆盖实例默认值 */
  timeout?: number
  /** 追加请求头 */
  headers?: Record<string, string>
  /** 查询参数，axios 自动拼接到 URL，值为 undefined/null 的键会被忽略 */
  params?: Record<string, string | number | boolean | undefined | null>
}

// ═══════════════════════════════════════════
// Axios 实例
// ═══════════════════════════════════════════

const http = axios.create({
  // 通过 Vite 环境变量支持自定义 API 前缀，默认 '/api'
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  // 默认 5 分钟超时，AI 对话、分析等接口响应时间远超普通 CRUD
  timeout: 300_000,
  // 不设默认 Content-Type：axios 对 JSON body 会自动设 application/json，
  // 对 FormData 则交由浏览器自动附加 multipart/form-data boundary。
  // 显式写死会导致 upload() 时发错 Content-Type，后端拒绝请求（415）。
})

// ═══════════════════════════════════════════
// 响应拦截器 — 统一解包 / 204 / 错误处理
// ═══════════════════════════════════════════

http.interceptors.response.use(
  (response) => {
    // 204 No Content → 返回 undefined，供调用方判空
    if (response.status === 204) return undefined
    // 常规成功 → 自动解包 data，调用方不需要手动 .data
    return response.data
  },
  (error) => {
    const status = error.response?.status ?? 0
    const raw = error.response?.data
    // 提取后端错误体中的可读消息字段
    const detail: string | undefined = raw?.message || raw?.detail || raw?.error
    const message = detail || error.message || `HTTP ${status}`
    throw new ApiError(status, message, detail, raw)
  },
)

// ═══════════════════════════════════════════
// 工具函数
// ═══════════════════════════════════════════

/**
 * 清洗请求体中的空值：过滤空字符串、null、undefined。
 * 从 api/creator.ts 提升为公共工具，供所有 API 模块使用。
 */
export function cleanPayload<T extends Record<string, unknown>>(payload: T): Partial<T> {
  return Object.fromEntries(
    Object.entries(payload).filter(([, value]) => {
      if (typeof value === 'string') return value.trim().length > 0
      return value !== undefined && value !== null
    }),
  ) as Partial<T>
}

/**
 * 从 Content-Disposition 响应头中解析文件名。
 * 优先 UTF-8 编码（RFC 5987），回退普通格式，再回退空串。
 */
function parseContentDispositionFilename(contentDisposition: string | null): string {
  if (!contentDisposition) return ''
  // 优匹配 UTF-8：filename*=UTF-8''xxx
  const utf8Match = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i)
  if (utf8Match?.[1]) return decodeURIComponent(utf8Match[1].replace(/"/g, ''))
  // 回退普通格式：filename="xxx"
  const normalMatch = contentDisposition.match(/filename="?([^";]+)"?/i)
  return normalMatch?.[1] ?? ''
}

/** 从 URL 路径末段提取文件名，作为 Content-Disposition 解析失败的兜底 */
function extractUrlFilename(url: string): string {
  const lastSegment = url.split('/').pop() ?? ''
  return lastSegment.split('?')[0] || ''
}

// ═══════════════════════════════════════════
// 对外暴露的请求方法
// ═══════════════════════════════════════════

/** GET 请求，返回 JSON 解析结果 */
export function get<T>(url: string, options?: RequestOptions): Promise<T> {
  return http.get(url, options) as Promise<T>
}

/** POST 请求，发送 JSON body，返回 JSON 解析结果 */
export function post<T>(url: string, body?: unknown, options?: RequestOptions): Promise<T> {
  return http.post(url, body, options) as Promise<T>
}

/** PUT 请求，发送 JSON body，返回 JSON 解析结果 */
export function put<T>(url: string, body?: unknown, options?: RequestOptions): Promise<T> {
  return http.put(url, body, options) as Promise<T>
}

/**
 * DELETE 请求。泛型默认为 void（无响应体场景），
 * 有响应体时可显式传 `del<MyType>(...)`。
 */
export function del<T = void>(url: string, options?: RequestOptions): Promise<T> {
  return http.delete(url, options) as Promise<T>
}

/**
 * 文件上传。axios 检测 body 为 FormData 时自动设置
 * Content-Type: multipart/form-data; boundary=...，无需手动指定。
 * 超时默认 120s，适应大文件上传场景。
 */
export function upload<T>(url: string, formData: FormData, options?: RequestOptions): Promise<T> {
  return http.post(url, formData, {
    ...options,
    timeout: options?.timeout ?? 120_000,
  }) as Promise<T>
}

/**
 * 文件下载，返回 Blob 对象和从 Content-Disposition 解析出的文件名。
 * 文件名解析失败 → URL 末段 → 'download'，三级兜底。
 * 超时默认 60s。
 *
 * 注意：下载需同时取 blob 和响应头，因此不能走拦截器的 data 解包路径，
 * 直接调用 axios 取完整 AxiosResponse 后再自行处理。
 */
export async function download(
  url: string,
  options?: RequestOptions,
): Promise<{ blob: Blob; filename: string }> {
  // 用 raw axios 取完整响应，blob 响应不经过 JSON 拦截器解包
  const response = await axios({
    method: 'GET',
    baseURL: http.defaults.baseURL,
    url,
    responseType: 'blob',
    timeout: options?.timeout ?? 60_000,
    headers: options?.headers,
    params: options?.params,
  })

  // 对下载也做错误处理，与拦截器逻辑保持一致
  if (response.status < 200 || response.status >= 300) {
    let detail: string | undefined
    try {
      const errorBody = JSON.parse(await (response.data as Blob).text())
      detail = errorBody.message || errorBody.detail || errorBody.error
    } catch {
      /* 错误体非 JSON 时忽略 */
    }
    throw new ApiError(response.status, detail || `HTTP ${response.status}`, detail)
  }

  return {
    blob: response.data,
    // 三级兜底：Content-Disposition → URL 末段 → 'download'
    filename:
      parseContentDispositionFilename(response.headers['content-disposition'] as string | null)
      || extractUrlFilename(url)
      || 'download',
  }
}
