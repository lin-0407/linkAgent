import { computed, nextTick, ref, watch } from 'vue'
import {
  loadAgentSessionMessages,
  loadAgentSessions,
  sendAgentMessage,
} from '@/api/agent'
import type { AgentExecutionMode, ChatMessage, SessionListItem } from '@/types/agent'

const selectedSessionKey = 'link-agent-session-id'

type ScrollContainer = {
  scrollHeight: number
  scrollTo: (options: ScrollToOptions) => void
}

type SendMessageOptions = {
  outboundMessage?: string
  displayMessage?: string
}

export function useAgentChat() {
  const inputMessage = ref('')
  const sessionId = ref('')
  const sessions = ref<SessionListItem[]>([])
  const messages = ref<ChatMessage[]>([])
  const isLoading = ref(false)
  const isSessionsLoading = ref(false)
  const isSessionsOpen = ref(false)
  const executionMode = ref<AgentExecutionMode>('AUTO')
  const errorMessage = ref('')
  const sessionsError = ref('')
  const messageListRef = ref<ScrollContainer | null>(null)

  const canSend = computed(() => inputMessage.value.trim().length > 0 && !isLoading.value)
  const activeSessionLabel = computed(() => sessionId.value || 'new session')
  const assistantMessageCount = computed(
    () => messages.value.filter((message) => message.role === 'assistant').length,
  )
  const userMessageCount = computed(
    () => messages.value.filter((message) => message.role === 'user').length,
  )
  const latestStepCount = computed(() => {
    const latestAssistantMessage = [...messages.value]
      .reverse()
      .find((message) => message.role === 'assistant' && message.steps?.length)

    return latestAssistantMessage?.steps?.length ?? 0
  })

  loadPersistedSession()
  void loadSessions()

  watch(
    () => messages.value.length,
    async () => {
      await scrollToBottom()
    },
  )

  async function sendMessage(options: SendMessageOptions = {}) {
    const displayMessage = (options.displayMessage ?? inputMessage.value).trim()
    const outboundMessage = (options.outboundMessage ?? displayMessage).trim()
    if (!displayMessage || !outboundMessage || isLoading.value) {
      return
    }

    errorMessage.value = ''
    inputMessage.value = ''
    messages.value.push({
      id: Date.now(),
      role: 'user',
      content: displayMessage,
    })

    await scrollToBottom()
    isLoading.value = true

    try {
      // 前端可以把上下文拼进实际出站消息，但聊天窗口只显示用户原问题，避免界面被大段事实材料刷屏。
      const data = await sendAgentMessage(sessionId.value, outboundMessage, executionMode.value)
      sessionId.value = data.sessionId
      persistSessionId(data.sessionId)
      await loadSessions()
      messages.value.push({
        id: Date.now() + 1,
        role: 'assistant',
        content: data.finalAnswer || data.stopReason || 'Agent 没有返回内容',
        steps: data.steps,
        stopReason: data.stopReason,
        executionMode: data.executionMode,
        planTrace: data.planTrace,
        workerTraces: data.workerTraces,
      })
    } catch (error) {
      errorMessage.value = error instanceof Error ? error.message : '请求失败'
    } finally {
      isLoading.value = false
      await scrollToBottom()
    }
  }

  function startNewSession() {
    sessionId.value = ''
    messages.value = []
    errorMessage.value = ''
    inputMessage.value = ''
    clearPersistedSession()
  }

  async function loadSessions() {
    isSessionsLoading.value = true
    sessionsError.value = ''
    try {
      sessions.value = await loadAgentSessions()
    } catch (error) {
      sessions.value = []
      sessionsError.value = error instanceof Error ? error.message : 'Failed to load sessions'
    } finally {
      isSessionsLoading.value = false
    }
  }

  async function openSession(session: SessionListItem) {
    sessionId.value = session.sessionId
    persistSessionId(session.sessionId)
    errorMessage.value = ''
    isSessionsOpen.value = false
    await loadSessionMessages(session.sessionId)
    await loadSessions()
  }

  async function loadSessionMessages(targetSessionId: string) {
    try {
      const data = await loadAgentSessionMessages(targetSessionId)
      messages.value = data.map((item, index) => ({
        id: Date.now() + index,
        role: normalizeRole(item.role),
        content: item.content,
      }))
    } catch (error) {
      messages.value = []
      errorMessage.value =
        error instanceof Error ? error.message : 'Failed to load session messages'
    }
  }

  async function scrollToBottom() {
    await nextTick()
    const el = messageListRef.value
    if (!el) {
      return
    }

    el.scrollTo({
      top: el.scrollHeight,
      behavior: 'smooth',
    })
  }

  function persistSessionId(value: string) {
    localStorage.setItem(selectedSessionKey, value)
  }

  function clearPersistedSession() {
    localStorage.removeItem(selectedSessionKey)
  }

  function loadPersistedSession() {
    const saved = localStorage.getItem(selectedSessionKey)
    if (saved) {
      sessionId.value = saved
      void loadSessionMessages(saved)
    }
  }

  function normalizeRole(role: string): 'user' | 'assistant' {
    const normalized = role.trim().toLowerCase()
    if (normalized === 'assistant' || normalized === 'ai' || normalized === 'bot') {
      return 'assistant'
    }
    return 'user'
  }

  return {
    activeSessionLabel,
    assistantMessageCount,
    canSend,
    errorMessage,
    executionMode,
    inputMessage,
    isLoading,
    isSessionsLoading,
    isSessionsOpen,
    latestStepCount,
    messageListRef,
    messages,
    openSession,
    scrollToBottom,
    sendMessage,
    sessionId,
    sessions,
    sessionsError,
    startNewSession,
    userMessageCount,
  }
}
