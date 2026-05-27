import type {
  CreatorFeedback,
  CreatorFeedbackAnalyzePayload,
  CreatorFeedbackReport,
  CreatorFeedbackSavePayload,
  CreatorSuggestion,
  CreatorTask,
  CreatorTaskCreatePayload,
  CreatorTaskSummary,
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

export function listCreatorTasks(userId = 'default', limit = 20) {
  const params = new URLSearchParams({
    userId,
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

export function saveCreatorFeedback(taskId: string, payload: CreatorFeedbackSavePayload) {
  return requestJson<CreatorFeedback>(`/api/creator/tasks/${encodeURIComponent(taskId)}/feedback`, {
    method: 'POST',
    body: JSON.stringify(cleanPayload(payload)),
  })
}

export function getCreatorFeedback(taskId: string) {
  return requestJson<CreatorFeedback>(`/api/creator/tasks/${encodeURIComponent(taskId)}/feedback`)
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
