export type CreatorMaterial = {
  id: number
  materialType: string
  content: string
  createTime: string
  updateTime: string
}

export type CreatorMaterialType = 'TITLE_DRAFT' | 'DESCRIPTION_DRAFT' | 'MANUSCRIPT' | 'SUBTITLE'

export type CreatorTask = {
  id: number
  taskId: string
  userId: string
  taskName: string
  videoType: string
  status: string
  createTime: string
  updateTime: string
  materials: CreatorMaterial[]
}

export type CreatorTaskSummary = {
  id: number
  taskId: string
  userId: string
  taskName: string
  videoType: string
  status: string
  materialCount: number
  createTime: string
  updateTime: string
}

export type CreatorTaskCreatePayload = {
  userId?: string
  taskName?: string
  videoType?: string
  titleDraft?: string
  descriptionDraft?: string
  manuscript?: string
  subtitle?: string
}

export type CreatorTaskUpdatePayload = {
  taskName?: string
  videoType?: string
  titleDraft?: string
  descriptionDraft?: string
  manuscript?: string
  subtitle?: string
}

export type CreativeIdeaOption = {
  id: number
  optionId: string
  sessionId: string
  taskId: string
  optionName: string
  targetAudience: string | null
  titleOutline: string | null
  contentOutline: string | null
  descriptionOutline: string | null
  sellingPoints: string | null
  riskPoints: string | null
  recommendReason: string | null
  selected: boolean
  createTime: string
  updateTime: string
}

export type InteractiveTask = {
  taskId: string
  sessionId: string
  userId: string
  idea: string
  videoType: string
  status: string
  selectedOptionId: string | null
  parseStatus: string
  /** 用户上传的补充背景资料（从文档中提取的纯文本） */
  backgroundContext: string | null
  /** AI 对创作者想法的理解摘要 */
  understandingSummary: string | null
  /** 理解确认状态：NONE / UNDERSTANDING / READY / CONFIRMED */
  understandingStatus: string
  createTime: string
  updateTime: string
  options: CreativeIdeaOption[]
}

export type InteractiveTaskCreatePayload = {
  userId?: string
  idea: string
  videoType?: string
}

export type CreativeOptionsRegeneratePayload = {
  extraRequirement?: string
}

export type CreatorWorkflowStage = 'PRE_PUBLISH' | 'FEEDBACK' | 'REPORT'

export type CreatorEvalCase = {
  id: number
  caseId: string
  userId: string
  caseName: string
  targetStage: CreatorWorkflowStage
  taskId: string | null
  inputSnapshot: string
  expectedPoints: string | null
  scoringRubric: string | null
  status: string
  createTime: string
  updateTime: string
}

export type CreatorEvalResult = {
  id: number
  resultId: string
  caseId: string
  taskId: string | null
  workflowSessionId: string | null
  targetStage: CreatorWorkflowStage
  modelName: string | null
  promptVersion: string | null
  promptHash: string | null
  promptSnapshot: string | null
  outputSummary: string | null
  rawOutput: string
  runStatus: string
  parseStatus: string
  elapsedMs: number | null
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  failureReason: string | null
  readabilityScore: number | null
  relevanceScore: number | null
  completenessScore: number | null
  accuracyScore: number | null
  stabilityScore: number | null
  costScore: number | null
  explainabilityScore: number | null
  reviewerNote: string | null
  createTime: string
  updateTime: string
}

export type CreatorEvalPromptVersionStats = {
  caseId: string
  promptVersion: string
  latestPromptHash: string | null
  resultCount: number
  successCount: number
  successRatePercent: number | null
  scoreSampleCount: number
  averageScore: number | null
  scoreStandardDeviation: number | null
  averageReadabilityScore: number | null
  averageRelevanceScore: number | null
  averageCompletenessScore: number | null
  averageAccuracyScore: number | null
  averageStabilityScore: number | null
  averageCostScore: number | null
  averageExplainabilityScore: number | null
  totalPromptTokens: number | null
  totalCompletionTokens: number | null
  totalTokens: number | null
  averagePromptTokens: number | null
  averageCompletionTokens: number | null
  averageTotalTokens: number | null
  averageElapsedMs: number | null
  fullScoreCoverageRatePercent: number | null
  latestUpdateTime: string | null
}

