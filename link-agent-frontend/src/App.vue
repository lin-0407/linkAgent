<script setup lang="ts">
import { nextTick, ref } from 'vue'
import 'katex/dist/katex.min.css'
import AgentSidebar from '@/components/AgentSidebar.vue'
import ChatComposer from '@/components/ChatComposer.vue'
import ErrorNotice from '@/components/ErrorNotice.vue'
import MessageList from '@/components/MessageList.vue'
import TopBar from '@/components/TopBar.vue'
import { useAgentChat } from '@/composables/useAgentChat'

const promptExamples = [
  '我叫 Link，请记住这个信息。稍后我会问你。',
  '请用 ReAct 思路帮我拆解一个 Spring AI 学习计划。',
  '帮我计算 128 * 37，并告诉我你是否调用了工具。',
]
const capabilityTags = ['短期记忆', '工具调用', 'ReAct 轨迹', 'Markdown / 公式']
const composerRef = ref<InstanceType<typeof ChatComposer> | null>(null)

const {
  activeSessionLabel,
  assistantMessageCount,
  canSend,
  errorMessage,
  inputMessage,
  isLoading,
  isSessionsLoading,
  isSessionsOpen,
  latestStepCount,
  messageListRef,
  messages,
  openSession,
  sendMessage,
  sessionId,
  sessions,
  sessionsError,
  startNewSession,
  userMessageCount,
} = useAgentChat()

function usePromptExample(example: string) {
  inputMessage.value = example
  void nextTick(() => {
    composerRef.value?.adjustInputHeight()
    composerRef.value?.focusInput()
  })
}
</script>

<template>
  <main class="app-shell">
    <AgentSidebar
      :active-session-label="activeSessionLabel"
      :is-loading="isLoading"
      :is-sessions-loading="isSessionsLoading"
      :is-sessions-open="isSessionsOpen"
      :latest-step-count="latestStepCount"
      :message-count="messages.length"
      :session-id="sessionId"
      :sessions="sessions"
      :sessions-error="sessionsError"
      @open-session="openSession"
      @start-new-session="startNewSession"
      @toggle-sessions="isSessionsOpen = !isSessionsOpen"
    />

    <section class="workspace">
      <TopBar
        :assistant-message-count="assistantMessageCount"
        :active-session-label="activeSessionLabel"
        :is-loading="isLoading"
        :latest-step-count="latestStepCount"
        :message-count="messages.length"
        :session-count="sessions.length"
        :session-id="sessionId"
        :user-message-count="userMessageCount"
      />

      <MessageList
        ref="messageListRef"
        :capability-tags="capabilityTags"
        :is-loading="isLoading"
        :messages="messages"
        :prompt-examples="promptExamples"
        @use-prompt-example="usePromptExample"
      />

      <ErrorNotice :error-message="errorMessage" />

      <ChatComposer
        ref="composerRef"
        v-model="inputMessage"
        :can-send="canSend"
        :is-loading="isLoading"
        @send-message="sendMessage"
      />
    </section>
  </main>
</template>
