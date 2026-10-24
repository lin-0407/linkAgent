/**
 * 全站唯一的 API 基址来源。
 *
 * 之前 axios 实例、流式对话、工作流 SSE、发布前试映各写了一遍
 * `import.meta.env.VITE_API_BASE_URL || '/api'`，任何一次改动都要同步四处，
 * 漏改就会出现「普通请求打到 A、消息流打到 B」的错位。
 *
 * 统一在这里归一化：去掉结尾斜杠，避免拼出 `//creator/tasks` 这类地址
 * （部分网关会把双斜杠当成不同路由）。默认值仍为同源 `/api`。
 */
const configuredBaseUrl = (import.meta.env.VITE_API_BASE_URL || '/api').trim()

export const API_BASE_URL = configuredBaseUrl.endsWith('/')
  ? configuredBaseUrl.slice(0, -1)
  : configuredBaseUrl
