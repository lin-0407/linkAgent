import type {
  CreatorFeedback,
  CreatorFeedbackAnalyzePayload,
  CreatorFeedbackChatPayload,
  CreatorFeedbackChatResult,
  CreatorFeedbackEvidenceIndexPayload,
  CreatorFeedbackEvidenceIndexResult,
  CreatorFeedbackEvidenceIndexStatus,
  CreatorFeedbackDashboard,
  CreatorFeedbackFetchPayload,
  CreatorFeedbackFetchResult,
  CreatorFeedbackImportResult,
  CreatorFeedbackReport,
  CreatorEvalCase,
  CreatorEvalPromptVersionStats,
  CreatorEvalResult,
  CreatorEvalResultPayload,
  CreatorContextBundle,
  CreatorContextTerm,
  CreatorContextTermPayload,
  CreatorFeedbackEventPayload,
  CreatorPreference,
  CreatorFeedbackSavePayload,
  CreatorSuggestion,
  CreatorMaterialType,
  CreatorTask,
  CreatorTaskCreatePayload,
  CreatorTaskSummary,
  CreatorTaskUpdatePayload,
  CreatorWorkflowConfirmPayload,
  CreatorWorkflowMessage,
  CreatorWorkflowMessagePayload,
  CreatorWorkflowSession,
  CreatorWorkflowStage,
  CreatorWorkflowStep,
  CreatorWorkflowStartPayload,
  LlmApiCallPage,
  LlmApiModelCategory,
  LlmApiUsageSummary,
  PrePublishAnalyzePayload,
  WorkflowUsageResponse,
} from '@/types/creator'
import { cleanPayload, del, download, get, post, put, upload } from './http'

// ── 任务 CRUD ──

export function createCreatorTask(payload: CreatorTaskCreatePayload) {
  return post<CreatorTask>('/creator/tasks', cleanPayload(payload))
}

export function updateCreatorTask(taskId: string, payload: CreatorTaskUpdatePayload) {
  return put<CreatorTask>(`/creator/tasks/${encodeURIComponent(taskId)}`, payload)
}

export function importCreatorTaskMaterialFile(
  taskId: string,
  materialType: CreatorMaterialType,
  file: File,
) {
  const formData = new FormData()
  formData.append('materialType', materialType)
  formData.append('file', file)
  return upload<CreatorTask>(`/creator/tasks/${encodeURIComponent(taskId)}/materials/import`, formData)
}

export function deleteCreatorTask(taskId: string) {
  return del(`/creator/tasks/${encodeURIComponent(taskId)}`)
}

export function listCreatorTasks(limit = 20) {
  return get<CreatorTaskSummary[]>('/creator/tasks', { params: { limit } })
}

export function getCreatorTask(taskId: string) {
  return get<CreatorTask>(`/creator/tasks/${encodeURIComponent(taskId)}`)
}

// ── 偏好 & 语境 ──

export function listCreatorPreferences(userId = 'default', limit = 10) {
  return get<CreatorPreference[]>('/creator/preferences', { params: { userId, limit } })
}

export function listCreatorContextTerms(
  userId = 'default',
  videoType?: string,
  includeDisabled = false,
  limit = 50,
) {
  return get<CreatorContextTerm[]>('/creator/context/terms', {
    params: { userId, videoType, includeDisabled, limit },
  })
}

export function getCreatorContextBundle(userId = 'default', videoType?: string, scene = 'PRE_PUBLISH') {
  return get<CreatorContextBundle>('/creator/context/bundle', {
    params: { userId, videoType, scene },
  })
}

export function saveCreatorContextTerm(payload: CreatorContextTermPayload) {
  return post<CreatorContextTerm>('/creator/context/terms', cleanPayload(payload))
}

export function disableCreatorContextTerm(termId: string) {
  return del<CreatorContextTerm>(`/creator/context/terms/${encodeURIComponent(termId)}`)
}

