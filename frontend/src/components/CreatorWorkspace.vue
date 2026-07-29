<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { onBeforeRouteLeave } from 'vue-router'
import { ApiError } from '@/api/http'
import {
  getCreatorTask,
  disableCreatorContextTerm,
  recordCreatorContextTermFeedback,
  saveCreatorContextTerm,
} from '@/api/creator'
import {
  getCurrentDraftVideo,
  getCurrentMediaProcessingJob,
  getCurrentPreflightReview,
  getMediaFeatureStatus,
} from '@/api/media'
import NotificationToast from '@/components/NotificationToast.vue'
import CreatorDeleteConfirmModal from '@/components/creator/CreatorDeleteConfirmModal.vue'
import CreatorDevTestModal from '@/components/creator/CreatorDevTestModal.vue'
import CreatorResultModal from '@/components/creator/CreatorResultModal.vue'
import CreatorTaskManagerModal from '@/components/creator/CreatorTaskManagerModal.vue'
import CreatorWorkflowMessageModal from '@/components/creator/CreatorWorkflowMessageModal.vue'
import { createPrePublishLayoutPreviewFixture } from '@/dev/prePublishLayoutPreview'
import { useCreatorFeedbackEvent } from '@/composables/creator/useCreatorFeedbackEvent'
import { provideCreatorWorkspace } from '@/composables/creator/useCreatorWorkspaceContext'
import {
  contextTermPolarity,
  contextTermTypeLabel,
  formatDate,
  formatDuration,
  formatInputCount,
  formatUsageToken,
  hasFeedbackResult,
  hasPrePublishResult,
  hasText,
  normalizeContextTermText,
  parseJsonArray,
  preferenceItemText,
  preferenceModeNoteByMode,
  shortId,
  statusLabel,
  usageCategoryLabel,
  usageStatusClass,
  usageStatusLabel,
} from '@/composables/creator/creatorWorkspaceUtils'
import type {
  CreatorContextPolarity,
  CreatorContextTerm,
  CreatorContextTermPayload,
  CreatorContextTermType,
  CreatorPreferenceMode,
  CreatorReport,
  CreatorTask,
  CreatorTaskSummary,
  CreatorWorkflowStage,
  LlmApiModelCategory,
  ResultModalTarget,
} from '@/types/creator'
import type { DraftVideo, MediaProcessingJob, PreflightReviewStatus } from '@/types/media'
import { useCreatorStore } from '@/stores/creatorStore'
import { useCreatorEvaluation, type CreatorEvaluationResultDraft } from '@/composables/creator/useCreatorEvaluation'
import { useCreatorUsage } from '@/composables/creator/useCreatorUsage'
import { useCreatorContext } from '@/composables/creator/useCreatorContext'
import { useCreatorGuidance } from '@/composables/creator/useCreatorGuidance'
import { useCreatorTask } from '@/composables/creator/useCreatorTask'
import { useCreatorWorkflow } from '@/composables/creator/useCreatorWorkflow'
import { useCreatorFeedback } from '@/composables/creator/useCreatorFeedback'
type CreatorWorkStep = 'prePublish' | 'production' | 'preflight' | 'feedback' | 'report'
type CreatorStepKey = 'task' | CreatorWorkStep
type PreferenceChip = {
  text: string
  sourceTaskId: string
}
type ContextTermOption = {
  value: CreatorContextTermType
  label: string
  polarity: CreatorContextPolarity
}

const AiCreationConsole = defineAsyncComponent(() => import('@/components/creator/AiCreationConsole.vue'))
const CreatorContextLibraryModal = defineAsyncComponent(
  () => import('@/components/creator/CreatorContextLibraryModal.vue'),
)
const CreatorFlowNav = defineAsyncComponent(() => import('@/components/creator/CreatorFlowNav.vue'))
const FeedbackTab = defineAsyncComponent(() => import('@/components/creator/FeedbackTab.vue'))
const GuidanceEditorModal = defineAsyncComponent(() => import('@/components/creator/GuidanceEditorModal.vue'))
const MaterialsTab = defineAsyncComponent(() => import('@/components/creator/MaterialsTab.vue'))
const PreflightTab = defineAsyncComponent(() => import('@/components/creator/PreflightTab.vue'))
const PrePublishTab = defineAsyncComponent(() => import('@/components/creator/PrePublishTab.vue'))
const ProductionPlanTab = defineAsyncComponent(() => import('@/components/creator/ProductionPlanTab.vue'))
const ReportTab = defineAsyncComponent(() => import('@/components/creator/ReportTab.vue'))
const TaskListPanel = defineAsyncComponent(() => import('@/components/creator/TaskListPanel.vue'))
const UsageTab = defineAsyncComponent(() => import('@/components/creator/UsageTab.vue'))

const props = withDefaults(
  defineProps<{
    developerMode?: boolean
  }>(),
  {
    developerMode: false,
  },
)

// 仅开发环境允许通过显式查询参数进入无后端布局预览，生产构建不会响应这个入口。
const isLayoutPreviewMode =
  import.meta.env.DEV &&
  new URLSearchParams(window.location.search).get('layoutPreview') === 'prepublish'

// 指导词域迁移至 useCreatorGuidance（表单 + guidance 编辑 + localStorage 持久化）
const guidance = useCreatorGuidance()
const {
  prePublishForm, feedbackAnalyzeForm,
  guidanceEditorTarget, lastPrePublishPreferenceMode, hasPrePublishPreferenceModeSnapshot,
  hasTaskGuidanceInput,
  loadGuidanceSettings, openGuidanceEditor, closeGuidanceEditor,
  resetCurrentGuidance, resetTaskGuidanceFields,
  markPrePublishGuidanceSubmitted, markFeedbackGuidanceSubmitted,
} = guidance
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
// 短文稿在 P0-1 中可能只是“已确认创意方向”，不能当成完整成片文稿来阻断 AI 补稿。
const fullScriptMinLength = 800
// 后端保存 AI 补稿时会写入该前缀。它让短创意和已经可用于生成发布方案的草稿保持可区分。
const aiGeneratedManuscriptPrefix = '【AI 可编辑文稿草稿】'
const contextTermOptions: ContextTermOption[] = [
  { value: 'KEYWORD', label: '关键词', polarity: 'POSITIVE' },
  { value: 'SLANG', label: '圈内黑话', polarity: 'POSITIVE' },
  { value: 'MEME', label: '梗表达', polarity: 'POSITIVE' },
  { value: 'TITLE_PATTERN', label: '标题套路', polarity: 'POSITIVE' },
  { value: 'AUDIENCE_CONCERN', label: '观众关注点', polarity: 'POSITIVE' },
  { value: 'TABOO', label: '慎用表达', polarity: 'NEGATIVE' },
]
const usageCategoryOptions: Array<{
  value: 'ALL' | LlmApiModelCategory
  label: string
}> = [
  { value: 'ALL', label: '全部模型' },
  { value: 'TEXT', label: '文本 LLM' },
  { value: 'EMBEDDING', label: '向量化' },
  { value: 'RERANK', label: 'Rerank' },
]


// feedbackForm 统一来源：见 Adapter Layer → feedbackModule

// feedbackChatForm / feedbackScriptForm 统一来源：见 Adapter Layer → feedbackModule

const errorMessage = ref('')
const successMessage = ref('')
const taskModule = useCreatorTask(errorMessage)
const {
  tasks, selectedTask, taskManageMode, taskSearchQuery, taskStatusFilter,
  taskForm, pendingDeleteTask,
  isLoadingTasks, isCreatingTask, isUpdatingTask, isDeletingTask,
  selectedTaskId, hasSelectedTask, hasSelectedTaskMaterials, hasTaskMaterialInput,
  hasUnsavedTaskFormInput,
  filteredTasks, taskSummaryStats, taskSubmitLabel, taskFormTitle, taskFormHint,
  currentVideoType,
  taskStatusOptions, videoTypeOptions,
} = taskModule

