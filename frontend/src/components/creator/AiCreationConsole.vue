<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import {
  confirmCreativeOption,
  createInteractiveTask,
  generateCreativeOptions,
  regenerateCreativeOptions,
  triggerUnderstanding,
  uploadContextDocuments,
} from '@/api/creator'
import { parseJsonArray } from '@/composables/creator/creatorWorkspaceUtils'
import type { CreativeIdeaOption, InteractiveTask } from '@/types/creator'

const emit = defineEmits<{
  confirmed: [taskId: string]
}>()

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

// ═══════════════════════════════════════════
// 表单状态
// ═══════════════════════════════════════════

const form = reactive({
  idea: '',
  videoType: '未分类',
  extraRequirement: '',
})

const interactiveTask = ref<InteractiveTask | null>(null)
const selectedOptionId = ref('')
const errorMessage = ref('')
const isCreating = ref(false)
const isUnderstanding = ref(false)
const isGenerating = ref(false)
const isRegenerating = ref(false)
const isConfirming = ref(false)
const isUploading = ref(false)
const activePanel = ref<'idea' | 'context' | 'direction'>('idea')

// ═══════════════════════════════════════════
// 文件上传
// ═══════════════════════════════════════════

/** 用户已上传的文件列表（记录文件名和大小用于展示） */
const uploadedFiles = ref<{ name: string; size: number }[]>([])
/** 拖拽悬停状态，用于高亮上传区域 */
const isDragging = ref(false)
/** 文件上传 input 引用 */
const fileInputRef = ref<HTMLInputElement | null>(null)
/** 预览弹窗状态 */
const previewModalVisible = ref(false)
const previewFileName = ref('')
const previewText = ref('')

/** 文件大小限制：10 MB */
const MAX_FILE_SIZE = 10 * 1024 * 1024
/** 支持的文件扩展名 */
const ALLOWED_EXTENSIONS = ['.pdf', '.docx', '.doc', '.pptx', '.ppt', '.txt', '.md', '.markdown', '.html', '.htm', '.rtf', '.odt', '.epub']

function removeFile(index: number) {
  uploadedFiles.value.splice(index, 1)
}

/** 打开文件预览弹窗——复用 session 的 backgroundContext 展示提取的文本 */
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

// ═══════════════════════════════════════════
// 计算属性
// ═══════════════════════════════════════════

const canSubmitIdea = computed(() => form.idea.trim().length >= 10 && !isCreating.value)
const options = computed(() => interactiveTask.value?.options ?? [])
const hasOptions = computed(() => options.value.length > 0)
const understandingReady = computed(() =>
  interactiveTask.value?.understandingStatus === 'READY'
  || interactiveTask.value?.understandingStatus === 'CONFIRMED',
)
const canGenerate = computed(() => understandingReady.value && !isGenerating.value)
const hasBackgroundContext = computed(() =>
  (interactiveTask.value?.backgroundContext?.length ?? 0) > 0,
)

const statusText = computed(() => {
  if (isCreating.value) return '正在创建任务...'
  if (isUploading.value) return '正在上传并解析文档...'
  if (isUnderstanding.value) return 'AI 正在理解你的想法...'
  if (isGenerating.value || isRegenerating.value) return 'AI 正在拆解想法'
  if (interactiveTask.value?.understandingStatus === 'CONFIRMED'
      && interactiveTask.value?.status === 'CREATIVE_OPTIONS_READY') return '方向卡已生成'
  if (interactiveTask.value?.status === 'CREATIVE_CONFIRMED') return '创意方向已确认'
  if (understandingReady.value) return 'AI 已理解，可以生成方向卡'
  if (interactiveTask.value?.status === 'IDEA_INPUT') return '可以上传补充资料或直接让 AI 理解'
  return '等待输入想法'
})

// ═══════════════════════════════════════════
// 流程：1. 创建任务 → 2. 上传文档(可选) → 3. AI理解 → 4. 生成方向卡
// ═══════════════════════════════════════════

/** Step 1: 输入想法，创建交互式创作任务 */
async function submitIdea() {
  if (!canSubmitIdea.value) return
  isCreating.value = true
  errorMessage.value = ''
  selectedOptionId.value = ''
  try {
    interactiveTask.value = await createInteractiveTask({
      idea: form.idea.trim(),
      videoType: form.videoType || undefined,
    })
    activePanel.value = 'context'
  } catch (error) {
    showError(error)
  } finally {
    isCreating.value = false
  }
}

