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
 * 三个灯 + 一句摘要，用三色（绿/黄/红）表达 online/degraded/offline。
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
  refreshConnectivity,
  startPolling,
} = useSystemStatus()

onMounted(() => {
  // 挂载即开始轮询，首屏立刻有一次探测
  startPolling()
})

// 指示灯颜色 class 映射
function dotClass(health: 'online' | 'degraded' | 'offline'): string {
  switch (health) {
    case 'online':
      return 'dot-online'
    case 'degraded':
      return 'dot-degraded'
    case 'offline':
      return 'dot-offline'
  }
}

// SSE 连接映射成三色灯：connected=online, connecting/reconnecting=degraded, 其余=offline
function sseHealth(): 'online' | 'degraded' | 'offline' {
  return sseConnected.value ? 'online' : 'offline'
}
</script>

<template>
  <footer
    class="system-status-bar"
    role="status"
    aria-label="系统运行状态"
    @click="refreshConnectivity"
  >
    <!-- 三组指示灯：LLM / 向量库 / 实时通道 -->
    <div class="status-indicators">
      <span class="status-item" :title="`LLM: ${llmHealth}`">
        <span class="status-dot" :class="dotClass(llmHealth)" aria-hidden="true"></span>
        <span class="status-label">LLM</span>
      </span>
      <span class="status-item" :title="`向量库: ${vectorHealth}`">
        <span class="status-dot" :class="dotClass(vectorHealth)" aria-hidden="true"></span>
        <span class="status-label">向量库</span>
      </span>
      <span class="status-item" :title="`实时通道: ${sseText}`">
        <span class="status-dot" :class="dotClass(sseHealth())" aria-hidden="true"></span>
        <span class="status-label">{{ sseText }}</span>
      </span>
    </div>

    <!-- 摘要文字：正常时一句话，异常时点出具体问题 -->
    <span v-if="summaryText" class="status-summary">{{ summaryText }}</span>

    <!-- 刷新指示：点击状态栏刷新时转圈提示 -->
    <span v-if="isLoadingConnectivity" class="status-refreshing" aria-hidden="true">
      刷新中…
    </span>
  </footer>
</template>

<style scoped>
.system-status-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 60;
  display: flex;
  align-items: center;
  gap: var(--s4);
  padding: var(--s2) var(--s5);
  background: var(--surface);
  border-top: 1px solid var(--border);
  font-size: 12px;
  color: var(--muted);
  cursor: pointer;
  user-select: none;
  /* 底栏内容超出时横向滚动，避免挤压指示灯 */
  overflow-x: auto;
  white-space: nowrap;
}

.system-status-bar:hover {
  background: var(--surface-sub);
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
  /* 离线时灯闪烁，强化警示 */
  animation: blink 1.6s ease-in-out infinite;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

@media (prefers-reduced-motion: reduce) {
  .dot-offline {
    animation: none;
  }
}

.status-label {
  color: var(--ink);
  font-weight: var(--fw-medium);
}

.status-summary {
  margin-left: auto;
  color: var(--muted);
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
