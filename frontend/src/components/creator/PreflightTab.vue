<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ApiError } from '@/api/http'
import {
  createMediaProcessingAssetReadUrl,
  createMediaProcessingJob,
  estimateMediaProcessing,
  getCurrentDraftVideo,
  getCurrentMediaProcessingJob,
  getDraftVideo,
  probeDraftVideo,
  retryMediaProcessingJob,
} from '@/api/media'
import { useCreatorWorkspaceShell } from '@/composables/creator/useCreatorWorkspaceContext'
import { useMediaUpload } from '@/composables/creator/useMediaUpload'

const {
  currentDraftVideo,
  currentMediaProcessingStatus,
  selectedTaskId,
  selectedTask,
  refreshCurrentDraftVideo,
} = useCreatorWorkspaceShell()
const versionName = ref('V1 初剪')
const selectedFile = ref<File | null>(null)
const localError = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const resultDialog = ref<HTMLElement | null>(null)
const isProbing = ref(false)
const resultModalKind = ref<'upload' | 'probe' | null>(null)
let isDisposed = false
let viewGeneration = 0
let processingEstimateGeneration = 0
let probeRefreshTimer: ReturnType<typeof setTimeout> | null = null
let processingRefreshTimer: ReturnType<typeof setTimeout> | null = null
let resultModalReturnFocus: HTMLElement | null = null
let isResultModalBackdropPointerDown = false

const mediaUpload = useMediaUpload()
const {
  currentUpload,
  completedDraft,
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
} = mediaUpload

const processingOptions = reactive<{
  frameIntervalSeconds: 5 | 10 | 15 | 30
  resolution: 'P480' | 'P720' | 'P1080'
  modelPlan: 'FLASH' | 'FLASH_PLUS_REVIEW'
  includeAsr: boolean
}>({
  frameIntervalSeconds: 10,
  resolution: 'P480',
  modelPlan: 'FLASH',
  includeAsr: true,
})
const processingEstimate = ref<Awaited<ReturnType<typeof estimateMediaProcessing>> | null>(null)
const processingEstimateFingerprint = ref('')
const processingJob = ref<Awaited<ReturnType<typeof getCurrentMediaProcessingJob>> | null>(null)
const processingBusy = ref(false)
const processingError = ref('')
const processingAssetUrls = ref<Record<string, string>>({})

const taskId = computed(() => selectedTaskId.value ?? '')
const fileSummary = computed(() => {
  if (!selectedFile.value) return '尚未选择文件'
  return `${selectedFile.value.name} · ${formatBytes(selectedFile.value.size)}`
})
const visibleError = computed(() => localError.value || errorMessage.value)
const completedPartRatio = computed(() => {
  const upload = currentUpload.value
  if (!upload || upload.totalParts <= 0) return '0 / 0'
  return `${upload.completedPartCount} / ${upload.totalParts}`
})
const uploadActionLabel = computed(() => {
  if (currentUpload.value?.status === 'VERIFYING') return '确认结果'
  if (completedDraft.value?.status === 'PROBE_FAILED') return '重新上传'
  return isPaused.value || currentUpload.value ? '继续上传' : '开始上传'
})
const activeVersionId = computed(
  () => completedDraft.value?.versionId ?? currentUpload.value?.versionId ?? '',
)
const currentProcessingOptionsFingerprint = computed(() =>
  [
    taskId.value,
    activeVersionId.value,
    processingOptions.frameIntervalSeconds,
    processingOptions.resolution,
    processingOptions.modelPlan,
    processingOptions.includeAsr ? '1' : '0',
  ].join('|'),
)
const canProbeDraft = computed(() => {
  if (!activeVersionId.value || isUploading.value || isProbing.value) return false
  if (completedDraft.value) {
    return (
      completedDraft.value.status === 'UPLOADED' || completedDraft.value.status === 'PROBE_FAILED'
    )
  }
  return currentUpload.value?.status === 'COMPLETED'
})
const canStartUpload = computed(() => {
  if (currentUpload.value?.status === 'VERIFYING') return !isUploading.value && !isProbing.value
  return (
    Boolean(selectedFile.value) &&
    !isProbing.value &&
    (!completedDraft.value || completedDraft.value.status === 'PROBE_FAILED') &&
    (currentUpload.value?.status !== 'COMPLETED' || completedDraft.value?.status === 'PROBE_FAILED')
  )
})
const showProbeButton = computed(() => {
  const status = completedDraft.value?.status
  return (
    status === 'UPLOADED' ||
    status === 'PROBE_FAILED' ||
    status === 'PROBING' ||
    (!status && currentUpload.value?.status === 'COMPLETED') ||
    isProbing.value
  )
})
const probeButtonLabel = computed(() => {
  if (isProbing.value) return '正在检测'
  if (completedDraft.value?.status === 'PROBING') return '刷新状态'
  return '检测成片信息'
})
const canUseProbeAction = computed(
  () => completedDraft.value?.status === 'PROBING' || canProbeDraft.value,
)
const mediaProbeSummary = computed(() => {
  const draft = completedDraft.value
  if (!draft?.durationMs) return []
  return [
    ['时长', formatDuration(draft.durationMs)],
    ['分辨率', draft.width && draft.height ? `${draft.width}×${draft.height}` : '未知'],
    ['帧率', draft.frameRate ? `${Number(draft.frameRate).toFixed(2)} fps` : '未知'],
    ['视频编码', draft.videoCodec || '未知'],
    ['音轨', draft.hasAudio ? draft.audioCodec || '有音轨' : '无音轨'],
  ]
})
const hasCompletedUpload = computed(() =>
  Boolean(completedDraft.value || currentUpload.value?.status === 'COMPLETED'),
)
const resultPanelTitle = computed(() => {
  if (visibleError.value) return '媒体处理需要检查'
  if (completedDraft.value?.status === 'READY_FOR_REVIEW') return '媒体探测已完成'
  if (completedDraft.value?.status === 'PROBING' || isProbing.value) return '正在读取成片信息'
  if (completedDraft.value?.status === 'PROBE_FAILED') return '媒体探测未完成'
  if (hasCompletedUpload.value) return '成片上传已完成'
  if (currentUpload.value) return isPaused.value ? '上传已暂停' : '正在传输成片'
  return '等待成片上传'
})
const resultPanelDescription = computed(() => {
  if (visibleError.value) return visibleError.value
  if (completedDraft.value?.status === 'READY_FOR_REVIEW') {
    return '视频参数已经就绪，可查看本次媒体探测详情。'
  }
  if (completedDraft.value?.status === 'PROBING' || isProbing.value) {
    return '结果会自动刷新，完成后将在弹窗中展示。'
  }
  if (completedDraft.value?.status === 'PROBE_FAILED') {
    return '可以重新检测；若文件本身异常，也可以重新上传成片。'
  }
  if (hasCompletedUpload.value) return '对象校验通过，下一步检测时长、分辨率与音轨。'
  if (currentUpload.value) return statusMessage.value || '分片进度会自动保存。'
  return '上传与探测结果会固定显示在这里，不再向页面底部追加。'
})
const resultPanelStatus = computed(() => {
  if (visibleError.value || completedDraft.value?.status === 'PROBE_FAILED') return 'error'
  if (completedDraft.value?.status === 'PROBING' || isProbing.value) return 'working'
  if (completedDraft.value?.status === 'READY_FOR_REVIEW' || hasCompletedUpload.value)
    return 'success'
  if (currentUpload.value) return 'working'
  return 'idle'
})
const resultPanelIcon = computed(() => {
  if (resultPanelStatus.value === 'error') return '!'
  if (resultPanelStatus.value === 'success') return '✓'
  if (resultPanelStatus.value === 'working') return '···'
  return '—'
})
const resultModalTitle = computed(() =>
  resultModalKind.value === 'probe' ? '媒体探测完成' : '成片上传完成',
)
const canProcessDraft = computed(() => completedDraft.value?.status === 'READY_FOR_REVIEW')
const processingIsActive = computed(
  () => processingJob.value?.status === 'QUEUED' || processingJob.value?.status === 'RUNNING',
)
const processingPreviewAsset = computed(() =>
  processingJob.value?.assets.find((asset) => asset.assetType === 'PREVIEW_VIDEO'),
)
const processingFrameAssets = computed(
  () =>
    processingJob.value?.assets.filter((asset) => asset.assetType === 'KEYFRAME').slice(0, 24) ??
    [],
)
const processingStepLabel = computed(() => {
  const step = processingJob.value?.steps.find(
    (item) => item.stepCode === processingJob.value?.currentStep,
  )
  return step?.stepName || processingJob.value?.currentStep || '等待处理'
})
const processingOptionsMatchJob = computed(() => {
  const job = processingJob.value
  return Boolean(
    job &&
    job.frameIntervalSeconds === processingOptions.frameIntervalSeconds &&
    job.targetResolution === processingOptions.resolution &&
    job.modelPlan === processingOptions.modelPlan &&
    job.includeAsr === processingOptions.includeAsr,
  )
})
const processingEstimateMatchesOptions = computed(
  () =>
    Boolean(processingEstimate.value) &&
    processingEstimateFingerprint.value === currentProcessingOptionsFingerprint.value,
)
const canStartProcessing = computed(() => {
  const job = processingJob.value
  return (
    !job ||
    job.status === 'FAILED' ||
    (job.status === 'COMPLETED' && !processingOptionsMatchJob.value)
  )
})
const processingSignalFacts = computed(() => {
  const summary = processingJob.value?.signalSummary
  if (!summary) return []
  return [
    ['黑屏片段', (summary.black?.length ?? 0) + ' 段'],
    ['静音片段', (summary.silence?.length ?? 0) + ' 段'],
    ['冻结片段', (summary.freeze?.length ?? 0) + ' 段'],
    ['平均音量', summary.meanVolumeDb == null ? '无音轨' : summary.meanVolumeDb.toFixed(1) + ' dB'],
    ['峰值音量', summary.maxVolumeDb == null ? '无音轨' : summary.maxVolumeDb.toFixed(1) + ' dB'],
  ]
})