export type CreatorEvalResultPayload = {
  taskId?: string
  workflowSessionId?: string
  targetStage: CreatorWorkflowStage
  modelName?: string
  promptVersion?: string
  promptHash?: string
  promptSnapshot?: string
  outputSummary?: string
  rawOutput?: string
  elapsedMs?: number
  promptTokens?: number
  completionTokens?: number
  totalTokens?: number
  failureReason?: string
  readabilityScore?: number
  relevanceScore?: number
  completenessScore?: number
  accuracyScore?: number
  stabilityScore?: number
  costScore?: number
  explainabilityScore?: number
  reviewerNote?: string
}

export type PrePublishAnalyzePayload = {
  customGuidance?: string
  creatorPreference?: string
  titleStyle?: string
  extraRequirement?: string
  preferenceMode?: CreatorPreferenceMode
  /** 分析策略：后端 P0-3 新增字段，用于切换 LLM 分析口径（GENERAL/TUTORIAL/VLOG/REVIEW/COMMENTARY） */
  analysisStrategy?: string
}

export type PrePublishDraftPayload = {
  extraRequirement?: string
}

export type PrePublishDraftResult = {
  taskId: string
  sessionId: string
  materialType: CreatorMaterialType
  content: string
  message: CreatorWorkflowMessage
}

export type CreatorPreferenceMode = 'USE_HISTORY' | 'IGNORE_HISTORY' | 'EXPERIMENT'

export type CreatorPreference = {
  id: number
  preferenceId: string
  userId: string
  sourceTaskId: string
  sourceReportId: string
  preferenceContent: string
  createTime: string
  updateTime: string
}

export type CreatorContextTermType =
  | 'KEYWORD'
  | 'SLANG'
  | 'MEME'
  | 'TABOO'
  | 'TITLE_PATTERN'
  | 'AUDIENCE_CONCERN'

export type CreatorContextPolarity = 'POSITIVE' | 'NEGATIVE' | 'NEUTRAL'

export type CreatorContextSourceType =
  | 'USER_SAVE'
  | 'AI_ACCEPTED'
  | 'COMMENT_EXTRACTED'
  | 'USER_REJECTED'
  | 'VIDEO_SUCCESS'

export type CreatorContextTerm = {
  id: number
  termId: string
  userId: string
  videoType: string
  term: string
  termType: CreatorContextTermType
  polarity: CreatorContextPolarity
  sourceType: CreatorContextSourceType
  sourceTaskId: string | null
  evidenceText: string | null
  weight: number
  usageCount: number
  acceptCount: number
  rejectCount: number
  enabled: boolean
  createTime: string
  updateTime: string
}

export type CreatorContextTermPayload = {
  userId?: string
  videoType: string
  term: string
  termType?: CreatorContextTermType
  polarity?: CreatorContextPolarity
  sourceType?: CreatorContextSourceType
  sourceTaskId?: string
  evidenceText?: string
}

export type CreatorContextBundle = {
  userId: string
  videoType: string
  scene: string
  terms: CreatorContextTerm[]
  keywords: string[]
  slangTerms: string[]
  titlePatterns: string[]
  audienceConcerns: string[]
  tabooTerms: string[]
  promptContext: string
}

export type CreatorSuggestion = {
  id: number
  suggestionId: string
  taskId: string
  contentSummary: string | null
  creatorDilemma: string | null
  audienceProfile: string | null
  audienceHook: string | null
  contentPositioning: string | null
  sellingPoints: string | null
  riskPoints: string | null
  titleSuggestions: string | null
  descriptionSuggestion: string | null
  actionableRevisionPlan: string | null
  tagSuggestions: string | null
  partitionSuggestion: string | null
  evidenceRefs: string | null
  missingInfo: string | null
  generationMode: string | null
  qualityStatus: string | null
  auditReport: string | null
  rawOutput: string
  parseStatus: string
  createTime: string
  updateTime: string
}

export type CreatorWorkflowStatus =
  | 'CREATED'
  | 'CONTEXT_LOADING'
  | 'WAITING_USER_INPUT'
  | 'RUNNING'
  | 'WAITING_CONFIRMATION'
  | 'CONFIRMED'
  | 'FAILED'
  | 'CANCELLED'

export type CreatorWorkflowMessageRole = 'SYSTEM' | 'USER' | 'AGENT' | 'TOOL' | 'RESULT'

export type CreatorWorkflowMessageContentType =
  | 'TEXT'
  | 'MATERIAL_SUMMARY'
  | 'RESULT_CARD'
  | 'ERROR'

