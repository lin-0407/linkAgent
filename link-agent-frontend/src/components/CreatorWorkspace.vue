<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  analyzeCreatorFeedback,
  analyzePrePublish,
  createCreatorTask,
  getCreatorFeedback,
  getCreatorFeedbackReport,
  getCreatorTask,
  getPrePublishSuggestion,
  listCreatorTasks,
  saveCreatorFeedback,
} from '@/api/creator'
import type {
  CreatorFeedback,
  CreatorFeedbackReport,
  CreatorSuggestion,
  CreatorTask,
  CreatorTaskSummary,
} from '@/types/creator'

type UnknownRecord = Record<string, unknown>
type GuidanceEditorTarget = 'prePublish' | 'feedback'

const guidanceStorageKey = 'link-agent-creator-guidance'
const legacyPromptStorageKey = 'link-agent-creator-system-prompts'
const defaultPrePublishGuidance =
  '标题表达克制、具体，优先说明视频能解决的问题；先总结核心卖点，再给出优化建议；避免夸张措辞。'
const defaultFeedbackGuidance =
  '先归纳观众最关注的问题，再分析争议和误解；建议应能直接转化为下一期选题或互动动作。'

const taskForm = reactive({
  userId: '',
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
const activeStep = ref('task')
const isLoadingTasks = ref(false)
const isCreatingTask = ref(false)
const isAnalyzingPrePublish = ref(false)
const isSavingFeedback = ref(false)
const isAnalyzingFeedback = ref(false)
const guidanceEditorTarget = ref<GuidanceEditorTarget | null>(null)
const isGuidanceBackdropPointerDown = ref(false)
const errorMessage = ref('')
const successMessage = ref('')

const selectedTaskId = computed(() => selectedTask.value?.taskId ?? '')
const hasSelectedTask = computed(() => selectedTaskId.value.length > 0)
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
  void refreshTasks()
})

async function refreshTasks() {
  isLoadingTasks.value = true
  errorMessage.value = ''
  try {
    tasks.value = await listCreatorTasks(taskForm.userId || 'default', 20)
    const firstTask = tasks.value[0]
    if (!selectedTask.value && firstTask) {
      await selectTask(firstTask.taskId)
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
      userId: taskForm.userId,
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
    selectedTask.value = await getCreatorTask(taskId)
    activeStep.value = 'prePublish'
    await loadOptionalResults(taskId)
  } catch (error) {
    showError(error)
  }
}

async function loadOptionalResults(taskId: string) {
  suggestion.value = await optionalRequest(() => getPrePublishSuggestion(taskId))
  feedback.value = await optionalRequest(() => getCreatorFeedback(taskId))
  feedbackReport.value = await optionalRequest(() => getCreatorFeedbackReport(taskId))
}

async function runPrePublishAnalyze() {
  if (!selectedTaskId.value) {
    return
  }
  isAnalyzingPrePublish.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    suggestion.value = await analyzePrePublish(selectedTaskId.value, {
      customGuidance: prePublishForm.customGuidance,
      creatorPreference: prePublishForm.creatorPreference,
      titleStyle: prePublishForm.titleStyle,
      extraRequirement: prePublishForm.extraRequirement,
    })
    selectedTask.value = await getCreatorTask(selectedTaskId.value)
    activeStep.value = 'feedback'
    successMessage.value = '发布前优化完成，已保存标题、简介和标签建议。'
    await refreshTasks()
  } catch (error) {
    showError(error)
  } finally {
    isAnalyzingPrePublish.value = false
  }
}

async function submitFeedback() {
  if (!selectedTaskId.value) {
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
  if (!selectedTaskId.value) {
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

function hasText(value: string) {
  return value.trim().length > 0
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
            :disabled="!hasSelectedTask"
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
              <span>用户 ID</span>
              <input v-model="taskForm.userId" type="text" maxlength="64" placeholder="默认用户" />
            </label>
            <label>
              <span>任务名称</span>
              <input v-model="taskForm.taskName" type="text" maxlength="128" placeholder="填写本期视频主题" />
            </label>
            <label>
              <span>标题草稿</span>
              <input v-model="taskForm.titleDraft" type="text" maxlength="200" placeholder="输入一个粗标题" />
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
              <textarea v-model="taskForm.subtitle" maxlength="20000" placeholder="可选：粘贴字幕文本"></textarea>
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
                :disabled="!hasSelectedTask || isAnalyzingPrePublish"
                @click="runPrePublishAnalyze"
              >
                {{ isAnalyzingPrePublish ? '分析中...' : '生成建议' }}
              </button>
            </div>
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
                  <p v-if="getRecordText(item, 'reason')">理由：{{ getRecordText(item, 'reason') }}</p>
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
                :disabled="!hasSelectedTask || !hasFeedbackSampleInput || isSavingFeedback"
                @click="submitFeedback"
              >
                {{ isSavingFeedback ? '保存中...' : '保存样例' }}
              </button>
              <button
                type="button"
                class="creator-primary-button"
                :disabled="!hasSelectedTask || isAnalyzingFeedback"
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
                  <p v-if="getRecordText(item, 'evidence')">依据：{{ getRecordText(item, 'evidence') }}</p>
                  <p v-if="getRecordText(item, 'suggestion')">建议：{{ getRecordText(item, 'suggestion') }}</p>
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
      <section class="creator-prompt-modal" role="dialog" aria-modal="true" :aria-label="guidanceEditorTitle">
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
