<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import MarkdownIt from 'markdown-it'
import { errorText } from '@/utils/error'
import {
  ArrowDown,
  ArrowRight,
  FileText,
  MonitorPlay,
  PanelRightClose,
  PanelRightOpen,
  Send,
  Sparkles,
} from '@lucide/vue'
import { createInteractiveTask, getInteractiveTask, uploadContextDocuments } from '@/api/creator'
import { getRecordText, parseJsonArray } from '@/composables/creator/creatorWorkspaceUtils'
import { useCreatorWorkflow } from '@/composables/creator/useCreatorWorkflow'
import { useCreatorStore, type InteractiveCreationPanel } from '@/stores/creatorStore'
import type { InteractiveTask } from '@/types/creator'

const props = withDefaults(
  defineProps<{
    skippingToPreflight?: boolean
    resumeTaskId?: string
  }>(),
  {
    skippingToPreflight: false,
    resumeTaskId: '',
  },
)

const emit = defineEmits<{
  confirmed: [taskId: string]
  skipToPreflight: [taskId: string]
  taskCreated: [taskId: string]
  pendingUploadChange: [pending: boolean]
}>()

const creatorStore = useCreatorStore()

const videoTypeOptions = [
  '未分类',
  '知识科普',
  '技术分享',
  '游戏攻略',
  '数码测评',
  '影视杂谈',
  '生活记录',
  '鬼畜娱乐',
]

const form = reactive({
  idea: creatorStore.newInteractiveTaskDraft.idea,
  videoType: creatorStore.newInteractiveTaskDraft.videoType || '未分类',
})

const interactiveTask = ref<InteractiveTask | null>(null)
const feedbackDraft = ref('')
const errorMessage = ref('')
const noticeMessage = ref('')
const isCreating = ref(false)
const isUploading = ref(false)
const isRestoring = ref(false)
const generationRequestLocked = ref(false)
const activePanel = ref<InteractiveCreationPanel>('idea')
const alignmentView = ref<'conversation' | 'plan'>('conversation')
const isPlanCollapsed = ref(false)
const conversationElement = ref<HTMLElement | null>(null)
const feedbackElement = ref<HTMLTextAreaElement | null>(null)
const isFollowingConversation = ref(true)
const showLatestShortcut = ref(false)
const markdown = new MarkdownIt({ html: false, breaks: true, linkify: true })
const referenceMarkdown = new MarkdownIt({ html: true, breaks: true, linkify: true })
// 文档的排版 HTML 只提取文字，避免展示布局代码或执行脚本；围栏内的代码仍保留原文。
referenceMarkdown.renderer.rules.html_block = (tokens, index) =>
  markdown.render(extractReferenceHtmlText(tokens[index]?.content || ''))
referenceMarkdown.renderer.rules.html_inline = (tokens, index) => {
  const content = tokens[index]?.content || ''
  return /^<br\s*\/?\s*>$/i.test(content)
    ? '<br>'
    : markdown.utils.escapeHtml(extractReferenceHtmlText(content))
}
let restoreVersion = 0

const selectedTaskId = computed(() => interactiveTask.value?.taskId ?? '')
const hasTaskMaterials = computed(() => Boolean(interactiveTask.value))
const workflowModule = useCreatorWorkflow(selectedTaskId, hasTaskMaterials, errorMessage)
const {
  workflowSession,
  workflowMessages,
  workflowSteps,
  suggestion,
  isLoadingWorkflow,
  isSendingWorkflowMessage: isSendingFeedback,
  isAligningIntent: isAligning,
  isAnalyzingPrePublish: isGeneratingPlan,
  isConfirmingPrePublish: isConfirming,
  loadWorkflow,
  syncWorkflow,
  alignIntent,
  runAnalyze,
  confirmSuggestion,
  sendSupplement,
  markIntentPending,
  canSendWorkflowMessage,
  canConfirmPrePublish,
  prePublishAnalyzeUnavailableReason,
  resetWorkflowState,
  disconnect,
} = workflowModule

const uploadedFiles = computed(() => interactiveTask.value?.uploadedDocuments ?? [])
const hasReferenceText = computed(() => Boolean(interactiveTask.value?.backgroundContext?.trim()))
const referenceHtml = computed(() =>
  referenceMarkdown.render(interactiveTask.value?.backgroundContext || ''),
)
const isDragging = ref(false)
const previewModalVisible = ref(false)
const previewFileName = ref('')
const previewText = ref('')

const MAX_FILE_SIZE = 10 * 1024 * 1024
const ALLOWED_EXTENSIONS = [
  '.pdf',
  '.docx',
  '.doc',
  '.pptx',
  '.ppt',
  '.txt',
  '.md',
  '.markdown',
  '.html',
  '.htm',
  '.rtf',
  '.odt',
  '.epub',
]

const isBusy = computed(
  () =>
    isCreating.value ||
    isUploading.value ||
    isRestoring.value ||
    generationRequestLocked.value ||
    isLoadingWorkflow.value ||
    isSendingFeedback.value ||
    isAligning.value ||
    isGeneratingPlan.value ||
    isConfirming.value ||
    props.skippingToPreflight,
)
const canSubmitIdea = computed(() => form.idea.trim().length >= 10 && !isBusy.value)
const alignmentMessages = computed(() =>
  workflowMessages.value.filter(
    (message) =>
      message.role === 'USER' ||
      (message.role === 'AGENT' && message.detailRefType === 'INTENT_ALIGNMENT'),
  ),
)
const hasAlignmentResponse = computed(() =>
  alignmentMessages.value.some(
    (message) => message.role === 'AGENT' && message.detailRefType === 'INTENT_ALIGNMENT',
  ),
)
const intentReady = computed(
  () => interactiveTask.value?.understandingStatus === 'READY' && hasAlignmentResponse.value,
)
const planGenerationCount = computed(() => workflowSession.value?.planGenerationCount ?? 0)
const latestAlignmentSequence = computed(() =>
  Math.max(
    0,
    ...workflowMessages.value
      .filter((message) => message.role === 'AGENT' && message.detailRefType === 'INTENT_ALIGNMENT')
      .map((message) => message.sequenceNo),
  ),
)
const latestResultSequence = computed(() =>
  Math.max(
    0,
    ...workflowMessages.value
      .filter((message) => message.contentType === 'RESULT_CARD')
      .map((message) => message.sequenceNo),
  ),
)
const clarificationRequired = computed(
  () =>
    planGenerationCount.value >= 3 && latestAlignmentSequence.value > latestResultSequence.value,
)
const canGeneratePlan = computed(
  () =>
    Boolean(interactiveTask.value) &&
    intentReady.value &&
    !clarificationRequired.value &&
    !isBusy.value &&
    !prePublishAnalyzeUnavailableReason.value,
)
const canSendFeedback = computed(
  () =>
    feedbackDraft.value.trim().length > 0 &&
    Boolean(workflowSession.value) &&
    canSendWorkflowMessage.value &&
    !isBusy.value,
)
const canStartAlignment = computed(
  () =>
    Boolean(interactiveTask.value) &&
    !isBusy.value &&
    (!workflowSession.value || canSendWorkflowMessage.value),
)

