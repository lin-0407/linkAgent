import type {
  ConnectivityCheckResult,
  CurrentTextModel,
  SettingsStatus,
  UserLlmConfigRecord,
  UserLlmConfigSavePayload,
  UserLlmConfigTestResult,
} from '@/types/settings'
import { del, get, post, put } from './http'
import { cleanPayload } from './http'

export function getSettingsStatus() {
  return get<SettingsStatus>('/settings/status')
}

/** 运行时开关切换，后端返回 204 No Content，http.put 拦截器自动返回 undefined */
export function updateRuntimeToggle(settingKey: string, enabled: boolean) {
  return put(`/settings/toggles/${encodeURIComponent(settingKey)}`, { enabled })
}

/** 运行时枚举值切换，后端返回 204 No Content */
export function updateRuntimeValue(settingKey: string, value: string) {
  return put(`/settings/values/${encodeURIComponent(settingKey)}`, { value })
}

export function checkSettingsConnectivity() {
  return post<ConnectivityCheckResult>('/settings/connectivity/check', {})
}

/** 强制刷新公开汇率；只影响后续新调用，旧价格快照不变。 */
export function refreshBillingRate() {
  return post<SettingsStatus['billing']>('/settings/billing/refresh', {})
}

// ── P1-4: 用户 LLM/Embedding 配置 ──

/** 列出用户的所有 LLM/Embedding 配置 */
export function listLlmConfigs(userId?: string) {
  return get<UserLlmConfigRecord[]>('/settings/llm-config', {
    params: userId ? { userId } : undefined,
  })
}

export function getCurrentTextModel() {
  return get<CurrentTextModel>('/settings/llm-config/current-text')
}

/** 显式切换后续文本调用；保存配置与选择配置分开。 */
export function activateTextLlmConfig(configId: string, userId?: string) {
  return post<UserLlmConfigRecord>(`/settings/llm-config/${encodeURIComponent(configId)}/activate`, undefined, {
    params: userId ? { userId } : undefined,
  })
}

export function useEnvironmentTextModel(userId?: string) {
  return post<void>('/settings/llm-config/use-environment', undefined, {
    params: userId ? { userId } : undefined,
  })
}

/** 保存或更新用户 LLM/Embedding 配置 */
export function saveLlmConfig(payload: UserLlmConfigSavePayload, userId?: string) {
  return post<UserLlmConfigRecord>('/settings/llm-config', cleanPayload(payload), {
    params: userId ? { userId } : undefined,
  })
}

/** 软删除用户 LLM/Embedding 配置 */
export function deleteLlmConfig(configId: string, userId?: string) {
  return del(`/settings/llm-config/${encodeURIComponent(configId)}`, {
    params: userId ? { userId } : undefined,
  })
}

/** 测试 LLM 连通性 */
export function testLlmConnectivity(configId: string, userId?: string) {
  return post<UserLlmConfigTestResult>(
    `/settings/llm-config/${encodeURIComponent(configId)}/test`,
    undefined,
    { params: userId ? { userId } : undefined },
  )
}