watch(completedDraft, (draft) => {
  if (draft && draft.taskId === taskId.value) currentDraftVideo.value = draft
  currentMediaProcessingStatus.value = null
  if (draft?.status === 'READY_FOR_REVIEW') void restoreProcessingState()
})

watch(
  [
    taskId,
    activeVersionId,
    () => completedDraft.value?.status,
    () => processingOptions.frameIntervalSeconds,
    () => processingOptions.resolution,
    () => processingOptions.modelPlan,
    () => processingOptions.includeAsr,
  ],
  () => {
    // 配置变化时旧估价不再代表当前任务，先失效再请求，避免用户按旧金额启动新配置。
    processingEstimateGeneration += 1
    processingEstimate.value = null
    processingEstimateFingerprint.value = ''
    processingError.value = ''
    if (canProcessDraft.value) void refreshProcessingEstimate()
  },
)

onMounted(async () => {
  const generation = viewGeneration
  if (!taskId.value) return
  try {
    await restoreStoredUpload(taskId.value)
    await restorePersistedDraft(taskId.value, generation)
    scheduleProbeRefreshIfNecessary(generation)
  } catch (error) {
    if (isDisposed || generation !== viewGeneration) return
    localError.value = toMessage(error)
  }
})

watch(taskId, async (nextTaskId) => {
  viewGeneration += 1
  processingEstimateGeneration += 1
  const generation = viewGeneration
  isProbing.value = false
  clearProbeRefreshTimer()
  clearProcessingRefreshTimer()
  processingEstimate.value = null
  processingEstimateFingerprint.value = ''
  processingJob.value = null
  processingBusy.value = false
  currentMediaProcessingStatus.value = null
  processingAssetUrls.value = {}
  processingError.value = ''
  resetForTaskChange()
  selectedFile.value = null
  if (fileInput.value) fileInput.value.value = ''
  localError.value = ''
  closeResultModal(false)
  if (nextTaskId) {
    try {
      await restoreStoredUpload(nextTaskId)
      await restorePersistedDraft(nextTaskId, generation)
      scheduleProbeRefreshIfNecessary(generation)
    } catch (error) {
      if (!isDisposed && generation === viewGeneration) localError.value = toMessage(error)
    }
  }
})

onBeforeUnmount(() => {
  isDisposed = true
  viewGeneration += 1
  processingEstimateGeneration += 1
  clearProbeRefreshTimer()
  clearProcessingRefreshTimer()
  resultModalReturnFocus = null
  disposeUpload()
})

function selectFile() {
  fileInput.value?.click()
}

function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  selectedFile.value = input.files?.[0] ?? null
  localError.value = ''
}

async function beginUpload() {
  localError.value = ''
  const currentTaskId = taskId.value
  const generation = viewGeneration
  if (currentTaskId && currentUpload.value?.status === 'VERIFYING') {
    try {
      const draft = await confirmUploadResult(currentTaskId)
      if (isDisposed || generation !== viewGeneration || currentTaskId !== taskId.value) return
      if (draft) {
        currentDraftVideo.value = draft
        notifyDraftResult(draft.status)
      }
    } catch (error) {
      if (!errorMessage.value) localError.value = toMessage(error)
    }
    return
  }
  const file = selectedFile.value
  if (!currentTaskId || !file) {
    localError.value = '请先选择当前任务的 MP4 成片'
    return
  }
  try {
    const durationSeconds = await readVideoDuration(file)
    if (isDisposed || generation !== viewGeneration || currentTaskId !== taskId.value) {
      return
    }
    if (durationSeconds > 1800) {
      localError.value = '视频时长不能超过30分钟'
      return
    }
    const draft = await startOrResume(currentTaskId, file, versionName.value)
    if (isDisposed || generation !== viewGeneration || currentTaskId !== taskId.value) return
    if (draft) {
      currentDraftVideo.value = draft
      notifyDraftResult(draft.status)
    }
  } catch (error) {
    // composable 已保存详细错误，这里只防止事件处理器产生未处理 Promise。
    if (!errorMessage.value) localError.value = toMessage(error)
  }
}

async function cancelCurrentUpload() {
  const currentTaskId = taskId.value
  const generation = viewGeneration
  if (!currentTaskId) return
  try {
    await cancelUpload(currentTaskId)
    if (isDisposed || generation !== viewGeneration || currentTaskId !== taskId.value) return
    await refreshCurrentDraftVideo(currentTaskId)
    if (isDisposed || generation !== viewGeneration || currentTaskId !== taskId.value) return
    selectedFile.value = null
    if (fileInput.value) fileInput.value.value = ''
  } catch (error) {
    if (!isDisposed && generation === viewGeneration && currentTaskId === taskId.value) {
      localError.value = toMessage(error)
    }
  }
}

