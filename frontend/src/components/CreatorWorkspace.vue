<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import {
  deleteCreatorTask,
  analyzeCreatorFeedback,
  analyzePrePublishWorkflow,
  chatCreatorFeedback,
  confirmWorkflowPrePublishSuggestion,
  createCreatorTask,
  exportCreatorReportMarkdown,
  getCreatorFeedback,
  getCreatorFeedbackDashboard,
  getCreatorFeedbackEvidenceIndexStatus,
  getCreatorFeedbackReport,
  getCreatorTask,
  getTaskLlmUsageSummary,
  getWorkflowUsage,
  getPrePublishSuggestion,
  fetchCreatorFeedbackByBv,
  generatePrePublishManuscriptDraft,
  importCreatorFeedbackFile,
  disableCreatorContextTerm,
  listCreatorContextTerms,
  listCreatorEvalCases,
  listCreatorEvalPromptVersionStats,
  listCreatorEvalResults,
  listTaskLlmApiCalls,
  listCreatorPreferences,
  listCreatorTasks,
  listWorkflowMessages,
  listWorkflowSteps,
  recordCreatorContextTermFeedback,
  recordCreatorEvalResult,
  rebuildCreatorFeedbackEvidenceIndex,
  saveCreatorContextTerm,
  saveCreatorFeedback,
  sendWorkflowMessage,
  startPrePublishWorkflow,
  updateCreatorTask,
} from '@/api/creator'
import MessageBubble from '@/components/MessageBubble.vue'
import NotificationToast from '@/components/NotificationToast.vue'
import AiCreationConsole from '@/components/creator/AiCreationConsole.vue'
import GuidanceEditorModal from '@/components/creator/GuidanceEditorModal.vue'
import FeedbackTab from '@/components/creator/FeedbackTab.vue'
import MaterialsTab from '@/components/creator/MaterialsTab.vue'
import PrePublishTab from '@/components/creator/PrePublishTab.vue'
import ReportTab from '@/components/creator/ReportTab.vue'
import SuggestionCard from '@/components/creator/SuggestionCard.vue'
import TaskListPanel from '@/components/creator/TaskListPanel.vue'
import UsageTab from '@/components/creator/UsageTab.vue'
import { useCreatorFeedbackEvent } from '@/composables/creator/useCreatorFeedbackEvent'
import { provideCreatorWorkspace } from '@/composables/creator/useCreatorWorkspaceContext'
import {
  clampScriptNumber,
  contextSaveKey,
  contextTermPolarity,
  contextTermSourceLabel,
  contextTermTypeLabel,
  evalResultStatusLabel,
  evalStageLabel,
  extractBvid,
  formatDate,
  formatDuration,
  formatInputCount,
  formatMetric,
  formatPercent,
  formatUsageToken,
  formatValue,
  formatWorkflowCallUsage,
  getRecordText,
  hasFeedbackResult,
  hasPrePublishResult,
  hasText,
  isPrePublishSuggestionVisible,
  isRecord,
  isWorkflowStatus,
  materialLabel,
  normalizeContextTermText,
  parseJsonArray,
  preferenceItemText,
  preferenceModeNoteByMode,
  previewWorkflowMessage,
  retrievalModeLabel,
  shortId,
  statBarWidth,
  statusLabel,
  usageCategoryLabel,
  usageStatusClass,
  usageStatusLabel,
  workflowContentTypeLabel,
  workflowRoleLabel,
  workflowSessionLabel,
  workflowStepStatusLabel,
  workflowStepTypeLabel,
} from '@/composables/creator/creatorWorkspaceUtils'
import type {
  CreatorFeedback,
  CreatorFeedbackChatResult,
  CreatorFeedbackDashboard,
  CreatorFeedbackEvidenceIndexStatus,
  CreatorFeedbackFetchResult,
  CreatorFeedbackReport,
  CreatorEvalCase,
  CreatorEvalPromptVersionStats,
  CreatorEvalResult,
  CreatorContextPolarity,
  CreatorContextTerm,
  CreatorContextTermPayload,
  CreatorContextTermType,
  CreatorEventType,
  CreatorRejectReason,
  CreatorPreference,
  CreatorPreferenceMode,
  CreatorSuggestion,
  CreatorTask,
  CreatorTaskSummary,
  CreatorTaskUpdatePayload,
  CreatorWorkflowMessage,
  CreatorWorkflowSession,
  CreatorWorkflowStatus,
  CreatorWorkflowStep,
  CreatorWorkflowStage,
  LlmApiCallPage,
  LlmApiCallRecord,
  LlmApiModelCategory,
  LlmApiUsageCategorySummary,
  LlmApiUsageSummary,
  ResultModalTarget,
  WorkflowUsageResponse,
} from '@/types/creator'
import type { ChatMessage } from '@/types/agent'
import { useWorkflowSSE } from '@/composables/useWorkflowSSE'
import { useCreatorStore } from '@/stores/creatorStore'
import { useCreatorEvaluation } from '@/composables/creator/useCreatorEvaluation'
import { useCreatorUsage } from '@/composables/creator/useCreatorUsage'
import { useCreatorContext } from '@/composables/creator/useCreatorContext'
import { useCreatorGuidance } from '@/composables/creator/useCreatorGuidance'
import { useCreatorTask } from '@/composables/creator/useCreatorTask'
import { useCreatorWorkflow } from '@/composables/creator/useCreatorWorkflow'
import { useCreatorFeedback } from '@/composables/creator/useCreatorFeedback'
type GuidanceEditorTarget = 'prePublish' | 'feedback'
type TaskManageMode = 'create' | 'edit'
type FeedbackChatTurn = {
  id: string
  question: string
  result: CreatorFeedbackChatResult | null
  status: 'PENDING' | 'DONE' | 'FAILED'
  errorMessage?: string
}
type PreferenceChip = {
  text: string
  sourceTaskId: string
}
type ContextTermOption = {
  value: CreatorContextTermType
  label: string
  polarity: CreatorContextPolarity
}

const props = withDefaults(
  defineProps<{
    developerMode?: boolean
  }>(),
  {
    developerMode: false,
  },
)

