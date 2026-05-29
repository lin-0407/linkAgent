<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  analyzeCreatorFeedback,
  analyzePrePublishWorkflow,
  confirmWorkflowPrePublishSuggestion,
  createCreatorTask,
  createWorkflowEventSource,
  getCreatorFeedback,
  getCreatorFeedbackReport,
  getCreatorTask,
  getPrePublishSuggestion,
  listCreatorTasks,
  listWorkflowMessages,
  saveCreatorFeedback,
  sendWorkflowMessage,
  startPrePublishWorkflow,
} from '@/api/creator'
import type {
  CreatorFeedback,
  CreatorFeedbackReport,
  CreatorSuggestion,
  CreatorTask,
  CreatorTaskSummary,
  CreatorWorkflowEvent,
  CreatorWorkflowMessage,
  CreatorWorkflowSession,
  CreatorWorkflowStatus,
} from '@/types/creator'

type UnknownRecord = Record<string, unknown>
type GuidanceEditorTarget = 'prePublish' | 'feedback'
type CreatorWorkspaceState = {
  taskId?: string | null
}

const guidanceStorageKey = 'link-agent-creator-guidance'
const legacyPromptStorageKey = 'link-agent-creator-system-prompts'
const workspaceStorageKey = 'link-agent-creator-workspace'
const defaultPrePublishGuidance =
  '标题表达克制、具体，优先说明视频能解决的问题；先总结核心卖点，再给出优化建议；避免夸张措辞。'
const defaultFeedbackGuidance =
  '先归纳观众最关注的问题，再分析争议和误解；建议应能直接转化为下一期选题或互动动作。'

const taskForm = reactive({
  taskName: '',
  titleDraft: '',
  descriptionDraft: '',
  manuscript: '',
  subtitle: '',
})

const prePublishForm = reactive({
  customGuidance: '',
  creatorPreference: '',
  titleStyle: '',
  extraRequirement: '',
})

const feedbackForm = reactive({
  commentSamples: '',
  danmakuSamples: '',
  extraContext: '',
})

const feedbackAnalyzeForm = reactive({
  customGuidance: '',
  analysisFocus: '',
  extraRequirement: '',
})

const tasks = ref<CreatorTaskSummary[]>([])
const selectedTask = ref<CreatorTask | null>(null)
const suggestion = ref<CreatorSuggestion | null>(null)
const feedback = ref<CreatorFeedback | null>(null)
const feedbackReport = ref<CreatorFeedbackReport | null>(null)
const workflowSession = ref<CreatorWorkflowSession | null>(null)
const workflowMessages = ref<CreatorWorkflowMessage[]>([])
const workflowMessageDraft = ref('')
const workflowEventSource = ref<EventSource | null>(null)
const workflowSseText = ref('未连接')
const selectedWorkflowMessageId = ref('')
const restoredTaskId = ref('')
const activeStep = ref('task')
const isLoadingTasks = ref(false)
const isCreatingTask = ref(false)
const isAnalyzingPrePublish = ref(false)
const isConfirmingPrePublish = ref(false)
const isLoadingWorkflow = ref(false)
const isSendingWorkflowMessage = ref(false)
const isSavingFeedback = ref(false)
const isAnalyzingFeedback = ref(false)
const guidanceEditorTarget = ref<GuidanceEditorTarget | null>(null)
const isGuidanceBackdropPointerDown = ref(false)
const errorMessage = ref('')
const successMessage = ref('')

const selectedTaskId = computed(() => selectedTask.value?.taskId ?? '')
const hasSelectedTask = computed(() => selectedTaskId.value.length > 0)
const hasSelectedTaskMaterials = computed(() => (selectedTask.value?.materials.length ?? 0) > 0)
const hasTaskMaterialInput = computed(
  () =>
    hasText(taskForm.titleDraft) ||
    hasText(taskForm.descriptionDraft) ||
    hasText(taskForm.manuscript) ||
    hasText(taskForm.subtitle),
)
const hasFeedbackSampleInput = computed(
  () => hasText(feedbackForm.commentSamples) || hasText(feedbackForm.danmakuSamples),
)
const hasConfirmedPrePublish = computed(() => {
  if (workflowSession.value?.status === 'CONFIRMED') {
    return true
  }
  return selectedTask.value ? hasPrePublishResult(selectedTask.value.status) : false
})
const canEnterFeedback = computed(() => hasSelectedTask.value && hasConfirmedPrePublish.value)
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
    hasSelectedTask.value &&
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
const materialPreview = computed(() => {
  if (!selectedTask.value) {
    return []
  }
  return selectedTask.value.materials.map((item) => ({
    ...item,
    label: materialLabel(item.materialType),
  }))
})

const sellingPoints = computed(() => parseJsonArray(suggestion.value?.sellingPoints))
const riskPoints = computed(() => parseJsonArray(suggestion.value?.riskPoints))
const titleSuggestions = computed(() => parseJsonArray(suggestion.value?.titleSuggestions))
const tagSuggestions = computed(() => parseJsonArray(suggestion.value?.tagSuggestions))
const hotTopics = computed(() => parseJsonArray(feedbackReport.value?.hotTopics))
const controversyPoints = computed(() => parseJsonArray(feedbackReport.value?.controversyPoints))
const misunderstandingPoints = computed(() =>
  parseJsonArray(feedbackReport.value?.misunderstandingPoints),
)
const nextContentSuggestions = computed(() =>
  parseJsonArray(feedbackReport.value?.nextContentSuggestions),
)
const interactionSuggestions = computed(() =>
  parseJsonArray(feedbackReport.value?.interactionSuggestions),
)
const selectedWorkflowMessage = computed(() => {
  if (workflowMessages.value.length === 0) {
    return null
  }
  return (
    workflowMessages.value.find((item) => item.messageId === selectedWorkflowMessageId.value) ??
    workflowMessages.value[0] ??
    null
  )
})
const selectedWorkflowMaterial = computed(() => {
  const message = selectedWorkflowMessage.value
  if (
    !message ||
    message.detailRefType !== 'MATERIAL' ||
    !selectedTask.value ||
    !message.detailRefId
  ) {
    return null
  }
  return (
    selectedTask.value.materials.find((item) => String(item.id) === message.detailRefId) ?? null
  )
})
const workflowStatusText = computed(() => {
  if (!workflowSession.value) {
    return '未创建'
  }
  return workflowSessionLabel(workflowSession.value.status)
})
const guidanceEditorTitle = computed(() => {
  if (guidanceEditorTarget.value === 'prePublish') {
    return '发布前优化指导'
  }
  if (guidanceEditorTarget.value === 'feedback') {
    return '反馈分析指导'
  }
  return ''
})

