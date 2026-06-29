import type {
  AgentChatResponse,
  AgentExecutionMode,
  SessionListItem,
  SessionMessageItem,
} from '@/types/agent'
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

export function loadAgentSessions() {
  return get<SessionListItem[]>('/agent/sessions')
}

export function loadAgentSessionMessages(sessionId: string) {
  return get<SessionMessageItem[]>(`/agent/sessions/${encodeURIComponent(sessionId)}`)
}