watch(
  () => suggestion.value?.suggestionId,
  (suggestionId, previousId) => {
    if (!suggestionId || suggestionId === previousId) return
    // 请求超时后后台仍可能完成；真实新结果到达时，不能继续提示用户付费重试。
    if (noticeMessage.value === '发布方案仍在后台生成，完成后会自动显示，请勿重复提交。') {
      errorMessage.value = ''
      noticeMessage.value = '发布方案已经生成，你可以查看并确认。'
    }
  },
)
const statusText = computed(() => {
  if (isRestoring.value) return '正在恢复创作现场...'
  if (props.skippingToPreflight) return '正在进入成片试映...'
  if (isCreating.value) return '正在创建任务...'
  if (isUploading.value) return '正在解析补充资料...'
  if (isSendingFeedback.value) return '正在发送你的补充...'
  if (isAligning.value) return 'AI 正在理解你的想法...'
  if (isGeneratingPlan.value) return '正在生成发布方案...'
  if (isConfirming.value) return '正在确认方案...'
  if (workflowSession.value?.status === 'RUNNING') return '服务端任务运行中，正在继续接收进度...'
  if (workflowSession.value?.status === 'CONFIRMED') return '发布方案已确认'
  if (workflowSession.value?.status === 'CANCELLED') return '当前会话已取消'
  if (clarificationRequired.value) return '需要先说清楚分歧'
  if (hasAlignmentResponse.value && !intentReady.value) return '资料已更新，等待重新对齐'
  if (suggestion.value) return '发布方案待确认'
  if (intentReady.value) return '理解无误后可生成方案'
  if (interactiveTask.value) return '可以补充资料并开始对齐'
  return '等待输入想法'
})
const renderedAlignmentMessages = computed(() =>
  alignmentMessages.value.map((message) => ({
    ...message,
    html: message.role === 'AGENT' ? markdown.render(message.content) : '',
  })),
)
const sessionHeading = computed(() => {
  if (activePanel.value === 'idea') return '原始想法'
  if (activePanel.value === 'context') return '创作资料'
  return '想法对齐'
})
const planHeading = computed(() => {
  if (isPlanProgressVisible.value) return 'AI 正在整理发布方案'
  if (clarificationRequired.value) return '先回答这轮澄清问题'
  if (!hasAlignmentResponse.value) return '先让 AI 理解你的想法'
  if (!intentReady.value) return '需要更新 AI 的理解'
  return '方向清楚了，就生成方案'
})
const planStatusText = computed(() => {
  if (workflowSession.value?.status === 'CONFIRMED') return '已确认'
  if (workflowSession.value?.status === 'CANCELLED') return '已取消'
  if (isPlanProgressVisible.value) return '生成中'
  if (clarificationRequired.value) return '待澄清'
  if (!intentReady.value) return '等待对齐'
  return suggestion.value ? '待你确认' : '对齐后生成'
})

const generationBlockReason = computed(() => {
  if (!interactiveTask.value) return '请先提交视频想法。'
  if (isUploading.value) return '补充资料仍在上传，请等待解析完成。'
  if (isAligning.value) return 'AI 正在更新当前理解，请等待对齐完成。'
  if (!hasAlignmentResponse.value) return '请先让 AI 说明它对创作想法的理解。'
  if (!intentReady.value) return '资料或补充信息已经变化，请先重新进行想法对齐。'
  if (clarificationRequired.value) return '本轮已达到三次生成上限，请先回答 AI 的澄清问题。'
  if (workflowSession.value?.status === 'RUNNING')
    return '服务端工作流仍在运行，页面会继续展示进度。'
  if (workflowSession.value?.status === 'CONFIRMED') return '当前发布方案已经确认。'
  return prePublishAnalyzeUnavailableReason.value
})

