import type {
  AgentChatResponse,
  AgentExecutionMode,
  SessionListItem,
  SessionMessageItem,
} from '@/types/agent'
import { get, post } from './http'

export function sendAgentMessage(
  sessionId: string,
  message: string,
  executionMode: AgentExecutionMode,
) {
  return post<AgentChatResponse>('/agent/chat', {
    sessionId: sessionId || undefined,
    message,
    executionMode,
  })
}

export function loadAgentSessions() {
  return get<SessionListItem[]>('/agent/sessions')
}

export function loadAgentSessionMessages(sessionId: string) {
  return get<SessionMessageItem[]>(`/agent/sessions/${encodeURIComponent(sessionId)}`)
}
