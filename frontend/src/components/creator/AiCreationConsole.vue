<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { MonitorPlay } from '@lucide/vue'
import {
  createInteractiveTask,
  getInteractiveTask,
  uploadContextDocuments,
} from '@/api/creator'
import { parseJsonArray } from '@/composables/creator/creatorWorkspaceUtils'
import { useCreatorWorkflow } from '@/composables/creator/useCreatorWorkflow'
import { useCreatorStore, type InteractiveCreationPanel } from '@/stores/creatorStore'
import type { InteractiveTask } from '@/types/creator'

const props = withDefaults(defineProps<{
  skippingToPreflight?: boolean
  resumeTaskId?: string
}>(), {
  skippingToPreflight: false,
  resumeTaskId: '',
})

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
let restoreVersion = 0

const selectedTaskId = computed(() => interactiveTask.value?.taskId ?? '')
const hasTaskMaterials = computed(() => Boolean(interactiveTask.value))
const workflowModule = useCreatorWorkflow(selectedTaskId, hasTaskMaterials, errorMessage)
const {
  workflowSession,
  workflowMessages,
  suggestion,
  isLoadingWorkflow,
  isAligningIntent: isAligning,
  isAnalyzingPrePublish: isGeneratingPlan,
  isConfirmingPrePublish: isConfirming,
  loadWorkflow,
  syncWorkflow,
  refreshWorkflow,
  alignIntent,
  runAnalyze,
  confirmSuggestion,
  sendSupplement,
  markIntentPending,
  canSendWorkflowMessage,
  prePublishAnalyzeUnavailableReason,
  resetWorkflowState,
  disconnect,
} = workflowModule

const uploadedFiles = computed(() => interactiveTask.value?.uploadedDocuments ?? [])
const isDragging = ref(false)
const previewModalVisible = ref(false)
const previewFileName = ref('')
const previewText = ref('')

const MAX_FILE_SIZE = 10 * 1024 * 1024
const ALLOWED_EXTENSIONS = [
  '.pdf', '.docx', '.doc', '.pptx', '.ppt', '.txt', '.md', '.markdown',
  '.html', '.htm', '.rtf', '.odt', '.epub',
]

const isBusy = computed(() =>
  isCreating.value ||
  isUploading.value ||
  isRestoring.value ||
  generationRequestLocked.value ||
  isLoadingWorkflow.value ||
  isAligning.value ||
  isGeneratingPlan.value ||
  isConfirming.value ||
  props.skippingToPreflight,
)
const canSubmitIdea = computed(() => form.idea.trim().length >= 10 && !isBusy.value)
const hasBackgroundContext = computed(() =>
  (interactiveTask.value?.backgroundContext?.length ?? 0) > 0,
)
const alignmentMessages = computed(() => workflowMessages.value.filter((message) =>
  message.role === 'USER' ||
  (message.role === 'AGENT' && message.detailRefType === 'INTENT_ALIGNMENT'),
))
const hasAlignmentResponse = computed(() => alignmentMessages.value.some((message) =>
  message.role === 'AGENT' && message.detailRefType === 'INTENT_ALIGNMENT',
))
const intentReady = computed(() =>
  interactiveTask.value?.understandingStatus === 'READY' && hasAlignmentResponse.value,
)
const planGenerationCount = computed(() => workflowSession.value?.planGenerationCount ?? 0)
const latestAlignmentSequence = computed(() => Math.max(
  0,
  ...workflowMessages.value
    .filter((message) => message.role === 'AGENT' && message.detailRefType === 'INTENT_ALIGNMENT')
    .map((message) => message.sequenceNo),
))
const latestResultSequence = computed(() => Math.max(
  0,
  ...workflowMessages.value
    .filter((message) => message.contentType === 'RESULT_CARD')
    .map((message) => message.sequenceNo),
))
const clarificationRequired = computed(() =>
  planGenerationCount.value >= 3 && latestAlignmentSequence.value > latestResultSequence.value,
)
const canGeneratePlan = computed(() =>
  !isBusy.value &&
  workflowSession.value?.status !== 'RUNNING' &&
  workflowSession.value?.status !== 'CONFIRMED' &&
  workflowSession.value?.status !== 'CANCELLED',
)
const canSendFeedback = computed(() =>
  feedbackDraft.value.trim().length > 0 &&
  Boolean(workflowSession.value) &&
  canSendWorkflowMessage.value &&
  !isBusy.value,
)
const statusText = computed(() => {
  if (isRestoring.value) return '正在恢复创作现场...'
  if (props.skippingToPreflight) return '正在进入成片试映...'
  if (isCreating.value) return '正在创建任务...'
  if (isUploading.value) return '正在解析补充资料...'
  if (isAligning.value) return 'AI 正在重新理解...'
  if (isGeneratingPlan.value) return '正在生成发布方案...'
  if (isConfirming.value) return '正在确认方案...'
  if (workflowSession.value?.status === 'RUNNING') return '服务端任务运行中，正在继续接收进度...'
  if (workflowSession.value?.status === 'CONFIRMED') return '发布方案已确认'
  if (clarificationRequired.value) return '需要先说清楚分歧'
  if (suggestion.value) return '发布方案待确认'
  if (intentReady.value) return '理解无误后可生成方案'
  if (interactiveTask.value) return '可以补充资料并开始对齐'
  return '等待输入想法'
})

