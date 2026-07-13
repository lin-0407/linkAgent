import { computed, ref } from 'vue'
import { ApiError } from '@/api/http'
import {
  abortDraftVideoUpload,
  completeDraftVideoUpload,
  createDraftVideoUpload,
  createMediaAccessSession,
  getDraftVideoUpload,
  getMediaAccessSession,
  listDraftVideoUploadParts,
  putDraftVideoPart,
  registerDraftVideoUploadParts,
  signDraftVideoUploadParts,
} from '@/api/media'
import type {
  DraftVideo,
  MediaAccessSession,
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
  const accessSession = ref<MediaAccessSession>({ enabled: false, authenticated: false, expiresAt: null })
  const currentUpload = ref<MediaUpload | null>(null)
  const completedParts = ref<MediaUploadPart[]>([])
  const completedDraft = ref<DraftVideo | null>(null)
  const uploadedBytes = ref(0)
  const isAuthenticating = ref(false)
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

  async function refreshAccessSession() {
    accessSession.value = await getMediaAccessSession()
    return accessSession.value
  }

  async function authenticate(accessCode: string) {
    isAuthenticating.value = true
    errorMessage.value = ''
    try {
      accessSession.value = await createMediaAccessSession(accessCode)
      statusMessage.value = '私有媒体访问已解锁'
      return true
    } catch (error) {
      errorMessage.value = toErrorMessage(error)
      return false
    } finally {
      isAuthenticating.value = false
    }
  }

  async function restoreStoredUpload(taskId: string) {
    const generation = operationGeneration
    const stored = readStoredResume(taskId)
    if (!stored) {
      currentUpload.value = null
      completedParts.value = []
      completedDraft.value = null
      uploadedBytes.value = 0
      statusMessage.value = ''
      return null
    }
    if (!stored.uploadSessionId) {
      currentUpload.value = null
      completedParts.value = []
      completedDraft.value = null
      uploadedBytes.value = 0
      statusMessage.value = '已保留上次上传标识，请重新选择同一个本地文件恢复上传会话'
      return null
    }
    try {
      const restoredUpload = await getDraftVideoUpload(taskId, stored.uploadSessionId)
      if (generation !== operationGeneration) return null
      const restoredParts = await listDraftVideoUploadParts(taskId, stored.uploadSessionId)
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
      uploadedBytes.value = sumPartBytes(completedParts.value)
      statusMessage.value = currentUpload.value.status === 'COMPLETED'
        ? '成片已经上传完成'
        : currentUpload.value.status === 'VERIFYING'
          ? '成片正在由服务端确认，请稍后刷新结果'
        : '已找到未完成上传，请重新选择同一个本地文件继续'
      return currentUpload.value
    } catch (error) {
      if (generation !== operationGeneration) return null
      if (error instanceof ApiError && (error.status === 404 || error.status === 410)) {
        clearStoredResume(taskId)
      }
      if (error instanceof ApiError && error.status === 401) {
        accessSession.value = { enabled: true, authenticated: false, expiresAt: null }
      }
      return null
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
    completedDraft.value = null
    const controller = new AbortController()
    uploadAbortController = controller
    const generation = ++operationGeneration

    try {
      const fingerprint = await fileFingerprint(file)
      const storedResume = readStoredResume(taskId)
      const idempotencyKey = storedResume?.fileFingerprint === fingerprint
        ? storedResume.idempotencyKey
        : `media-${fingerprint.slice(0, 24)}-${crypto.randomUUID()}`
      // 先保存幂等键，页面恰好在创建接口返回前关闭时，下一次仍能用同一键找回服务端会话。
      writeStoredResume({
        taskId,
        uploadSessionId: storedResume?.fileFingerprint === fingerprint ? storedResume.uploadSessionId : null,
        fileFingerprint: fingerprint,
        idempotencyKey,
      })
      const upload = await createDraftVideoUpload(
        taskId,
        idempotencyKey,
        {
          versionName: versionName.trim() || 'V1 初剪',
          fileName: file.name,
          fileSize: file.size,
          contentType: 'video/mp4',
          lastModified: file.lastModified,
        },
      )
      if (upload.fileFingerprint !== fingerprint || upload.expectedSize !== file.size) {
        throw new Error('重新选择的文件与原上传会话不一致')
      }
      writeStoredResume({
        taskId,
        uploadSessionId: upload.uploadSessionId,
        fileFingerprint: fingerprint,
        idempotencyKey,
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
            const etag = await putDraftVideoPart(part.uploadUrl, chunk, controller.signal)
            await registerDraftVideoUploadParts(taskId, upload.uploadSessionId, [
              { partNumber: part.partNumber, etag, partSize: chunk.size },
            ])
            ensureActiveOperation(generation, operationGeneration, controller)
            uploadedBytes.value += chunk.size
            const activeUpload = currentUpload.value
            if (activeUpload) {
              currentUpload.value = {
                ...activeUpload,
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
        clearStoredResume(taskId)
      }
      if (generation !== operationGeneration) return null
      const activeUpload = currentUpload.value
      if (error instanceof ApiError && error.status === 409 && activeUpload) {
        try {
          const latestUpload = await getDraftVideoUpload(taskId, activeUpload.uploadSessionId)
          ensureActiveOperation(generation, operationGeneration, controller)
          if (isClosedUploadStatus(latestUpload.status)) {
            clearStoredResume(taskId)
            currentUpload.value = null
            completedParts.value = []
            uploadedBytes.value = 0
            statusMessage.value = '上次上传未能完成，请重新选择文件创建新的上传尝试'
          }
        } catch {
          // 原始上传错误更有助于用户判断问题，回查失败时不覆盖它。
        }
      }
      if (error instanceof ApiError && error.status === 401) {
        accessSession.value = { enabled: true, authenticated: false, expiresAt: null }
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
    operationGeneration += 1
    uploadAbortController?.abort()
    uploadAbortController = null
    const upload = currentUpload.value
    if (upload && upload.status !== 'COMPLETED') {
      await abortDraftVideoUpload(taskId, upload.uploadSessionId)
    }
    clearStoredResume(taskId)
    currentUpload.value = null
    completedParts.value = []
    uploadedBytes.value = 0
    isUploading.value = false
    isPaused.value = false
    statusMessage.value = '上传会话已取消'
  }

  return {
    accessSession,
    currentUpload,
    completedParts,
    completedDraft,
    uploadedBytes,
    isAuthenticating,
    isUploading,
    isPaused,
    errorMessage,
    statusMessage,
    progressPercent,
    refreshAccessSession,
    authenticate,
    restoreStoredUpload,
    startOrResume,
    pauseUpload,
    resetForTaskChange,
    disposeUpload,
    cancelUpload,
  }
}

async function fileFingerprint(file: File) {
  const source = `${file.name}\n${file.size}\n${file.lastModified}`
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(source))
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
}

function validateFile(file: File) {
  if (!file.name.toLowerCase().endsWith('.mp4') || (file.type && file.type !== 'video/mp4')) {
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
  localStorage.setItem(`${RESUME_KEY_PREFIX}${resume.taskId}`, JSON.stringify(resume))
}

function clearStoredResume(taskId: string) {
  localStorage.removeItem(`${RESUME_KEY_PREFIX}${taskId}`)
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