export function recordCreatorContextTermFeedback(termId: string, accepted: boolean) {
  return post<CreatorContextTerm>(
    `/creator/context/terms/${encodeURIComponent(termId)}/feedback`,
    { accepted },
  )
}

// ── 发布前优化 ──

export function analyzePrePublish(taskId: string, payload: PrePublishAnalyzePayload) {
  return post<CreatorSuggestion>(
    `/creator/tasks/${encodeURIComponent(taskId)}/pre-publish/analyze`,
    cleanPayload(payload),
  )
}

export function getPrePublishSuggestion(taskId: string) {
  return get<CreatorSuggestion>(`/creator/tasks/${encodeURIComponent(taskId)}/pre-publish/suggestions`)
}

export function startPrePublishWorkflow(
  taskId: string,
  payload: CreatorWorkflowStartPayload = { resumeLatest: true },
) {
  return post<CreatorWorkflowSession>(
    `/creator/tasks/${encodeURIComponent(taskId)}/workflow/pre-publish/start`,
    cleanPayload(payload),
  )
}

// ── 工作流消息 / 步骤 / 用量 ──

export function listWorkflowMessages(taskId: string, sessionId: string) {
  return get<CreatorWorkflowMessage[]>(
    `/creator/tasks/${encodeURIComponent(taskId)}/workflow/sessions/${encodeURIComponent(sessionId)}/messages`,
  )
}

export function listWorkflowSteps(taskId: string, sessionId: string) {
  return get<CreatorWorkflowStep[]>(
    `/creator/tasks/${encodeURIComponent(taskId)}/workflow/sessions/${encodeURIComponent(sessionId)}/steps`,
  )
}

export function getWorkflowUsage(taskId: string, sessionId: string) {
  return get<WorkflowUsageResponse>(
    `/creator/tasks/${encodeURIComponent(taskId)}/workflow/sessions/${encodeURIComponent(sessionId)}/usage`,
  )
}

export function sendWorkflowMessage(
  taskId: string,
  sessionId: string,
  payload: CreatorWorkflowMessagePayload,
) {
  return post<CreatorWorkflowMessage>(
    `/creator/tasks/${encodeURIComponent(taskId)}/workflow/sessions/${encodeURIComponent(sessionId)}/messages`,
    cleanPayload(payload),
  )
}

export function analyzePrePublishWorkflow(
  taskId: string,
  sessionId: string,
  payload: PrePublishAnalyzePayload,
) {
  return post<CreatorSuggestion>(
    `/creator/tasks/${encodeURIComponent(taskId)}/workflow/sessions/${encodeURIComponent(sessionId)}/pre-publish/analyze`,
    cleanPayload(payload),
  )
}

export function confirmWorkflowPrePublishSuggestion(
  taskId: string,
  sessionId: string,
  payload: CreatorWorkflowConfirmPayload,
) {
  return post<CreatorWorkflowSession>(
    `/creator/tasks/${encodeURIComponent(taskId)}/workflow/sessions/${encodeURIComponent(sessionId)}/pre-publish/confirm`,
    cleanPayload(payload),
  )
}

// ── 反馈管理 ──

export function saveCreatorFeedback(taskId: string, payload: CreatorFeedbackSavePayload) {
  return post<CreatorFeedback>(
    `/creator/tasks/${encodeURIComponent(taskId)}/feedback`,
    cleanPayload(payload),
  )
}

export function getCreatorFeedback(taskId: string) {
  return get<CreatorFeedback>(`/creator/tasks/${encodeURIComponent(taskId)}/feedback`)
}

export function importCreatorFeedbackFile(taskId: string, file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return upload<CreatorFeedbackImportResult>(
    `/creator/tasks/${encodeURIComponent(taskId)}/feedback/import`,
    formData,
  )
}

export function fetchCreatorFeedbackByBv(taskId: string, payload: CreatorFeedbackFetchPayload) {
  return post<CreatorFeedbackFetchResult>(
    `/creator/tasks/${encodeURIComponent(taskId)}/feedback/fetch`,
    cleanPayload(payload),
  )
}

