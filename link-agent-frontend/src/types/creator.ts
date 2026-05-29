export type CreatorMaterial = {
  id: number
  materialType: string
  content: string
  createTime: string
  updateTime: string
}

export type CreatorTask = {
  id: number
  taskId: string
  userId: string
  taskName: string
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
  status: string
  materialCount: number
  createTime: string
  updateTime: string
}

export type CreatorTaskCreatePayload = {
  userId?: string
  taskName?: string
  titleDraft?: string
  descriptionDraft?: string
  manuscript?: string
  subtitle?: string
}

export type PrePublishAnalyzePayload = {
  customGuidance?: string
  creatorPreference?: string
  titleStyle?: string
  extraRequirement?: string
}

export type CreatorSuggestion = {
  id: number
  suggestionId: string
  taskId: string
  contentSummary: string | null
  audienceProfile: string | null
  sellingPoints: string | null
  riskPoints: string | null
  titleSuggestions: string | null
  descriptionSuggestion: string | null
  tagSuggestions: string | null
  partitionSuggestion: string | null
  rawOutput: string
  parseStatus: string
  createTime: string
  updateTime: string
}

export type CreatorWorkflowStage = 'PRE_PUBLISH' | 'FEEDBACK' | 'REPORT'

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
  rawOutput: string
  parseStatus: string
  createTime: string
  updateTime: string
}
