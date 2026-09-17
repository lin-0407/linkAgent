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
  /** summary 表示这是一条摘要检查点：历史在这里被压缩过，content 是摘要正文 */
  role: 'user' | 'assistant' | 'summary' | string
  content: string
  /** 摘要检查点压缩释放的 token 数；普通消息为 null */
  tokenCount?: number | null
}

export type ChatMessage = {
  id: number
  /** summary：摘要检查点，代表「这里压缩过历史」；content 是摘要正文 */
  role: 'user' | 'assistant' | 'summary'
  content: string
  /** 摘要检查点释放的 token 数，用于显示"释放了多少" */
  releasedTokens?: number
  /** 摘要检查点压缩掉的消息条数；实时事件有，历史回放只有 releasedTokens */
  compressedMessageCount?: number
  steps?: AgentStep[]
  /** 模型思考过程原文；模型未开启思考模式或未返回思考内容时为空，界面不渲染思考区 */
  thinking?: string
  stopReason?: string | null
  executionMode?: AgentExecutionMode
  planTrace?: AgentPlanTrace | null
  workerTraces?: AgentWorkerTrace[]
}
