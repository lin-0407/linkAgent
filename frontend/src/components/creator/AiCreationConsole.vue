<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import {
  confirmCreativeOption,
  createInteractiveTask,
  regenerateCreativeOptions,
} from '@/api/creator'
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

const form = reactive({
  idea: '',
  videoType: '未分类',
  extraRequirement: '',
})

const interactiveTask = ref<InteractiveTask | null>(null)
const selectedOptionId = ref('')
const errorMessage = ref('')
const isCreating = ref(false)
const isRegenerating = ref(false)
const isConfirming = ref(false)

const canSubmit = computed(() => form.idea.trim().length >= 10 && !isCreating.value)
const options = computed(() => interactiveTask.value?.options ?? [])
const hasOptions = computed(() => options.value.length > 0)
const statusText = computed(() => {
  if (isCreating.value || isRegenerating.value) return 'AI 正在拆解想法'
  if (interactiveTask.value?.status === 'CREATIVE_CONFIRMED') return '创意方向已确认'
  if (hasOptions.value) return '等待选择一个方向'
  return '等待输入想法'
})

async function submitIdea() {
  if (!canSubmit.value) return
  isCreating.value = true
  errorMessage.value = ''
  selectedOptionId.value = ''
  try {
    interactiveTask.value = await createInteractiveTask({
      idea: form.idea.trim(),
      videoType: form.videoType || undefined,
    })
  } catch (error) {
    showError(error)
  } finally {
    isCreating.value = false
  }
}

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

function parseTextList(value: string | null) {
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    if (Array.isArray(parsed)) {
      return parsed.map((item) => formatValue(item)).filter(Boolean)
    }
    return [formatValue(parsed)].filter(Boolean)
  } catch {
    return [value]
  }
}

function formatValue(value: unknown) {
  if (typeof value === 'string') return value
  if (value === null || value === undefined) return ''
  return JSON.stringify(value)
}

function showError(error: unknown) {
  errorMessage.value = error instanceof Error ? error.message : '请求失败'
}
</script>

<template>
  <section class="ai-creation-console" aria-label="AI 创作台">
    <div class="ai-creation-header">
      <div>
        <p class="creator-kicker">AI 创作台</p>
        <h3>把你这期视频的想法告诉我</h3>
        <p>可以只写一句话，也可以写素材、目标观众、想表达的观点。</p>
      </div>
      <span class="ai-creation-status">{{ statusText }}</span>
    </div>

    <form class="ai-creation-composer" @submit.prevent="submitIdea">
      <label class="ai-creation-field ai-creation-field-main">
        <span>创作想法</span>
        <textarea
          v-model="form.idea"
          rows="5"
          maxlength="3000"
          placeholder="例如：我想做一期讲 Spring AI Agent 工作流的视频，面向会 Java 但没接触过 Agent 的人，希望别太学术。"
        />
      </label>
      <label class="ai-creation-field">
        <span>视频类型</span>
        <select v-model="form.videoType">
          <option v-for="option in videoTypeOptions" :key="option" :value="option">
            {{ option }}
          </option>
        </select>
      </label>
      <button type="submit" class="creator-primary-button" :disabled="!canSubmit">
        {{ isCreating ? '生成中...' : '生成 3 个方向' }}
      </button>
    </form>

    <p v-if="errorMessage" class="ai-creation-error" role="alert">{{ errorMessage }}</p>

    <div v-if="isCreating || isRegenerating" class="ai-creation-loading" aria-live="polite">
      <span />
      <p>AI 正在整理标题大纲、内容大纲和简介大纲。</p>
    </div>

    <div v-if="hasOptions" class="ai-creation-options" aria-label="AI 创意卡片">
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

    <div v-if="hasOptions" class="ai-creation-regenerate">
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
  </section>
</template>
