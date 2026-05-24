<script setup lang="ts">
import { ref } from 'vue'
import type { ChatMessage } from '@/types/agent'
import EmptyState from './EmptyState.vue'
import MessageBubble from './MessageBubble.vue'

defineProps<{
  capabilityTags: string[]
  isLoading: boolean
  messages: ChatMessage[]
  promptExamples: string[]
}>()

defineEmits<{
  usePromptExample: [example: string]
}>()

const listRef = ref<HTMLElement | null>(null)

defineExpose({
  get scrollHeight() {
    return listRef.value?.scrollHeight ?? 0
  },
  scrollTo(options: ScrollToOptions) {
    listRef.value?.scrollTo(options)
  },
})
</script>

<template>
  <div ref="listRef" class="message-list">
    <EmptyState
      v-if="messages.length === 0"
      :capability-tags="capabilityTags"
      :prompt-examples="promptExamples"
      @use-prompt-example="$emit('usePromptExample', $event)"
    />

    <MessageBubble v-for="message in messages" :key="message.id" :message="message" />

    <div v-if="isLoading" class="message assistant">
      <div class="avatar">A</div>
      <div class="bubble loading animated" aria-label="Thinking">
        <span class="thinking-text">Agent 正在组织思路</span>
        <span class="thinking-dots" aria-hidden="true">
          <i></i>
          <i></i>
          <i></i>
        </span>
      </div>
    </div>
  </div>
</template>
