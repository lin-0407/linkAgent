import type { AgentChatResponse, SessionListItem, SessionMessageItem } from '@/types/agent'

export async function sendAgentMessage(sessionId: string, message: string) {
  const response = await fetch('/api/agent/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      sessionId: sessionId || undefined,
      message,
    }),
  })

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }

  return (await response.json()) as AgentChatResponse
}

export async function loadAgentSessions() {
  const response = await fetch('/api/agent/sessions')
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }

  return (await response.json()) as SessionListItem[]
}

export async function loadAgentSessionMessages(sessionId: string) {
  const response = await fetch(`/api/agent/sessions/${encodeURIComponent(sessionId)}`)
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }

  return (await response.json()) as SessionMessageItem[]
}