async function restorePersistedDraft(currentTaskId: string, generation: number) {
  if (currentUpload.value) return
  try {
    const draft = await getCurrentDraftVideo(currentTaskId)
    if (isDisposed || generation !== viewGeneration || currentTaskId !== taskId.value) return
    if (
      draft.status === 'UPLOADED' ||
      draft.status === 'PROBING' ||
      draft.status === 'READY_FOR_REVIEW' ||
      draft.status === 'PROBE_FAILED'
    ) {
      completedDraft.value = draft
      currentDraftVideo.value = draft
      updateProbeStatusMessage(draft.status)
      if (draft.status === 'PROBING') scheduleProbeRefreshIfNecessary(generation)
    }
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return
    throw error
  }
}

async function probeCurrentDraft() {
  localError.value = ''
  const currentTaskId = taskId.value
  const versionId = activeVersionId.value
  if (!currentTaskId || !versionId) {
    localError.value = '请先完成成片上传'
    return
  }
  const generation = viewGeneration
  isProbing.value = true
  try {
    statusMessage.value = '正在读取视频时长、分辨率和音轨信息'
    const draft = await probeDraftVideo(currentTaskId, versionId)
    if (isDisposed || generation !== viewGeneration) return
    completedDraft.value = draft
    currentDraftVideo.value = draft
    updateProbeStatusMessage(draft.status)
    if (draft.status === 'PROBING') scheduleProbeRefreshIfNecessary(generation)
    if (draft.status === 'READY_FOR_REVIEW') await openResultModal('probe')
  } catch (error) {
    if (isDisposed || generation !== viewGeneration) return
    try {
      const draft = await getDraftVideo(currentTaskId, versionId)
      if (isDisposed || generation !== viewGeneration) return
      completedDraft.value = draft
      currentDraftVideo.value = draft
      updateProbeStatusMessage(draft.status)
    } catch {
      // 原始探测错误更有助于用户判断问题，状态回读失败时不覆盖它。
    }
    localError.value = toMessage(error)
  } finally {
    if (generation === viewGeneration) {
      isProbing.value = false
    }
  }
}

async function handleProbeAction() {
  if (completedDraft.value?.status === 'PROBING') {
    await refreshProbeStatus(true)
    return
  }
  await probeCurrentDraft()
}

async function refreshProbeStatus(showError = false) {
  const currentTaskId = taskId.value
  const versionId = activeVersionId.value
  const generation = viewGeneration
  if (!currentTaskId || !versionId) return
  try {
    const previousStatus = completedDraft.value?.status
    const draft = await getDraftVideo(currentTaskId, versionId)
    if (isDisposed || generation !== viewGeneration || currentTaskId !== taskId.value) return
    completedDraft.value = draft
    currentDraftVideo.value = draft
    updateProbeStatusMessage(draft.status)
    if (draft.status === 'PROBING') scheduleProbeRefreshIfNecessary(generation)
    if (previousStatus === 'PROBING' && draft.status === 'READY_FOR_REVIEW') {
      await openResultModal('probe')
    }
  } catch (error) {
    if (isDisposed || generation !== viewGeneration) return
    if (showError) localError.value = toMessage(error)
    if (!showError) scheduleProbeRefreshIfNecessary(generation, 5000)
  }
}

async function refreshProcessingEstimate() {
  const currentTaskId = taskId.value
  const versionId = activeVersionId.value
  if (!currentTaskId || !versionId || !canProcessDraft.value) return
  const fingerprint = currentProcessingOptionsFingerprint.value
  const generation = ++processingEstimateGeneration
  try {
    const estimate = await estimateMediaProcessing(currentTaskId, versionId, processingOptions)
    if (
      isDisposed ||
      generation !== processingEstimateGeneration ||
      currentTaskId !== taskId.value ||
      versionId !== activeVersionId.value ||
      fingerprint !== currentProcessingOptionsFingerprint.value
    ) {
      return
    }
    processingEstimate.value = estimate
    processingEstimateFingerprint.value = fingerprint
    processingError.value = ''
  } catch (error) {
    if (
      isDisposed ||
      generation !== processingEstimateGeneration ||
      currentTaskId !== taskId.value ||
      versionId !== activeVersionId.value ||
      fingerprint !== currentProcessingOptionsFingerprint.value
    ) {
      return
    }
    processingEstimate.value = null
    processingEstimateFingerprint.value = ''
    processingError.value = toMessage(error)
  }
}

async function restoreProcessingState() {
  const currentTaskId = taskId.value
  const versionId = activeVersionId.value
  if (!currentTaskId || !versionId || !canProcessDraft.value) return
  const generation = viewGeneration
  try {
    const job = await getCurrentMediaProcessingJob(currentTaskId, versionId)
    if (
      isDisposed ||
      generation !== viewGeneration ||
      currentTaskId !== taskId.value ||
      versionId !== activeVersionId.value
    ) {
      return
    }
    processingJob.value = job
    currentMediaProcessingStatus.value = job.status
    processingOptions.frameIntervalSeconds = job.frameIntervalSeconds as 5 | 10 | 15 | 30
    processingOptions.resolution = job.targetResolution as 'P480' | 'P720' | 'P1080'
    processingOptions.modelPlan = job.modelPlan as 'FLASH' | 'FLASH_PLUS_REVIEW'
    processingOptions.includeAsr = job.includeAsr
    if (processingJob.value.status === 'COMPLETED') await loadProcessingAssetUrls()
    else scheduleProcessingRefreshIfNecessary()
  } catch (error) {
    if (
      isDisposed ||
      generation !== viewGeneration ||
      currentTaskId !== taskId.value ||
      versionId !== activeVersionId.value
    ) {
      return
    }
    if (error instanceof ApiError && error.status === 404) {
      processingJob.value = null
      currentMediaProcessingStatus.value = null
      return
    }
    processingError.value = toMessage(error)
  }
}

async function startMediaProcessing() {
  const currentTaskId = taskId.value
  const versionId = activeVersionId.value
  if (!currentTaskId || !versionId || !processingEstimateMatchesOptions.value) return
  const generation = viewGeneration
  processingBusy.value = true
  processingError.value = ''
  try {
    let job: Awaited<ReturnType<typeof getCurrentMediaProcessingJob>>
    if (processingJob.value?.status === 'FAILED' && processingOptionsMatchJob.value) {
      job = await retryMediaProcessingJob(currentTaskId, versionId, processingJob.value.jobId)
    } else {
      job = await createMediaProcessingJob(currentTaskId, versionId, processingOptions)
    }
    if (
      isDisposed ||
      generation !== viewGeneration ||
      currentTaskId !== taskId.value ||
      versionId !== activeVersionId.value
    ) {
      return
    }
    processingJob.value = job
    currentMediaProcessingStatus.value = processingJob.value.status
    if (processingJob.value.status === 'COMPLETED') await loadProcessingAssetUrls()
    else scheduleProcessingRefreshIfNecessary()
  } catch (error) {
    if (
      !isDisposed &&
      generation === viewGeneration &&
      currentTaskId === taskId.value &&
      versionId === activeVersionId.value
    ) {
      processingError.value = toMessage(error)
    }
  } finally {
    if (generation === viewGeneration) processingBusy.value = false
  }
}

