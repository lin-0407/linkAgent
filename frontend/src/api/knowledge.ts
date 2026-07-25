import type {
  ReferenceVideoAnalysisContext,
  ReferenceVideoFetchImportPayload,
  ReferenceVideoImportResult,
  ReferenceVideoIndexResult,
  ReferenceVideoIndexStatus,
  ReferenceVideoListQuery,
  ReferenceVideoPage,
  ReferenceVideoTopicSearchPayload,
  ReferenceVideoTopicSearchResult,
} from '@/types/knowledge'
import { get, post } from './http'

// ── 案例库 CRUD ──

/** 输入 BV → 后端显式调用采集脚本 → 自动清洗导入。空的 tier / category 不下发，交给后端按默认兜底。 */
export function fetchImportReferenceVideo(payload: ReferenceVideoFetchImportPayload) {
  const body: Record<string, string> = { bvInput: payload.bvInput.trim() }
  const tier = payload.tier?.trim()
  const category = payload.category?.trim()
  if (tier) body.tier = tier
  if (category) body.category = category
  return post<ReferenceVideoImportResult>('/knowledge/reference-videos/fetch-import', body)
}

export function listReferenceVideos(query: ReferenceVideoListQuery = {}) {
  return get<ReferenceVideoPage>('/knowledge/reference-videos', {
    params: {
      category: query.category?.trim() || undefined,
      tier: query.tier?.trim() || undefined,
      page: query.page ?? 1,
      size: query.size ?? 20,
    },
  })
}

// ── 向量索引状态 & 重建 ──

/** 查询向量索引状态。RAG 关闭时也正常返回，用于前端展示当前检索模式与各状态计数。 */
export function getReferenceVideoIndexStatus() {
  return get<ReferenceVideoIndexStatus>('/knowledge/reference-videos/index/status')
}

/** 触发（增量）重建向量索引。maxItems 不传则用后端配置默认值。 */
export function rebuildReferenceVideoIndex(maxItems?: number) {
  const body: Record<string, number> = {}
  if (maxItems != null) body.maxItems = maxItems
  return post<ReferenceVideoIndexResult>('/knowledge/reference-videos/index/rebuild', body)
}

/** 查询主题中块向量索引状态。topic-search 先查这一层；只看父表"已索引"不能代表主题检索可用。 */
export function getReferenceVideoChunkIndexStatus() {
  return get<ReferenceVideoIndexStatus>('/knowledge/reference-videos/index/chunks/status')
}

/** 触发（增量）重建主题中块索引；会先为历史案例补齐中块，再写入中块集合。 */
export function rebuildReferenceVideoChunkIndex(maxItems?: number) {
  const body: Record<string, number> = {}
  if (maxItems != null) body.maxItems = maxItems
  return post<ReferenceVideoIndexResult>('/knowledge/reference-videos/index/chunks/rebuild', body)
}

/** 查询子条目向量索引状态（5.2c-1）。与父索引状态同形，复用 ReferenceVideoIndexStatus；RAG 关闭时也正常返回。 */
export function getReferenceVideoItemIndexStatus() {
  return get<ReferenceVideoIndexStatus>('/knowledge/reference-videos/index/items/status')
}

/** 触发（增量）重建子条目向量索引（5.2c-1，small-to-big 的 small 端）。maxItems 不传则用后端配置默认值。 */
export function rebuildReferenceVideoItemIndex(maxItems?: number) {
  const body: Record<string, number> = {}
  if (maxItems != null) body.maxItems = maxItems
  return post<ReferenceVideoIndexResult>('/knowledge/reference-videos/index/items/rebuild', body)
}

/** 查询原生 hybrid 索引状态（5.2d-1）。复用同形 ReferenceVideoIndexStatus；RAG/hybrid 关闭时也正常返回。 */
export function getReferenceVideoHybridIndexStatus() {
  return get<ReferenceVideoIndexStatus>('/knowledge/reference-videos/index/hybrid/status')
}

/** 触发（整库重灌）原生 hybrid 索引（5.2d-1，dense+BM25）。maxItems 不传则用后端配置默认值。 */
export function rebuildReferenceVideoHybridIndex(maxItems?: number) {
  const body: Record<string, number> = {}
  if (maxItems != null) body.maxItems = maxItems
  return post<ReferenceVideoIndexResult>('/knowledge/reference-videos/index/hybrid/rebuild', body)
}

/** 查询子条目原生 hybrid 索引状态（5.2d-3）。复用同形 ReferenceVideoIndexStatus；RAG/hybrid 关闭时也正常返回。 */
export function getReferenceVideoItemHybridIndexStatus() {
  return get<ReferenceVideoIndexStatus>('/knowledge/reference-videos/index/hybrid/items/status')
}

/** 触发（整库重灌）子条目原生 hybrid 索引（5.2d-3，dense+BM25）。maxItems 不传则用后端配置默认值。 */
export function rebuildReferenceVideoItemHybridIndex(maxItems?: number) {
  const body: Record<string, number> = {}
  if (maxItems != null) body.maxItems = maxItems
  return post<ReferenceVideoIndexResult>('/knowledge/reference-videos/index/hybrid/items/rebuild', body)
}

// ── 检索 ──

/** 主题优先检索：先召回主题中块，再由后端按视频质量信号分页返回卡片。 */
export function topicSearchReferenceVideos(payload: ReferenceVideoTopicSearchPayload) {
  const body: Record<string, string | number> = { query: payload.query.trim() }
  const tier = payload.tier?.trim()
  const category = payload.category?.trim()
  const strategy = payload.strategy?.trim()
  if (tier) body.tier = tier
  if (category) body.category = category
  if (payload.page != null) body.page = payload.page
  if (payload.size != null) body.size = payload.size
  if (strategy) body.strategy = strategy
  return post<ReferenceVideoTopicSearchResult>('/knowledge/reference-videos/topic-search', body)
}

/** 点击某张视频卡片后加载 MySQL 事实源上下文，不要求用户再手动打开 RAG。 */
export function getReferenceVideoAnalysisContext(videoId: string) {
  return get<ReferenceVideoAnalysisContext>(
    `/knowledge/reference-videos/${encodeURIComponent(videoId)}/analysis-context`,
  )
}
