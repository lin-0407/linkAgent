import { ApiError } from '@/api/http'

/** 这些状态码有稳定的用户可读解释，不依赖后端文案 */
const STATUS_TEXT: Record<number, string> = {
  400: '请求内容有误，请检查后重试',
  401: '登录状态已失效，请重新登录',
  403: '没有权限执行该操作',
  404: '没有找到对应的数据',
  408: '请求超时，请稍后重试',
  409: '数据已发生变化，请刷新后重试',
  413: '文件太大，请换一个更小的文件',
  422: '提交的内容不符合要求，请检查后重试',
  429: '操作太频繁，请稍后再试',
}

/** 后端文案是否已经是给人看的中文（含中日韩字符） */
function looksHuman(text: string): boolean {
  return /[\u4e00-\u9fa5]/.test(text) && !/^\s*HTTP\s*\d+/i.test(text)
}

function textByStatus(status: number): string {
  const known = STATUS_TEXT[status]
  if (known) return known
  if (status >= 500) return '服务暂时不可用，请稍后重试'
  return '请求失败，请稍后重试'
}

/** 识别 axios / 浏览器抛出的技术性文案 */
function fromTechnicalMessage(message: string): string | null {
  const statusMatch = /status code (\d{3})/i.exec(message)
  if (statusMatch) return textByStatus(Number(statusMatch[1]))
  if (/network error|failed to fetch|networkerror/i.test(message)) {
    return '网络连接失败，请检查网络后重试'
  }
  if (/timeout/i.test(message)) return '请求超时，请稍后重试'
  if (/abort/i.test(message)) return '请求已取消'
  return null
}

/**
 * 从未知异常里取一条可以直接展示给用户的文案。
 *
 * 统一入口的意义：API 层已经把后端错误收敛成 ApiError（Error 子类），
 * 但 localStorage、DOM、第三方库抛出的东西类型并不固定。
 *
 * 原则：**技术细节不出现在界面上**。
 * - 后端给了中文说明 → 直接用（它对用户更有信息量）
 * - 否则按状态码翻译成一句人话
 * - axios 的 "Request failed with status code 502" 之类一律不透传
 */
export function errorText(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    const detail = error.detail?.trim()
    if (detail && looksHuman(detail)) return detail
    if (error.status > 0) return textByStatus(error.status)
    const technical = fromTechnicalMessage(error.message)
    if (technical) return technical
    return fallback
  }

  if (error instanceof Error && error.message.trim()) {
    const technical = fromTechnicalMessage(error.message)
    if (technical) return technical
    // 纯英文/代码风格的消息对用户没有意义，交给 fallback
    if (!looksHuman(error.message) && /^[a-zA-Z0-9\s.,:;'"()[\]{}<>/\\_-]+$/.test(error.message)) {
      return fallback
    }
    return error.message
  }

  if (typeof error === 'string' && error.trim()) {
    return fromTechnicalMessage(error) ?? error
  }

  if (typeof error === 'object' && error !== null && 'message' in error) {
    const message = (error as { message?: unknown }).message
    if (typeof message === 'string' && message.trim()) {
      return fromTechnicalMessage(message) ?? fallback
    }
  }

  return fallback
}