export type CreatorWorkflowMessage = {
  id: number
  messageId: string
  sessionId: string
  role: CreatorWorkflowMessageRole
  content: string
  contentType: CreatorWorkflowMessageContentType
  detailRefType: string | null
  detailRefId: string | null
  sequenceNo: number
  createTime: string
}

export type CreatorWorkflowStep = {
  id: number
  stepId: string
  sessionId: string
  stepType: string
  stepName: string
  status: string
  inputSummary: string | null
  outputSummary: string | null
  rawOutput: string | null
  errorMessage: string | null
  startTime: string | null
  endTime: string | null
  createTime: string
}

export type CreatorWorkflowSession = {
  id: number
  sessionId: string
  taskId: string
  stage: CreatorWorkflowStage
  status: CreatorWorkflowStatus
  userId: string
  confirmedResultId: string | null
  errorMessage: string | null
  createTime: string
  updateTime: string
  messages: CreatorWorkflowMessage[]
}

export type CreatorWorkflowStartPayload = {
  userId?: string
  resumeLatest?: boolean
}

export type CreatorWorkflowMessagePayload = {
  content: string
}

export type CreatorWorkflowConfirmPayload = {
  suggestionId: string
}

export type CreatorWorkflowEvent = {
  eventId: string
  sessionId: string
  taskId: string
  eventType: string
  sequenceNo: number | null
  payload: unknown
  createTime: string
}

export type CreatorFeedbackSavePayload = {
  commentSamples?: string
  danmakuSamples?: string
  extraContext?: string
}

export type CreatorFeedbackAnalyzePayload = {
  customGuidance?: string
  analysisFocus?: string
  extraRequirement?: string
}

export type CreatorFeedbackChatPayload = {
  question: string
}

export type CreatorFeedback = {
  id: number
  feedbackId: string
  taskId: string
  commentSamples: string | null
  danmakuSamples: string | null
  extraContext: string | null
  createTime: string
  updateTime: string
}

export type CreatorFeedbackImportResult = {
  taskId: string
  commentCount: number
  danmakuCount: number
  metricImported: boolean
  warnings: string[]
}

export type CreatorFeedbackFetchPayload = {
  bvInput: string
  maxComments?: number
  maxRepliesPerComment?: number
  maxDanmaku?: number
  format?: 'json' | 'both'
}

export type CreatorFeedbackFetchResult = CreatorFeedbackImportResult & {
  bvid: string
  outputDirectory: string
  outputFiles: string[]
}

export type CreatorFeedbackStat = {
  name: string
  label: string
  count: number
}

export type CreatorFeedbackKeyword = {
  keyword: string
  count: number
}

export type CreatorFeedbackTimelinePoint = {
  timeBucket: string
  count: number
}

export type CreatorFeedbackMetric = {
  metricId: string
  viewCount: number | null
  favoriteCount: number | null
  coinCount: number | null
  likeCount: number | null
  shareCount: number | null
  source: string | null
  createTime: string
}

export type CreatorFeedbackItem = {
  itemId: string
  sourceType: string
  sourceLabel: string
  content: string
  occurTimeText: string | null
  likeCount: number | null
  replyCount: number | null
  category: string
  categoryLabel: string
  sentiment: string
  sentimentLabel: string
  noise: boolean
  reason: string | null
  createTime: string
}

export type CreatorFeedbackDashboard = {
  taskId: string
  commentCount: number
  danmakuCount: number
  noiseCount: number
  metric: CreatorFeedbackMetric | null
  commentCategoryStats: CreatorFeedbackStat[]
  danmakuCategoryStats: CreatorFeedbackStat[]
  sentimentStats: CreatorFeedbackStat[]
  keywords: CreatorFeedbackKeyword[]
  danmakuTimeline: CreatorFeedbackTimelinePoint[]
  topCommentItems: CreatorFeedbackItem[]
  recentItems: CreatorFeedbackItem[]
  warnings: string[]
}

export type CreatorFeedbackReport = {
  id: number
  reportId: string
  taskId: string
  feedbackSummary: string | null
  hotTopics: string | null
  sentimentSummary: string | null
  controversyPoints: string | null
  misunderstandingPoints: string | null
  nextContentSuggestions: string | null
  interactionSuggestions: string | null
  creatorFeedbackDilemma: string | null
  audienceCoreConcern: string | null
  misunderstandingSourceAnalysis: string | null
  feedbackActionPlan: string | null
  rawOutput: string
  parseStatus: string
  createTime: string
  updateTime: string
}

