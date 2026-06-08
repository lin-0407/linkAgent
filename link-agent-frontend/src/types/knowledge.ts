// 案例库（跨分区视频案例）前端类型，对应后端 /api/knowledge/reference-videos 系列接口。
// 单独成文件而非塞进 creator.ts：案例库是跨创作任务的独立域，类型与创作任务无耦合。

export type ReferenceVideoTier = 'BENCHMARK' | 'COMPETITOR' | 'OWN_HISTORY'

// 「输入 BV → 后端调脚本采集 → 自动清洗导入」一键接口的入参。tier / category 可选。
export type ReferenceVideoFetchImportPayload = {
  bvInput: string
  tier?: string
  category?: string
}

// 导入结果：收到 / 实际入库 / 按 BV 去重跳过 的条数。
export type ReferenceVideoImportResult = {
  receivedCount: number
  importedCount: number
  skippedCount: number
  source: string
  tier: string
}

// 案例列表项，字段顺序与后端 ReferenceVideoResponse 一致；可空字段用 | null 标注。
export type ReferenceVideo = {
  id: number
  videoId: string
  bvId: string | null
  tier: string
  category: string | null
  title: string
  description: string | null
  // tags 在后端是序列化后的 JSON 字符串，前端暂不解析展示。
  tags: string | null
  viewCount: number | null
  likeCount: number | null
  coinCount: number | null
  favoriteCount: number | null
  danmakuCount: number | null
  replyCount: number | null
  highlightSummary: string | null
  qualityScore: number | null
  source: string
  publishTimeText: string | null
  embeddingStatus: string | null
  createTime: string | null
  updateTime: string | null
}

export type ReferenceVideoPage = {
  items: ReferenceVideo[]
  total: number
  page: number
  size: number
}

export type ReferenceVideoListQuery = {
  category?: string
  tier?: string
  page?: number
  size?: number
}

// 向量索引状态，对应后端 GET /index/status。RAG 关闭时 ragEnabled/vectorStoreReady 均为 false。
export type ReferenceVideoIndexStatus = {
  ragEnabled: boolean
  vectorStoreReady: boolean
  totalCount: number
  indexedCount: number
  pendingCount: number
  failedCount: number
  lastIndexedAt: string | null
  retrievalMode: string
}

// 重建索引结果，对应后端 POST /index/rebuild。部分批次失败不报错，写进 failedCount + warnings。
export type ReferenceVideoIndexResult = {
  ragEnabled: boolean
  vectorStoreReady: boolean
  requestedCount: number
  indexedCount: number
  skippedCount: number
  failedCount: number
  warnings: string[]
  createTime: string
}

// 案例检索入参（5.2a；5.2b 增补 strategy）：query 必填，tier / category / strategy 可选；topK 用后端默认。
export type ReferenceVideoSearchPayload = {
  query: string
  tier?: string
  category?: string
  // 查询增强策略（5.2b，可选）：NONE/REWRITE/HYDE/MULTI_QUERY；为空走后端配置默认（默认 REWRITE）。
  strategy?: string
}

// 单条召回证据（5.2c-2）：small-to-big 中命中的优质评论 / 弹幕原文。
export type ReferenceVideoEvidenceItem = {
  itemId: string
  content: string
  sentiment: string
  sourceType: string
}

// 按 videoId 分组的召回证据（5.2c-2，方案 a）：这张案例卡片是被哪几条子条目召回的。
export type ReferenceVideoEvidence = {
  videoId: string
  items: ReferenceVideoEvidenceItem[]
}

// 案例检索响应（5.2a；5.2b 增补 strategy / enhancedQueries；5.2c-2 增补 evidence；5.2e 增补 reranked）：
// mode 回显实际检索模式；strategy 为实际生效策略（SQL 路径为 NONE）；enhancedQueries 为实际用于向量检索的扩展查询（NONE / SQL 为空）；
// items 复用案例卡片类型；evidence 为 small-to-big 命中的子条目证据（按 videoId 分组，无子召回 / SQL 路径为空）；
// reranked 表示本次结果是否经 qwen3-rerank 精排（关闭 / 失败 / SQL 降级为 false）。
export type ReferenceVideoSearchResult = {
  mode: string
  strategy: string
  enhancedQueries: string[]
  items: ReferenceVideo[]
  evidence: ReferenceVideoEvidence[]
  reranked: boolean
}
