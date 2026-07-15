import { ApiError, del, get, post } from './http'
import type {
  CreateMediaUploadPayload,
  DraftVideo,
  MediaUpload,
  MediaUploadPart,
  PresignedMediaUploadPart,
} from '@/types/media'

export function getMediaFeatureStatus() {
  return get<{ enabled: boolean }>('/creator/media/status')
}

export function createDraftVideoUpload(
  taskId: string,
  idempotencyKey: string,
  payload: CreateMediaUploadPayload,
) {
  return post<MediaUpload>(
    `/creator/tasks/${encodeURIComponent(taskId)}/draft-video/uploads`,
    payload,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
}

export function getDraftVideoUpload(taskId: string, uploadSessionId: string) {
  return get<MediaUpload>(uploadUrl(taskId, uploadSessionId))
}

export function listDraftVideoUploadParts(taskId: string, uploadSessionId: string) {
  return get<MediaUploadPart[]>(`${uploadUrl(taskId, uploadSessionId)}/parts`)
}

export function signDraftVideoUploadParts(
  taskId: string,
  uploadSessionId: string,
  partNumbers: number[],
) {
  return post<{ parts: PresignedMediaUploadPart[] }>(
    `${uploadUrl(taskId, uploadSessionId)}/parts:sign`,
    { partNumbers },
  )
}

export function registerDraftVideoUploadParts(
  taskId: string,
  uploadSessionId: string,
  parts: Array<{ partNumber: number; etag: string; partSize: number }>,
) {
  return post<MediaUploadPart[]>(
    `${uploadUrl(taskId, uploadSessionId)}/parts:complete`,
    { parts },
  )
}

export function completeDraftVideoUpload(taskId: string, uploadSessionId: string) {
  return post<DraftVideo>(`${uploadUrl(taskId, uploadSessionId)}:complete`)
}

export function abortDraftVideoUpload(taskId: string, uploadSessionId: string) {
  return del(uploadUrl(taskId, uploadSessionId))
}

/**
 * 视频正文直接 PUT 到 OSS，不能经过 axios 的 API baseURL，也不能携带站内 Cookie。
 * ETag 必须由 Bucket CORS 暴露，否则浏览器无法登记分片并完成断点续传。
 */
export async function putDraftVideoPart(
  uploadUrl: string,
  body: Blob,
  signal: AbortSignal,
): Promise<string> {
  const response = await fetch(uploadUrl, {
    method: 'PUT',
    body,
    signal,
    credentials: 'omit',
  })
  if (!response.ok) {
    const detail = await response.text().catch(() => '')
    throw new ApiError(response.status, detail || `视频分片上传失败（HTTP ${response.status}）`, detail)
  }
  const etag = response.headers.get('ETag')
  if (!etag) {
    throw new ApiError(0, 'OSS 响应没有暴露 ETag，请检查 Bucket CORS 的 ExposeHeaders 配置')
  }
  return etag
}

function uploadUrl(taskId: string, uploadSessionId: string) {
  return `/creator/tasks/${encodeURIComponent(taskId)}/draft-video/uploads/${encodeURIComponent(uploadSessionId)}`
}
