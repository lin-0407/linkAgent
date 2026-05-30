import type {
  CreatorFeedback,
  CreatorFeedbackAnalyzePayload,
  CreatorFeedbackChatPayload,
  CreatorFeedbackChatResult,
  CreatorFeedbackDashboard,
  CreatorFeedbackFetchPayload,
  CreatorFeedbackFetchResult,
  CreatorFeedbackImportResult,
  CreatorFeedbackReport,
  CreatorFeedbackSavePayload,
  CreatorSuggestion,
  CreatorTask,
  CreatorTaskCreatePayload,
  CreatorTaskSummary,
  CreatorWorkflowConfirmPayload,
  CreatorWorkflowMessage,
  CreatorWorkflowMessagePayload,
  CreatorWorkflowSession,
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

export function listCreatorTasks(limit = 20) {
  const params = new URLSearchParams({
    limit: String(limit),
  })
  return requestJson<CreatorTaskSummary[]>(`/api/creator/tasks?${params.toString()}`)
}

export function getCreatorTask(taskId: string) {
  return requestJson<CreatorTask>(`/api/creator/tasks/${encodeURIComponent(taskId)}`)
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
