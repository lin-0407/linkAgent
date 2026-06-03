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
  PrePublishAnalyzePayload,
} from '@/types/creator'

async function requestJson<T>(url: string, options?: RequestInit) {
  const response = await fetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...options?.headers,
    },
  })

  if (!response.ok) {
    const message = await readErrorMessage(response)
    throw new Error(message || `HTTP ${response.status}`)
  }

  return (await response.json()) as T
}

async function readErrorMessage(response: Response) {
  try {
    const data = (await response.json()) as { message?: string; error?: string; detail?: string }
    return data.message || data.detail || data.error
  } catch {
    return ''
  }
}

async function requestForm<T>(url: string, formData: FormData) {
  const response = await fetch(url, {
    method: 'POST',
    body: formData,
  })

  if (!response.ok) {
    const message = await readErrorMessage(response)
    throw new Error(message || `HTTP ${response.status}`)
  }

  return (await response.json()) as T
}

async function requestEmpty(url: string, options?: RequestInit) {
  const response = await fetch(url, options)

  if (!response.ok) {
    const message = await readErrorMessage(response)
    throw new Error(message || `HTTP ${response.status}`)
  }
}

async function requestBlob(url: string, options?: RequestInit) {
  const response = await fetch(url, options)

  if (!response.ok) {
    const message = await readErrorMessage(response)
    throw new Error(message || `HTTP ${response.status}`)
  }

  return {
    blob: await response.blob(),
    filename: parseDownloadFilename(response.headers.get('Content-Disposition')),
  }
}

function parseDownloadFilename(contentDisposition: string | null) {
  if (!contentDisposition) {
    return ''
  }
  const utf8Match = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i)
  if (utf8Match?.[1]) {
    return decodeURIComponent(utf8Match[1].replace(/"/g, ''))
  }
  const filenameMatch = contentDisposition.match(/filename="?([^";]+)"?/i)
  return filenameMatch?.[1] ?? ''
}

function cleanPayload<T extends Record<string, unknown>>(payload: T) {
  return Object.fromEntries(
    Object.entries(payload).filter(([, value]) => {
      if (typeof value === 'string') {
        return value.trim().length > 0
      }
      return value !== undefined && value !== null
    }),
  )
}

export function createCreatorTask(payload: CreatorTaskCreatePayload) {
  return requestJson<CreatorTask>('/api/creator/tasks', {
    method: 'POST',
    body: JSON.stringify(cleanPayload(payload)),
  })
}

