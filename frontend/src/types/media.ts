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
  status: 'CREATED' | 'UPLOADING' | 'VERIFYING' | 'COMPLETED' | 'ABORTED' | 'EXPIRED' | 'FAILED' | 'SUPERSEDED'
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

export type StoredMediaUploadResume = {
  taskId: string
  uploadSessionId: string | null
  fileFingerprint: string
  idempotencyKey: string
}
