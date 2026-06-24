import { computed, reactive, ref } from 'vue'
import type { Ref } from 'vue'
import {
  analyzeCreatorFeedback,
  chatCreatorFeedback,
  exportCreatorReportMarkdown,
  fetchCreatorFeedbackByBv,
  getCreatorFeedback,
  getCreatorFeedbackDashboard,
  getCreatorFeedbackEvidenceIndexStatus,
  getCreatorFeedbackReport,
  importCreatorFeedbackFile,
  rebuildCreatorFeedbackEvidenceIndex,
  saveCreatorFeedback,
} from '@/api/creator'
import type {
  CreatorFeedback,
  CreatorFeedbackAnalyzePayload,
  CreatorFeedbackChatPayload,
  CreatorFeedbackChatResult,
  CreatorFeedbackDashboard,
  CreatorFeedbackEvidenceIndexStatus,
  CreatorFeedbackFetchPayload,
  CreatorFeedbackFetchResult,
  CreatorFeedbackImportResult,
  CreatorFeedbackReport,
  CreatorFeedbackSavePayload,
} from '@/types/creator'

type FeedbackChatTurn = {
  id: string
  question: string
  result: CreatorFeedbackChatResult | null
  status: 'pending' | 'loading' | 'done' | 'error'
  errorMessage: string
}

let turnCounter = 0
function nextTurnId() {
  return `turn-${Date.now()}-${++turnCounter}`
}

