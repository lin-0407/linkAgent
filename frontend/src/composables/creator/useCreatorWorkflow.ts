import { computed, ref } from 'vue'
import type { Ref } from 'vue'
import {
  alignPrePublishIntent,
  analyzePrePublishWorkflow,
  confirmWorkflowPrePublishSuggestion,
  generatePrePublishManuscriptDraft,
  getPrePublishSuggestion,
  listWorkflowMessages,
  listWorkflowSteps,
  sendWorkflowMessage,
  startPrePublishWorkflow,
} from '@/api/creator'
import { ApiError } from '@/api/http'
import {
  isPrePublishSuggestionVisible,
  isWorkflowStatus,
  workflowSessionLabel,
} from '@/composables/creator/creatorWorkspaceUtils'
import { useWorkflowSSE } from '@/composables/useWorkflowSSE'
import { useCreatorStore } from '@/stores/creatorStore'
import type {
  CreatorSuggestion,
  CreatorWorkflowMessage,
  CreatorWorkflowSession,
  CreatorWorkflowStep,
  PrePublishAnalyzePayload,
  PrePublishDraftResult,
} from '@/types/creator'

type LoadWorkflowOptions = {
  taskId: string
  userId?: string
  resumeLatest?: boolean
}

export function useCreatorWorkflow(
  selectedTaskId: Ref<string>,
  hasSelectedTaskMaterials: Ref<boolean>,
  errorRef: Ref<string>,
  onSuggestionUpdated?: () => void,
) {
  const creatorStore = useCreatorStore()
  const {
    connect: connectSSE,
    disconnect: disconnectSSE,
    statusText: workflowSseText,
  } = useWorkflowSSE()
  let requestVersion = 0
  let messageRefreshVersion = 0
  let stepRefreshVersion = 0

  const workflowSession = ref<CreatorWorkflowSession | null>(null)
  const workflowMessages = ref<CreatorWorkflowMessage[]>([])
  const workflowSteps = ref<CreatorWorkflowStep[]>([])
  const workflowMessageDraft = ref('')
  const selectedWorkflowMessageId = ref('')
  const suggestion = ref<CreatorSuggestion | null>(null)
  const workflowMessageModalOpen = ref(false)
  const isLoadingWorkflow = ref(false)
  const isSendingWorkflowMessage = ref(false)
  const isAligningIntent = ref(false)
  const isAnalyzingPrePublish = ref(false)
  const isConfirmingPrePublish = ref(false)
  const isGeneratingPrePublishDraft = ref(false)

  const isWorkflowCommandRunning = computed(() =>
    isSendingWorkflowMessage.value ||
    isAligningIntent.value ||
    isAnalyzingPrePublish.value ||
    isConfirmingPrePublish.value ||
    isGeneratingPrePublishDraft.value,
  )
  const canSendWorkflowMessage = computed(() => {
    const status = workflowSession.value?.status
    return Boolean(
      workflowSession.value &&
        !isWorkflowCommandRunning.value &&
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
        !isWorkflowCommandRunning.value &&
        status !== 'RUNNING' &&
        status !== 'CONFIRMED' &&
        status !== 'CANCELLED',
    )
  })
  const prePublishAnalyzeUnavailableReason = computed(() => {
    if (!selectedTaskId.value) return '请先选择创作任务。'
    if (!hasSelectedTaskMaterials.value) return '当前任务缺少可分析材料，请先补充标题、简介、文稿或字幕。'
    if (isLoadingWorkflow.value) return '正在从服务端恢复工作流会话，请稍候。'
    if (isSendingWorkflowMessage.value) return '正在发送补充信息，请等待发送完成。'
    if (isGeneratingPrePublishDraft.value) return '正在补全文稿，请等待文稿保存完成。'
    if (isConfirmingPrePublish.value) return '正在确认当前发布方案，请等待操作完成。'
    if (isAnalyzingPrePublish.value) return '发布方案正在生成，请勿重复提交。'
    if (!workflowSession.value) return '工作流会话尚未恢复，请刷新会话后重试。'
    if (workflowSession.value.status === 'RUNNING') return '服务端工作流仍在运行，页面会继续展示执行进度。'
    if (workflowSession.value.status === 'CONFIRMED') return '当前发布方案已经确认，不能再次生成。'
    if (workflowSession.value.status === 'CANCELLED') return '当前工作流会话已取消，请重新进入任务后再试。'
    return ''
  })
  const canConfirmPrePublish = computed(
    () =>
      Boolean(suggestion.value?.suggestionId) &&
      workflowSession.value?.status === 'WAITING_CONFIRMATION' &&
      !isWorkflowCommandRunning.value,
  )
  const selectedWorkflowMessage = computed(() => {
    if (workflowMessages.value.length === 0) return null
    return (
      workflowMessages.value.find((item) => item.messageId === selectedWorkflowMessageId.value) ??
      workflowMessages.value[0] ??
      null
    )
  })
  const workflowStatusText = computed(() =>
    workflowSession.value ? workflowSessionLabel(workflowSession.value.status) : '未创建',
  )

  function showError(error: unknown) {
    errorRef.value = error instanceof Error ? error.message : '请求失败'
  }

  function isCurrentTask(taskId: string, version: number) {
    return selectedTaskId.value === taskId && requestVersion === version
  }

  function isCurrentSession(taskId: string, sessionId: string, version: number) {
    return isCurrentTask(taskId, version) && workflowSession.value?.sessionId === sessionId
  }

  function upsertWorkflowMessage(message: CreatorWorkflowMessage) {
    const messageIndex = workflowMessages.value.findIndex(
      (item) => item.messageId === message.messageId,
    )
    if (messageIndex >= 0) {
      workflowMessages.value = workflowMessages.value.map((item, index) =>
        index === messageIndex ? message : item,
      )
      return
    }
    workflowMessages.value = [...workflowMessages.value, message].sort(
      (left, right) => left.sequenceNo - right.sequenceNo,
    )
  }

  function syncWorkflowSelection(messageId?: string) {
    const selected = messageId
      ? workflowMessages.value.find((item) => item.messageId === messageId)
      : workflowMessages.value.find((item) => item.messageId === selectedWorkflowMessageId.value)
    selectedWorkflowMessageId.value = selected?.messageId ?? workflowMessages.value[0]?.messageId ?? ''
  }

  async function refreshSuggestion(
    taskId: string,
    sessionId: string,
    version = requestVersion,
  ) {
    try {
      const result = await getPrePublishSuggestion(taskId, sessionId)
      if (isCurrentSession(taskId, sessionId, version)) suggestion.value = result
      return isCurrentSession(taskId, sessionId, version) ? result : null
    } catch (error) {
      if (
        error instanceof ApiError &&
        error.status === 404 &&
        isCurrentSession(taskId, sessionId, version)
      ) {
        suggestion.value = null
      }
      // 当前会话明确没有结果时清空；其它临时失败保留旧值，避免网络波动让可用方案消失。
      return null
    }
  }

  async function refreshSteps(taskId: string, sessionId: string, version = requestVersion) {
    const refreshVersion = ++stepRefreshVersion
    try {
      const result = await listWorkflowSteps(taskId, sessionId)
      const isCurrent =
        refreshVersion === stepRefreshVersion && isCurrentSession(taskId, sessionId, version)
      if (isCurrent) workflowSteps.value = result
      return isCurrent ? result : null
    } catch {
      // SSE 可能连续触发刷新；失败时保留上一份步骤，避免进度条瞬间清空。
      return null
    }
  }

  function connectWorkflowEvents(taskId: string, sessionId: string, version: number) {
    connectSSE(taskId, sessionId, {
      onMessageCreated: (message) => {
        if (!isCurrentSession(taskId, sessionId, version)) return
        upsertWorkflowMessage(message)
        syncWorkflowSelection()
      },
      onSessionStatus: (status, confirmedResultId, eventErrorMessage, planGenerationCount) => {
        if (!isCurrentSession(taskId, sessionId, version) || !workflowSession.value) return
        const nextStatus = status ?? null
        workflowSession.value = {
          ...workflowSession.value,
          status: isWorkflowStatus(nextStatus) ? nextStatus : workflowSession.value.status,
          confirmedResultId:
            (confirmedResultId ?? workflowSession.value.confirmedResultId) ?? null,
          planGenerationCount:
            planGenerationCount ?? workflowSession.value.planGenerationCount,
          errorMessage: eventErrorMessage ?? null,
        }
        if (status === 'WAITING_CONFIRMATION' || status === 'CONFIRMED') {
          void refreshSuggestion(taskId, sessionId, version).then((result) => {
            if (result) onSuggestionUpdated?.()
          })
        }
      },
      onResultReady: (resultTaskId) => {
        if (resultTaskId !== taskId) return
        void refreshSuggestion(taskId, sessionId, version).then((result) => {
          if (result) onSuggestionUpdated?.()
        })
      },
      onStepStarted: () => { void refreshSteps(taskId, sessionId, version) },
      onStepCompleted: () => { void refreshSteps(taskId, sessionId, version) },
      onStepFailed: () => { void refreshSteps(taskId, sessionId, version) },
    })
  }

  async function loadWorkflow({
    taskId,
    userId,
    resumeLatest = true,
  }: LoadWorkflowOptions): Promise<CreatorWorkflowSession | null> {
    resetWorkflowState(false)
    workflowMessageDraft.value = creatorStore.workflowMessageDrafts[taskId] ?? ''
    const version = requestVersion
    if (!hasSelectedTaskMaterials.value) {
      errorRef.value = '当前任务没有可加载材料，请重新创建包含标题、简介、文稿或字幕的任务。'
      return null
    }
    isLoadingWorkflow.value = true
    try {
      const session = await startPrePublishWorkflow(taskId, { userId, resumeLatest })
      if (!isCurrentTask(taskId, version)) return null
      workflowSession.value = session
      workflowMessages.value = session.messages ?? []
      syncWorkflowSelection()
      await refreshSteps(taskId, session.sessionId, version)
      if (!suggestion.value && isPrePublishSuggestionVisible(session.status)) {
        await refreshSuggestion(taskId, session.sessionId, version)
      }
      if (!isCurrentSession(taskId, session.sessionId, version)) return null
      connectWorkflowEvents(taskId, session.sessionId, version)
      return session
    } catch (error) {
      if (isCurrentTask(taskId, version)) showError(error)
      return null
    } finally {
      if (requestVersion === version) isLoadingWorkflow.value = false
    }
  }

  /**
   * 从服务端重新读取最新会话后再判断操作门禁，避免页面保留的旧 RUNNING 状态阻断生成。
   */
  async function syncWorkflow(): Promise<CreatorWorkflowSession | null> {
    const taskId = selectedTaskId.value
    if (!taskId) {
      errorRef.value = '请先选择创作任务。'
      return null
    }
    if (!hasSelectedTaskMaterials.value) {
      errorRef.value = '当前任务缺少可分析材料，请先补充标题、简介、文稿或字幕。'
      return null
    }

    const version = requestVersion
    isLoadingWorkflow.value = true
    errorRef.value = ''
    try {
      const previousSessionId = workflowSession.value?.sessionId
      const session = await startPrePublishWorkflow(taskId, { resumeLatest: true })
      if (!isCurrentTask(taskId, version)) return null
      if (previousSessionId && previousSessionId !== session.sessionId) suggestion.value = null
      workflowSession.value = session
      workflowMessages.value = session.messages ?? []
      syncWorkflowSelection()
      await refreshSteps(taskId, session.sessionId, version)
      if (isPrePublishSuggestionVisible(session.status)) {
        await refreshSuggestion(taskId, session.sessionId, version)
      }
      if (!isCurrentSession(taskId, session.sessionId, version)) return null
      connectWorkflowEvents(taskId, session.sessionId, version)
      return session
    } catch (error) {
      if (isCurrentTask(taskId, version)) showError(error)
      return null
    } finally {
      if (requestVersion === version) isLoadingWorkflow.value = false
    }
  }

  async function refreshWorkflow(): Promise<boolean> {
    const taskId = selectedTaskId.value
    const sessionId = workflowSession.value?.sessionId
    if (!taskId || !sessionId) return false
    const version = requestVersion
    const refreshVersion = ++messageRefreshVersion
    isLoadingWorkflow.value = true
    errorRef.value = ''
    try {
      const messages = await listWorkflowMessages(taskId, sessionId)
      if (
        refreshVersion !== messageRefreshVersion ||
        !isCurrentSession(taskId, sessionId, version)
      ) return false
      workflowMessages.value = messages
      syncWorkflowSelection()
      await refreshSteps(taskId, sessionId, version)
      return (
        refreshVersion === messageRefreshVersion &&
        isCurrentSession(taskId, sessionId, version)
      )
    } catch (error) {
      if (isCurrentSession(taskId, sessionId, version)) showError(error)
      return false
    } finally {
      if (
        requestVersion === version &&
        refreshVersion === messageRefreshVersion
      ) isLoadingWorkflow.value = false
    }
  }

  async function generateDraft(extraRequirement = ''): Promise<PrePublishDraftResult | null> {
    const taskId = selectedTaskId.value
    const sessionId = workflowSession.value?.sessionId
    if (!taskId || !sessionId || isWorkflowCommandRunning.value) return null
    const version = requestVersion
    isGeneratingPrePublishDraft.value = true
    errorRef.value = ''
    try {
      const result = await generatePrePublishManuscriptDraft(taskId, sessionId, { extraRequirement })
      if (!isCurrentSession(taskId, sessionId, version)) return null
      upsertWorkflowMessage(result.message)
      workflowSession.value = {
        ...workflowSession.value!,
        status: 'WAITING_USER_INPUT',
        planGenerationCount: 0,
      }
      syncWorkflowSelection(result.message.messageId)
      await refreshWorkflow()
      return isCurrentSession(taskId, sessionId, version) ? result : null
    } catch (error) {
      if (isCurrentSession(taskId, sessionId, version)) showError(error)
      return null
    } finally {
      if (requestVersion === version) isGeneratingPrePublishDraft.value = false
    }
  }

  async function alignIntent(): Promise<CreatorWorkflowMessage | null> {
    const taskId = selectedTaskId.value
    const sessionId = workflowSession.value?.sessionId
    if (!taskId || !sessionId || isWorkflowCommandRunning.value) return null
    const version = requestVersion
    isAligningIntent.value = true
    errorRef.value = ''
    try {
      const message = await alignPrePublishIntent(taskId, sessionId)
      if (!isCurrentSession(taskId, sessionId, version)) return null
      upsertWorkflowMessage(message)
      workflowSession.value = {
        ...workflowSession.value!,
        status: 'WAITING_USER_INPUT',
      }
      syncWorkflowSelection(message.messageId)
      return message
    } catch (error) {
      if (isCurrentSession(taskId, sessionId, version)) showError(error)
      return null
    } finally {
      if (requestVersion === version) isAligningIntent.value = false
    }
  }

  async function runAnalyze(payload: PrePublishAnalyzePayload): Promise<CreatorSuggestion | null> {
    const taskId = selectedTaskId.value
    const sessionId = workflowSession.value?.sessionId
    if (!taskId || !sessionId || !canRunPrePublishAnalyze.value) {
      errorRef.value = prePublishAnalyzeUnavailableReason.value || '当前状态不能生成发布方案，请同步会话后重试。'
      return null
    }
    const version = requestVersion
    isAnalyzingPrePublish.value = true
    errorRef.value = ''
    try {
      const result = await analyzePrePublishWorkflow(taskId, sessionId, payload)
      if (!isCurrentSession(taskId, sessionId, version)) return null
      suggestion.value = result
      workflowSession.value = {
        ...workflowSession.value!,
        status: 'WAITING_CONFIRMATION',
        planGenerationCount: workflowSession.value!.planGenerationCount + 1,
      }
      await refreshWorkflow()
      return isCurrentSession(taskId, sessionId, version) ? result : null
    } catch (error) {
      if (isCurrentSession(taskId, sessionId, version)) {
        if (error instanceof ApiError && error.status === 409) {
          await syncWorkflow()
          if (isCurrentTask(taskId, version)) {
            errorRef.value = error.message || '当前生成请求与服务端状态冲突，请查看最新消息后重试。'
          }
        } else {
          showError(error)
        }
      }
      return null
    } finally {
      if (requestVersion === version) isAnalyzingPrePublish.value = false
    }
  }

  async function confirmSuggestion(): Promise<CreatorWorkflowSession | null> {
    const taskId = selectedTaskId.value
    const sessionId = workflowSession.value?.sessionId
    const suggestionId = suggestion.value?.suggestionId
    if (!taskId || !sessionId || !suggestionId || !canConfirmPrePublish.value) return null
    const version = requestVersion
    isConfirmingPrePublish.value = true
    errorRef.value = ''
    try {
      const session = await confirmWorkflowPrePublishSuggestion(taskId, sessionId, { suggestionId })
      if (!isCurrentTask(taskId, version) || session.sessionId !== sessionId) return null
      workflowSession.value = session
      workflowMessages.value = session.messages ?? workflowMessages.value
      syncWorkflowSelection()
      return session
    } catch (error) {
      if (isCurrentSession(taskId, sessionId, version)) showError(error)
      return null
    } finally {
      if (requestVersion === version) isConfirmingPrePublish.value = false
    }
  }

  async function sendSupplement(contentOverride?: string): Promise<CreatorWorkflowMessage | null> {
    const taskId = selectedTaskId.value
    const sessionId = workflowSession.value?.sessionId
    const content = (contentOverride ?? workflowMessageDraft.value).trim()
    if (!taskId || !sessionId || !content || !canSendWorkflowMessage.value) return null
    const version = requestVersion
    isSendingWorkflowMessage.value = true
    errorRef.value = ''
    try {
      const message = await sendWorkflowMessage(taskId, sessionId, { content })
      if (!isCurrentSession(taskId, sessionId, version)) return null
      const session = workflowSession.value
      if (!session) return null
      upsertWorkflowMessage(message)
      if (contentOverride === undefined) {
        workflowMessageDraft.value = ''
        creatorStore.clearWorkflowMessageDraft(taskId)
      }
      workflowSession.value = {
        ...session,
        status: 'WAITING_USER_INPUT',
        planGenerationCount: 0,
      }
      syncWorkflowSelection(message.messageId)
      return message
    } catch (error) {
      if (isCurrentSession(taskId, sessionId, version)) showError(error)
      return null
    } finally {
      if (requestVersion === version) isSendingWorkflowMessage.value = false
    }
  }

  function markIntentPending() {
    if (!workflowSession.value) return
    workflowSession.value = {
      ...workflowSession.value,
      status: 'WAITING_USER_INPUT',
      planGenerationCount: 0,
    }
  }

  function updateMessageDraft(draft: string) {
    workflowMessageDraft.value = draft
    const taskId = selectedTaskId.value
    if (taskId) creatorStore.setWorkflowMessageDraft(taskId, draft)
  }

  function resetWorkflowState(closeModal = true) {
    requestVersion += 1
    messageRefreshVersion += 1
    stepRefreshVersion += 1
    disconnectSSE()
    workflowSession.value = null
    workflowMessages.value = []
    workflowSteps.value = []
    workflowMessageDraft.value = ''
    selectedWorkflowMessageId.value = ''
    suggestion.value = null
    isLoadingWorkflow.value = false
    isSendingWorkflowMessage.value = false
    isAligningIntent.value = false
    isAnalyzingPrePublish.value = false
    isConfirmingPrePublish.value = false
    isGeneratingPrePublishDraft.value = false
    if (closeModal) workflowMessageModalOpen.value = false
  }

  function disconnect() {
    requestVersion += 1
    disconnectSSE()
  }

  return {
    workflowSession, workflowMessages, workflowSteps,
    workflowMessageDraft, selectedWorkflowMessageId, suggestion,
    workflowMessageModalOpen, workflowSseText,
    isLoadingWorkflow, isSendingWorkflowMessage, isAligningIntent,
    isAnalyzingPrePublish, isConfirmingPrePublish, isGeneratingPrePublishDraft,
    canSendWorkflowMessage, canRunPrePublishAnalyze, canConfirmPrePublish,
    isWorkflowCommandRunning, prePublishAnalyzeUnavailableReason,
    selectedWorkflowMessage, workflowStatusText,
    loadWorkflow, syncWorkflow, refreshWorkflow, generateDraft, alignIntent, runAnalyze,
    confirmSuggestion, sendSupplement, markIntentPending, updateMessageDraft,
    resetWorkflowState, disconnect,
  }
}
