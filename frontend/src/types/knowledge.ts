// 案例库（跨分区视频案例）前端类型，对应后端 /api/knowledge/reference-videos 系列接口。
// 单独成文件而非塞进 creator.ts：案例库是跨创作任务的独立域，类型与创作任务无耦合。

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
  // rawQualityScore 是后端用于小样本兜底排序的单视频原始分，不直接当成 0-100 展示。
  rawQualityScore: number | null
  qualityScore: number | null
  qualitySampleCount: number
  qualityScoreReliable: boolean
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

// 主题优先检索入参：page 表示“第几批卡片”，后端限制 1-4，每批最多 5 张。
export type ReferenceVideoTopicSearchPayload = {
  query: string
  tier?: string
  category?: string
  page?: number
  size?: number
  strategy?: string
}

// 单条召回证据（5.2c-2）：small-to-big 中命中的优质评论 / 弹幕原文。
export type ReferenceVideoEvidenceItem = {
  itemId: string
  content: string
  sentiment: string
  sourceType: string
}

// 主题中块命中摘要：前端用它解释卡片为什么被召回，真正的视频排序仍按质量信号走 MySQL。
export type ReferenceVideoMatchedTopic = {
  chunkId: string
  videoId: string
  chunkType: string
  chunkTitle: string
  preview: string
}

// 按 videoId 分组的召回证据（5.2c-2，方案 a）：这张案例卡片是被哪几条子条目召回的。
export type ReferenceVideoEvidence = {
  videoId: string
  items: ReferenceVideoEvidenceItem[]
}

// 主题优先检索响应：cards 是当前批次要展示的视频卡片，matchedTopics/evidence 解释为什么命中和排序。
// reranked 表示后端是否对 top20 候选做过 qwen3-rerank 精排。
export type ReferenceVideoTopicSearchResult = {
  mode: string
  strategy: string
  enhancedQueries: string[]
  page: number
  size: number
  maxPage: number
  hasMore: boolean
  matchedTopics: ReferenceVideoMatchedTopic[]
  evidence: ReferenceVideoEvidence[]
  cards: ReferenceVideo[]
  reranked: boolean
}

// 单视频分析上下文：点击卡片后加载进 AI 交互台，让后续追问围绕这个视频的主题和观众反馈展开。
export type ReferenceVideoAnalysisContext = {
  video: ReferenceVideo
  topics: ReferenceVideoMatchedTopic[]
  evidenceItems: ReferenceVideoEvidenceItem[]
}
