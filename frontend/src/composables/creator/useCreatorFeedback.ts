import { computed, reactive, ref } from 'vue'
import type { Ref } from 'vue'
import {
  analyzeCreatorFeedback,
  chatCreatorFeedback,
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
  CreatorFeedbackChatTurn,
  CreatorFeedbackDashboard,
  CreatorFeedbackEvidenceIndexResult,
  CreatorFeedbackEvidenceIndexStatus,
  CreatorFeedbackFetchPayload,
  CreatorFeedbackFetchResult,
  CreatorFeedbackImportResult,
  CreatorFeedbackReport,
  CreatorFeedbackSavePayload,
} from '@/types/creator'
import {
  clampScriptNumber,
  extractBvid,
  hasText,
} from '@/composables/creator/creatorWorkspaceUtils'

let turnCounter = 0
function nextTurnId() {
  return `turn-${Date.now()}-${++turnCounter}`
}

export function useCreatorFeedback(
  selectedTaskId: Ref<string>,
  canEnterFeedback: Ref<boolean>,
  errorRef: Ref<string>,
  successRef: Ref<string>,
) {
  let requestVersion = 0

  // ── 状态 ──
  const feedback = ref<CreatorFeedback | null>(null)
  const feedbackDashboard = ref<CreatorFeedbackDashboard | null>(null)
  const feedbackReport = ref<CreatorFeedbackReport | null>(null)
  const feedbackChatResult = ref<CreatorFeedbackChatResult | null>(null)
  const feedbackChatTurns = ref<CreatorFeedbackChatTurn[]>([])
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
  const savedFeedbackFormSnapshot = ref(feedbackFormSnapshot())
  const submittedScriptFormSnapshot = reactive(currentFeedbackScriptForm())

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

  // ── 计算 ──

  const hasFeedbackSampleInput = computed(
    () => hasText(feedbackForm.commentSamples) || hasText(feedbackForm.danmakuSamples),
  )

  const canRunFeedbackAnalyze = computed(() =>
    Boolean(
      canEnterFeedback.value &&
        !isSavingFeedback.value &&
        !isImportingFeedback.value &&
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

  const feedbackDashboardWarnings = computed(() => {
    const warnings = [...feedbackImportWarnings.value, ...(feedbackDashboard.value?.warnings ?? [])]
    return Array.from(new Set(warnings))
  })

  const feedbackScriptBv = computed(() => extractBvid(feedbackScriptForm.bvInput))
  const hasUnsavedTaskFeedbackInput = computed(() =>
    Boolean(
      feedbackFormSnapshot() !== savedFeedbackFormSnapshot.value ||
        feedbackChatForm.question.trim() ||
        feedbackImportFile.value ||
        feedbackScriptFormSnapshot() !== JSON.stringify(submittedScriptFormSnapshot),
    ),
  )

  // ── 工具 ──
  function showError(error: unknown) {
    errorRef.value = error instanceof Error ? error.message : '请求失败'
  }

  function beginRequest() {
    errorRef.value = ''
    successRef.value = ''
  }

  function isCurrentRequest(taskId: string, version: number) {
    return selectedTaskId.value === taskId && requestVersion === version
  }

  function hasPendingFeedbackMutation() {
    return isSavingFeedback.value ||
      isImportingFeedback.value ||
      isFetchingFeedback.value ||
      isAnalyzingFeedback.value
  }

  async function optionalRequest<T>(request: () => Promise<T>): Promise<T | null> {
    try { return await request() } catch { return null }
  }

  // ── 方法 ──

  /** 任务切换时按后端状态恢复反馈数据，避免页面表单成为第二份结果来源。 */
  async function loadFeedbackData(taskId: string, includeReport = false) {
    const version = requestVersion
    const loadedFeedback = await optionalRequest(() => getCreatorFeedback(taskId))
    if (!isCurrentRequest(taskId, version)) return
    feedback.value = loadedFeedback

    const loadedDashboard = await optionalRequest(() => getCreatorFeedbackDashboard(taskId))
    if (!isCurrentRequest(taskId, version)) return
    feedbackDashboard.value = loadedDashboard

    if (includeReport) {
      const loadedReport = await optionalRequest(() => getCreatorFeedbackReport(taskId))
      if (!isCurrentRequest(taskId, version)) return
      feedbackReport.value = loadedReport
    }
  }

  /** 从已填充的表单手动保存反馈样例 */
  async function submitFeedback(): Promise<CreatorFeedback | null> {
    const taskId = selectedTaskId.value
    if (!taskId || !canEnterFeedback.value || hasPendingFeedbackMutation()) return null
    const version = requestVersion
    isSavingFeedback.value = true
    beginRequest()
    try {
      const payload: CreatorFeedbackSavePayload = {}
      if (hasText(feedbackForm.commentSamples)) payload.commentSamples = feedbackForm.commentSamples
      if (hasText(feedbackForm.danmakuSamples)) payload.danmakuSamples = feedbackForm.danmakuSamples
      if (hasText(feedbackForm.extraContext)) payload.extraContext = feedbackForm.extraContext
      const result = await saveCreatorFeedback(taskId, payload)
      if (!isCurrentRequest(taskId, version)) return null
      feedback.value = result
      feedbackReport.value = null
      clearFeedbackChatState()
      feedbackDashboard.value = null
      feedbackFetchResult.value = null
      feedbackImportWarnings.value = []
      savedFeedbackFormSnapshot.value = feedbackFormSnapshot()
      return result
    } catch (error) {
      if (isCurrentRequest(taskId, version)) showError(error)
      return null
    } finally {
      if (requestVersion === version) isSavingFeedback.value = false
    }
  }

  function handleFeedbackFileChange(event: Event) {
    const input = event.target as HTMLInputElement
    feedbackImportFile.value = input.files?.[0] ?? null
    feedbackImportWarnings.value = []
  }

  async function importFeedbackFile(): Promise<CreatorFeedbackImportResult | null> {
    const taskId = selectedTaskId.value
    const file = feedbackImportFile.value
    if (!taskId || !canEnterFeedback.value || !file || hasPendingFeedbackMutation()) return null
    const version = requestVersion
    isImportingFeedback.value = true
    beginRequest()
    try {
      const result = await importCreatorFeedbackFile(taskId, file)
      if (!isCurrentRequest(taskId, version)) return null
      feedbackFetchResult.value = null
      feedbackReport.value = null
      clearFeedbackChatState()
      feedbackImportWarnings.value = result.warnings ?? []

      const loadedFeedback = await optionalRequest(() => getCreatorFeedback(taskId))
      if (!isCurrentRequest(taskId, version)) return null
      feedback.value = loadedFeedback

      const loadedDashboard = await optionalRequest(() => getCreatorFeedbackDashboard(taskId))
      if (!isCurrentRequest(taskId, version)) return null
      feedbackDashboard.value = loadedDashboard
      feedbackImportFile.value = null
      return result
    } catch (error) {
      if (isCurrentRequest(taskId, version)) showError(error)
      return null
    } finally {
      if (requestVersion === version) isImportingFeedback.value = false
    }
  }

  async function fetchFeedbackByBv(): Promise<CreatorFeedbackFetchResult | null> {
    const taskId = selectedTaskId.value
    if (!taskId || !canEnterFeedback.value || hasPendingFeedbackMutation()) return null
    if (!feedbackScriptBv.value) {
      errorRef.value = '请先输入有效 BV 号或视频链接。'
      return null
    }
    const version = requestVersion
    isFetchingFeedback.value = true
    beginRequest()
    try {
      const payload: CreatorFeedbackFetchPayload = {
        bvInput: feedbackScriptForm.bvInput,
        maxComments: clampScriptNumber(feedbackScriptForm.maxComments, 0, 500),
        maxRepliesPerComment: clampScriptNumber(feedbackScriptForm.maxRepliesPerComment, 0, 100),
        maxDanmaku: clampScriptNumber(feedbackScriptForm.maxDanmaku, 0, 2000),
        format: feedbackScriptForm.format,
      }
      const result = await fetchCreatorFeedbackByBv(taskId, payload)
      if (!isCurrentRequest(taskId, version)) return null
      feedbackFetchResult.value = result
      feedbackReport.value = null
      clearFeedbackChatState()
      feedbackImportWarnings.value = result.warnings ?? []

      const loadedFeedback = await optionalRequest(() => getCreatorFeedback(taskId))
      if (!isCurrentRequest(taskId, version)) return null
      feedback.value = loadedFeedback

      const loadedDashboard = await optionalRequest(() => getCreatorFeedbackDashboard(taskId))
      if (!isCurrentRequest(taskId, version)) return null
      feedbackDashboard.value = loadedDashboard
      Object.assign(submittedScriptFormSnapshot, currentFeedbackScriptForm())
      return result
    } catch (error) {
      if (isCurrentRequest(taskId, version)) showError(error)
      return null
    } finally {
      if (requestVersion === version) isFetchingFeedback.value = false
    }
  }

  async function runAnalyze(
    payload: CreatorFeedbackAnalyzePayload,
  ): Promise<CreatorFeedbackReport | null> {
    const taskId = selectedTaskId.value
    if (!taskId || !canRunFeedbackAnalyze.value) return null
    const version = requestVersion
    isAnalyzingFeedback.value = true
    beginRequest()
    try {
      const result = await analyzeCreatorFeedback(taskId, payload)
      if (!isCurrentRequest(taskId, version)) return null
      feedbackReport.value = result
      clearFeedbackChatState()
      return result
    } catch (error) {
      if (isCurrentRequest(taskId, version)) showError(error)
      return null
    } finally {
      if (requestVersion === version) isAnalyzingFeedback.value = false
    }
  }

  async function askChat(): Promise<CreatorFeedbackChatResult | null> {
    const taskId = selectedTaskId.value
    if (!canAskFeedbackChat.value) return null
    const version = requestVersion
    const turn: CreatorFeedbackChatTurn = {
      id: nextTurnId(),
      question: feedbackChatForm.question.trim(),
      result: null,
      status: 'PENDING',
    }
    feedbackChatTurns.value = [...feedbackChatTurns.value, turn]
    feedbackChatForm.question = ''
    isAskingFeedbackChat.value = true
    beginRequest()

    try {
      const payload: CreatorFeedbackChatPayload = { question: turn.question }
      const result = await chatCreatorFeedback(taskId, payload)
      if (!isCurrentRequest(taskId, version)) return null
      updateFeedbackChatTurn(turn.id, { result, status: 'DONE' })
      feedbackChatResult.value = result
      return result
    } catch (error) {
      if (!isCurrentRequest(taskId, version)) return null
      const message = error instanceof Error ? error.message : String(error)
      updateFeedbackChatTurn(turn.id, { status: 'FAILED', errorMessage: message })
      showError(error)
      return null
    } finally {
      if (requestVersion === version) isAskingFeedbackChat.value = false
    }
  }

  function updateFeedbackChatTurn(turnId: string, patch: Partial<CreatorFeedbackChatTurn>) {
    feedbackChatTurns.value = feedbackChatTurns.value.map((t) =>
      t.id === turnId ? { ...t, ...patch } : t,
    )
  }

  function clearFeedbackChatState(clearQuestion = true) {
    feedbackChatTurns.value = []
    feedbackChatResult.value = null
    if (clearQuestion) feedbackChatForm.question = ''
  }

  async function loadEvidenceIndexStatus(): Promise<CreatorFeedbackEvidenceIndexStatus | null> {
    const taskId = selectedTaskId.value
    if (!taskId || isLoadingFeedbackEvidenceIndexStatus.value) return null
    const version = requestVersion
    isLoadingFeedbackEvidenceIndexStatus.value = true
    try {
      const result = await getCreatorFeedbackEvidenceIndexStatus(taskId)
      if (!isCurrentRequest(taskId, version)) return null
      feedbackEvidenceIndexStatus.value = result
      feedbackEvidenceIndexWarnings.value = []
      return result
    } catch (error) {
      if (isCurrentRequest(taskId, version)) showError(error)
      return null
    } finally {
      if (requestVersion === version) isLoadingFeedbackEvidenceIndexStatus.value = false
    }
  }

  async function rebuildEvidenceIndex(): Promise<CreatorFeedbackEvidenceIndexResult | null> {
    const taskId = selectedTaskId.value
    if (
      !taskId ||
      !canEnterFeedback.value ||
      hasPendingFeedbackMutation() ||
      isRebuildingFeedbackEvidenceIndex.value
    ) return null
    const version = requestVersion
    isRebuildingFeedbackEvidenceIndex.value = true
    beginRequest()
    try {
      const result = await rebuildCreatorFeedbackEvidenceIndex(taskId, {})
      if (!isCurrentRequest(taskId, version)) return null
      feedbackEvidenceIndexWarnings.value = result.warnings ?? []
      // 重建接口只返回本次处理统计，额外回读状态才能让弹窗展示最新的待索引和失败数量。
      const status = await optionalRequest(() => getCreatorFeedbackEvidenceIndexStatus(taskId))
      if (!isCurrentRequest(taskId, version)) return null
      if (status) {
        feedbackEvidenceIndexStatus.value = status
      }
      clearFeedbackChatState(false)
      successRef.value = '证据索引重建已触发，可在反馈分析弹窗查看最新状态。'
      return result
    } catch (error) {
      if (isCurrentRequest(taskId, version)) showError(error)
      return null
    } finally {
      if (requestVersion === version) isRebuildingFeedbackEvidenceIndex.value = false
    }
  }

  function resetFeedbackData() {
    requestVersion += 1
    feedback.value = null
    feedbackDashboard.value = null
    feedbackReport.value = null
    feedbackFetchResult.value = null
    feedbackImportFile.value = null
    feedbackImportWarnings.value = []
    feedbackEvidenceIndexStatus.value = null
    feedbackEvidenceIndexWarnings.value = []
    feedbackForm.commentSamples = ''
    feedbackForm.danmakuSamples = ''
    feedbackForm.extraContext = ''
    feedbackScriptForm.bvInput = ''
    feedbackScriptForm.maxComments = 50
    feedbackScriptForm.maxRepliesPerComment = 20
    feedbackScriptForm.maxDanmaku = 500
    feedbackScriptForm.format = 'both'
    savedFeedbackFormSnapshot.value = feedbackFormSnapshot()
    Object.assign(submittedScriptFormSnapshot, currentFeedbackScriptForm())
    isFeedbackChatDrawerOpen.value = false
    clearFeedbackChatState()
    isSavingFeedback.value = false
    isImportingFeedback.value = false
    isFetchingFeedback.value = false
    isAnalyzingFeedback.value = false
    isAskingFeedbackChat.value = false
    isRebuildingFeedbackEvidenceIndex.value = false
    isLoadingFeedbackEvidenceIndexStatus.value = false
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
    // 计算
    hasFeedbackSampleInput, canRunFeedbackAnalyze, canAskFeedbackChat,
    feedbackDashboardWarnings, feedbackScriptBv, hasUnsavedTaskFeedbackInput,
    // 方法
    loadFeedbackData, resetFeedbackData, submitFeedback,
    handleFeedbackFileChange, importFeedbackFile,
    fetchFeedbackByBv, runAnalyze, askChat,
    loadEvidenceIndexStatus, rebuildEvidenceIndex,
  }

  function feedbackFormSnapshot() {
    return JSON.stringify({
      commentSamples: feedbackForm.commentSamples,
      danmakuSamples: feedbackForm.danmakuSamples,
      extraContext: feedbackForm.extraContext,
    })
  }

  function feedbackScriptFormSnapshot() {
    return JSON.stringify(currentFeedbackScriptForm())
  }

  function currentFeedbackScriptForm() {
    return {
      bvInput: feedbackScriptForm.bvInput,
      maxComments: feedbackScriptForm.maxComments,
      maxRepliesPerComment: feedbackScriptForm.maxRepliesPerComment,
      maxDanmaku: feedbackScriptForm.maxDanmaku,
      format: feedbackScriptForm.format,
    }
  }
}
