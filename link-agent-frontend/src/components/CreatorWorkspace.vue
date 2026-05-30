<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  deleteCreatorTask,
  analyzeCreatorFeedback,
  analyzePrePublishWorkflow,
  chatCreatorFeedback,
  confirmWorkflowPrePublishSuggestion,
  createCreatorTask,
  createWorkflowEventSource,
  getCreatorFeedback,
  getCreatorFeedbackDashboard,
  getCreatorFeedbackReport,
  getCreatorTask,
  getPrePublishSuggestion,
  fetchCreatorFeedbackByBv,
  importCreatorFeedbackFile,
  listCreatorPreferences,
  listCreatorTasks,
  listWorkflowMessages,
  saveCreatorFeedback,
  sendWorkflowMessage,
  startPrePublishWorkflow,
  updateCreatorTask,
} from '@/api/creator'
import type {
  CreatorFeedback,
  CreatorFeedbackChatResult,
  CreatorFeedbackDashboard,
  CreatorFeedbackFetchResult,
  CreatorFeedbackReport,
  CreatorPreference,
  CreatorPreferenceMode,
  CreatorSuggestion,
  CreatorTask,
  CreatorTaskSummary,
  CreatorTaskUpdatePayload,
  CreatorWorkflowEvent,
  CreatorWorkflowMessage,
  CreatorWorkflowSession,
  CreatorWorkflowStatus,
} from '@/types/creator'

type UnknownRecord = Record<string, unknown>
type GuidanceEditorTarget = 'prePublish' | 'feedback'
type ResultModalTarget = 'prePublishSuggestion' | 'feedbackDashboard' | 'feedbackReport'
type TaskManageMode = 'create' | 'edit'
type CreatorWorkspaceState = {
  taskId?: string | null
}
type PreferenceChip = {
  text: string
  sourceTaskId: string
}

const guidanceStorageKey = 'link-agent-creator-guidance'
const legacyPromptStorageKey = 'link-agent-creator-system-prompts'
const workspaceStorageKey = 'link-agent-creator-workspace'
const defaultPrePublishGuidance =
  '标题表达克制、具体，优先说明视频能解决的问题；先总结核心卖点，再给出优化建议；避免夸张措辞。'
const defaultFeedbackGuidance =
  '先归纳观众最关注的问题，再分析争议和误解；建议应能直接转化为下一期选题或互动动作。'
const preferenceModeOptions: Array<{
  value: CreatorPreferenceMode
  label: string
  description: string
}> = [
  {
    value: 'USE_HISTORY',
    label: '沿用历史偏好',
    description: '参考最近复盘',
  },
  {
    value: 'IGNORE_HISTORY',
    label: '本期换风格',
    description: '不带入历史',
  },
  {
    value: 'EXPERIMENT',
    label: '试验新方向',
    description: '新要求优先',
  },
]
const taskStatusOptions: Array<{
  value: 'ALL' | CreatorTaskSummary['status']
  label: string
}> = [
  { value: 'ALL', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'PRE_PUBLISH_ANALYZED', label: '发布前完成' },
  { value: 'FEEDBACK_ANALYZED', label: '反馈分析完成' },
  { value: 'COMPETITOR_ANALYZED', label: '竞品分析完成' },
  { value: 'ANALYZED', label: '复盘完成' },
]

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
  preferenceMode: 'USE_HISTORY' as CreatorPreferenceMode,
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

const feedbackChatForm = reactive({
  question: '',
})

const feedbackScriptForm = reactive({
  bvInput: '',
  maxComments: 50,
  maxRepliesPerComment: 20,
  maxDanmaku: 500,
  format: 'both' as 'json' | 'both',
})