const generationBlockReason = computed(() => {
  if (!interactiveTask.value) return '请先提交视频想法。'
  if (isUploading.value) return '补充资料仍在上传，请等待解析完成。'
  if (isAligning.value) return 'AI 正在更新当前理解，请等待对齐完成。'
  if (!hasAlignmentResponse.value) return '请先让 AI 说明它对创作想法的理解。'
  if (!intentReady.value) return '资料或补充信息已经变化，请先重新进行想法对齐。'
  if (clarificationRequired.value) return '本轮已达到三次生成上限，请先回答 AI 的澄清问题。'
  if (workflowSession.value?.status === 'RUNNING') return '服务端工作流仍在运行，页面会继续展示进度。'
  if (workflowSession.value?.status === 'CONFIRMED') return '当前发布方案已经确认。'
  return prePublishAnalyzeUnavailableReason.value
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
    if (taskId) creatorStore.setInteractiveTaskDraft(taskId, { activePanel: panel, feedbackDraft: draft })
  },
  { flush: 'sync' },
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
    noticeMessage.value = '已恢复此前的创作输入、对话、生成次数和已上传资料。'
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
  if (!task || isBusy.value) return
  clearMessages()
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
    const result = await runAnalyze({})
    await refreshWorkflow()
    if (result) {
      noticeMessage.value = '发布方案已经生成。你可以确认，也可以继续告诉 AI 哪里不对。'
      return
    }
    if (!errorMessage.value) errorMessage.value = '发布方案生成失败，请检查当前状态后重试。'
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
  return parseJsonArray(value).map((item) => typeof item === 'string' ? item : JSON.stringify(item))
}

function clearMessages() {
  errorMessage.value = ''
  noticeMessage.value = ''
}