// 任务列表是导航型信息，默认收进弹窗，避免持续挤占创作主流程空间。
const isTaskManagerOpen = ref(false)
const isTaskComposerOpen = ref(false)
// 任务编辑会临时进入资料页，记录编辑前阶段，避免取消或保存后把用户困在第一阶段。
const editReturnStep = ref<CreatorWorkStep>('prePublish')
// 评测域状态迁移至 useCreatorEvaluation
const evaluationModule = useCreatorEvaluation(errorMessage)
const {
  evalCases, selectedEvalResultId, evalStageFilter,
  evalResults, evalPromptVersionStats, evalResultDraft,
  isLoadingEvalCases, isLoadingEvalResults, isRecordingEvalResult,
  filteredEvalCases, selectedEvalCase, selectedEvalResult, canRecordEvalResult,
  loadEvaluationCases, refreshEvaluationResults,
  selectEvalCase, submitEvalResult,
} = evaluationModule
const isDeveloperTestOpen = ref(false)
const isGuidanceBackdropPointerDown = ref(false)
// 语境库域迁移至 useCreatorContext（saveContextTerm 作为编排函数留在组件）
const contextModule = useCreatorContext(errorMessage)
const {
  creatorPreferences, creatorContextTerms,
  isLoadingCreatorPreferences, isLoadingCreatorContextTerms,
  isSavingCreatorContextTerm, savingContextTermKey,
  loadCreatorPreferences, loadCreatorContextTerms,
} = contextModule
// disableContextTerm / feedbackContextTerm 保留在组件：有 errorMessage/successMessage 交互逻辑
const contextTermForm = reactive({
  term: '',
  termType: 'KEYWORD' as CreatorContextTermType,
  evidenceText: '',
})
// 开销统计域迁移至 useCreatorUsage
const usageModule = useCreatorUsage(() => selectedTaskId.value, errorMessage)
const {
  usageSummary, usageCallPage, usageCategoryFilter, usageCurrentPage,
  isLoadingUsageStats,
  usageCategorySummaries, usageTotalPages,
  refreshUsageStats, changeUsageCategoryFilter, changeUsagePage,
} = usageModule
const workflowModule = useCreatorWorkflow(
  selectedTaskId,
  hasSelectedTaskMaterials,
  errorMessage,
  () => { void refreshUsageStats(1, false) },
)
const {
  workflowSession, workflowMessages, workflowSteps, workflowMessageDraft,
  selectedWorkflowMessageId, suggestion, workflowMessageModalOpen,
  isAnalyzingPrePublish, isConfirmingPrePublish, isGeneratingPrePublishDraft,
  isLoadingWorkflow, isSendingWorkflowMessage,
  canSendWorkflowMessage, canRunPrePublishAnalyze, canConfirmPrePublish,
  selectedWorkflowMessage, workflowStatusText,
  workflowSseText: liveWorkflowSseText,
  disconnect: closeWorkflowEventSource,
} = workflowModule
const workflowMessageListRef = ref<HTMLDivElement | null>(null)
const workflowSseText = computed(() =>
  isLayoutPreviewMode ? '实时连接' : liveWorkflowSseText.value,
)
const hasConfirmedPrePublish = computed(() => {
  return workflowSession.value?.status === 'CONFIRMED'
    && Boolean(workflowSession.value.confirmedResultId)
})
// activeStep / restoredTaskId 从 Pinia creatorStore 读取，替代原来的 localStorage + persistWorkspaceState 模式
const creatorStore = useCreatorStore()
const { activeStep, restoredTaskId } = storeToRefs(creatorStore)
const isMediaFeatureEnabled = ref(false)
const isMediaFeatureAvailabilityResolved = ref(false)
const currentDraftVideo = ref<DraftVideo | null>(null)
const currentMediaProcessingStatus = ref<MediaProcessingJob['status'] | null>(null)
const currentPreflightReviewStatus = ref<PreflightReviewStatus | null>(null)
const productionPlanReady = ref(false)
let currentDraftRefreshGeneration = 0
const mediaFeatureUnavailableMessage =
  '发布前试映未启用，任务不能进入观众反馈阶段。请先完成媒体配置并开启该能力。'
const mediaPreflightRequiredMessage =
  '请先在成片试映完成媒体探测、预处理和发布前试映，再进入观众反馈阶段。'
type CreatorStepMeta = {
  key: CreatorStepKey
  label: string
  shortLabel: string
  description: string
}
const creatorStepMetas = computed<CreatorStepMeta[]>(() => {
  const steps: CreatorStepMeta[] = [
    { key: 'task', label: '视频资料', shortLabel: '资料', description: '填写视频基础信息' },
    { key: 'prePublish', label: '发布方案', shortLabel: '发布', description: '生成发布计划与方案' },
    { key: 'production', label: '制作蓝图', shortLabel: '制作', description: '拆解制作步骤与工具' },
  ]
  if (isMediaFeatureEnabled.value) {
    steps.push({ key: 'preflight', label: '成片试映', shortLabel: '试映', description: '上传成片并完成发布前体检' })
  }
  steps.push(
    { key: 'feedback', label: '观众反馈', shortLabel: '反馈', description: '收集观众意见与反馈' },
    { key: 'report', label: '总体复盘', shortLabel: '复盘', description: '汇总反馈与竞品结论' },
  )
  return steps
})
const activeCreatorStepIndex = computed(() => {
  const matchedIndex = creatorStepMetas.value.findIndex((step) => step.key === activeStep.value)
  // 开销统计属于开发者辅助入口，不参与普通创作者五步流程；移动端进度条保持在复盘阶段。
  if (activeStep.value === 'usage') {
    return creatorStepMetas.value.length - 1
  }
  return matchedIndex >= 0 ? matchedIndex : 0
})
// 使用 ?? 兜底第一个步骤元信息；creatorStepMetas 会随媒体能力开关变化，但始终至少包含基础四步。
const activeCreatorStepMeta = computed(
  () => creatorStepMetas.value[activeCreatorStepIndex.value] ?? creatorStepMetas.value[0]!,
)
const creatorProgressPercent = computed(() => `${((activeCreatorStepIndex.value + 1) / creatorStepMetas.value.length) * 100}%`)
const currentTaskProgressIndex = computed(() => {
  if (!selectedTask.value) {
    return 0
  }
  return creatorStepMetas.value.findIndex((step) => step.key === resolveTaskEntryStep(selectedTask.value!))
})
const isContextLibraryOpen = ref(false)
const resultModalTarget = ref<ResultModalTarget | null>(null)

// 建议卡片反馈事件上报：复用已有的 successMessage/errorMessage toast 通道，
// 点击"采纳/不太好"后由这里统一写 creator_event 表，触发画像增量更新
const feedbackEvent = useCreatorFeedbackEvent(successMessage, errorMessage)

