export type AgentStep = {
  stepNumber: number
  thought: string
  action: string | null
  actionInput: string | null
  observation: string | null
}

export type AgentExecutionMode = 'AUTO' | 'REACT' | 'PLAN_EXECUTE' | 'MULTI_AGENT'

export type PlanStepStatus = 'SUCCESS' | 'FAILED' | 'SKIPPED'

export type WorkerStatus = 'SUCCESS' | 'FAILED' | 'SKIPPED'

export type AgentPlanStep = {
  id: number
  description: string
  action: string
  actionInput: string
  dependsOn: number[]
  expectedObservation: string
}

export type PlanStepExecution = {
  stepId: number
  description: string
  action: string
  actionInput: string
  dependsOn: number[]
  expectedObservation: string
  status: PlanStepStatus
  observation: string | null
  errorMessage: string | null
}

export type AgentPlanTrace = {
  objective: string
  rationale: string
  coverageCheck: string
  plannedSteps: AgentPlanStep[]
  executions: PlanStepExecution[]
}

export type AgentWorkerTrace = {
  callId: number
  workerName: string
  role: string
  capability: string
  status: WorkerStatus
  subTask: string
  sharedContext: string
  summary: string | null
  errorMessage: string | null
  planTrace: AgentPlanTrace | null
  steps: AgentStep[]
}

export type AgentChatResponse = {
  sessionId: string
  finalAnswer: string | null
  stopReason: string | null
  totalSteps: number
  steps: AgentStep[]
  executionMode: AgentExecutionMode
  planTrace: AgentPlanTrace | null
  workerTraces: AgentWorkerTrace[]
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
  executionMode?: AgentExecutionMode
  planTrace?: AgentPlanTrace | null
  workerTraces?: AgentWorkerTrace[]
}
