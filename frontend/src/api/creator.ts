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
  CreatorContextTerm,
  CreatorContextTermPayload,
  CreatorFeedbackEventPayload,
  CreativeOptionsRegeneratePayload,
  CreatorPreference,
  CreatorFeedbackSavePayload,
  CreatorSuggestion,
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
  InteractiveTask,
  InteractiveTaskCreatePayload,
  LlmApiCallPage,
  LlmApiModelCategory,
  LlmApiUsageSummary,
  PrePublishAnalyzePayload,
  PrePublishDraftPayload,
  PrePublishDraftResult,
  // P0-3: B站账号绑定 + 视频分析
  BilibiliAccount,
  BindAccountPayload,
  BilibiliVideo,
  TaskVideoBinding,
  BindBvPayload,
  SyncVideosResult,
  VideoAnalysisReport,
  // 创作者画像
  CreatorProfile,
  // 竞品分析
  CreatorCompetitorReport,
  CompetitorAnalyzeByReferencePayload,
} from '@/types/creator'
import { cleanPayload, del, download, get, post, put, upload } from './http'

// ── 任务 CRUD ──

export function createCreatorTask(payload: CreatorTaskCreatePayload) {
  return post<CreatorTask>('/creator/tasks', cleanPayload(payload))
}

export function updateCreatorTask(taskId: string, payload: CreatorTaskUpdatePayload) {
  return put<CreatorTask>(`/creator/tasks/${encodeURIComponent(taskId)}`, payload)
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

// ── AI 交互式创作 ──

export function createInteractiveTask(payload: InteractiveTaskCreatePayload) {
  return post<InteractiveTask>('/creator/interactive/tasks', cleanPayload(payload))
}

export function regenerateCreativeOptions(
  taskId: string,
  payload: CreativeOptionsRegeneratePayload = {},
) {
  return post<InteractiveTask>(
    `/creator/interactive/tasks/${encodeURIComponent(taskId)}/creative-options/regenerate`,
    cleanPayload(payload),
  )
}

/** 上传补充背景文档（多文件，multipart/form-data） */
export function uploadContextDocuments(taskId: string, files: File[]) {
  const formData = new FormData()
  files.forEach((file) => formData.append('files', file))
  return upload<InteractiveTask>(
    `/creator/interactive/tasks/${encodeURIComponent(taskId)}/context-documents`,
    formData,
    { timeout: 180_000 },
  )
}

/** AI 理解确认 —— 调用 LLM 理解创作意图 */
export function triggerUnderstanding(taskId: string) {
  return post<InteractiveTask>(`/creator/interactive/tasks/${encodeURIComponent(taskId)}/understand`)
}

/** 生成创意方向卡（在 AI 理解确认之后调用） */
export function generateCreativeOptions(
  taskId: string,
  payload: CreativeOptionsRegeneratePayload = {},
) {
  return post<InteractiveTask>(
    `/creator/interactive/tasks/${encodeURIComponent(taskId)}/creative-options/generate`,
    cleanPayload(payload),
  )
}

export function confirmCreativeOption(taskId: string, optionId: string) {
  return post<InteractiveTask>(
    `/creator/interactive/tasks/${encodeURIComponent(taskId)}/creative-options/${encodeURIComponent(optionId)}/confirm`,
  )
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

export function generatePrePublishManuscriptDraft(
  taskId: string,
  sessionId: string,
  payload: PrePublishDraftPayload = {},
) {
  return post<PrePublishDraftResult>(
    `/creator/tasks/${encodeURIComponent(taskId)}/workflow/sessions/${encodeURIComponent(sessionId)}/pre-publish/manuscript-draft`,
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

// ── B站账号绑定（P0-3）──

/** 绑定或更新B站账号（第一版只需UID，不做OAuth授权） */
export function bindBilibiliAccount(payload: BindAccountPayload) {
  return post<BilibiliAccount>('/creator/bilibili/accounts', cleanPayload(payload))
}

/** 查询B站账号绑定状态 */
export function getBilibiliAccount(userId: string) {
  return get<BilibiliAccount>(`/creator/bilibili/accounts/${encodeURIComponent(userId)}`)
}

/** 触发B站公开视频同步，并校验当前用户任务BV的归属 */
export function syncBilibiliVideos(userId: string) {
  return post<SyncVideosResult>(`/creator/bilibili/accounts/${encodeURIComponent(userId)}/sync`)
}

// ── 任务BV绑定（P0-3）──

/** 将BV号绑定到创作任务，绑定后视频分析页才能展示该视频卡片 */
export function bindBvToTask(taskId: string, payload: BindBvPayload) {
  return post<TaskVideoBinding>(
    `/creator/bilibili/tasks/${encodeURIComponent(taskId)}/video-binding`,
    cleanPayload(payload),
  )
}

/** 查询任务的BV绑定状态，不存在时后端返回404 */
export function getTaskVideoBinding(taskId: string) {
  return get<TaskVideoBinding>(
    `/creator/bilibili/tasks/${encodeURIComponent(taskId)}/video-binding`,
  )
}

// ── 已绑定视频列表（P0-3）──

/** 查询某B站UID下已和平台任务绑定的视频列表（仅BOUND状态+当前用户） */
export function listLinkedVideos(bilibiliUid: string, userId = 'default') {
  return get<BilibiliVideo[]>(
    `/creator/bilibili/accounts/${encodeURIComponent(bilibiliUid)}/linked-videos`,
    { params: { userId } },
  )
}

// ── 创作者画像（P0-3）──
// 后端 CreatorProfileController，路径前缀 /api/creator/profile

/** 获取当前用户的创作者画像；不存在时返回空画像（非 404） */
export function getCreatorProfile(userId?: string) {
  return get<CreatorProfile>('/creator/profile', {
    params: userId ? { userId } : undefined,
  })
}

/** 手动触发画像刷新（立即重推理，不检查阈值） */
export function refreshCreatorProfile(userId?: string) {
  return post<CreatorProfile>('/creator/profile/refresh', undefined, {
    params: userId ? { userId } : undefined,
  })
}

/** 基于参考案例触发竞品分析（P1-1：无需手动填写竞品文稿，直接从知识库读取） */
export function analyzeCompetitorByReference(taskId: string, payload: CompetitorAnalyzeByReferencePayload) {
  return post<CreatorCompetitorReport>(
    `/creator/tasks/${encodeURIComponent(taskId)}/competitors/analyze-by-reference`,
    cleanPayload(payload),
  )
}
