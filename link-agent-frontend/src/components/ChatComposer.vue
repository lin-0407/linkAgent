<script setup lang="ts">
import { nextTick, ref } from 'vue'

const inputMessage = defineModel<string>({ required: true })

defineProps<{
  canSend: boolean
  isLoading: boolean
}>()

defineEmits<{
  sendMessage: []
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
        placeholder="把问题交给 Link Agent，例如：帮我拆解这个功能的实现步骤..."
        @keydown.enter.exact.prevent="$emit('sendMessage')"
        @input="adjustInputHeight"
      />
      <span>{{ inputMessage.trim().length }} 字 · Enter 发送，Shift + Enter 换行</span>
    </div>
    <button type="submit" :disabled="!canSend">
      {{ isLoading ? '思考中' : '发送' }}
    </button>
  </form>
</template>
