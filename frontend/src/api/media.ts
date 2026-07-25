import { ApiError, del, get, post } from './http'
import type {
  CreateMediaUploadPayload,
  DraftVideo,
  MediaUpload,
  MediaUploadPart,
  MediaProcessingAssetReadUrl,
  MediaProcessingEstimate,
  MediaProcessingJob,
  MediaProcessingOptions,
  CreatePreflightReviewPayload,
  PreflightReview,
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

export function getCurrentDraftVideoUpload(taskId: string) {
  return get<MediaUpload>(
    `/creator/tasks/${encodeURIComponent(taskId)}/draft-video/uploads/current`,
  )
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
  return post<MediaUploadPart[]>(`${uploadUrl(taskId, uploadSessionId)}/parts:complete`, { parts })
}

export function completeDraftVideoUpload(taskId: string, uploadSessionId: string) {
  return post<DraftVideo>(`${uploadUrl(taskId, uploadSessionId)}:complete`)
}

export function getCurrentDraftVideo(taskId: string) {
  return get<DraftVideo>(`/creator/tasks/${encodeURIComponent(taskId)}/draft-videos/current`)
}

export function getDraftVideo(taskId: string, versionId: string) {
  return get<DraftVideo>(
    `/creator/tasks/${encodeURIComponent(taskId)}/draft-videos/${encodeURIComponent(versionId)}`,
  )
}

export function probeDraftVideo(taskId: string, versionId: string) {
  return post<DraftVideo>(
    `/creator/tasks/${encodeURIComponent(taskId)}/draft-videos/${encodeURIComponent(versionId)}:probe`,
  )
}

export function abortDraftVideoUpload(taskId: string, uploadSessionId: string) {
  return del(uploadUrl(taskId, uploadSessionId))
}

export function estimateMediaProcessing(
  taskId: string,
  versionId: string,
  payload: MediaProcessingOptions,
) {
  return post<MediaProcessingEstimate>(
    `/creator/tasks/${encodeURIComponent(taskId)}/draft-videos/${encodeURIComponent(versionId)}/processing-estimate`,
    payload,
  )
}

export function createMediaProcessingJob(
  taskId: string,
  versionId: string,
  payload: MediaProcessingOptions,
) {
  return post<MediaProcessingJob>(
    `/creator/tasks/${encodeURIComponent(taskId)}/draft-videos/${encodeURIComponent(versionId)}/processing-jobs`,
    payload,
  )
}

export function retryMediaProcessingJob(taskId: string, versionId: string, jobId: string) {
  return post<MediaProcessingJob>(
    `/creator/tasks/${encodeURIComponent(taskId)}/draft-videos/${encodeURIComponent(versionId)}/processing-jobs/${encodeURIComponent(jobId)}:retry`,
  )
}

export function getCurrentMediaProcessingJob(taskId: string, versionId: string) {
  return get<MediaProcessingJob>(
    `/creator/tasks/${encodeURIComponent(taskId)}/draft-videos/${encodeURIComponent(versionId)}/processing-jobs/current`,
  )
}

export function createMediaProcessingAssetReadUrl(
  taskId: string,
  versionId: string,
  assetId: string,
) {
  return post<MediaProcessingAssetReadUrl>(
    `/creator/tasks/${encodeURIComponent(taskId)}/draft-videos/${encodeURIComponent(versionId)}/processing-assets/${encodeURIComponent(assetId)}:read-url`,
  )
}

export function createPreflightReview(
  taskId: string,
  idempotencyKey: string,
  payload: CreatePreflightReviewPayload,
) {
  return post<PreflightReview>(
    `/creator/tasks/${encodeURIComponent(taskId)}/preflight-jobs`,
    payload,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
}

export function getCurrentPreflightReview(taskId: string, versionId: string) {
  return get<PreflightReview>(
    `/creator/tasks/${encodeURIComponent(taskId)}/preflight-jobs/current`,
    { params: { versionId } },
  )
}

export function getPreflightReview(taskId: string, reviewId: string) {
  return get<PreflightReview>(
    `/creator/tasks/${encodeURIComponent(taskId)}/preflight-jobs/${encodeURIComponent(reviewId)}`,
  )
}

export function cancelPreflightReview(taskId: string, reviewId: string) {
  return post<PreflightReview>(
    `/creator/tasks/${encodeURIComponent(taskId)}/preflight-jobs/${encodeURIComponent(reviewId)}:cancel`,
  )
}

export function retryPreflightReview(taskId: string, reviewId: string) {
  return post<PreflightReview>(
    `/creator/tasks/${encodeURIComponent(taskId)}/preflight-jobs/${encodeURIComponent(reviewId)}:retry`,
  )
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
  let response: Response
  try {
    response = await fetch(uploadUrl, {
      method: 'PUT',
      body,
      signal,
      credentials: 'omit',
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    const storageHost = safeUrlHost(uploadUrl)
    const directRuleTarget = storageHost || '当前对象存储域名'
    // 浏览器不会暴露系统代理状态，CORS、DNS、TLS 和代理断连都会表现为没有状态码的网络异常。
    // 提示按实际可操作顺序覆盖这些来源，避免用户在 CORS 已正确配置后仍反复修改 Bucket。
    throw new ApiError(
      0,
      `浏览器与对象存储${storageHost ? `（${storageHost}）` : ''}的连接被中断。请先确认 OSS CORS 已允许来源 ${window.location.origin} 的 PUT 请求；若 CORS 已配置，请临时关闭本机代理/VPN（如梯子或网络加速工具），或将 ${directRuleTarget} 加入直连规则后重试；仍失败时再检查浏览器 Endpoint 与 HTTPS 配置。`,
    )
  }
  if (!response.ok) {
    const detail = await response.text().catch(() => '')
    throw new ApiError(
      response.status,
      detail || `视频分片上传失败（HTTP ${response.status}）`,
      detail,
    )
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

function safeUrlHost(url: string) {
  try {
    return new URL(url).host
  } catch {
    return ''
  }
}
