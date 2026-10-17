import { get, post, upload } from './http'
import type { AgentChatResponse } from '@/types/agent'

export type AssistantChatContext = { page: string; taskId?: string; step?: string }
export type AssistantActionType = 'ANALYZE_FEEDBACK' | 'GENERATE_REVIEW'
export type AssistantTaskContext = {
  page: string
  taskId: string | null
  taskName: string | null
  taskStatus: string | null
  blockingReason: string | null
  facts: Record<string, unknown>
  availableActions: AssistantActionType[]
}
export type AssistantAction = {
  proposalId: string
  taskId: string
  taskName: string
  actionType: AssistantActionType
  guidance: string
  description: string
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  resultMessage: string | null
  expiresAt: string
}
export type BusinessAgentTrace = {
  runId: string
  taskId: string
  scene: string
  executionMode: string
  status: string
  traceJson: string | null
  errorMessage: string | null
  createTime: string
}

export const getAssistantContext = (context: AssistantChatContext) =>
  get<AssistantTaskContext>('/assistant/context', { params: context })
export const getAssistantActions = (taskId: string) =>
  get<AssistantAction[]>('/assistant/actions', { params: { taskId } })
export const prepareAssistantAction = (taskId: string, actionType: AssistantActionType) =>
  post<AssistantAction>('/assistant/actions', { taskId, actionType })
export const confirmAssistantAction = (proposalId: string, taskId: string) =>
  post<AssistantAction>(`/assistant/actions/${encodeURIComponent(proposalId)}/confirm`, { taskId, confirmed: true })
export const getBusinessAgentTraces = (taskId: string) =>
  get<BusinessAgentTrace[]>('/assistant/executions', { params: { taskId } })

export function parseBusinessTrace(trace: BusinessAgentTrace): AgentChatResponse | null {
  if (!trace.traceJson) return null
  try { return JSON.parse(trace.traceJson) as AgentChatResponse }
  catch { return null }
}

export type PlatformSchema = {
  type?: string; enum?: string[]; properties?: Record<string, PlatformSchema>; items?: PlatformSchema
  description?: string; writeOnly?: boolean
  'x-multiple-files'?: boolean
}
export type PlatformOperation = {
  id: string; group: string; label: string; access: 'READ' | 'WRITE'
  interaction?: 'NONE' | 'FILES' | 'VIDEO'; parameters?: PlatformSchema
  impact?: string
}
export type AssistantPlanStep = {
  id: string; operation: string; arguments: Record<string, unknown>; note?: string
}
export type AssistantPlanProgress = {
  id: string; status: string; arguments: Record<string, unknown> | null; result: unknown
  verification?: { kind: string; message?: string; result?: unknown }; error?: string
  inputs: { path: string; label: string; secret: boolean; schema: PlatformSchema }[]
}
export type AssistantPlan = {
  planId: string; taskId: string | null; title: string; status: string; digest: string; revision: number
  definition: { taskId?: string; title: string; steps: AssistantPlanStep[] }
  state: { message: string; steps: AssistantPlanProgress[]; targets?: Record<string, string> }
  operations: PlatformOperation[]; createTime: string
}
export const getPlatformOperations = () => get<PlatformOperation[]>('/assistant/operations')
export const getAssistantPlans = () => get<AssistantPlan[]>('/assistant/plans')
export const confirmAssistantPlan = (plan: AssistantPlan) =>
  post<AssistantPlan>(`/assistant/plans/${encodeURIComponent(plan.planId)}/confirm`, { confirmed: true, digest: plan.digest })
export const submitAssistantPlanInputs = (plan: AssistantPlan, values: Record<string, unknown>) =>
  post<AssistantPlan>(`/assistant/plans/${encodeURIComponent(plan.planId)}/inputs`, { confirmed: true, digest: plan.digest, values })
export function submitAssistantPlanFiles(plan: AssistantPlan, files: File[], values: Record<string, unknown>) {
  const form = new FormData()
  form.append('confirmed', 'true'); form.append('digest', plan.digest); form.append('values', JSON.stringify(values))
  files.forEach(file => form.append('files', file))
  return upload<AssistantPlan>(`/assistant/plans/${encodeURIComponent(plan.planId)}/files`, form)
}
export const resumeAssistantPlan = (planId: string) => post<AssistantPlan>(`/assistant/plans/${encodeURIComponent(planId)}/resume`)
export const cancelAssistantPlan = (planId: string) => post<AssistantPlan>(`/assistant/plans/${encodeURIComponent(planId)}/cancel`)
