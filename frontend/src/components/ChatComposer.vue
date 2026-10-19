<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { ArrowUp, Square } from '@lucide/vue'

const inputMessage = defineModel<string>({ required: true })

const props = withDefaults(defineProps<{
  canSend: boolean
  isLoading: boolean
  isStreaming?: boolean
  placeholder?: string
}>(), { placeholder: '输入一个能暴露推理过程的问题，例如：帮我拆解这个功能的实现步骤...' })

const emit = defineEmits<{
  sendMessage: []
  stopStreaming: []
}>()

const inputRef = ref<HTMLTextAreaElement | null>(null)

function focusInput() {
  inputRef.value?.focus({ preventScroll: true })
}

function adjustInputHeight() {
  void nextTick(() => {
    const el = inputRef.value
    if (!el) {
      return
    }

    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, 120)}px`
  })
}

function submitMessage() {
  if (props.canSend) emit('sendMessage')
}

function handleEnter(event: KeyboardEvent) {
  // 中文输入法用回车确认候选词时不能把半句话直接发送。
  if (event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  submitMessage()
}

defineExpose({
  adjustInputHeight,
  focusInput,
})
</script>

<template>
  <form class="composer" @submit.prevent="submitMessage">
    <div class="composer-input">
      <textarea
        ref="inputRef"
        v-model="inputMessage"
        rows="2"
        aria-label="给创作助手发送消息"
        :placeholder="placeholder"
        @keydown.enter.exact="handleEnter"
        @input="adjustInputHeight"
      />
    </div>
    <div class="composer-toolbar">
      <span class="composer-hint"><span v-if="inputMessage.trim()">{{ inputMessage.trim().length }} 字 · </span>Enter 发送，Shift + Enter 换行</span>
    <button
      v-if="isStreaming"
      type="button"
      class="stop-btn"
      aria-label="停止生成"
      @click="emit('stopStreaming')"
    >
      <Square :size="13" fill="currentColor" aria-hidden="true" />停止
    </button>
    <button v-else type="submit" class="composer-send" :disabled="!canSend" aria-label="发送消息">
      {{ isLoading ? '思考中' : '发送' }}<ArrowUp :size="16" aria-hidden="true" />
    </button>
    </div>
  </form>
</template>

<style scoped>
.composer {
  display: flex;
  flex: 0 0 auto;
  flex-direction: column;
  gap: 8px;
  padding: 12px 16px;
  background: var(--surface);
  border-top: 1px solid var(--border);
  backdrop-filter: none;
}
.composer-input { min-width: 0; }
.composer-input textarea {
  display: block;
  width: 100%;
  min-height: 64px;
  max-height: 120px;
  padding: 10px 12px;
  resize: none;
  color: var(--ink);
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: 10px;
  box-shadow: none;
  font: inherit;
  font-size: 13px;
  line-height: 1.6;
}
.composer-input textarea:focus { border-color: var(--accent); background: var(--surface); box-shadow: 0 0 0 3px var(--accent-ring); }
.composer-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.composer-hint { color: var(--faint); font-size: 10px; line-height: 1.5; }
.composer-toolbar button {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-width: 68px;
  min-height: 32px;
  padding: 0 12px;
  border-radius: 8px;
  box-shadow: none;
  font-size: 12px;
}
.composer-toolbar .stop-btn { background: var(--danger); }
@media (max-width: 480px) {
  .composer { padding: 10px 12px; }
  .composer-hint { max-width: 150px; }
}
</style>