/** Step 2: 上传补充背景文档文件 */
async function handleFilesUpload(fileList: FileList | null) {
  if (!fileList || fileList.length === 0 || !interactiveTask.value) return
  const files = Array.from(fileList).filter((file) => {
    if (file.size > MAX_FILE_SIZE) {
      errorMessage.value = `文件 "${file.name}" 超过 10 MB 限制`
      return false
    }
    const lowerName = file.name.toLowerCase()
    if (!ALLOWED_EXTENSIONS.some((ext) => lowerName.endsWith(ext))) {
      errorMessage.value = `文件 "${file.name}" 格式不支持`
      return false
    }
    return true
  })
  if (files.length === 0) return

  isUploading.value = true
  errorMessage.value = ''
  try {
    const result = await uploadContextDocuments(interactiveTask.value.taskId, files)
    interactiveTask.value = result
    // 记录已上传的文件名
    uploadedFiles.value.push(...files.map((f) => ({ name: f.name, size: f.size })))
  } catch (error) {
    showError(error)
  } finally {
    isUploading.value = false
  }
}

function onFileInputChange(event: Event) {
  const input = event.target as HTMLInputElement
  handleFilesUpload(input.files)
  // 重置 input 值，允许重复上传同名文件
  input.value = ''
}

/** Step 3: AI 理解确认 */
async function submitUnderstanding() {
  if (!interactiveTask.value || isUnderstanding.value) return
  isUnderstanding.value = true
  errorMessage.value = ''
  try {
    interactiveTask.value = await triggerUnderstanding(interactiveTask.value.taskId)
    activePanel.value = 'direction'
  } catch (error) {
    showError(error)
  } finally {
    isUnderstanding.value = false
  }
}

/** Step 4: 生成创意方向卡 */
async function submitGenerateOptions() {
  if (!canGenerate.value || !interactiveTask.value) return
  isGenerating.value = true
  errorMessage.value = ''
  selectedOptionId.value = ''
  try {
    interactiveTask.value = await generateCreativeOptions(interactiveTask.value.taskId, {
      extraRequirement: form.extraRequirement.trim() || undefined,
    })
    activePanel.value = 'direction'
  } catch (error) {
    showError(error)
  } finally {
    isGenerating.value = false
  }
}

/** 重新生成方向卡（微调） */
async function regenerateOptions() {
  if (!interactiveTask.value || isRegenerating.value) return
  isRegenerating.value = true
  errorMessage.value = ''
  selectedOptionId.value = ''
  try {
    interactiveTask.value = await regenerateCreativeOptions(interactiveTask.value.taskId, {
      extraRequirement: form.extraRequirement.trim() || undefined,
    })
  } catch (error) {
    showError(error)
  } finally {
    isRegenerating.value = false
  }
}

/** 确认选中某张方向卡 */
async function confirmOption(option: CreativeIdeaOption) {
  if (!interactiveTask.value || isConfirming.value) return
  isConfirming.value = true
  selectedOptionId.value = option.optionId
  errorMessage.value = ''
  try {
    const result = await confirmCreativeOption(interactiveTask.value.taskId, option.optionId)
    interactiveTask.value = result
    emit('confirmed', result.taskId)
  } catch (error) {
    showError(error)
  } finally {
    isConfirming.value = false
  }
}

// ═══════════════════════════════════════════
// 工具函数
// ═══════════════════════════════════════════

function parseTextList(value: string | null) {
  return parseJsonArray(value).map(formatValue).filter(Boolean)
}

function formatValue(value: unknown) {
  if (typeof value === 'string') return value
  if (value === null || value === undefined) return ''
  return JSON.stringify(value)
}

function showError(error: unknown) {
  errorMessage.value = error instanceof Error ? error.message : '请求失败'
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/** 拖拽事件 —— 阻止默认行为以支持拖放上传 */
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
  handleFilesUpload(event.dataTransfer?.files ?? null)
}
</script>