async function refreshProcessingJob(showError = false) {
  const currentTaskId = taskId.value
  const versionId = activeVersionId.value
  if (!currentTaskId || !versionId) return
  const generation = viewGeneration
  try {
    const job = await getCurrentMediaProcessingJob(currentTaskId, versionId)
    if (
      isDisposed ||
      generation !== viewGeneration ||
      currentTaskId !== taskId.value ||
      versionId !== activeVersionId.value
    ) {
      return
    }
    processingJob.value = job
    currentMediaProcessingStatus.value = processingJob.value.status
    if (processingJob.value.status === 'COMPLETED') {
      await loadProcessingAssetUrls()
    }
    scheduleProcessingRefreshIfNecessary()
  } catch (error) {
    if (
      isDisposed ||
      generation !== viewGeneration ||
      currentTaskId !== taskId.value ||
      versionId !== activeVersionId.value
    ) {
      return
    }
    if (showError) processingError.value = toMessage(error)
    else scheduleProcessingRefreshIfNecessary(5000)
  }
}

async function loadProcessingAssetUrls() {
  const currentTaskId = taskId.value
  const versionId = activeVersionId.value
  const job = processingJob.value
  if (!currentTaskId || !versionId || !job || job.status !== 'COMPLETED') return
  const generation = viewGeneration
  const assets = [processingPreviewAsset.value, ...processingFrameAssets.value].filter(Boolean)
  const nextUrls: Record<string, string> = {}
  await Promise.all(
    assets.map(async (asset) => {
      if (!asset) return
      try {
        const result = await createMediaProcessingAssetReadUrl(
          currentTaskId,
          versionId,
          asset.assetId,
        )
        nextUrls[asset.assetId] = result.readUrl
      } catch {
        // 单个短签失败不影响其它素材和任务状态，用户可刷新任务重新签发。
      }
    }),
  )
  if (
    isDisposed ||
    generation !== viewGeneration ||
    currentTaskId !== taskId.value ||
    versionId !== activeVersionId.value ||
    job.jobId !== processingJob.value?.jobId
  ) {
    return
  }
  processingAssetUrls.value = nextUrls
}

function scheduleProcessingRefreshIfNecessary(delay = 2000) {
  clearProcessingRefreshTimer()
  if (!processingIsActive.value) return
  processingRefreshTimer = setTimeout(() => void refreshProcessingJob(), delay)
}

function clearProcessingRefreshTimer() {
  if (processingRefreshTimer) clearTimeout(processingRefreshTimer)
  processingRefreshTimer = null
}

function scheduleProbeRefreshIfNecessary(generation: number, delay = 2000) {
  clearProbeRefreshTimer()
  if (completedDraft.value?.status !== 'PROBING' || isDisposed || generation !== viewGeneration)
    return
  probeRefreshTimer = setTimeout(() => void refreshProbeStatus(), delay)
}

function clearProbeRefreshTimer() {
  if (probeRefreshTimer) clearTimeout(probeRefreshTimer)
  probeRefreshTimer = null
}

function formatCost(value: number | null | undefined) {
  return `$${Number(value || 0).toFixed(4)}`
}

function updateProbeStatusMessage(status: string) {
  if (status === 'READY_FOR_REVIEW') {
    statusMessage.value = '成片已完成媒体探测'
  } else if (status === 'PROBING') {
    statusMessage.value = '成片正在进行媒体探测，页面会自动刷新结果'
  } else if (status === 'PROBE_FAILED') {
    statusMessage.value = '上一次媒体探测失败，可以重试检测或重新上传成片'
  } else if (status === 'UPLOADED') {
    statusMessage.value = '成片已经上传完成，等待媒体探测'
  }
}

function notifyDraftResult(status: string) {
  void openResultModal(status === 'READY_FOR_REVIEW' ? 'probe' : 'upload')
}

function openStoredResult() {
  void openResultModal(completedDraft.value?.status === 'READY_FOR_REVIEW' ? 'probe' : 'upload')
}

async function openResultModal(kind: 'upload' | 'probe') {
  // 保存触发按钮，关闭后把键盘焦点送回原处，避免弹窗打断操作路径。
  resultModalReturnFocus =
    document.activeElement instanceof HTMLElement ? document.activeElement : null
  resultModalKind.value = kind
  await nextTick()
  resultDialog.value?.focus()
}

function closeResultModal(restoreFocus = true) {
  const returnFocus = resultModalReturnFocus
  resultModalKind.value = null
  if (restoreFocus) {
    void nextTick(() => returnFocus?.focus())
  }
  resultModalReturnFocus = null
}

function handleResultModalBackdropPointerDown(event: PointerEvent) {
  isResultModalBackdropPointerDown = event.target === event.currentTarget
}

function handleResultModalBackdropClick(event: MouseEvent) {
  if (isResultModalBackdropPointerDown && event.target === event.currentTarget) {
    closeResultModal()
  }
  isResultModalBackdropPointerDown = false
}

async function probeFromResultModal() {
  closeResultModal(false)
  await handleProbeAction()
}

function formatBytes(bytes: number) {
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
}

