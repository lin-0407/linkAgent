import type {
  ConnectivityCheckResult,
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

// ── P1-4: 用户 LLM/Embedding 配置 ──

/** 列出用户的所有 LLM/Embedding 配置 */
export function listLlmConfigs(userId?: string) {
  return get<UserLlmConfigRecord[]>('/settings/llm-config', {
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
