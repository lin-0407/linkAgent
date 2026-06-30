import type {
  AgentChatResponse,
  AgentExecutionMode,
  SessionListItem,
  SessionMessageItem,
} from '@/types/agent'
import type { AgentStep } from '@/types/agent'
import { get, post } from './http'

/**
 * 发送 Agent 消息。
 * userId 是后端 AgentChatRequest 新增字段（P0-3 阶段），
 * 用于将会话绑定到具体用户，支持长期记忆和画像查询。
 */
export function sendAgentMessage(
  sessionId: string,
  message: string,
  executionMode: AgentExecutionMode,
  userId?: string,
) {
  return post<AgentChatResponse>('/agent/chat', {
    sessionId: sessionId || undefined,
    message,
    executionMode,
    userId: userId || undefined,
  })
}

/** SSE 流式事件类型，与后端 AgentController.chatStream 的 SSE 事件一一对应 */
export type AgentStreamEvent =
  | { type: 'session'; sessionId: string }
  | { type: 'step'; step: AgentStep }
  | { type: 'token'; text: string }
  | { type: 'error'; message: string }
  | { type: 'done'; sessionId: string }

/** SSE 流式事件处理器，由调用方提供业务逻辑 */
export interface AgentStreamHandlers {
  onSession?: (sessionId: string) => void
  onStep?: (step: AgentStep) => void
  onToken?: (text: string) => void
  onError?: (message: string) => void
  onDone?: (sessionId: string) => void
}

/**
 * 流式发送 Agent 消息，通过 SSE 实时接收 ReAct 步骤与最终答案。
 * <p>
 * 使用 {@code fetch} + {@code ReadableStream} 而非 {@code EventSource}，
 * 因为 EventSource 仅支持 GET 请求，而我们需要 POST 发送消息体。
 * <p>
 * 返回一个 abort 函数，调用方可随时中断流式连接。
 *
 * @param sessionId 会话标识，为空时后端自动创建
 * @param message 用户消息
 * @param executionMode 执行模式
 * @param handlers 事件处理器
 * @param userId 用户标识（可选）
 * @returns abort 函数，调用后中断 SSE 连接
 */
export function sendAgentMessageStream(
  sessionId: string,
  message: string,
  executionMode: AgentExecutionMode,
  handlers: AgentStreamHandlers,
  userId?: string,
): () => void {
  const abortController = new AbortController()
  const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api'

  // 异步启动 fetch 流，不阻塞调用方
  void (async () => {
    try {
      const response = await fetch(`${baseUrl}/agent/chat/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sessionId: sessionId || undefined,
          message,
          executionMode,
          userId: userId || undefined,
        }),
        signal: abortController.signal,
      })

      if (!response.ok) {
        // 非 200 响应：尝试解析错误消息
        let errorMsg = `HTTP ${response.status}`
        try {
          const errorBody = await response.json()
          errorMsg = errorBody.message || errorBody.detail || errorBody.error || errorMsg
        } catch { /* 错误体非 JSON 时忽略 */ }
        handlers.onError?.(errorMsg)
        return
      }

      if (!response.body) {
        handlers.onError?.('浏览器不支持 ReadableStream')
        return
      }

      // 逐行解析 SSE 流（标准 SSE 格式：event: xxx\ndata: yyy\n\n）
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        // SSE 事件以双换行分隔
        const parts = buffer.split('\n\n')
        // 最后一段可能不完整，保留到下次循环
        buffer = parts.pop() ?? ''

        for (const part of parts) {
          const event = parseSseEvent(part)
          if (!event) continue

          // 根据事件类型分发到对应的 handler
          switch (event.type) {
            case 'session':
              handlers.onSession?.(event.sessionId)
              break
            case 'step':
              handlers.onStep?.(event.step)
              break
            case 'token':
              handlers.onToken?.(event.text)
              break
            case 'error':
              handlers.onError?.(event.message)
              break
            case 'done':
              handlers.onDone?.(event.sessionId)
              break
          }
        }
      }
    } catch (error) {
      if ((error as Error).name === 'AbortError') {
        return // 用户主动中断，不是错误
      }
      handlers.onError?.((error as Error).message || 'SSE 连接失败')
    }
  })()

  // 返回 abort 函数，供外部中断连接
  return () => abortController.abort()
}

/**
 * 解析单条 SSE 事件文本为 AgentStreamEvent 对象。
 * <p>
 * SSE 格式示例：
 * <pre>
 * event: step
 * data: {"stepNumber":1,"thought":"...","action":"search","actionInput":"q","observation":"..."}
 * </pre>
 * <p>
 * 容忍 data 行 JSON 解析失败（如心跳包或空数据行），返回 null 让上层跳过。
 */
function parseSseEvent(raw: string): AgentStreamEvent | null {
  const lines = raw.split('\n')
  let eventType = ''
  let dataStr = ''

  for (const line of lines) {
    if (line.startsWith('event: ')) {
      eventType = line.slice(7).trim()
    } else if (line.startsWith('data: ')) {
      dataStr = line.slice(6)
    }
  }

  if (!eventType || !dataStr) return null

  // token 事件由后端按纯文本片段发送，不能先 JSON.parse，否则中文和普通文本会被静默丢弃。
  if (eventType === 'token') {
    return { type: 'token', text: dataStr }
  }

  try {
    const data = JSON.parse(dataStr)
    switch (eventType) {
      case 'session':
        return { type: 'session', sessionId: data.sessionId ?? '' }
      case 'step':
        return { type: 'step', step: data as AgentStep }
      case 'error':
        return { type: 'error', message: typeof data === 'string' ? data : (data.message ?? '未知错误') }
      case 'done':
        return { type: 'done', sessionId: data.sessionId ?? '' }
      default:
        return null
    }
  } catch {
    // JSON 解析失败：非关键事件（如心跳或格式不兼容的旧版事件），静默跳过
    return null
  }
}

export function loadAgentSessions() {
  return get<SessionListItem[]>('/agent/sessions')
}

export function loadAgentSessionMessages(sessionId: string) {
  return get<SessionMessageItem[]>(`/agent/sessions/${encodeURIComponent(sessionId)}`)
}
