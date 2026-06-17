import type { ConnectivityCheckResult, SettingsStatus } from '@/types/settings'

// 设置面板接口封装。保持和 knowledge.ts 一样的模块私有 requestJson，避免本阶段扩大到公共请求层重构。
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

  if (response.status === 204) {
    return undefined as T
  }

  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}

async function readErrorMessage(response: Response) {
  try {
    const data = (await response.json()) as { message?: string; error?: string; detail?: string }
    return data.message || data.detail || data.error
  } catch {
    return ''
  }
}

export function getSettingsStatus() {
  return requestJson<SettingsStatus>('/api/settings/status')
}

export function updateRuntimeToggle(settingKey: string, enabled: boolean) {
  return requestJson<void>(`/api/settings/toggles/${encodeURIComponent(settingKey)}`, {
    method: 'PUT',
    body: JSON.stringify({ enabled }),
  })
}

export function checkSettingsConnectivity() {
  return requestJson<ConnectivityCheckResult>('/api/settings/connectivity/check', {
    method: 'POST',
    body: JSON.stringify({}),
  })
}