const hasTaskHistory = computed(() => tasks.value.length > 0)
const showDeveloperTools = computed(() => props.developerMode)
const hasFullScriptMaterial = computed(() =>
  Boolean(
    selectedTask.value?.materials.some(
      (material) =>
        (material.materialType === 'MANUSCRIPT' || material.materialType === 'SUBTITLE') &&
        hasText(material.content) &&
        material.content.trim().length >= fullScriptMinLength,
    ),
  ),
)
const hasGeneratedPrePublishDraft = computed(() =>
  Boolean(
    selectedTask.value?.materials.some(
      (material) =>
        material.materialType === 'MANUSCRIPT' &&
        hasText(material.content) &&
        material.content.trim().startsWith(aiGeneratedManuscriptPrefix),
    ),
  ),
)
// 已保存的 AI 草稿虽可能不足 800 字，但已经是可分析材料，不能继续把用户困在补稿状态。
const hasPrePublishScriptMaterial = computed(
  () => hasFullScriptMaterial.value || hasGeneratedPrePublishDraft.value,
)
const canEnterPreflight = computed(() =>
  hasSelectedTask.value && hasConfirmedPrePublish.value && productionPlanReady.value && isMediaFeatureEnabled.value,
)
const hasReadyPreflightDraft = computed(() => currentDraftVideo.value?.status === 'READY_FOR_REVIEW')
const hasCompletedPreflightProcessing = computed(
  () => hasReadyPreflightDraft.value && currentMediaProcessingStatus.value === 'COMPLETED',
)
const hasCompletedPreflightReview = computed(
  () => hasCompletedPreflightProcessing.value && currentPreflightReviewStatus.value === 'COMPLETED',
)
const canEnterFeedback = computed(() =>
  Boolean(
    selectedTask.value
      && (hasFeedbackResult(selectedTask.value.status)
        || (hasConfirmedPrePublish.value
          && productionPlanReady.value
          && isMediaFeatureEnabled.value
          && hasCompletedPreflightReview.value)),
  ),
)
const feedbackModule = useCreatorFeedback(
  selectedTaskId,
  canEnterFeedback,
  errorMessage,
  successMessage,
)
const {
  feedback, feedbackDashboard, feedbackReport,
  feedbackChatResult, feedbackChatTurns, feedbackFetchResult,
  feedbackImportFile,
  feedbackEvidenceIndexStatus, feedbackEvidenceIndexWarnings,
  feedbackForm, feedbackChatForm, feedbackScriptForm,
  isFeedbackChatDrawerOpen,
  isSavingFeedback, isImportingFeedback, isFetchingFeedback,
  isAnalyzingFeedback, isAskingFeedbackChat,
  isRebuildingFeedbackEvidenceIndex, isLoadingFeedbackEvidenceIndexStatus,
  hasFeedbackSampleInput, canRunFeedbackAnalyze, canAskFeedbackChat,
  feedbackDashboardWarnings, feedbackScriptBv, hasUnsavedTaskFeedbackInput,
} = feedbackModule
const hasUnsavedCurrentTaskInput = computed(() =>
  hasUnsavedTaskFormInput.value ||
    hasUnsavedTaskFeedbackInput.value ||
    hasTaskGuidanceInput.value ||
    Boolean(
      workflowMessageDraft.value.trim() ||
        contextTermForm.term.trim() ||
        contextTermForm.evidenceText.trim(),
    ),
)
const canGeneratePrePublishDraft = computed(() => {
  const status = workflowSession.value?.status
  return Boolean(
    hasSelectedTask.value &&
      hasSelectedTaskMaterials.value &&
      workflowSession.value &&
      !hasPrePublishScriptMaterial.value &&
      !isSendingWorkflowMessage.value &&
      !isAnalyzingPrePublish.value &&
      !isConfirmingPrePublish.value &&
      !isGeneratingPrePublishDraft.value &&
      status !== 'RUNNING' &&
      status !== 'CONFIRMED' &&
      status !== 'CANCELLED',
  )
})
const isActiveStepReadOnly = computed(() => {
  if (isLayoutPreviewMode) {
    return true
  }
  if (taskManageMode.value === 'edit') {
    return false
  }
  const matchedIndex = creatorStepMetas.value.findIndex((step) => step.key === activeStep.value)
  return matchedIndex >= 0 && matchedIndex < currentTaskProgressIndex.value
})
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
const activeContextTerms = computed(() => creatorContextTerms.value.filter((term) => term.enabled))
const contextTermChips = computed(() =>
  activeContextTerms.value
    .slice(0, 10)
    .map((term) => ({
      id: term.termId,
      text: term.term,
      label: contextTermTypeLabel(term.termType),
      title: term.evidenceText || `${term.videoType} · ${contextTermTypeLabel(term.termType)}`,
    })),
)
const canSaveContextTerm = computed(
  () => hasText(contextTermForm.term) && hasText(currentVideoType.value) && !isSavingCreatorContextTerm.value,
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
const preferenceModeNote = computed(() =>
  preferenceModeNoteByMode(prePublishForm.preferenceMode, historicalPreferenceChips.value.length),
)
const lastPreferenceModeNote = computed(() =>
  preferenceModeNoteByMode(
    lastPrePublishPreferenceMode.value,
    historicalPreferenceChips.value.length,
  ),
)
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
const evalStats = computed(() => {
  const stats = {
    total: evalCases.value.length,
    prePublish: 0,
    feedback: 0,
    report: 0,
  }
  for (const item of evalCases.value) {
    if (item.targetStage === 'PRE_PUBLISH') {
      stats.prePublish += 1
    } else if (item.targetStage === 'FEEDBACK') {
      stats.feedback += 1
    } else if (item.targetStage === 'REPORT') {
      stats.report += 1
    }
  }
  return stats
})
const workflowRunningStep = computed(() => workflowSteps.value.find((step) => step.status === 'RUNNING') ?? null)
const guidanceEditorTitle = computed(() => {
  if (guidanceEditorTarget.value === 'prePublish') {
    return '发布方案偏好'
  }
  if (guidanceEditorTarget.value === 'feedback') {
    return '反馈分析偏好'
  }
  return ''
})
const resultModalTitle = computed(() => {
  if (resultModalTarget.value === 'feedbackDashboard') {
    return '观众反馈导入结果'
  }
  if (resultModalTarget.value === 'feedbackReport') {
    return '反馈分析结果'
  }
  return ''
})
onMounted(() => {
  loadGuidanceSettings()
  if (isLayoutPreviewMode) {
    loadPrePublishLayoutPreview()
    window.addEventListener('keydown', handleWorkspaceKeydown)
    return
  }
  loadWorkspaceState()
  void refreshTasks()
  void refreshMediaFeatureAvailability()
  window.addEventListener('keydown', handleWorkspaceKeydown)
})

function loadPrePublishLayoutPreview() {
  const fixture = createPrePublishLayoutPreviewFixture()
  taskModule.tasks.value = [fixture.taskSummary]
  taskModule.selectedTask.value = fixture.task
  taskModule.fillTaskForm(fixture.task)
  suggestion.value = fixture.suggestion
  workflowMessages.value = fixture.workflowMessages
  workflowSession.value = fixture.workflowSession
  workflowSteps.value = fixture.workflowSteps
  creatorPreferences.value = fixture.creatorPreferences
  creatorContextTerms.value = fixture.creatorContextTerms
  selectedWorkflowMessageId.value = fixture.workflowMessages[0]?.messageId ?? ''
  prePublishForm.preferenceMode = 'USE_HISTORY'
  taskManageMode.value = 'create'
  isTaskComposerOpen.value = true
  activeStep.value = 'prePublish'
  errorMessage.value = ''
}

async function refreshMediaFeatureAvailability() {
  try {
    const status = await getMediaFeatureStatus()
    isMediaFeatureEnabled.value = status.enabled
  } catch {
    // 探测失败同样视为不可用，避免未知状态下绕过发布前试映进入下游阶段。
    isMediaFeatureEnabled.value = false
  } finally {
    isMediaFeatureAvailabilityResolved.value = true
  }
  const task = selectedTask.value
  if (!task || !requiresPreflight(task)) {
    return
  }
  if (!isMediaFeatureEnabled.value) {
    activeStep.value = 'production'
    errorMessage.value = mediaFeatureUnavailableMessage
    return
  }
  void refreshCurrentDraftVideo(task.taskId)
  activeStep.value = 'production'
}

onBeforeUnmount(() => {
  closeWorkflowEventSource()
  window.removeEventListener('keydown', handleWorkspaceKeydown)
})

function confirmDiscardUnsavedInput(message: string) {
  return !hasUnsavedCurrentTaskInput.value || window.confirm(message)
}

onBeforeRouteLeave(() =>
  confirmDiscardUnsavedInput('当前任务还有未保存的输入，离开后会清空。确定继续吗？'),
)

watch(
  () => props.developerMode,
  (enabled) => {
    if (enabled) {
      return
    }
    if (activeStep.value === 'usage') {
      activeStep.value = selectedTask.value ? 'prePublish' : 'task'
    }
    isDeveloperTestOpen.value = false
    workflowMessageModalOpen.value = false
  },
)

watch(
  () => workflowMessages.value.length,
  () => {
    void scrollWorkflowMessagesToBottom()
  },
)

function openTaskManager() {
  pendingDeleteTask.value = null
  isTaskManagerOpen.value = true
}

function closeTaskManager() {
  isTaskManagerOpen.value = false
}

function openContextLibrary() {
  isContextLibraryOpen.value = true
}

function handleWorkspaceKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape') {
    return
  }
  if (isTaskManagerOpen.value) {
    closeTaskManager()
  }
  if (workflowMessageModalOpen.value) {
    closeWorkflowMessageModal()
  }
}

async function scrollWorkflowMessagesToBottom() {
  await nextTick()
  const messageList = workflowMessageListRef.value
  if (!messageList) {
    return
  }
  messageList.scrollTop = messageList.scrollHeight
}

/** 关闭成功通知（NotificationToast 组件通过 @close 事件触发，定时器由组件内部管理） */
function closeSuccessToast() {
  successMessage.value = ''
}

async function refreshTasks() {
  await taskModule.refreshTasks()
  // 编排：检查是否有待恢复的任务，自动选中
  const targetTask = resolveRefreshTargetTask()
  if (!targetTask) {
    resetSelectedWorkspace()
    creatorStore.selectedTaskId = null
    return
  }
  if (targetTask.taskId !== selectedTask.value?.taskId) {
    await selectTask(targetTask.taskId)
  }
}

// openDeveloperTest 保留在组件：依赖 isDeveloperTestOpen (组件 UI 状态) + evalCases (composable)
function openDeveloperTest() {
  if (!showDeveloperTools.value) {
    return
  }
  isDeveloperTestOpen.value = true
  if (evalCases.value.length === 0 && !isLoadingEvalCases.value) {
    void loadEvaluationCases()
  }
}

function resetTaskForm() { taskModule.resetTaskForm() }
function hasTaskAnalysisInputChanged(task: CreatorTask) { return taskModule.hasTaskAnalysisInputChanged(task) }

function resetGeneratedTaskResults() {
  workflowModule.resetWorkflowState()
  feedbackModule.resetFeedbackData()
  resultModalTarget.value = null
  hasPrePublishPreferenceModeSnapshot.value = false
}

function resetTaskLocalDrafts() {
  resetContextTermForm()
  isContextLibraryOpen.value = false
}

