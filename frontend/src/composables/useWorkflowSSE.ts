import { onBeforeUnmount, ref } from 'vue'
import { storeToRefs } from 'pinia'
import type { CreatorWorkflowMessage } from '@/types/creator'
import { useWorkflowStore } from '@/stores/workflowStore'

// ═══════════════════════════════════════════
// 类型定义
// ═══════════════════════════════════════════

/** SSE 连接状态机 */
export type SseConnectionStatus =
  | 'idle'           // 从未连接或已主动断开
  | 'connecting'     // connect() 已调用，等待 onopen
  | 'connected'      // onopen 已触发
  | 'reconnecting'   // onerror 触发且浏览器自动重连中（readyState === CONNECTING）
  | 'disconnected'   // 连接已彻底关闭（readyState === CLOSED）

/**
 * 步骤类 SSE 事件携带的载荷。
 *
 * 后端 CreatorWorkflowService.buildStepPayload 推送的字段，原本被前端无参 handler 丢弃。
 * 现在透传给调用方，用于紧凑状态条和过程详情实时更新：
 * 后端推 step_started 时用 userLabel 立即显示一个"运行中"节点，不必等 HTTP 全量刷新。
 */
export interface WorkflowStepEvent {
  stepId: string
  stepType?: string
  stepName?: string
  status?: string
  /** 面向用户的步骤标题，如"提取内容要点"，由后端给出，前端无需理解业务 */
  userLabel?: string
  /** 面向用户的步骤详情，如"识别到 3 个核心论点" */
  userDetail?: string
  inputSummary?: string
  outputSummary?: string
  errorMessage?: string
  /** 步骤耗时毫秒，仅 step_completed/step_failed 事件可能携带 */
  durationMs?: number
}

/** 工作流 SSE 事件处理器，由调用方提供业务逻辑 */
export interface WorkflowSseHandlers {
  onMessageCreated?: (message: CreatorWorkflowMessage, taskId: string) => void
  onSessionStatus?: (
    status: string | undefined,
    confirmedResultId: string | undefined,
    errorMessage: string | undefined,
    planGenerationCount: number | undefined,
  ) => void
  onResultReady?: (taskId: string) => void
  onHeartbeat?: () => void
  /** 步骤开始：后端推送 userLabel/userDetail，可用于即时显示"运行中"节点 */
  onStepStarted?: (event: WorkflowStepEvent) => void
  /** 步骤完成：携带 durationMs，用于展示每步耗时 */
  onStepCompleted?: (event: WorkflowStepEvent) => void
  /** 步骤失败：携带 errorMessage，用于时间轴标红 */
  onStepFailed?: (event: WorkflowStepEvent) => void
}

// ═══════════════════════════════════════════
// 常量
// ═══════════════════════════════════════════

/** 心跳超时阈值：超过此时间未收到任何事件则判定连接僵死，触发主动重连 */
const HEARTBEAT_TIMEOUT_MS = 45_000
/** 心跳检测轮询间隔 */
const HEARTBEAT_CHECK_INTERVAL_MS = 5_000
/** SSE 与 axios 使用同一 API 前缀，避免分域部署时普通请求和消息流地址不一致 */
const API_BASE_URL = normalizeApiBaseUrl(import.meta.env.VITE_API_BASE_URL || '/api')

// ═══════════════════════════════════════════
// Composable
// ═══════════════════════════════════════════