function formatDuration(durationMs: number) {
  const totalSeconds = Math.round(durationMs / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}

function readVideoDuration(file: File): Promise<number> {
  return new Promise((resolve, reject) => {
    const video = document.createElement('video')
    const objectUrl = URL.createObjectURL(file)
    video.preload = 'metadata'
    video.onloadedmetadata = () => {
      URL.revokeObjectURL(objectUrl)
      resolve(Number.isFinite(video.duration) ? video.duration : 0)
    }
    video.onerror = () => {
      URL.revokeObjectURL(objectUrl)
      reject(new Error('浏览器无法读取该 MP4 的时长，请确认文件没有损坏'))
    }
    video.src = objectUrl
  })
}

function toMessage(error: unknown) {
  return error instanceof Error ? error.message : '媒体操作失败'
}
</script>

<template>
  <section class="creator-section preflight-studio">
    <header class="preflight-hero">
      <div>
        <p class="preflight-eyebrow">FINAL CUT REVIEW</p>
        <h3>成片试映</h3>
        <p>上传最终成片并保留传输进度，随后进入发布前画面与声音体检。</p>
      </div>
      <div class="preflight-limit-plate" aria-label="视频上传限制">
        <span>MP4 ONLY</span>
        <strong>30:00</strong>
        <small>最大 1.5 GB</small>
      </div>
    </header>

    <div class="preflight-route" aria-label="媒体上传链路">
      <span>选择成片</span><i aria-hidden="true"></i><span>分片上传</span><i aria-hidden="true"></i
      ><span>媒体探测</span><i aria-hidden="true"></i><span>发布前体检</span>
    </div>

    <div class="preflight-work-grid">
      <section class="preflight-file-card">
        <span class="preflight-index">01</span>
        <div class="preflight-card-head">
          <div>
            <h4>选择成片</h4>
            <p>{{ selectedTask?.taskName || '当前创作任务' }}</p>
          </div>
          <span v-if="currentUpload" class="preflight-state-chip">{{ currentUpload.status }}</span>
        </div>

        <label class="preflight-version-field">
          <span>版本名称</span>
          <input v-model="versionName" type="text" maxlength="128" :disabled="isUploading" />
        </label>

        <button
          type="button"
          class="preflight-dropzone"
          :disabled="isUploading"
          @click="selectFile"
        >
          <span class="preflight-reel" aria-hidden="true">◉</span>
          <strong>{{ selectedFile ? '已选择成片' : '选择 MP4 文件' }}</strong>
          <small>{{ fileSummary }}</small>
        </button>
        <input
          ref="fileInput"
          class="preflight-hidden-input"
          type="file"
          accept="video/mp4,.mp4"
          @change="handleFileChange"
        />

        <p
          v-if="currentUpload && !selectedFile && currentUpload.status !== 'COMPLETED'"
          class="preflight-resume-note"
        >
          已恢复服务器端分片记录。浏览器不会保存你的本地文件，请重新选择同一个文件继续。
        </p>
      </section>

      <section class="preflight-meter-card">
        <span class="preflight-index">02</span>
        <div class="preflight-meter-head">
          <div>
            <h4>分片传输</h4>
            <p>支持暂停与继续</p>
          </div>
          <strong>{{ progressPercent.toFixed(1) }}%</strong>
        </div>

        <div class="preflight-progress-track" aria-label="上传进度">
          <span :style="{ width: `${progressPercent}%` }"></span>
        </div>
        <div class="preflight-meter-meta">
          <span>分片 {{ completedPartRatio }}</span>
          <span>{{ currentUpload ? formatBytes(currentUpload.expectedSize) : '等待文件' }}</span>
        </div>

        <p class="preflight-status-copy">
          {{ statusMessage || '上传进度会自动保存，刷新页面后可以继续。' }}
        </p>

        <div class="preflight-actions">
          <button
            v-if="!isUploading"
            type="button"
            class="preflight-primary"
            :disabled="!canStartUpload"
            @click="beginUpload"
          >
            {{ uploadActionLabel }}
          </button>
          <button
            v-else-if="currentUpload?.status !== 'VERIFYING'"
            type="button"
            class="preflight-secondary"
            @click="pauseUpload"
          >
            暂停
          </button>
          <button v-else type="button" class="preflight-secondary" disabled>确认中...</button>
          <button
            v-if="currentUpload && currentUpload.status !== 'COMPLETED'"
            type="button"
            class="preflight-ghost"
            :disabled="isUploading || currentUpload.status === 'VERIFYING'"
            @click="cancelCurrentUpload"
          >
            取消上传
          </button>
        </div>
      </section>
      <section
        class="preflight-result-dock"
        :class="`is-${resultPanelStatus}`"
        aria-label="当前成片状态"
        aria-live="polite"
      >
        <span class="preflight-result-icon" aria-hidden="true">{{ resultPanelIcon }}</span>
        <div class="preflight-result-copy">
          <small>当前成片状态</small>
          <strong>{{ resultPanelTitle }}</strong>
          <p :role="visibleError ? 'alert' : undefined">{{ resultPanelDescription }}</p>
        </div>
        <div class="preflight-result-actions">
          <button
            v-if="hasCompletedUpload"
            type="button"
            class="preflight-ghost-action"
            @click="openStoredResult"
          >
            查看详情
          </button>
          <button
            v-if="showProbeButton"
            type="button"
            class="preflight-secondary"
            :disabled="!canUseProbeAction"
            @click="handleProbeAction"
          >
            {{ probeButtonLabel }}
          </button>
        </div>
      </section>

      <section v-if="canProcessDraft" class="preflight-processing-panel">
        <div class="preflight-processing-head">
          <div>
            <span class="preflight-index">03</span>
            <p class="preflight-eyebrow">MEDIA PIPELINE</p>
            <h4>生成预览与关键画面</h4>
            <p>先确定抽帧和识别档位，系统按实际视频时长给出本次预估。</p>
          </div>
          <span v-if="processingJob" class="preflight-state-chip">{{ processingJob.status }}</span>
        </div>

        <div class="preflight-processing-options">
          <label>
            <span>抽帧间隔</span>
            <select
              v-model="processingOptions.frameIntervalSeconds"
              :disabled="processingIsActive || processingBusy"
            >
              <option :value="5">每 5 秒</option>
              <option :value="10">每 10 秒</option>
              <option :value="15">每 15 秒</option>
              <option :value="30">每 30 秒</option>
            </select>
          </label>
          <label>
            <span>分析清晰度</span>
            <select
              v-model="processingOptions.resolution"
              :disabled="processingIsActive || processingBusy"
            >
              <option value="P480">480p · 低成本</option>
              <option value="P720">720p · 平衡</option>
              <option value="P1080">1080p · 高细节</option>
            </select>
          </label>
          <label>
            <span>模型档位</span>
            <select
              v-model="processingOptions.modelPlan"
              :disabled="processingIsActive || processingBusy"
            >
              <option value="FLASH">Flash · 基础观察</option>
              <option value="FLASH_PLUS_REVIEW">Flash + Plus · 抽样复核</option>
            </select>
          </label>
          <label class="preflight-check-option">
            <input
              v-model="processingOptions.includeAsr"
              type="checkbox"
              :disabled="processingIsActive || processingBusy"
            />
            <span>估算 ASR 转写</span>
          </label>
        </div>

        <div
          v-if="processingEstimateMatchesOptions && processingEstimate"
          class="preflight-cost-strip"
        >
          <div>
            <span>预计图片</span><strong>{{ processingEstimate.estimatedFrameCount }} 张</strong>
          </div>
          <div>
            <span>视觉 Token</span
            ><strong>{{ processingEstimate.estimatedVisualInputTokens.toLocaleString() }}</strong>
          </div>
          <div>
            <span>ASR 时长</span><strong>{{ processingEstimate.estimatedAsrSeconds }} 秒</strong>
          </div>
          <div>
            <span>预计费用</span
            ><strong>{{ formatCost(processingEstimate.estimatedTotalCostUsd) }}</strong>
          </div>
        </div>
        <p v-if="processingEstimateMatchesOptions && processingEstimate" class="preflight-cost-note">
          {{ processingEstimate.notice }}
        </p>
        <p v-if="processingError" class="preflight-processing-error" role="alert">
          {{ processingError }}
        </p>

        <div v-if="processingJob" class="preflight-processing-status">
          <div class="preflight-processing-status-head">
            <strong>{{
              processingJob.status === 'COMPLETED'
                ? '预览已生成'
                : processingJob.status === 'FAILED'
                  ? '预处理失败'
                  : processingStepLabel
            }}</strong>
            <span>{{ processingJob.progressPercent }}%</span>
          </div>
          <div class="preflight-progress-track">
            <span :style="{ width: processingJob.progressPercent + `%` }"></span>
          </div>
          <p v-if="processingJob.failureMessage" class="preflight-processing-error">
            {{ processingJob.failureMessage }}
          </p>
        </div>

        <dl v-if="processingSignalFacts.length" class="preflight-signal-strip">
          <div v-for="fact in processingSignalFacts" :key="fact[0]">
            <dt>{{ fact[0] }}</dt>
            <dd>{{ fact[1] }}</dd>
          </div>
        </dl>

        <div class="preflight-actions">
          <button
            v-if="canStartProcessing"
            type="button"
            class="preflight-primary"
            :disabled="!processingEstimateMatchesOptions || processingBusy"
            @click="startMediaProcessing"
          >
            {{
              processingJob?.status === 'FAILED'
                ? '重新处理'
                : processingJob?.status === 'COMPLETED'
                  ? '使用新设置重新生成'
                  : '确认并开始处理'
            }}
          </button>
        </div>

        <div
          v-if="processingPreviewAsset && processingAssetUrls[processingPreviewAsset.assetId]"
          class="preflight-preview-result"
        >
          <div class="preflight-processing-status-head">
            <strong>预览成片</strong>
            <span>{{ formatBytes(processingPreviewAsset.fileSize) }}</span>
          </div>
          <video
            controls
            preload="metadata"
            :src="processingAssetUrls[processingPreviewAsset.assetId]"
          ></video>
        </div>

        <div v-if="processingFrameAssets.length" class="preflight-frame-grid">
          <figure v-for="asset in processingFrameAssets" :key="asset.assetId">
            <img
              v-if="processingAssetUrls[asset.assetId]"
              :src="processingAssetUrls[asset.assetId]"
              alt="关键画面"
              loading="lazy"
            />
            <figcaption>{{ formatDuration(asset.timestampMs || 0) }}</figcaption>
          </figure>
        </div>
      </section>
    </div>

    <Teleport to="body">
      <Transition name="preflight-modal">
        <div
          v-if="resultModalKind"
          class="creator-modal-backdrop preflight-result-backdrop"
          role="presentation"
          @pointerdown="handleResultModalBackdropPointerDown"
          @click="handleResultModalBackdropClick"
        >
          <section
            ref="resultDialog"
            class="creator-result-modal preflight-result-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="preflight-result-title"
            tabindex="-1"
            @keydown.esc.stop="closeResultModal()"
          >
            <header class="creator-result-modal-head">
              <div>
                <p class="preflight-modal-kicker">阶段通知</p>
                <h3 id="preflight-result-title">{{ resultModalTitle }}</h3>
              </div>
              <button
                type="button"
                class="preflight-modal-close"
                aria-label="关闭结果窗口"
                @click="closeResultModal()"
              >
                ×
              </button>
            </header>

            <div class="creator-result-modal-body preflight-modal-body">
              <div class="preflight-modal-status">
                <span aria-hidden="true">✓</span>
                <div>
                  <strong>{{ resultModalTitle }}</strong>
                  <p>
                    {{
                      resultModalKind === 'probe'
                        ? '参数已经读取完成，结果只在当前窗口中展开。'
                        : '对象校验已经通过，可以继续检测成片信息。'
                    }}
                  </p>
                </div>
              </div>

              <dl class="preflight-result-facts">
                <div>
                  <dt>文件</dt>
                  <dd>
                    {{ completedDraft?.originalFileName || selectedFile?.name || '已上传成片' }}
                  </dd>
                </div>
                <div>
                  <dt>版本</dt>
                  <dd>{{ completedDraft?.versionName || versionName }}</dd>
                </div>
                <div>
                  <dt>大小</dt>
                  <dd>
                    {{ formatBytes(completedDraft?.fileSize || currentUpload?.expectedSize || 0) }}
                  </dd>
                </div>
              </dl>

              <div v-if="resultModalKind === 'probe'" class="preflight-modal-probe">
                <div class="preflight-modal-section-head">
                  <strong>媒体探测结果</strong>
                  <span>READY FOR REVIEW</span>
                </div>
                <dl>
                  <div v-for="item in mediaProbeSummary" :key="item[0]">
                    <dt>{{ item[0] }}</dt>
                    <dd>{{ item[1] }}</dd>
                  </div>
                </dl>
              </div>
              <p v-else class="preflight-modal-next-step">
                下一步将读取视频时长、分辨率、帧率、编码与音轨信息。检测完成后，新的结果仍会通过此窗口告知。
              </p>
            </div>

            <footer class="preflight-modal-actions">
              <button
                v-if="resultModalKind === 'upload'"
                type="button"
                class="preflight-ghost-action"
                @click="closeResultModal()"
              >
                稍后查看
              </button>
              <button
                v-if="resultModalKind === 'upload' && showProbeButton"
                type="button"
                class="preflight-primary"
                :disabled="!canUseProbeAction"
                @click="probeFromResultModal"
              >
                {{ probeButtonLabel }}
              </button>
              <button v-else type="button" class="preflight-primary" @click="closeResultModal()">
                知道了
              </button>
            </footer>
          </section>
        </div>
      </Transition>
    </Teleport>

    <footer class="preflight-privacy-note">
      <strong>隐私边界</strong>
      <span
        >原片使用私有存储和短时读取签名；当前切片尚未实现发布后自动删除，请按部署保留策略清理。</span
      >
    </footer>
  </section>
</template>

<style scoped>
.preflight-studio {
  --lab-ink: #102235;
  --lab-cyan: var(--bili-blue);
  position: relative;
  overflow: hidden;
  background:
    linear-gradient(90deg, rgba(0, 174, 236, 0.045) 1px, transparent 1px),
    linear-gradient(rgba(0, 174, 236, 0.045) 1px, transparent 1px), var(--surface);
  background-size: 28px 28px;
}

.preflight-studio::before {
  position: absolute;
  top: 0;
  right: 0;
  width: 220px;
  height: 6px;
  content: '';
  background: linear-gradient(90deg, var(--lab-cyan), var(--bili-pink));
}

.preflight-hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s7);
  padding-bottom: var(--s6);
  border-bottom: 1px solid var(--border-strong);
}