export function updateCreatorTask(taskId: string, payload: CreatorTaskUpdatePayload) {
  return requestJson<CreatorTask>(`/api/creator/tasks/${encodeURIComponent(taskId)}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function importCreatorTaskMaterialFile(
  taskId: string,
  materialType: CreatorMaterialType,
  file: File,
) {
  const formData = new FormData()
  formData.append('materialType', materialType)
  formData.append('file', file)
  return requestForm<CreatorTask>(
    `/api/creator/tasks/${encodeURIComponent(taskId)}/materials/import`,
    formData,
  )
}

export function deleteCreatorTask(taskId: string) {
  return requestEmpty(`/api/creator/tasks/${encodeURIComponent(taskId)}`, {
    method: 'DELETE',
  })
}

export function listCreatorTasks(limit = 20) {
  const params = new URLSearchParams({
    limit: String(limit),
  })
  return requestJson<CreatorTaskSummary[]>(`/api/creator/tasks?${params.toString()}`)
}

export function getCreatorTask(taskId: string) {
  return requestJson<CreatorTask>(`/api/creator/tasks/${encodeURIComponent(taskId)}`)
}

export function listCreatorPreferences(userId = 'default', limit = 10) {
  const params = new URLSearchParams({
    userId,
    limit: String(limit),
  })
  return requestJson<CreatorPreference[]>(`/api/creator/preferences?${params.toString()}`)
}

export function analyzePrePublish(taskId: string, payload: PrePublishAnalyzePayload) {
  return requestJson<CreatorSuggestion>(
    `/api/creator/tasks/${encodeURIComponent(taskId)}/pre-publish/analyze`,
    {
      method: 'POST',
      body: JSON.stringify(cleanPayload(payload)),
    },
  )
}

export function getPrePublishSuggestion(taskId: string) {
  return requestJson<CreatorSuggestion>(
    `/api/creator/tasks/${encodeURIComponent(taskId)}/pre-publish/suggestions`,
  )
}

export function startPrePublishWorkflow(
  taskId: string,
  payload: CreatorWorkflowStartPayload = { resumeLatest: true },
) {
  return requestJson<CreatorWorkflowSession>(
    `/api/creator/tasks/${encodeURIComponent(taskId)}/workflow/pre-publish/start`,
    {
      method: 'POST',
      body: JSON.stringify(cleanPayload(payload)),
    },
  )
}

export function listWorkflowMessages(taskId: string, sessionId: string) {
  return requestJson<CreatorWorkflowMessage[]>(
    `/api/creator/tasks/${encodeURIComponent(taskId)}/workflow/sessions/${encodeURIComponent(sessionId)}/messages`,
  )
}

export function listWorkflowSteps(taskId: string, sessionId: string) {
  return requestJson<CreatorWorkflowStep[]>(
    `/api/creator/tasks/${encodeURIComponent(taskId)}/workflow/sessions/${encodeURIComponent(sessionId)}/steps`,
  )
}

export function createWorkflowEventSource(taskId: string, sessionId: string) {
  return new EventSource(
    `/api/creator/tasks/${encodeURIComponent(taskId)}/workflow/sessions/${encodeURIComponent(sessionId)}/events`,
  )
}

export function sendWorkflowMessage(
  taskId: string,
  sessionId: string,
  payload: CreatorWorkflowMessagePayload,
) {
  return requestJson<CreatorWorkflowMessage>(
    `/api/creator/tasks/${encodeURIComponent(taskId)}/workflow/sessions/${encodeURIComponent(sessionId)}/messages`,
    {
      method: 'POST',
      body: JSON.stringify(cleanPayload(payload)),
    },
  )
}

export function analyzePrePublishWorkflow(
  taskId: string,
  sessionId: string,
  payload: PrePublishAnalyzePayload,
) {
  return requestJson<CreatorSuggestion>(
    `/api/creator/tasks/${encodeURIComponent(taskId)}/workflow/sessions/${encodeURIComponent(sessionId)}/pre-publish/analyze`,
    {
      method: 'POST',
      body: JSON.stringify(cleanPayload(payload)),
    },
  )
}

export function confirmWorkflowPrePublishSuggestion(
  taskId: string,
  sessionId: string,
  payload: CreatorWorkflowConfirmPayload,
) {
  return requestJson<CreatorWorkflowSession>(
    `/api/creator/tasks/${encodeURIComponent(taskId)}/workflow/sessions/${encodeURIComponent(sessionId)}/pre-publish/confirm`,
    {
      method: 'POST',
      body: JSON.stringify(cleanPayload(payload)),
    },
  )
}

export function saveCreatorFeedback(taskId: string, payload: CreatorFeedbackSavePayload) {
  return requestJson<CreatorFeedback>(`/api/creator/tasks/${encodeURIComponent(taskId)}/feedback`, {
    method: 'POST',
    body: JSON.stringify(cleanPayload(payload)),
  })
}

export function getCreatorFeedback(taskId: string) {
  return requestJson<CreatorFeedback>(`/api/creator/tasks/${encodeURIComponent(taskId)}/feedback`)
}

export function importCreatorFeedbackFile(taskId: string, file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return requestForm<CreatorFeedbackImportResult>(
    `/api/creator/tasks/${encodeURIComponent(taskId)}/feedback/import`,
    formData,
  )
}

export function fetchCreatorFeedbackByBv(taskId: string, payload: CreatorFeedbackFetchPayload) {
  return requestJson<CreatorFeedbackFetchResult>(
    `/api/creator/tasks/${encodeURIComponent(taskId)}/feedback/fetch`,
    {
      method: 'POST',
      body: JSON.stringify(cleanPayload(payload)),
    },
  )
}

export function getCreatorFeedbackDashboard(taskId: string) {
  return requestJson<CreatorFeedbackDashboard>(
    `/api/creator/tasks/${encodeURIComponent(taskId)}/feedback/dashboard`,
  )
}

export function analyzeCreatorFeedback(taskId: string, payload: CreatorFeedbackAnalyzePayload) {
  return requestJson<CreatorFeedbackReport>(
    `/api/creator/tasks/${encodeURIComponent(taskId)}/feedback/analyze`,
    {
      method: 'POST',
      body: JSON.stringify(cleanPayload(payload)),
    },
  )
}

export function getCreatorFeedbackReport(taskId: string) {
  return requestJson<CreatorFeedbackReport>(
    `/api/creator/tasks/${encodeURIComponent(taskId)}/feedback/report`,
  )
}

export function chatCreatorFeedback(taskId: string, payload: CreatorFeedbackChatPayload) {
  return requestJson<CreatorFeedbackChatResult>(
    `/api/creator/tasks/${encodeURIComponent(taskId)}/feedback/chat`,
    {
      method: 'POST',
      body: JSON.stringify(cleanPayload(payload)),
    },
  )
}

export function rebuildCreatorFeedbackEvidenceIndex(
  taskId: string,
  payload: CreatorFeedbackEvidenceIndexPayload,
) {
  return requestJson<CreatorFeedbackEvidenceIndexResult>(
    `/api/creator/tasks/${encodeURIComponent(taskId)}/feedback/evidence-index/rebuild`,
    {
      method: 'POST',
      body: JSON.stringify(cleanPayload(payload)),
    },
  )
}

export function getCreatorFeedbackEvidenceIndexStatus(taskId: string) {
  return requestJson<CreatorFeedbackEvidenceIndexStatus>(
    `/api/creator/tasks/${encodeURIComponent(taskId)}/feedback/evidence-index/status`,
  )
}

export function exportCreatorReportMarkdown(taskId: string) {
  return requestBlob(`/api/creator/tasks/${encodeURIComponent(taskId)}/report/markdown`)
}

export function listCreatorEvalCases(
  userId = 'default',
  targetStage?: CreatorWorkflowStage,
  limit = 20,
) {
  const params = new URLSearchParams({
    userId,
    limit: String(limit),
  })
  if (targetStage) {
    params.set('targetStage', targetStage)
  }
  return requestJson<CreatorEvalCase[]>(`/api/creator/evaluations/cases?${params.toString()}`)
}

export function recordCreatorEvalResult(caseId: string, payload: CreatorEvalResultPayload) {
  return requestJson<CreatorEvalResult>(
    `/api/creator/evaluations/cases/${encodeURIComponent(caseId)}/results`,
    {
      method: 'POST',
      body: JSON.stringify(cleanPayload(payload)),
    },
  )
}

export function listCreatorEvalResults(caseId: string, limit = 10) {
  const params = new URLSearchParams({
    limit: String(limit),
  })
  return requestJson<CreatorEvalResult[]>(
    `/api/creator/evaluations/cases/${encodeURIComponent(caseId)}/results?${params.toString()}`,
  )
}

export function listCreatorEvalPromptVersionStats(caseId: string) {
  return requestJson<CreatorEvalPromptVersionStats[]>(
    `/api/creator/evaluations/cases/${encodeURIComponent(caseId)}/prompt-version-stats`,
  )
}