const planProgressStages = ['读取材料', '生成方案', '审查方案', '保存结果']
const planGenerationStepNames = new Set([
  '发布前优化 Agent 推理',
  '直连 LLM 回退生成建议',
  '生成发布前优化建议',
  '检查发布方案是否偏离用户想法',
  '按用户原话重新生成发布方案',
  '复查重答后的发布方案',
  '保存建议结果消息',
])
const currentPlanSteps = computed(() => {
  const orderedSteps = [...workflowSteps.value].sort((left, right) => {
    const leftTime = left.startTime || left.createTime
    const rightTime = right.startTime || right.createTime
    return leftTime.localeCompare(rightTime)
  })
  const latestStartIndex = orderedSteps.reduce(
    (result, step, index) => (step.stepType === 'LOAD_CONTEXT' ? index : result),
    -1,
  )
  return latestStartIndex >= 0 ? orderedSteps.slice(latestStartIndex) : []
})
const latestCurrentPlanStep = computed(
  () => currentPlanSteps.value[currentPlanSteps.value.length - 1] ?? null,
)
const hasRunningPlanGeneration = computed(() => {
  const latestStep = latestCurrentPlanStep.value
  return Boolean(
    workflowSession.value?.status === 'RUNNING' &&
    latestStep &&
    (latestStep.stepType === 'LOAD_CONTEXT' || planGenerationStepNames.has(latestStep.stepName)),
  )
})
const isPlanProgressVisible = computed(
  () =>
    !isAligning.value &&
    (generationRequestLocked.value || isGeneratingPlan.value || hasRunningPlanGeneration.value),
)
const hasSecondarySurface = computed(
  () => Boolean(suggestion.value) || isPlanProgressVisible.value || hasReferenceText.value,
)
const planProgress = computed(() => {
  if (workflowSession.value?.status !== 'RUNNING') {
    return {
      currentStep: '正在准备生成发布方案',
      percent: 8,
      stageIndex: 0,
    }
  }

  const steps = currentPlanSteps.value
  const runningIndex = steps.findIndex((step) => step.status === 'RUNNING')
  const currentIndex = runningIndex >= 0 ? runningIndex : steps.length - 1
  const currentStep = currentIndex >= 0 ? steps[currentIndex] : null
  const hasEarlierCandidate =
    currentIndex > 0 &&
    steps
      .slice(0, currentIndex)
      .some(
        (step) =>
          step.status === 'SUCCESS' &&
          (step.stepType === 'AGENT_REASONING' || step.stepType === 'LLM_CALL'),
      )

  // 偏离后会动态增加重答与复查步骤，按固定业务阶段推进可避免总步骤数变化导致进度倒退。
  let stageIndex = 0
  let percent = 8
  if (currentStep?.stepType === 'LOAD_CONTEXT') {
    percent = currentStep.status === 'SUCCESS' ? 25 : 12
  } else if (currentStep?.stepType === 'SAVE_RESULT') {
    stageIndex = 3
    percent = currentStep.status === 'SUCCESS' ? 100 : 90
  } else if (currentStep?.stepType === 'AGENT_REASONING') {
    stageIndex = 1
    percent = currentStep.status === 'SUCCESS' ? 50 : 38
  } else if (currentStep?.stepType === 'LLM_CALL') {
    if (hasEarlierCandidate) {
      stageIndex = 2
      percent = currentStep.status === 'SUCCESS' ? 75 : 65
    } else {
      stageIndex = 1
      percent = currentStep.status === 'SUCCESS' ? 50 : 38
    }
  }

  return {
    currentStep: currentStep?.stepName || '正在准备生成发布方案',
    percent,
    stageIndex,
  }
})

onMounted(() => {
  window.addEventListener('beforeunload', handleBeforeUnload)
  void restoreInteractiveTask(props.resumeTaskId)
})

onBeforeUnmount(() => {
  restoreVersion += 1
  disconnect()
  window.removeEventListener('beforeunload', handleBeforeUnload)
  emit('pendingUploadChange', false)
})

watch(
  () => props.resumeTaskId,
  (taskId, previousTaskId) => {
    if (taskId === previousTaskId || interactiveTask.value?.taskId === taskId) return
    void restoreInteractiveTask(taskId)
  },
)

watch(
  () => [form.idea, form.videoType] as const,
  ([idea, videoType]) => {
    if (!interactiveTask.value) creatorStore.setNewInteractiveTaskDraft(idea, videoType)
  },
  { flush: 'sync' },
)

watch(
  [activePanel, feedbackDraft],
  ([panel, draft]) => {
    const taskId = interactiveTask.value?.taskId
    if (taskId)
      creatorStore.setInteractiveTaskDraft(taskId, { activePanel: panel, feedbackDraft: draft })
  },
  { flush: 'sync' },
)

watch(
  () =>
    [
      activePanel.value,
      alignmentView.value,
      alignmentMessages.value[alignmentMessages.value.length - 1]?.messageId,
      isAligning.value,
    ] as const,
  async ([panel, view, , aligning], [previousPanel, previousView]) => {
    if (panel !== 'alignment' || view !== 'conversation') return
    await nextTick()
    if (panel === previousPanel && view !== previousView) {
      trackConversationScroll()
      return
    }
    if (panel === previousPanel && view === previousView && !isFollowingConversation.value) {
      trackConversationScroll()
      return
    }
    if (aligning && conversationElement.value) {
      conversationElement.value.scrollTop = conversationElement.value.scrollHeight
    } else {
      scrollToLatestReply()
    }
  },
  { flush: 'post' },
)

watch(
  [latestAlignmentSequence, isBusy, () => workflowSession.value?.status],
  async ([sequence, busy, status]) => {
    const task = interactiveTask.value
    const version = restoreVersion
    if (!task || !sequence || busy || status !== 'WAITING_USER_INPUT') return
    if (task.understandingStatus === 'READY') return
    try {
      // 实时回复先于 HTTP 请求返回时，以服务端状态判断是否就绪，避免重复调用 AI 对齐。
      const latestTask = await getInteractiveTask(task.taskId)
      if (version === restoreVersion && interactiveTask.value === task && !isBusy.value)
        interactiveTask.value = latestTask
    } catch {
      // 后台同步失败保留当前状态；不能依据一条历史回复擅自放开生成门禁。
    }
  },
)

async function restoreInteractiveTask(taskId: string) {
  const version = ++restoreVersion
  resetWorkflowState(false)
  interactiveTask.value = null
  feedbackDraft.value = ''
  isRestoring.value = false
  clearMessages()

  if (!taskId) {
    form.idea = creatorStore.newInteractiveTaskDraft.idea
    form.videoType = creatorStore.newInteractiveTaskDraft.videoType || '未分类'
    activePanel.value = 'idea'
    return
  }

  isRestoring.value = true
  try {
    const task = await getInteractiveTask(taskId)
    if (version !== restoreVersion || props.resumeTaskId !== taskId) return
    interactiveTask.value = task
    form.idea = task.idea
    form.videoType = task.videoType || '未分类'
    creatorStore.setActiveInteractiveTaskId(task.taskId)
    const savedDraft = creatorStore.interactiveTaskDrafts[task.taskId]
    feedbackDraft.value = savedDraft?.feedbackDraft ?? ''
    activePanel.value = isInteractivePanel(savedDraft?.activePanel)
      ? savedDraft.activePanel
      : 'context'

    await loadWorkflow({
      taskId: task.taskId,
      userId: task.userId,
      resumeLatest: true,
    })
    if (version !== restoreVersion || interactiveTask.value?.taskId !== task.taskId) return
    if (!savedDraft && hasAlignmentResponse.value) activePanel.value = 'alignment'
  } catch (error) {
    if (version !== restoreVersion) return
    creatorStore.setActiveInteractiveTaskId(null)
    showError(error)
  } finally {
    if (version === restoreVersion) isRestoring.value = false
  }
}

