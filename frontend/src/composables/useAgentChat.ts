import { computed, nextTick, ref, watch } from 'vue'
import {
  loadAgentSessionMessages,
  loadAgentSessions,
  sendAgentMessageStream,
} from '@/api/agent'
import type { AgentExecutionMode, AgentStep, ChatMessage, SessionListItem } from '@/types/agent'

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
  /** 是否正在流式接收中（SSE 连接已建立且未收到 done 事件） */
  const isStreaming = ref(false)
  /** 流式接收过程中实时累积的文本内容，用于显示打字效果 */
  const streamingContent = ref('')
  /** 流式接收过程中实时累积的步骤列表 */
  const streamingSteps = ref<AgentStep[]>([])
  /** 中断流式连接的函数，调用后 SSE 连接关闭 */
  let abortStream: (() => void) | null = null
  const isSessionsLoading = ref(false)
  const isSessionsOpen = ref(false)
  const executionMode = ref<AgentExecutionMode>('AUTO')
  const errorMessage = ref('')
  const sessionsError = ref('')
  const messageListRef = ref<ScrollContainer | null>(null)

  const canSend = computed(() => inputMessage.value.trim().length > 0 && !isLoading.value && !isStreaming.value)
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
    if (!displayMessage || !outboundMessage || isLoading.value || isStreaming.value) {
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

    // 优先使用 SSE 流式，失败时回退到非流式
    try {
      await sendMessageStream(displayMessage, outboundMessage)
    } catch {
      // 流式已内部处理错误，此处不需要额外操作
    }
  }

  /**
   * 通过 SSE 流式发送消息并实时接收响应。
   * <p>
   * 在 SSE 连接期间：
   * - step 事件：逐步累积到 streamingSteps，前端实时展示推理过程
   * - token 事件：逐步累积到 streamingContent，前端实现打字机效果
   * - done 事件：将累积的内容固化为一条完整的 assistant 消息
   * - error 事件：设置 errorMessage 并终止
   * <p>
   * 设计权衡：此方法返回 Promise 且在 done/error 时 resolve，
   * 让 sendFloatingMessage 可以 await 它来执行后处理（如重置输入框高度）。
   */
  async function sendMessageStream(displayMessage: string, outboundMessage: string): Promise<void> {
    // 重置流式状态
    streamingContent.value = ''
    streamingSteps.value = []
    isStreaming.value = true
    isLoading.value = true

    return new Promise<void>((resolve) => {
      // 保存当前会话 ID 的快照，用于流式结束时构建消息
      const sessionSnapshot = sessionId.value

      abortStream = sendAgentMessageStream(
        sessionId.value,
        outboundMessage,
        executionMode.value,
        {
          onSession: (sid) => {
            // 收到服务端会话 ID 后立即更新，让前端可以关联后续请求
            sessionId.value = sid
            persistSessionId(sid)
            void loadSessions()
          },
          onStep: (step) => {
            // 每完成一个 ReAct 步骤，立即追加到流式步骤列表
            streamingSteps.value = [...streamingSteps.value, step]
            void scrollToBottom()
          },
          onToken: (text) => {
            // 逐字符追加，实现打字机效果
            streamingContent.value += text
            void scrollToBottom()
          },
          onError: (message) => {
            // 流式错误：设置错误消息并清理状态
            errorMessage.value = message
            abortStream = null
            isStreaming.value = false
            isLoading.value = false
            resolve()
          },
          onDone: (sid) => {
            // 流式结束：将累积的内容固化为 assistant 消息
            abortStream = null

            // 合并流式追加的步骤与返回的 sessionId
            const finalSessionId = sid || sessionSnapshot
            if (finalSessionId) {
              sessionId.value = finalSessionId
              persistSessionId(finalSessionId)
              void loadSessions()
            }

            // 将流式结果固化为一条完整的 assistant 消息
            const hasSteps = streamingSteps.value.length > 0
            const hasContent = streamingContent.value.trim().length > 0
            if (hasContent || hasSteps) {
              messages.value.push({
                id: Date.now(),
                role: 'assistant',
                content: hasContent ? streamingContent.value : (hasSteps ? 'Agent 完成推理（详见步骤）' : 'Agent 没有返回内容'),
                steps: hasSteps ? [...streamingSteps.value] : undefined,
                executionMode: executionMode.value,
              })
            }

            // 清理流式状态
            isStreaming.value = false
            isLoading.value = false
            void scrollToBottom()
            resolve()
          },
        },
      )
    })
  }

  /**
   * 中断当前正在进行的流式连接。
   * 用户点击停止按钮或切换会话时调用。
   */
  function stopStreaming() {
    if (abortStream) {
      abortStream()
      abortStream = null
    }
    isStreaming.value = false
    isLoading.value = false

    // 如果已有部分流式内容，固化为一条不完整的 assistant 消息
    const hasSteps = streamingSteps.value.length > 0
    const hasContent = streamingContent.value.trim().length > 0
    if (hasContent || hasSteps) {
      messages.value.push({
        id: Date.now(),
        role: 'assistant',
        content: hasContent ? streamingContent.value + ' [已中断]' : '[已中断]',
        steps: hasSteps ? [...streamingSteps.value] : undefined,
        executionMode: executionMode.value,
      })
    }
    streamingContent.value = ''
    streamingSteps.value = []
  }

  function startNewSession() {
    stopStreaming()
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
    stopStreaming()
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
    isStreaming,
    streamingContent,
    streamingSteps,
    isSessionsLoading,
    isSessionsOpen,
    latestStepCount,
    messageListRef,
    messages,
    openSession,
    scrollToBottom,
    sendMessage,
    stopStreaming,
    sessionId,
    sessions,
    sessionsError,
    startNewSession,
    userMessageCount,
  }
}
