import { computed, reactive, ref } from 'vue'
import type { Ref } from 'vue'
import {
  listCreatorEvalCases,
  listCreatorEvalPromptVersionStats,
  listCreatorEvalResults,
  recordCreatorEvalResult,
} from '@/api/creator'
import type {
  CreatorEvalCase,
  CreatorEvalPromptVersionStats,
  CreatorEvalResult,
  CreatorWorkflowStage,
} from '@/types/creator'

// ═══════════════════════════════════════════
// 工具函数（后续统一收口至 utils/）
// ═══════════════════════════════════════════
const hasText = (v: string) => v.trim().length > 0
const trimToNull = (v: string | undefined) => {
  const t = v?.trim()
  return t ? t : undefined
}
const normalizeOptionalNumber = (v: unknown) => {
  if (typeof v === 'number') return Number.isFinite(v) ? v : undefined
  if (typeof v === 'string' && v.trim()) {
    const n = Number(v)
    return Number.isFinite(n) ? n : undefined
  }
  return undefined
}

export function useCreatorEvaluation(errorRef: Ref<string>) {
  // ── 状态 ──
  const evalCases = ref<CreatorEvalCase[]>([])
  const selectedEvalCaseId = ref('')
  const selectedEvalResultId = ref('')
  const evalStageFilter = ref<'ALL' | CreatorWorkflowStage>('ALL')
  const evalResults = ref<CreatorEvalResult[]>([])
  const evalPromptVersionStats = ref<CreatorEvalPromptVersionStats[]>([])
  const isLoadingEvalCases = ref(false)
  const isLoadingEvalResults = ref(false)
  const isRecordingEvalResult = ref(false)

  const evalResultDraft = reactive({
    taskId: '',
    workflowSessionId: '',
    targetStage: 'PRE_PUBLISH' as CreatorWorkflowStage,
    modelName: 'qwen3',
    promptVersion: '',
    promptHash: '',
    promptSnapshot: '',
    outputSummary: '',
    rawOutput: '',
    elapsedMs: null as number | null,
    promptTokens: null as number | null,
    completionTokens: null as number | null,
    totalTokens: null as number | null,
    failureReason: '',
    readabilityScore: 4,
    relevanceScore: 4,
    completenessScore: 4,
    accuracyScore: 4,
    stabilityScore: 4,
    costScore: 4,
    explainabilityScore: 4,
    reviewerNote: '',
  })

  // ── 计算属性 ──
  const filteredEvalCases = computed(() =>
    evalCases.value.filter((item) =>
      evalStageFilter.value === 'ALL' || item.targetStage === evalStageFilter.value,
    ),
  )

  const selectedEvalCase = computed(() => {
    if (!selectedEvalCaseId.value) return filteredEvalCases.value[0] ?? null
    return (
      evalCases.value.find((item) => item.caseId === selectedEvalCaseId.value) ??
      filteredEvalCases.value[0] ??
      null
    )
  })

  const selectedEvalResult = computed(() => {
    if (evalResults.value.length === 0) return null
    return (
      evalResults.value.find((item) => item.resultId === selectedEvalResultId.value) ??
      evalResults.value[0] ??
      null
    )
  })

  const canRecordEvalResult = computed(() =>
    Boolean(
      selectedEvalCase.value &&
        !isRecordingEvalResult.value &&
        (hasText(evalResultDraft.rawOutput) || hasText(evalResultDraft.failureReason)),
    ),
  )

  // ── 方法 ──
  function showError(error: unknown) {
    errorRef.value = error instanceof Error ? error.message : String(error)
  }

  async function loadEvaluationCases() {
    isLoadingEvalCases.value = true
    try {
      await refreshEvaluationCases(false)
    } catch (error) {
      showError(error)
    } finally {
      isLoadingEvalCases.value = false
    }
  }

  async function refreshEvaluationCases(resetSelection = true) {
    const stage = evalStageFilter.value === 'ALL' ? undefined : evalStageFilter.value
    evalCases.value = await listCreatorEvalCases('default', stage)
    if (evalCases.value.length === 0) {
      selectedEvalCaseId.value = ''
      evalResults.value = []
      selectedEvalResultId.value = ''
      return
    }
    if (resetSelection || !evalCases.value.some((item) => item.caseId === selectedEvalCaseId.value)) {
      selectedEvalCaseId.value = evalCases.value[0]?.caseId ?? ''
    }
    await refreshEvaluationResults(selectedEvalCaseId.value)
  }

  async function refreshEvaluationResults(caseId: string) {
    if (!caseId) {
      evalResults.value = []
      evalPromptVersionStats.value = []
      selectedEvalResultId.value = ''
      return
    }
    isLoadingEvalResults.value = true
    try {
      const [results, promptVersionStats] = await Promise.all([
        listCreatorEvalResults(caseId, 10),
        listCreatorEvalPromptVersionStats(caseId),
      ])
      evalResults.value = results
      evalPromptVersionStats.value = promptVersionStats
      if (
        evalResults.value.length === 0 ||
        !evalResults.value.some((item) => item.resultId === selectedEvalResultId.value)
      ) {
        selectedEvalResultId.value = evalResults.value[0]?.resultId ?? ''
      }
      resetEvalResultDraftFromCase()
    } finally {
      isLoadingEvalResults.value = false
    }
  }

  async function selectEvalCase(caseId: string) {
    selectedEvalCaseId.value = caseId
    await refreshEvaluationResults(caseId)
  }

  function resetEvalResultDraftFromCase() {
    if (!selectedEvalCase.value) return
    evalResultDraft.targetStage = selectedEvalCase.value.targetStage
    evalResultDraft.taskId = selectedEvalCase.value.taskId ?? ''
    evalResultDraft.workflowSessionId = ''
    evalResultDraft.promptVersion = ''
    evalResultDraft.promptHash = ''
    evalResultDraft.promptSnapshot = ''
    evalResultDraft.outputSummary = ''
    evalResultDraft.rawOutput = ''
    evalResultDraft.failureReason = ''
    evalResultDraft.elapsedMs = null
    evalResultDraft.promptTokens = null
    evalResultDraft.completionTokens = null
    evalResultDraft.totalTokens = null
    evalResultDraft.readabilityScore = 4
    evalResultDraft.relevanceScore = 4
    evalResultDraft.completenessScore = 4
    evalResultDraft.accuracyScore = 4
    evalResultDraft.stabilityScore = 4
    evalResultDraft.costScore = 4
    evalResultDraft.explainabilityScore = 4
    evalResultDraft.reviewerNote = ''
  }

  async function submitEvalResult() {
    if (!selectedEvalCase.value || !canRecordEvalResult.value) return
    isRecordingEvalResult.value = true
    errorRef.value = ''
    try {
      const result = await recordCreatorEvalResult(selectedEvalCase.value.caseId, {
        taskId: trimToNull(evalResultDraft.taskId),
        workflowSessionId: trimToNull(evalResultDraft.workflowSessionId),
        targetStage: evalResultDraft.targetStage,
        modelName: trimToNull(evalResultDraft.modelName),
        promptVersion: trimToNull(evalResultDraft.promptVersion),
        promptHash: trimToNull(evalResultDraft.promptHash),
        promptSnapshot: trimToNull(evalResultDraft.promptSnapshot),
        outputSummary: trimToNull(evalResultDraft.outputSummary),
        rawOutput: trimToNull(evalResultDraft.rawOutput),
        elapsedMs: normalizeOptionalNumber(evalResultDraft.elapsedMs),
        promptTokens: normalizeOptionalNumber(evalResultDraft.promptTokens),
        completionTokens: normalizeOptionalNumber(evalResultDraft.completionTokens),
        totalTokens: normalizeOptionalNumber(evalResultDraft.totalTokens),
        failureReason: trimToNull(evalResultDraft.failureReason),
        readabilityScore: normalizeOptionalNumber(evalResultDraft.readabilityScore),
        relevanceScore: normalizeOptionalNumber(evalResultDraft.relevanceScore),
        completenessScore: normalizeOptionalNumber(evalResultDraft.completenessScore),
        accuracyScore: normalizeOptionalNumber(evalResultDraft.accuracyScore),
        stabilityScore: normalizeOptionalNumber(evalResultDraft.stabilityScore),
        costScore: normalizeOptionalNumber(evalResultDraft.costScore),
        explainabilityScore: normalizeOptionalNumber(evalResultDraft.explainabilityScore),
        reviewerNote: trimToNull(evalResultDraft.reviewerNote),
      })
      await refreshEvaluationResults(selectedEvalCase.value.caseId)
      selectedEvalResultId.value = result.resultId
    } catch (error) {
      showError(error)
    } finally {
      isRecordingEvalResult.value = false
    }
  }

  return {
    // 状态
    evalCases,
    selectedEvalCaseId,
    selectedEvalResultId,
    evalStageFilter,
    evalResults,
    evalPromptVersionStats,
    evalResultDraft,
    isLoadingEvalCases,
    isLoadingEvalResults,
    isRecordingEvalResult,
    // 计算
    filteredEvalCases,
    selectedEvalCase,
    selectedEvalResult,
    canRecordEvalResult,
    // 方法
    loadEvaluationCases,
    refreshEvaluationCases,
    refreshEvaluationResults,
    selectEvalCase,
    submitEvalResult,
  }
}
