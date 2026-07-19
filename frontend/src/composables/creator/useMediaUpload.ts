import { computed, ref } from 'vue'
import { ApiError } from '@/api/http'
import {
  abortDraftVideoUpload,
  completeDraftVideoUpload,
  createDraftVideoUpload,
  getCurrentDraftVideoUpload,
  getDraftVideo,
  getDraftVideoUpload,
  listDraftVideoUploadParts,
  putDraftVideoPart,
  registerDraftVideoUploadParts,
  signDraftVideoUploadParts,
} from '@/api/media'
import type {
  DraftVideo,
  MediaUpload,
  MediaUploadPart,
  StoredMediaUploadResume,
} from '@/types/media'

const RESUME_KEY_PREFIX = 'linkagent-media-upload:'
const SIGN_BATCH_SIZE = 20
const UPLOAD_CONCURRENCY = 3

/**
 * 阶段 7 P0-0 浏览器分片上传状态机。
 * 页面刷新后只恢复服务端上传事实；出于浏览器安全限制，用户仍需重新选择同一个本地文件。
 */
export function useMediaUpload() {
  const currentUpload = ref<MediaUpload | null>(null)
  const completedParts = ref<MediaUploadPart[]>([])
  const completedDraft = ref<DraftVideo | null>(null)
  const uploadedBytes = ref(0)
  const isUploading = ref(false)
  const isPaused = ref(false)
  const errorMessage = ref('')
  const statusMessage = ref('')
  let uploadAbortController: AbortController | null = null
  let operationGeneration = 0
  let disposed = false

  const progressPercent = computed(() => {
    const total = currentUpload.value?.expectedSize ?? 0
    if (total <= 0) return 0
    return Math.min(100, Math.round((uploadedBytes.value / total) * 1000) / 10)
  })

  async function restoreStoredUpload(taskId: string) {
    const generation = operationGeneration
    const stored = readStoredResume(taskId)
    try {
      let restoredUpload: MediaUpload | null = null
      if (stored?.uploadSessionId) {
        try {
          restoredUpload = await getDraftVideoUpload(taskId, stored.uploadSessionId)
        } catch (error) {
          if (!(error instanceof ApiError) || (error.status !== 404 && error.status !== 410)) {
            throw error
          }
          clearStoredResume(taskId)
        }
      }
      if (!restoredUpload) {
        try {
          restoredUpload = await getCurrentDraftVideoUpload(taskId)
        } catch (error) {
          if (error instanceof ApiError && (error.status === 404 || error.status === 410)) {
            clearUploadState()
            return null
          }
          throw error
        }
      }
      if (generation !== operationGeneration) return null
      writeStoredResume({
        taskId,
        uploadSessionId: restoredUpload.uploadSessionId,
        fileFingerprint: restoredUpload.fileFingerprint,
        idempotencyKey: restoredUpload.idempotencyKey,
      })
      const restoredParts = await listDraftVideoUploadParts(taskId, restoredUpload.uploadSessionId)
      if (generation !== operationGeneration) return null
      if (isClosedUploadStatus(restoredUpload.status)) {
        clearStoredResume(taskId)
        currentUpload.value = null
        completedParts.value = []
        completedDraft.value = null
        uploadedBytes.value = 0
        statusMessage.value = restoredUpload.status === 'FAILED'
          ? '上次上传未能完成，请重新选择文件创建新的上传尝试'
          : '上次上传会话已经结束，请重新选择文件创建新的上传尝试'
        return null
      }
      currentUpload.value = restoredUpload
      completedParts.value = restoredParts
      completedDraft.value = null
      uploadedBytes.value = sumPartBytes(completedParts.value)
      if (restoredUpload.status === 'COMPLETED') {
        try {
          const draft = await getDraftVideo(taskId, restoredUpload.versionId)
          if (generation !== operationGeneration) return null
          completedDraft.value = draft
        } catch (error) {
          if (generation !== operationGeneration) return null
          if (!(error instanceof ApiError) || error.status !== 404) throw error
          // 上传会话仍然是续传事实；查询成片摘要失败不应阻止用户重新进入上传页。
          completedDraft.value = null
        }
      }
      statusMessage.value = currentUpload.value.status === 'COMPLETED'
        ? completedDraft.value?.status === 'READY_FOR_REVIEW'
          ? '成片已完成媒体探测'
          : completedDraft.value?.status === 'PROBING'
            ? '成片正在进行媒体探测，请稍后刷新结果'
          : '成片已经上传完成，等待媒体探测'
        : currentUpload.value.status === 'VERIFYING'
          ? '成片正在由服务端确认，请稍后刷新结果'
        : '已找到未完成上传，请重新选择同一个本地文件继续'
      return currentUpload.value
    } catch (error) {
      if (generation !== operationGeneration) return null
      if (error instanceof ApiError && (error.status === 404 || error.status === 410)) {
        clearStoredResume(taskId)
        clearUploadState()
        return null
      }
      throw error
    }
  }

  async function startOrResume(taskId: string, file: File, versionName: string) {
    if (disposed) {
      throw new DOMException('上传页面已经关闭', 'AbortError')
    }
    validateFile(file)
    isUploading.value = true
    isPaused.value = false
    errorMessage.value = ''
    const isReplacingProbeFailedDraft = completedDraft.value?.status === 'PROBE_FAILED'
    if (isReplacingProbeFailedDraft) {
      // 探测失败后必须生成新的幂等键和 OSS 上传会话，不能复用已经完成的旧对象。
      clearStoredResume(taskId)
      currentUpload.value = null
      completedParts.value = []
      uploadedBytes.value = 0
    }
    completedDraft.value = null
    const controller = new AbortController()
    uploadAbortController = controller
    const generation = ++operationGeneration

    try {
      const storedResume = readStoredResume(taskId)
      // 活跃会话必须复用服务端返回的幂等键；新会话只需要唯一标识，不依赖安全上下文中的 Web Crypto。
      const idempotencyKey = currentUpload.value?.idempotencyKey
        ?? (storedResume?.uploadSessionId ? storedResume.idempotencyKey : `media-${createClientId()}`)
      const upload = await createDraftVideoUpload(
        taskId,
        idempotencyKey,
        {
          versionName: versionName.trim() || 'V1 初剪',
          fileName: normalizeFileName(file.name),
          fileSize: file.size,
          contentType: 'video/mp4',
          lastModified: file.lastModified,
        },
      )
      if (upload.expectedSize !== file.size) {
        throw new Error('重新选择的文件与原上传会话不一致')
      }
      writeStoredResume({
        taskId,
        uploadSessionId: upload.uploadSessionId,
        fileFingerprint: upload.fileFingerprint,
        idempotencyKey: upload.idempotencyKey,
      })
      ensureActiveOperation(generation, operationGeneration, controller)
      currentUpload.value = upload

      const restoredParts = await listDraftVideoUploadParts(taskId, upload.uploadSessionId)
      ensureActiveOperation(generation, operationGeneration, controller)
      completedParts.value = restoredParts
      uploadedBytes.value = sumPartBytes(completedParts.value)
      const completedNumbers = new Set(completedParts.value.map((part) => part.partNumber))
      const missingPartNumbers = Array.from(
        { length: upload.totalParts },
        (_, index) => index + 1,
      ).filter((partNumber) => !completedNumbers.has(partNumber))

      for (let offset = 0; offset < missingPartNumbers.length; offset += SIGN_BATCH_SIZE) {
        ensureActiveOperation(generation, operationGeneration, controller)
        const partNumberBatch = missingPartNumbers.slice(offset, offset + SIGN_BATCH_SIZE)
        const signed = await signDraftVideoUploadParts(taskId, upload.uploadSessionId, partNumberBatch)
        ensureActiveOperation(generation, operationGeneration, controller)
        await runWithConcurrency(signed.parts, UPLOAD_CONCURRENCY, async (part) => {
          try {
            const start = (part.partNumber - 1) * upload.partSize
            const end = Math.min(start + upload.partSize, file.size)
            const chunk = file.slice(start, end, 'video/mp4')
            let uploadUrl = part.uploadUrl
            if (Date.parse(part.expiresAt) - Date.now() < 60_000) {
              const refreshed = await signDraftVideoUploadParts(
                taskId,
                upload.uploadSessionId,
                [part.partNumber],
              )
              uploadUrl = refreshed.parts[0]?.uploadUrl ?? uploadUrl
            }
            let etag: string
            try {
              etag = await putDraftVideoPart(uploadUrl, chunk, controller.signal)
            } catch (error) {
              if (!(error instanceof ApiError) || error.status !== 403) throw error
              // 短签可能在慢速上传中失效；只为当前分片换签一次，不重传已登记分片。
              const refreshed = await signDraftVideoUploadParts(
                taskId,
                upload.uploadSessionId,
                [part.partNumber],
              )
              const refreshedUrl = refreshed.parts[0]?.uploadUrl
              if (!refreshedUrl) throw error
              etag = await putDraftVideoPart(refreshedUrl, chunk, controller.signal)
            }
            await registerDraftVideoUploadParts(taskId, upload.uploadSessionId, [
              { partNumber: part.partNumber, etag, partSize: chunk.size },
            ])
            ensureActiveOperation(generation, operationGeneration, controller)
            uploadedBytes.value += chunk.size
            const activeUpload = currentUpload.value
            if (activeUpload) {
              currentUpload.value = {
                ...activeUpload,
                status: 'UPLOADING',
                completedPartCount: Math.min(
                  activeUpload.totalParts,
                  activeUpload.completedPartCount + 1,
                ),
              }
            }
            statusMessage.value = `正在上传：${Math.round(progressPercent.value)}%`
          } catch (error) {
            // 任一并发分片失败后立即停止同批其它请求，避免页面已经报错但后台仍继续上传。
            controller.abort()
            throw error
          }
        })
      }

      const latestParts = await listDraftVideoUploadParts(taskId, upload.uploadSessionId)
      ensureActiveOperation(generation, operationGeneration, controller)
      completedParts.value = latestParts
      uploadedBytes.value = sumPartBytes(completedParts.value)
      statusMessage.value = '分片齐全，正在由服务端确认完整对象'
      const draft = await completeDraftVideoUpload(taskId, upload.uploadSessionId)
      ensureActiveOperation(generation, operationGeneration, controller)
      completedDraft.value = draft
      currentUpload.value = { ...upload, status: 'COMPLETED', completedPartCount: upload.totalParts }
      // 完成后仍保留不含媒体内容的会话指针，刷新页面可以恢复“已上传”状态。
      statusMessage.value = '成片已安全上传，下一阶段可以开始媒体探测'
      return completedDraft.value
    } catch (error) {
      if (error instanceof ApiError && error.status === 410 && generation === operationGeneration) {
        // 过期会话不能继续复用内存中的幂等键，否则下一次上传仍会命中同一个已结束会话。
        clearStoredResume(taskId)
        clearUploadState()
      }
      if (generation !== operationGeneration) return null
      const activeUpload = currentUpload.value
      if (error instanceof ApiError && error.status === 409) {
        try {
          const latestUpload = activeUpload
            ? await getDraftVideoUpload(taskId, activeUpload.uploadSessionId)
            : await getCurrentDraftVideoUpload(taskId)
          ensureActiveOperation(generation, operationGeneration, controller)
          if (isClosedUploadStatus(latestUpload.status)) {
            clearStoredResume(taskId)
            clearUploadState()
            statusMessage.value = '上次上传未能完成，请重新选择文件创建新的上传尝试'
          } else {
            currentUpload.value = latestUpload
            writeStoredResume({
              taskId,
              uploadSessionId: latestUpload.uploadSessionId,
              fileFingerprint: latestUpload.fileFingerprint,
              idempotencyKey: latestUpload.idempotencyKey,
            })
          }
        } catch {
          // 原始上传错误更有助于用户判断问题，回查失败时不覆盖它。
        }
      }
      if (isAbortError(error)) {
        isPaused.value = true
        statusMessage.value = '上传已暂停，分片进度已经保留'
        return null
      }
      errorMessage.value = toErrorMessage(error)
      throw error
    } finally {
      if (generation === operationGeneration) {
        isUploading.value = false
      }
      if (uploadAbortController === controller && generation === operationGeneration) {
        uploadAbortController = null
      }
    }
  }

  function pauseUpload() {
    uploadAbortController?.abort()
  }

  function clearUploadState() {
    currentUpload.value = null
    completedParts.value = []
    completedDraft.value = null
    uploadedBytes.value = 0
    statusMessage.value = ''
  }

  async function confirmUploadResult(taskId: string) {
    const upload = currentUpload.value
    if (!upload || upload.status !== 'VERIFYING') return null
    const generation = operationGeneration
    isUploading.value = true
    errorMessage.value = ''
    statusMessage.value = '正在向服务端确认完整对象'
    try {
      const draft = await completeDraftVideoUpload(taskId, upload.uploadSessionId)
      // 任务切换会递增操作序号，旧请求返回后不能覆盖新任务的上传状态。
      if (generation !== operationGeneration) return null
      completedDraft.value = draft
      currentUpload.value = {
        ...upload,
        status: 'COMPLETED',
        completedPartCount: upload.totalParts,
      }
      statusMessage.value = '成片已安全上传，下一阶段可以开始媒体探测'
      return draft
    } catch (error) {
      if (generation !== operationGeneration) return null
      errorMessage.value = toErrorMessage(error)
      throw error
    } finally {
      if (generation === operationGeneration) {
        isUploading.value = false
      }
    }
  }

  function resetForTaskChange() {
    operationGeneration += 1
    uploadAbortController?.abort()
    uploadAbortController = null
    currentUpload.value = null
    completedParts.value = []
    completedDraft.value = null
    uploadedBytes.value = 0
    isUploading.value = false
    isPaused.value = false
    errorMessage.value = ''
    statusMessage.value = ''
  }

  function disposeUpload() {
    disposed = true
    operationGeneration += 1
    uploadAbortController?.abort()
    uploadAbortController = null
  }

  async function cancelUpload(taskId: string) {
    const generation = ++operationGeneration
    uploadAbortController?.abort()
    uploadAbortController = null
    const upload = currentUpload.value
    try {
      if (upload && upload.status !== 'COMPLETED') {
        await abortDraftVideoUpload(taskId, upload.uploadSessionId)
      }
      if (generation !== operationGeneration) return
      clearStoredResume(taskId)
      currentUpload.value = null
      completedParts.value = []
      uploadedBytes.value = 0
      isPaused.value = false
      statusMessage.value = '上传会话已取消'
    } catch (error) {
      if (generation !== operationGeneration) return
      throw error
    } finally {
      if (generation === operationGeneration) {
        isUploading.value = false
      }
    }
  }

  return {
    currentUpload,
    completedParts,
    completedDraft,
    uploadedBytes,
    isUploading,
    isPaused,
    errorMessage,
    statusMessage,
    progressPercent,
    restoreStoredUpload,
    startOrResume,
    pauseUpload,
    confirmUploadResult,
    resetForTaskChange,
    disposeUpload,
    cancelUpload,
  }
}