async function submitIdea() {
  if (!canSubmitIdea.value) return
  isCreating.value = true
  clearMessages()
  try {
    interactiveTask.value = await createInteractiveTask({
      idea: form.idea.trim(),
      videoType: form.videoType || undefined,
    })
    creatorStore.setActiveInteractiveTaskId(interactiveTask.value.taskId)
    creatorStore.clearNewInteractiveTaskDraft()
    creatorStore.setInteractiveTaskDraft(interactiveTask.value.taskId, {
      activePanel: 'context',
      feedbackDraft: '',
    })
    emit('taskCreated', interactiveTask.value.taskId)
    activePanel.value = 'context'
  } catch (error) {
    showError(error)
  } finally {
    isCreating.value = false
  }
}

function requestSkipToPreflight() {
  const taskId = interactiveTask.value?.taskId
  if (!taskId || isBusy.value) return
  clearMessages()
  emit('skipToPreflight', taskId)
}

async function ensureWorkflow() {
  if (!interactiveTask.value) return null
  if (workflowSession.value) return workflowSession.value
  return loadWorkflow({
    taskId: interactiveTask.value.taskId,
    userId: interactiveTask.value.userId,
    resumeLatest: true,
  })
}

async function startAlignment() {
  const task = interactiveTask.value
  if (!task || !canStartAlignment.value) return
  clearMessages()
  activePanel.value = 'alignment'
  alignmentView.value = 'conversation'
  isFollowingConversation.value = true
  const session = await ensureWorkflow()
  if (!session) return
  const message = await alignIntent()
  if (!message) return
  markIntentReady(message.content)
  activePanel.value = 'alignment'
}

async function sendFeedbackAndAlign() {
  const task = interactiveTask.value
  const currentSession = workflowSession.value
  if (!task || !canSendFeedback.value || !currentSession) return
  clearMessages()
  isFollowingConversation.value = true
  const userMessage = await sendSupplement(feedbackDraft.value.trim())
  if (!userMessage) return
  feedbackDraft.value = ''
  interactiveTask.value = {
    ...task,
    understandingStatus: 'PENDING',
  }

  const agentMessage = await alignIntent()
  if (!agentMessage) return
  markIntentReady(agentMessage.content)
  noticeMessage.value = 'AI 已按你的补充重新说明当前理解。'
}

async function generatePlan() {
  const task = interactiveTask.value
  if (generationRequestLocked.value) {
    errorMessage.value = '发布方案请求已经提交，请等待本轮完成。'
    return
  }
  if (!task) {
    errorMessage.value = '请先提交视频想法，再生成发布方案。'
    return
  }
  if (isBusy.value) {
    errorMessage.value = statusText.value
    return
  }
  generationRequestLocked.value = true
  clearMessages()
  alignmentView.value = 'plan'
  try {
    const latestTask = await getInteractiveTask(task.taskId)
    if (interactiveTask.value?.taskId !== task.taskId) return
    interactiveTask.value = latestTask
    const session = await syncWorkflow()
    if (!session) {
      if (!errorMessage.value) errorMessage.value = '工作流会话恢复失败，请重试。'
      return
    }
    if (!intentReady.value || clarificationRequired.value) {
      errorMessage.value = generationBlockReason.value || '当前还不能生成发布方案。'
      return
    }
    const previousSuggestionId = suggestion.value?.suggestionId
    const result = await runAnalyze({})
    const generationError = errorMessage.value
    await syncWorkflow()
    if (result || (suggestion.value && suggestion.value.suggestionId !== previousSuggestionId)) {
      errorMessage.value = ''
      noticeMessage.value = '发布方案已经生成。你可以确认，也可以继续告诉 AI 哪里不对。'
      return
    }
    if (workflowSession.value?.status === 'RUNNING') {
      errorMessage.value = ''
      noticeMessage.value = '发布方案仍在后台生成，完成后会自动显示，请勿重复提交。'
      return
    }
    if (!errorMessage.value)
      errorMessage.value = generationError || '发布方案生成失败，请检查当前状态后重试。'
  } catch (error) {
    showError(error)
  } finally {
    generationRequestLocked.value = false
  }
}

async function confirmPlan() {
  const taskId = interactiveTask.value?.taskId
  if (!taskId || isBusy.value) return
  clearMessages()
  const session = await confirmSuggestion()
  if (session) emit('confirmed', taskId)
}

async function handleFilesUpload(fileList: FileList | null) {
  const task = interactiveTask.value
  if (!fileList || fileList.length === 0 || !task) return
  if (isUploading.value) {
    errorMessage.value = '已有补充资料正在上传，请等待完成后再选择文件。'
    return
  }
  const files = Array.from(fileList).filter((file) => {
    if (file.size > MAX_FILE_SIZE) {
      errorMessage.value = `文件“${file.name}”超过 10 MB 限制`
      return false
    }
    if (!ALLOWED_EXTENSIONS.some((extension) => file.name.toLowerCase().endsWith(extension))) {
      errorMessage.value = `文件“${file.name}”格式不支持`
      return false
    }
    return true
  })
  if (files.length === 0) return

  isUploading.value = true
  const version = restoreVersion
  emit('pendingUploadChange', true)
  clearMessages()
  try {
    const updatedTask = await uploadContextDocuments(task.taskId, files)
    if (version !== restoreVersion || interactiveTask.value?.taskId !== task.taskId) return
    interactiveTask.value = updatedTask
    if (workflowSession.value && updatedTask.understandingStatus === 'PENDING') {
      markIntentPending()
      noticeMessage.value = '资料已经变化，需要让 AI 重新说明理解。'
    }
  } catch (error) {
    if (version === restoreVersion && interactiveTask.value?.taskId === task.taskId) {
      showError(error)
    }
  } finally {
    if (version === restoreVersion && interactiveTask.value?.taskId === task.taskId) {
      isUploading.value = false
      emit('pendingUploadChange', false)
    }
  }
}