<template>
  <section class="ai-creation-console" aria-label="AI 创作台">
    <!-- ═══════════ 顶部状态栏 ═══════════ -->
    <div class="ai-creation-header">
      <div>
        <p class="creator-kicker">AI 创作台</p>
        <h3>把你这期视频的想法告诉我</h3>
        <p>可以只写一句话，也可以写素材、目标观众、想表达的观点。</p>
      </div>
      <span class="ai-creation-status">{{ statusText }}</span>
    </div>

    <nav class="ai-creation-tabs" aria-label="创作准备步骤" role="tablist">
      <button
        type="button"
        role="tab"
        :aria-selected="activePanel === 'idea'"
        :class="{ active: activePanel === 'idea' }"
        @click="activePanel = 'idea'"
      >
        创作输入
      </button>
      <button
        type="button"
        role="tab"
        :aria-selected="activePanel === 'context'"
        :class="{ active: activePanel === 'context' }"
        @click="activePanel = 'context'"
      >
        补充资料
      </button>
      <button
        type="button"
        role="tab"
        :aria-selected="activePanel === 'direction'"
        :class="{ active: activePanel === 'direction' }"
        @click="activePanel = 'direction'"
      >
        创作方向
      </button>
    </nav>

    <div class="ai-creation-panel" role="tabpanel">
      <form v-if="activePanel === 'idea'" class="ai-creation-composer" @submit.prevent="submitIdea">
        <label class="ai-creation-field ai-creation-field-main">
          <span>创作想法</span>
          <textarea
            v-model="form.idea"
            rows="3"
            maxlength="3000"
            :disabled="interactiveTask !== null"
            placeholder="例如：演示 LinkAgent 项目，让更多开发者了解并使用"
          />
        </label>
        <label class="ai-creation-field">
          <span>视频类型</span>
          <select v-model="form.videoType" :disabled="interactiveTask !== null">
            <option v-for="option in videoTypeOptions" :key="option" :value="option">{{ option }}</option>
          </select>
        </label>
        <button v-if="!interactiveTask" type="submit" class="creator-primary-button" :disabled="!canSubmitIdea">
          {{ isCreating ? '创建任务中...' : '提交想法' }}
        </button>
        <button v-else type="button" class="creator-secondary-action" @click="activePanel = 'context'">
          继续补充资料
        </button>
      </form>

      <div v-else-if="activePanel === 'context'" class="ai-creation-context-panel">
        <div v-if="!interactiveTask" class="ai-creation-empty-panel">
          <strong>补充资料</strong>
          <p>提交创作想法后，可在这里上传项目文档和背景资料。</p>
          <button type="button" class="creator-secondary-action" @click="activePanel = 'idea'">填写创作想法</button>
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
              <span>补充背景资料（可选）</span>
              <span class="upload-hint">项目文档、竞品分析或其它创作背景</span>
            </div>
            <label class="upload-zone-body">
              <input
                ref="fileInputRef"
                type="file"
                multiple
                accept=".pdf,.docx,.doc,.pptx,.ppt,.txt,.md,.markdown,.html,.htm,.rtf,.odt,.epub"
                :disabled="isUploading"
                @change="onFileInputChange"
              />
              <span>{{ isUploading ? '正在解析文档...' : '点击或拖拽上传文档' }}</span>
            </label>
            <ul v-if="uploadedFiles.length > 0" class="uploaded-file-list">
              <li v-for="(file, index) in uploadedFiles" :key="`${file.name}-${index}`">
                <button
                  type="button"
                  class="file-name-btn"
                  :disabled="!hasBackgroundContext"
                  :title="hasBackgroundContext ? '点击预览提取内容' : '暂无提取内容'"
                  @click="openPreview(file.name)"
                >
                  {{ file.name }}
                </button>
                <span class="file-size">{{ formatFileSize(file.size) }}</span>
                <button type="button" class="file-remove-btn" title="移除" :disabled="isUploading" @click="removeFile(index)">
                  移除
                </button>
              </li>
            </ul>
          </div>
          <div class="ai-creation-understanding">
            <button type="button" class="creator-primary-button" :disabled="isUnderstanding" @click="submitUnderstanding">
              {{ isUnderstanding ? 'AI 正在理解...' : understandingReady ? '重新理解' : '让 AI 理解' }}
            </button>
            <button v-if="understandingReady" type="button" class="creator-secondary-action" @click="activePanel = 'direction'">
              查看创作方向
            </button>
          </div>
          <div v-if="isUnderstanding" class="ai-creation-loading" aria-live="polite">
            <span />
            <p>AI 正在阅读想法和补充资料。</p>
          </div>
        </template>
      </div>

      <div v-else class="ai-creation-direction-panel">
        <div v-if="!understandingReady" class="ai-creation-empty-panel">
          <strong>创作方向</strong>
          <p>{{ interactiveTask ? '先让 AI 理解想法，再生成可选方向。' : '提交想法后，这里会承接 AI 的理解和创作方向。' }}</p>
          <button type="button" class="creator-secondary-action" @click="activePanel = interactiveTask ? 'context' : 'idea'">
            {{ interactiveTask ? '前往 AI 理解' : '填写创作想法' }}
          </button>
        </div>
        <template v-else>
          <div v-if="interactiveTask?.understandingSummary" class="understanding-result">
            <div class="understanding-result-header"><span>AI 理解摘要</span></div>
            <pre class="understanding-content">{{ interactiveTask.understandingSummary }}</pre>
          </div>
          <div v-if="!hasOptions" class="ai-creation-generate">
            <label class="ai-creation-field">
              <span>微调要求（可选）</span>
              <input v-model="form.extraRequirement" maxlength="2000" placeholder="例如：更适合新手，标题别太像教程课" />
            </label>
            <button type="button" class="creator-primary-button" :disabled="!canGenerate" @click="submitGenerateOptions">
              {{ isGenerating ? '生成中...' : '生成 3 个方向' }}
            </button>
          </div>
          <div v-if="isGenerating" class="ai-creation-loading" aria-live="polite">
            <span />
            <p>AI 正在整理标题、内容和简介方向。</p>
          </div>
        </template>
      </div>

      <div
        v-if="activePanel === 'direction' && hasOptions"
        class="ai-creation-options"
        aria-label="AI 创意卡片"
      >
        <article
          v-for="(option, index) in options"
          :key="option.optionId"
          class="ai-creation-option"
          :class="{ selected: option.selected || selectedOptionId === option.optionId }"
        >
          <header>
            <span>方向 {{ index + 1 }}</span>
            <h4>{{ option.optionName }}</h4>
            <p>{{ option.targetAudience || '适合对这个主题感兴趣的观众' }}</p>
          </header>

        <div class="ai-creation-section">
          <strong>标题大纲</strong>
          <ul>
            <li
              v-for="(item, itemIndex) in parseTextList(option.titleOutline)"
              :key="`${option.optionId}-title-${itemIndex}`"
            >
              {{ item }}
            </li>
          </ul>
        </div>

        <div class="ai-creation-section">
          <strong>内容大纲</strong>
          <ul>
            <li
              v-for="(item, itemIndex) in parseTextList(option.contentOutline)"
              :key="`${option.optionId}-content-${itemIndex}`"
            >
              {{ item }}
            </li>
          </ul>
        </div>

        <div class="ai-creation-section">
          <strong>简介大纲</strong>
          <ul>
            <li
              v-for="(item, itemIndex) in parseTextList(option.descriptionOutline)"
              :key="`${option.optionId}-description-${itemIndex}`"
            >
              {{ item }}
            </li>
          </ul>
        </div>

        <div class="ai-creation-meta">
          <div>
            <strong>亮点</strong>
            <span>{{ parseTextList(option.sellingPoints).join(' / ') || '贴合原始想法' }}</span>
          </div>
          <div>
            <strong>风险</strong>
            <span>{{ parseTextList(option.riskPoints).join(' / ') || '需要后续补充素材' }}</span>
          </div>
        </div>

        <p class="ai-creation-reason">{{ option.recommendReason || 'AI 建议先从这个方向继续细化。' }}</p>

        <button
          type="button"
          class="creator-primary-button"
          :disabled="isConfirming"
          @click="confirmOption(option)"
        >
          {{ isConfirming && selectedOptionId === option.optionId ? '确认中...' : '选择这个方向' }}
        </button>
        </article>
      </div>

      <!-- 方向卡微调 -->
      <div v-if="activePanel === 'direction' && hasOptions" class="ai-creation-regenerate">
        <label class="ai-creation-field">
          <span>让 AI 微调</span>
          <input
            v-model="form.extraRequirement"
            maxlength="2000"
            placeholder="例如：更适合新手，标题别太像教程课"
          />
        </label>
        <button
          type="button"
          class="creator-secondary-action"
          :disabled="isRegenerating || isConfirming"
          @click="regenerateOptions"
        >
          {{ isRegenerating ? '调整中...' : '重新生成' }}
        </button>
      </div>
    </div>

    <p v-if="errorMessage" class="ai-creation-error" role="alert">{{ errorMessage }}</p>

    <!-- ═══════════ 文档内容预览弹窗 ═══════════ -->
    <Teleport to="body">
      <div v-if="previewModalVisible" class="preview-modal-overlay" @click.self="closePreview">
        <div class="preview-modal">
          <div class="preview-modal-header">
            <h4>📄 {{ previewFileName }}</h4>
            <button type="button" class="preview-modal-close" @click="closePreview">✕</button>
          </div>
          <div class="preview-modal-body">
            <pre class="preview-content">{{ previewText }}</pre>
          </div>
        </div>
      </div>
    </Teleport>
  </section>
</template>
