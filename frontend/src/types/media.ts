export type CreateMediaUploadPayload = {
  versionName: string
  fileName: string
  fileSize: number
  contentType: 'video/mp4'
  lastModified: number
}

/** 页面刷新后恢复上传所需的服务端事实快照。 */
export type MediaUpload = {
  uploadSessionId: string
  versionId: string
  taskId: string
  status:
    | 'CREATED'
    | 'UPLOADING'
    | 'VERIFYING'
    | 'COMPLETED'
    | 'ABORTED'
    | 'EXPIRED'
    | 'FAILED'
    | 'SUPERSEDED'
  expectedSize: number
  fileFingerprint: string
  idempotencyKey: string
  partSize: number
  totalParts: number
  completedPartCount: number
  expiresAt: string
  completedAt: string | null
  failureMessage: string | null
}

export type MediaUploadPart = {
  partNumber: number
  etag: string
  partSize: number
  completedAt: string
}

export type PresignedMediaUploadPart = {
  partNumber: number
  uploadUrl: string
  expiresAt: string
}

export type DraftVideoStatus =
  | 'UPLOADING'
  | 'UPLOADED'
  | 'PROBING'
  | 'READY_FOR_REVIEW'
  | 'PROBE_FAILED'
  | 'UPLOAD_FAILED'
  | 'UPLOAD_ABORTED'

export type DraftVideo = {
  versionId: string
  taskId: string
  versionNo: number
  versionName: string
  originalFileName: string
  contentType: string
  fileSize: number
  durationMs: number | null
  width: number | null
  height: number | null
  frameRate: number | null
  videoCodec: string | null
  audioCodec: string | null
  hasAudio: boolean | null
  status: DraftVideoStatus
  createTime: string
  updateTime: string
}

export type MediaProcessingOptions = {
  frameIntervalSeconds: 5 | 10 | 15 | 30
  resolution: 'P480' | 'P720' | 'P1080'
  modelPlan: 'FLASH' | 'FLASH_PLUS_REVIEW'
  includeAsr: boolean
}

export type MediaProcessingEstimate = {
  pricingVersion: string
  durationSeconds: number
  estimatedFrameCount: number
  plusReviewFrameCount: number
  estimatedVisualInputTokens: number
  estimatedVisualOutputTokens: number
  estimatedAsrSeconds: number
  estimatedFlashCostUsd: number
  estimatedPlusCostUsd: number
  estimatedVisualCostUsd: number
  estimatedAsrCostUsd: number
  estimatedTotalCostUsd: number
  notice: string
}

export type MediaProcessingSignalSummary = {
  black: Array<{ startSeconds: number; endSeconds?: number; durationSeconds?: number }>
  silence: Array<{ startSeconds: number; endSeconds?: number; durationSeconds?: number }>
  freeze: Array<{ startSeconds: number; endSeconds?: number; durationSeconds?: number }>
  meanVolumeDb: number | null
  maxVolumeDb: number | null
}

export type MediaProcessingJob = {
  jobId: string
  versionId: string
  taskId: string
  frameIntervalSeconds: number
  targetResolution: string
  modelPlan: string
  includeAsr: boolean
  pricingVersion: string
  estimatedFrameCount: number
  estimatedVisualInputTokens: number
  estimatedVisualOutputTokens: number
  estimatedAsrSeconds: number
  estimatedVisualCostUsd: number
  estimatedAsrCostUsd: number
  estimatedTotalCostUsd: number
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  currentStep: string
  progressPercent: number
  attemptCount: number
  failureMessage: string | null
  signalSummary: MediaProcessingSignalSummary | null
  startedAt: string | null
  completedAt: string | null
  createTime: string
  updateTime: string
  steps: Array<{
    stepCode: string
    stepName: string
    sequenceNo: number
    status: string
    progressPercent: number
    outputSummary: string | null
    failureMessage: string | null
  }>
  assets: Array<{
    assetId: string
    assetType: 'PREVIEW_VIDEO' | 'AUDIO' | 'KEYFRAME'
    contentType: string
    fileSize: number
    sequenceNo: number | null
    timestampMs: number | null
    width: number | null
    height: number | null
    durationMs: number | null
  }>
  costNotice: string
}

export type MediaProcessingAssetReadUrl = {
  assetId: string
  readUrl: string
  expiresAt: string
}

export type CreatePreflightReviewPayload = {
  versionId: string
  confirmedProviderDisclosure: boolean
  reviewFocus?: string
}

export type PreflightReviewStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'RETRY_WAIT'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCEL_REQUESTED'
  | 'CANCELLED'

export type PreflightReview = {
  reviewId: string
  taskId: string
  versionId: string
  status: PreflightReviewStatus
  currentStep: string
  progressPercent: number
  eventSequence: number
  cancelRequested: boolean
  attemptCount: number
  maxAttempts: number
  reviewFocus: string | null
  executiveSummary: string | null
  estimatedCostUsd: number | null
  actualCostUsd: number | null
  usageSeconds: number | null
  currency: string
  errorCode: string | null
  errorMessage: string | null
  startedAt: string | null
  completedAt: string | null
  createTime: string
  updateTime: string
  steps: Array<{
    stepId: string
    stepType:
      | 'TRANSCRIBE'
      | 'BUILD_TIMELINE'
      | 'ANALYZE_VIDEO'
      | 'REVIEW_SEGMENTS'
      | 'SCREEN_AUDIENCE'
    sequenceNo: number
    status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED'
    attemptCount: number
    providerTaskId: string | null
    errorCode: string | null
    errorMessage: string | null
  }>
  evidence: Array<{
    evidenceId: string
    sourceType:
      | 'TRANSCRIPT'
      | 'KEY_FRAME'
      | 'BLACK'
      | 'SILENCE'
      | 'FREEZE'
      | 'VOLUME'
      | 'VIDEO_MODEL'
    startMs: number
    endMs: number
    content: string
    confidence: number | null
    assetId: string | null
    assetAvailable: boolean
    metadataJson: string | null
  }>
  issues: Array<{
    issueId: string
    issueType: string
    dimension: string
    title: string
    description: string
    startMs: number
    endMs: number
    severity: 'BLOCKER' | 'HIGH' | 'MEDIUM' | 'LOW'
    confidence: number
    evidenceRefs: string[]
    suggestedAction: string
    needsHumanReview: boolean
    affectedPersonas: Array<'CASUAL' | 'TARGET' | 'CORE_FAN'>
    userDisposition: 'PENDING' | 'ACCEPTED' | 'IGNORED'
    ignoreReason: string | null
  }>
  audienceScreenings: Array<{
    screeningId: string
    personaType: 'CASUAL' | 'TARGET' | 'CORE_FAN'
    overallReaction: string
    interestPoints: string[]
    confusionPoints: string[]
    dropRisks: string[]
    evidenceRefs: string[]
    confidence: number
  }>
  editTasks: Array<{
    editTaskId: string
    issueId: string
    title: string
    action: string
    startMs: number
    endMs: number
    priority: 'BLOCKER' | 'HIGH' | 'MEDIUM' | 'LOW'
    targetOutcome: string
    status: 'TODO' | 'IN_PROGRESS' | 'COMPLETED' | 'IGNORED'
    userNote: string | null
    completedAt: string | null
    updateTime: string
  }>
}

export type StoredMediaUploadResume = {
  taskId: string
  uploadSessionId: string | null
  fileFingerprint: string
  idempotencyKey: string
}