const tasks = ref<CreatorTaskSummary[]>([])
const selectedTask = ref<CreatorTask | null>(null)
const taskManageMode = ref<TaskManageMode>('create')
const taskSearchQuery = ref('')
const taskStatusFilter = ref<'ALL' | CreatorTaskSummary['status']>('ALL')
const suggestion = ref<CreatorSuggestion | null>(null)
const feedback = ref<CreatorFeedback | null>(null)
const feedbackReport = ref<CreatorFeedbackReport | null>(null)
const feedbackChatResult = ref<CreatorFeedbackChatResult | null>(null)
const feedbackDashboard = ref<CreatorFeedbackDashboard | null>(null)
const feedbackFetchResult = ref<CreatorFeedbackFetchResult | null>(null)
const creatorPreferences = ref<CreatorPreference[]>([])
const feedbackImportFile = ref<File | null>(null)
const feedbackImportWarnings = ref<string[]>([])
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
const isUpdatingTask = ref(false)
const isDeletingTask = ref(false)
const isAnalyzingPrePublish = ref(false)
const isConfirmingPrePublish = ref(false)
const isLoadingWorkflow = ref(false)
const isSendingWorkflowMessage = ref(false)
const isSavingFeedback = ref(false)
const isImportingFeedback = ref(false)
const isFetchingFeedback = ref(false)
const isAnalyzingFeedback = ref(false)
const isAskingFeedbackChat = ref(false)
const isLoadingCreatorPreferences = ref(false)
const lastPrePublishPreferenceMode = ref<CreatorPreferenceMode>('USE_HISTORY')
const hasPrePublishPreferenceModeSnapshot = ref(false)
const guidanceEditorTarget = ref<GuidanceEditorTarget | null>(null)
const resultModalTarget = ref<ResultModalTarget | null>(null)
const pendingDeleteTask = ref<CreatorTaskSummary | null>(null)
const isGuidanceBackdropPointerDown = ref(false)
const isResultModalBackdropPointerDown = ref(false)
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
const filteredTasks = computed(() => {
  const keyword = taskSearchQuery.value.trim().toLowerCase()
  return tasks.value.filter((task) => {
    const matchStatus = taskStatusFilter.value === 'ALL' || task.status === taskStatusFilter.value
    if (!matchStatus) {
      return false
    }
    if (!keyword) {
      return true
    }
    const searchableText = [task.taskName, task.taskId, statusLabel(task.status)]
      .join(' ')
      .toLowerCase()
    return searchableText.includes(keyword)
  })
})
const taskSummaryStats = computed(() => {
  const stats = {
    total: tasks.value.length,
    draft: 0,
    inProgress: 0,
    done: 0,
  }
  for (const task of tasks.value) {
    if (task.status === 'DRAFT') {
      stats.draft += 1
      continue
    }
    if (task.status === 'ANALYZED') {
      stats.done += 1
      continue
    }
    stats.inProgress += 1
  }
  return stats
})
const taskSubmitLabel = computed(() => {
  if (taskManageMode.value === 'edit') {
    return isUpdatingTask.value ? '保存中...' : '保存修改'
  }
  return isCreatingTask.value ? '创建中...' : '创建任务'
})
const taskFormTitle = computed(() =>
  taskManageMode.value === 'edit' ? '编辑创作任务' : '创建创作任务',
)
const taskFormHint = computed(() =>
  taskManageMode.value === 'edit'
    ? '编辑当前任务后，旧材料会被覆盖，后续分析请重新生成。'
    : '先填一份能直接被后端接住的材料，再创建任务。',
)
const pendingDeleteTaskName = computed(() => pendingDeleteTask.value?.taskName ?? '')
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
const canRunFeedbackAnalyze = computed(() =>
  Boolean(
    // LLM 分析必须基于已经保存或导入到后端的样例，避免用户改了输入框却误以为未保存内容也参与分析。
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
const feedbackDashboardWarnings = computed(() => {
  const warnings = [...feedbackImportWarnings.value, ...(feedbackDashboard.value?.warnings ?? [])]
  return Array.from(new Set(warnings))
})
const feedbackScriptBv = computed(() => extractBvid(feedbackScriptForm.bvInput))
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
const historicalPreferenceChips = computed<PreferenceChip[]>(() =>
  creatorPreferences.value
    .flatMap((record) =>
      parseJsonArray(record.preferenceContent).map((item) => ({
        text: preferenceItemText(item),
        sourceTaskId: record.sourceTaskId,
      })),
    )
    .filter((item) => item.text.length > 0)
    .slice(0, 8),
)
const selectedPreferenceModeLabel = computed(
  () =>
    preferenceModeOptions.find((option) => option.value === prePublishForm.preferenceMode)?.label ??
    '沿用历史偏好',
)
const lastPreferenceModeLabel = computed(
  () =>
    preferenceModeOptions.find((option) => option.value === lastPrePublishPreferenceMode.value)
      ?.label ?? '沿用历史偏好',
)
const preferenceModeNote = computed(() => preferenceModeNoteByMode(prePublishForm.preferenceMode))
const lastPreferenceModeNote = computed(() =>
  preferenceModeNoteByMode(lastPrePublishPreferenceMode.value),
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
const resultModalTitle = computed(() => {
  if (resultModalTarget.value === 'prePublishSuggestion') {
    return '发布前优化建议'
  }
  if (resultModalTarget.value === 'feedbackDashboard') {
    return '评论弹幕导入结果'
  }
  if (resultModalTarget.value === 'feedbackReport') {
    return '反馈分析报告'
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

function resetTaskForm() {
  taskForm.taskName = ''
  taskForm.titleDraft = ''
  taskForm.descriptionDraft = ''
  taskForm.manuscript = ''
  taskForm.subtitle = ''
}

function fillTaskForm(task: CreatorTask) {
  taskForm.taskName = task.taskName
  taskForm.titleDraft = getMaterialContent(task, 'TITLE_DRAFT')
  taskForm.descriptionDraft = getMaterialContent(task, 'DESCRIPTION_DRAFT')
  taskForm.manuscript = getMaterialContent(task, 'MANUSCRIPT')
  taskForm.subtitle = getMaterialContent(task, 'SUBTITLE')
}

function getMaterialContent(task: CreatorTask, materialType: string) {
  return task.materials.find((item) => item.materialType === materialType)?.content ?? ''
}

function hasTaskMaterialChanged(task: CreatorTask) {
  return (
    getMaterialContent(task, 'TITLE_DRAFT') !== taskForm.titleDraft.trim() ||
    getMaterialContent(task, 'DESCRIPTION_DRAFT') !== taskForm.descriptionDraft.trim() ||
    getMaterialContent(task, 'MANUSCRIPT') !== taskForm.manuscript.trim() ||
    getMaterialContent(task, 'SUBTITLE') !== taskForm.subtitle.trim()
  )
}

function resetGeneratedTaskResults() {
  closeWorkflowEventSource()
  suggestion.value = null
  feedback.value = null
  feedbackReport.value = null
  feedbackChatResult.value = null
  feedbackDashboard.value = null
  feedbackFetchResult.value = null
  feedbackImportFile.value = null
  feedbackImportWarnings.value = []
  workflowSession.value = null
  workflowMessages.value = []
  workflowMessageDraft.value = ''
  selectedWorkflowMessageId.value = ''
  resultModalTarget.value = null
  hasPrePublishPreferenceModeSnapshot.value = false
}

async function submitTask() {
  if (taskManageMode.value === 'edit') {
    await updateTask()
    return
  }
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
    taskManageMode.value = 'create'
    activeStep.value = 'prePublish'
    resetPrePublishPreferenceMode()
    resetGeneratedTaskResults()
    await loadCreatorPreferences(task.userId)
    persistWorkspaceState({ taskId: task.taskId })
    await loadPrePublishWorkflow(task.taskId)
    resetTaskForm()
    await refreshTasks()
    successMessage.value = '创作任务已创建，可以继续做发布前优化。'
  } catch (error) {
    showError(error)
  } finally {
    isCreatingTask.value = false
  }
}

async function updateTask() {
  if (!selectedTask.value) {
    return
  }
  isUpdatingTask.value = true
  errorMessage.value = ''
  successMessage.value = ''
  const materialChanged = hasTaskMaterialChanged(selectedTask.value)
  const payload: CreatorTaskUpdatePayload = {
    taskName: taskForm.taskName,
    titleDraft: taskForm.titleDraft,
    descriptionDraft: taskForm.descriptionDraft,
    manuscript: taskForm.manuscript,
    subtitle: taskForm.subtitle,
  }
  try {
    const task = await updateCreatorTask(selectedTask.value.taskId, payload)
    selectedTask.value = task
    taskManageMode.value = 'edit'
    persistWorkspaceState({ taskId: task.taskId })
    await loadCreatorPreferences(task.userId)
    if (materialChanged) {
      resetGeneratedTaskResults()
      await loadPrePublishWorkflow(task.taskId, false)
    } else {
      await loadPrePublishWorkflow(task.taskId)
    }
    await refreshTasks()
    successMessage.value = materialChanged
      ? '任务内容已更新，旧建议已清空，请重新生成。'
      : '任务名称已更新。'
  } catch (error) {
    showError(error)
  } finally {
    isUpdatingTask.value = false
  }
}

function startCreateTask() {
  taskManageMode.value = 'create'
  resetTaskForm()
  pendingDeleteTask.value = null
  errorMessage.value = ''
  successMessage.value = ''
  activeStep.value = 'task'
}

async function startEditTask(taskId: string) {
  errorMessage.value = ''
  successMessage.value = ''
  const task = selectedTask.value?.taskId === taskId ? selectedTask.value : null
  if (task) {
    taskManageMode.value = 'edit'
    fillTaskForm(task)
    activeStep.value = 'task'
    pendingDeleteTask.value = null
    return
  }

  await selectTask(taskId)
  if (selectedTask.value?.taskId !== taskId) {
    return
  }
  taskManageMode.value = 'edit'
  fillTaskForm(selectedTask.value)
  activeStep.value = 'task'
  pendingDeleteTask.value = null
}

function cancelEditTask() {
  taskManageMode.value = 'create'
  resetTaskForm()
}

function askDeleteTask(task: CreatorTaskSummary) {
  pendingDeleteTask.value = task
  errorMessage.value = ''
  successMessage.value = ''
}

function askDeleteSelectedTask() {
  if (!selectedTask.value) {
    return
  }
  askDeleteTask({
    id: selectedTask.value.id,
    taskId: selectedTask.value.taskId,
    userId: selectedTask.value.userId,
    taskName: selectedTask.value.taskName,
    status: selectedTask.value.status,
    materialCount: selectedTask.value.materials.length,
    createTime: selectedTask.value.createTime,
    updateTime: selectedTask.value.updateTime,
  })
}

function cancelDeleteTask() {
  pendingDeleteTask.value = null
}

async function confirmDeleteTask() {
  if (!pendingDeleteTask.value) {
    return
  }
  isDeletingTask.value = true
  errorMessage.value = ''
  successMessage.value = ''
  const targetTaskId = pendingDeleteTask.value.taskId
  try {
    await deleteCreatorTask(targetTaskId)
    pendingDeleteTask.value = null
    if (selectedTask.value?.taskId === targetTaskId) {
      resetSelectedWorkspace()
      restoredTaskId.value = ''
      persistWorkspaceState({ taskId: null })
    }
    await refreshTasks()
    successMessage.value = '任务已删除，列表会自动刷新。'
  } catch (error) {
    showError(error)
  } finally {
    isDeletingTask.value = false
  }
}

async function selectTask(taskId: string) {
  errorMessage.value = ''
  successMessage.value = ''
  resultModalTarget.value = null
  pendingDeleteTask.value = null
  taskManageMode.value = 'create'
  resetTaskForm()
  try {
    const task = await getCreatorTask(taskId)
    selectedTask.value = task
    activeStep.value = 'prePublish'
    resetPrePublishPreferenceMode()
    persistWorkspaceState({ taskId: task.taskId })
    await loadCreatorPreferences(task.userId)
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
  feedbackChatResult.value = null
  feedbackDashboard.value = null
  feedbackFetchResult.value = null
  feedbackImportFile.value = null
  feedbackImportWarnings.value = []

  if (hasPrePublishResult(task.status)) {
    suggestion.value = await optionalRequest(() => getPrePublishSuggestion(task.taskId))
    feedback.value = await optionalRequest(() => getCreatorFeedback(task.taskId))
    feedbackDashboard.value = await optionalRequest(() => getCreatorFeedbackDashboard(task.taskId))
  }

  if (hasFeedbackResult(task.status)) {
    feedbackReport.value = await optionalRequest(() => getCreatorFeedbackReport(task.taskId))
  }
}

async function loadCreatorPreferences(userId?: string) {
  isLoadingCreatorPreferences.value = true
  try {
    creatorPreferences.value = await listCreatorPreferences(userId || 'default', 10)
  } catch {
    // 偏好记忆是发布前优化的增强上下文，查询失败时不阻断任务主流程。
    creatorPreferences.value = []
  } finally {
    isLoadingCreatorPreferences.value = false
  }
}

async function loadPrePublishWorkflow(taskId: string, resumeLatest = true) {
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
      resumeLatest,
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
        preferenceMode: prePublishForm.preferenceMode,
      },
    )
    lastPrePublishPreferenceMode.value = prePublishForm.preferenceMode
    hasPrePublishPreferenceModeSnapshot.value = true
    workflowSession.value = {
      ...workflowSession.value,
      status: 'WAITING_CONFIRMATION' as CreatorWorkflowStatus,
    }
    await refreshPrePublishWorkflowMessages()
    successMessage.value = '发布前优化建议已生成，请确认采用后再进入评论弹幕分析。'
    openResultModal('prePublishSuggestion')
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
    feedbackReport.value = null
    feedbackChatResult.value = null
    feedbackDashboard.value = null
    feedbackFetchResult.value = null
    feedbackImportWarnings.value = []
    resultModalTarget.value = null
    activeStep.value = 'feedback'
    successMessage.value = '评论弹幕样例已保存，可以开始分析。'
  } catch (error) {
    showError(error)
  } finally {
    isSavingFeedback.value = false
  }
}

function handleFeedbackFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  feedbackImportFile.value = input.files?.[0] ?? null
  feedbackImportWarnings.value = []
}

async function importFeedbackFile() {
  if (!selectedTaskId.value || !canEnterFeedback.value || !feedbackImportFile.value) {
    return
  }
  isImportingFeedback.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const result = await importCreatorFeedbackFile(selectedTaskId.value, feedbackImportFile.value)
    feedbackFetchResult.value = null
    feedbackReport.value = null
    feedbackChatResult.value = null
    feedbackImportWarnings.value = result.warnings ?? []
    // 导入会回填旧样例表并生成明细表，前端立即重读后端状态，避免本地文件内容成为隐藏的数据源。
    feedback.value = await optionalRequest(() => getCreatorFeedback(selectedTaskId.value))
    feedbackDashboard.value = await optionalRequest(() => getCreatorFeedbackDashboard(selectedTaskId.value))
    successMessage.value = `已导入 ${result.commentCount} 条评论、${result.danmakuCount} 条弹幕，仪表盘已更新。`
    openResultModal('feedbackDashboard')
  } catch (error) {
    showError(error)
  } finally {
    isImportingFeedback.value = false
  }
}

async function fetchFeedbackByBv() {
  if (!selectedTaskId.value || !canEnterFeedback.value) {
    return
  }
  if (!feedbackScriptBv.value) {
    errorMessage.value = '请先输入有效 BV 号或视频链接。'
    return
  }
  isFetchingFeedback.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const result = await fetchCreatorFeedbackByBv(selectedTaskId.value, {
      bvInput: feedbackScriptForm.bvInput,
      maxComments: clampScriptNumber(feedbackScriptForm.maxComments, 0, 500),
      maxRepliesPerComment: clampScriptNumber(feedbackScriptForm.maxRepliesPerComment, 0, 100),
      maxDanmaku: clampScriptNumber(feedbackScriptForm.maxDanmaku, 0, 2000),
      format: feedbackScriptForm.format,
    })
    feedbackFetchResult.value = result
    feedbackReport.value = null
    feedbackChatResult.value = null
    feedbackImportWarnings.value = result.warnings ?? []
    // 后端已经完成脚本执行和入库，前端只刷新权威状态，避免页面表单成为第二份数据源。
    feedback.value = await optionalRequest(() => getCreatorFeedback(selectedTaskId.value))
    feedbackDashboard.value = await optionalRequest(() => getCreatorFeedbackDashboard(selectedTaskId.value))
    successMessage.value = `已拉取并导入 ${result.commentCount} 条评论、${result.danmakuCount} 条弹幕，文件已保存到 ${result.outputDirectory}。`
    openResultModal('feedbackDashboard')
  } catch (error) {
    showError(error)
  } finally {
    isFetchingFeedback.value = false
  }
}