function onFileInputChange(event: Event) {
  const input = event.target as HTMLInputElement
  void handleFilesUpload(input.files)
  input.value = ''
}

function onDragOver(event: DragEvent) {
  event.preventDefault()
  isDragging.value = true
}

function onDragLeave() {
  isDragging.value = false
}

function onDrop(event: DragEvent) {
  event.preventDefault()
  isDragging.value = false
  void handleFilesUpload(event.dataTransfer?.files ?? null)
}

function markIntentReady(summary: string) {
  if (!interactiveTask.value) return
  interactiveTask.value = {
    ...interactiveTask.value,
    status: 'INTENT_ALIGNMENT',
    understandingSummary: summary,
    understandingStatus: 'READY',
  }
}

function parseTextList(value: string | null) {
  // 标题建议现在是带 title 字段的对象，直接 stringify 会把整段 JSON 显示给用户；
  // 与 PrePublishSuggestionPanel 保持一致，优先取 title，取不到再退回原样展示
  return parseJsonArray(value).map((item) =>
    typeof item === 'string' ? item : getRecordText(item, 'title') || JSON.stringify(item),
  )
}

function clearMessages() {
  errorMessage.value = ''
  noticeMessage.value = ''
}

function showError(error: unknown) {
  errorMessage.value = errorText(error, '请求失败')
}

function formatFileSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function openPreview(fileName: string) {
  previewFileName.value = fileName
  previewText.value = interactiveTask.value?.backgroundContext ?? '（暂无提取内容）'
  previewModalVisible.value = true
}

function closePreview() {
  previewModalVisible.value = false
  previewFileName.value = ''
  previewText.value = ''
}

function handleBeforeUnload(event: BeforeUnloadEvent) {
  if (!isUploading.value) return
  // 浏览器无法恢复尚未完成上传的 File 对象，因此离开前必须触发原生确认。
  event.preventDefault()
  event.returnValue = ''
}

function isInteractivePanel(value: unknown): value is InteractiveCreationPanel {
  return value === 'idea' || value === 'context' || value === 'alignment'
}