export function getCreatorFeedbackDashboard(taskId: string) {
  return get<CreatorFeedbackDashboard>(`/creator/tasks/${encodeURIComponent(taskId)}/feedback/dashboard`)
}

export function analyzeCreatorFeedback(taskId: string, payload: CreatorFeedbackAnalyzePayload) {
  return post<CreatorFeedbackReport>(
    `/creator/tasks/${encodeURIComponent(taskId)}/feedback/analyze`,
    cleanPayload(payload),
  )
}

export function getCreatorFeedbackReport(taskId: string) {
  return get<CreatorFeedbackReport>(`/creator/tasks/${encodeURIComponent(taskId)}/feedback/report`)
}

export function chatCreatorFeedback(taskId: string, payload: CreatorFeedbackChatPayload) {
  return post<CreatorFeedbackChatResult>(
    `/creator/tasks/${encodeURIComponent(taskId)}/feedback/chat`,
    cleanPayload(payload),
  )
}

export function rebuildCreatorFeedbackEvidenceIndex(
  taskId: string,
  payload: CreatorFeedbackEvidenceIndexPayload,
) {
  return post<CreatorFeedbackEvidenceIndexResult>(
    `/creator/tasks/${encodeURIComponent(taskId)}/feedback/evidence-index/rebuild`,
    cleanPayload(payload),
  )
}

export function getCreatorFeedbackEvidenceIndexStatus(taskId: string) {
  return get<CreatorFeedbackEvidenceIndexStatus>(
    `/creator/tasks/${encodeURIComponent(taskId)}/feedback/evidence-index/status`,
  )
}

// ── 报告导出 ──

export function exportCreatorReportMarkdown(taskId: string) {
  return download(`/creator/tasks/${encodeURIComponent(taskId)}/report/markdown`)
}

// ── 评测 ──

export function listCreatorEvalCases(
  userId = 'default',
  targetStage?: CreatorWorkflowStage,
  limit = 20,
) {
  return get<CreatorEvalCase[]>('/creator/evaluations/cases', {
    params: { userId, targetStage, limit },
  })
}

export function recordCreatorEvalResult(caseId: string, payload: CreatorEvalResultPayload) {
  return post<CreatorEvalResult>(
    `/creator/evaluations/cases/${encodeURIComponent(caseId)}/results`,
    cleanPayload(payload),
  )
}

export function listCreatorEvalResults(caseId: string, limit = 10) {
  return get<CreatorEvalResult[]>(
    `/creator/evaluations/cases/${encodeURIComponent(caseId)}/results`,
    { params: { limit } },
  )
}

export function listCreatorEvalPromptVersionStats(caseId: string) {
  return get<CreatorEvalPromptVersionStats[]>(
    `/creator/evaluations/cases/${encodeURIComponent(caseId)}/prompt-version-stats`,
  )
}

// ── LLM 开销统计 ──

export function getTaskLlmUsageSummary(taskId: string) {
  return get<LlmApiUsageSummary>(`/llm-usage/tasks/${encodeURIComponent(taskId)}/summary`)
}

export function listTaskLlmApiCalls(
  taskId: string,
  page = 1,
  pageSize = 20,
  modelCategory?: LlmApiModelCategory,
) {
  return get<LlmApiCallPage>(`/llm-usage/tasks/${encodeURIComponent(taskId)}/calls`, {
    params: { page, pageSize, modelCategory },
  })
}

// ── 创作者反馈事件（画像增量更新信号源）──
// 后端 POST /api/creator/profile/events：从 body 取 userId/eventType/taskId 作为事件主字段，
// 整个 body 作为 payload 落 creator_event 表，并尝试触发画像增量更新。

export function recordCreatorEvent(payload: CreatorFeedbackEventPayload) {
  // 后端约定无响应体（写入后异步触发画像更新），用 void 泛型
  return post<void>('/creator/profile/events', payload)
}
