import type {
  ReferenceVideoAnalysisContext,
  ReferenceVideoFetchImportPayload,
  ReferenceVideoImportResult,
  ReferenceVideoIndexResult,
  ReferenceVideoIndexStatus,
  ReferenceVideoListQuery,
  ReferenceVideoPage,
  ReferenceVideoSearchPayload,
  ReferenceVideoSearchResult,
  ReferenceVideoTopicSearchPayload,
  ReferenceVideoTopicSearchResult,
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

// 查询主题中块向量索引状态。topic-search 先查这一层；只看父表“已索引”不能代表主题检索可用。
export function getReferenceVideoChunkIndexStatus() {
  return requestJson<ReferenceVideoIndexStatus>('/api/knowledge/reference-videos/index/chunks/status')
}

// 触发（增量）重建主题中块索引；会先为历史案例补齐中块，再写入中块集合。
export function rebuildReferenceVideoChunkIndex(maxItems?: number) {
  const body: Record<string, number> = {}
  if (maxItems != null) {
    body.maxItems = maxItems
  }
  return requestJson<ReferenceVideoIndexResult>('/api/knowledge/reference-videos/index/chunks/rebuild', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

// 查询子条目向量索引状态（5.2c-1）。与父索引状态同形，复用 ReferenceVideoIndexStatus；RAG 关闭时也正常返回。
export function getReferenceVideoItemIndexStatus() {
  return requestJson<ReferenceVideoIndexStatus>('/api/knowledge/reference-videos/index/items/status')
}

// 触发（增量）重建子条目向量索引（5.2c-1，small-to-big 的 small 端）。maxItems 不传则用后端配置默认值。
export function rebuildReferenceVideoItemIndex(maxItems?: number) {
  const body: Record<string, number> = {}
  if (maxItems != null) {
    body.maxItems = maxItems
  }
  return requestJson<ReferenceVideoIndexResult>('/api/knowledge/reference-videos/index/items/rebuild', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

// 查询原生 hybrid 索引状态（5.2d-1）。复用同形 ReferenceVideoIndexStatus；RAG/hybrid 关闭时也正常返回。
export function getReferenceVideoHybridIndexStatus() {
  return requestJson<ReferenceVideoIndexStatus>('/api/knowledge/reference-videos/index/hybrid/status')
}

// 触发（整库重灌）原生 hybrid 索引（5.2d-1，dense+BM25）。maxItems 不传则用后端配置默认值。
export function rebuildReferenceVideoHybridIndex(maxItems?: number) {
  const body: Record<string, number> = {}
  if (maxItems != null) {
    body.maxItems = maxItems
  }
  return requestJson<ReferenceVideoIndexResult>('/api/knowledge/reference-videos/index/hybrid/rebuild', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

// 查询子条目原生 hybrid 索引状态（5.2d-3）。复用同形 ReferenceVideoIndexStatus；RAG/hybrid 关闭时也正常返回。
export function getReferenceVideoItemHybridIndexStatus() {
  return requestJson<ReferenceVideoIndexStatus>('/api/knowledge/reference-videos/index/hybrid/items/status')
}

// 触发（整库重灌）子条目原生 hybrid 索引（5.2d-3，dense+BM25）。maxItems 不传则用后端配置默认值。
export function rebuildReferenceVideoItemHybridIndex(maxItems?: number) {
  const body: Record<string, number> = {}
  if (maxItems != null) {
    body.maxItems = maxItems
  }
  return requestJson<ReferenceVideoIndexResult>('/api/knowledge/reference-videos/index/hybrid/items/rebuild', {
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

// 主题优先检索：先召回主题中块，再由后端按视频质量分分页返回卡片。
// page/size 只在前端做批次控制，真正的 top20 截断规则由后端统一兜底。
export function topicSearchReferenceVideos(payload: ReferenceVideoTopicSearchPayload) {
  const body: Record<string, string | number> = { query: payload.query.trim() }
  const tier = payload.tier?.trim()
  const category = payload.category?.trim()
  const strategy = payload.strategy?.trim()
  if (tier) {
    body.tier = tier
  }
  if (category) {
    body.category = category
  }
  if (payload.page != null) {
    body.page = payload.page
  }
  if (payload.size != null) {
    body.size = payload.size
  }
  if (strategy) {
    body.strategy = strategy
  }
  return requestJson<ReferenceVideoTopicSearchResult>('/api/knowledge/reference-videos/topic-search', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

// 点击某张视频卡片后加载 MySQL 事实源上下文，不要求用户再手动打开 RAG。
export function getReferenceVideoAnalysisContext(videoId: string) {
  return requestJson<ReferenceVideoAnalysisContext>(
    `/api/knowledge/reference-videos/${encodeURIComponent(videoId)}/analysis-context`,
  )
}
