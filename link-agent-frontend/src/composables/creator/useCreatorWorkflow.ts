import { computed, ref } from 'vue'
import type { Ref } from 'vue'
import {
  analyzePrePublishWorkflow,
  confirmWorkflowPrePublishSuggestion,
  getWorkflowUsage,
  listWorkflowMessages,
  listWorkflowSteps,
  sendWorkflowMessage,
  startPrePublishWorkflow,
  getPrePublishSuggestion,
} from '@/api/creator'
import type {
  CreatorSuggestion,
  CreatorWorkflowConfirmPayload,
  CreatorWorkflowMessage,
  CreatorWorkflowMessagePayload,
  CreatorWorkflowSession,
  CreatorWorkflowStatus,
  CreatorWorkflowStep,
  LlmApiCallRecord,
  PrePublishAnalyzePayload,
  WorkflowUsageResponse,
} from '@/types/creator'
import { useWorkflowSSE } from '@/composables/useWorkflowSSE'

export function useCreatorWorkflow(
  selectedTaskId: Ref<string>,
  hasSelectedTaskMaterials: Ref<boolean>,
  errorRef: Ref<string>,
) {
  // ── SSE（内部管理，外部无需感知 EventSource）──
  const {
    connect: connectSSE,
    disconnect: disconnectSSE,
    statusText: workflowSseText,
  } = useWorkflowSSE()

  // ── 状态 ──
  const workflowSession = ref<CreatorWorkflowSession | null>(null)
  const workflowMessages = ref<CreatorWorkflowMessage[]>([])
  const workflowSteps = ref<CreatorWorkflowStep[]>([])
  const workflowUsage = ref<WorkflowUsageResponse | null>(null)
  const workflowMessageDraft = ref('')
  const selectedWorkflowMessageId = ref('')
  const workflowUsageError = ref('')
  const suggestion = ref<CreatorSuggestion | null>(null)

  // UI
  const workflowMessageModalOpen = ref(false)
  const workflowProcessModalOpen = ref(false)
  const expandedRawStepIds = ref<Set<string>>(new Set())

  // loading
  const isLoadingWorkflow = ref(false)
  const isSendingWorkflowMessage = ref(false)
  const isAnalyzingPrePublish = ref(false)
  const isConfirmingPrePublish = ref(false)

  // ── 计算属性 ──

  const hasConfirmedPrePublish = computed(() => {
    if (workflowSession.value?.status === 'CONFIRMED') return true
    return false
  })

  const canSendWorkflowMessage = computed(() => {
    const status = workflowSession.value?.status
    return Boolean(
      workflowSession.value &&
        status !== 'RUNNING' &&
        status !== 'CONFIRMED' &&
        status !== 'CANCELLED',
    )
  })

  const canRunPrePublishAnalyze = computed(() => {
    const status = workflowSession.value?.status
    return Boolean(
      selectedTaskId.value &&
        hasSelectedTaskMaterials.value &&
        workflowSession.value &&
        status !== 'RUNNING' &&
        status !== 'CONFIRMED' &&
        status !== 'CANCELLED',
    )
  })

  const canConfirmPrePublish = computed(
    () =>
      Boolean(suggestion.value?.suggestionId) &&
      workflowSession.value?.status === 'WAITING_CONFIRMATION' &&
      !isConfirmingPrePublish.value,
  )

  const selectedWorkflowMessage = computed(() => {
    if (workflowMessages.value.length === 0) return null
    return (
      workflowMessages.value.find((item) => item.messageId === selectedWorkflowMessageId.value) ??
      workflowMessages.value[0] ??
      null
    )
  })

  const workflowStatusText = computed(() => {
    if (!workflowSession.value) return '未创建'
    return workflowSessionLabel(workflowSession.value.status)
  })

  // ── 工具 ──
  function showError(error: unknown) {
    errorRef.value = error instanceof Error ? error.message : String(error)
  }

  async function optionalRequest<T>(request: () => Promise<T>): Promise<T | undefined> {
    try { return await request() } catch { return undefined }
  }

  // ── SSE 事件处理 ──

  function upsertWorkflowMessage(message: CreatorWorkflowMessage) {
    const idx = workflowMessages.value.findIndex((item) => item.messageId === message.messageId)
    if (idx >= 0) {
      workflowMessages.value = workflowMessages.value.map((item, i) => (i === idx ? message : item))
      return
    }
    workflowMessages.value = [...workflowMessages.value, message].sort(
      (a, b) => a.sequenceNo - b.sequenceNo,
    )
  }

  async function refreshWorkflowSuggestion(taskId: string) {
    suggestion.value = (await optionalRequest(() => getPrePublishSuggestion(taskId))) ?? null
  }

  function syncWorkflowSelection(messageId?: string) {
    if (messageId) {
      selectedWorkflowMessageId.value = messageId
      return
    }
    if (!workflowMessages.value.length) return
    selectedWorkflowMessageId.value = workflowMessages.value[workflowMessages.value.length - 1]?.messageId ?? ''
  }

  // ── 工作流核心 ──

  /** 启动/恢复工作流，建立 SSE 连接 */
  async function startWorkflow(taskId: string, resumeLatest = true) {
    disconnectSSE()
    workflowSession.value = null
    workflowMessages.value = []
    workflowSteps.value = []
    workflowMessageDraft.value = ''
    selectedWorkflowMessageId.value = ''

    isLoadingWorkflow.value = true
    try {
      workflowSession.value = await startPrePublishWorkflow(taskId, { resumeLatest })
      workflowMessages.value = workflowSession.value.messages ?? []
      workflowSteps.value =
        (await optionalRequest(() => listWorkflowSteps(taskId, workflowSession.value!.sessionId))) ?? []
      await loadWorkflowUsage(taskId, workflowSession.value!.sessionId, false)
      if (!suggestion.value && isPrePublishSuggestionVisible(workflowSession.value.status)) {
        suggestion.value = (await optionalRequest(() => getPrePublishSuggestion(taskId))) ?? null
      }
      syncWorkflowSelection()

      // SSE 连接
      connectSSE(taskId, workflowSession.value.sessionId, {
        onMessageCreated: (message) => {
          upsertWorkflowMessage(message)
          syncWorkflowSelection()
        },
        onSessionStatus: (status, confirmedResultId, errorMessage) => {
          if (workflowSession.value) {
            workflowSession.value = {
              ...workflowSession.value,
              status: (isWorkflowStatus(status ?? null) ? status : workflowSession.value.status) as CreatorWorkflowStatus,
              confirmedResultId: (confirmedResultId ?? workflowSession.value.confirmedResultId) ?? null,
              errorMessage: errorMessage ?? null,
            }
          }
          if (status === 'WAITING_CONFIRMATION' || status === 'CONFIRMED') {
            void refreshWorkflowSuggestion(taskId)
          }
        },
        onResultReady: (resultTaskId) => {
          void refreshWorkflowSuggestion(resultTaskId)
        },
        onStepStarted: () => { void refreshWorkflowSteps(taskId) },
        onStepCompleted: () => { void refreshWorkflowSteps(taskId) },
        onStepFailed: () => { void refreshWorkflowSteps(taskId) },
      })
    } catch (error) {
      showError(error)
    } finally {
      isLoadingWorkflow.value = false
    }
  }

  async function refreshWorkflowSteps(taskId: string) {
    if (!workflowSession.value) return
    workflowSteps.value =
      (await optionalRequest(() =>
        listWorkflowSteps(taskId, workflowSession.value!.sessionId),
      )) ?? []
  }

  async function loadWorkflowUsage(taskId: string, sessionId: string, reportError = false) {
    try {
      workflowUsage.value = await getWorkflowUsage(taskId, sessionId)
      workflowUsageError.value = ''
    } catch (error) {
      workflowUsage.value = null
      if (reportError) showError(error)
      else workflowUsageError.value = error instanceof Error ? error.message : String(error)
    }
  }

  /** 运行发布前分析 */
  async function runAnalyze(taskId: string, sessionId: string, payload: PrePublishAnalyzePayload) {
    if (!canRunPrePublishAnalyze.value) return
    isAnalyzingPrePublish.value = true
    errorRef.value = ''
    try {
      suggestion.value = await analyzePrePublishWorkflow(taskId, sessionId, payload)
    } catch (error) {
      showError(error)
    } finally {
      isAnalyzingPrePublish.value = false
    }
  }

  /** 确认发布前方案 */
  async function confirmSuggestion(taskId: string, sessionId: string, payload: CreatorWorkflowConfirmPayload) {
    if (!canConfirmPrePublish.value) return
    isConfirmingPrePublish.value = true
    errorRef.value = ''
    try {
      workflowSession.value = await confirmWorkflowPrePublishSuggestion(taskId, sessionId, payload)
    } catch (error) {
      showError(error)
    } finally {
      isConfirmingPrePublish.value = false
    }
  }

  /** 发送补充消息 */
  async function sendSupplement(
    taskId: string,
    sessionId: string,
    payload: CreatorWorkflowMessagePayload,
  ) {
    if (!canSendWorkflowMessage.value || !workflowMessageDraft.value.trim()) return
    isSendingWorkflowMessage.value = true
    errorRef.value = ''
    try {
      await sendWorkflowMessage(taskId, sessionId, payload)
      workflowMessageDraft.value = ''
    } catch (error) {
      showError(error)
    } finally {
      isSendingWorkflowMessage.value = false
    }
  }

  // ── UI 方法 ──

  function openMessageModal() { workflowMessageModalOpen.value = true }
  function closeMessageModal() { workflowMessageModalOpen.value = false }
  function openProcessModal() { workflowProcessModalOpen.value = true }
  function closeProcessModal() { workflowProcessModalOpen.value = false }

  function toggleRawOutput(stepId: string) {
    const next = new Set(expandedRawStepIds.value)
    if (next.has(stepId)) next.delete(stepId)
    else next.add(stepId)
    expandedRawStepIds.value = next
  }

  function isRawOutputExpanded(stepId: string) {
    return expandedRawStepIds.value.has(stepId)
  }

  function workflowCallsForStep(stepId: string, allCalls: LlmApiCallRecord[]) {
    return allCalls.filter((call) => call.workflowStepId === stepId)
  }

  function disconnect() {
    disconnectSSE()
  }

  return {
    // 状态
    workflowSession, workflowMessages, workflowSteps, workflowUsage,
    workflowMessageDraft, selectedWorkflowMessageId, expandedRawStepIds,
    workflowUsageError, suggestion, workflowSseText,
    workflowMessageModalOpen, workflowProcessModalOpen,
    isLoadingWorkflow, isSendingWorkflowMessage, isAnalyzingPrePublish, isConfirmingPrePublish,
    // 计算
    hasConfirmedPrePublish, canSendWorkflowMessage, canRunPrePublishAnalyze, canConfirmPrePublish,
    selectedWorkflowMessage, workflowStatusText,
    // 方法
    startWorkflow, runAnalyze, confirmSuggestion, sendSupplement,
    loadWorkflowUsage, refreshWorkflowSteps,
    openMessageModal, closeMessageModal, openProcessModal, closeProcessModal,
    toggleRawOutput, isRawOutputExpanded, workflowCallsForStep,
    disconnect,
  }
}

// ── 内部工具 ──

function isWorkflowStatus(value: string | null): value is CreatorWorkflowStatus {
  const statuses: CreatorWorkflowStatus[] = [
    'CREATED', 'RUNNING', 'WAITING_CONFIRMATION',
    'CONFIRMED', 'FAILED', 'CANCELLED',
  ]
  return statuses.includes(value as CreatorWorkflowStatus)
}

function isPrePublishSuggestionVisible(status: string) {
  return ['WAITING_CONFIRMATION', 'CONFIRMED'].includes(status)
}

function workflowSessionLabel(status: string) {
  const labels: Record<string, string> = {
    CREATED: '已达工作台', IN_PROGRESS: '进行中', RUNNING: '推理中',
    WAITING_CONFIRMATION: '等待确认', CONFIRMED: '方案已确认',
    COMPLETED: '已完成', FAILED: '未完成', CANCELLED: '已取消',
  }
  return labels[status] ?? status
}