.preflight-eyebrow {
  margin-bottom: var(--s2);
  color: var(--accent-strong);
  font-family: var(--font-code);
  font-size: 11px;
  font-weight: var(--fw-bold);
  letter-spacing: 0;
}

.preflight-hero h3 {
  margin-bottom: var(--s2);
  color: var(--lab-ink);
  font-size: 38px;
  letter-spacing: 0;
}

.preflight-hero p:not(.preflight-eyebrow) {
  max-width: 680px;
  margin-bottom: 0;
  color: var(--muted);
  line-height: 1.75;
}

.preflight-limit-plate {
  display: grid;
  min-width: 138px;
  padding: var(--s4);
  color: #fff;
  background: var(--lab-ink);
  border-radius: var(--r-sm);
  box-shadow: 8px 8px 0 rgba(0, 174, 236, 0.14);
}

.preflight-limit-plate span,
.preflight-limit-plate small {
  color: rgba(255, 255, 255, 0.68);
  font-family: var(--font-code);
  font-size: 10px;
  letter-spacing: 0;
}

.preflight-limit-plate strong {
  margin: 4px 0;
  font-family: var(--font-code);
  font-size: 28px;
}

.preflight-route {
  display: flex;
  align-items: center;
  gap: var(--s3);
  margin: var(--s5) 0;
  color: var(--muted);
  font-family: var(--font-code);
  font-size: 11px;
  letter-spacing: 0;
  text-transform: uppercase;
}

.preflight-route i {
  flex: 1;
  height: 1px;
  background: linear-gradient(90deg, var(--border-strong), var(--lab-cyan));
}

.preflight-file-card,
.preflight-meter-card {
  position: relative;
  padding: var(--s6);
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid var(--border-strong);
  border-radius: var(--r);
  box-shadow: var(--sh-sm);
}

.preflight-index {
  position: absolute;
  top: var(--s4);
  right: var(--s4);
  color: rgba(0, 174, 236, 0.28);
  font-family: var(--font-code);
  font-size: 28px;
  font-weight: var(--fw-bold);
}

.preflight-file-card h4,
.preflight-meter-card h4 {
  margin: 0 0 var(--s2);
  color: var(--lab-ink);
  font-size: 19px;
}

.preflight-card-head p,
.preflight-meter-head p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}

.preflight-version-field span {
  display: block;
  margin-bottom: var(--s2);
  color: var(--muted);
  font-size: 12px;
  font-weight: var(--fw-semibold);
}

.preflight-version-field input {
  min-width: 0;
  padding: 11px 12px;
  color: var(--ink);
  background: var(--surface-sub);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-sm);
  outline: none;
}

.preflight-version-field input:focus {
  border-color: var(--lab-cyan);
  box-shadow: 0 0 0 3px var(--accent-ring);
}

