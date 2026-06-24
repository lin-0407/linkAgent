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

/** 工作流 SSE 事件处理器，由调用方提供业务逻辑 */
export interface WorkflowSseHandlers {
  onMessageCreated?: (message: CreatorWorkflowMessage, taskId: string) => void
  onSessionStatus?: (
    status: string | undefined,
    confirmedResultId: string | undefined,
    errorMessage: string | undefined,
  ) => void
  onResultReady?: (taskId: string) => void
  onHeartbeat?: () => void
  onStepStarted?: () => void
  onStepCompleted?: () => void
  onStepFailed?: () => void
}

// ═══════════════════════════════════════════
// 常量
// ═══════════════════════════════════════════

/** 心跳超时阈值：超过此时间未收到任何事件则判定连接僵死，触发主动重连 */
const HEARTBEAT_TIMEOUT_MS = 45_000
/** 心跳检测轮询间隔 */
const HEARTBEAT_CHECK_INTERVAL_MS = 5_000

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

    const url = `/api/creator/tasks/${encodeURIComponent(taskId)}/workflow/sessions/${encodeURIComponent(sessionId)}/events`
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
      step_started: () => {
        if (versionAtConnect !== connectionVersion) return
        recordHeartbeat()
        handlers.onStepStarted?.()
      },
      step_completed: () => {
        if (versionAtConnect !== connectionVersion) return
        recordHeartbeat()
        handlers.onStepCompleted?.()
      },
      step_failed: () => {
        if (versionAtConnect !== connectionVersion) return
        recordHeartbeat()
        handlers.onStepFailed?.()
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