async function submitTask() {
  if (taskManageMode.value === 'edit') {
    await updateTask()
    return
  }
  // 委托 taskModule 执行创建（内部已处理 loading / selectedTask / store）
  const task = await taskModule.submitTask()
  if (!task) return
  // 编排层：跨域操作 + UI 状态
  resetTaskGuidanceFields()
  resetGeneratedTaskResults()
  resetTaskLocalDrafts()
  taskModule.resetTaskForm()
  restoredTaskId.value = task.taskId
  activeStep.value = 'prePublish'
  await Promise.all([
    loadCreatorPreferences(task.userId),
    loadCreatorContextTerms(task.userId, task.videoType),
  ])
  await loadPrePublishWorkflow(task.taskId)
  await refreshTasks()
  successMessage.value = '创作任务已创建，可以继续做发布前优化。'
}

async function updateTask() {
  if (!selectedTask.value) return
  const analysisInputChanged = hasTaskAnalysisInputChanged(selectedTask.value)
  const returnStep = editReturnStep.value
  // 委托 taskModule 执行更新（内部已处理 loading / store / selectedTask）
  const task = await taskModule.submitUpdateTask()
  if (!task) return
  // 编排层：跨域操作
  restoredTaskId.value = task.taskId
  await Promise.all([
    loadCreatorPreferences(task.userId),
    loadCreatorContextTerms(task.userId, task.videoType),
  ])
  if (analysisInputChanged) {
    resetGeneratedTaskResults()
    await loadPrePublishWorkflow(task.taskId, false)
  } else {
    await loadPrePublishWorkflow(task.taskId)
  }
  taskManageMode.value = 'create'
  activeStep.value = analysisInputChanged ? 'prePublish' : normalizeReturnStepForTask(task, returnStep)
  await refreshTasks()
  successMessage.value = analysisInputChanged
    ? '任务分析输入已更新，旧建议已清空，请重新生成。'
    : '任务名称已更新。'
}

function startCreateTask() {
  if (!confirmDiscardUnsavedInput('当前任务还有未保存的输入，新建任务后会清空。确定继续吗？')) {
    return
  }
  taskModule.startCreateTask()
  taskManageMode.value = 'create'
  isTaskComposerOpen.value = true
  pendingDeleteTask.value = null
  errorMessage.value = ''
  successMessage.value = ''
  activeStep.value = 'task'
  closeTaskManager()
}

function returnToAiCreation() {
  if (!confirmDiscardUnsavedInput('当前任务还有未保存的输入，离开后会清空。确定继续吗？')) {
    return
  }
  resetSelectedWorkspace()
  // 主动返回创意入口时清空恢复标记，避免任务列表刷新后又自动进入刚离开的项目。
  restoredTaskId.value = ''
  pendingDeleteTask.value = null
  errorMessage.value = ''
  successMessage.value = ''
  closeTaskManager()
}

async function startEditTask(taskId: string) {
  errorMessage.value = ''
  successMessage.value = ''
  editReturnStep.value = resolveCurrentEditReturnStep()
  const alreadyLoaded = selectedTask.value?.taskId === taskId
  if (alreadyLoaded) {
    if (!isTaskMaterialsEditable(selectedTask.value!)) {
      errorMessage.value = '发布方案已确认，任务资料仅可查看，不支持原地修改。'
      return
    }
    taskManageMode.value = 'edit'
    isTaskComposerOpen.value = true
    taskModule.fillTaskForm(selectedTask.value!)
    activeStep.value = 'task'
    pendingDeleteTask.value = null
    closeTaskManager()
    return
  }
  await selectTask(taskId)
  if (selectedTask.value?.taskId !== taskId) return
  if (!isTaskMaterialsEditable(selectedTask.value)) {
    errorMessage.value = '发布方案已确认，任务资料仅可查看，不支持原地修改。'
    return
  }
  taskManageMode.value = 'edit'
  isTaskComposerOpen.value = true
  taskModule.fillTaskForm(selectedTask.value!)
  activeStep.value = 'task'
  pendingDeleteTask.value = null
  closeTaskManager()
}

function cancelEditTask() {
  taskModule.cancelEditTask()
  taskManageMode.value = 'create'
  if (!selectedTask.value) {
    activeStep.value = 'task'
    return
  }
  taskModule.fillTaskForm(selectedTask.value)
  activeStep.value = normalizeReturnStepForTask(selectedTask.value, editReturnStep.value)
}

/**
 * 发布方案确认前任务保持 DRAFT，可继续修正文稿和视频类型；
 * 确认后这些输入会成为反馈与复盘的基线，只允许查看而不能原地覆盖。
 */
function isTaskMaterialsEditable(task: Pick<CreatorTask, 'status'>) {
  return task.status === 'DRAFT'
}

function creatorStepIndex(stepKey: CreatorStepKey) {
  return creatorStepMetas.value.findIndex((step) => step.key === stepKey)
}

function isCreatorStepCompleted(stepKey: CreatorStepKey) {
  // 成片试映必须完成媒体探测、预处理和 P0-3 试映，避免页面提前开放发布后流程。
  if (stepKey === 'preflight') {
    return hasCompletedPreflightReview.value
  }
  if (stepKey === 'production') {
    return productionPlanReady.value
  }
  const index = creatorStepIndex(stepKey)
  return index >= 0 && index < currentTaskProgressIndex.value
}

function canNavigateCreatorStep(stepKey: CreatorStepKey) {
  if (stepKey === 'task') {
    return Boolean(selectedTask.value || isTaskComposerOpen.value)
  }
  if (stepKey === 'prePublish') {
    return hasSelectedTask.value
  }
  if (stepKey === 'production') {
    return hasSelectedTask.value && hasConfirmedPrePublish.value
  }
  if (stepKey === 'preflight') {
    return canEnterPreflight.value
  }
  if (stepKey === 'feedback') {
    return canEnterFeedback.value
  }
  return Boolean(feedbackReport.value)
}

function navigateCreatorStep(stepKey: CreatorStepKey) {
  if ((stepKey === 'preflight' || stepKey === 'feedback') && hasConfirmedPrePublish.value && !productionPlanReady.value) {
    errorMessage.value = '请先完成制作蓝图中的全部步骤，再进入成片试映。'
    activeStep.value = 'production'
    return
  }
  if (
    (stepKey === 'preflight' || stepKey === 'feedback' || stepKey === 'report') &&
    selectedTask.value &&
    requiresPreflight(selectedTask.value) &&
    !isMediaFeatureEnabled.value
  ) {
    errorMessage.value = mediaFeatureUnavailableMessage
    return
  }
  if (
    stepKey === 'feedback' &&
    selectedTask.value &&
    requiresPreflight(selectedTask.value) &&
    isMediaFeatureEnabled.value &&
    !hasCompletedPreflightReview.value
  ) {
    errorMessage.value = mediaPreflightRequiredMessage
    return
  }
  if (!canNavigateCreatorStep(stepKey)) {
    return
  }
  if (taskManageMode.value === 'edit') {
    taskModule.cancelEditTask()
    if (selectedTask.value) {
      taskModule.fillTaskForm(selectedTask.value)
    }
    taskManageMode.value = 'create'
  }
  if (stepKey === 'task' && selectedTask.value) {
    // 已确认任务的资料页只读展示仍复用同一份表单状态，
    // 每次进入时回填当前任务，避免显示上一个任务残留的材料而误导创作者。
    taskModule.fillTaskForm(selectedTask.value)
  }
  isTaskComposerOpen.value = true
  activeStep.value = stepKey
}

function askDeleteTask(task: CreatorTaskSummary) { taskModule.askDeleteTask(task) }

function askDeleteSelectedTask() {
  if (!selectedTask.value) {
    return
  }
  taskModule.askDeleteTask({
    id: selectedTask.value.id,
    taskId: selectedTask.value.taskId,
    userId: selectedTask.value.userId,
    taskName: selectedTask.value.taskName,
    videoType: selectedTask.value.videoType,
    status: selectedTask.value.status,
    materialCount: selectedTask.value.materials.length,
    createTime: selectedTask.value.createTime,
    updateTime: selectedTask.value.updateTime,
  })
  errorMessage.value = ''
  successMessage.value = ''
}

function cancelDeleteTask() {
  taskModule.cancelDeleteTask()
}

async function confirmDeleteTask() {
  const targetTaskId = pendingDeleteTask.value?.taskId
  if (!targetTaskId) {
    return
  }
  await taskModule.confirmDeleteTask()
  if (pendingDeleteTask.value?.taskId === targetTaskId) {
    return
  }
  // 编排：删除成功后补充 toast，并刷新一次主壳状态，确保选中任务和历史列表保持一致。
  successMessage.value = '任务已删除，列表会自动刷新。'
  await refreshTasks()
}