.preflight-primary,
.preflight-secondary,
.preflight-ghost {
  min-height: 44px;
  padding: 10px 16px;
  border-radius: var(--r-sm);
  cursor: pointer;
}

.preflight-primary {
  color: #fff;
  background: var(--lab-ink);
  border: 1px solid var(--lab-ink);
}

.preflight-work-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(320px, 0.85fr);
  gap: var(--s4);
}

.preflight-card-head,
.preflight-meter-head,
.preflight-meter-meta,
.preflight-actions,
.preflight-privacy-note {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s3);
}

.preflight-state-chip {
  padding: 4px 8px;
  color: var(--accent-strong);
  background: var(--accent-tint);
  border-radius: var(--r-pill);
  font-family: var(--font-code);
  font-size: 10px;
}

.preflight-version-field {
  display: block;
  margin-top: var(--s5);
}

.preflight-version-field input {
  width: 100%;
}

.preflight-dropzone {
  display: grid;
  place-items: center;
  width: 100%;
  min-height: 160px;
  margin-top: var(--s3);
  padding: var(--s5);
  color: var(--ink);
  background:
    repeating-linear-gradient(135deg, rgba(0, 174, 236, 0.04) 0 8px, transparent 8px 16px),
    var(--surface-sub);
  border: 1px dashed rgba(0, 138, 197, 0.45);
  border-radius: var(--r-sm);
  cursor: pointer;
  transition:
    border-color 160ms ease,
    transform 160ms ease,
    background 160ms ease;
}

.preflight-dropzone:hover:not(:disabled) {
  background-color: rgba(0, 174, 236, 0.05);
  border-color: var(--lab-cyan);
  transform: translateY(-1px);
}

.preflight-reel {
  color: var(--lab-cyan);
  font-size: 34px;
}

.preflight-dropzone small {
  max-width: 100%;
  margin-top: 5px;
  overflow: hidden;
  color: var(--muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preflight-hidden-input {
  display: none;
}

.preflight-resume-note,
.preflight-status-copy {
  margin: var(--s3) 0 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.preflight-meter-head strong {
  color: var(--lab-ink);
  font-family: var(--font-code);
  font-size: 28px;
}

.preflight-progress-track {
  height: 10px;
  margin: var(--s7) 0 var(--s2);
  overflow: hidden;
  background: #dfe8ef;
  border-radius: 2px;
}

.preflight-progress-track span {
  display: block;
  width: 0;
  height: 100%;
  background: linear-gradient(90deg, var(--lab-cyan), var(--bili-pink));
  transition: width 240ms ease;
}

.preflight-meter-meta {
  color: var(--muted);
  font-family: var(--font-code);
  font-size: 11px;
}

.preflight-actions {
  justify-content: flex-start;
  margin-top: var(--s6);
}

.preflight-secondary {
  color: var(--lab-ink);
  background: var(--accent-tint);
  border: 1px solid rgba(0, 138, 197, 0.24);
}

.preflight-ghost-action {
  min-height: 44px;
  padding: 10px 16px;
  color: var(--lab-ink);
  background: #fff;
  border: 1px solid var(--border-strong);
  border-radius: var(--r-sm);
  cursor: pointer;
}

.preflight-primary:focus-visible,
.preflight-secondary:focus-visible,
.preflight-ghost:focus-visible,
.preflight-ghost-action:focus-visible,
.preflight-modal-close:focus-visible {
  outline: 3px solid var(--accent-ring);
  outline-offset: 2px;
}

.preflight-ghost {
  color: var(--danger);
  background: transparent;
  border: 1px solid rgba(220, 38, 38, 0.2);
}

button:disabled {
  cursor: not-allowed;
  opacity: 0.48;
}

.preflight-result-dock {
  display: grid;
  grid-column: 1 / -1;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--s4);
  height: 104px;
  padding: var(--s4) var(--s5);
  overflow: hidden;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid var(--border-strong);
  border-radius: var(--r);
}

.preflight-result-dock.is-success {
  background: linear-gradient(90deg, rgba(22, 163, 74, 0.09), rgba(0, 174, 236, 0.05));
  border-color: rgba(22, 163, 74, 0.22);
}

.preflight-result-dock.is-working {
  border-color: rgba(0, 138, 197, 0.28);
}

.preflight-result-dock.is-error {
  background: rgba(220, 38, 38, 0.055);
  border-color: rgba(220, 38, 38, 0.2);
}

.preflight-result-icon {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  color: var(--muted);
  background: var(--surface-sub);
  border-radius: 50%;
  font-family: var(--font-code);
  font-weight: var(--fw-bold);
}

.is-success .preflight-result-icon {
  color: #fff;
  background: var(--ok);
}

.is-working .preflight-result-icon {
  color: var(--accent-strong);
  background: var(--accent-tint);
}

.is-error .preflight-result-icon {
  color: #fff;
  background: var(--danger);
}

.preflight-result-copy {
  min-width: 0;
  max-height: 74px;
  overflow: auto;
}

.preflight-result-copy small {
  display: block;
  margin-bottom: 2px;
  color: var(--muted);
  font-size: 10px;
  font-weight: var(--fw-bold);
  letter-spacing: 0.08em;
}

.preflight-result-copy strong {
  color: var(--lab-ink);
  font-size: 15px;
}

.preflight-result-copy p {
  margin: 3px 0 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.is-error .preflight-result-copy p {
  color: #991b1b;
}

.preflight-result-actions,
.preflight-modal-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--s2);
}

.preflight-result-backdrop {
  --lab-ink: #102235;
  --lab-cyan: var(--bili-blue);
  z-index: 180;
}

.preflight-result-modal {
  grid-template-rows: auto minmax(0, 1fr) auto;
  width: min(720px, 100%);
  outline: none;
  border-top: 4px solid var(--lab-cyan);
}

.preflight-modal-kicker {
  margin: 0 0 4px;
  color: var(--accent-strong);
  font-family: var(--font-code);
  font-size: 10px;
  font-weight: var(--fw-bold);
  letter-spacing: 0.08em;
}

.preflight-modal-close {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  width: 42px;
  height: 42px;
  color: var(--muted);
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: 50%;
  cursor: pointer;
  font-size: 24px;
  line-height: 1;
}

.preflight-modal-body {
  display: grid;
  gap: var(--s4);
}

.preflight-modal-status {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding: var(--s4);
  background: linear-gradient(90deg, rgba(22, 163, 74, 0.09), rgba(0, 174, 236, 0.05));
  border: 1px solid rgba(22, 163, 74, 0.2);
  border-radius: var(--r-sm);
}

.preflight-modal-status > span {
  display: grid;
  flex: 0 0 36px;
  place-items: center;
  width: 36px;
  height: 36px;
  color: #fff;
  background: var(--ok);
  border-radius: 50%;
}

.preflight-modal-status p,
.preflight-modal-next-step {
  margin: 3px 0 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}

.preflight-result-facts,
.preflight-modal-probe dl {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--s3);
  margin: 0;
}

.preflight-result-facts > div,
.preflight-modal-probe dl > div {
  min-width: 0;
  padding: var(--s3);
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
}

.preflight-result-facts dt,
.preflight-modal-probe dt {
  color: var(--muted);
  font-size: 11px;
}

.preflight-result-facts dd,
.preflight-modal-probe dd {
  margin: 4px 0 0;
  color: var(--ink);
  font-family: var(--font-code);
  overflow-wrap: anywhere;
}

.preflight-modal-probe {
  display: grid;
  gap: var(--s3);
}