onMounted(() => {
  loadGuidanceSettings()
  loadWorkspaceState()
  void refreshTasks()
})

onBeforeUnmount(() => {
  closeWorkflowEventSource()
})

async function refreshTasks() {
  isLoadingTasks.value = true
  errorMessage.value = ''
  try {
    tasks.value = await listCreatorTasks(20)
    const targetTask = resolveRefreshTargetTask()
    if (!targetTask) {
      resetSelectedWorkspace()
      persistWorkspaceState({ taskId: null })
      return
    }
    if (targetTask.taskId !== selectedTask.value?.taskId) {
      await selectTask(targetTask.taskId)
    }
  } catch (error) {
    showError(error)
  } finally {
    isLoadingTasks.value = false
  }
}

async function submitTask() {
  isCreatingTask.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const task = await createCreatorTask({
      taskName: taskForm.taskName,
      titleDraft: taskForm.titleDraft,
      descriptionDraft: taskForm.descriptionDraft,
      manuscript: taskForm.manuscript,
      subtitle: taskForm.subtitle,
    })
    selectedTask.value = task
    activeStep.value = 'prePublish'
    suggestion.value = null
    feedback.value = null
    feedbackReport.value = null
    persistWorkspaceState({ taskId: task.taskId })
    await loadPrePublishWorkflow(task.taskId)
    successMessage.value = '创作任务已创建，可以继续做发布前优化。'
    await refreshTasks()
  } catch (error) {
    showError(error)
  } finally {
    isCreatingTask.value = false
  }
}

async function selectTask(taskId: string) {
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const task = await getCreatorTask(taskId)
    selectedTask.value = task
    activeStep.value = 'prePublish'
    persistWorkspaceState({ taskId: task.taskId })
    await loadOptionalResults(task)
    await loadPrePublishWorkflow(taskId)
  } catch (error) {
    showError(error)
  }
}

async function loadOptionalResults(task: CreatorTask) {
  suggestion.value = null
  feedback.value = null
  feedbackReport.value = null

  if (hasPrePublishResult(task.status)) {
    suggestion.value = await optionalRequest(() => getPrePublishSuggestion(task.taskId))
  }

  if (hasFeedbackResult(task.status)) {
    feedback.value = await optionalRequest(() => getCreatorFeedback(task.taskId))
    feedbackReport.value = await optionalRequest(() => getCreatorFeedbackReport(task.taskId))
  }
}

async function loadPrePublishWorkflow(taskId: string) {
  isLoadingWorkflow.value = true
  closeWorkflowEventSource()
  workflowSession.value = null
  workflowMessages.value = []
  workflowMessageDraft.value = ''
  selectedWorkflowMessageId.value = ''
  if (!hasSelectedTaskMaterials.value) {
    errorMessage.value = '当前任务没有可加载材料，请重新创建包含标题、简介、文稿或字幕的任务。'
    isLoadingWorkflow.value = false
    return
  }
  try {
    workflowSession.value = await startPrePublishWorkflow(taskId, {
      userId: selectedTask.value?.userId,
      resumeLatest: true,
    })
    workflowMessages.value = workflowSession.value.messages ?? []
    if (!suggestion.value && isPrePublishSuggestionVisible(workflowSession.value.status)) {
      suggestion.value = await optionalRequest(() => getPrePublishSuggestion(taskId))
    }
    syncWorkflowSelection()
    connectWorkflowEvents(taskId, workflowSession.value.sessionId)
  } catch (error) {
    showError(error)
  } finally {
    isLoadingWorkflow.value = false
  }
}

async function refreshPrePublishWorkflowMessages() {
  if (!selectedTaskId.value) {
    return
  }
  if (!workflowSession.value) {
    await loadPrePublishWorkflow(selectedTaskId.value)
    return
  }

  isLoadingWorkflow.value = true
  errorMessage.value = ''
  try {
    workflowMessages.value = await listWorkflowMessages(
      selectedTaskId.value,
      workflowSession.value.sessionId,
    )
    syncWorkflowSelection()
  } catch (error) {
    showError(error)
  } finally {
    isLoadingWorkflow.value = false
  }
}

async function runPrePublishAnalyze() {
  if (!selectedTaskId.value) {
    return
  }
  if (!hasSelectedTaskMaterials.value) {
    errorMessage.value = '当前任务没有可分析材料，请重新创建包含标题、简介、文稿或字幕的任务。'
    return
  }
  if (!workflowSession.value) {
    await loadPrePublishWorkflow(selectedTaskId.value)
  }
  if (!workflowSession.value || !canRunPrePublishAnalyze.value) {
    return
  }
  isAnalyzingPrePublish.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    suggestion.value = await analyzePrePublishWorkflow(
      selectedTaskId.value,
      workflowSession.value.sessionId,
      {
        customGuidance: prePublishForm.customGuidance,
        creatorPreference: prePublishForm.creatorPreference,
        titleStyle: prePublishForm.titleStyle,
        extraRequirement: prePublishForm.extraRequirement,
      },
    )
    workflowSession.value = {
      ...workflowSession.value,
      status: 'WAITING_CONFIRMATION' as CreatorWorkflowStatus,
    }
    await refreshPrePublishWorkflowMessages()
    successMessage.value = '发布前优化建议已生成，请确认采用后再进入评论弹幕分析。'
  } catch (error) {
    showError(error)
  } finally {
    isAnalyzingPrePublish.value = false
  }
}

