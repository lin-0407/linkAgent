export interface RuntimeToggle {
  key: string
  name: string
  enabled: boolean
  description: string
}

export interface RuntimeValue {
  key: string
  name: string
  value: string
  options: string[]
  description: string
}

export interface ReadonlySetting {
  key: string
  name: string
  value: string
  description: string
}

export interface SettingsStatus {
  dynamicToggles: RuntimeToggle[]
  dynamicValues: RuntimeValue[]
  readonlySettings: ReadonlySetting[]
}

export type ConnectivityStatus = 'UP' | 'DOWN' | 'DISABLED' | 'UNKNOWN'

export interface ConnectivityItem {
  key: string
  name: string
  status: ConnectivityStatus
  message: string
}

export interface ConnectivityCheckResult {
  items: ConnectivityItem[]
}

// ═══ P1-4: 用户 LLM/Embedding 配置 ═══

export type UserLlmConfigProvider = 'DEEPSEEK' | 'OPENAI' | 'SILICONFLOW' | 'CUSTOM'

/** 用户 LLM 配置记录（API Key 为脱敏值） */
export type UserLlmConfigRecord = {
  configId: string
  userId: string
  provider: UserLlmConfigProvider
  llmBaseUrl: string | null
  /** 脱敏后的 LLM API Key，如 sk-****j8x2 */
  llmApiKeyMasked: string | null
  llmModelName: string | null
  embeddingBaseUrl: string | null
  /** 脱敏后的 Embedding API Key */
  embeddingApiKeyMasked: string | null
  embeddingModelName: string | null
  createTime: string
  updateTime: string
}

/** 保存用户 LLM 配置的请求参数 */
export type UserLlmConfigSavePayload = {
  provider: string
  llmBaseUrl?: string
  /** LLM API Key 明文，为空时不修改已有配置 */
  llmApiKey?: string
  llmModelName?: string
  embeddingBaseUrl?: string
  /** Embedding API Key 明文，为空时不修改已有配置 */
  embeddingApiKey?: string
  embeddingModelName?: string
}

/** LLM 连通性测试结果 */
export type UserLlmConfigTestResult = {
  success: boolean
  elapsedMs: number
  response?: string
  error?: string
}
