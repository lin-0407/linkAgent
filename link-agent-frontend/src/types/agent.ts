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

export type EvidenceSourceType =
  | 'TOOL_OBSERVATION'
  | 'PLAN_STEP'
  | 'USER_INPUT'
  | 'CONVERSATION_CONTEXT'
  | 'WORKER_REASONING'
  | 'SYSTEM_LIMITATION'

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

export type AgentEvidence = {
  evidenceId: string
  sourceType: EvidenceSourceType
  sourceRef: string
  content: string
  quote: string
  confidence: number
}

export type WorkerBrief = {
  coreConclusion: string
  keyPoints: string[]
  confidence: number
  evidenceIds: string[]
  unresolvedQuestions: string[]
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
  brief: WorkerBrief | null
  evidences: AgentEvidence[]
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
