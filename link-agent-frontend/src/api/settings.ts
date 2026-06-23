import type { ConnectivityCheckResult, SettingsStatus } from '@/types/settings'
import { get, post, put } from './http'

export function getSettingsStatus() {
  return get<SettingsStatus>('/settings/status')
}

/** 运行时开关切换，后端返回 204 No Content，http.put 拦截器自动返回 undefined */
export function updateRuntimeToggle(settingKey: string, enabled: boolean) {
  return put(`/settings/toggles/${encodeURIComponent(settingKey)}`, { enabled })
}

export function checkSettingsConnectivity() {
  return post<ConnectivityCheckResult>('/settings/connectivity/check', {})
}