function handlePreparationTabKeydown(event: KeyboardEvent) {
  const panels: InteractiveCreationPanel[] = ['idea', 'context', 'alignment']
  const current = panels.indexOf(activePanel.value)
  let next = current
  if (event.key === 'ArrowRight') next = (current + 1) % panels.length
  else if (event.key === 'ArrowLeft') next = (current + panels.length - 1) % panels.length
  else if (event.key === 'Home') next = 0
  else if (event.key === 'End') next = panels.length - 1
  else return
  event.preventDefault()
  activePanel.value = panels[next]!
  if (activePanel.value === 'alignment') alignmentView.value = 'conversation'
  const buttons = (event.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>(
    '[role="tab"]',
  )
  buttons[next]?.focus()
}

function trackConversationScroll() {
  const element = conversationElement.value
  if (element) {
    isFollowingConversation.value =
      element.scrollHeight - element.scrollTop - element.clientHeight < 100
    const latest = element.querySelector<HTMLElement>('[data-intent-message]:last-of-type')
    showLatestShortcut.value = Boolean(
      latest && latest.getBoundingClientRect().top > element.getBoundingClientRect().bottom,
    )
  }
}

function scrollToLatestReply() {
  const container = conversationElement.value
  const latest = container?.querySelector<HTMLElement>('[data-intent-message]:last-of-type')
  if (!container || !latest) return
  // 长回复从开头阅读，滚动只发生在消息区，不能把底部输入框带出窗口。
  container.scrollTop +=
    latest.getBoundingClientRect().top - container.getBoundingClientRect().top - 24
  trackConversationScroll()
}

async function focusFeedback() {
  alignmentView.value = 'conversation'
  await nextTick()
  feedbackElement.value?.focus()
}

function openAlignmentConversation() {
  activePanel.value = 'alignment'
  alignmentView.value = 'conversation'
}

function extractReferenceHtmlText(content: string) {
  const fragment = document.createElement('template')
  fragment.innerHTML = content
  fragment.content
    .querySelectorAll('script, style, iframe, object')
    .forEach((node) => node.remove())
  fragment.content.querySelectorAll('img').forEach((node) => node.replaceWith(node.alt))
  return fragment.content.textContent || ''
}
</script>

<template>
  <section
    class="studio-creation-console"
    :class="{ 'is-session': Boolean(interactiveTask || props.resumeTaskId) }"
    aria-label="AI 创作台"
  >
    <div v-if="!interactiveTask && !isRestoring" class="studio-invitation">
      <span class="studio-invitation-symbol" aria-hidden="true"
        ><svg viewBox="0 0 76 54" fill="none">
          <rect
            x="1"
            y="1"
            width="74"
            height="52"
            rx="5"
            stroke="currentColor"
            stroke-width="1.5"
          />
          <path d="m32 18 15 9-15 9V18Z" fill="currentColor" />
          <path d="M8 8h5M63 8h5M8 46h5M63 46h5" stroke="currentColor" stroke-width="1.5" /></svg
      ></span>
      <h1>让一个想法，<br />成为一部作品。</h1>
      <p>说说你想拍什么。一起理清方向，把剩下的工作逐步交给 AI。</p>
    </div>
    <header v-else class="studio-intent-header">
      <div class="studio-intent-identity">
        <h2><Sparkles :size="17" :stroke-width="1.6" aria-hidden="true" />{{ sessionHeading }}</h2>
        <p :title="form.idea">{{ form.idea || '正在恢复创作任务…' }}</p>
      </div>
      <div class="studio-intent-header-actions">
        <span class="studio-intent-status" role="status">{{ statusText }}</span>
        <button
          v-if="activePanel === 'alignment' && hasSecondarySurface"
          type="button"
          class="studio-icon-button studio-intent-panel-toggle"
          :title="isPlanCollapsed ? '展开右侧面板' : '收起右侧面板'"
          :aria-label="isPlanCollapsed ? '展开右侧面板' : '收起右侧面板'"
          :aria-expanded="!isPlanCollapsed"
          aria-controls="creation-reference-panel"
          @click="isPlanCollapsed = !isPlanCollapsed"
        >
          <PanelRightOpen v-if="isPlanCollapsed" :size="18" aria-hidden="true" />
          <PanelRightClose v-else :size="18" aria-hidden="true" />
        </button>
      </div>
    </header>

    <nav
      v-if="interactiveTask"
      class="studio-intent-tabs"
      aria-label="创作准备步骤"
      role="tablist"
      @keydown="handlePreparationTabKeydown"
    >
      <button
        id="creation-tab-idea"
        type="button"
        role="tab"
        :tabindex="activePanel === 'idea' ? 0 : -1"
        :aria-selected="activePanel === 'idea'"
        aria-controls="creation-preparation-panel"
        :class="{ active: activePanel === 'idea' }"
        @click="activePanel = 'idea'"
      >
        原始想法
      </button>
      <button
        id="creation-tab-context"
        type="button"
        role="tab"
        :tabindex="activePanel === 'context' ? 0 : -1"
        :aria-selected="activePanel === 'context'"
        aria-controls="creation-preparation-panel"
        :class="{ active: activePanel === 'context' }"
        @click="activePanel = 'context'"
      >
        资料<span v-if="uploadedFiles.length" class="studio-intent-file-count">{{
          uploadedFiles.length
        }}</span>
      </button>
      <button
        id="creation-tab-alignment"
        type="button"
        role="tab"
        :tabindex="activePanel === 'alignment' ? 0 : -1"
        :aria-selected="activePanel === 'alignment'"
        aria-controls="creation-preparation-panel"
        :class="{ active: activePanel === 'alignment' }"
        @click="openAlignmentConversation"
      >
        想法对齐
      </button>
    </nav>

    <nav
      v-if="interactiveTask && activePanel === 'alignment' && hasSecondarySurface"
      class="studio-intent-view-switch"
      aria-label="切换对齐工作面"
    >
      <button
        type="button"
        :aria-pressed="alignmentView === 'conversation'"
        @click="alignmentView = 'conversation'"
      >
        对话
      </button>
      <button
        type="button"
        :aria-pressed="alignmentView === 'plan'"
        @click="alignmentView = 'plan'"
      >
        {{ suggestion || isPlanProgressVisible ? '发布方案' : '创作参考'
        }}<span v-if="suggestion" class="studio-intent-result-dot" aria-label="已有方案" />
      </button>
    </nav>

    <div
      id="creation-preparation-panel"
      class="studio-creation-panel"
      :data-panel="activePanel"
      :role="interactiveTask ? 'tabpanel' : undefined"
      :aria-labelledby="interactiveTask ? `creation-tab-${activePanel}` : undefined"
    >
      <p v-if="isRestoring && !interactiveTask" class="studio-intent-restoring" role="status">
        正在恢复创作现场…
      </p>
      <form
        v-else-if="activePanel === 'idea'"
        class="studio-creation-composer"
        @submit.prevent="submitIdea"
      >
        <label class="studio-creation-field studio-creation-field-main">
          <span class="sr-only">你想做什么视频</span>
          <textarea
            v-model="form.idea"
            rows="4"
            maxlength="3000"
            :disabled="interactiveTask !== null"
            placeholder="我想做一期关于……的视频，想让观众……"
          />
        </label>
        <div class="studio-creation-submit-row">
          <label class="studio-creation-field">
            <span>视频类型</span>
            <select v-model="form.videoType" :disabled="interactiveTask !== null">
              <option v-for="option in videoTypeOptions" :key="option" :value="option">
                {{ option }}
              </option>
            </select>
          </label>
          <span class="studio-creation-submit-hint">先说想法，资料可以稍后补充</span>
          <button
            v-if="!interactiveTask"
            type="submit"
            class="creator-primary-button"
            :disabled="!canSubmitIdea"
          >
            {{ isCreating ? '创建中...' : '开始创作' }}<ArrowRight :size="16" aria-hidden="true" />
          </button>
          <button
            v-else
            type="button"
            class="creator-secondary-action"
            @click="activePanel = 'context'"
          >
            继续补充资料
          </button>
        </div>
      </form>

      <div v-else-if="activePanel === 'context'" class="studio-creation-context-panel">
        <div v-if="!interactiveTask" class="studio-creation-empty-panel">
          <strong>先写下视频想法</strong>
          <p>创建任务后，可以上传项目文档、已有文稿或其它背景资料。</p>
          <button type="button" class="creator-secondary-action" @click="activePanel = 'idea'">
            去填写
          </button>
        </div>
        <template v-else>
          <div
            class="studio-creation-upload-zone"
            :class="{ dragging: isDragging }"
            @dragover="onDragOver"
            @dragleave="onDragLeave"
            @drop="onDrop"
          >
            <div class="upload-zone-header">
              <span>补充资料（可选）</span>
              <span class="upload-hint">支持文稿、项目资料与参考文档，单个文件不超过 10 MB</span>
            </div>
            <label class="upload-zone-body">
              <input
                type="file"
                multiple
                accept=".pdf,.docx,.doc,.pptx,.ppt,.txt,.md,.markdown,.html,.htm,.rtf,.odt,.epub"
                :disabled="isUploading"
                @change="onFileInputChange"
              />
              <span>{{ isUploading ? '正在解析文件...' : '点击或拖拽上传文件' }}</span>
            </label>
            <ul v-if="uploadedFiles.length" class="uploaded-file-list">
              <li v-for="file in uploadedFiles" :key="file.documentId">
                <button type="button" class="file-name-btn" @click="openPreview(file.fileName)">
                  {{ file.fileName }}
                </button>
                <span class="file-size">{{ formatFileSize(file.fileSize) }}</span>
              </li>
            </ul>
            <p v-if="isUploading" class="studio-creation-upload-warning" role="status">
              本地文件正在上传。请等待解析完成再离开，否则浏览器无法恢复这些文件。
            </p>
          </div>
          <div class="studio-creation-understanding">
            <button
              type="button"
              class="creator-primary-button"
              :disabled="!canStartAlignment"
              @click="startAlignment"
            >
              {{
                isAligning ? '正在理解...' : intentReady ? '根据最新资料重新对齐' : '开始想法对齐'
              }}
            </button>
            <button
              v-if="hasAlignmentResponse"
              type="button"
              class="creator-secondary-action"
              @click="openAlignmentConversation"
            >
              查看对话
            </button>
          </div>
          <aside class="studio-intent-existing-video" aria-labelledby="existing-video-entry-title">
            <div>
              <strong id="existing-video-entry-title">已经有成片？</strong>
              <p>可跳过发布方案和制作蓝图，上传视频进行成片试映。</p>
            </div>
            <button
              type="button"
              class="creator-secondary-action"
              :disabled="isBusy"
              @click="requestSkipToPreflight"
            >
              <MonitorPlay :size="16" aria-hidden="true" />
              {{ props.skippingToPreflight ? '正在进入…' : '进入成片试映' }}
            </button>
          </aside>
        </template>
      </div>

      <div
        v-else
        class="studio-intent-workspace"
        :class="{
          'is-discussion-only': !hasSecondarySurface,
          'is-plan-collapsed': isPlanCollapsed && hasSecondarySurface,
        }"
        :data-view="alignmentView"
      >
        <section class="studio-intent-conversation" aria-label="创作对话">
          <button
            v-if="hasAlignmentResponse && showLatestShortcut"
            type="button"
            class="studio-intent-jump"
            @click="scrollToLatestReply"
          >
            <ArrowDown :size="13" aria-hidden="true" />最新回复
          </button>
          <div
            ref="conversationElement"
            class="studio-intent-history"
            role="log"
            aria-label="想法对齐消息"
            aria-live="polite"
            @scroll="trackConversationScroll"
          >
            <div class="studio-intent-thread">
              <div class="studio-intent-original">
                <span>这期视频，我想做</span>
                <p>{{ form.idea }}</p>
                <button v-if="uploadedFiles.length" type="button" @click="activePanel = 'context'">
                  <FileText :size="13" aria-hidden="true" />{{ uploadedFiles.length }} 份参考资料
                </button>
              </div>
              <div v-if="!hasAlignmentResponse && !isAligning" class="studio-intent-empty">
                <Sparkles :size="23" :stroke-width="1.4" aria-hidden="true" />
                <h3>先聊清楚这期视频</h3>
                <p>AI 会结合想法和资料说明它的理解，你可以继续补充、纠正。</p>
                <button
                  type="button"
                  class="creator-primary-button"
                  :disabled="!canStartAlignment"
                  @click="startAlignment"
                >
                  开始想法对齐
                </button>
              </div>
              <article
                v-for="message in renderedAlignmentMessages"
                :key="message.messageId"
                class="studio-intent-message"
                :class="message.role === 'USER' ? 'is-user' : 'is-agent'"
                data-intent-message
              >
                <header>
                  <Sparkles
                    v-if="message.role === 'AGENT'"
                    :size="15"
                    :stroke-width="1.7"
                    aria-hidden="true"
                  /><span>{{ message.role === 'USER' ? '你' : 'AI 当前理解' }}</span>
                </header>
                <div
                  v-if="message.role === 'AGENT'"
                  class="studio-intent-markdown"
                  v-html="message.html"
                />
                <p v-else class="studio-intent-user-text">{{ message.content }}</p>
              </article>
              <div v-if="isAligning" class="studio-intent-thinking" role="status">
                <span class="studio-intent-thinking-mark" />
                <div>
                  <strong>正在理解你的想法</strong>
                  <p>结合资料梳理方向，并检查有没有偏离你的原话。</p>
                </div>
              </div>
            </div>
          </div>

          <form class="studio-intent-composer" @submit.prevent="sendFeedbackAndAlign">
            <div class="studio-intent-input">
              <label for="intent-feedback" class="sr-only">补充或纠正 AI 的理解</label>
              <textarea
                id="intent-feedback"
                ref="feedbackElement"
                v-model="feedbackDraft"
                rows="2"
                maxlength="2000"
                :disabled="isBusy || !workflowSession || !canSendWorkflowMessage"
                placeholder="回答 AI 的问题，或告诉它哪里需要调整…"
                @keydown.ctrl.enter.prevent="sendFeedbackAndAlign"
                @keydown.meta.enter.prevent="sendFeedbackAndAlign"
              />
              <div class="studio-intent-input-actions">
                <span>{{ isAligning ? 'AI 正在回复…' : '补充后，AI 会重新对齐想法' }}</span>
                <button
                  type="submit"
                  class="creator-primary-button"
                  :disabled="!canSendFeedback"
                  title="Ctrl / ⌘ + Enter 发送"
                >
                  <Send :size="14" aria-hidden="true" />{{
                    isSendingFeedback ? '发送中…' : '发送并对齐'
                  }}
                </button>
              </div>
            </div>
          </form>
        </section>

        <aside
          id="creation-reference-panel"
          class="studio-intent-plan"
          aria-label="参考资料与发布方案"
        >
          <header class="studio-intent-plan-header">
            <FileText :size="16" :stroke-width="1.6" aria-hidden="true" />
            <h3>{{ suggestion || isPlanProgressVisible ? '发布方案' : '创作参考' }}</h3>
            <span>{{ suggestion || isPlanProgressVisible ? planStatusText : '资料提取正文' }}</span>
          </header>
          <div class="studio-intent-plan-body">
            <article
              v-if="hasReferenceText && !suggestion && !isPlanProgressVisible"
              class="studio-intent-reference"
            >
              <header>
                {{ uploadedFiles.map((file) => file.fileName).join(' / ') || '背景资料' }}
              </header>
              <div class="studio-intent-markdown" v-html="referenceHtml" />
            </article>
            <div v-else-if="!suggestion || isPlanProgressVisible" class="studio-intent-plan-intro">
              <h4>{{ planHeading }}</h4>
              <p v-if="isPlanProgressVisible">生成完成后，方案会出现在这里。</p>
              <p v-else-if="clarificationRequired">在对话里回答 AI 的问题，再继续生成。</p>
              <p v-else-if="!intentReady && hasAlignmentResponse">
                资料或补充信息有变化。重新对齐后，再生成发布方案。
              </p>
              <p v-else>在对话里确认 AI 的理解。准备好后，由你开始生成。</p>
            </div>
            <div v-if="isPlanProgressVisible" class="studio-intent-progress">
              <div class="studio-intent-progress-header">
                <strong>发布方案生成中</strong>
                <span>{{ planProgress.currentStep }}</span>
              </div>
              <div
                class="studio-intent-progress-track"
                role="progressbar"
                aria-label="发布方案生成进度"
                aria-valuemin="0"
                aria-valuemax="100"
                :aria-valuenow="planProgress.percent"
                :aria-valuetext="planProgress.currentStep"
              >
                <span :style="{ width: `${planProgress.percent}%` }" />
              </div>
              <div class="studio-intent-progress-stages" aria-hidden="true">
                <span
                  v-for="(stage, index) in planProgressStages"
                  :key="stage"
                  :class="{
                    done: index < planProgress.stageIndex,
                    current: index === planProgress.stageIndex,
                  }"
                >
                  {{ stage }}
                </span>
              </div>
            </div>
            <article v-if="suggestion" class="studio-intent-plan-result">
              <header>
                <div>
                  <span>当前发布方案</span>
                  <h4>
                    {{
                      suggestion.contentPositioning ||
                      suggestion.contentSummary ||
                      '本期视频发布方案'
                    }}
                  </h4>
                </div>
                <span>{{ suggestion.parseStatus === 'PARSED' ? '结构化结果' : '原始结果' }}</span>
              </header>
              <div v-if="suggestion.audienceProfile" class="studio-intent-plan-block">
                <strong>给谁看</strong>
                <p>{{ suggestion.audienceProfile }}</p>
              </div>
              <div
                v-if="parseTextList(suggestion.titleSuggestions).length"
                class="studio-intent-plan-block"
              >
                <strong>标题建议</strong>
                <ul>
                  <li v-for="title in parseTextList(suggestion.titleSuggestions)" :key="title">
                    {{ title }}
                  </li>
                </ul>
              </div>
              <div v-if="suggestion.descriptionSuggestion" class="studio-intent-plan-block">
                <strong>简介</strong>
                <p>{{ suggestion.descriptionSuggestion }}</p>
              </div>
              <div
                v-if="parseTextList(suggestion.tagSuggestions).length"
                class="studio-intent-plan-block"
              >
                <strong>标签</strong>
                <p>{{ parseTextList(suggestion.tagSuggestions).join(' / ') }}</p>
              </div>
              <div v-if="suggestion.riskPoints" class="studio-intent-plan-block">
                <strong>需要注意</strong>
                <p>
                  {{ parseTextList(suggestion.riskPoints).join('；') || suggestion.riskPoints }}
                </p>
              </div>
              <pre v-if="suggestion.parseStatus !== 'PARSED'" class="understanding-content">{{
                suggestion.rawOutput
              }}</pre>
            </article>
          </div>
          <footer class="studio-intent-plan-actions">
            <p
              v-if="!hasSecondarySurface && !generationBlockReason && !planGenerationCount"
              class="studio-intent-block-reason"
            >
              理解无误后，可让 AI 生成发布方案。
            </p>
            <p
              v-if="generationBlockReason && !isPlanProgressVisible"
              class="studio-intent-block-reason"
            >
              {{ generationBlockReason }}
            </p>
            <p v-if="planGenerationCount" class="studio-intent-generation-count">
              当前信息已生成 {{ planGenerationCount }}/3 次，补充新信息后重新计数。
            </p>
            <button
              v-if="suggestion"
              type="button"
              class="creator-primary-button"
              :disabled="isBusy || !canConfirmPrePublish"
              @click="confirmPlan"
            >
              {{ isConfirming ? '确认中…' : '确认方案，进入制作' }}
            </button>
            <button
              v-if="clarificationRequired"
              type="button"
              class="creator-secondary-action"
              :disabled="isBusy"
              @click="focusFeedback"
            >
              回答澄清问题
            </button>
            <button
              v-else-if="!intentReady && hasAlignmentResponse && !isPlanProgressVisible"
              type="button"
              class="creator-primary-button"
              :disabled="!canStartAlignment"
              @click="startAlignment"
            >
              {{ isAligning ? '正在对齐…' : '按最新资料重新对齐' }}
            </button>
            <button
              v-else
              type="button"
              :class="suggestion ? 'creator-secondary-action' : 'creator-primary-button'"
              :disabled="!canGeneratePlan"
              :title="generationBlockReason"
              @click="generatePlan"
            >
              {{
                isPlanProgressVisible
                  ? '正在生成…'
                  : planGenerationCount >= 3
                    ? '让 AI 先问清楚'
                    : suggestion
                      ? '重新生成方案'
                      : '生成发布方案'
              }}
            </button>
          </footer>
        </aside>
      </div>
    </div>

    <div
      v-if="noticeMessage || errorMessage"
      class="studio-intent-notification"
      :class="{ 'is-error': errorMessage }"
      :role="errorMessage ? 'alert' : 'status'"
    >
      {{ errorMessage || noticeMessage }}
    </div>

    <Teleport to="body">
      <div v-if="previewModalVisible" class="preview-modal-overlay" @click.self="closePreview">
        <div class="preview-modal" role="dialog" aria-modal="true">
          <header class="preview-modal-header">
            <h4>{{ previewFileName }}</h4>
            <button type="button" class="preview-modal-close" @click="closePreview">关闭</button>
          </header>
          <pre class="understanding-content">{{ previewText }}</pre>
        </div>
      </div>
    </Teleport>
  </section>
</template>
