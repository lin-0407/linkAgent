<script setup lang="ts">
import { nextTick, ref } from 'vue'

const inputMessage = defineModel<string>({ required: true })

defineProps<{
  canSend: boolean
  isLoading: boolean
  isStreaming?: boolean
}>()

const emit = defineEmits<{
  sendMessage: []
  stopStreaming: []
}>()

const inputRef = ref<HTMLTextAreaElement | null>(null)

function focusInput() {
  inputRef.value?.focus()
}

function adjustInputHeight() {
  void nextTick(() => {
    const el = inputRef.value
    if (!el) {
      return
    }

    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, 180)}px`
  })
}

defineExpose({
  adjustInputHeight,
  focusInput,
})
</script>

<template>
  <form class="composer" @submit.prevent="$emit('sendMessage')">
    <div class="input-wrap">
      <textarea
        ref="inputRef"
        v-model="inputMessage"
        rows="3"
        placeholder="输入一个能暴露推理过程的问题，例如：帮我拆解这个功能的实现步骤..."
        @keydown.enter.exact.prevent="$emit('sendMessage')"
        @input="adjustInputHeight"
      />
      <span>{{ inputMessage.trim().length }} 字 · Enter 发送，Shift + Enter 换行</span>
    </div>
    <button
      v-if="isStreaming"
      type="button"
      class="stop-btn"
      @click="emit('stopStreaming')"
    >
      停止生成
    </button>
    <button v-else type="submit" :disabled="!canSend">
      {{ isLoading ? '思考中' : '发送' }}
    </button>
  </form>
</template>