export function useCreatorFeedback(
  selectedTaskId: Ref<string>,
  hasConfirmedPrePublish: Ref<boolean>,
  errorRef: Ref<string>,
  successRef: Ref<string>,
) {
  // ── 状态 ──
  const feedback = ref<CreatorFeedback | null>(null)
  const feedbackDashboard = ref<CreatorFeedbackDashboard | null>(null)
  const feedbackReport = ref<CreatorFeedbackReport | null>(null)
  const feedbackChatResult = ref<CreatorFeedbackChatResult | null>(null)
  const feedbackChatTurns = ref<FeedbackChatTurn[]>([])
  const feedbackFetchResult = ref<CreatorFeedbackFetchResult | null>(null)
  const feedbackImportFile = ref<File | null>(null)
  const feedbackImportWarnings = ref<string[]>([])
  const feedbackEvidenceIndexStatus = ref<CreatorFeedbackEvidenceIndexStatus | null>(null)
  const feedbackEvidenceIndexWarnings = ref<string[]>([])

  // forms
  const feedbackForm = reactive({
    commentSamples: '',
    danmakuSamples: '',
    extraContext: '',
  })

  const feedbackChatForm = reactive({ question: '' })

  const feedbackScriptForm = reactive({
    bvInput: '',
    maxComments: 50,
    maxRepliesPerComment: 20,
    maxDanmaku: 500,
    format: 'both' as 'json' | 'both',
  })

  // UI
  const isFeedbackChatDrawerOpen = ref(false)

  // loading
  const isSavingFeedback = ref(false)
  const isImportingFeedback = ref(false)
  const isFetchingFeedback = ref(false)
  const isAnalyzingFeedback = ref(false)
  const isAskingFeedbackChat = ref(false)
  const isRebuildingFeedbackEvidenceIndex = ref(false)
  const isLoadingFeedbackEvidenceIndexStatus = ref(false)
  const isExportingReportMarkdown = ref(false)

  // ── 计算 ──

  const canEnterFeedback = computed(() => selectedTaskId.value.length > 0 && hasConfirmedPrePublish.value)

  const hasFeedbackSampleInput = computed(
    () => hasText(feedbackForm.commentSamples) || hasText(feedbackForm.danmakuSamples),
  )

  const canRunFeedbackAnalyze = computed(() =>
    Boolean(
      canEnterFeedback.value &&
        !isFetchingFeedback.value &&
        !isAnalyzingFeedback.value &&
        (feedback.value ||
          (feedbackDashboard.value &&
            feedbackDashboard.value.commentCount + feedbackDashboard.value.danmakuCount > 0)),
    ),
  )

  const canAskFeedbackChat = computed(() =>
    Boolean(
      selectedTaskId.value &&
        feedbackReport.value &&
        hasText(feedbackChatForm.question) &&
        !isAskingFeedbackChat.value,
    ),
  )

  const hasFeedbackChatTurns = computed(() => feedbackChatTurns.value.length > 0)

  const feedbackDashboardWarnings = computed(() => {
    const warnings = [...feedbackImportWarnings.value, ...(feedbackDashboard.value?.warnings ?? [])]
    return Array.from(new Set(warnings))
  })

  const feedbackScriptBv = computed(() => extractBvid(feedbackScriptForm.bvInput))

  // ── 工具 ──
  function showError(error: unknown) {
    errorRef.value = error instanceof Error ? error.message : String(error)
  }

  async function optionalRequest<T>(request: () => Promise<T>): Promise<T | undefined> {
    try { return await request() } catch { return undefined }
  }

  // ── 方法 ──

  /** 加载任务关联的反馈数据（feedback → dashboard → report） */
  async function loadFeedbackData(taskId: string) {
    feedback.value = await optionalRequest(() => getCreatorFeedback(taskId)) ?? null
    feedbackDashboard.value = await optionalRequest(() => getCreatorFeedbackDashboard(taskId)) ?? null
  }

  /** 从已填充的表单手动保存反馈样例 */
  async function submitFeedback(taskId: string) {
    if (!canEnterFeedback.value) return
    isSavingFeedback.value = true
    errorRef.value = ''
    try {
      const payload: CreatorFeedbackSavePayload = {}
      if (hasText(feedbackForm.commentSamples)) payload.commentSamples = feedbackForm.commentSamples
      if (hasText(feedbackForm.danmakuSamples)) payload.danmakuSamples = feedbackForm.danmakuSamples
      if (hasText(feedbackForm.extraContext)) payload.extraContext = feedbackForm.extraContext
      feedback.value = await saveCreatorFeedback(taskId, payload)
    } catch (error) {
      showError(error)
    } finally {
      isSavingFeedback.value = false
    }
  }

  function handleFeedbackFileChange(event: Event) {
    const input = event.target as HTMLInputElement
    feedbackImportFile.value = input.files?.[0] ?? null
  }

  async function importFeedbackFile(taskId: string) {
    if (!canEnterFeedback.value || !feedbackImportFile.value) return
    isImportingFeedback.value = true
    errorRef.value = ''
    try {
      const result = await importCreatorFeedbackFile(taskId, feedbackImportFile.value)
      feedbackImportWarnings.value = result.warnings ?? []
      feedback.value = await optionalRequest(() => getCreatorFeedback(taskId)) ?? feedback.value
      feedbackDashboard.value =
        await optionalRequest(() => getCreatorFeedbackDashboard(taskId)) ?? feedbackDashboard.value
    } catch (error) {
      showError(error)
    } finally {
      isImportingFeedback.value = false
    }
  }

  async function fetchFeedbackByBv(taskId: string) {
    if (!canEnterFeedback.value) return
    isFetchingFeedback.value = true
    errorRef.value = ''
    try {
      const bv = extractBvid(feedbackScriptForm.bvInput)
      const payload: CreatorFeedbackFetchPayload = {
        bvInput: feedbackScriptForm.bvInput,
        maxComments: feedbackScriptForm.maxComments,
        maxRepliesPerComment: feedbackScriptForm.maxRepliesPerComment,
        maxDanmaku: feedbackScriptForm.maxDanmaku,
        format: feedbackScriptForm.format,
      }
      const result = await fetchCreatorFeedbackByBv(taskId, payload)
      feedbackFetchResult.value = result
      feedback.value = await optionalRequest(() => getCreatorFeedback(taskId)) ?? feedback.value
      feedbackDashboard.value =
        await optionalRequest(() => getCreatorFeedbackDashboard(taskId)) ?? feedbackDashboard.value
    } catch (error) {
      showError(error)
    } finally {
      isFetchingFeedback.value = false
    }
  }

  async function runAnalyze(taskId: string, payload: CreatorFeedbackAnalyzePayload) {
    if (!canRunFeedbackAnalyze.value) return
    isAnalyzingFeedback.value = true
    errorRef.value = ''
    try {
      feedbackReport.value = await analyzeCreatorFeedback(taskId, payload)
    } catch (error) {
      showError(error)
    } finally {
      isAnalyzingFeedback.value = false
    }
  }

  async function askChat(taskId: string) {
    if (!canAskFeedbackChat.value) return null
    const turn: FeedbackChatTurn = {
      id: nextTurnId(),
      question: feedbackChatForm.question.trim(),
      result: null,
      status: 'loading',
      errorMessage: '',
    }
    feedbackChatTurns.value = [...feedbackChatTurns.value, turn]
    feedbackChatForm.question = ''
    isAskingFeedbackChat.value = true

    try {
      const payload: CreatorFeedbackChatPayload = { question: turn.question }
      const result = await chatCreatorFeedback(taskId, payload)
      updateFeedbackChatTurn(turn.id, { result, status: 'done' })
      feedbackChatResult.value = result
      return result
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      updateFeedbackChatTurn(turn.id, { status: 'error', errorMessage: message })
      showError(error)
      return null
    } finally {
      isAskingFeedbackChat.value = false
    }
  }

  function updateFeedbackChatTurn(turnId: string, patch: Partial<FeedbackChatTurn>) {
    feedbackChatTurns.value = feedbackChatTurns.value.map((t) =>
      t.id === turnId ? { ...t, ...patch } : t,
    )
  }

  function clearFeedbackChatState(clearQuestion = true) {
    feedbackChatTurns.value = []
    feedbackChatResult.value = null
    if (clearQuestion) feedbackChatForm.question = ''
  }

  async function downloadReportMarkdown(taskId: string) {
    if (!selectedTaskId.value || isExportingReportMarkdown.value) return
    isExportingReportMarkdown.value = true
    try {
      const { blob, filename } = await exportCreatorReportMarkdown(taskId)
      triggerBrowserDownload(blob, filename || `creator-report-${taskId}.md`)
      successRef.value = '报告已下载。'
    } catch (error) {
      showError(error)
    } finally {
      isExportingReportMarkdown.value = false
    }
  }

  async function loadEvidenceIndexStatus(taskId: string) {
    if (!taskId || isLoadingFeedbackEvidenceIndexStatus.value) return
    isLoadingFeedbackEvidenceIndexStatus.value = true
    try {
      const result = await getCreatorFeedbackEvidenceIndexStatus(taskId)
      feedbackEvidenceIndexStatus.value = result
      feedbackEvidenceIndexWarnings.value = (result as Record<string, unknown>).warnings as string[] ?? []
    } catch (error) {
      showError(error)
    } finally {
      isLoadingFeedbackEvidenceIndexStatus.value = false
    }
  }

  async function rebuildEvidenceIndex(taskId: string) {
    if (!taskId || isRebuildingFeedbackEvidenceIndex.value) return
    isRebuildingFeedbackEvidenceIndex.value = true
    errorRef.value = ''
    try {
      const result = await rebuildCreatorFeedbackEvidenceIndex(taskId, {})
      feedbackEvidenceIndexStatus.value = (result as Record<string, unknown>).status as CreatorFeedbackEvidenceIndexStatus ?? feedbackEvidenceIndexStatus.value
      if (result.warnings?.length) {
        feedbackEvidenceIndexWarnings.value = result.warnings
      }
      successRef.value = '证据索引重建已触发，可在反馈报告弹窗查看最新状态。'
    } catch (error) {
      showError(error)
    } finally {
      isRebuildingFeedbackEvidenceIndex.value = false
    }
  }

  function openChatDrawer() {
    isFeedbackChatDrawerOpen.value = true
  }

  function closeChatDrawer() {
    isFeedbackChatDrawerOpen.value = false
  }

  function toggleChatDrawer() {
    isFeedbackChatDrawerOpen.value = !isFeedbackChatDrawerOpen.value
  }

  return {
    // 状态
    feedback, feedbackDashboard, feedbackReport,
    feedbackChatResult, feedbackChatTurns, feedbackFetchResult,
    feedbackImportFile, feedbackImportWarnings,
    feedbackEvidenceIndexStatus, feedbackEvidenceIndexWarnings,
    feedbackForm, feedbackChatForm, feedbackScriptForm,
    isFeedbackChatDrawerOpen,
    isSavingFeedback, isImportingFeedback, isFetchingFeedback,
    isAnalyzingFeedback, isAskingFeedbackChat,
    isRebuildingFeedbackEvidenceIndex, isLoadingFeedbackEvidenceIndexStatus,
    isExportingReportMarkdown,
    // 计算
    canEnterFeedback, hasFeedbackSampleInput, canRunFeedbackAnalyze,
    canAskFeedbackChat, hasFeedbackChatTurns,
    feedbackDashboardWarnings, feedbackScriptBv,
    // 方法
    loadFeedbackData, submitFeedback,
    handleFeedbackFileChange, importFeedbackFile,
    fetchFeedbackByBv, runAnalyze, askChat,
    clearFeedbackChatState, updateFeedbackChatTurn,
    downloadReportMarkdown, loadEvidenceIndexStatus, rebuildEvidenceIndex,
    openChatDrawer, closeChatDrawer, toggleChatDrawer,
  }
}

// ── 内部工具 ──
const hasText = (v: string) => v.trim().length > 0

function extractBvid(value: string) {
  const matched = value.match(/BV[0-9A-Za-z]{10}/)
  return matched?.[0] ?? ''
}

function triggerBrowserDownload(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
  URL.revokeObjectURL(url)
}