async function selectTask(taskId: string) {
  if (selectedTaskId.value === taskId && !hasUnsavedCurrentTaskInput.value) {
    closeTaskManager()
    return
  }
  const discardMessage = selectedTaskId.value === taskId
    ? '当前任务还有未保存的输入，重新载入后会清空。确定继续吗？'
    : '当前任务还有未保存的输入，切换后会清空。确定继续吗？'
  if (!confirmDiscardUnsavedInput(discardMessage)) {
    return
  }
  errorMessage.value = ''
  successMessage.value = ''
  const task = await taskModule.loadTask(taskId)
  if (!task) return

  resultModalTarget.value = null
  pendingDeleteTask.value = null
  taskManageMode.value = 'create'
  taskModule.resetTaskForm()
  currentDraftVideo.value = null
  currentMediaProcessingStatus.value = null
  currentPreflightReviewStatus.value = null
  productionPlanReady.value = false
  workflowModule.resetWorkflowState()
  feedbackModule.resetFeedbackData()
  resetTaskLocalDrafts()
  // 编排层：跨域操作
  isTaskComposerOpen.value = true
  activeStep.value = resolveTaskEntryStep(task)
  taskModule.fillTaskForm(task)
  resetTaskGuidanceFields()
  if (requiresPreflight(task) && isMediaFeatureAvailabilityResolved.value && !isMediaFeatureEnabled.value) {
    errorMessage.value = mediaFeatureUnavailableMessage
  }
  restoredTaskId.value = task.taskId
  await Promise.all([
    loadCreatorPreferences(task.userId),
    loadCreatorContextTerms(task.userId, task.videoType),
    refreshCurrentDraftVideo(task.taskId),
  ])
  if (selectedTaskId.value !== task.taskId) return
  await loadOptionalResults(task)
  if (selectedTaskId.value !== task.taskId) return
  await loadPrePublishWorkflow(taskId)
  if (selectedTaskId.value !== task.taskId) return
  activeStep.value = resolveTaskEntryStep(task)
  await refreshUsageStats(1)
  closeTaskManager()
}

async function refreshCurrentDraftVideo(taskId = selectedTask.value?.taskId) {
  const generation = ++currentDraftRefreshGeneration
  if (!taskId || !isMediaFeatureEnabled.value) {
    currentDraftVideo.value = null
    currentMediaProcessingStatus.value = null
    currentPreflightReviewStatus.value = null
    return
  }
  try {
    const draft = await getCurrentDraftVideo(taskId)
    if (generation !== currentDraftRefreshGeneration || selectedTask.value?.taskId !== taskId) {
      return
    }
    currentDraftVideo.value = draft
    if (draft.status !== 'READY_FOR_REVIEW') {
      currentMediaProcessingStatus.value = null
      currentPreflightReviewStatus.value = null
      return
    }
    try {
      const job = await getCurrentMediaProcessingJob(taskId, draft.versionId)
      if (generation === currentDraftRefreshGeneration && selectedTask.value?.taskId === taskId) {
        currentMediaProcessingStatus.value = job.status
      }
      if (job.status !== 'COMPLETED') {
        currentPreflightReviewStatus.value = null
        return
      }
      try {
        const review = await getCurrentPreflightReview(taskId, draft.versionId)
        if (generation === currentDraftRefreshGeneration && selectedTask.value?.taskId === taskId) {
          currentPreflightReviewStatus.value = review.status
        }
      } catch (error) {
        if (generation !== currentDraftRefreshGeneration || selectedTask.value?.taskId !== taskId) {
          return
        }
        if (error instanceof ApiError && error.status === 404) {
          currentPreflightReviewStatus.value = null
          return
        }
        // 临时读取失败时保留上一次试映状态，避免网络抖动让已完成门禁倒退。
        showError(error)
      }
    } catch (error) {
      if (generation !== currentDraftRefreshGeneration || selectedTask.value?.taskId !== taskId) {
        return
      }
      if (error instanceof ApiError && error.status === 404) {
        currentMediaProcessingStatus.value = null
        currentPreflightReviewStatus.value = null
        return
      }
      // 临时读取失败时保留上一次处理状态，避免网络抖动让已完成门禁倒退。
      showError(error)
    }
  } catch (error) {
    if (generation !== currentDraftRefreshGeneration || selectedTask.value?.taskId !== taskId) {
      return
    }
    if (error instanceof ApiError && error.status === 404) {
      currentDraftVideo.value = null
      currentMediaProcessingStatus.value = null
      currentPreflightReviewStatus.value = null
      return
    }
    // 暂时读取失败时保留上一次服务端事实，避免把网络故障伪装成“没有成片”。
    showError(error)
  }
}

async function handleProductionPlanRegenerated() {
  const taskId = selectedTaskId.value
  if (!taskId) return
  // 后端已经使旧发布后链路失效，本地必须同步清空，避免刷新前仍展示旧报告和完成态。
  currentDraftRefreshGeneration += 1
  currentDraftVideo.value = null
  currentMediaProcessingStatus.value = null
  currentPreflightReviewStatus.value = null
  feedbackModule.resetFeedbackData()

  if (selectedTask.value?.taskId === taskId) {
    selectedTask.value = {
      ...selectedTask.value,
      status: 'PRE_PUBLISH_ANALYZED',
    }
  }
  await taskModule.refreshTasks()
}

async function handleCreativeOptionConfirmed(taskId: string) {
  await selectTask(taskId)
  if (selectedTaskId.value !== taskId) return
  activeStep.value = 'production'
  successMessage.value = '发布方案已确认，下一步生成制作蓝图。'
}

async function loadOptionalResults(task: CreatorTask) {
  usageSummary.value = null
  usageCallPage.value = null
  usageCurrentPage.value = 1
  usageCategoryFilter.value = 'ALL'

  if (hasPrePublishResult(task.status)) {
    await feedbackModule.loadFeedbackData(task.taskId, hasFeedbackResult(task.status))
  }
}

function resetContextTermForm() {
  contextTermForm.term = ''
  contextTermForm.termType = 'KEYWORD'
  contextTermForm.evidenceText = ''
}

async function saveManualContextTerm() {
  if (!canSaveContextTerm.value) {
    return
  }
  await saveContextTerm({
    term: contextTermForm.term,
    termType: contextTermForm.termType,
    polarity: contextTermPolarity(contextTermForm.termType),
    sourceType: 'USER_SAVE',
    evidenceText: contextTermForm.evidenceText,
  })
  resetContextTermForm()
}

async function saveContextTermFromSuggestion(
  term: string,
  termType: CreatorContextTermType,
  evidenceText?: string,
) {
  if (!hasText(term)) {
    return
  }
  await saveContextTerm({
    term,
    termType,
    polarity: contextTermPolarity(termType),
    sourceType: 'AI_ACCEPTED',
    evidenceText,
  })
}

async function saveContextTerm(payload: Omit<CreatorContextTermPayload, 'videoType'>) {
  if (!selectedTask.value && !hasText(taskForm.videoType)) {
    return
  }
  const normalizedTerm = normalizeContextTermText(payload.term)
  if (!normalizedTerm) {
    return
  }
  const saveKey = `${payload.termType || 'KEYWORD'}-${normalizedTerm}`
  savingContextTermKey.value = saveKey
  isSavingCreatorContextTerm.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await saveCreatorContextTerm({
      userId: selectedTask.value?.userId || 'default',
      videoType: currentVideoType.value,
      sourceTaskId: selectedTaskId.value || undefined,
      ...payload,
      term: normalizedTerm,
      evidenceText: payload.evidenceText || payload.term,
    })
    await loadCreatorContextTerms(selectedTask.value?.userId, currentVideoType.value)
    successMessage.value = '已保存到当前视频类型语境库。'
  } catch (error) {
    showError(error)
  } finally {
    isSavingCreatorContextTerm.value = false
    savingContextTermKey.value = ''
  }
}

async function disableContextTerm(term: CreatorContextTerm) {
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await disableCreatorContextTerm(term.termId)
    await loadCreatorContextTerms(term.userId, term.videoType)
    successMessage.value = '语境词条已禁用。'
  } catch (error) {
    showError(error)
  }
}

async function feedbackContextTerm(term: CreatorContextTerm, accepted: boolean) {
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await recordCreatorContextTermFeedback(term.termId, accepted)
    await loadCreatorContextTerms(term.userId, term.videoType)
    successMessage.value = accepted ? '已提高该词条权重。' : '已降低该词条权重。'
  } catch (error) {
    showError(error)
  }
}

async function loadPrePublishWorkflow(taskId: string, resumeLatest = true) {
  await workflowModule.loadWorkflow({
    taskId,
    userId: selectedTask.value?.userId,
    resumeLatest,
  })
}

async function refreshPrePublishWorkflowMessages() {
  if (!selectedTaskId.value) return
  if (!workflowSession.value) {
    await loadPrePublishWorkflow(selectedTaskId.value)
    return
  }
  await workflowModule.refreshWorkflow()
}