async function runFeedbackAnalyze() {
  if (!selectedTaskId.value || !canRunFeedbackAnalyze.value) {
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
    feedbackChatResult.value = null
    selectedTask.value = await getCreatorTask(selectedTaskId.value)
    activeStep.value = 'report'
    successMessage.value = '评论弹幕分析完成，反馈报告已保存。'
    openResultModal('feedbackReport')
    await refreshTasks()
  } catch (error) {
    showError(error)
  } finally {
    isAnalyzingFeedback.value = false
  }
}

async function askFeedbackChat() {
  if (!selectedTaskId.value || !canAskFeedbackChat.value) {
    return
  }
  isAskingFeedbackChat.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    feedbackChatResult.value = await chatCreatorFeedback(selectedTaskId.value, {
      question: feedbackChatForm.question,
    })
    successMessage.value = '反馈追问已生成，回答基于当前任务报告和评论弹幕证据。'
  } catch (error) {
    showError(error)
  } finally {
    isAskingFeedbackChat.value = false
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
  taskManageMode.value = 'create'
  resetTaskForm()
  suggestion.value = null
  feedback.value = null
  feedbackReport.value = null
  feedbackChatResult.value = null
  feedbackDashboard.value = null
  feedbackFetchResult.value = null
  creatorPreferences.value = []
  feedbackImportFile.value = null
  feedbackImportWarnings.value = []
  isFetchingFeedback.value = false
  resetPrePublishPreferenceMode()
  resultModalTarget.value = null
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

function preferenceItemText(value: unknown) {
  if (typeof value === 'string') {
    return value.trim()
  }
  if (isRecord(value)) {
    const keys = ['preference', 'preferenceValue', 'content', 'insight', 'label', 'value', 'suggestion']
    for (const key of keys) {
      const text = value[key]
      if (typeof text === 'string' && text.trim()) {
        return text.trim()
      }
    }
  }
  return formatValue(value)
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

function extractBvid(value: string) {
  const matched = value.match(/BV[0-9A-Za-z]{10}/)
  return matched?.[0] ?? ''
}

function clampScriptNumber(value: number, min: number, max: number) {
  if (!Number.isFinite(value)) {
    return min
  }
  return Math.min(max, Math.max(min, Math.trunc(value)))
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

function openResultModal(target: ResultModalTarget) {
  if (target === 'prePublishSuggestion' && !suggestion.value) {
    return
  }
  if (target === 'feedbackDashboard' && !feedbackDashboard.value && !feedbackFetchResult.value) {
    return
  }
  if (target === 'feedbackReport' && !feedbackReport.value) {
    return
  }
  resultModalTarget.value = target
  if (guidanceEditorTarget.value) {
    closeGuidanceEditor()
  }
}

function closeResultModal() {
  resultModalTarget.value = null
  isResultModalBackdropPointerDown.value = false
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

function handleResultModalBackdropPointerDown(event: PointerEvent) {
  isResultModalBackdropPointerDown.value = event.target === event.currentTarget
}

function handleResultModalBackdropClick(event: MouseEvent) {
  if (isResultModalBackdropPointerDown.value && event.target === event.currentTarget) {
    closeResultModal()
    return
  }
  isResultModalBackdropPointerDown.value = false
}

function resetCurrentGuidance() {
  if (guidanceEditorTarget.value === 'prePublish') {
    prePublishForm.customGuidance = defaultPrePublishGuidance
  }
  if (guidanceEditorTarget.value === 'feedback') {
    feedbackAnalyzeForm.customGuidance = defaultFeedbackGuidance
  }
}

function resetPrePublishPreferenceMode() {
  prePublishForm.preferenceMode = 'USE_HISTORY'
  lastPrePublishPreferenceMode.value = 'USE_HISTORY'
  hasPrePublishPreferenceModeSnapshot.value = false
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
    COMPETITOR_ANALYZED: '已竞品分析',
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

function formatMetric(value: number | null | undefined) {
  if (value === null || value === undefined) {
    return '-'
  }
  return value.toLocaleString('zh-CN')
}

function statBarWidth(count: number, total: number) {
  if (total <= 0 || count <= 0) {
    return '0%'
  }
  return `${Math.max(8, Math.round((count / total) * 100))}%`
}

function preferenceModeNoteByMode(mode: CreatorPreferenceMode) {
  if (mode === 'IGNORE_HISTORY') {
    return '历史偏好不参与本次生成'
  }
  if (mode === 'EXPERIMENT') {
    return '本期覆盖要求优先'
  }
  if (historicalPreferenceChips.value.length === 0) {
    return '暂无可用历史偏好'
  }
  return `参考 ${historicalPreferenceChips.value.length} 条偏好`
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
            <div>
              <span>任务管理</span>
              <b>{{ filteredTasks.length }} / {{ taskSummaryStats.total }}</b>
            </div>
            <div class="creator-panel-actions">
              <button type="button" class="creator-ghost-button" @click="startCreateTask">
                新建
              </button>
              <button type="button" class="creator-ghost-button" @click="refreshTasks">
                {{ isLoadingTasks ? '读取中' : '刷新' }}
              </button>
            </div>
          </div>

          <div class="creator-task-toolbar">
            <label class="creator-task-search">
              <span>搜索</span>
              <input v-model="taskSearchQuery" type="search" placeholder="名称 / ID / 状态" />
            </label>
            <label class="creator-task-filter">
              <span>状态</span>
              <select v-model="taskStatusFilter">
                <option
                  v-for="option in taskStatusOptions"
                  :key="option.value"
                  :value="option.value"
                >
                  {{ option.label }}
                </option>
              </select>
            </label>
          </div>

          <div class="creator-task-overview" aria-label="任务概览">
            <span><b>{{ taskSummaryStats.draft }}</b> 草稿</span>
            <span><b>{{ taskSummaryStats.inProgress }}</b> 推进中</span>
            <span><b>{{ taskSummaryStats.done }}</b> 已复盘</span>
          </div>

          <div v-if="pendingDeleteTask" class="creator-delete-confirm">
            <strong>删除「{{ pendingDeleteTaskName }}」？</strong>
            <span>任务会从列表隐藏，历史分析产物保留在后端。</span>
            <div class="creator-delete-actions">
              <button
                type="button"
                class="creator-danger-action creator-mini-button"
                :disabled="isDeletingTask"
                @click="confirmDeleteTask"
              >
                {{ isDeletingTask ? '删除中' : '确认删除' }}
              </button>
              <button
                type="button"
                class="creator-ghost-button creator-mini-button"
                :disabled="isDeletingTask"
                @click="cancelDeleteTask"
              >
                取消
              </button>
            </div>
          </div>

          <div class="creator-task-list">
            <article
              v-for="task in filteredTasks"
              :key="task.taskId"
              class="creator-task-item"
              :class="{ active: task.taskId === selectedTaskId }"
            >
              <button type="button" class="creator-task-select" @click="selectTask(task.taskId)">
                <strong>{{ task.taskName }}</strong>
                <span>{{ statusLabel(task.status) }} · {{ task.materialCount }} 份材料</span>
                <small>{{ shortId(task.taskId) }} · {{ formatDate(task.updateTime) }}</small>
              </button>
              <div class="creator-task-actions">
                <button
                  type="button"
                  class="creator-ghost-button creator-mini-button"
                  @click="selectTask(task.taskId)"
                >
                  查看
                </button>
                <button
                  type="button"
                  class="creator-secondary-action creator-mini-button"
                  @click="startEditTask(task.taskId)"
                >
                  编辑
                </button>
                <button
                  type="button"
                  class="creator-danger-action creator-mini-button"
                  @click="askDeleteTask(task)"
                >
                  删除
                </button>
              </div>
            </article>
            <p v-if="!isLoadingTasks && tasks.length === 0" class="creator-muted">
              还没有创作任务，先新建一个。
            </p>
            <p v-else-if="!isLoadingTasks && filteredTasks.length === 0" class="creator-muted">
              没有匹配当前筛选条件的任务。
            </p>
          </div>
        </div>

        <div v-if="selectedTask" class="creator-panel compact-panel">
          <div class="creator-panel-title">
            <div>
              <span>当前任务</span>
              <b>{{ selectedTask.taskName }}</b>
            </div>
            <div class="creator-panel-actions">
              <button
                type="button"
                class="creator-secondary-action creator-mini-button"
                @click="startEditTask(selectedTask.taskId)"
              >
                编辑
              </button>
              <button
                type="button"
                class="creator-danger-action creator-mini-button"
                @click="askDeleteSelectedTask"
              >
                删除
              </button>
            </div>
          </div>
          <div class="creator-current-task-meta">
            <span>{{ statusLabel(selectedTask.status) }}</span>
            <span>{{ selectedTask.materials.length }} 份材料</span>
            <span>{{ formatDate(selectedTask.updateTime) }}</span>
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
              <h3>{{ taskFormTitle }}</h3>
            </div>
            <div class="creator-action-row">
              <button
                v-if="taskManageMode === 'edit'"
                type="button"
                class="creator-secondary-action"
                :disabled="isUpdatingTask"
                @click="cancelEditTask"
              >
                取消编辑
              </button>
              <button
                type="button"
                class="creator-primary-button"
                :disabled="!hasTaskMaterialInput || isCreatingTask || isUpdatingTask"
                @click="submitTask"
              >
                {{ taskSubmitLabel }}
              </button>
            </div>
          </div>

          <p class="creator-inline-note">{{ taskFormHint }}</p>

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
                v-if="suggestion"
                type="button"
                class="creator-secondary-action"
                @click="openResultModal('prePublishSuggestion')"
              >
                {{ hasConfirmedPrePublish ? '查看建议' : '查看并确认' }}
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

          <article class="creator-preference-panel">
            <div class="creator-preference-head">
              <div>
                <span>偏好记忆</span>
                <strong>{{ selectedPreferenceModeLabel }}</strong>
              </div>
              <b>{{ isLoadingCreatorPreferences ? '读取中' : preferenceModeNote }}</b>
            </div>

            <div class="creator-preference-modes" role="group" aria-label="偏好使用方式">
              <button
                v-for="option in preferenceModeOptions"
                :key="option.value"
                type="button"
                :class="{ active: prePublishForm.preferenceMode === option.value }"
                @click="prePublishForm.preferenceMode = option.value"
              >
                <span>{{ option.label }}</span>
                <small>{{ option.description }}</small>
              </button>
            </div>

            <div class="creator-preference-tags">
              <span
                v-for="chip in historicalPreferenceChips"
                :key="`${chip.sourceTaskId}-${chip.text}`"
                :class="{ muted: prePublishForm.preferenceMode === 'IGNORE_HISTORY' }"
                :title="`来源任务：${chip.sourceTaskId}`"
              >
                {{ chip.text }}
              </span>
              <em v-if="!isLoadingCreatorPreferences && historicalPreferenceChips.length === 0">
                暂无历史偏好
              </em>
            </div>
          </article>

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

          <article v-if="suggestion" class="creator-result-entry">
            <div>
              <strong>发布前 AI 分析结果已生成</strong>
              <span>
                {{
                  hasConfirmedPrePublish
                    ? '本轮建议已确认，可以继续评论弹幕阶段。'
                    : '进入独立弹窗查看标题、简介、标签建议，并决定是否采用。'
                }}
              </span>
            </div>
            <button
              type="button"
              class="creator-primary-button"
              @click="openResultModal('prePublishSuggestion')"
            >
              {{ hasConfirmedPrePublish ? '查看建议' : '查看并确认建议' }}
            </button>
          </article>

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
                v-if="feedbackDashboard || feedbackFetchResult"
                type="button"
                class="creator-secondary-action"
                @click="openResultModal('feedbackDashboard')"
              >
                查看导入结果
              </button>
              <button
                v-if="feedbackReport"
                type="button"
                class="creator-secondary-action"
                @click="openResultModal('feedbackReport')"
              >
                查看分析结果
              </button>
              <button
                type="button"
                class="creator-secondary-action"
                :disabled="!canEnterFeedback || !feedbackImportFile || isImportingFeedback || isFetchingFeedback"
                @click="importFeedbackFile"
              >
                {{ isImportingFeedback ? '导入中...' : '导入文件' }}
              </button>
              <button
                type="button"
                class="creator-secondary-action"
                :disabled="!canEnterFeedback || !hasFeedbackSampleInput || isSavingFeedback || isFetchingFeedback"
                @click="submitFeedback"
              >
                {{ isSavingFeedback ? '保存中...' : '保存样例' }}
              </button>
              <button
                type="button"
                class="creator-primary-button"
                :disabled="!canRunFeedbackAnalyze"
                @click="runFeedbackAnalyze"
              >
                {{ isAnalyzingFeedback ? '分析中...' : '分析反馈' }}
              </button>
            </div>
          </div>

          <div class="creator-form-grid">
            <article class="span-full creator-script-panel">
              <div class="creator-script-panel-head">
                <div>
                  <span>BV 拉取并导入</span>
                  <p>填好 BV 和数量上限后，后端执行项目内脚本，文件保存到项目根目录 export/bilibili_feedback，并自动导入仪表盘。</p>
                </div>
                <button
                  type="button"
                  class="creator-primary-button"
                  :disabled="!canEnterFeedback || !feedbackScriptBv || isFetchingFeedback"
                  @click="fetchFeedbackByBv"
                >
                  {{ isFetchingFeedback ? '拉取中...' : '拉取并导入' }}
                </button>
              </div>
              <div class="creator-script-grid">
                <label>
                  <span>BV 号或链接</span>
                  <input
                    v-model="feedbackScriptForm.bvInput"
                    type="text"
                    maxlength="200"
                    placeholder="BVxxxx 或 https://www.bilibili.com/video/BVxxxx"
                  />
                </label>
                <label>
                  <span>主楼评论数</span>
                  <input
                    v-model.number="feedbackScriptForm.maxComments"
                    type="number"
                    min="0"
                    max="500"
                  />
                </label>
                <label>
                  <span>每条回复数</span>
                  <input
                    v-model.number="feedbackScriptForm.maxRepliesPerComment"
                    type="number"
                    min="0"
                    max="100"
                  />
                </label>
                <label>
                  <span>弹幕数</span>
                  <input
                    v-model.number="feedbackScriptForm.maxDanmaku"
                    type="number"
                    min="0"
                    max="2000"
                  />
                </label>
                <label>
                  <span>输出格式</span>
                  <select v-model="feedbackScriptForm.format">
                    <option value="both">JSON + TXT</option>
                    <option value="json">只输出 JSON</option>
                  </select>
                </label>
              </div>
              <small>自动导入依赖 JSON 文件，所以页面只开放 JSON 或 JSON+TXT 两种输出格式。</small>
            </article>

            <label class="span-full creator-file-field">
              <span>导入脚本文件</span>
              <!-- 切换任务时重建文件输入框，避免浏览器保留上一个任务选择过的本地文件。 -->
              <input
                :key="selectedTaskId"
                type="file"
                accept=".json,.txt,application/json,text/plain"
                :disabled="!canEnterFeedback || isImportingFeedback || isFetchingFeedback"
                @change="handleFeedbackFileChange"
              />
              <small>
                仍保留文件导入入口，便于导入历史 JSON/TXT 或手工整理后的样例文件。
              </small>
            </label>
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

          <p v-if="feedback" class="creator-inline-note">
            样例已于 {{ formatDate(feedback.updateTime) }} 保存，导入结果和分析报告请通过上方按钮查看。
          </p>
        </section>

        <section v-if="activeStep === 'report'" class="creator-section">
          <div class="creator-section-head">
            <div>
              <p class="creator-kicker">Step 4</p>
              <h3>反馈分析结果</h3>
            </div>
            <div v-if="feedbackReport" class="creator-action-row">
              <span class="creator-parse-status">{{ feedbackReport.parseStatus }}</span>
              <button
                type="button"
                class="creator-primary-button"
                @click="openResultModal('feedbackReport')"
              >
                打开分析结果
              </button>
            </div>
          </div>

          <article v-if="feedbackReport" class="creator-result-entry">
            <div>
              <strong>反馈分析已生成</strong>
              <span>
                完整报告已收纳到独立结果弹窗，避免和评论弹幕输入区混在一起。
              </span>
            </div>
            <button
              type="button"
              class="creator-primary-button"
              @click="openResultModal('feedbackReport')"
            >
              查看完整报告
            </button>
          </article>

          <article v-else class="creator-empty-result">
            <strong>还没有反馈报告</strong>
            <span>先提交评论弹幕样例，然后点击“分析反馈”。</span>
          </article>
        </section>
      </section>
    </div>

    <div
      v-if="resultModalTarget"
      class="creator-modal-backdrop"
      role="presentation"
      @pointerdown="handleResultModalBackdropPointerDown"
      @click="handleResultModalBackdropClick"
    >
      <section
        class="creator-result-modal"
        role="dialog"
        aria-modal="true"
        :aria-label="resultModalTitle"
      >
        <header class="creator-result-modal-head">
          <div>
            <p class="creator-kicker">阶段结果</p>
            <h3>{{ resultModalTitle }}</h3>
          </div>
          <button type="button" class="creator-ghost-button" @click="closeResultModal">
            关闭
          </button>
        </header>

        <div class="creator-result-modal-body">
          <template v-if="resultModalTarget === 'prePublishSuggestion' && suggestion">
            <div class="creator-result-grid">
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
                <span>偏好使用方式</span>
                <strong>
                  {{ hasPrePublishPreferenceModeSnapshot ? lastPreferenceModeLabel : '未记录生成方式' }}
                </strong>
                <p>
                  {{
                    !hasPrePublishPreferenceModeSnapshot
                      ? '历史结果未保存偏好使用方式；重新生成后会记录。'
                      : lastPrePublishPreferenceMode === 'USE_HISTORY'
                        ? lastPreferenceModeNote
                        : lastPrePublishPreferenceMode === 'EXPERIMENT'
                          ? '历史偏好仅作避坑参考，本期覆盖要求优先。'
                          : '历史偏好未参与本次发布前优化。'
                  }}
                </p>
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
                    <p v-if="getRecordText(item, 'risk')">
                      风险：{{ getRecordText(item, 'risk') }}
                    </p>
                  </section>
                </div>
              </article>
              <article class="creator-result-block">
                <span>核心卖点</span>
                <ul>
                  <li v-for="(item, index) in sellingPoints" :key="index">
                    {{ formatValue(item) }}
                  </li>
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
                  <b v-for="(item, index) in tagSuggestions" :key="index">
                    {{ formatValue(item) }}
                  </b>
                </div>
              </article>
              <article class="creator-result-block">
                <span>简介建议</span>
                <p>{{ suggestion.descriptionSuggestion || '未解析到简介建议' }}</p>
              </article>
            </div>
          </template>

          <template v-else-if="resultModalTarget === 'feedbackDashboard'">
            <div class="creator-result-grid">
              <article v-if="feedbackFetchResult" class="creator-result-block span-full">
                <span>脚本输出</span>
                <div class="creator-script-output">
                  <span>输出目录</span>
                  <code>{{ feedbackFetchResult.outputDirectory }}</code>
                  <span>生成文件</span>
                  <ul>
                    <li v-for="filePath in feedbackFetchResult.outputFiles" :key="filePath">
                      {{ filePath }}
                    </li>
                  </ul>
                </div>
              </article>

              <template v-if="feedbackDashboard">
                <article class="creator-result-block">
                  <span>导入概览</span>
                  <div class="creator-metric-grid">
                    <section>
                      <strong>{{ formatMetric(feedbackDashboard.commentCount) }}</strong>
                      <small>评论</small>
                    </section>
                    <section>
                      <strong>{{ formatMetric(feedbackDashboard.danmakuCount) }}</strong>
                      <small>弹幕</small>
                    </section>
                    <section>
                      <strong>{{ formatMetric(feedbackDashboard.noiseCount) }}</strong>
                      <small>无意义/重复</small>
                    </section>
                  </div>
                </article>

                <article class="creator-result-block">
                  <span>视频指标</span>
                  <div v-if="feedbackDashboard.metric" class="creator-metric-grid">
                    <section>
                      <strong>{{ formatMetric(feedbackDashboard.metric.viewCount) }}</strong>
                      <small>播放</small>
                    </section>
                    <section>
                      <strong>{{ formatMetric(feedbackDashboard.metric.likeCount) }}</strong>
                      <small>点赞</small>
                    </section>
                    <section>
                      <strong>{{ formatMetric(feedbackDashboard.metric.coinCount) }}</strong>
                      <small>投币</small>
                    </section>
                    <section>
                      <strong>{{ formatMetric(feedbackDashboard.metric.favoriteCount) }}</strong>
                      <small>收藏</small>
                    </section>
                  </div>
                  <p v-else>当前文件没有视频指标，仪表盘只展示评论和弹幕明细。</p>
                </article>

                <article class="creator-result-block">
                  <span>评论分类</span>
                  <div class="creator-stat-bars">
                    <section
                      v-for="item in feedbackDashboard.commentCategoryStats"
                      :key="`comment-${item.name}`"
                    >
                      <div>
                        <strong>{{ item.label }}</strong>
                        <small>{{ item.count }} 条</small>
                      </div>
                      <i
                        :style="{
                          width: statBarWidth(item.count, feedbackDashboard.commentCount),
                        }"
                      ></i>
                    </section>
                  </div>
                </article>

                <article class="creator-result-block">
                  <span>弹幕分类</span>
                  <div class="creator-stat-bars">
                    <section
                      v-for="item in feedbackDashboard.danmakuCategoryStats"
                      :key="`danmaku-${item.name}`"
                    >
                      <div>
                        <strong>{{ item.label }}</strong>
                        <small>{{ item.count }} 条</small>
                      </div>
                      <i
                        :style="{
                          width: statBarWidth(item.count, feedbackDashboard.danmakuCount),
                        }"
                      ></i>
                    </section>
                  </div>
                </article>

                <article class="creator-result-block">
                  <span>情绪分布</span>
                  <div class="creator-stat-bars">
                    <section
                      v-for="item in feedbackDashboard.sentimentStats"
                      :key="`sentiment-${item.name}`"
                    >
                      <div>
                        <strong>{{ item.label }}</strong>
                        <small>{{ item.count }} 条</small>
                      </div>
                      <i
                        :style="{
                          width: statBarWidth(
                            item.count,
                            feedbackDashboard.commentCount + feedbackDashboard.danmakuCount,
                          ),
                        }"
                      ></i>
                    </section>
                  </div>
                </article>

                <article class="creator-result-block">
                  <span>高频关键词</span>
                  <div class="creator-chip-list">
                    <b v-for="item in feedbackDashboard.keywords" :key="item.keyword">
                      {{ item.keyword }} · {{ item.count }}
                    </b>
                    <p v-if="feedbackDashboard.keywords.length === 0">
                      暂未命中内置关键词，后续可接入分词或 LLM 分类。
                    </p>
                  </div>
                </article>

                <article class="creator-result-block span-full">
                  <span>弹幕时间段热度</span>
                  <div v-if="feedbackDashboard.danmakuTimeline.length" class="creator-stat-bars">
                    <section
                      v-for="item in feedbackDashboard.danmakuTimeline"
                      :key="item.timeBucket"
                    >
                      <div>
                        <strong>{{ item.timeBucket }}</strong>
                        <small>{{ item.count }} 条</small>
                      </div>
                      <i
                        :style="{
                          width: statBarWidth(item.count, feedbackDashboard.danmakuCount),
                        }"
                      ></i>
                    </section>
                  </div>
                  <p v-else>当前弹幕没有时间戳，暂不展示时间段热度。</p>
                </article>

                <article class="creator-result-block span-full">
                  <span>高反馈评论</span>
                  <div
                    v-if="feedbackDashboard.topCommentItems.length"
                    class="creator-feedback-item-list"
                  >
                    <section v-for="item in feedbackDashboard.topCommentItems" :key="item.itemId">
                      <small>
                        {{ item.categoryLabel }} · {{ item.sentimentLabel }}
                        <template v-if="item.likeCount !== null">
                          · 点赞 {{ formatMetric(item.likeCount) }}
                        </template>
                        <template v-if="item.replyCount !== null">
                          · 回复 {{ formatMetric(item.replyCount) }}
                        </template>
                        <template v-if="item.occurTimeText"> · {{ item.occurTimeText }}</template>
                      </small>
                      <p>{{ item.content }}</p>
                    </section>
                  </div>
                  <p v-else>当前导入没有可排序的评论点赞数据。</p>
                </article>

                <article class="creator-result-block span-full">
                  <span>最近导入明细</span>
                  <div class="creator-feedback-item-list">
                    <section v-for="item in feedbackDashboard.recentItems" :key="item.itemId">
                      <small>
                        {{ item.sourceLabel }} · {{ item.categoryLabel }} ·
                        {{ item.sentimentLabel }}
                        <template v-if="item.likeCount !== null">
                          · 点赞 {{ formatMetric(item.likeCount) }}
                        </template>
                        <template v-if="item.replyCount !== null">
                          · 回复 {{ formatMetric(item.replyCount) }}
                        </template>
                        <template v-if="item.occurTimeText"> · {{ item.occurTimeText }}</template>
                      </small>
                      <p>{{ item.content }}</p>
                    </section>
                  </div>
                </article>

                <article
                  v-if="feedbackDashboardWarnings.length"
                  class="creator-result-block span-full"
                >
                  <span>导入提示</span>
                  <ul>
                    <li v-for="warning in feedbackDashboardWarnings" :key="warning">
                      {{ warning }}
                    </li>
                  </ul>
                </article>
              </template>

              <article v-else class="creator-empty-result span-full">
                <strong>还没有可展示的导入仪表盘</strong>
                <span>请先导入 JSON/TXT 文件，或通过单个 BV 显式触发限量样例采集。</span>
              </article>
            </div>
          </template>

          <template v-else-if="resultModalTarget === 'feedbackReport' && feedbackReport">
            <div class="creator-result-grid">
              <article class="creator-result-block span-full">
                <span>整体反馈</span>
                <p>{{ feedbackReport.feedbackSummary || '未解析到整体反馈' }}</p>
              </article>
              <article class="creator-result-block span-full creator-feedback-chat">
                <span>反馈追问</span>
                <div class="creator-feedback-chat-form">
                  <textarea
                    v-model="feedbackChatForm.question"
                    maxlength="1000"
                    placeholder="例如：为什么认为观众误解了 Agent 工具调用？"
                    @keydown.ctrl.enter.prevent="askFeedbackChat"
                  ></textarea>
                  <button
                    type="button"
                    class="creator-primary-button"
                    :disabled="!canAskFeedbackChat"
                    @click="askFeedbackChat"
                  >
                    {{ isAskingFeedbackChat ? '生成中...' : '追问' }}
                  </button>
                </div>
                <div v-if="feedbackChatResult" class="creator-feedback-chat-answer">
                  <strong>回答</strong>
                  <p>{{ feedbackChatResult.answer }}</p>
                  <small>
                    当前任务证据 · {{ feedbackChatResult.reportUsed ? '含报告' : '仅明细' }} ·
                    {{ feedbackChatResult.ragEnabled ? '向量检索' : 'SQL 检索' }}
                  </small>
                  <div
                    v-if="feedbackChatResult.evidenceItems.length"
                    class="creator-feedback-item-list"
                  >
                    <section
                      v-for="(item, index) in feedbackChatResult.evidenceItems"
                      :key="item.itemId"
                    >
                      <small>
                        证据{{ index + 1 }} · {{ item.sourceLabel }} ·
                        {{ item.categoryLabel }} · {{ item.sentimentLabel }}
                        <template v-if="item.occurTimeText"> · {{ item.occurTimeText }}</template>
                      </small>
                      <p>{{ item.content }}</p>
                    </section>
                  </div>
                </div>
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
                    <p v-if="getRecordText(item, 'risk')">
                      风险：{{ getRecordText(item, 'risk') }}
                    </p>
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
          </template>
        </div>
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