// 指导词域迁移至 useCreatorGuidance（表单 + guidance 编辑 + localStorage 持久化）
const guidance = useCreatorGuidance()
const {
  prePublishForm, feedbackAnalyzeForm,
  guidanceEditorTarget, lastPrePublishPreferenceMode, hasPrePublishPreferenceModeSnapshot,
  defaultPrePublishGuidance, defaultFeedbackGuidance,
  loadGuidanceSettings, openGuidanceEditor, closeGuidanceEditor,
  resetCurrentGuidance, resetPrePublishPreferenceMode,
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
const videoTypeOptions = [
  '未分类',
  'GLOBAL',
  '知识科普',
  '游戏实况',
  '游戏攻略',
  '数码测评',
  '影视杂谈',
  '生活记录',
  '鬼畜娱乐',
]
// 短文稿在 P0-1 中可能只是“已确认创意方向”，不能当成完整成片文稿来阻断 AI 补稿。
const fullScriptMinLength = 800
const contextTermOptions: ContextTermOption[] = [
  { value: 'KEYWORD', label: '关键词', polarity: 'POSITIVE' },
  { value: 'SLANG', label: '圈内黑话', polarity: 'POSITIVE' },
  { value: 'MEME', label: '梗表达', polarity: 'POSITIVE' },
  { value: 'TITLE_PATTERN', label: '标题套路', polarity: 'POSITIVE' },
  { value: 'AUDIENCE_CONCERN', label: '观众关注点', polarity: 'POSITIVE' },
  { value: 'TABOO', label: '慎用表达', polarity: 'NEGATIVE' },
]
const evalStageOptions: Array<{
  value: 'ALL' | CreatorWorkflowStage
  label: string
}> = [
  { value: 'ALL', label: '全部样例' },
  { value: 'PRE_PUBLISH', label: '发布前优化' },
  { value: 'FEEDBACK', label: '评论弹幕' },
  { value: 'REPORT', label: '复盘报告' },
]
const evalScoreOptions = [1, 2, 3, 4, 5]
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

const tasks = ref<CreatorTaskSummary[]>([])
const selectedTask = ref<CreatorTask | null>(null)
const taskManageMode = ref<TaskManageMode>('create')
const taskSearchQuery = ref('')
const taskStatusFilter = ref<'ALL' | CreatorTaskSummary['status']>('ALL')
// 任务列表是导航型信息，默认收进弹窗，避免持续挤占创作主流程空间。
const errorMessage = ref('')
const successMessage = ref('')

const isTaskManagerOpen = ref(false)
const isTaskComposerOpen = ref(false)
// 评测域状态迁移至 useCreatorEvaluation
const evaluationModule = useCreatorEvaluation(errorMessage)
const {
  evalCases, selectedEvalCaseId, selectedEvalResultId, evalStageFilter,
  evalResults, evalPromptVersionStats, evalResultDraft,
  isLoadingEvalCases, isLoadingEvalResults, isRecordingEvalResult,
  filteredEvalCases, selectedEvalCase, selectedEvalResult, canRecordEvalResult,
  loadEvaluationCases, refreshEvaluationCases, refreshEvaluationResults,
  selectEvalCase, submitEvalResult,
} = evaluationModule
const isDeveloperTestOpen = ref(false)
const isResultModalBackdropPointerDown = ref(false)
const isGuidanceBackdropPointerDown = ref(false)
const suggestion = ref<CreatorSuggestion | null>(null)
const feedback = ref<CreatorFeedback | null>(null)
const feedbackReport = ref<CreatorFeedbackReport | null>(null)
const feedbackChatResult = ref<CreatorFeedbackChatResult | null>(null)
const feedbackChatTurns = ref<FeedbackChatTurn[]>([])
const feedbackChatThreadRef = ref<HTMLElement | null>(null)
// 阶段 4.13：证据索引状态与重建结果提示。状态在打开反馈报告弹窗时按需加载，避免每次切任务都多发请求。
const feedbackEvidenceIndexStatus = ref<CreatorFeedbackEvidenceIndexStatus | null>(null)
const feedbackEvidenceIndexWarnings = ref<string[]>([])
const feedbackDashboard = ref<CreatorFeedbackDashboard | null>(null)
const feedbackFetchResult = ref<CreatorFeedbackFetchResult | null>(null)
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
// feedbackImportFile → see Adapter Layer
const feedbackImportWarnings = ref<string[]>([])
// 开销统计域迁移至 useCreatorUsage
const usageModule = useCreatorUsage(() => selectedTask.value?.taskId ?? '', errorMessage)
const {
  usageSummary, usageCallPage, usageCategoryFilter, usageCurrentPage,
  isLoadingUsageStats,
  usageCategorySummaries, usageTotalPages,
  refreshUsageStats, changeUsageCategoryFilter, changeUsagePage,
} = usageModule
const workflowSession = ref<CreatorWorkflowSession | null>(null)
const workflowMessages = ref<CreatorWorkflowMessage[]>([])
const workflowSteps = ref<CreatorWorkflowStep[]>([])
const workflowUsage = ref<WorkflowUsageResponse | null>(null)
const workflowMessageDraft = ref('')
const workflowMessageListRef = ref<HTMLDivElement | null>(null)
// useWorkflowSSE 统一管理 EventSource 生命周期、连接状态、心跳检测和版本校验，
// 组件层只需提供 handlers 处理业务数据，不再直接操作 EventSource 实例。
const { connect: connectWorkflowEvents, disconnect: closeWorkflowEventSource, statusText: workflowSseText } = useWorkflowSSE()
const workflowMessageModalOpen = ref(false)
const workflowProcessModalOpen = ref(false)
const expandedRawStepIds = ref<Set<string>>(new Set())
const workflowUsageError = ref('')
const selectedWorkflowMessageId = ref('')
// activeStep / restoredTaskId 从 Pinia creatorStore 读取，替代原来的 localStorage + persistWorkspaceState 模式
const creatorStore = useCreatorStore()
const { activeStep, restoredTaskId } = storeToRefs(creatorStore)
const creatorStepMetas = [
  { key: 'task', label: '视频资料', shortLabel: '资料', description: '填写视频基础信息' },
  { key: 'prePublish', label: '发布方案', shortLabel: '发布', description: '生成发布计划与方案' },
  { key: 'feedback', label: '观众反馈', shortLabel: '反馈', description: '收集观众意见与反馈' },
  { key: 'report', label: '复盘报告', shortLabel: '复盘', description: '生成复盘总结报告' },
]
const activeCreatorStepIndex = computed(() => {
  const matchedIndex = creatorStepMetas.findIndex((step) => step.key === activeStep.value)
  // 开销统计属于开发者辅助入口，不参与普通创作者四步流程；移动端进度条保持在复盘阶段，避免第五步挤压窄屏。
  if (activeStep.value === 'usage') {
    return creatorStepMetas.length - 1
  }
  return matchedIndex >= 0 ? matchedIndex : 0
})
// 使用 ?? 兜底第一个步骤元信息，满足 TypeScript 对数组索引可能返回 undefined 的严格检查
// creatorStepMetas 是常量数组，始终有 4 个元素，所以 [0] 安全非空
const activeCreatorStepMeta = computed(() => creatorStepMetas[activeCreatorStepIndex.value] ?? creatorStepMetas[0]!)
const creatorProgressPercent = computed(() => `${((activeCreatorStepIndex.value + 1) / creatorStepMetas.length) * 100}%`)
// 当前任务详情默认折叠，让发布前优化和复盘区域成为页面第一视觉重点。
const isCurrentTaskExpanded = ref(false)
const isLoadingTasks = ref(false)
const isCreatingTask = ref(false)
const isUpdatingTask = ref(false)
const isDeletingTask = ref(false)
const isAnalyzingPrePublish = ref(false)
const isGeneratingPrePublishDraft = ref(false)
const isConfirmingPrePublish = ref(false)
const isLoadingWorkflow = ref(false)
const isSendingWorkflowMessage = ref(false)
const isSavingFeedback = ref(false)
const isImportingFeedback = ref(false)
const isFetchingFeedback = ref(false)
const isAnalyzingFeedback = ref(false)
const isAskingFeedbackChat = ref(false)
const isRebuildingFeedbackEvidenceIndex = ref(false)
const isLoadingFeedbackEvidenceIndexStatus = ref(false)
const isExportingReportMarkdown = ref(false)
const isContextLibraryOpen = ref(false)
const resultModalTarget = ref<ResultModalTarget | null>(null)
const isFeedbackChatDrawerOpen = ref(false)
const isDeveloperTestBackdropPointerDown = ref(false)


// ═══════════════════════════════════════════
// Phase 5.8 Adapter Layer — 桥接模式
// composable 持有真实状态，旧 ref/函数逐步改为转发到 module
// ═══════════════════════════════════════════

const taskModule = useCreatorTask(errorMessage)
const workflowModule = useCreatorWorkflow(
  taskModule.selectedTaskId,
  taskModule.hasSelectedTaskMaterials,
  errorMessage,
)
const feedbackModule = useCreatorFeedback(
  taskModule.selectedTaskId,
  workflowModule.hasConfirmedPrePublish,
  errorMessage,
  successMessage,
)

// ═══════════════════════════════════════════

// taskForm 统一来源：模块持有真实 reactive，组件通过此引用读写
const taskForm = taskModule.taskForm
const pendingDeleteTask = taskModule.pendingDeleteTask
const pendingDeleteTaskName = taskModule.pendingDeleteTaskName
// feedback 表单统一来源：feedbackModule 持有真实 reactive
const feedbackForm = feedbackModule.feedbackForm
const feedbackChatForm = feedbackModule.feedbackChatForm
const feedbackScriptForm = feedbackModule.feedbackScriptForm
const feedbackImportFile = feedbackModule.feedbackImportFile

// 建议卡片反馈事件上报：复用已有的 successMessage/errorMessage toast 通道，
// 点击"采纳/不太好"后由这里统一写 creator_event 表，触发画像增量更新
const feedbackEvent = useCreatorFeedbackEvent(successMessage, errorMessage)

const selectedTaskId = computed(() => selectedTask.value?.taskId ?? '')
const hasSelectedTask = computed(() => selectedTaskId.value.length > 0)
const hasTaskHistory = computed(() => tasks.value.length > 0)
const hasSelectedTaskMaterials = computed(() => (selectedTask.value?.materials.length ?? 0) > 0)
const showDeveloperTools = computed(() => props.developerMode)
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
    const searchableText = [task.taskName, task.videoType, task.taskId, statusLabel(task.status)]
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
    return isUpdatingTask.value ? '保存中...' : '保存视频资料'
  }
  return isCreatingTask.value ? '保存中...' : '保存视频资料'
})
const taskFormTitle = computed(() =>
  taskManageMode.value === 'edit' ? '编辑视频资料' : '填写视频资料',
)
const taskFormHint = computed(() =>
  taskManageMode.value === 'edit'
    ? '编辑当前任务后，旧材料会被覆盖，后续分析请重新生成。'
    : '先放入这期视频已有的标题、简介和文稿，后续会基于这些资料生成发布方案。',
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
const canGeneratePrePublishDraft = computed(() => {
  const status = workflowSession.value?.status
  return Boolean(
    hasSelectedTask.value &&
      hasSelectedTaskMaterials.value &&
      workflowSession.value &&
      !hasFullScriptMaterial.value &&
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
const hasFeedbackChatTurns = computed(() => feedbackChatTurns.value.length > 0)
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
const actionableRevisionPlan = computed(() =>
  parseJsonArray(suggestion.value?.actionableRevisionPlan),
)
const tagSuggestions = computed(() => parseJsonArray(suggestion.value?.tagSuggestions))

// ── 建议卡片：把后端返回的 JSON 数组解析成 SuggestionCard 需要的结构 ──
// 卡片组件只接收已解析的 item，不重复实现 parseJsonArray/getRecordText 逻辑，
// 这样解析逻辑单一来源，避免卡片和列表两套解析互相漂移。
type SuggestionCardItem = {
  content: string
  viewerPsychology?: string
  clickReason?: string
  trustRisk?: string
  bestScenario?: string
  reason?: string
  risk?: string
}

const titleSuggestionCards = computed<SuggestionCardItem[]>(() =>
  titleSuggestions.value.map((raw) => ({
    content: getRecordText(raw, 'title') || formatValue(raw),
    viewerPsychology: getRecordText(raw, 'viewerPsychology') || undefined,
    clickReason: getRecordText(raw, 'clickReason') || undefined,
    trustRisk: getRecordText(raw, 'trustRisk') || undefined,
    bestScenario: getRecordText(raw, 'bestScenario') || undefined,
    reason: getRecordText(raw, 'reason') || undefined,
    risk: getRecordText(raw, 'risk') || undefined,
  })),
)

// 标签建议同样转成卡片结构，只是没有那么多元信息字段
const tagSuggestionCards = computed<SuggestionCardItem[]>(() =>
  tagSuggestions.value.map((raw) => ({ content: formatValue(raw) })),
)

// 已采纳的标题集合：用 content 去重，采纳后卡片视觉态切换，避免重复上报
const acceptedTitleContents = ref<Set<string>>(new Set())
const acceptedTagContents = ref<Set<string>>(new Set())

/** 卡片"采纳"：上报 ACCEPTED 事件并标记为已采纳 */
async function onAcceptSuggestion(
  type: 'title' | 'tag',
  item: SuggestionCardItem,
  rank?: number,
) {
  const eventType: CreatorEventType =
    type === 'title' ? 'TITLE_ACCEPTED' : 'TAG_ACCEPTED'
  const ok = await feedbackEvent.reportAccept(
    eventType,
    selectedTaskId.value,
    item.content,
    rank,
  )
  if (ok) {
    const store = type === 'title' ? acceptedTitleContents : acceptedTagContents
    store.value.add(item.content)
  }
}

/** 卡片"复制"：写入剪贴板，复制成功由卡片自身给"已复制"提示，这里只负责副作用 */
async function onCopySuggestion(item: SuggestionCardItem) {
  try {
    await navigator.clipboard.writeText(item.content)
  } catch {
    // 剪贴板可能被浏览器策略拦截（非 HTTPS / 无焦点），失败不阻塞，静默降级
  }
}

/** 卡片"不太好"：上报 REJECTED 事件，带上原因 */
function onRejectSuggestion(
  type: 'title' | 'tag',
  item: SuggestionCardItem,
  reason: CreatorRejectReason,
  reasonText: string,
  rank?: number,
) {
  const eventType: CreatorEventType =
    type === 'title' ? 'TITLE_REJECTED' : 'TAG_REJECTED'
  void feedbackEvent.reportReject(
    eventType,
    selectedTaskId.value,
    item.content,
    reason,
    reasonText,
    rank,
  )
}
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
const misunderstandingSourceAnalysis = computed(() =>
  parseJsonArray(feedbackReport.value?.misunderstandingSourceAnalysis),
)
const feedbackActionPlan = computed(() =>
  parseJsonArray(feedbackReport.value?.feedbackActionPlan),
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
const currentVideoType = computed(() => {
  if (selectedTask.value?.videoType) {
    return selectedTask.value.videoType
  }
  return taskForm.videoType || '未分类'
})
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
const workflowStepStats = computed(() => {
  const stats = {
    total: workflowSteps.value.length,
    success: 0,
    failed: 0,
    running: 0,
  }
  for (const step of workflowSteps.value) {
    if (step.status === 'SUCCESS') {
      stats.success += 1
    } else if (step.status === 'FAILED') {
      stats.failed += 1
    } else if (step.status === 'RUNNING') {
      stats.running += 1
    }
  }
  return stats
})
const workflowFailedStep = computed(() => workflowSteps.value.find((step) => step.status === 'FAILED') ?? null)
const workflowRunningStep = computed(() => workflowSteps.value.find((step) => step.status === 'RUNNING') ?? null)
const workflowUnmatchedUsageSteps = computed(() => {
  const stepIds = new Set(workflowSteps.value.map((step) => step.stepId))
  return (workflowUsage.value?.steps ?? []).filter((step) => !stepIds.has(step.stepId))
})
const workflowProcessSummary = computed(() => {
  if (!workflowSession.value) {
    return '未创建工作流会话'
  }
  if (workflowSession.value.status === 'RUNNING') {
    return `${workflowRunningStep.value?.stepName ?? '正在执行'} · 已完成 ${workflowStepStats.value.success}/${Math.max(workflowStepStats.value.total, 1)}`
  }
  if (workflowSession.value.status === 'FAILED') {
    return `执行失败 · 失败步骤：${workflowFailedStep.value?.stepName ?? '未知步骤'}`
  }
  const usage = workflowUsage.value
  const usagePart = usage
    ? `API 调用 ${usage.totalCalls} 次 · ${formatUsageToken(usage.totalTokens)} token · ${formatDuration(usage.totalElapsedMs)}`
    : workflowUsageError.value
      ? '开销统计暂不可用'
      : '开销统计待加载'
  return `${workflowStepStats.value.total} 步完成 · ${workflowStepStats.value.failed} 步失败 · ${usagePart}`
})
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
  if (resultModalTarget.value === 'prePublishSuggestion') {
    return '发布方案'
  }
  if (resultModalTarget.value === 'feedbackDashboard') {
    return '观众反馈导入结果'
  }
  if (resultModalTarget.value === 'feedbackReport') {
    return '复盘报告'
  }
  return ''
})
onMounted(() => {
  loadGuidanceSettings()
  loadWorkspaceState()
  void refreshTasks()
  window.addEventListener('keydown', handleWorkspaceKeydown)
})

onBeforeUnmount(() => {
  closeWorkflowEventSource()
  window.removeEventListener('keydown', handleWorkspaceKeydown)
})

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
    workflowProcessModalOpen.value = false
  },
)

watch(
  () => workflowMessages.value.length,
  () => {
    void scrollWorkflowMessagesToBottom()
  },
)

// ═══════════════════════════════════════════
// Phase 5.8 Bridge Sync — 将 taskModule 状态单向同步到旧 ref，模板继续读旧变量
// ═══════════════════════════════════════════
watch(() => taskModule.tasks.value, (val) => { tasks.value = val }, { immediate: true })
watch(() => taskModule.selectedTask.value, (val) => { selectedTask.value = val }, { immediate: true })
watch(() => taskModule.isLoadingTasks.value, (val) => { isLoadingTasks.value = val })
watch(() => taskModule.isCreatingTask.value, (val) => { isCreatingTask.value = val })
watch(() => taskModule.isUpdatingTask.value, (val) => { isUpdatingTask.value = val })
watch(() => taskModule.isDeletingTask.value, (val) => { isDeletingTask.value = val })

function openTaskManager() {
  pendingDeleteTask.value = null
  isTaskManagerOpen.value = true
}

function closeTaskManager() {
  isTaskManagerOpen.value = false
}

function toggleCurrentTaskExpanded() {
  isCurrentTaskExpanded.value = !isCurrentTaskExpanded.value
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
  if (workflowProcessModalOpen.value) {
    closeWorkflowProcessModal()
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

async function scrollFeedbackChatToBottom() {
  await nextTick()
  const thread = feedbackChatThreadRef.value
  if (!thread) {
    return
  }
  thread.scrollTop = thread.scrollHeight
}

function clearFeedbackChatState(clearQuestion = true) {
  feedbackChatResult.value = null
  feedbackChatTurns.value = []
  if (clearQuestion) {
    feedbackChatForm.question = ''
  }
}

function feedbackQuestionMessage(turn: FeedbackChatTurn, index: number): ChatMessage {
  return {
    id: index * 2 + 1,
    role: 'user',
    content: turn.question,
  }
}

function feedbackAnswerMessage(turn: FeedbackChatTurn, index: number): ChatMessage {
  return {
    id: index * 2 + 2,
    role: 'assistant',
    content: feedbackAnswerContent(turn),
  }
}

function feedbackAnswerContent(turn: FeedbackChatTurn) {
  if (turn.result) {
    return turn.result.answer
  }
  if (turn.status === 'FAILED') {
    return `追问失败：${turn.errorMessage || '请稍后重试。'}`
  }
  return '正在基于当前反馈报告和评论弹幕证据生成回答...'
}

function updateFeedbackChatTurn(turnId: string, patch: Partial<FeedbackChatTurn>) {
  feedbackChatTurns.value = feedbackChatTurns.value.map((turn) =>
    turn.id === turnId ? { ...turn, ...patch } : turn,
  )
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

function openUsageStats() {
  if (!showDeveloperTools.value) {
    return
  }
  activeStep.value = 'usage'
  void refreshUsageStats(1)
}

function resetTaskForm() { taskModule.resetTaskForm() }
function fillTaskForm(task: CreatorTask) { taskModule.fillTaskForm(task) }
function getMaterialContent(task: CreatorTask, materialType: string) { return taskModule.getMaterialContent(task, materialType) }
function hasTaskMaterialChanged(task: CreatorTask) { return taskModule.hasTaskMaterialChanged(task) }

function resetGeneratedTaskResults() {
  closeWorkflowEventSource()
  suggestion.value = null
  feedback.value = null
  feedbackReport.value = null
  clearFeedbackChatState()
  feedbackEvidenceIndexStatus.value = null
  feedbackEvidenceIndexWarnings.value = []
  isFeedbackChatDrawerOpen.value = false
  feedbackDashboard.value = null
  feedbackFetchResult.value = null
  feedbackImportFile.value = null
  feedbackImportWarnings.value = []
  workflowSession.value = null
  workflowMessages.value = []
  workflowSteps.value = []
  workflowUsage.value = null
  workflowUsageError.value = ''
  workflowMessageModalOpen.value = false
  expandedRawStepIds.value = new Set()
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
  // 委托 taskModule 执行创建（内部已处理 loading / selectedTask / store）
  const task = await taskModule.submitTask()
  if (!task) return
  // 编排层：跨域操作 + UI 状态
  resetPrePublishPreferenceMode()
  resetGeneratedTaskResults()
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
  const materialChanged = hasTaskMaterialChanged(selectedTask.value)
  // 委托 taskModule 执行更新（内部已处理 loading / store / selectedTask）
  const task = await taskModule.submitUpdateTask()
  if (!task) return
  // 编排层：跨域操作
  restoredTaskId.value = task.taskId
  await Promise.all([
    loadCreatorPreferences(task.userId),
    loadCreatorContextTerms(task.userId, task.videoType),
  ])
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
}

function startCreateTask() {
  taskModule.startCreateTask()
  isTaskComposerOpen.value = true
  pendingDeleteTask.value = null
  errorMessage.value = ''
  successMessage.value = ''
  activeStep.value = 'task'
  closeTaskManager()
}

async function startEditTask(taskId: string) {
  errorMessage.value = ''
  successMessage.value = ''
  const alreadyLoaded = selectedTask.value?.taskId === taskId
  if (alreadyLoaded) {
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
  taskManageMode.value = 'edit'
  isTaskComposerOpen.value = true
  taskModule.fillTaskForm(selectedTask.value!)
  activeStep.value = 'task'
  pendingDeleteTask.value = null
  closeTaskManager()
}

function cancelEditTask() { taskModule.cancelEditTask() }

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
  isTaskManagerOpen.value = true
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
  errorMessage.value = ''
  successMessage.value = ''
  resultModalTarget.value = null
  pendingDeleteTask.value = null
  taskManageMode.value = 'create'
  taskModule.resetTaskForm()
  // 委托 taskModule 加载完整任务（内部已处理 selectedTask / store）
  const task = await taskModule.loadTask(taskId)
  if (!task) return
  // 编排层：跨域操作
  isTaskComposerOpen.value = true
  activeStep.value = 'prePublish'
  resetPrePublishPreferenceMode()
  restoredTaskId.value = task.taskId
  await Promise.all([
    loadCreatorPreferences(task.userId),
    loadCreatorContextTerms(task.userId, task.videoType),
  ])
  await loadOptionalResults(task)
  await loadPrePublishWorkflow(taskId)
  await refreshUsageStats(1)
  closeTaskManager()
}

async function handleCreativeOptionConfirmed(taskId: string) {
  await selectTask(taskId)
  activeStep.value = 'prePublish'
  successMessage.value = '创意方向已确认，下一步可以继续做发布前优化。'
}

async function loadOptionalResults(task: CreatorTask) {
  suggestion.value = null
  feedback.value = null
  feedbackReport.value = null
  clearFeedbackChatState()
  feedbackEvidenceIndexStatus.value = null
  feedbackEvidenceIndexWarnings.value = []
  feedbackDashboard.value = null
  feedbackFetchResult.value = null
  feedbackImportFile.value = null
  feedbackImportWarnings.value = []
  usageSummary.value = null
  usageCallPage.value = null
  usageCurrentPage.value = 1
  usageCategoryFilter.value = 'ALL'

  if (hasPrePublishResult(task.status)) {
    suggestion.value = await optionalRequest(() => getPrePublishSuggestion(task.taskId))
    feedback.value = await optionalRequest(() => getCreatorFeedback(task.taskId))
    feedbackDashboard.value = await optionalRequest(() => getCreatorFeedbackDashboard(task.taskId))
  }

  if (hasFeedbackResult(task.status)) {
    feedbackReport.value = await optionalRequest(() => getCreatorFeedbackReport(task.taskId))
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
  isLoadingWorkflow.value = true
  closeWorkflowEventSource()
  workflowSession.value = null
  workflowMessages.value = []
  workflowSteps.value = []
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
    workflowSteps.value =
      (await optionalRequest(() => listWorkflowSteps(taskId, workflowSession.value!.sessionId))) ?? []
    await loadWorkflowUsage(false)
    if (!suggestion.value && isPrePublishSuggestionVisible(workflowSession.value.status)) {
      suggestion.value = await optionalRequest(() => getPrePublishSuggestion(taskId))
    }
    syncWorkflowSelection()
    connectWorkflowEvents(taskId, workflowSession.value.sessionId, {
      onMessageCreated: (message) => {
        upsertWorkflowMessage(message)
        syncWorkflowSelection()
      },
      onSessionStatus: (status, confirmedResultId, errorMessage) => {
        if (workflowSession.value) {
          workflowSession.value = {
            ...workflowSession.value,
            status: (isWorkflowStatus(status ?? null) ? status : workflowSession.value.status) as CreatorWorkflowStatus,
            confirmedResultId:
              (confirmedResultId ?? workflowSession.value.confirmedResultId) ?? null,
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
      onStepStarted: () => { void refreshPrePublishWorkflowSteps() },
      onStepCompleted: () => { void refreshPrePublishWorkflowSteps() },
      onStepFailed: () => { void refreshPrePublishWorkflowSteps() },
    })
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
    workflowSteps.value =
      (await optionalRequest(() =>
        listWorkflowSteps(selectedTaskId.value, workflowSession.value!.sessionId),
      )) ?? []
    await loadWorkflowUsage(false)
    syncWorkflowSelection()
  } catch (error) {
    showError(error)
  } finally {
    isLoadingWorkflow.value = false
  }
}

async function refreshPrePublishWorkflowSteps() {
  if (!selectedTaskId.value || !workflowSession.value) {
    return
  }
  try {
    workflowSteps.value =
      (await optionalRequest(() =>
        listWorkflowSteps(selectedTaskId.value, workflowSession.value!.sessionId),
      )) ?? []
    await loadWorkflowUsage(false)
  } catch (error) {
    showError(error)
  }
}

async function loadWorkflowUsage(reportError = false) {
  if (!selectedTaskId.value || !workflowSession.value) {
    workflowUsage.value = null
    workflowUsageError.value = ''
    return
  }
  try {
    workflowUsage.value = await getWorkflowUsage(selectedTaskId.value, workflowSession.value.sessionId)
    workflowUsageError.value = ''
  } catch (error) {
    workflowUsage.value = null
    workflowUsageError.value = error instanceof Error ? error.message : '开销统计暂不可用'
    if (reportError) {
      showError(error)
    }
  }
}

async function generatePrePublishManuscriptDraftForCurrentTask(extraRequirement = '') {
  if (!selectedTaskId.value) {
    return
  }
  if (hasFullScriptMaterial.value) {
    errorMessage.value = '当前任务已有较完整文稿或字幕，可以直接生成发布方案。'
    return
  }
  if (!workflowSession.value) {
    await loadPrePublishWorkflow(selectedTaskId.value)
  }
  if (!workflowSession.value || !canGeneratePrePublishDraft.value) {
    return
  }

  isGeneratingPrePublishDraft.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const result = await generatePrePublishManuscriptDraft(
      selectedTaskId.value,
      workflowSession.value.sessionId,
      { extraRequirement },
    )
    if (result.message) {
      upsertWorkflowMessage(result.message)
    }
    selectedTask.value = await getCreatorTask(selectedTaskId.value)
    await refreshPrePublishWorkflowMessages()
    await refreshUsageStats(1, false)
    await scrollWorkflowMessagesToBottom()
    successMessage.value = 'AI 已补全文稿草稿，可以继续补充修改要求或生成发布方案。'
  } catch (error) {
    showError(error)
  } finally {
    isGeneratingPrePublishDraft.value = false
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
    await refreshUsageStats(1, false)
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
    await scrollWorkflowMessagesToBottom()
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
    clearFeedbackChatState()
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
    clearFeedbackChatState()
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
    clearFeedbackChatState()
    feedbackImportWarnings.value = result.warnings ?? []
    // 后端已经完成脚本执行和入库，前端只刷新权威状态，避免页面表单成为第二份数据源。
    feedback.value = await optionalRequest(() => getCreatorFeedback(selectedTaskId.value))
    feedbackDashboard.value = await optionalRequest(() => getCreatorFeedbackDashboard(selectedTaskId.value))
    successMessage.value = showDeveloperTools.value
      ? `已读取 ${result.commentCount} 条评论、${result.danmakuCount} 条弹幕，文件已保存到 ${result.outputDirectory}。`
      : `已读取 ${result.commentCount} 条评论、${result.danmakuCount} 条弹幕。`
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
    clearFeedbackChatState()
    selectedTask.value = await getCreatorTask(selectedTaskId.value)
    activeStep.value = 'report'
    successMessage.value = '评论弹幕分析完成，反馈报告已保存。'
    openResultModal('feedbackReport')
    await refreshTasks()
  } catch (error) {
    showError(error)
  } finally {
    await refreshUsageStats(1, false)
    isAnalyzingFeedback.value = false
  }
}

async function askFeedbackChat() {
  if (!selectedTaskId.value || !canAskFeedbackChat.value) {
    return
  }
  const question = feedbackChatForm.question.trim()
  const turnId = `feedback-chat-${Date.now()}-${feedbackChatTurns.value.length}`
  feedbackChatTurns.value = [
    ...feedbackChatTurns.value,
    {
      id: turnId,
      question,
      result: null,
      status: 'PENDING',
    },
  ]
  feedbackChatForm.question = ''
  await scrollFeedbackChatToBottom()
  isAskingFeedbackChat.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const result = await chatCreatorFeedback(selectedTaskId.value, {
      question,
    })
    feedbackChatResult.value = result
    updateFeedbackChatTurn(turnId, {
      result,
      status: 'DONE',
    })
    successMessage.value = '反馈追问已生成，回答基于当前任务报告和评论弹幕证据。'
  } catch (error) {
    updateFeedbackChatTurn(turnId, {
      status: 'FAILED',
      errorMessage: error instanceof Error ? error.message : String(error),
    })
    showError(error)
  } finally {
    await scrollFeedbackChatToBottom()
    await refreshUsageStats(1, false)
    isAskingFeedbackChat.value = false
  }
}

// 委托 feedbackModule：API 调用 + blob 下载 + 状态管理已在模块内闭环
async function downloadReportMarkdown() {
  if (!selectedTaskId.value || isExportingReportMarkdown.value) return
  await feedbackModule.downloadReportMarkdown(selectedTaskId.value)
}

// 委托 feedbackModule 加载证据索引状态
async function loadFeedbackEvidenceIndexStatus() {
  if (!selectedTaskId.value || isLoadingFeedbackEvidenceIndexStatus.value) return
  await feedbackModule.loadEvidenceIndexStatus(selectedTaskId.value)
}

// 委托 feedbackModule 重建证据索引，补充组件级 UI 清理
async function rebuildFeedbackEvidenceIndex() {
  if (!selectedTaskId.value || isRebuildingFeedbackEvidenceIndex.value) return
  await feedbackModule.rebuildEvidenceIndex(selectedTaskId.value)
  clearFeedbackChatState(false)
  await refreshUsageStats(1, false)
}

// 把后端检索模式编码翻译成用户能看懂的中文，统一用于状态区和追问回答脚注。
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
  closeWorkflowEventSource()
  selectedTask.value = null
  creatorStore.selectedTaskId = null
  taskManageMode.value = 'create'
  resetTaskForm()
  suggestion.value = null
  feedback.value = null
  feedbackReport.value = null
  clearFeedbackChatState()
  feedbackEvidenceIndexStatus.value = null
  feedbackEvidenceIndexWarnings.value = []
  isFeedbackChatDrawerOpen.value = false
  feedbackDashboard.value = null
  feedbackFetchResult.value = null
  creatorPreferences.value = []
  creatorContextTerms.value = []
  feedbackImportFile.value = null
  feedbackImportWarnings.value = []
  usageSummary.value = null
  usageCallPage.value = null
  usageCurrentPage.value = 1
  usageCategoryFilter.value = 'ALL'
  isFetchingFeedback.value = false
  resetPrePublishPreferenceMode()
  resultModalTarget.value = null
  workflowMessageModalOpen.value = false
  workflowProcessModalOpen.value = false
  workflowSession.value = null
  workflowMessages.value = []
  workflowSteps.value = []
  workflowUsage.value = null
  workflowUsageError.value = ''
  expandedRawStepIds.value = new Set()
  selectedWorkflowMessageId.value = ''
  isTaskComposerOpen.value = false
  activeStep.value = 'task'
  closeDeveloperTest()
}

// 从 Pinia creatorStore 恢复上次选中的任务 ID，替代原来的 localStorage 直接读取。
// Pinia persist 插件在 store 初始化时已自动从 localStorage 反序列化 selectedTaskId。
function loadWorkspaceState() {
  if (creatorStore.selectedTaskId) {
    restoredTaskId.value = creatorStore.selectedTaskId
  }
}

function trimToNull(value: string | undefined) {
  const trimmed = value?.trim()
  return trimmed ? trimmed : undefined
}

function normalizeOptionalNumber(value: unknown) {
  if (value === null || value === undefined || value === '') {
    return undefined
  }
  if (typeof value === 'string' && value.trim().length === 0) {
    return undefined
  }
  const numericValue = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(numericValue) ? numericValue : undefined
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
  await refreshUsageStats(1, false)
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
  // 证据索引是工程诊断信息，只有开发者模式才提前读取，避免普通用户看到 RAG/Milvus 等概念。
  if (target === 'feedbackReport' && showDeveloperTools.value) {
    feedbackEvidenceIndexWarnings.value = []
    void loadFeedbackEvidenceIndexStatus()
  }
}

function closeResultModal() {
  resultModalTarget.value = null
  isResultModalBackdropPointerDown.value = false
  isFeedbackChatDrawerOpen.value = false
}

function openWorkflowMessageModal() {
  if (!hasSelectedTask.value) {
    return
  }
  workflowMessageModalOpen.value = true
  syncWorkflowSelection()
  void scrollWorkflowMessagesToBottom()
}

function closeWorkflowMessageModal() {
  workflowMessageModalOpen.value = false
}

function openWorkflowProcessModal() {
  workflowProcessModalOpen.value = true
  void loadWorkflowUsage(false)
}

function closeWorkflowProcessModal() {
  workflowProcessModalOpen.value = false
}

function toggleRawOutput(stepId: string) {
  const next = new Set(expandedRawStepIds.value)
  if (next.has(stepId)) {
    next.delete(stepId)
  } else {
    next.add(stepId)
  }
  expandedRawStepIds.value = next
}

function isRawOutputExpanded(stepId: string) {
  return expandedRawStepIds.value.has(stepId)
}

function workflowCallsForStep(stepId: string) {
  return workflowUsage.value?.steps.find((step) => step.stepId === stepId)?.calls ?? []
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
  isDeveloperTestBackdropPointerDown.value = false
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

function handleDeveloperTestBackdropPointerDown(event: PointerEvent) {
  isDeveloperTestBackdropPointerDown.value = event.target === event.currentTarget
}

function handleDeveloperTestBackdropClick(event: MouseEvent) {
  if (isDeveloperTestBackdropPointerDown.value && event.target === event.currentTarget) {
    closeDeveloperTest()
    return
  }
  isDeveloperTestBackdropPointerDown.value = false
}





function showError(error: unknown) {
  errorMessage.value = error instanceof Error ? error.message : '请求失败'
}

// 子组件只负责承载下沉后的模板，核心编排仍由主壳统一持有，
// 这样任务恢复、SSE 连接和跨 tab 结果弹窗不会因为拆分产生第二份状态。
provideCreatorWorkspace({
  taskModule,
  workflowModule,
  feedbackModule,
  guidance,
  contextModule,
  usageModule,
  evaluationModule,
  feedbackEvent,
  showDeveloperTools,
  successMessage,
  errorMessage,
  openResultModal,
  closeResultModal,
  openWorkflowMessageModal,
  openWorkflowProcessModal,
  openFeedbackChatDrawer,
  closeFeedbackChatDrawer,
  toggleFeedbackChatDrawer,
  openDeveloperTest,
  openUsageStats,
  openTaskManager,
  shell: {
    askDeleteSelectedTask,
    cancelEditTask,
    canEnterFeedback,
    canConfirmPrePublish,
    canGeneratePrePublishDraft,
    canRunFeedbackAnalyze,
    canRunPrePublishAnalyze,
    canSendWorkflowMessage,
    changeUsageCategoryFilter,
    changeUsagePage,
    confirmPrePublishResult,
    contextTermChips,
    currentVideoType,
    downloadReportMarkdown,
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
    hasFullScriptMaterial,
    hasFeedbackSampleInput,
    hasSelectedTask,
    hasTaskMaterialInput,
    historicalPreferenceChips,
    generatePrePublishManuscriptDraftForCurrentTask,
    importFeedbackFile,
    isAnalyzingFeedback,
    isAnalyzingPrePublish,
    isContextLibraryOpen,
    openContextLibrary,
    isCreatingTask,
    isCurrentTaskExpanded,
    isExportingReportMarkdown,
    isFetchingFeedback,
    isGeneratingPrePublishDraft,
    isImportingFeedback,
    isLoadingCreatorContextTerms,
    isLoadingCreatorPreferences,
    isLoadingUsageStats,
    isConfirmingPrePublish,
    isSendingWorkflowMessage,
    isSavingFeedback,
    isUpdatingTask,
    materialPreview,
    openGuidanceEditor,
    openResultModal,
    openTaskManager,
    openWorkflowMessageModal,
    openWorkflowProcessModal,
    preferenceModeNote,
    preferenceModeOptions,
    prePublishForm,
    refreshUsageStats,
    runFeedbackAnalyze,
    runPrePublishAnalyze,
    selectedPreferenceModeLabel,
    selectedTask,
    selectedTaskId,
    shortId,
    showDeveloperTools,
    startEditTask,
    statusLabel,
    submitFeedback,
    submitTask,
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
    workflowFailedStep,
    workflowMessageDraft,
    workflowMessages,
    workflowProcessSummary,
    workflowRunningStep,
    workflowSession,
    workflowSseText,
    workflowStatusText,
    workflowSteps,
    sendWorkflowSupplement,
    handleFeedbackFileChange,
    toggleCurrentTaskExpanded,
    suggestion,
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
          <span :class="{ active: Boolean(feedback || feedbackDashboard) }">观众反馈</span>
          <span :class="{ active: Boolean(feedbackReport) }">复盘报告</span>
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

    <Teleport to="body">
      <Transition name="creator-modal">
        <div
          v-if="isTaskManagerOpen"
          class="creator-modal-backdrop creator-task-manager-backdrop"
          @click.self="closeTaskManager"
        >
          <section
            class="creator-task-manager-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="creator-task-manager-title"
          >
            <header class="creator-result-modal-head creator-task-manager-head">
              <div>
                <span>项目列表</span>
                <h3 id="creator-task-manager-title">
                  历史视频项目
                </h3>
                <p>{{ filteredTasks.length }} 个匹配项目，共 {{ taskSummaryStats.total }} 个项目</p>
              </div>
              <div class="creator-panel-actions">
                <button type="button" class="creator-ghost-button" @click="startCreateTask">
                  新建
                </button>
                <button type="button" class="creator-ghost-button" @click="refreshTasks">
                  {{ isLoadingTasks ? '读取中' : '刷新' }}
                </button>
                <button type="button" class="creator-ghost-button" @click="closeTaskManager">
                  关闭
                </button>
              </div>
            </header>

            <div class="creator-task-manager-body">
              <div class="creator-task-toolbar">
                <label class="creator-task-search">
                  <span>搜索</span>
                <input v-model="taskSearchQuery" type="search" placeholder="名称 / 项目编号 / 状态" />
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

              <div class="creator-task-overview" aria-label="项目概览">
                <span><b>{{ taskSummaryStats.draft }}</b> 草稿</span>
                <span><b>{{ taskSummaryStats.inProgress }}</b> 推进中</span>
                <span><b>{{ taskSummaryStats.done }}</b> 已复盘</span>
              </div>

              <div class="creator-task-list creator-task-manager-list">
                <article
                  v-for="task in filteredTasks"
                  :key="task.taskId"
                  class="creator-task-item"
                  :class="{ active: task.taskId === selectedTaskId }"
                >
                  <button type="button" class="creator-task-select" @click="selectTask(task.taskId)">
                    <strong>{{ task.taskName }}</strong>
                    <span>{{ task.videoType }} · {{ statusLabel(task.status) }} · {{ task.materialCount }} 份材料</span>
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
                <div v-if="!isLoadingTasks && tasks.length === 0" class="creator-task-empty-state">
                  <strong>还没有视频项目</strong>
                  <span>先新建一条视频资料，后续发布方案和复盘报告都会沉淀在这里。</span>
                  <button type="button" class="creator-primary-button creator-mini-button" @click="startCreateTask">
                    新建项目
                  </button>
                </div>
                <div
                  v-else-if="!isLoadingTasks && filteredTasks.length === 0"
                  class="creator-task-empty-state"
                >
                  <strong>没有匹配的项目</strong>
                  <span>换一个关键词，或把状态筛选切回全部状态。</span>
                </div>
              </div>
            </div>
          </section>
        </div>
      </Transition>
    </Teleport>

    <Teleport to="body">
      <Transition name="creator-modal">
        <div
          v-if="pendingDeleteTask"
          class="creator-modal-backdrop creator-delete-modal-backdrop"
          @click.self="!isDeletingTask && cancelDeleteTask()"
        >
          <section
            class="creator-delete-confirm-modal"
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="creator-delete-confirm-title"
            aria-describedby="creator-delete-confirm-desc"
          >
            <header>
              <span>删除项目</span>
              <h3 id="creator-delete-confirm-title">删除「{{ pendingDeleteTaskName }}」？</h3>
            </header>
            <p id="creator-delete-confirm-desc">
              项目会从列表隐藏，历史分析产物保留在后端。这个操作完成后，当前列表会自动刷新。
            </p>
            <div class="creator-delete-actions">
              <button
                type="button"
                class="creator-ghost-button"
                :disabled="isDeletingTask"
                @click="cancelDeleteTask"
              >
                取消
              </button>
              <button
                type="button"
                class="creator-danger-action"
                :disabled="isDeletingTask"
                @click="confirmDeleteTask"
              >
                {{ isDeletingTask ? '删除中...' : '确认删除' }}
              </button>
            </div>
          </section>
        </div>
      </Transition>
    </Teleport>

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
        <section class="creator-flow-card" aria-label="视频发布流程">
          <header class="creator-flow-head">
            <strong>视频发布流程</strong>
            <span>完成以下步骤，高效发布视频</span>
          </header>

          <div
            class="creator-mobile-progress"
            aria-label="当前创作进度"
            :style="{ '--creator-progress-percent': creatorProgressPercent }"
          >
            <div class="creator-mobile-progress-head">
              <span>第 {{ activeCreatorStepIndex + 1 }} / {{ creatorStepMetas.length }} 步</span>
              <strong>{{ activeCreatorStepMeta.label }}</strong>
            </div>
            <div class="creator-mobile-progress-track" aria-hidden="true">
              <span></span>
            </div>
            <p>{{ activeCreatorStepMeta.description }}</p>
          </div>

          <nav class="creator-tabs creator-tabs-vertical creator-tabs-readonly" aria-label="创作步骤">
            <button
              type="button"
              :class="{ active: activeStep === 'task' }"
              :aria-current="activeStep === 'task' ? 'step' : undefined"
              aria-disabled="true"
              tabindex="-1"
            >
              <span class="creator-step-count">1</span>
              <span class="creator-step-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24">
                  <path d="M7 3.5h7l3.5 3.5v13.5h-11v-17z" />
                  <path d="M14 3.5v4h3.5" />
                  <path d="M9 11h6M9 15h5" />
                </svg>
              </span>
              <span class="creator-step-text">
                <strong>视频资料</strong>
                <small>填写视频基础信息</small>
              </span>
            </button>
            <button
              type="button"
              :disabled="!hasSelectedTask"
              :class="{ active: activeStep === 'prePublish' }"
              :aria-current="activeStep === 'prePublish' ? 'step' : undefined"
              aria-disabled="true"
              tabindex="-1"
            >
              <span class="creator-step-count">2</span>
              <span class="creator-step-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24">
                  <path d="M6 5.5h12v13h-12z" />
                  <path d="M9 9h6M9 12.5h5M9 16h3.5" />
                  <path d="M16.5 4v3M18 5.5h-3" />
                </svg>
              </span>
              <span class="creator-step-text">
                <strong>发布方案</strong>
                <small>生成发布计划与方案</small>
              </span>
            </button>
            <button
              type="button"
              :disabled="!canEnterFeedback"
              :class="{ active: activeStep === 'feedback' }"
              :aria-current="activeStep === 'feedback' ? 'step' : undefined"
              aria-disabled="true"
              tabindex="-1"
            >
              <span class="creator-step-count">3</span>
              <span class="creator-step-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24">
                  <path d="M5 6.5h14v8.5h-8l-4 3v-3h-2z" />
                  <path d="M8.5 10.5h7M8.5 13h4.5" />
                </svg>
              </span>
              <span class="creator-step-text">
                <strong>观众反馈</strong>
                <small>收集观众意见与反馈</small>
              </span>
            </button>
            <button
              type="button"
              :disabled="!feedbackReport"
              :class="{ active: activeStep === 'report' }"
              :aria-current="activeStep === 'report' ? 'step' : undefined"
              aria-disabled="true"
              tabindex="-1"
            >
              <span class="creator-step-count">4</span>
              <span class="creator-step-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24">
                  <path d="M6 4.5h12v15h-12z" />
                  <path d="M9 16v-3M12 16v-6M15 16v-4" />
                  <path d="M8.5 8h7" />
                </svg>
              </span>
              <span class="creator-step-text">
                <strong>复盘报告</strong>
                <small>生成复盘总结报告</small>
              </span>
            </button>
          </nav>
        </section>

        <TaskListPanel />
      </aside>

      <section class="creator-main">
        <NotificationToast
          type="error"
          :message="errorMessage"
          @close="errorMessage = ''"
        />

        <MaterialsTab v-if="activeStep === 'task'" />

        <Teleport to="body">
          <div
            v-if="isDeveloperTestOpen"
            class="creator-modal-backdrop creator-dev-test-backdrop"
            role="presentation"
            @pointerdown="handleDeveloperTestBackdropPointerDown"
            @click="handleDeveloperTestBackdropClick"
          >
            <section
              class="creator-result-modal creator-dev-test-modal"
              role="dialog"
              aria-modal="true"
              aria-label="开发者功能测试"
            >
              <header class="creator-result-modal-head creator-dev-test-head">
                <div>
                  <h3>开发者功能测试</h3>
                </div>
                <div class="creator-action-row">
                  <label class="creator-eval-filter">
                    <span>阶段</span>
                    <select v-model="evalStageFilter" @change="loadEvaluationCases">
                      <option
                        v-for="option in evalStageOptions"
                        :key="option.value"
                        :value="option.value"
                      >
                        {{ option.label }}
                      </option>
                    </select>
                  </label>
                  <button
                    type="button"
                    class="creator-secondary-action"
                    :disabled="isLoadingEvalCases"
                    @click="loadEvaluationCases"
                  >
                    {{ isLoadingEvalCases ? '读取中...' : '刷新样例' }}
                  </button>
                  <button type="button" class="creator-ghost-button" @click="closeDeveloperTest">
                    关闭
                  </button>
                </div>
              </header>

              <div class="creator-result-modal-body creator-dev-test-body">
                <div class="creator-eval-overview" aria-label="评测样例概览">
            <span><b>{{ evalStats.total }}</b> 样例</span>
            <span><b>{{ evalStats.prePublish }}</b> 发布前</span>
            <span><b>{{ evalStats.feedback }}</b> 反馈</span>
            <span><b>{{ evalStats.report }}</b> 复盘</span>
          </div>

          <div class="creator-eval-grid">
            <section class="creator-eval-list-panel" aria-label="评测样例列表">
              <header class="creator-workflow-head">
                <div>
                  <h4>样例列表</h4>
                </div>
                <span class="creator-parse-status">{{ filteredEvalCases.length }} 个</span>
              </header>

              <div class="creator-eval-case-list">
                <button
                  v-for="item in filteredEvalCases"
                  :key="item.caseId"
                  type="button"
                  class="creator-eval-case"
                  :class="{ active: item.caseId === selectedEvalCase?.caseId }"
                  @click="selectEvalCase(item.caseId)"
                >
                  <small>{{ evalStageLabel(item.targetStage) }} · {{ item.status }}</small>
                  <strong>{{ item.caseName }}</strong>
                  <span>{{ item.taskId || '未绑定任务' }}</span>
                </button>

                <p v-if="!isLoadingEvalCases && filteredEvalCases.length === 0" class="creator-muted">
                  当前筛选条件下没有评测样例。
                </p>
              </div>
            </section>

            <section v-if="selectedEvalCase" class="creator-eval-detail-panel" aria-label="评测样例详情">
              <header class="creator-workflow-head">
                <div>
                  <h4>{{ selectedEvalCase.caseName }}</h4>
                </div>
                <span class="creator-parse-status">{{ selectedEvalCase.status }}</span>
              </header>

              <div class="creator-eval-snapshot">
                <span>输入快照</span>
                <pre>{{ selectedEvalCase.inputSnapshot }}</pre>
                <span>期望要点</span>
                <pre>{{ selectedEvalCase.expectedPoints || '未填写' }}</pre>
                <span>评分说明</span>
                <pre>{{ selectedEvalCase.scoringRubric || '未填写' }}</pre>
              </div>

              <form class="creator-eval-result-form" @submit.prevent="submitEvalResult">
                <header>
                  <div>
                    <span>记录一次评测结果</span>
                    <strong>{{ evalStageLabel(evalResultDraft.targetStage) }}</strong>
                  </div>
                  <button
                    type="submit"
                    class="creator-primary-button"
                    :disabled="!canRecordEvalResult"
                  >
                    {{ isRecordingEvalResult ? '记录中...' : '记录结果' }}
                  </button>
                </header>

                <div class="creator-form-grid">
                  <label>
                    <span>模型名称</span>
                    <input v-model="evalResultDraft.modelName" type="text" maxlength="128" />
                  </label>
                  <label>
                    <span>Prompt 版本</span>
                    <input
                      v-model="evalResultDraft.promptVersion"
                      type="text"
                      maxlength="64"
                      placeholder="例如 prepublish-v2"
                    />
                  </label>
                  <label>
                    <span>Prompt 哈希</span>
                    <input
                      v-model="evalResultDraft.promptHash"
                      type="text"
                      maxlength="64"
                      placeholder="可选，留空由后端根据快照计算"
                    />
                  </label>
                  <label>
                    <span>关联任务</span>
                    <input
                      v-model="evalResultDraft.taskId"
                      type="text"
                      maxlength="64"
                      placeholder="可选"
                    />
                  </label>
                  <label>
                    <span>工作流会话</span>
                    <input
                      v-model="evalResultDraft.workflowSessionId"
                      type="text"
                      maxlength="64"
                      placeholder="可选"
                    />
                  </label>
                  <label>
                    <span>耗时毫秒</span>
                    <input v-model.number="evalResultDraft.elapsedMs" type="number" min="0" />
                  </label>
                  <label>
                    <span>Prompt Token</span>
                    <input v-model.number="evalResultDraft.promptTokens" type="number" min="1" />
                  </label>
                  <label>
                    <span>Completion Token</span>
                    <input
                      v-model.number="evalResultDraft.completionTokens"
                      type="number"
                      min="1"
                    />
                  </label>
                  <label class="span-full">
                    <span>Prompt 快照</span>
                    <textarea
                      v-model="evalResultDraft.promptSnapshot"
                      maxlength="20000"
                      placeholder="粘贴本轮 system prompt 和 user prompt，后续用于复现和版本对比"
                    ></textarea>
                  </label>
                  <label class="span-full">
                    <span>输出摘要</span>
                    <textarea
                      v-model="evalResultDraft.outputSummary"
                      maxlength="4000"
                      placeholder="概括这次输出的主要结论"
                    ></textarea>
                  </label>
                  <label class="span-full">
                    <span>模型原始输出</span>
                    <textarea
                      v-model="evalResultDraft.rawOutput"
                      maxlength="20000"
                      placeholder="粘贴本轮模型输出；失败时可以留空并填写失败原因"
                    ></textarea>
                  </label>
                  <label class="span-full">
                    <span>失败原因</span>
                    <textarea
                      v-model="evalResultDraft.failureReason"
                      maxlength="500"
                      placeholder="成功时可留空"
                    ></textarea>
                  </label>
                </div>

                <div class="creator-eval-score-grid" aria-label="人工评分">
                  <label>
                    <span>可读性</span>
                    <select v-model.number="evalResultDraft.readabilityScore">
                      <option v-for="score in evalScoreOptions" :key="`read-${score}`" :value="score">
                        {{ score }}
                      </option>
                    </select>
                  </label>
                  <label>
                    <span>贴合度</span>
                    <select v-model.number="evalResultDraft.relevanceScore">
                      <option v-for="score in evalScoreOptions" :key="`rel-${score}`" :value="score">
                        {{ score }}
                      </option>
                    </select>
                  </label>
                  <label>
                    <span>完整性</span>
                    <select v-model.number="evalResultDraft.completenessScore">
                      <option v-for="score in evalScoreOptions" :key="`comp-${score}`" :value="score">
                        {{ score }}
                      </option>
                    </select>
                  </label>
                  <label>
                    <span>准确性</span>
                    <select v-model.number="evalResultDraft.accuracyScore">
                      <option v-for="score in evalScoreOptions" :key="`acc-${score}`" :value="score">
                        {{ score }}
                      </option>
                    </select>
                  </label>
                  <label>
                    <span>稳定性</span>
                    <select v-model.number="evalResultDraft.stabilityScore">
                      <option v-for="score in evalScoreOptions" :key="`sta-${score}`" :value="score">
                        {{ score }}
                      </option>
                    </select>
                  </label>
                  <label>
                    <span>成本</span>
                    <select v-model.number="evalResultDraft.costScore">
                      <option v-for="score in evalScoreOptions" :key="`cost-${score}`" :value="score">
                        {{ score }}
                      </option>
                    </select>
                  </label>
                  <label>
                    <span>可解释性</span>
                    <select v-model.number="evalResultDraft.explainabilityScore">
                      <option v-for="score in evalScoreOptions" :key="`exp-${score}`" :value="score">
                        {{ score }}
                      </option>
                    </select>
                  </label>
                </div>

                <label class="creator-eval-note-field">
                  <span>人工备注</span>
                  <textarea
                    v-model="evalResultDraft.reviewerNote"
                    maxlength="1000"
                    placeholder="记录这次评分的判断依据"
                  ></textarea>
                </label>
              </form>

              <section class="creator-eval-results" aria-label="评测结果列表">
                <header class="creator-workflow-head">
                  <div>
                    <h4>最近结果</h4>
                  </div>
                  <button
                    type="button"
                    class="creator-ghost-button"
                    :disabled="isLoadingEvalResults"
                    @click="refreshEvaluationResults(selectedEvalCase.caseId)"
                  >
                    {{ isLoadingEvalResults ? '读取中' : '刷新结果' }}
                  </button>
                </header>

                <div class="creator-eval-result-list">
                  <article
                    v-if="evalPromptVersionStats.length > 0"
                    class="creator-eval-prompt-stats"
                    aria-label="Prompt版本对比"
                  >
                    <strong>Prompt 版本对比</strong>
                    <div>
                      <span v-for="item in evalPromptVersionStats" :key="item.promptVersion">
                        {{ item.promptVersion }} · {{ item.resultCount }} 次 · 成功率
                        {{ formatPercent(item.successRatePercent) }} · 均分
                        {{ formatMetric(item.averageScore) }} · 准确
                        {{ formatMetric(item.averageAccuracyScore) }} · Token
                        {{ formatMetric(item.averageTotalTokens) }} · 覆盖
                        {{ formatPercent(item.fullScoreCoverageRatePercent) }} · 波动
                        {{ formatMetric(item.scoreStandardDeviation) }}
                      </span>
                    </div>
                  </article>

                  <button
                    v-for="item in evalResults"
                    :key="item.resultId"
                    type="button"
                    class="creator-eval-result-item"
                    :class="{ active: item.resultId === selectedEvalResult?.resultId }"
                    @click="selectedEvalResultId = item.resultId"
                  >
                    <small>
                      {{ evalResultStatusLabel(item.runStatus) }} ·
                      {{ item.modelName || '未记录模型' }} · {{ formatDate(item.updateTime) }}
                    </small>
                    <strong>{{ item.outputSummary || item.failureReason || '未填写摘要' }}</strong>
                    <span>
                      {{ item.promptVersion || '未记录Prompt版本' }} · Token
                      {{ formatMetric(item.totalTokens) }} · {{ item.parseStatus }}
                    </span>
                  </button>

                  <p v-if="evalResults.length === 0" class="creator-muted">
                    当前样例还没有评测结果。
                  </p>
                </div>

                <article v-if="selectedEvalResult" class="creator-eval-result-detail">
                  <small>
                    {{ evalResultStatusLabel(selectedEvalResult.runStatus) }} ·
                    {{ selectedEvalResult.resultId }}
                  </small>
                  <strong>{{ selectedEvalResult.outputSummary || '未填写输出摘要' }}</strong>
                  <p v-if="selectedEvalResult.failureReason">
                    失败原因：{{ selectedEvalResult.failureReason }}
                  </p>
                  <p v-if="selectedEvalResult.promptVersion || selectedEvalResult.promptHash">
                    Prompt：{{ selectedEvalResult.promptVersion || '未记录版本' }}
                    <span v-if="selectedEvalResult.promptHash">
                      · {{ selectedEvalResult.promptHash.slice(0, 12) }}
                    </span>
                  </p>
                  <pre v-if="selectedEvalResult.promptSnapshot">{{ selectedEvalResult.promptSnapshot }}</pre>
                  <pre>{{ selectedEvalResult.rawOutput }}</pre>
                </article>
              </section>
            </section>

            <article v-else class="creator-empty-result">
              <strong>还没有评测样例</strong>
              <span>请先执行数据库初始化脚本，导入阶段 4.6 内置样例。</span>
            </article>
                </div>
              </div>
            </section>
          </div>
        </Teleport>

        <PrePublishTab v-if="activeStep === 'prePublish'" />

        <FeedbackTab v-if="activeStep === 'feedback'" />

        <ReportTab v-if="activeStep === 'report'" />

        <UsageTab v-if="activeStep === 'usage'" />
      </section>
    </div>

    <Teleport to="body">
      <div
        v-if="workflowProcessModalOpen"
        class="creator-modal-backdrop"
        role="presentation"
        @click.self="closeWorkflowProcessModal"
      >
        <section
          class="creator-process-modal"
          role="dialog"
          aria-modal="true"
          aria-label="过程详情"
        >
          <header class="creator-result-modal-head">
            <div>
              <p class="creator-kicker">过程详情</p>
              <h3>执行步骤、证据和开销</h3>
            </div>
            <div class="creator-panel-actions">
              <button
                type="button"
                class="creator-secondary-action"
                :disabled="!workflowSession"
                @click="refreshPrePublishWorkflowSteps"
              >
                刷新
              </button>
              <button type="button" class="creator-ghost-button" @click="closeWorkflowProcessModal">
                关闭
              </button>
            </div>
          </header>

          <div class="creator-process-modal-body">
            <section class="creator-process-summary-grid" aria-label="工作流过程汇总">
              <article>
                <span>工作流状态</span>
                <strong>{{ workflowStatusText }}</strong>
              </article>
              <article>
                <span>步骤</span>
                <strong>{{ workflowStepStats.total }} 步</strong>
                <small>{{ workflowStepStats.failed }} 失败 · {{ workflowStepStats.running }} 执行中</small>
              </article>
              <article>
                <span>API 调用</span>
                <strong>{{ workflowUsage?.totalCalls ?? 0 }} 次</strong>
                <small>{{ workflowUsage?.failedCalls ?? 0 }} 失败 · {{ workflowUsage?.skippedCalls ?? 0 }} 跳过</small>
              </article>
              <article>
                <span>模型开销</span>
                <strong>{{ formatUsageToken(workflowUsage?.totalTokens) }} token</strong>
                <small>{{ formatDuration(workflowUsage?.totalElapsedMs) }}</small>
              </article>
            </section>

            <p v-if="workflowUsageError" class="creator-process-warning">
              开销统计暂不可用：{{ workflowUsageError }}
            </p>

            <div class="creator-process-step-list">
              <article
                v-for="step in workflowSteps"
                :key="step.stepId"
                class="creator-process-step"
                :class="step.status.toLowerCase()"
              >
                <header>
                  <small>
                    {{ workflowStepTypeLabel(step.stepType) }} ·
                    {{ workflowStepStatusLabel(step.status) }} ·
                    {{ formatDate(step.startTime || step.createTime) }}
                  </small>
                  <strong>{{ step.stepName }}</strong>
                </header>

                <p v-if="step.inputSummary">输入摘要：{{ step.inputSummary }}</p>
                <p v-if="step.outputSummary">输出摘要：{{ step.outputSummary }}</p>
                <p v-if="step.errorMessage" class="creator-step-error">
                  错误信息：{{ step.errorMessage }}
                </p>

                <div v-if="workflowCallsForStep(step.stepId).length > 0" class="creator-process-api">
                  <small>API 开销</small>
                  <span
                    v-for="call in workflowCallsForStep(step.stepId)"
                    :key="call.callId"
                    :class="{ failed: call.status === 'FAILED' }"
                  >
                    {{ formatWorkflowCallUsage(call) }}
                  </span>
                </div>

                <button
                  v-if="step.rawOutput"
                  type="button"
                  class="creator-ghost-button"
                  @click="toggleRawOutput(step.stepId)"
                >
                  {{ isRawOutputExpanded(step.stepId) ? '收起原始输出' : '查看原始输出' }}
                </button>
                <pre v-if="step.rawOutput && isRawOutputExpanded(step.stepId)">{{ step.rawOutput }}</pre>
              </article>

              <article v-if="workflowSteps.length === 0" class="creator-empty-result">
                <strong>当前会话还没有步骤</strong>
                <span>触发发布前优化分析后，这里会展示步骤、状态、输入输出摘要和模型开销。</span>
              </article>

              <article
                v-for="usageStep in workflowUnmatchedUsageSteps"
                :key="usageStep.stepId"
                class="creator-process-step"
              >
                <header>
                  <small>{{ usageStep.stage || '未记录阶段' }} · 未匹配到工作流步骤</small>
                  <strong>{{ usageStep.stepName }}</strong>
                </header>
                <div class="creator-process-api">
                  <small>API 开销</small>
                  <span
                    v-for="call in usageStep.calls"
                    :key="call.callId"
                    :class="{ failed: call.status === 'FAILED' }"
                  >
                    {{ formatWorkflowCallUsage(call) }}
                  </span>
                </div>
              </article>
            </div>
          </div>
        </section>
      </div>

      <div
        v-if="workflowMessageModalOpen"
        class="creator-modal-backdrop"
        role="presentation"
        @click.self="closeWorkflowMessageModal"
      >
        <section
          class="creator-message-modal"
          role="dialog"
          aria-modal="true"
          aria-label="发布前优化消息流"
        >
          <header class="creator-result-modal-head">
            <div>
              <p class="creator-kicker">材料与消息</p>
              <h3>发布前优化消息流</h3>
            </div>
            <div class="creator-panel-actions">
              <span>{{ workflowStatusText }}</span>
              <span class="creator-sse-status" :class="{ active: workflowSseText === '实时连接' }">
                {{ workflowSseText }}
              </span>
              <button
                type="button"
                class="creator-secondary-action"
                :disabled="!hasSelectedTask || !hasSelectedTaskMaterials || isLoadingWorkflow"
                @click="refreshPrePublishWorkflowMessages"
              >
                {{ isLoadingWorkflow ? '载入中' : '刷新消息' }}
              </button>
              <button type="button" class="creator-ghost-button" @click="closeWorkflowMessageModal">
                关闭
              </button>
            </div>
          </header>

          <div class="creator-message-modal-body">
            <section class="creator-workflow-stream" aria-label="发布前优化消息列表">
              <div ref="workflowMessageListRef" class="creator-workflow-message-list">
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
                <pre>{{ selectedWorkflowMaterial?.content || selectedWorkflowMessage.content }}</pre>
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
                  !canSendWorkflowMessage || !hasText(workflowMessageDraft) || isSendingWorkflowMessage
                "
              >
                {{ isSendingWorkflowMessage ? '发送中...' : '发送消息' }}
              </button>
            </form>
          </div>
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
        :class="{
          'with-feedback-drawer':
            resultModalTarget === 'feedbackReport' && isFeedbackChatDrawerOpen,
        }"
        role="dialog"
        aria-modal="true"
        :aria-label="resultModalTitle"
      >
        <header class="creator-result-modal-head">
          <div>
            <p class="creator-kicker">阶段结果</p>
            <h3>{{ resultModalTitle }}</h3>
          </div>
          <div class="creator-panel-actions">
            <button
              v-if="resultModalTarget === 'feedbackReport'"
              type="button"
              class="creator-secondary-action"
              @click="toggleFeedbackChatDrawer"
            >
              {{ isFeedbackChatDrawerOpen ? '收起追问' : '追问报告' }}
            </button>
            <button type="button" class="creator-ghost-button" @click="closeResultModal">
              关闭
            </button>
          </div>
        </header>

        <div class="creator-result-modal-body">
          <template v-if="resultModalTarget === 'prePublishSuggestion' && suggestion">
            <div class="creator-result-grid">
              <article class="creator-confirm-panel span-full">
                <div>
                  <span>采用状态</span>
                  <strong>{{ hasConfirmedPrePublish ? '已采用' : '待采用' }}</strong>
                  <p>
                    {{
                      hasConfirmedPrePublish
                        ? '本轮发布方案已确认，观众反馈阶段已开放。'
                        : '建议生成后不会自动进入下一步，需要你确认采用。'
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
              <article class="creator-result-block span-full">
                <span>创作者困境</span>
                <p>{{ suggestion.creatorDilemma || '未解析到创作者困境' }}</p>
              </article>
              <article class="creator-result-block">
                <span>目标受众</span>
                <p>{{ suggestion.audienceProfile || '未解析到受众判断' }}</p>
                <button
                  v-if="suggestion.audienceProfile"
                  type="button"
                  class="creator-ghost-button creator-mini-button"
                  :disabled="isSavingCreatorContextTerm"
                  @click="
                    saveContextTermFromSuggestion(
                      suggestion.audienceProfile || '',
                      'AUDIENCE_CONCERN',
                      '来自发布前优化的目标受众判断',
                    )
                  "
                >
                  保存为观众关注点
                </button>
              </article>
              <article class="creator-result-block">
                <span>观众钩子</span>
                <p>{{ suggestion.audienceHook || '未解析到观众钩子' }}</p>
                <button
                  v-if="suggestion.audienceHook"
                  type="button"
                  class="creator-ghost-button creator-mini-button"
                  :disabled="isSavingCreatorContextTerm"
                  @click="
                    saveContextTermFromSuggestion(
                      suggestion.audienceHook || '',
                      'AUDIENCE_CONCERN',
                      '来自发布前优化的观众钩子判断',
                    )
                  "
                >
                  保存为观众关注点
                </button>
              </article>
              <article class="creator-result-block span-full">
                <span>内容定位</span>
                <p>{{ suggestion.contentPositioning || '未解析到内容定位' }}</p>
              </article>
              <article class="creator-result-block">
                <span>建议分区</span>
                <p>{{ suggestion.partitionSuggestion || '未解析到分区建议' }}</p>
              </article>
              <article class="creator-result-block span-full">
                <span>可执行修改计划</span>
                <p v-if="actionableRevisionPlan.length === 0">未解析到可执行修改计划</p>
                <div v-if="actionableRevisionPlan.length > 0" class="creator-list">
                  <section v-for="(item, index) in actionableRevisionPlan" :key="index">
                    <strong>
                      {{
                        getRecordText(item, 'target') ||
                        getRecordText(item, 'priority') ||
                        formatValue(item)
                      }}
                    </strong>
                    <p v-if="getRecordText(item, 'problem')">
                      问题：{{ getRecordText(item, 'problem') }}
                    </p>
                    <p v-if="getRecordText(item, 'action')">
                      动作：{{ getRecordText(item, 'action') }}
                    </p>
                    <p v-if="getRecordText(item, 'expectedEffect')">
                      预期效果：{{ getRecordText(item, 'expectedEffect') }}
                    </p>
                  </section>
                </div>
              </article>
              <article class="creator-result-block span-full">
                <span>标题建议{{ titleSuggestionCards.length ? `（${titleSuggestionCards.length} 个）` : '' }}</span>
                <p v-if="titleSuggestionCards.length === 0" class="creator-muted">未解析到标题建议</p>
                <!-- 用卡片网格替代原来的纯文本列表，每条标题独立成卡，带采纳/复制/反馈 -->
                <div v-if="titleSuggestionCards.length > 0" class="suggestion-card-grid">
                  <SuggestionCard
                    v-for="(cardItem, index) in titleSuggestionCards"
                    :key="index"
                    type="title"
                    :item="cardItem"
                    :rank="index + 1"
                    :reporting="feedbackEvent.isReporting(cardItem.content, 'TITLE_ACCEPTED')"
                    :accepted="acceptedTitleContents.has(cardItem.content)"
                    @accept="onAcceptSuggestion('title', $event, titleSuggestionCards.indexOf(cardItem) + 1)"
                    @copy="onCopySuggestion"
                    @reject="(item, reason, reasonText) => onRejectSuggestion('title', item, reason, reasonText, titleSuggestionCards.indexOf(item) + 1)"
                  >
                    <!-- 二级操作：保留原有"保存为标题套路"，作为沉淀到语境库的入口 -->
                    <template #secondary-actions>
                      <button
                        type="button"
                        class="creator-ghost-button creator-mini-button"
                        :disabled="
                          isSavingCreatorContextTerm &&
                          savingContextTermKey === contextSaveKey(cardItem.content, 'TITLE_PATTERN')
                        "
                        @click="
                          saveContextTermFromSuggestion(
                            cardItem.content,
                            'TITLE_PATTERN',
                            cardItem.reason || cardItem.clickReason,
                          )
                        "
                      >
                        保存为标题套路
                      </button>
                    </template>
                  </SuggestionCard>
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
                <span>标签建议{{ tagSuggestionCards.length ? `（${tagSuggestionCards.length} 个）` : '' }}</span>
                <p v-if="tagSuggestionCards.length === 0" class="creator-muted">未解析到标签建议</p>
                <!-- 标签建议用紧凑卡片网格，每条可采纳/复制/反馈 -->
                <div v-if="tagSuggestionCards.length > 0" class="suggestion-card-grid suggestion-card-grid-tags">
                  <SuggestionCard
                    v-for="(cardItem, index) in tagSuggestionCards"
                    :key="index"
                    type="tag"
                    :item="cardItem"
                    :rank="index + 1"
                    :reporting="feedbackEvent.isReporting(cardItem.content, 'TAG_ACCEPTED')"
                    :accepted="acceptedTagContents.has(cardItem.content)"
                    @accept="onAcceptSuggestion('tag', $event)"
                    @copy="onCopySuggestion"
                    @reject="(item, reason, reasonText) => onRejectSuggestion('tag', item, reason, reasonText)"
                  >
                    <template #secondary-actions>
                      <button
                        type="button"
                        class="creator-ghost-button creator-mini-button"
                        :disabled="isSavingCreatorContextTerm"
                        @click="
                          saveContextTermFromSuggestion(
                            cardItem.content,
                            'KEYWORD',
                            '来自发布前优化的标签建议',
                          )
                        "
                      >
                        存为关键词
                      </button>
                    </template>
                  </SuggestionCard>
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
              <article
                v-if="showDeveloperTools && feedbackFetchResult"
                class="creator-result-block span-full"
              >
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
              <article
                v-else-if="feedbackFetchResult && !feedbackDashboard"
                class="creator-result-block span-full"
              >
                <span>读取完成</span>
                <p>
                  已读取 {{ feedbackFetchResult.commentCount }} 条评论、{{
                    feedbackFetchResult.danmakuCount
                  }} 条弹幕。导入明细暂时没有返回，可以稍后刷新或直接继续分析。
                </p>
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
                      暂未识别出明显关键词，可以补充更多评论或弹幕后再分析。
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
            <div class="creator-report">
              <section v-if="showDeveloperTools" class="creator-feedback-index-status">
                <div class="creator-feedback-index-line">
                  <div>
                    <strong>证据索引</strong>
                    <small>
                      {{
                        feedbackEvidenceIndexStatus
                          ? retrievalModeLabel(feedbackEvidenceIndexStatus.retrievalMode)
                          : isLoadingFeedbackEvidenceIndexStatus
                            ? '读取中'
                            : '未读取'
                      }}
                    </small>
                  </div>
                  <button
                    type="button"
                    class="creator-ghost-button creator-mini-button"
                    :disabled="isRebuildingFeedbackEvidenceIndex"
                    @click="rebuildFeedbackEvidenceIndex"
                  >
                    {{ isRebuildingFeedbackEvidenceIndex ? '索引中...' : '重建证据索引' }}
                  </button>
                </div>
                <p v-if="feedbackEvidenceIndexStatus" class="creator-feedback-index-hint">
                  <template
                    v-if="
                      feedbackEvidenceIndexStatus.ragEnabled &&
                      feedbackEvidenceIndexStatus.vectorStoreReady
                    "
                  >
                    已索引 {{ feedbackEvidenceIndexStatus.indexedCount }}/{{
                      feedbackEvidenceIndexStatus.totalItems
                    }} 条，待索引 {{ feedbackEvidenceIndexStatus.pendingCount }} 条，失败
                    {{ feedbackEvidenceIndexStatus.failedCount }} 条。
                  </template>
                  <template v-else>
                    当前使用 SQL 证据检索（{{
                      feedbackEvidenceIndexStatus.ragEnabled ? 'Milvus 未就绪' : 'RAG 未启用'
                    }}）。
                  </template>
                </p>
                <p v-else class="creator-feedback-index-hint">
                  {{
                    isLoadingFeedbackEvidenceIndexStatus
                      ? '正在读取证据索引状态...'
                      : '暂未读取证据索引状态。'
                  }}
                </p>
                <p v-if="feedbackChatResult" class="creator-feedback-index-hint">
                  最近追问：{{ feedbackChatResult.reportUsed ? '含报告' : '仅明细' }} ·
                  {{ retrievalModeLabel(feedbackChatResult.retrievalMode) }} ·
                  {{ feedbackChatResult.modelName || '未记录模型' }} · Token
                  {{ formatMetric(feedbackChatResult.totalTokens) }} ·
                  {{ formatMetric(feedbackChatResult.elapsedMs) }} ms
                </p>
                <ul v-if="feedbackEvidenceIndexWarnings.length" class="creator-feedback-index-warnings">
                  <li v-for="(warning, index) in feedbackEvidenceIndexWarnings" :key="index">
                    {{ warning }}
                  </li>
                </ul>
                <details
                  v-if="feedbackChatResult && feedbackChatResult.evidenceItems.length > 0"
                  class="creator-feedback-index-evidence"
                >
                  <summary>
                    最近追问依据
                    <small>{{ feedbackChatResult.evidenceItems.length }} 条证据</small>
                  </summary>
                  <div class="creator-feedback-evidence-list">
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
                </details>
              </section>

              <section class="creator-report-group">
                <h4 class="creator-report-group-title">概览</h4>
                <div class="creator-report-overview">
                  <div class="creator-report-row">
                    <span>整体反馈</span>
                    <p>{{ feedbackReport.feedbackSummary || '未解析到整体反馈' }}</p>
                  </div>
                  <div class="creator-report-row">
                    <span>创作者复盘困境</span>
                    <p>{{ feedbackReport.creatorFeedbackDilemma || '未解析到创作者复盘困境' }}</p>
                  </div>
                  <div class="creator-report-row">
                    <span>观众核心关注</span>
                    <p>{{ feedbackReport.audienceCoreConcern || '未解析到观众核心关注' }}</p>
                  </div>
                  <div class="creator-report-row">
                    <span>情绪倾向</span>
                    <p>{{ feedbackReport.sentimentSummary || '未解析到情绪倾向' }}</p>
                  </div>
                </div>
              </section>

              <section class="creator-report-group">
                <h4 class="creator-report-group-title">观众怎么想</h4>
                <div class="creator-report-cards">
                  <article class="creator-result-block">
                    <span>高频观点</span>
                    <div class="creator-list">
                      <section v-for="(item, index) in hotTopics" :key="index">
                        <strong>{{ getRecordText(item, 'topic') || formatValue(item) }}</strong>
                        <p v-if="getRecordText(item, 'evidence')" class="creator-kv">
                          <span>依据</span>{{ getRecordText(item, 'evidence') }}
                        </p>
                        <p v-if="getRecordText(item, 'creatorDecision')" class="creator-kv">
                          <span>判断</span>{{ getRecordText(item, 'creatorDecision') }}
                        </p>
                        <p v-if="getRecordText(item, 'suggestion')" class="creator-kv">
                          <span>建议</span>{{ getRecordText(item, 'suggestion') }}
                        </p>
                      </section>
                    </div>
                  </article>
                  <article class="creator-result-block">
                    <span>下一期内容建议</span>
                    <div class="creator-list">
                      <section v-for="(item, index) in nextContentSuggestions" :key="index">
                        <strong>{{ getRecordText(item, 'topic') || formatValue(item) }}</strong>
                        <p v-if="getRecordText(item, 'sourceSignal')" class="creator-kv">
                          <span>信号</span>{{ getRecordText(item, 'sourceSignal') }}
                        </p>
                        <p v-if="getRecordText(item, 'executionHint')" class="creator-kv">
                          <span>做法</span>{{ getRecordText(item, 'executionHint') }}
                        </p>
                        <p v-if="getRecordText(item, 'risk')" class="creator-kv">
                          <span>注意</span>{{ getRecordText(item, 'risk') }}
                        </p>
                      </section>
                    </div>
                  </article>
                </div>
              </section>

              <section class="creator-report-group">
                <h4 class="creator-report-group-title">风险与误解</h4>
                <div class="creator-report-cards">
                  <article class="creator-result-block">
                    <span>争议点</span>
                    <div class="creator-list">
                      <section v-for="(item, index) in controversyPoints" :key="index">
                        <strong>{{ getRecordText(item, 'point') || formatValue(item) }}</strong>
                        <p v-if="getRecordText(item, 'risk')" class="creator-kv">
                          <span>风险</span>{{ getRecordText(item, 'risk') }}
                        </p>
                        <p v-if="getRecordText(item, 'responseBoundary')" class="creator-kv">
                          <span>边界</span>{{ getRecordText(item, 'responseBoundary') }}
                        </p>
                        <p v-if="getRecordText(item, 'responseAdvice')" class="creator-kv">
                          <span>回应</span>{{ getRecordText(item, 'responseAdvice') }}
                        </p>
                      </section>
                    </div>
                  </article>
                  <article class="creator-result-block">
                    <span>误解点</span>
                    <div class="creator-list">
                      <section v-for="(item, index) in misunderstandingPoints" :key="index">
                        <strong>{{ getRecordText(item, 'point') || formatValue(item) }}</strong>
                        <p v-if="getRecordText(item, 'source')" class="creator-kv">
                          <span>来源</span>{{ getRecordText(item, 'source') }}
                        </p>
                        <p v-if="getRecordText(item, 'clarificationAdvice')" class="creator-kv">
                          <span>澄清</span>{{ getRecordText(item, 'clarificationAdvice') }}
                        </p>
                      </section>
                    </div>
                  </article>
                  <article class="creator-result-block">
                    <span>误解来源分析</span>
                    <div v-if="misunderstandingSourceAnalysis.length" class="creator-list">
                      <section
                        v-for="(item, index) in misunderstandingSourceAnalysis"
                        :key="index"
                      >
                        <strong>{{ getRecordText(item, 'source') || formatValue(item) }}</strong>
                        <p v-if="getRecordText(item, 'reason')" class="creator-kv">
                          <span>成因</span>{{ getRecordText(item, 'reason') }}
                        </p>
                        <p v-if="getRecordText(item, 'repairAction')" class="creator-kv">
                          <span>修复</span>{{ getRecordText(item, 'repairAction') }}
                        </p>
                      </section>
                    </div>
                    <p v-else class="creator-report-empty">未解析到误解来源分析</p>
                  </article>
                </div>
              </section>

              <section class="creator-report-group">
                <h4 class="creator-report-group-title">我该做什么</h4>
                <div class="creator-report-cards">
                  <article class="creator-result-block">
                    <span>互动建议</span>
                    <div class="creator-list">
                      <section v-for="(item, index) in interactionSuggestions" :key="index">
                        <strong>{{ getRecordText(item, 'channel') || formatValue(item) }}</strong>
                        <p v-if="getRecordText(item, 'message')" class="creator-kv">
                          <span>内容</span>{{ getRecordText(item, 'message') }}
                        </p>
                        <p v-if="getRecordText(item, 'purpose')" class="creator-kv">
                          <span>目的</span>{{ getRecordText(item, 'purpose') }}
                        </p>
                      </section>
                    </div>
                  </article>
                  <article class="creator-result-block">
                    <span>反馈行动计划</span>
                    <div v-if="feedbackActionPlan.length" class="creator-list">
                      <section v-for="(item, index) in feedbackActionPlan" :key="index">
                        <strong>
                          <span
                            v-if="getRecordText(item, 'priority')"
                            class="creator-badge"
                          >{{ getRecordText(item, 'priority') }}</span>
                          {{ getRecordText(item, 'action') || formatValue(item) }}
                        </strong>
                        <p v-if="getRecordText(item, 'reason')" class="creator-kv">
                          <span>原因</span>{{ getRecordText(item, 'reason') }}
                        </p>
                        <p v-if="getRecordText(item, 'expectedResult')" class="creator-kv">
                          <span>预期</span>{{ getRecordText(item, 'expectedResult') }}
                        </p>
                      </section>
                    </div>
                    <p v-else class="creator-report-empty">未解析到反馈行动计划</p>
                  </article>
                </div>
              </section>

            </div>
          </template>
        </div>

        <aside
          v-if="resultModalTarget === 'feedbackReport' && isFeedbackChatDrawerOpen"
          class="creator-feedback-drawer"
          aria-label="反馈追问"
        >
          <header class="creator-feedback-drawer-head">
            <div>
              <h3>反馈追问</h3>
            </div>
            <button type="button" class="creator-ghost-button" @click="closeFeedbackChatDrawer">
              关闭
            </button>
          </header>

          <div class="creator-feedback-drawer-body">
            <section
              ref="feedbackChatThreadRef"
              class="message-list creator-feedback-chat-thread"
              aria-label="反馈追问对话"
            >
              <template v-if="hasFeedbackChatTurns">
                <template v-for="(turn, index) in feedbackChatTurns" :key="turn.id">
                  <MessageBubble :message="feedbackQuestionMessage(turn, index)" />
                  <MessageBubble :message="feedbackAnswerMessage(turn, index)" />
                </template>
              </template>
              <div v-else class="creator-feedback-chat-empty">
                <strong>还没有追问</strong>
                <p>输入一个和本次反馈报告相关的问题，系统会结合报告与评论弹幕证据回答。</p>
              </div>
            </section>
          </div>

          <form class="creator-feedback-chat-composer" @submit.prevent="askFeedbackChat">
            <textarea
              v-model="feedbackChatForm.question"
              maxlength="1000"
              placeholder="向当前报告追问..."
              @keydown.ctrl.enter.prevent="askFeedbackChat"
            ></textarea>
            <button
              type="submit"
              class="creator-primary-button"
              :disabled="!canAskFeedbackChat"
            >
              {{ isAskingFeedbackChat ? '生成中...' : '追问' }}
            </button>
          </form>
        </aside>
      </section>
    </div>

    </Teleport>

    <Teleport to="body">
      <div v-if="isContextLibraryOpen" class="creator-modal-backdrop" role="presentation">
        <section
          class="creator-result-modal creator-context-modal"
          role="dialog"
          aria-modal="true"
          aria-label="类型语境库"
        >
          <header class="creator-result-modal-head">
            <div>
              <p class="creator-kicker">类型语境库</p>
              <h3>{{ currentVideoType === 'GLOBAL' ? '全局通用' : currentVideoType }}</h3>
            </div>
            <button type="button" class="creator-ghost-button" @click="isContextLibraryOpen = false">
              关闭
            </button>
          </header>

          <div class="creator-result-modal-body">
            <form class="creator-form-grid creator-context-form" @submit.prevent="saveManualContextTerm">
              <label>
                <span>词条</span>
                <input
                  v-model="contextTermForm.term"
                  type="text"
                  maxlength="128"
                  placeholder="输入关键词、黑话、梗或慎用表达"
                />
              </label>
              <label>
                <span>类型</span>
                <select v-model="contextTermForm.termType">
                  <option
                    v-for="option in contextTermOptions"
                    :key="option.value"
                    :value="option.value"
                  >
                    {{ option.label }}
                  </option>
                </select>
              </label>
              <label class="span-full">
                <span>证据说明</span>
                <textarea
                  v-model="contextTermForm.evidenceText"
                  maxlength="1000"
                  placeholder="为什么这个词适合或不适合当前类型"
                ></textarea>
              </label>
              <div class="creator-action-row span-full">
                <button
                  type="submit"
                  class="creator-primary-button creator-mini-button"
                  :disabled="!canSaveContextTerm"
                >
                  {{ isSavingCreatorContextTerm ? '保存中...' : '保存词条' }}
                </button>
                <button type="button" class="creator-ghost-button creator-mini-button" @click="resetContextTermForm">
                  清空
                </button>
              </div>
            </form>

            <div class="creator-context-list">
              <article
                v-for="term in creatorContextTerms"
                :key="term.termId"
                class="creator-result-block"
                :class="{ muted: !term.enabled }"
              >
                <span>{{ contextTermTypeLabel(term.termType) }}</span>
                <strong>{{ term.term }}</strong>
                <p v-if="term.evidenceText">{{ term.evidenceText }}</p>
                <small>
                  {{ contextTermSourceLabel(term.sourceType) }} · 权重 {{ term.weight }} ·
                  接受 {{ term.acceptCount }} · 拒绝 {{ term.rejectCount }}
                </small>
                <div class="creator-action-row">
                  <button
                    type="button"
                    class="creator-ghost-button creator-mini-button"
                    @click="feedbackContextTerm(term, true)"
                  >
                    提高权重
                  </button>
                  <button
                    type="button"
                    class="creator-ghost-button creator-mini-button"
                    @click="feedbackContextTerm(term, false)"
                  >
                    降低权重
                  </button>
                  <button
                    v-if="term.enabled"
                    type="button"
                    class="creator-danger-action creator-mini-button"
                    @click="disableContextTerm(term)"
                  >
                    禁用
                  </button>
                </div>
              </article>
              <p v-if="!isLoadingCreatorContextTerms && creatorContextTerms.length === 0" class="creator-muted">
                当前类型还没有语境词条。
              </p>
            </div>
          </div>
        </section>
      </div>
    </Teleport>

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
