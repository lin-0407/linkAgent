<script setup lang="ts">
import { onMounted } from 'vue'
import { useSystemStatus } from '@/composables/useSystemStatus'

/**
 * 运行时状态栏。
 *
 * 固定在页面底部，让用户随时知道系统是否健康：
 * - LLM 指示灯：模型能不能用（最关键）
 * - 向量库指示灯：知识库检索是否可用
 * - 实时通道指示灯：创作台 SSE 是否在线
 *
 * 三个灯 + 一句摘要，用绿、黄、红和中性灰表达正常、降级、不可用与未知。
 * 点击状态栏可手动刷新连通性，不必等下一个轮询周期。
 *
 * 不展示"本月调用次数/花费"，因为后端无此跨任务汇总接口，避免编造数字。
 */

const {
  llmHealth,
  vectorHealth,
  sseConnected,
  sseText,
  summaryText,
  isLoadingConnectivity,
  lastCheckError,
  refreshConnectivity,
  startPolling,
} = useSystemStatus()

onMounted(() => {
  // 挂载即开始轮询，首屏立刻有一次探测
  startPolling()
})

// 指示灯颜色 class 映射
function dotClass(health: 'online' | 'degraded' | 'offline' | 'unknown'): string {
  switch (health) {
    case 'online':
      return 'dot-online'
    case 'degraded':
      return 'dot-degraded'
    case 'offline':
      return 'dot-offline'
    case 'unknown':
      return 'dot-unknown'
  }
}

function healthText(health: 'online' | 'degraded' | 'offline' | 'unknown'): string {
  return {
    online: '正常',
    degraded: '降级',
    offline: '不可用',
    unknown: '未知',
  }[health]
}

// SSE 连接映射成三色灯：connected=online, connecting/reconnecting=degraded, 其余=offline
function sseHealth(): 'online' | 'degraded' | 'offline' {
  return sseConnected.value ? 'online' : 'offline'
}
</script>

<template>
  <footer class="system-status-bar" aria-label="系统运行状态">
    <button
      type="button"
      class="system-status-action"
      :disabled="isLoadingConnectivity"
      :aria-label="lastCheckError ? '系统状态检查失败，重新检查' : '重新检查系统运行状态'"
      @click="refreshConnectivity"
    >
      <span class="status-indicators">
        <span class="status-item" :title="`LLM：${healthText(llmHealth)}`">
          <span class="status-dot" :class="dotClass(llmHealth)" aria-hidden="true"></span>
          <span class="status-label">LLM {{ healthText(llmHealth) }}</span>
        </span>
        <span class="status-item" :title="`向量库：${healthText(vectorHealth)}`">
          <span class="status-dot" :class="dotClass(vectorHealth)" aria-hidden="true"></span>
          <span class="status-label">向量库 {{ healthText(vectorHealth) }}</span>
        </span>
        <span class="status-item" :title="`实时通道：${sseText}`">
          <span class="status-dot" :class="dotClass(sseHealth())" aria-hidden="true"></span>
          <span class="status-label">{{ sseText }}</span>
        </span>
      </span>

      <span
        class="status-summary"
        :class="{ 'is-error': lastCheckError }"
        role="status"
        aria-live="polite"
      >{{ summaryText }}</span>
      <span v-if="isLoadingConnectivity" class="status-refreshing" aria-hidden="true">刷新中...</span>
    </button>
  </footer>
</template>

<style scoped>
.system-status-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 60;
  display: block;
  background: var(--surface);
  border-top: 1px solid var(--border);
  font-size: 12px;
  color: var(--muted);
}

.system-status-action {
  display: flex;
  width: 100%;
  min-height: 36px;
  align-items: center;
  gap: var(--s4);
  padding: var(--s2) var(--s5);
  color: inherit;
  background: transparent;
  border: 0;
  cursor: pointer;
  overflow-x: auto;
  white-space: nowrap;
}

.system-status-action:hover:not(:disabled) {
  background: var(--surface-sub);
}

.system-status-action:focus-visible {
  outline: 3px solid var(--accent-ring);
  outline-offset: -3px;
}

.system-status-action:disabled {
  cursor: wait;
}

.status-indicators {
  display: flex;
  align-items: center;
  gap: var(--s4);
}

.status-item {
  display: inline-flex;
  align-items: center;
  gap: var(--s1);
}

.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: var(--r-pill);
  flex-shrink: 0;
}

/* 三色灯：绿/黄/红 */
.dot-online {
  background: #22c55e;
  box-shadow: 0 0 0 2px rgba(34, 197, 94, 0.18);
}

.dot-degraded {
  background: #f59e0b;
  box-shadow: 0 0 0 2px rgba(245, 158, 11, 0.18);
}

.dot-offline {
  background: var(--danger);
  box-shadow: 0 0 0 2px rgba(220, 38, 38, 0.18);
}

.dot-unknown {
  background: #64748b;
  box-shadow: 0 0 0 2px rgba(100, 116, 139, 0.18);
}

.status-label {
  color: var(--ink);
  font-weight: var(--fw-medium);
}

.status-summary {
  margin-left: auto;
  color: var(--muted);
}

.status-summary.is-error {
  color: var(--danger);
  font-weight: var(--fw-semibold);
}

.status-refreshing {
  color: var(--accent);
}

/* 窄屏隐藏固定状态栏，避免它遮挡表单和列表；完整状态仍可在设置中查看。 */
@media (max-width: 640px) {
  .system-status-bar {
    display: none;
  }
}
</style>
