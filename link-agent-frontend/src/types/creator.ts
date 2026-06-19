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
