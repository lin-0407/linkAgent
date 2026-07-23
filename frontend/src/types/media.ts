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

export type StoredMediaUploadResume = {
  taskId: string
  uploadSessionId: string | null
  fileFingerprint: string
  idempotencyKey: string
}