async function generatePrePublishManuscriptDraftForCurrentTask(extraRequirement = '') {
  if (!selectedTaskId.value) return false
  if (hasPrePublishScriptMaterial.value) {
    errorMessage.value = '当前任务已有可用文稿或 AI 草稿，可以直接生成发布方案。'
    return false
  }
  if (!workflowSession.value) {
    await loadPrePublishWorkflow(selectedTaskId.value)
  }
  if (!workflowSession.value || !canGeneratePrePublishDraft.value) {
    return false
  }

  successMessage.value = ''
  const result = await workflowModule.generateDraft(extraRequirement)
  if (!result) return false
  try {
    const task = await getCreatorTask(result.taskId)
    if (selectedTaskId.value !== result.taskId) return false
    selectedTask.value = task
    await refreshUsageStats(1, false)
    await scrollWorkflowMessagesToBottom()
    successMessage.value = 'AI 已补全文稿草稿，可以继续补充修改要求或生成发布方案。'
    return true
  } catch (error) {
    showError(error)
    return false
  }
}

async function runPrePublishAnalyze() {
  if (!selectedTaskId.value) return
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
  successMessage.value = ''
  try {
    const result = await workflowModule.runAnalyze({
      customGuidance: prePublishForm.customGuidance,
      creatorPreference: prePublishForm.creatorPreference,
      titleStyle: prePublishForm.titleStyle,
      extraRequirement: prePublishForm.extraRequirement,
      preferenceMode: prePublishForm.preferenceMode,
    })
    if (!result) return
    lastPrePublishPreferenceMode.value = prePublishForm.preferenceMode
    hasPrePublishPreferenceModeSnapshot.value = true
    markPrePublishGuidanceSubmitted()
    successMessage.value = '发布前优化建议已生成，请确认采用后进入制作蓝图。'
  } finally {
    await refreshUsageStats(1, false)
  }
}

async function confirmPrePublishResult() {
  if (!selectedTaskId.value || !canConfirmPrePublish.value) return
  successMessage.value = ''
  const session = await workflowModule.confirmSuggestion()
  if (!session) return
  try {
    const task = await getCreatorTask(session.taskId)
    if (selectedTaskId.value !== session.taskId) return
    selectedTask.value = task
    await refreshMediaFeatureAvailability()
    if (!isMediaFeatureEnabled.value) return
    activeStep.value = 'production'
    successMessage.value = '已采用本轮发布前优化建议，请先生成制作蓝图。'
    await refreshTasks()
  } catch (error) {
    showError(error)
  }
}

async function sendWorkflowSupplement() {
  if (!canSendWorkflowMessage.value || !hasText(workflowMessageDraft.value)) return
  successMessage.value = ''
  const message = await workflowModule.sendSupplement()
  if (!message) return
  await scrollWorkflowMessagesToBottom()
  successMessage.value = '补充要求已写入工作流消息流。'
}

async function submitFeedback() {
  const result = await feedbackModule.submitFeedback()
  if (!result) return
  await syncTaskAfterFeedbackChange(result.taskId)
  if (selectedTaskId.value !== result.taskId) return
  resultModalTarget.value = null
  activeStep.value = 'feedback'
  successMessage.value = '评论弹幕样例已保存，可以开始分析。'
}

function handleFeedbackFileChange(event: Event) {
  feedbackModule.handleFeedbackFileChange(event)
}

async function importFeedbackFile() {
  if (!feedbackImportFile.value) return
  const taskId = selectedTaskId.value
  if (!taskId) return
  const result = await feedbackModule.importFeedbackFile()
  if (!result) return
  await syncTaskAfterFeedbackChange(taskId)
  if (selectedTaskId.value !== taskId) return
  successMessage.value = `已导入 ${result.commentCount} 条评论、${result.danmakuCount} 条弹幕，仪表盘已更新。`
  openResultModal('feedbackDashboard')
}

async function fetchFeedbackByBv() {
  if (!feedbackScriptBv.value) return
  const taskId = selectedTaskId.value
  if (!taskId) return
  const result = await feedbackModule.fetchFeedbackByBv()
  if (!result) return
  await syncTaskAfterFeedbackChange(taskId)
  if (selectedTaskId.value !== taskId) return
  successMessage.value = showDeveloperTools.value
    ? `已读取 ${result.commentCount} 条评论、${result.danmakuCount} 条弹幕，文件已保存到 ${result.outputDirectory}。`
    : `已读取 ${result.commentCount} 条评论、${result.danmakuCount} 条弹幕。`
  openResultModal('feedbackDashboard')
}

async function syncTaskAfterFeedbackChange(taskId: string) {
  if (selectedTaskId.value !== taskId) return
  feedbackReport.value = null
  if (selectedTask.value?.taskId === taskId) {
    selectedTask.value = { ...selectedTask.value, status: 'FEEDBACK_COLLECTING' }
  }
  await Promise.all([
    taskModule.refreshTasks(),
    selectedTask.value ? loadCreatorPreferences(selectedTask.value.userId) : Promise.resolve(),
  ])
  if (selectedTaskId.value === taskId) activeStep.value = 'feedback'
}

async function runFeedbackAnalyze() {
  if (!canRunFeedbackAnalyze.value) return
  const taskId = selectedTaskId.value
  try {
    const report = await feedbackModule.runAnalyze({
      customGuidance: feedbackAnalyzeForm.customGuidance,
      analysisFocus: feedbackAnalyzeForm.analysisFocus,
      extraRequirement: feedbackAnalyzeForm.extraRequirement,
    })
    if (!report || selectedTaskId.value !== taskId) return
    markFeedbackGuidanceSubmitted()
    if (selectedTask.value?.taskId === report.taskId) {
      selectedTask.value = { ...selectedTask.value, status: 'FEEDBACK_ANALYZED' }
    }
    activeStep.value = 'report'
    successMessage.value = '反馈分析完成，可以继续选择参考案例进行竞品分析。'
    await taskModule.refreshTasks()
  } catch (error) {
    showError(error)
  } finally {
    if (selectedTaskId.value === taskId) {
      await refreshUsageStats(1, false)
    }
  }
}

async function handleCreatorReportGenerated(report: CreatorReport) {
  const taskId = report.taskId
  if (selectedTaskId.value !== taskId) return
  if (selectedTask.value?.taskId === report.taskId) {
    selectedTask.value = { ...selectedTask.value, status: 'ANALYZED' }
  }
  successMessage.value = '总体复盘已生成。'
  await Promise.all([
    taskModule.refreshTasks(),
    selectedTask.value ? loadCreatorPreferences(selectedTask.value.userId) : Promise.resolve(),
  ])
  if (selectedTaskId.value === taskId) {
    await refreshUsageStats(1, false)
  }
}

async function askFeedbackChat() {
  if (!canAskFeedbackChat.value) return
  const taskId = selectedTaskId.value
  const result = await feedbackModule.askChat()
  if (result) {
    successMessage.value = '反馈追问已生成，回答基于当前反馈分析和评论弹幕证据。'
  }
  if (selectedTaskId.value === taskId) {
    await refreshUsageStats(1, false)
  }
}

async function loadFeedbackEvidenceIndexStatus() {
  await feedbackModule.loadEvidenceIndexStatus()
}

async function rebuildFeedbackEvidenceIndex() {
  const result = await feedbackModule.rebuildEvidenceIndex()
  if (!result) return
  await refreshUsageStats(1, false)
}

function requiresPreflight(task: Pick<CreatorTask, 'status'>) {
  return hasPrePublishResult(task.status) && !hasFeedbackResult(task.status)
}

function resolveTaskEntryStep(task: Pick<CreatorTask, 'status'>): CreatorWorkStep {
  if (hasFeedbackResult(task.status)) {
    return 'report'
  }
  if (task.status === 'FEEDBACK_COLLECTING') {
    return 'feedback'
  }
  if (hasPrePublishResult(task.status) && hasConfirmedPrePublish.value) {
    return 'production'
  }
  return 'prePublish'
}

function normalizeReturnStepForTask(
  task: Pick<CreatorTask, 'status'>,
  targetStep: CreatorWorkStep,
): CreatorWorkStep {
  // 下游阶段必须由任务状态支撑，避免旧的本地阶段把用户带到不可用页面。
  if (targetStep === 'report' && !hasFeedbackResult(task.status)) {
    return resolveTaskEntryStep(task)
  }
  if (targetStep === 'feedback' && !hasPrePublishResult(task.status)) {
    return resolveTaskEntryStep(task)
  }
  if (targetStep === 'preflight' && !hasPrePublishResult(task.status)) {
    return resolveTaskEntryStep(task)
  }
  if (targetStep === 'production' && !hasPrePublishResult(task.status)) {
    return resolveTaskEntryStep(task)
  }
  if (
    targetStep === 'feedback' &&
    requiresPreflight(task) &&
    (!isMediaFeatureEnabled.value || !hasCompletedPreflightReview.value)
  ) {
    return resolveTaskEntryStep(task)
  }
  return targetStep
}