async function confirmPrePublishResult() {
  if (!selectedTaskId.value || !workflowSession.value || !suggestion.value?.suggestionId) {
    return
  }
  isConfirmingPrePublish.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    workflowSession.value = await confirmWorkflowPrePublishSuggestion(
      selectedTaskId.value,
      workflowSession.value.sessionId,
      {
        suggestionId: suggestion.value.suggestionId,
      },
    )
    workflowMessages.value = workflowSession.value.messages ?? workflowMessages.value
    selectedTask.value = await getCreatorTask(selectedTaskId.value)
    syncWorkflowSelection()
    activeStep.value = 'feedback'
    successMessage.value = '已采用本轮发布前优化建议，可以继续导入评论弹幕样例。'
    await refreshTasks()
  } catch (error) {
    showError(error)
  } finally {
    isConfirmingPrePublish.value = false
  }
}

async function sendWorkflowSupplement() {
  if (
    !selectedTaskId.value ||
    !workflowSession.value ||
    !canSendWorkflowMessage.value ||
    !hasText(workflowMessageDraft.value)
  ) {
    return
  }
  isSendingWorkflowMessage.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const message = await sendWorkflowMessage(
      selectedTaskId.value,
      workflowSession.value.sessionId,
      {
        content: workflowMessageDraft.value,
      },
    )
    upsertWorkflowMessage(message)
    workflowMessageDraft.value = ''
    syncWorkflowSelection(message.messageId)
    successMessage.value = '补充要求已写入工作流消息流。'
  } catch (error) {
    showError(error)
  } finally {
    isSendingWorkflowMessage.value = false
  }
}

async function submitFeedback() {
  if (!selectedTaskId.value || !canEnterFeedback.value) {
    return
  }
  isSavingFeedback.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    feedback.value = await saveCreatorFeedback(selectedTaskId.value, {
      commentSamples: feedbackForm.commentSamples,
      danmakuSamples: feedbackForm.danmakuSamples,
      extraContext: feedbackForm.extraContext,
    })
    activeStep.value = 'feedback'
    successMessage.value = '评论弹幕样例已保存，可以开始分析。'
  } catch (error) {
    showError(error)
  } finally {
    isSavingFeedback.value = false
  }
}

async function runFeedbackAnalyze() {
  if (!selectedTaskId.value || !canEnterFeedback.value) {
    return
  }
  isAnalyzingFeedback.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    feedbackReport.value = await analyzeCreatorFeedback(selectedTaskId.value, {
      customGuidance: feedbackAnalyzeForm.customGuidance,
      analysisFocus: feedbackAnalyzeForm.analysisFocus,
      extraRequirement: feedbackAnalyzeForm.extraRequirement,
    })
    selectedTask.value = await getCreatorTask(selectedTaskId.value)
    activeStep.value = 'report'
    successMessage.value = '评论弹幕分析完成，反馈报告已保存。'
    await refreshTasks()
  } catch (error) {
    showError(error)
  } finally {
    isAnalyzingFeedback.value = false
  }
}

async function optionalRequest<T>(request: () => Promise<T>) {
  try {
    return await request()
  } catch {
    // 查询历史结果允许 404，因为新任务通常还没有分析产物。
    return null
  }
}

function resolveRefreshTargetTask() {
  const currentTaskId = selectedTask.value?.taskId
  const targetTaskId = currentTaskId || restoredTaskId.value
  if (targetTaskId) {
    const matchedTask = tasks.value.find((task) => task.taskId === targetTaskId)
    if (matchedTask) {
      return matchedTask
    }
  }
  return tasks.value[0] ?? null
}

function resetSelectedWorkspace() {
  closeWorkflowEventSource()
  selectedTask.value = null
  suggestion.value = null
  feedback.value = null
  feedbackReport.value = null
  workflowSession.value = null
  workflowMessages.value = []
  selectedWorkflowMessageId.value = ''
  activeStep.value = 'task'
}

function loadWorkspaceState() {
  const saved = readWorkspaceState()
  if (saved.taskId) {
    restoredTaskId.value = saved.taskId
  }
}

function readWorkspaceState(): CreatorWorkspaceState {
  const savedValue = localStorage.getItem(workspaceStorageKey)
  if (!savedValue) {
    return {}
  }

  try {
    const saved = JSON.parse(savedValue) as unknown
    if (!isRecord(saved)) {
      return {}
    }
    return {
      taskId: typeof saved.taskId === 'string' ? saved.taskId : undefined,
    }
  } catch {
    localStorage.removeItem(workspaceStorageKey)
    return {}
  }
}

function persistWorkspaceState(patch: CreatorWorkspaceState) {
  const previous = readWorkspaceState()
  const next: CreatorWorkspaceState = {
    ...previous,
    ...patch,
  }
  const taskId = patch.taskId === null ? undefined : trimToNull(next.taskId ?? undefined)
  restoredTaskId.value = taskId ?? ''
  localStorage.setItem(
    workspaceStorageKey,
    JSON.stringify({
      ...(taskId ? { taskId } : {}),
    }),
  )
}

function trimToNull(value: string | undefined) {
  const trimmed = value?.trim()
  return trimmed ? trimmed : undefined
}