.preflight-modal-probe dl {
  grid-template-columns: repeat(5, minmax(0, 1fr));
}

.preflight-modal-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s3);
}

.preflight-modal-section-head span {
  color: var(--accent-strong);
  font-family: var(--font-code);
  font-size: 10px;
  font-weight: var(--fw-bold);
}

.preflight-modal-next-step {
  padding: var(--s4);
  background: var(--surface-sub);
  border-left: 3px solid var(--lab-cyan);
}

.preflight-modal-actions {
  padding-top: var(--s3);
  border-top: 1px solid var(--border);
}

.preflight-modal-enter-active,
.preflight-modal-leave-active {
  transition: opacity 180ms ease;
}

.preflight-modal-enter-active .preflight-result-modal,
.preflight-modal-leave-active .preflight-result-modal {
  transition:
    opacity 180ms ease,
    transform 180ms ease;
}

.preflight-modal-enter-from,
.preflight-modal-leave-to,
.preflight-modal-enter-from .preflight-result-modal,
.preflight-modal-leave-to .preflight-result-modal {
  opacity: 0;
}

.preflight-modal-enter-from .preflight-result-modal,
.preflight-modal-leave-to .preflight-result-modal {
  transform: translateY(12px) scale(0.985);
}

.preflight-privacy-note {
  align-items: flex-start;
  justify-content: flex-start;
  margin-top: var(--s5);
  padding-top: var(--s4);
  color: var(--muted);
  border-top: 1px solid var(--border);
  font-size: 12px;
  line-height: 1.6;
}

.preflight-privacy-note strong {
  flex: 0 0 auto;
  color: var(--ink);
}

.preflight-processing-panel {
  position: relative;
  display: grid;
  grid-column: 1 / -1;
  gap: var(--s4);
  padding: var(--s6);
  overflow: hidden;
  background: #fff;
  border: 1px solid var(--border-strong);
  border-radius: var(--r);
}

.preflight-processing-head,
.preflight-processing-status-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s4);
}

.preflight-processing-head h4 {
  margin: 0 0 var(--s2);
  color: var(--lab-ink);
  font-size: 20px;
}

.preflight-processing-head p:not(.preflight-eyebrow) {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
}

.preflight-processing-options {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s3);
}

.preflight-processing-options label {
  display: grid;
  gap: var(--s2);
  min-width: 0;
}

.preflight-processing-options label > span {
  color: var(--muted);
  font-size: 12px;
  font-weight: var(--fw-semibold);
}

.preflight-processing-options select {
  width: 100%;
  min-height: 42px;
  padding: 9px 10px;
  color: var(--ink);
  background: var(--surface-sub);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-sm);
}

.preflight-processing-options select:focus-visible {
  outline: 3px solid var(--accent-ring);
  outline-offset: 2px;
}

.preflight-processing-options .preflight-check-option {
  display: flex;
  align-items: center;
  min-height: 42px;
  margin-top: 22px;
  padding: 9px 10px;
  background: var(--surface-sub);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-sm);
}

.preflight-check-option input {
  width: 18px;
  height: 18px;
  margin: 0;
  accent-color: var(--lab-cyan);
}

.preflight-cost-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  overflow: hidden;
  color: #fff;
  background: rgba(255, 255, 255, 0.15);
  border: 1px solid var(--lab-ink);
  border-radius: var(--r-sm);
}

.preflight-cost-strip > div {
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: var(--s4);
  background: var(--lab-ink);
}

.preflight-cost-strip span {
  color: rgba(255, 255, 255, 0.65);
  font-size: 11px;
}

.preflight-cost-strip strong {
  overflow-wrap: anywhere;
  font-family: var(--font-code);
  font-size: 17px;
}

.preflight-cost-note,
.preflight-processing-error {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
}

.preflight-cost-note {
  color: var(--muted);
}

.preflight-processing-error {
  color: var(--danger);
}

.preflight-processing-status {
  padding: var(--s4);
  background: var(--surface-sub);
  border-left: 3px solid var(--lab-cyan);
}

.preflight-processing-status .preflight-progress-track {
  margin: var(--s3) 0;
}

.preflight-processing-status-head {
  align-items: center;
}

.preflight-processing-status-head span {
  color: var(--muted);
  font-family: var(--font-code);
  font-size: 12px;
}

.preflight-signal-strip {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--s2);
  margin: 0;
}

.preflight-signal-strip > div {
  min-width: 0;
  padding: var(--s3);
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
}

.preflight-signal-strip dt {
  color: var(--muted);
  font-size: 11px;
}

.preflight-signal-strip dd {
  margin: 4px 0 0;
  color: var(--ink);
  font-family: var(--font-code);
  font-size: 13px;
}

.preflight-preview-result {
  display: grid;
  gap: var(--s3);
  padding-top: var(--s4);
  border-top: 1px solid var(--border);
}

.preflight-preview-result video {
  width: 100%;
  max-height: 560px;
  aspect-ratio: 16 / 9;
  background: #08121d;
  object-fit: contain;
}

.preflight-frame-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s3);
}

.preflight-frame-grid figure {
  position: relative;
  min-width: 0;
  aspect-ratio: 16 / 9;
  margin: 0;
  overflow: hidden;
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
}

.preflight-frame-grid img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.preflight-frame-grid figcaption {
  position: absolute;
  right: 6px;
  bottom: 6px;
  padding: 3px 6px;
  color: #fff;
  background: rgba(8, 18, 29, 0.82);
  border-radius: 2px;
  font-family: var(--font-code);
  font-size: 10px;
}

@media (max-width: 860px) {
  .preflight-work-grid {
    grid-template-columns: 1fr;
  }

  .preflight-hero {
    flex-direction: column;
  }

  .preflight-limit-plate {
    width: 100%;
  }

  .preflight-result-dock {
    height: 168px;
    grid-template-columns: 42px minmax(0, 1fr);
  }

  .preflight-result-actions {
    grid-column: 1 / -1;
    justify-content: flex-start;
  }

  .preflight-processing-options,
  .preflight-cost-strip,
  .preflight-signal-strip,
  .preflight-frame-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .preflight-hero h3 {
    font-size: 30px;
  }

  .preflight-route {
    align-items: flex-start;
    flex-direction: column;
  }

  .preflight-route i {
    width: 1px;
    height: 18px;
    margin-left: 20px;
  }

  .preflight-result-dock {
    height: 164px;
    grid-template-columns: 36px minmax(0, 1fr);
    gap: var(--s2);
  }

  .preflight-result-icon {
    width: 36px;
    height: 36px;
  }

  .preflight-result-actions,
  .preflight-modal-actions {
    width: 100%;
  }

  .preflight-result-actions > button,
  .preflight-modal-actions > button {
    flex: 1;
  }

  .preflight-result-facts {
    grid-template-columns: 1fr;
  }

  .preflight-modal-probe dl {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .preflight-processing-panel {
    padding: var(--s4);
  }

  .preflight-processing-options,
  .preflight-cost-strip,
  .preflight-signal-strip,
  .preflight-frame-grid {
    grid-template-columns: 1fr;
  }

  .preflight-processing-head {
    flex-direction: column;
  }
}

@media (prefers-reduced-motion: reduce) {
  .preflight-modal-enter-active,
  .preflight-modal-leave-active,
  .preflight-modal-enter-active .preflight-result-modal,
  .preflight-modal-leave-active .preflight-result-modal {
    transition: none;
  }
}
</style>