export type CreatorFeedbackChatResult = {
  taskId: string
  question: string
  answer: string
  evidenceItems: CreatorFeedbackItem[]
  reportUsed: boolean
  retrievalMode: string
  ragEnabled: boolean
  modelName: string | null
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  elapsedMs: number | null
  createTime: string
}

// 阶段 4.13：重建证据索引入参。两个字段都可空，为空时后端回落到 creator.feedback.rag 配置默认值。
export type CreatorFeedbackEvidenceIndexPayload = {
  maxItems?: number | null
  includeNoise?: boolean | null
}

export type CreatorFeedbackEvidenceIndexResult = {
  taskId: string
  ragEnabled: boolean
  vectorStoreReady: boolean
  requestedCount: number
  indexedCount: number
  skippedCount: number
  failedCount: number
  warnings: string[]
  createTime: string
}

// 证据索引状态：ragEnabled 区分“业务开关没开”，vectorStoreReady 区分“Milvus 基础设施没就绪”。
export type CreatorFeedbackEvidenceIndexStatus = {
  taskId: string
  ragEnabled: boolean
  vectorStoreReady: boolean
  totalItems: number
  indexedCount: number
  pendingCount: number
  failedCount: number
  lastIndexedAt: string | null
  retrievalMode: string
}

export type LlmApiModelCategory = 'TEXT' | 'EMBEDDING' | 'RERANK'

export type LlmApiCallStatus = 'SUCCESS' | 'FAILED' | 'SKIPPED'

export type LlmApiUsageCategorySummary = {
  modelCategory: LlmApiModelCategory
  callCount: number
  successCount: number
  failedCount: number
  skippedCount: number
  totalTokens: number | null
  promptTokens: number | null
  completionTokens: number | null
  totalElapsedMs: number | null
  averageElapsedMs: number | null
}

export type LlmApiUsageSummary = {
  taskId: string
  callCount: number
  successCount: number
  failedCount: number
  skippedCount: number
  totalTokens: number | null
  totalElapsedMs: number | null
  averageElapsedMs: number | null
  categories: LlmApiUsageCategorySummary[]
}

export type LlmApiCallRecord = {
  id: number
  callId: string
  taskId: string | null
  traceId: string | null
  requestId: string | null
  workflowSessionId: string | null
  workflowStepId: string | null
  workflowStepName: string | null
  workflowStage: CreatorWorkflowStage | string | null
  modelCategory: LlmApiModelCategory
  scene: string | null
  modelName: string | null
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  elapsedMs: number | null
  status: LlmApiCallStatus
  errorMessage: string | null
  inputCount: number | null
  createTime: string
}

export type LlmApiCallPage = {
  taskId: string
  page: number
  pageSize: number
  total: number
  items: LlmApiCallRecord[]
}

export type WorkflowStepUsage = {
  stepId: string
  stepName: string
  stage: CreatorWorkflowStage | string | null
  calls: LlmApiCallRecord[]
}

export type WorkflowUsageResponse = {
  taskId: string
  sessionId: string
  totalCalls: number
  successCalls: number
  failedCalls: number
  skippedCalls: number
  totalTokens: number | null
  totalElapsedMs: number | null
  steps: WorkflowStepUsage[]
}

// ═══════════════════════════════════════════
// 创作者反馈事件（对接 POST /api/creator/profile/events → creator_event 表）
// ═══════════════════════════════════════════

/**
 * 创作者反馈事件类型。
 * 取值与后端 creator_event.event_type 注释保持一致，
 * 作为创作者画像增量更新的信号源。
 */
export type CreatorEventType =
  | 'TITLE_ACCEPTED' // 用户采纳某条标题建议
  | 'TITLE_REJECTED' // 用户拒绝某条标题建议（点了"不太好"）
  | 'TAG_ACCEPTED' // 用户采纳某条标签建议
  | 'TAG_REJECTED' // 用户拒绝某条标签建议
  | 'SUGGESTION_ADOPTED' // 用户采纳整组建议
  | 'SUGGESTION_REJECTED' // 用户拒绝整组建议
  | 'FEEDBACK_INSIGHT_SAVED' // 用户保存某条观众洞察

/**
 * 建议反馈的预设原因。
 * 点"不太好"后展开的面板里给用户选择，提交时随事件写入 payload.reason。
 * 用预设值而非自由文本，是为了让画像更新能稳定聚类用户的排斥倾向。
 */
