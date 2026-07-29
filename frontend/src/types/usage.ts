import type {
  LlmApiCallRecord,
  LlmApiCallStatus,
  LlmApiModelCategory,
} from '@/types/creator'

export type LlmApiCallLogSummary = {
  callCount: number
  successCount: number
  failedCount: number
  skippedCount: number
  totalTokens: number | null
  promptTokens: number | null
  completionTokens: number | null
  totalElapsedMs: number | null
  averageElapsedMs: number | null
}

export type LlmApiCallLogPage = {
  page: number
  pageSize: number
  total: number
  summary: LlmApiCallLogSummary
  items: LlmApiCallRecord[]
}

export type LlmApiCallLogFilters = {
  startTime?: string
  endTime?: string
  modelName?: string
  scene?: string
  modelCategory?: LlmApiModelCategory
  status?: LlmApiCallStatus
  page: number
  pageSize: number
}
