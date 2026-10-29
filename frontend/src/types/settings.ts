export interface RuntimeToggle {
  key: string
  name: string
  enabled: boolean
  description: string
  /** 当前部署无法在运行期打开；关闭方向仍可能允许。 */
  locked?: boolean
  lockedReason?: string
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
  /** 制作与付费能力开关，和通用运行期开关分开展示。 */
  productionToggles: RuntimeToggle[]
  billing: BillingSettings
}

export interface PriceRange {
  key: string
  name: string
  unit: string
  lowCny: number | string
  highCny: number | string
  note: string
}

export interface BillingSettings {
  usdToCny: number | string
  /** LIVE / CACHED / CONFIG_FALLBACK */
  source: string
  fetchedAt: string
  stale: boolean
  note: string
  ranges: PriceRange[]
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
  activeForText: boolean
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

/** 后续文本调用实际使用的模型和端点，不包含密钥。 */
export type CurrentTextModel = {
  configId: string | null
  source: 'ENVIRONMENT' | 'SAVED_CONFIG'
  provider: string | null
  modelName: string
  baseUrl: string
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
