/** 阶段 7 P0 单部署媒体访问会话。 */
export type MediaAccessSession = {
  enabled: boolean
  authenticated: boolean
  expiresAt: string | null
}

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

export type DraftVideo = {
  versionId: string
  taskId: string
  versionNo: number
  versionName: string
  originalFileName: string
  contentType: string
  fileSize: number
  status: string
  createTime: string
  updateTime: string
}

export type StoredMediaUploadResume = {
  taskId: string
  uploadSessionId: string | null
  fileFingerprint: string
  idempotencyKey: string
}