export function useWorkflowSSE() {
  // 连接状态已提升至 Pinia workflowStore，其他组件可直接观察 isConnected / statusText
  const workflowStore = useWorkflowStore()
  const { connectionStatus, lastHeartbeat, isConnected, isStreaming, statusText } =
    storeToRefs(workflowStore)

  // EventSource 实例仍由 composable 私有管理（原始 DOM 对象不适合进 store）
  const eventSource = ref<EventSource | null>(null)

  // 保存最后连接参数，供 reconnect() 复用
  let lastTaskId: string | null = null
  let lastSessionId: string | null = null
  let lastHandlers: WorkflowSseHandlers | null = null

  /**
   * 连接版本号：每次 connect() 调用时递增。
   * 每个事件监听器闭包捕获当时的版本号，事件到达时校验：
   * 版本不匹配表示该事件来自已被取代的旧连接，直接丢弃，防止旧连接"复活"污染当前状态。
   */
  let connectionVersion = 0

  /** 心跳检测定时器句柄 */
  let heartbeatTimer: ReturnType<typeof setInterval> | null = null

  // ── 公共方法 ──

  /**
   * 建立 SSE 连接并注册事件处理器。
   * 会自动断开旧连接（如果存在），连接参数和 handlers 被保存供 reconnect() 复用。
   */
  function connect(taskId: string, sessionId: string, handlers: WorkflowSseHandlers) {
    disconnect()

    lastTaskId = taskId
    lastSessionId = sessionId
    lastHandlers = handlers
    connectionVersion++
    const versionAtConnect = connectionVersion

    workflowStore.connectionStatus = 'connecting'

    const url = buildWorkflowEventUrl(taskId, sessionId)
    const es = new EventSource(url)
    eventSource.value = es

    es.onopen = () => {
      if (versionAtConnect !== connectionVersion) return
      workflowStore.connectionStatus = 'connected'
      recordHeartbeat()
    }

    es.onerror = () => {
      if (versionAtConnect !== connectionVersion) return
      if (es.readyState === EventSource.CONNECTING) {
        workflowStore.connectionStatus = 'reconnecting'
        recordHeartbeat()
      } else {
        workflowStore.connectionStatus = 'disconnected'
        stopHeartbeatCheck()
      }
    }

    // 注册 7 种命名 SSE 事件，每类事件先做版本校验再调对应 handler
    const eventMap: Record<string, (payload: Record<string, unknown>) => void> = {
      message_created: (payload) => {
        if (versionAtConnect !== connectionVersion) return
        if (handlers.onMessageCreated && isWorkflowMessage(payload)) {
          handlers.onMessageCreated(payload, taskId)
        }
      },
      session_status: (payload) => {
        if (versionAtConnect !== connectionVersion) return
        handlers.onSessionStatus?.(
          readStringField(payload, 'status'),
          readStringField(payload, 'confirmedResultId'),
          readStringField(payload, 'errorMessage'),
          readNumberField(payload, 'planGenerationCount'),
        )
      },
      result_ready: (payload) => {
        if (versionAtConnect !== connectionVersion) return
        const resultTaskId = readStringField(payload, 'taskId') || taskId
        handlers.onResultReady?.(resultTaskId)
      },
      heartbeat: () => {
        if (versionAtConnect !== connectionVersion) return
        recordHeartbeat()
        handlers.onHeartbeat?.()
      },
      step_started: (payload) => {
        if (versionAtConnect !== connectionVersion) return
        recordHeartbeat()
        handlers.onStepStarted?.(toStepEvent(payload))
      },
      step_completed: (payload) => {
        if (versionAtConnect !== connectionVersion) return
        recordHeartbeat()
        handlers.onStepCompleted?.(toStepEvent(payload))
      },
      step_failed: (payload) => {
        if (versionAtConnect !== connectionVersion) return
        recordHeartbeat()
        handlers.onStepFailed?.(toStepEvent(payload))
      },
    }

    Object.entries(eventMap).forEach(([eventName, handler]) => {
      es.addEventListener(eventName, (event: Event) => {
        const rawData = parseSseData((event as MessageEvent).data)
        if (rawData) {
          handler((rawData.payload as Record<string, unknown>) ?? rawData)
        }
      })
    })

    startHeartbeatCheck()
  }

  /** 断开当前 SSE 连接并重置所有状态 */
  function disconnect() {
    stopHeartbeatCheck()
    if (eventSource.value) {
      eventSource.value.close()
      eventSource.value = null
    }
    workflowStore.connectionStatus = 'idle'
    workflowStore.lastHeartbeat = null
  }

  /** 使用最后一次 connect() 的参数和 handlers 重新建立连接 */
  function reconnect() {
    if (lastTaskId && lastSessionId && lastHandlers) {
      connect(lastTaskId, lastSessionId, lastHandlers)
    }
  }

  // ── 心跳检测 ──

  function recordHeartbeat() {
    workflowStore.lastHeartbeat = Date.now()
  }

  /** 启动心跳超时检测：定时检查距上次收事件是否超过阈值，超时则主动关闭重连 */
  function startHeartbeatCheck() {
    stopHeartbeatCheck()
    heartbeatTimer = setInterval(() => {
      if (workflowStore.lastHeartbeat === null || workflowStore.connectionStatus !== 'connected') return
      const elapsed = Date.now() - workflowStore.lastHeartbeat
      if (elapsed > HEARTBEAT_TIMEOUT_MS) {
        stopHeartbeatCheck()
        if (eventSource.value) {
          eventSource.value.close()
          eventSource.value = null
        }
        workflowStore.connectionStatus = 'disconnected'
        reconnect()
      }
    }, HEARTBEAT_CHECK_INTERVAL_MS)
  }

  function stopHeartbeatCheck() {
    if (heartbeatTimer !== null) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  // 组件卸载时自动断开连接，防止 SSE 泄漏
  onBeforeUnmount(() => {
    disconnect()
  })

  return {
    // 从 store 解构出的 refs，保持与原来完全相同的返回值签
    connectionStatus,
    lastHeartbeat,
    isConnected,
    isStreaming,
    statusText,
    connect,
    disconnect,
    reconnect,
  }
}

// ═══════════════════════════════════════════
// 内部工具（模块私有，仅供 useWorkflowSSE 使用）
// ═══════════════════════════════════════════

function normalizeApiBaseUrl(value: string): string {
  const trimmed = value.trim()
  if (!trimmed || trimmed === '/') return ''
  return trimmed.endsWith('/') ? trimmed.slice(0, -1) : trimmed
}

function buildWorkflowEventUrl(taskId: string, sessionId: string): string {
  return `${API_BASE_URL}/creator/tasks/${encodeURIComponent(taskId)}/workflow/sessions/${encodeURIComponent(sessionId)}/events`
}

function parseSseData(rawData: string): Record<string, unknown> | null {
  try {
    return JSON.parse(rawData)
  } catch {
    return null
  }
}

function isWorkflowMessage(value: unknown): value is CreatorWorkflowMessage {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as Record<string, unknown>).messageId === 'string' &&
    typeof (value as Record<string, unknown>).sessionId === 'string' &&
    typeof (value as Record<string, unknown>).content === 'string' &&
    typeof (value as Record<string, unknown>).sequenceNo === 'number'
  )
}