function resolveCurrentEditReturnStep(): CreatorWorkStep {
  if (
    activeStep.value === 'prePublish' ||
    activeStep.value === 'production' ||
    activeStep.value === 'preflight' ||
    activeStep.value === 'feedback' ||
    activeStep.value === 'report'
  ) {
    return activeStep.value
  }
  if (selectedTask.value) {
    return resolveTaskEntryStep(selectedTask.value)
  }
  return 'prePublish'
}

function resolveRefreshTargetTask() {
  const currentTaskId = selectedTask.value?.taskId
  const targetTaskId = currentTaskId || restoredTaskId.value
  if (!targetTaskId) {
    return null
  }
  const matchedTask = tasks.value.find((task) => task.taskId === targetTaskId)
  if (matchedTask) {
    return matchedTask
  }
  return null
}

function resetSelectedWorkspace() {
  workflowModule.resetWorkflowState()
  feedbackModule.resetFeedbackData()
  resetTaskGuidanceFields()
  resetTaskLocalDrafts()
  selectedTask.value = null
  currentDraftVideo.value = null
  currentMediaProcessingStatus.value = null
  currentPreflightReviewStatus.value = null
  productionPlanReady.value = false
  creatorStore.selectedTaskId = null
  taskManageMode.value = 'create'
  resetTaskForm()
  creatorPreferences.value = []
  creatorContextTerms.value = []
  usageSummary.value = null
  usageCallPage.value = null
  usageCurrentPage.value = 1
  usageCategoryFilter.value = 'ALL'
  resultModalTarget.value = null
  isTaskComposerOpen.value = false
  activeStep.value = 'task'
  closeDeveloperTest()
}

// 从 Pinia creatorStore 恢复上次选中的任务 ID，替代原来的 localStorage 直接读取。
// Pinia persist 插件在 store 初始化时已自动从 localStorage 反序列化 selectedTaskId。
function loadWorkspaceState() {
  if (activeStep.value === 'videoBinding') {
    // 旧版本把发布后 BV 绑定放在创作台主流程里；现在确认发布方案后应回到发布前试映链路。
    activeStep.value = 'production'
  }
  if (creatorStore.selectedTaskId) {
    restoredTaskId.value = creatorStore.selectedTaskId
  }
}

function openResultModal(target: ResultModalTarget) {
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
  // 证据索引是工程诊断信息，只有开发者模式才提前读取，避免普通用户看到 RAG/Milvus 等概念。
  if (target === 'feedbackReport' && showDeveloperTools.value) {
    feedbackEvidenceIndexWarnings.value = []
    void loadFeedbackEvidenceIndexStatus()
  }
}

function closeResultModal() {
  resultModalTarget.value = null
  isFeedbackChatDrawerOpen.value = false
}

function openWorkflowMessageModal() {
  if (!hasSelectedTask.value) {
    return
  }
  workflowMessageModalOpen.value = true
  void scrollWorkflowMessagesToBottom()
}

function closeWorkflowMessageModal() {
  workflowMessageModalOpen.value = false
}

function openFeedbackChatDrawer() {
  if (!feedbackReport.value) {
    return
  }
  isFeedbackChatDrawerOpen.value = true
  feedbackEvidenceIndexWarnings.value = []
  void loadFeedbackEvidenceIndexStatus()
}

function closeFeedbackChatDrawer() {
  isFeedbackChatDrawerOpen.value = false
}

function toggleFeedbackChatDrawer() {
  if (isFeedbackChatDrawerOpen.value) {
    closeFeedbackChatDrawer()
    return
  }
  openFeedbackChatDrawer()
}

