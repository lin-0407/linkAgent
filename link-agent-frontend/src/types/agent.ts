export type AgentStep = {
  stepNumber: number
  thought: string
  action: string | null
  actionInput: string | null
  observation: string | null
}

export type AgentChatResponse = {
  sessionId: string
  finalAnswer: string | null
  stopReason: string | null
  totalSteps: number
  steps: AgentStep[]
}

export type SessionListItem = {
  sessionId: string
  preview: string
  messageCount: number
}

export type SessionMessageItem = {
  role: 'user' | 'assistant' | string
  content: string
}

export type ChatMessage = {
  id: number
  role: 'user' | 'assistant'
  content: string
  steps?: AgentStep[]
  stopReason?: string | null
}
