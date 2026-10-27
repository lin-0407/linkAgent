import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { SseConnectionStatus } from '@/composables/useWorkflowSSE'

/**
 * 工作流 SSE 连接状态。从 useWorkflowSSE composable 中提升至 Pinia，
 * 使连接状态可被任意组件观察（如全局状态栏、AgentFloatingWindow 等）。
 */
export const useWorkflowStore = defineStore('workflow', () => {
  const connectionStatus = ref<SseConnectionStatus>('idle')
  const lastHeartbeat = ref<number | null>(null)

  const isConnected = computed(() => connectionStatus.value === 'connected')
  const isStreaming = computed(() => isConnected.value)

  const statusText = computed(() => {
    switch (connectionStatus.value) {
      case 'idle':
        return '未连接'
      case 'connecting':
        return '连接中'
      case 'connected':
        return '实时连接'
      case 'reconnecting':
        return '重连中'
      case 'disconnected':
        return '连接中断'
      default:
        // 状态机新增枚举后旧代码仍要能渲染，不能返回 undefined 让界面空白。
        return '未连接'
    }
  })

  return {
    connectionStatus,
    lastHeartbeat,
    isConnected,
    isStreaming,
    statusText,
  }
})
