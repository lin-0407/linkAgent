import { get } from '@/api/http'
import type { LlmApiCallLogFilters, LlmApiCallLogPage } from '@/types/usage'

export function listLlmApiCallLogs(filters: LlmApiCallLogFilters) {
  return get<LlmApiCallLogPage>('/llm-usage/calls', { params: filters })
}
