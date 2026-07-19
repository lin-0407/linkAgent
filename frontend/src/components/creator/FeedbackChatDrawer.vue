<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import MessageBubble from '@/components/MessageBubble.vue'
import type { ChatMessage } from '@/types/agent'
import type { CreatorFeedbackChatTurn } from '@/types/creator'

const props = defineProps<{
  open: boolean
  turns: CreatorFeedbackChatTurn[]
  question: string
  canAsk: boolean
  asking: boolean
}>()

const emit = defineEmits<{
  close: []
  'update:question': [question: string]
  ask: []
}>()

const threadRef = ref<HTMLElement | null>(null)

function updateQuestion(event: Event) {
  emit('update:question', (event.target as HTMLTextAreaElement).value)
}

function questionMessage(turn: CreatorFeedbackChatTurn, index: number): ChatMessage {
  return { id: index * 2 + 1, role: 'user', content: turn.question }
}

function answerMessage(turn: CreatorFeedbackChatTurn, index: number): ChatMessage {
  let content = '正在基于当前反馈报告和评论弹幕证据生成回答...'
  if (turn.result) {
    content = turn.result.answer
  } else if (turn.status === 'FAILED') {
    content = `追问失败：${turn.errorMessage || '请稍后重试。'}`
  }
  return { id: index * 2 + 2, role: 'assistant', content }
}

async function scrollToBottom() {
  await nextTick()
  if (threadRef.value) threadRef.value.scrollTop = threadRef.value.scrollHeight
}

watch(
  [() => props.open, () => props.turns],
  ([open]) => {
    if (open) void scrollToBottom()
  },
  { flush: 'post' },
)
</script>

<template>
  <aside v-if="props.open" class="creator-feedback-drawer" aria-label="反馈追问">
    <header class="creator-feedback-drawer-head">
      <div>
        <h3>反馈追问</h3>
      </div>
      <button type="button" class="creator-ghost-button" @click="emit('close')">
        关闭
      </button>
    </header>

    <div class="creator-feedback-drawer-body">
      <section
        ref="threadRef"
        class="message-list creator-feedback-chat-thread"
        aria-label="反馈追问对话"
      >
        <template v-if="props.turns.length > 0">
          <template v-for="(turn, index) in props.turns" :key="turn.id">
            <MessageBubble :message="questionMessage(turn, index)" />
            <MessageBubble :message="answerMessage(turn, index)" />
          </template>
        </template>
        <div v-else class="creator-feedback-chat-empty">
          <strong>还没有追问</strong>
          <p>输入一个和本次反馈报告相关的问题，系统会结合报告与评论弹幕证据回答。</p>
        </div>
      </section>
    </div>

    <form class="creator-feedback-chat-composer" @submit.prevent="emit('ask')">
      <textarea
        :value="props.question"
        maxlength="1000"
        placeholder="向当前报告追问..."
        @input="updateQuestion"
        @keydown.ctrl.enter.prevent="emit('ask')"
      ></textarea>
      <button
        type="submit"
        class="creator-primary-button"
        :disabled="!props.canAsk"
      >
        {{ props.asking ? '生成中...' : '追问' }}
      </button>
    </form>
  </aside>
</template>