function connectWorkflowEvents(taskId: string, sessionId: string) {
  closeWorkflowEventSource()
  const eventSource = createWorkflowEventSource(taskId, sessionId)
  workflowEventSource.value = eventSource
  workflowSseText.value = '连接中'

  eventSource.onopen = () => {
    workflowSseText.value = '实时连接'
  }
  eventSource.onerror = () => {
    workflowSseText.value = '连接中断'
  }

  const eventNames = [
    'message_created',
    'session_status',
    'result_ready',
    'heartbeat',
    'step_started',
    'step_completed',
    'step_failed',
  ]
  eventNames.forEach((eventName) => {
    eventSource.addEventListener(eventName, (event) => {
      handleWorkflowEvent(event as MessageEvent<string>)
    })
  })
}

function closeWorkflowEventSource() {
  if (!workflowEventSource.value) {
    return
  }
  workflowEventSource.value.close()
  workflowEventSource.value = null
  workflowSseText.value = '未连接'
}

function handleWorkflowEvent(event: MessageEvent<string>) {
  const data = parseWorkflowEvent(event.data)
  if (!data) {
    return
  }

  if (data.eventType === 'message_created' && isWorkflowMessage(data.payload)) {
    upsertWorkflowMessage(data.payload)
    syncWorkflowSelection()
    return
  }

  if (data.eventType === 'result_ready') {
    void refreshWorkflowSuggestion(data.taskId)
    return
  }

  if (data.eventType === 'session_status' && workflowSession.value) {
    const status = readStringField(data.payload, 'status')
    workflowSession.value = {
      ...workflowSession.value,
      status: isWorkflowStatus(status) ? status : workflowSession.value.status,
      confirmedResultId:
        readStringField(data.payload, 'confirmedResultId') ??
        workflowSession.value.confirmedResultId,
      errorMessage: readStringField(data.payload, 'errorMessage'),
    }
    if (status === 'WAITING_CONFIRMATION' || status === 'CONFIRMED') {
      void refreshWorkflowSuggestion(data.taskId)
    }
  }
}

function parseWorkflowEvent(value: string) {
  try {
    return JSON.parse(value) as CreatorWorkflowEvent
  } catch {
    return null
  }
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

async function refreshWorkflowSuggestion(taskId: string) {
  suggestion.value = await optionalRequest(() => getPrePublishSuggestion(taskId))
}

function isWorkflowMessage(value: unknown): value is CreatorWorkflowMessage {
  return (
    isRecord(value) &&
    typeof value.messageId === 'string' &&
    typeof value.sessionId === 'string' &&
    typeof value.content === 'string' &&
    typeof value.sequenceNo === 'number'
  )
}

function parseJsonArray(value: string | null | undefined) {
  if (!value) {
    return []
  }

  try {
    const parsed = JSON.parse(value) as unknown
    return Array.isArray(parsed) ? parsed : [parsed]
  } catch {
    return [value]
  }
}

function formatValue(value: unknown) {
  if (value === null || value === undefined) {
    return ''
  }
  if (typeof value === 'string') {
    return value
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value)
  }
  return JSON.stringify(value, null, 2)
}

function getRecordText(value: unknown, key: string) {
  if (isRecord(value)) {
    const text = value[key]
    return typeof text === 'string' ? text : ''
  }
  return ''
}

function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function readStringField(value: unknown, key: string) {
  if (!isRecord(value)) {
    return null
  }
  const fieldValue = value[key]
  return typeof fieldValue === 'string' ? fieldValue : null
}

function isWorkflowStatus(value: string | null): value is CreatorWorkflowStatus {
  return Boolean(
    value &&
    [
      'CREATED',
      'CONTEXT_LOADING',
      'WAITING_USER_INPUT',
      'RUNNING',
      'WAITING_CONFIRMATION',
      'CONFIRMED',
      'FAILED',
      'CANCELLED',
    ].includes(value),
  )
}

function hasText(value: string) {
  return value.trim().length > 0
}

function previewWorkflowMessage(value: string) {
  const normalized = value.replace(/\s+/g, ' ').trim()
  if (!normalized) {
    return '空消息'
  }
  return normalized.length > 64 ? `${normalized.slice(0, 64)}...` : normalized
}

function workflowRoleLabel(role: string) {
  const labels: Record<string, string> = {
    SYSTEM: '系统',
    USER: '用户',
    AGENT: 'Agent',
    TOOL: '工具',
    RESULT: '结果',
  }
  return labels[role] ?? role
}

function workflowContentTypeLabel(contentType: string) {
  const labels: Record<string, string> = {
    TEXT: '文本',
    MATERIAL_SUMMARY: '材料摘要',
    RESULT_CARD: '结果卡片',
    ERROR: '错误',
  }
  return labels[contentType] ?? contentType
}

function workflowSessionLabel(status: string) {
  const labels: Record<string, string> = {
    CREATED: '已创建',
    CONTEXT_LOADING: '装载中',
    WAITING_USER_INPUT: '等待补充',
    RUNNING: '运行中',
    WAITING_CONFIRMATION: '等待确认',
    CONFIRMED: '已确认',
    FAILED: '失败',
    CANCELLED: '已取消',
  }
  return labels[status] ?? status
}

function isPrePublishSuggestionVisible(status: string) {
  return ['WAITING_CONFIRMATION', 'CONFIRMED'].includes(status)
}

function hasPrePublishResult(status: string) {
  return [
    'PRE_PUBLISH_ANALYZED',
    'FEEDBACK_ANALYZED',
    'COMPETITOR_ANALYZED',
    'ANALYZED',
    'ARCHIVED',
  ].includes(status)
}

function hasFeedbackResult(status: string) {
  return ['FEEDBACK_ANALYZED', 'COMPETITOR_ANALYZED', 'ANALYZED', 'ARCHIVED'].includes(status)
}

function syncWorkflowSelection(messageId?: string) {
  const selected = messageId
    ? workflowMessages.value.find((item) => item.messageId === messageId)
    : workflowMessages.value.find((item) => item.messageId === selectedWorkflowMessageId.value)

  if (selected) {
    selectedWorkflowMessageId.value = selected.messageId
    return
  }

  selectedWorkflowMessageId.value = workflowMessages.value[0]?.messageId ?? ''
}