function validateFile(file: File) {
  if (!normalizeFileName(file.name).toLowerCase().endsWith('.mp4')
    || (file.type && file.type !== 'video/mp4')) {
    throw new Error('P0 只支持 MP4 视频文件')
  }
  if (file.size <= 0 || file.size > 1_500_000_000) {
    throw new Error('视频文件必须大于0且不能超过1.5GB')
  }
}

async function runWithConcurrency<T>(
  items: T[],
  concurrency: number,
  worker: (item: T) => Promise<void>,
) {
  let nextIndex = 0
  const workers = Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (nextIndex < items.length) {
      const currentIndex = nextIndex
      nextIndex += 1
      const item = items[currentIndex]
      if (item !== undefined) {
        await worker(item)
      }
    }
  })
  const results = await Promise.allSettled(workers)
  const rejected = results.filter(
    (result): result is PromiseRejectedResult => result.status === 'rejected',
  )
  // 网络错误应优先于被连带取消的 AbortError，否则真实失败会被误显示成“用户暂停”。
  const failed = rejected.find((result) => !isAbortError(result.reason)) ?? rejected[0]
  if (failed) throw failed.reason
}

function sumPartBytes(parts: MediaUploadPart[]) {
  return parts.reduce((sum, part) => sum + part.partSize, 0)
}