function showError(error: unknown) {
  errorMessage.value = error instanceof Error ? error.message : '请求失败'
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
</script>

<template>
  <section class="ai-creation-console" aria-label="AI 创作台">
    <div class="ai-creation-header">
      <div>
        <p class="creator-kicker">AI 创作台</p>
        <h3>先把想法说清楚，再生成发布方案</h3>
        <p>AI 每轮只说明它现在怎么理解。你觉得没问题时，再自己点击生成方案。</p>
      </div>
      <span class="ai-creation-status">{{ statusText }}</span>
    </div>

    <nav class="ai-creation-tabs" aria-label="创作准备步骤" role="tablist">
      <button type="button" :class="{ active: activePanel === 'idea' }" @click="activePanel = 'idea'">
        创作输入
      </button>
      <button type="button" :class="{ active: activePanel === 'context' }" @click="activePanel = 'context'">
        补充资料
      </button>
      <button type="button" :class="{ active: activePanel === 'alignment' }" @click="activePanel = 'alignment'">
        想法对齐
      </button>
    </nav>

    <aside v-if="interactiveTask" class="ai-existing-video-entry" aria-labelledby="existing-video-entry-title">
      <div>
        <strong id="existing-video-entry-title">已经做好视频？</strong>
        <p>跳过想法对齐、发布方案和制作蓝图，直接上传视频进行成片试映。</p>
      </div>
      <button
        type="button"
        class="ai-existing-video-button"
        :disabled="isBusy"
        @click="requestSkipToPreflight"
      >
        <MonitorPlay :size="18" :stroke-width="1.8" aria-hidden="true" />
        {{ props.skippingToPreflight ? '正在进入...' : '直接进入成片试映' }}
      </button>
    </aside>

    <div class="ai-creation-panel" role="tabpanel">
      <form v-if="activePanel === 'idea'" class="ai-creation-composer" @submit.prevent="submitIdea">
        <label class="ai-creation-field ai-creation-field-main">
          <span>你想做什么视频</span>
          <textarea
            v-model="form.idea"
            rows="4"
            maxlength="3000"
            :disabled="interactiveTask !== null"
            placeholder="直接按你的说话方式写，不需要整理成正式需求。"
          />
        </label>
        <label class="ai-creation-field">
          <span>视频类型</span>
          <select v-model="form.videoType" :disabled="interactiveTask !== null">
            <option v-for="option in videoTypeOptions" :key="option" :value="option">{{ option }}</option>
          </select>
        </label>
        <button v-if="!interactiveTask" type="submit" class="creator-primary-button" :disabled="!canSubmitIdea">
          {{ isCreating ? '创建中...' : '提交想法' }}
        </button>
        <button v-else type="button" class="creator-secondary-action" @click="activePanel = 'context'">
          继续补充资料
        </button>
      </form>

      <div v-else-if="activePanel === 'context'" class="ai-creation-context-panel">
        <div v-if="!interactiveTask" class="ai-creation-empty-panel">
          <strong>先写下视频想法</strong>
          <p>创建任务后，可以上传项目文档、已有文稿或其它背景资料。</p>
          <button type="button" class="creator-secondary-action" @click="activePanel = 'idea'">去填写</button>
        </div>
        <template v-else>
          <div
            class="ai-creation-upload-zone"
            :class="{ dragging: isDragging }"
            @dragover="onDragOver"
            @dragleave="onDragLeave"
            @drop="onDrop"
          >
            <div class="upload-zone-header">
              <span>补充资料（可选）</span>
              <span class="upload-hint">这里是真实文件上传，系统会提取文件文字</span>
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
                <button type="button" class="file-name-btn" @click="openPreview(file.fileName)">{{ file.fileName }}</button>
                <span class="file-size">{{ formatFileSize(file.fileSize) }}</span>
              </li>
            </ul>
            <p v-if="isUploading" class="ai-creation-upload-warning" role="status">
              本地文件正在上传。请等待解析完成再离开，否则浏览器无法恢复这些文件。
            </p>
          </div>
          <div class="ai-creation-understanding">
            <button type="button" class="creator-primary-button" :disabled="isBusy" @click="startAlignment">
              {{ isAligning ? '正在理解...' : intentReady ? '根据最新资料重新对齐' : '开始想法对齐' }}
            </button>
            <button v-if="hasAlignmentResponse" type="button" class="creator-secondary-action" @click="activePanel = 'alignment'">
              查看对话
            </button>
          </div>
        </template>
      </div>

      <div v-else class="ai-creation-alignment-panel">
        <div v-if="!interactiveTask" class="ai-creation-empty-panel">
          <strong>这里会显示你和 AI 的对齐过程</strong>
          <button type="button" class="creator-secondary-action" @click="activePanel = 'idea'">先填写想法</button>
        </div>
        <div v-else-if="!hasAlignmentResponse && !isAligning" class="ai-creation-empty-panel">
          <strong>AI 还没有说明它的理解</strong>
          <p>可以先补充资料，也可以直接开始对齐。</p>
          <button type="button" class="creator-primary-button" @click="startAlignment">开始想法对齐</button>
        </div>
        <template v-else>
          <div class="ai-alignment-chat" aria-live="polite">
            <article
              v-for="message in alignmentMessages"
              :key="message.messageId"
              class="ai-alignment-message"
              :class="message.role === 'USER' ? 'user' : 'agent'"
            >
              <span>{{ message.role === 'USER' ? '你' : 'AI 当前理解' }}</span>
              <p>{{ message.content }}</p>
            </article>
            <div v-if="isAligning" class="ai-creation-loading">
              <span />
              <p>主 Agent 正在理解，审查 Agent 会检查它有没有偏离你的原话。</p>
            </div>
          </div>

          <div class="ai-alignment-composer">
            <label class="ai-creation-field">
              <span>哪里不对，直接告诉它</span>
              <textarea
                v-model="feedbackDraft"
                rows="3"
                maxlength="2000"
                :disabled="isBusy"
                placeholder="例如：我不是要教别人部署，我是想展示现在的流程为什么麻烦。"
              />
            </label>
            <button type="button" class="creator-secondary-action" :disabled="!canSendFeedback" @click="sendFeedbackAndAlign">
              发送并重新对齐
            </button>
          </div>

          <div class="ai-plan-action">
            <div>
              <strong>发布方案由你决定什么时候生成</strong>
              <p v-if="clarificationRequired">AI 已停止重复生成，请先回答上面的问题。</p>
              <p v-else>当前上下文已生成 {{ planGenerationCount }}/3 次；你补充新信息后会重新计数。</p>
              <p v-if="generationBlockReason" class="ai-plan-block-reason">{{ generationBlockReason }}</p>
            </div>
            <button
              type="button"
              class="creator-primary-button"
              :disabled="!canGeneratePlan"
              :title="generationBlockReason"
              @click="generatePlan"
            >
              {{ isGeneratingPlan ? '生成中...' : planGenerationCount >= 3 ? '让 AI 先问清楚' : suggestion ? '重新生成发布方案' : '生成发布方案' }}
            </button>
          </div>

          <article v-if="suggestion" class="ai-plan-result">
            <header>
              <div>
                <span>当前发布方案</span>
                <h4>{{ suggestion.contentPositioning || suggestion.contentSummary || '本期视频发布方案' }}</h4>
              </div>
              <span>{{ suggestion.parseStatus === 'PARSED' ? '结构化结果' : '原始结果' }}</span>
            </header>
            <div v-if="suggestion.audienceProfile" class="ai-plan-block">
              <strong>给谁看</strong>
              <p>{{ suggestion.audienceProfile }}</p>
            </div>
            <div v-if="parseTextList(suggestion.titleSuggestions).length" class="ai-plan-block">
              <strong>标题建议</strong>
              <ul>
                <li v-for="title in parseTextList(suggestion.titleSuggestions)" :key="title">{{ title }}</li>
              </ul>
            </div>
            <div v-if="suggestion.descriptionSuggestion" class="ai-plan-block">
              <strong>简介</strong>
              <p>{{ suggestion.descriptionSuggestion }}</p>
            </div>
            <div v-if="parseTextList(suggestion.tagSuggestions).length" class="ai-plan-block">
              <strong>标签</strong>
              <p>{{ parseTextList(suggestion.tagSuggestions).join(' / ') }}</p>
            </div>
            <div v-if="suggestion.riskPoints" class="ai-plan-block">
              <strong>需要注意</strong>
              <p>{{ parseTextList(suggestion.riskPoints).join('；') || suggestion.riskPoints }}</p>
            </div>
            <pre v-if="suggestion.parseStatus !== 'PARSED'" class="understanding-content">{{ suggestion.rawOutput }}</pre>
            <button type="button" class="creator-primary-button" :disabled="isBusy" @click="confirmPlan">
              {{ isConfirming ? '确认中...' : '确认这个发布方案' }}
            </button>
          </article>
        </template>
      </div>
    </div>

    <p v-if="noticeMessage" class="ai-creation-notice">{{ noticeMessage }}</p>
    <p v-if="errorMessage" class="ai-creation-error">{{ errorMessage }}</p>

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