function readStringField(obj: unknown, key: string): string | undefined {
  if (typeof obj === 'object' && obj !== null) {
    const value = (obj as Record<string, unknown>)[key]
    return typeof value === 'string' ? value : undefined
  }
  return undefined
}

function readNumberField(obj: unknown, key: string): number | undefined {
  if (!obj || typeof obj !== 'object') return undefined
  const value = (obj as Record<string, unknown>)[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

/**
 * 把后端 step 事件的原始 payload 整理成 WorkflowStepEvent。
 * 字段缺失时给安全默认值，保证调用方拿到的对象结构稳定，
 * 时间轴组件不需要逐字段判空。
 */
function toStepEvent(payload: Record<string, unknown>): WorkflowStepEvent {
  return {
    stepId: readStringField(payload, 'stepId') ?? '',
    stepType: readStringField(payload, 'stepType'),
    stepName: readStringField(payload, 'stepName'),
    status: readStringField(payload, 'status'),
    userLabel: readStringField(payload, 'userLabel'),
    userDetail: readStringField(payload, 'userDetail'),
    inputSummary: readStringField(payload, 'inputSummary'),
    outputSummary: readStringField(payload, 'outputSummary'),
    errorMessage: readStringField(payload, 'errorMessage'),
    // durationMs 后端按数字推送，这里做一次类型收敛
    durationMs:
      typeof payload.durationMs === 'number' ? payload.durationMs : undefined,
  }
}