function readStoredResume(taskId: string): StoredMediaUploadResume | null {
  try {
    const value = localStorage.getItem(`${RESUME_KEY_PREFIX}${taskId}`)
    return value ? JSON.parse(value) as StoredMediaUploadResume : null
  } catch {
    return null
  }
}

function writeStoredResume(resume: StoredMediaUploadResume) {
  try {
    localStorage.setItem(`${RESUME_KEY_PREFIX}${resume.taskId}`, JSON.stringify(resume))
  } catch {
    // 服务端 current 接口可以恢复会话，本地存储不可用不应阻断上传。
  }
}

function clearStoredResume(taskId: string) {
  try {
    localStorage.removeItem(`${RESUME_KEY_PREFIX}${taskId}`)
  } catch {
    // 本地存储不可用时无需清理；服务端状态仍是唯一事实来源。
  }
}

function normalizeFileName(fileName: string) {
  const normalized = fileName.trim().replaceAll('\\', '/')
  return normalized.slice(normalized.lastIndexOf('/') + 1)
}

function createClientId() {
  const secureId = globalThis.crypto?.randomUUID?.()
  if (secureId) return secureId
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`
}

function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError'
}

function isClosedUploadStatus(status: MediaUpload['status']) {
  return status === 'ABORTED'
    || status === 'EXPIRED'
    || status === 'FAILED'
    || status === 'SUPERSEDED'
}

function ensureActiveOperation(
  generation: number,
  currentGeneration: number,
  controller: AbortController,
) {
  if (controller.signal.aborted || generation !== currentGeneration) {
    throw new DOMException('上传操作已取消', 'AbortError')
  }
}

function toErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : '媒体上传操作失败'
}