export type CreatorRejectReason =
  | 'STYLE_MISMATCH' // 风格不符合我的定位
  | 'LENGTH_AWKWARD' // 太长或太短
  | 'TOO_CLICKBAIT' // 太夸张 / 震惊体
  | 'NOT_ATTRACTIVE' // 不够吸引人
  | 'OFF_TOPIC' // 偏离视频主题
  | 'OTHER' // 其他（附 reasonText 自定义说明）

/**
 * 创作者反馈事件提交载荷。
 * 后端约定：从 body 中取 userId / eventType / taskId 作为事件主字段，
 * 整个 body 作为 payload（JSON）落库，因此建议正文相关字段直接平铺在这里。
 */
export type CreatorFeedbackEventPayload = {
  userId: string
  eventType: CreatorEventType
  taskId?: string
  /** 建议的具体内容（标题原文、标签原文等），便于后续回溯是哪一条建议被反馈 */
  content?: string
  /** 建议的适用场景/标签等附加信息，按需带上 */
  scenario?: string
  /** 拒绝原因预设值，仅 REJECTED 类事件需要 */
  reason?: CreatorRejectReason
  /** 当 reason=OTHER 时，用户填写的自定义说明 */
  reasonText?: string
  /** 建议在结果列表中的序号（从 1 开始），用于分析"用户更偏好靠前还是靠后的建议" */
  rank?: number
}

/**
 * 创作台结果弹窗目标。
 * 主壳和子组件共享这个类型，是为了让弹窗入口保持同一组合法值。
 */
export type ResultModalTarget = 'prePublishSuggestion' | 'feedbackDashboard' | 'feedbackReport'

/**
 * 创作台一级步骤。
 * 持久化 activeStep 时复用同一类型，避免主壳和子组件各自维护 tab 名称。
 */
export type CreatorActiveStep = 'task' | 'prePublish' | 'feedback' | 'report' | 'usage' | 'videoAnalysis'

// ═══════════════════════════════════════════
// P0-3: B站账号绑定
// ═══════════════════════════════════════════

/** B站账号绑定信息，对应后端 creator_bilibili_account 表 */
export type BilibiliAccount = {
  accountId: string
  userId: string
  bilibiliUid: string
  nickname: string | null
  /** 绑定状态：ACTIVE=正常绑定，UNVERIFIED=UID未校验，SYNC_FAILED=同步失败 */
  bindStatus: string
  lastSyncTime: string | null
  lastSyncError: string | null
  createTime: string
  updateTime: string
}

/** B站账号绑定/更新请求 */
export type BindAccountPayload = {
  userId: string
  bilibiliUid: string
}

// ═══════════════════════════════════════════
// P0-3: B站视频缓存
// ═══════════════════════════════════════════

/** B站视频缓存信息，对应后端 creator_bilibili_video 表 + 任务关联信息 */
export type BilibiliVideo = {
  videoId: string
  bilibiliUid: string
  bvid: string
  aid: number | null
  title: string | null
  coverUrl: string | null
  publishTime: string | null
  viewCount: number | null
  likeCount: number | null
  coinCount: number | null
  favoriteCount: number | null
  shareCount: number | null
  /** 同步状态：SYNCED=已同步，STALE=数据过期，FAILED=同步失败 */
  syncStatus: string
  lastSyncTime: string | null
  /** 是否已和平台任务绑定 */
  hasTaskBinding: boolean
  /** 关联的任务ID，未绑定时为空 */
  taskId: string | null
  /** 关联的任务名称，未绑定时为空 */
  taskName: string | null
}

// ═══════════════════════════════════════════
// P0-3: 任务视频绑定
// ═══════════════════════════════════════════

/** 任务视频绑定信息，对应后端 creator_task_video_binding 表 */
export type TaskVideoBinding = {
  bindingId: string
  taskId: string
  userId: string
  bilibiliUid: string | null
  bvid: string
  /** 绑定状态：WAITING_VERIFY=等待校验，BOUND=已绑定校验通过，UID_MISMATCH=BV不属于该UID，VIDEO_NOT_FOUND=BV查不到视频 */
  bindingStatus: string
  verifyMessage: string | null
  createTime: string
  updateTime: string
}

/** BV绑定请求 */
export type BindBvPayload = {
  userId: string
  bilibiliUid: string
  bvid: string
}

// ═══════════════════════════════════════════
// P0-3: 视频同步结果
// ═══════════════════════════════════════════

/** B站视频同步结果 */
export type SyncVideosResult = {
  bilibiliUid: string
  syncedCount: number
  linkedCount: number
  anomalyCount: number
  lastError: string | null
  message?: string
}