function openGuidanceEditor(target: GuidanceEditorTarget) {
  guidanceEditorTarget.value = target
}

function closeGuidanceEditor() {
  persistGuidanceSettings()
  guidanceEditorTarget.value = null
  isGuidanceBackdropPointerDown.value = false
}

function handleGuidanceBackdropPointerDown(event: PointerEvent) {
  isGuidanceBackdropPointerDown.value = event.target === event.currentTarget
}

function handleGuidanceBackdropClick(event: MouseEvent) {
  if (isGuidanceBackdropPointerDown.value && event.target === event.currentTarget) {
    closeGuidanceEditor()
    return
  }
  isGuidanceBackdropPointerDown.value = false
}

function resetCurrentGuidance() {
  if (guidanceEditorTarget.value === 'prePublish') {
    prePublishForm.customGuidance = defaultPrePublishGuidance
  }
  if (guidanceEditorTarget.value === 'feedback') {
    feedbackAnalyzeForm.customGuidance = defaultFeedbackGuidance
  }
}

function loadGuidanceSettings() {
  // 旧版本曾保存完整系统提示词，主动移除以避免在前端继续保留受保护规则。
  localStorage.removeItem(legacyPromptStorageKey)
  const savedValue = localStorage.getItem(guidanceStorageKey)
  if (!savedValue) {
    return
  }

  try {
    const saved = JSON.parse(savedValue) as {
      prePublishGuidance?: string
      feedbackGuidance?: string
    }
    if (saved.prePublishGuidance) {
      prePublishForm.customGuidance = saved.prePublishGuidance
    }
    if (saved.feedbackGuidance) {
      feedbackAnalyzeForm.customGuidance = saved.feedbackGuidance
    }
  } catch {
    localStorage.removeItem(guidanceStorageKey)
  }
}

function persistGuidanceSettings() {
  localStorage.setItem(
    guidanceStorageKey,
    JSON.stringify({
      prePublishGuidance: prePublishForm.customGuidance,
      feedbackGuidance: feedbackAnalyzeForm.customGuidance,
    }),
  )
}

function materialLabel(type: string) {
  const labels: Record<string, string> = {
    TITLE_DRAFT: '标题草稿',
    DESCRIPTION_DRAFT: '简介草稿',
    MANUSCRIPT: '文稿',
    SUBTITLE: '字幕',
  }
  return labels[type] ?? type
}

function statusLabel(status: string) {
  const labels: Record<string, string> = {
    DRAFT: '草稿',
    PRE_PUBLISH_ANALYZED: '已发布前优化',
    FEEDBACK_ANALYZED: '已反馈分析',
    ANALYZED: '已分析',
    ARCHIVED: '已归档',
  }
  return labels[status] ?? status
}

function shortId(value: string) {
  return value.length <= 14 ? value : `${value.slice(0, 8)}...${value.slice(-4)}`
}

function formatDate(value: string) {
  if (!value) {
    return '-'
  }
  return value.replace('T', ' ').slice(0, 16)
}

function showError(error: unknown) {
  errorMessage.value = error instanceof Error ? error.message : '请求失败'
}
</script>

