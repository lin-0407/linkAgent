import { computed, onBeforeUnmount, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { checkSettingsConnectivity } from '@/api/settings'
import type { ConnectivityItem } from '@/types/settings'
import { useWorkflowStore } from '@/stores/workflowStore'

/**
 * 运行时系统状态。
 *
 * 给底部状态栏用，让用户在发起分析前就能看到 LLM/Milvus/Redis 等是否正常，
 * 不用等到请求失败才发现问题。
 *
 * 数据来源（均已核实，不编造后端没有的数据）：
 * 1. 基础设施健康：POST /api/settings/connectivity/check，后端探测 7 项
 *    （MySQL/Redis/Embedding/ChatModel/3个向量库），返回 UP/DOWN/DISABLED。
 * 2. SSE 连接状态：复用 workflowStore.connectionStatus，反映创作台实时通道是否在线。
 *
 * 后端没有"本月调用次数/花费"的跨任务汇总接口（只有单任务粒度），
 * 因此本 composable 不提供这类数据，避免状态栏展示编造的数字。
 */

/** 单项服务的健康等级；探测未完成或请求失败时必须明确标为未知。 */
export type ServiceHealth = 'online' | 'degraded' | 'offline' | 'unknown'

/** 连通性轮询间隔：接口含真实连接探测，不宜太频繁，60s 足够 */
const CONNECTIVITY_POLL_INTERVAL_MS = 60_000

export function useSystemStatus() {
  // ── 状态 ──
  const connectivityItems = ref<ConnectivityItem[]>([])
  const isLoadingConnectivity = ref(false)
  const lastCheckError = ref('')
  const lastCheckTime = ref<number | null>(null)

  // SSE 连接状态从全局 store 读取，避免重复建连
  const workflowStore = useWorkflowStore()
  const { connectionStatus, isConnected } = storeToRefs(workflowStore)

  let pollTimer: ReturnType<typeof setInterval> | null = null

  // ── 计算属性 ──

  /**
   * 整体健康等级：任一关键服务 DOWN 即 offline，有 DISABLED 即 degraded，否则 online。
   * 关键服务判定：chat-model（LLM 调用）和 mysql 是硬依赖，DOWN 直接判离线。
   */
  const overallHealth = computed<ServiceHealth>(() => {
    if (lastCheckError.value || connectivityItems.value.length === 0) return 'unknown'
    const items = connectivityItems.value
    // 硬依赖 DOWN → 离线
    const criticalDown = items.some(
      (item) =>
        (item.key === 'chat-model' || item.key === 'mysql') && item.status === 'DOWN',
    )
    if (criticalDown) return 'offline'
    // 任一项 DOWN → 离线；任一项 DISABLED → 降级
    const hasDown = items.some((item) => item.status === 'DOWN')
    if (hasDown) return 'offline'
    const hasDisabled = items.some((item) => item.status === 'DISABLED')
    if (hasDisabled) return 'degraded'
    return 'online'
  })

  /** LLM 专项状态：状态栏主灯，用户最关心模型能不能用 */
  const llmHealth = computed<ServiceHealth>(() => {
    if (lastCheckError.value || connectivityItems.value.length === 0) return 'unknown'
    const llm = connectivityItems.value.find((item) => item.key === 'chat-model')
    if (!llm) return 'unknown'
    return statusToHealth(llm.status)
  })

  /** 向量库专项状态：知识库检索依赖 */
  const vectorHealth = computed<ServiceHealth>(() => {
    if (lastCheckError.value || connectivityItems.value.length === 0) return 'unknown'
    const vectorItems = connectivityItems.value.filter((item) =>
      item.key.includes('vector'),
    )
    if (vectorItems.length === 0) return 'unknown'
    // 三个向量库任一 DOWN 即离线（检索会受影响），全 DISABLED 才算降级
    const hasDown = vectorItems.some((item) => item.status === 'DOWN')
    if (hasDown) return 'offline'
    const hasDisabled = vectorItems.some((item) => item.status === 'DISABLED')
    if (hasDisabled) return 'degraded'
    return 'online'
  })

  /** 一句话摘要：状态栏右侧文字，说明当前总体情况 */
  const summaryText = computed(() => {
    if (lastCheckError.value) return '状态检查失败，点击重试'
    if (connectivityItems.value.length === 0) {
      return isLoadingConnectivity.value ? '正在检查系统状态' : '系统状态尚未检查'
    }
    const failed = connectivityItems.value.filter((item) => item.status === 'DOWN')
    // 取首个失败项做摘要：length>0 已保证存在，解构收窄类型让 TS 满意
    const [firstFailed] = failed
    if (failed.length > 0 && firstFailed) {
      return `${firstFailed.name} 不可用${failed.length > 1 ? ` 等 ${failed.length} 项` : ''}`
    }
    const disabled = connectivityItems.value.filter((item) => item.status === 'DISABLED')
    if (disabled.length > 0) {
      return `${disabled.length} 项服务降级运行`
    }
    return '全部服务正常'
  })

  /** SSE 连接的中文描述，供状态栏显示实时通道状态 */
  const sseText = computed(() => {
    switch (connectionStatus.value) {
      case 'connected':
        return '实时在线'
      case 'connecting':
        return '连接中'
      case 'reconnecting':
        return '重连中'
      case 'disconnected':
        return '连接中断'
      default:
        return '未连接'
    }
  })

  // ── 方法 ──

  /** 手动刷新连通性，状态栏点击时调用 */
  async function refreshConnectivity() {
    if (isLoadingConnectivity.value) return
    isLoadingConnectivity.value = true
    lastCheckError.value = ''
    try {
      const result = await checkSettingsConnectivity()
      connectivityItems.value = result.items
      lastCheckTime.value = Date.now()
    } catch (error) {
      // 探测接口失败时保留错误并展示未知状态，避免把未取得的结果误报为离线。
      lastCheckError.value = error instanceof Error ? error.message : String(error)
    } finally {
      isLoadingConnectivity.value = false
    }
  }

  /** 启动定时轮询，组件挂载时调用 */
  function startPolling() {
    stopPolling()
    // 首次立即查一次，不等第一个周期
    void refreshConnectivity()
    pollTimer = setInterval(() => {
      void refreshConnectivity()
    }, CONNECTIVITY_POLL_INTERVAL_MS)
  }

  function stopPolling() {
    if (pollTimer !== null) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  // 组件卸载时清理定时器，避免内存泄漏
  onBeforeUnmount(() => {
    stopPolling()
  })

  return {
    connectivityItems,
    isLoadingConnectivity,
    lastCheckError,
    lastCheckTime,
    overallHealth,
    llmHealth,
    vectorHealth,
    summaryText,
    sseText,
    sseConnected: isConnected,
    refreshConnectivity,
    startPolling,
    stopPolling,
  }
}

/** 后端 ConnectivityStatus → 前端健康状态；未识别值不能被推断为降级。 */
function statusToHealth(status: string): ServiceHealth {
  switch (status) {
    case 'UP':
      return 'online'
    case 'DOWN':
      return 'offline'
    case 'DISABLED':
      return 'degraded'
    default:
      return 'unknown'
  }
}
