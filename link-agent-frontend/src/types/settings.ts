export interface RuntimeToggle {
  key: string
  name: string
  enabled: boolean
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