// ═══════════════════════════════════════════
// P0-3: 视频分析报告（P0-4开始写入和读取）
// ═══════════════════════════════════════════

/** 视频分析报告，对应后端 creator_video_analysis_report 表 */
export type VideoAnalysisReport = {
  analysisId: string
  taskId: string
  bvid: string
  workflowSessionId: string | null
  /** 分析状态：PENDING=待分析，SYNCING=同步中，FETCHING=采集中，ANALYZING=分析中，COMPLETED=已完成，FAILED=失败 */
  analysisStatus: string
  oneSentenceSummary: string | null
  publishPlanReview: string | null
  audienceFocus: string | null
  misunderstandingPoints: string | null
  controversyPoints: string | null
  nextActionPlan: string | null
  evidenceSummary: string | null
  rawOutput: string | null
  parseStatus: string
  createTime: string
  updateTime: string
}

// ═══════════════════════════════════════════
// 创作者画像
// ═══════════════════════════════════════════

/**
 * 创作者画像，对应后端 CreatorProfileRecord 和 GET /api/creator/profile。
 * 由反馈事件和任务偏好推理生成的用户级聚合画像，
 * 与 CreatorPreference（单期任务快照）不同。
 */
export type CreatorProfile = {
  creatorId: string
  /** 风格标签 JSON 数组字符串，如 '["幽默","知识型"]' */
  styleTags: string
  /** 语气指引，描述创作者的语言风格特征 */
  toneGuide: string | null
  /** 受众画像，描述创作者的受众群体认知 */
  audienceView: string | null
  createTime: string | null
  updateTime: string | null
}

// ═══════════════════════════════════════════
// 竞品分析
// ═══════════════════════════════════════════

/** 竞品视频样本，对应后端 CreatorCompetitorSampleResponse */
export type CreatorCompetitorSample = {
  id: number
  competitorBvId: string
  competitorVideoName: string
  taskId: string
  category: string | null
  competitorSamples: string
  compareDimension: string | null
  extraContext: string | null
  createTime: string
  updateTime: string
}

/** 竞品分析报告，对应后端 CreatorCompetitorReportResponse */
export type CreatorCompetitorReport = {
  id: number
  reportId: string
  taskId: string
  competitorSummary: string | null
  competitorAdvantages: string | null
  ownAdvantages: string | null
  ownDisadvantages: string | null
  gapAnalysis: string | null
  improvementSuggestions: string | null
  differentiationStrategy: string | null
  rawOutput: string
  parseStatus: string
  createTime: string
  updateTime: string
}

/** 竞品视频保存请求，对应后端 CreatorCompetitorSaveRequest */
export type CreatorCompetitorSavePayload = {
  competitorBvId: string
  competitorVideoName: string
  category?: string
  competitorSamples: string
  compareDimension?: string
  extraContext?: string
}

/** 竞品分析触发请求，对应后端 CreatorCompetitorAnalyzeRequest */
export type CreatorCompetitorAnalyzePayload = {
  customGuidance?: string
  analysisFocus?: string
  extraRequirement?: string
}

// ═══════════════════════════════════════════
// 字段自动补全
// ═══════════════════════════════════════════

/** 字段自动补全请求，对应后端 FieldAutofillRequest */
export type FieldAutofillPayload = {
  /** 字段类型：TITLE_DRAFT / DESCRIPTION_DRAFT / CUSTOM_GUIDANCE / TITLE_STYLE / EXTRA_REQUIREMENT */
  fieldType: string
}

/** 字段自动补全响应，对应后端 FieldAutofillResponse */
export type FieldAutofillResult = {
  fieldType: string
  suggestion: string
}

// ═══════════════════════════════════════════
// 创作复盘报告
// ═══════════════════════════════════════════

/** 创作复盘报告，对应后端 CreatorReportResponse */
export type CreatorReport = {
  id: number
  reportId: string
  taskId: string
  contentSummary: string | null
  coreSellingPoints: string | null
  titleDescriptionReview: string | null
  audienceFeedbackSummary: string | null
  competitorComparison: string | null
  controversyAndMisunderstanding: string | null
  nextActionSuggestions: string | null
  creatorPreferenceInsight: string | null
  overallConclusion: string | null
  rawOutput: string
  parseStatus: string
  createTime: string
  updateTime: string
}

/** 创作复盘分析请求，对应后端 CreatorReportAnalyzeRequest */
export type CreatorReportAnalyzePayload = {
  customGuidance?: string
  reviewFocus?: string
  extraRequirement?: string
}
