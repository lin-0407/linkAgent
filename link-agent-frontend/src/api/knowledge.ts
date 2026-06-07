import type {
  ReferenceVideoFetchImportPayload,
  ReferenceVideoImportResult,
  ReferenceVideoIndexResult,
  ReferenceVideoIndexStatus,
  ReferenceVideoListQuery,
  ReferenceVideoPage,
  ReferenceVideoSearchPayload,
  ReferenceVideoSearchResult,
} from '@/types/knowledge'

// 案例库接口封装。requestJson / readErrorMessage 与 api/creator.ts 同款：
// creator.ts 里它们是模块私有、未导出，这里照其约定自带一份，保持两个域的 API 客户端各自独立、互不牵连。

async function requestJson<T>(url: string, options?: RequestInit) {
  const response = await fetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...options?.headers,
    },
  })

  if (!response.ok) {
    const message = await readErrorMessage(response)
    throw new Error(message || `HTTP ${response.status}`)
  }

  return (await response.json()) as T
}

async function readErrorMessage(response: Response) {
  try {
    const data = (await response.json()) as { message?: string; error?: string; detail?: string }
    return data.message || data.detail || data.error
  } catch {
    return ''
  }
}

// 输入 BV → 后端显式调用采集脚本 → 自动清洗导入。空的 tier / category 不下发，交给后端按默认兜底。
export function fetchImportReferenceVideo(payload: ReferenceVideoFetchImportPayload) {
  const body: Record<string, string> = { bvInput: payload.bvInput.trim() }
  const tier = payload.tier?.trim()
  const category = payload.category?.trim()
  if (tier) {
    body.tier = tier
  }
  if (category) {
    body.category = category
  }
  return requestJson<ReferenceVideoImportResult>('/api/knowledge/reference-videos/fetch-import', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function listReferenceVideos(query: ReferenceVideoListQuery = {}) {
  const params = new URLSearchParams()
  const category = query.category?.trim()
  const tier = query.tier?.trim()
  if (category) {
    params.set('category', category)
  }
  if (tier) {
    params.set('tier', tier)
  }
  params.set('page', String(query.page ?? 1))
  params.set('size', String(query.size ?? 20))
  return requestJson<ReferenceVideoPage>(`/api/knowledge/reference-videos?${params.toString()}`)
}

// 查询向量索引状态。RAG 关闭时也正常返回，用于前端展示当前检索模式与各状态计数。
export function getReferenceVideoIndexStatus() {
  return requestJson<ReferenceVideoIndexStatus>('/api/knowledge/reference-videos/index/status')
}

// 触发（增量）重建向量索引。maxItems 不传则用后端配置默认值。
export function rebuildReferenceVideoIndex(maxItems?: number) {
  const body: Record<string, number> = {}
  if (maxItems != null) {
    body.maxItems = maxItems
  }
  return requestJson<ReferenceVideoIndexResult>('/api/knowledge/reference-videos/index/rebuild', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

// 案例检索：query 必填；空的 tier / category / strategy 不下发，交给后端按「不过滤 / 配置默认」处理（与 fetchImport 同约定）。
export function searchReferenceVideos(payload: ReferenceVideoSearchPayload) {
  const body: Record<string, string> = { query: payload.query.trim() }
  const tier = payload.tier?.trim()
  const category = payload.category?.trim()
  const strategy = payload.strategy?.trim()
  if (tier) {
    body.tier = tier
  }
  if (category) {
    body.category = category
  }
  if (strategy) {
    body.strategy = strategy
  }
  return requestJson<ReferenceVideoSearchResult>('/api/knowledge/reference-videos/search', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}