<template>
  <section class="creator-shell">
    <header class="creator-header">
      <div>
        <p class="creator-kicker">Creator Copilot</p>
        <h2>UP 主智能工作台</h2>
        <p>从稿件输入到发布前优化，再到评论弹幕复盘，直接在同一个页面验证后端闭环。</p>
      </div>
      <div class="creator-status-strip" aria-label="Creator workflow status">
        <span :class="{ active: Boolean(selectedTask) }">任务</span>
        <span :class="{ active: Boolean(workflowSession) }">工作流</span>
        <span :class="{ active: Boolean(suggestion) }">发布建议</span>
        <span :class="{ active: Boolean(feedbackReport) }">反馈报告</span>
      </div>
    </header>

    <div class="creator-layout">
      <aside class="creator-task-rail">
        <div class="creator-panel compact-panel">
          <div class="creator-panel-title">
            <span>任务列表</span>
            <button type="button" class="creator-ghost-button" @click="refreshTasks">
              {{ isLoadingTasks ? '读取中' : '刷新' }}
            </button>
          </div>

          <div class="creator-task-list">
            <button
              v-for="task in tasks"
              :key="task.taskId"
              type="button"
              class="creator-task-item"
              :class="{ active: task.taskId === selectedTaskId }"
              @click="selectTask(task.taskId)"
            >
              <strong>{{ task.taskName }}</strong>
              <span>{{ statusLabel(task.status) }} · {{ task.materialCount }} 份材料</span>
              <small>{{ shortId(task.taskId) }} · {{ formatDate(task.updateTime) }}</small>
            </button>
            <p v-if="!isLoadingTasks && tasks.length === 0" class="creator-muted">
              还没有创作任务，先在右侧创建一个。
            </p>
          </div>
        </div>

        <div v-if="selectedTask" class="creator-panel compact-panel">
          <div class="creator-panel-title">
            <span>当前任务</span>
            <b>{{ statusLabel(selectedTask.status) }}</b>
          </div>
          <code class="creator-task-id">{{ selectedTask.taskId }}</code>
          <div class="creator-material-list">
            <article v-for="material in materialPreview" :key="material.id">
              <strong>{{ material.label }}</strong>
              <p>{{ material.content }}</p>
            </article>
          </div>
        </div>
      </aside>

      <section class="creator-main">
        <nav class="creator-tabs" aria-label="Creator workflow tabs">
          <button
            type="button"
            :class="{ active: activeStep === 'task' }"
            @click="activeStep = 'task'"
          >
            任务输入
          </button>
          <button
            type="button"
            :disabled="!hasSelectedTask"
            :class="{ active: activeStep === 'prePublish' }"
            @click="activeStep = 'prePublish'"
          >
            发布前优化
          </button>
          <button
            type="button"
            :disabled="!canEnterFeedback"
            :class="{ active: activeStep === 'feedback' }"
            @click="activeStep = 'feedback'"
          >
            评论弹幕
          </button>
          <button
            type="button"
            :disabled="!feedbackReport"
            :class="{ active: activeStep === 'report' }"
            @click="activeStep = 'report'"
          >
            分析结果
          </button>
        </nav>

        <div v-if="errorMessage" class="creator-alert error-alert">
          <strong>请求失败</strong>
          <span>{{ errorMessage }}</span>
        </div>

        <div v-if="successMessage" class="creator-alert success-alert">
          <strong>操作完成</strong>
          <span>{{ successMessage }}</span>
        </div>

        <section v-if="activeStep === 'task'" class="creator-section">
          <div class="creator-section-head">
            <div>
              <p class="creator-kicker">Step 1</p>
              <h3>创建创作任务</h3>
            </div>
            <button
              type="button"
              class="creator-primary-button"
              :disabled="!hasTaskMaterialInput || isCreatingTask"
              @click="submitTask"
            >
              {{ isCreatingTask ? '创建中...' : '创建任务' }}
            </button>
          </div>

          <div class="creator-form-grid">
            <label>
              <span>任务名称</span>
              <input
                v-model="taskForm.taskName"
                type="text"
                maxlength="128"
                placeholder="填写本期视频主题"
              />
            </label>
            <label>
              <span>标题草稿</span>
              <input
                v-model="taskForm.titleDraft"
                type="text"
                maxlength="200"
                placeholder="输入一个粗标题"
              />
            </label>
            <label>
              <span>简介草稿</span>
              <textarea
                v-model="taskForm.descriptionDraft"
                maxlength="2000"
                placeholder="粘贴 B 站简介初稿"
              ></textarea>
            </label>
            <label class="span-full">
              <span>文稿</span>
              <textarea
                v-model="taskForm.manuscript"
                maxlength="20000"
                placeholder="粘贴脚本、口播稿或整理后的文稿"
              ></textarea>
            </label>
            <label class="span-full">
              <span>字幕</span>
              <textarea
                v-model="taskForm.subtitle"
                maxlength="20000"
                placeholder="可选：粘贴字幕文本"
              ></textarea>
            </label>
          </div>
        </section>

        <section v-if="activeStep === 'prePublish'" class="creator-section">
          <div class="creator-section-head">
            <div>
              <p class="creator-kicker">Step 2</p>
              <h3>发布前优化 Agent</h3>
            </div>
            <div class="creator-action-row">
              <button
                type="button"
                class="creator-secondary-action"
                @click="openGuidanceEditor('prePublish')"
              >
                创作指导
              </button>
              <button
                type="button"
                class="creator-primary-button"
                :disabled="!canRunPrePublishAnalyze || isAnalyzingPrePublish"
                @click="runPrePublishAnalyze"
              >
                {{ isAnalyzingPrePublish ? '分析中...' : '生成建议' }}
              </button>
            </div>
          </div>

          <div class="creator-workflow-grid">
            <section class="creator-workflow-stream" aria-label="发布前优化消息流">
              <header class="creator-workflow-head">
                <div>
                  <p class="creator-kicker">Workflow</p>
                  <h4>发布前优化消息流</h4>
                </div>
                <div class="creator-workflow-head-actions">
                  <span>{{ workflowStatusText }}</span>
                  <span
                    class="creator-sse-status"
                    :class="{ active: workflowSseText === '实时连接' }"
                  >
                    {{ workflowSseText }}
                  </span>
                  <button
                    type="button"
                    class="creator-ghost-button"
                    :disabled="!hasSelectedTask || !hasSelectedTaskMaterials || isLoadingWorkflow"
                    @click="refreshPrePublishWorkflowMessages"
                  >
                    {{ isLoadingWorkflow ? '载入中' : '刷新消息' }}
                  </button>
                </div>
              </header>

              <div class="creator-workflow-message-list">
                <button
                  v-for="message in workflowMessages"
                  :key="message.messageId"
                  type="button"
                  class="creator-workflow-message"
                  :class="[
                    `role-${message.role.toLowerCase()}`,
                    { active: message.messageId === selectedWorkflowMessage?.messageId },
                  ]"
                  @click="selectedWorkflowMessageId = message.messageId"
                >
                  <small>
                    #{{ message.sequenceNo }} · {{ workflowRoleLabel(message.role) }} ·
                    {{ formatDate(message.createTime) }}
                  </small>
                  <strong>{{ previewWorkflowMessage(message.content) }}</strong>
                  <span>{{ workflowContentTypeLabel(message.contentType) }}</span>
                </button>

                <p v-if="!isLoadingWorkflow && workflowMessages.length === 0" class="creator-muted">
                  {{
                    hasSelectedTaskMaterials
                      ? '还没有工作流消息，选择任务后会自动装载材料。'
                      : '当前任务没有材料，无法装载发布前优化工作流。'
                  }}
                </p>
              </div>
            </section>

            <section class="creator-workflow-detail" aria-label="工作流消息详情">
              <header class="creator-workflow-head">
                <div>
                  <p class="creator-kicker">Detail</p>
                  <h4>消息详情</h4>
                </div>
                <span v-if="selectedWorkflowMessage" class="creator-parse-status">
                  {{ workflowContentTypeLabel(selectedWorkflowMessage.contentType) }}
                </span>
              </header>

              <article v-if="selectedWorkflowMessage" class="creator-workflow-detail-body">
                <small>
                  {{ workflowRoleLabel(selectedWorkflowMessage.role) }} ·
                  {{ formatDate(selectedWorkflowMessage.createTime) }}
                </small>
                <strong>
                  {{
                    selectedWorkflowMaterial
                      ? materialLabel(selectedWorkflowMaterial.materialType)
                      : workflowContentTypeLabel(selectedWorkflowMessage.contentType)
                  }}
                </strong>
                <p v-if="selectedWorkflowMaterial">{{ selectedWorkflowMessage.content }}</p>
                <pre>{{
                  selectedWorkflowMaterial?.content || selectedWorkflowMessage.content
                }}</pre>
              </article>

              <article v-else class="creator-empty-result">
                <strong>未选择消息</strong>
                <span>点击左侧消息可以查看完整材料或过程内容。</span>
              </article>
            </section>

            <form class="creator-workflow-composer" @submit.prevent="sendWorkflowSupplement">
              <textarea
                v-model="workflowMessageDraft"
                maxlength="2000"
                :disabled="!canSendWorkflowMessage || isSendingWorkflowMessage"
                placeholder="补充发布前优化要求，例如：标题更适合 Java 后端学习者，不要标题党"
              ></textarea>
              <button
                type="submit"
                class="creator-primary-button"
                :disabled="
                  !canSendWorkflowMessage ||
                  !hasText(workflowMessageDraft) ||
                  isSendingWorkflowMessage
                "
              >
                {{ isSendingWorkflowMessage ? '发送中...' : '发送消息' }}
              </button>
            </form>
          </div>

          <div class="creator-form-grid">
            <label>
              <span>创作者偏好</span>
              <textarea
                v-model="prePublishForm.creatorPreference"
                maxlength="500"
                placeholder="如：表达克制，面向技术学习者"
              ></textarea>
            </label>
            <label>
              <span>标题风格</span>
              <input
                v-model="prePublishForm.titleStyle"
                type="text"
                maxlength="100"
                placeholder="如：经验分享 / 问题解决"
              />
            </label>
            <label class="span-full">
              <span>额外要求</span>
              <textarea
                v-model="prePublishForm.extraRequirement"
                maxlength="500"
                placeholder="补充标题、简介或标签要求"
              ></textarea>
            </label>
          </div>

          <div v-if="suggestion" class="creator-result-grid">
            <article class="creator-confirm-panel span-full">
              <div>
                <span>确认状态</span>
                <strong>{{ workflowStatusText }}</strong>
                <p>
                  {{
                    hasConfirmedPrePublish
                      ? '本轮发布前优化建议已确认，评论弹幕阶段已开放。'
                      : '建议生成后不会自动推进阶段，需要你确认采用。'
                  }}
                </p>
              </div>
              <button
                type="button"
                class="creator-primary-button"
                :disabled="!canConfirmPrePublish"
                @click="confirmPrePublishResult"
              >
                {{
                  hasConfirmedPrePublish
                    ? '已采用'
                    : isConfirmingPrePublish
                      ? '确认中...'
                      : '采用本轮建议'
                }}
              </button>
            </article>
            <article class="creator-result-block span-full">
              <span>内容摘要</span>
              <p>{{ suggestion.contentSummary || '未解析到摘要' }}</p>
            </article>
            <article class="creator-result-block">
              <span>目标受众</span>
              <p>{{ suggestion.audienceProfile || '未解析到受众判断' }}</p>
            </article>
            <article class="creator-result-block">
              <span>建议分区</span>
              <p>{{ suggestion.partitionSuggestion || '未解析到分区建议' }}</p>
            </article>
            <article class="creator-result-block span-full">
              <span>标题建议</span>
              <div class="creator-list">
                <section v-for="(item, index) in titleSuggestions" :key="index">
                  <strong>{{ getRecordText(item, 'title') || formatValue(item) }}</strong>
                  <p v-if="getRecordText(item, 'reason')">
                    理由：{{ getRecordText(item, 'reason') }}
                  </p>
                  <p v-if="getRecordText(item, 'risk')">风险：{{ getRecordText(item, 'risk') }}</p>
                </section>
              </div>
            </article>
            <article class="creator-result-block">
              <span>核心卖点</span>
              <ul>
                <li v-for="(item, index) in sellingPoints" :key="index">{{ formatValue(item) }}</li>
              </ul>
            </article>
            <article class="creator-result-block">
              <span>风险点</span>
              <ul>
                <li v-for="(item, index) in riskPoints" :key="index">{{ formatValue(item) }}</li>
              </ul>
            </article>
            <article class="creator-result-block">
              <span>标签建议</span>
              <div class="creator-chip-list">
                <b v-for="(item, index) in tagSuggestions" :key="index">{{ formatValue(item) }}</b>
              </div>
            </article>
            <article class="creator-result-block">
              <span>简介建议</span>
              <p>{{ suggestion.descriptionSuggestion || '未解析到简介建议' }}</p>
            </article>
          </div>
        </section>

        <section v-if="activeStep === 'feedback'" class="creator-section">
          <div class="creator-section-head">
            <div>
              <p class="creator-kicker">Step 3</p>
              <h3>评论弹幕样例</h3>
            </div>
            <div class="creator-action-row">
              <button
                type="button"
                class="creator-secondary-action"
                @click="openGuidanceEditor('feedback')"
              >
                分析指导
              </button>
              <button
                type="button"
                class="creator-secondary-action"
                :disabled="!canEnterFeedback || !hasFeedbackSampleInput || isSavingFeedback"
                @click="submitFeedback"
              >
                {{ isSavingFeedback ? '保存中...' : '保存样例' }}
              </button>
              <button
                type="button"
                class="creator-primary-button"
                :disabled="!canEnterFeedback || isAnalyzingFeedback"
                @click="runFeedbackAnalyze"
              >
                {{ isAnalyzingFeedback ? '分析中...' : '分析反馈' }}
              </button>
            </div>
          </div>

          <div class="creator-form-grid">
            <label>
              <span>评论样例</span>
              <textarea
                v-model="feedbackForm.commentSamples"
                maxlength="20000"
                placeholder="粘贴已整理的评论样例"
              ></textarea>
            </label>
            <label>
              <span>弹幕样例</span>
              <textarea
                v-model="feedbackForm.danmakuSamples"
                maxlength="20000"
                placeholder="粘贴弹幕样例，可换行分隔"
              ></textarea>
            </label>
            <label class="span-full">
              <span>补充背景</span>
              <textarea
                v-model="feedbackForm.extraContext"
                maxlength="500"
                placeholder="说明样例来源、时间段或反馈场景"
              ></textarea>
            </label>
            <label>
              <span>分析重点</span>
              <textarea
                v-model="feedbackAnalyzeForm.analysisFocus"
                maxlength="500"
                placeholder="如：判断观众是否理解项目价值"
              ></textarea>
            </label>
            <label>
              <span>额外要求</span>
              <textarea
                v-model="feedbackAnalyzeForm.extraRequirement"
                maxlength="500"
                placeholder="补充报告输出偏好"
              ></textarea>
            </label>
          </div>

          <article v-if="feedback" class="creator-result-block span-full">
            <span>已保存样例</span>
            <p>{{ formatDate(feedback.updateTime) }} 更新，后端已保存用户主动提供的数据。</p>
          </article>
        </section>

        <section v-if="activeStep === 'report'" class="creator-section">
          <div class="creator-section-head">
            <div>
              <p class="creator-kicker">Step 4</p>
              <h3>反馈分析结果</h3>
            </div>
            <span v-if="feedbackReport" class="creator-parse-status">
              {{ feedbackReport.parseStatus }}
            </span>
          </div>

          <div v-if="feedbackReport" class="creator-result-grid">
            <article class="creator-result-block span-full">
              <span>整体反馈</span>
              <p>{{ feedbackReport.feedbackSummary || '未解析到整体反馈' }}</p>
            </article>
            <article class="creator-result-block">
              <span>情绪倾向</span>
              <p>{{ feedbackReport.sentimentSummary || '未解析到情绪倾向' }}</p>
            </article>
            <article class="creator-result-block">
              <span>下一期内容建议</span>
              <ul>
                <li v-for="(item, index) in nextContentSuggestions" :key="index">
                  {{ formatValue(item) }}
                </li>
              </ul>
            </article>
            <article class="creator-result-block span-full">
              <span>高频观点</span>
              <div class="creator-list">
                <section v-for="(item, index) in hotTopics" :key="index">
                  <strong>{{ getRecordText(item, 'topic') || formatValue(item) }}</strong>
                  <p v-if="getRecordText(item, 'evidence')">
                    依据：{{ getRecordText(item, 'evidence') }}
                  </p>
                  <p v-if="getRecordText(item, 'suggestion')">
                    建议：{{ getRecordText(item, 'suggestion') }}
                  </p>
                </section>
              </div>
            </article>
            <article class="creator-result-block">
              <span>争议点</span>
              <div class="creator-list">
                <section v-for="(item, index) in controversyPoints" :key="index">
                  <strong>{{ getRecordText(item, 'point') || formatValue(item) }}</strong>
                  <p v-if="getRecordText(item, 'risk')">风险：{{ getRecordText(item, 'risk') }}</p>
                  <p v-if="getRecordText(item, 'responseAdvice')">
                    回应：{{ getRecordText(item, 'responseAdvice') }}
                  </p>
                </section>
              </div>
            </article>
            <article class="creator-result-block">
              <span>误解点</span>
              <div class="creator-list">
                <section v-for="(item, index) in misunderstandingPoints" :key="index">
                  <strong>{{ getRecordText(item, 'point') || formatValue(item) }}</strong>
                  <p v-if="getRecordText(item, 'clarificationAdvice')">
                    澄清：{{ getRecordText(item, 'clarificationAdvice') }}
                  </p>
                </section>
              </div>
            </article>
            <article class="creator-result-block span-full">
              <span>互动建议</span>
              <ul>
                <li v-for="(item, index) in interactionSuggestions" :key="index">
                  {{ formatValue(item) }}
                </li>
              </ul>
            </article>
          </div>

          <article v-else class="creator-empty-result">
            <strong>还没有反馈报告</strong>
            <span>先提交评论弹幕样例，然后点击“分析反馈”。</span>
          </article>
        </section>
      </section>
    </div>

    <div
      v-if="guidanceEditorTarget"
      class="creator-modal-backdrop"
      role="presentation"
      @pointerdown="handleGuidanceBackdropPointerDown"
      @click="handleGuidanceBackdropClick"
    >
      <section
        class="creator-prompt-modal"
        role="dialog"
        aria-modal="true"
        :aria-label="guidanceEditorTitle"
      >
        <header>
          <div>
            <p class="creator-kicker">业务指导</p>
            <h3>{{ guidanceEditorTitle }}</h3>
          </div>
          <button type="button" class="creator-ghost-button" @click="closeGuidanceEditor">
            关闭
          </button>
        </header>

        <label v-if="guidanceEditorTarget === 'prePublish'" class="creator-prompt-field">
          <span>可调整的风格与建议偏好</span>
          <textarea
            v-model="prePublishForm.customGuidance"
            maxlength="2000"
            placeholder="可补充固定风格；留空沿用后端基础规则"
          ></textarea>
        </label>
        <label v-else class="creator-prompt-field">
          <span>可调整的风格与分析偏好</span>
          <textarea
            v-model="feedbackAnalyzeForm.customGuidance"
            maxlength="2000"
            placeholder="可补充固定复盘口径；留空沿用后端基础规则"
          ></textarea>
        </label>

        <p class="creator-prompt-hint">
          可描述表达风格、建议侧重点和分析顺序；角色、数据边界及基础输出结构由系统统一维护。
        </p>

        <footer>
          <button type="button" class="creator-secondary-action" @click="resetCurrentGuidance">
            恢复默认指导
          </button>
          <button type="button" class="creator-primary-button" @click="closeGuidanceEditor">
            保存并关闭
          </button>
        </footer>
      </section>
    </div>
  </section>
</template>