function closeDeveloperTest() {
  isDeveloperTestOpen.value = false
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

function showError(error: unknown) {
  errorMessage.value = error instanceof Error ? error.message : '请求失败'
}

function updateWorkflowMessageSelection(messageId: string) {
  selectedWorkflowMessageId.value = messageId
}

function updateWorkflowMessageDraft(draft: string) {
  workflowMessageDraft.value = draft
}

function updateWorkflowMessageListRef(element: HTMLDivElement | null) {
  workflowMessageListRef.value = element
}

function updateFeedbackChatQuestion(question: string) {
  feedbackChatForm.question = question
}

function updateEvaluationStageFilter(stage: 'ALL' | CreatorWorkflowStage) {
  evalStageFilter.value = stage
  void loadEvaluationCases()
}

function updateEvaluationResultDraft(patch: Partial<CreatorEvaluationResultDraft>) {
  Object.assign(evalResultDraft, patch)
}

function updateSelectedEvalResultId(resultId: string) {
  selectedEvalResultId.value = resultId
}

// 子组件只负责承载下沉后的模板，核心编排仍由主壳统一持有，
// 这样任务恢复、SSE 连接和跨 tab 结果弹窗不会因为拆分产生第二份状态。
provideCreatorWorkspace({
  feedbackEvent,
  shell: {
    askDeleteSelectedTask,
    cancelEditTask,
    canConfirmPrePublish,
    canEnterFeedback,
    canGeneratePrePublishDraft,
    canRunFeedbackAnalyze,
    canRunPrePublishAnalyze,
    canSendWorkflowMessage,
    changeUsageCategoryFilter,
    changeUsagePage,
    confirmPrePublishResult,
    contextTermChips,
    currentDraftVideo,
    currentMediaProcessingStatus,
    currentPreflightReviewStatus,
    currentVideoType,
    feedback,
    feedbackAnalyzeForm,
    feedbackDashboard,
    feedbackFetchResult,
    feedbackForm,
    feedbackImportFile,
    feedbackReport,
    feedbackScriptBv,
    feedbackScriptForm,
    fetchFeedbackByBv,
    formatDate,
    formatDuration,
    formatInputCount,
    formatUsageToken,
    hasConfirmedPrePublish,
    hasFeedbackSampleInput,
    hasPrePublishPreferenceModeSnapshot,
    hasPrePublishScriptMaterial,
    hasSelectedTask,
    hasTaskMaterialInput,
    generatePrePublishManuscriptDraftForCurrentTask,
    handleFeedbackFileChange,
    historicalPreferenceChips,
    importFeedbackFile,
    isActiveStepReadOnly,
    isAnalyzingFeedback,
    isAnalyzingPrePublish,
    isConfirmingPrePublish,
    isCreatingTask,
    isFetchingFeedback,
    isGeneratingPrePublishDraft,
    isImportingFeedback,
    isLoadingCreatorContextTerms,
    isLoadingCreatorPreferences,
    isLoadingUsageStats,
    isSavingCreatorContextTerm,
    isSavingFeedback,
    isSendingWorkflowMessage,
    isUpdatingTask,
    lastPreferenceModeLabel,
    lastPreferenceModeNote,
    openContextLibrary,
    openGuidanceEditor,
    openResultModal,
    openTaskManager,
    openWorkflowMessageModal,
    preferenceModeNote,
    preferenceModeOptions,
    prePublishForm,
    refreshUsageStats,
    runFeedbackAnalyze,
    runPrePublishAnalyze,
    saveContextTermFromSuggestion,
    savingContextTermKey,
    selectedPreferenceModeLabel,
    selectedTask,
    selectedTaskId,
    shortId,
    showDeveloperTools,
    startEditTask,
    statusLabel,
    submitFeedback,
    submitTask,
    suggestion,
    taskForm,
    taskFormHint,
    taskFormTitle,
    taskManageMode,
    taskSubmitLabel,
    usageCallPage,
    usageCategoryFilter,
    usageCategoryLabel,
    usageCategoryOptions,
    usageCategorySummaries,
    usageCurrentPage,
    usageStatusClass,
    usageStatusLabel,
    usageSummary,
    usageTotalPages,
    videoTypeOptions,
    workflowRunningStep,
    workflowSession,
    workflowSseText,
    workflowStatusText,
    workflowSteps,
    refreshCurrentDraftVideo,
  },
})
</script>

<template>
  <section class="creator-shell creator-workbench-shell">
    <NotificationToast
      type="success"
      :message="successMessage"
      @close="closeSuccessToast"
    />

    <header v-if="!selectedTask && !isTaskComposerOpen" class="creator-header">
      <div>
        <p class="creator-kicker">创作台</p>
        <h2>视频发布与复盘助手</h2>
        <p>围绕一条视频整理资料、生成发布方案、读懂观众反馈，并沉淀下一期行动。</p>
      </div>
      <div
        v-if="selectedTask || isTaskComposerOpen || showDeveloperTools"
        class="creator-header-actions"
      >
        <div
          v-if="selectedTask || isTaskComposerOpen"
          class="creator-status-strip"
          aria-label="创作进度"
        >
          <span :class="{ active: Boolean(selectedTask) }">视频资料</span>
          <span :class="{ active: Boolean(suggestion) }">发布方案</span>
          <span :class="{ active: activeStep === 'production' || productionPlanReady }">制作蓝图</span>
          <span v-if="isMediaFeatureEnabled" :class="{ active: activeStep === 'preflight' }">成片试映</span>
          <span :class="{ active: Boolean(feedback || feedbackDashboard) }">观众反馈</span>
          <span :class="{ active: ['ANALYZED', 'ARCHIVED'].includes(selectedTask?.status ?? '') }">总体复盘</span>
        </div>
        <button
          v-if="showDeveloperTools"
          type="button"
          class="creator-secondary-action creator-mini-button creator-dev-test-button"
          @click="openDeveloperTest"
        >
          开发者测试
        </button>
        <button
          v-if="selectedTask || isTaskComposerOpen"
          type="button"
          class="creator-secondary-action creator-mini-button"
          @click="openTaskManager"
        >
          继续历史项目
        </button>
      </div>
    </header>

    <CreatorTaskManagerModal
      :open="isTaskManagerOpen"
      :tasks="tasks"
      :filtered-tasks="filteredTasks"
      :summary="taskSummaryStats"
      :selected-task-id="selectedTaskId"
      :loading="isLoadingTasks"
      :search-query="taskSearchQuery"
      :status-filter="taskStatusFilter"
      :status-options="taskStatusOptions"
      :can-edit-task="isTaskMaterialsEditable"
      @close="closeTaskManager"
      @create="startCreateTask"
      @refresh="refreshTasks"
      @select="selectTask"
      @edit="startEditTask"
      @delete="askDeleteTask"
      @update:search-query="taskSearchQuery = $event"
      @update:status-filter="taskStatusFilter = $event"
    />

    <CreatorDeleteConfirmModal
      :pending-task="pendingDeleteTask"
      :deleting="isDeletingTask"
      @cancel="cancelDeleteTask"
      @confirm="confirmDeleteTask"
    />

    <section
      v-if="!selectedTask && !isTaskComposerOpen"
      class="creator-start-screen"
      aria-label="创作台入口"
    >
      <AiCreationConsole @confirmed="handleCreativeOptionConfirmed" />
      <div class="creator-start-actions" aria-label="其他创建方式">
        <button
          v-if="hasTaskHistory"
          type="button"
          class="creator-start-link-action"
          @click="openTaskManager"
        >
          继续上次复盘
        </button>
        <button type="button" class="creator-start-link-action" @click="startCreateTask">
          手动填写资料
        </button>
      </div>
    </section>

    <div v-else class="creator-layout">
      <aside class="creator-task-rail">
        <CreatorFlowNav
          :active-step="activeStep"
          :steps="creatorStepMetas"
          :active-step-index="activeCreatorStepIndex"
          :active-step-meta="activeCreatorStepMeta"
          :progress-percent="creatorProgressPercent"
          :can-navigate="canNavigateCreatorStep"
          :is-completed="isCreatorStepCompleted"
          @navigate="navigateCreatorStep"
        />

        <TaskListPanel />
      </aside>

      <section class="creator-main">
        <NotificationToast
          type="error"
          :message="errorMessage"
          @close="errorMessage = ''"
        />

        <MaterialsTab
          v-if="activeStep === 'task'"
          @back-to-ai-creation="returnToAiCreation"
        />

        <Teleport to="body">
          <CreatorDevTestModal
            :open="isDeveloperTestOpen"
            :stage-filter="evalStageFilter"
            :stats="evalStats"
            :filtered-cases="filteredEvalCases"
            :selected-case="selectedEvalCase"
            :results="evalResults"
            :selected-result="selectedEvalResult"
            :prompt-version-stats="evalPromptVersionStats"
            :draft="evalResultDraft"
            :loading-cases="isLoadingEvalCases"
            :loading-results="isLoadingEvalResults"
            :recording="isRecordingEvalResult"
            :can-record="canRecordEvalResult"
            @close="closeDeveloperTest"
            @update:stage-filter="updateEvaluationStageFilter"
            @update:selected-result-id="updateSelectedEvalResultId"
            @update:draft="updateEvaluationResultDraft"
            @reload-cases="loadEvaluationCases"
            @select-case="selectEvalCase"
            @submit-result="submitEvalResult"
            @refresh-results="refreshEvaluationResults"
          />
        </Teleport>

        <PrePublishTab v-if="activeStep === 'prePublish'" />

        <ProductionPlanTab
          v-if="activeStep === 'production'"
          @ready-change="productionPlanReady = $event"
          @regenerated="handleProductionPlanRegenerated"
        />

        <PreflightTab v-if="isMediaFeatureEnabled && activeStep === 'preflight'" />

        <FeedbackTab v-if="activeStep === 'feedback'" />

        <ReportTab v-if="activeStep === 'report'" @generated="handleCreatorReportGenerated" />

        <UsageTab v-if="activeStep === 'usage'" />
      </section>
    </div>

    <CreatorWorkflowMessageModal
      :open="workflowMessageModalOpen"
      :status-text="workflowStatusText"
      :sse-text="workflowSseText"
      :has-selected-task="hasSelectedTask"
      :has-selected-task-materials="hasSelectedTaskMaterials"
      :loading="isLoadingWorkflow"
      :messages="workflowMessages"
      :selected-message="selectedWorkflowMessage"
      :selected-material="selectedWorkflowMaterial"
      :draft="workflowMessageDraft"
      :can-send="canSendWorkflowMessage"
      :sending="isSendingWorkflowMessage"
      @close="closeWorkflowMessageModal"
      @refresh="refreshPrePublishWorkflowMessages"
      @send="sendWorkflowSupplement"
      @update:selected-message-id="updateWorkflowMessageSelection"
      @update:draft="updateWorkflowMessageDraft"
      @message-list-ref="updateWorkflowMessageListRef"
    />

    <Teleport to="body">
      <CreatorResultModal
        :target="resultModalTarget"
        :title="resultModalTitle"
        :show-developer-tools="showDeveloperTools"
        :feedback-fetch-result="feedbackFetchResult"
        :feedback-dashboard="feedbackDashboard"
        :dashboard-warnings="feedbackDashboardWarnings"
        :feedback-report="feedbackReport"
        :feedback-evidence-index-status="feedbackEvidenceIndexStatus"
        :loading-feedback-evidence-index-status="isLoadingFeedbackEvidenceIndexStatus"
        :rebuilding-feedback-evidence-index="isRebuildingFeedbackEvidenceIndex"
        :feedback-chat-result="feedbackChatResult"
        :feedback-evidence-index-warnings="feedbackEvidenceIndexWarnings"
        :feedback-drawer-open="isFeedbackChatDrawerOpen"
        :feedback-chat-turns="feedbackChatTurns"
        :feedback-chat-question="feedbackChatForm.question"
        :can-ask-feedback-chat="canAskFeedbackChat"
        :asking-feedback-chat="isAskingFeedbackChat"
        @close="closeResultModal"
        @toggle-feedback-drawer="toggleFeedbackChatDrawer"
        @close-feedback-drawer="closeFeedbackChatDrawer"
        @rebuild-evidence-index="rebuildFeedbackEvidenceIndex"
        @update:feedback-question="updateFeedbackChatQuestion"
        @ask-feedback-chat="askFeedbackChat"
      />
    </Teleport>

    <CreatorContextLibraryModal
      :open="isContextLibraryOpen"
      :video-type="currentVideoType"
      :options="contextTermOptions"
      :terms="creatorContextTerms"
      :can-save="canSaveContextTerm"
      :saving="isSavingCreatorContextTerm"
      :loading="isLoadingCreatorContextTerms"
      v-model:term="contextTermForm.term"
      v-model:term-type="contextTermForm.termType"
      v-model:evidence-text="contextTermForm.evidenceText"
      @close="isContextLibraryOpen = false"
      @save="saveManualContextTerm"
      @reset="resetContextTermForm"
      @feedback="feedbackContextTerm"
      @disable="disableContextTerm"
    />

    <GuidanceEditorModal
      :target="guidanceEditorTarget"
      :title="guidanceEditorTitle"
      v-model:pre-publish-guidance="prePublishForm.customGuidance"
      v-model:feedback-guidance="feedbackAnalyzeForm.customGuidance"
      @close="closeGuidanceEditor"
      @reset="resetCurrentGuidance"
      @backdrop-pointer-down="handleGuidanceBackdropPointerDown"
      @backdrop-click="handleGuidanceBackdropClick"
    />
  </section>
</template>
