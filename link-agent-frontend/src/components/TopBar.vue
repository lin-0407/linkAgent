<script setup lang="ts">
defineProps<{
  activeSessionLabel: string
  assistantMessageCount: number
  isLoading: boolean
  isCompact: boolean
  latestStepCount: number
  messageCount: number
  sessionCount: number
  sessionId: string
  userMessageCount: number
}>()

defineEmits<{
  togglePanel: []
}>()
</script>

<template>
  <header class="topbar" :class="{ compact: isCompact }">
    <div class="topbar-copy">
      <p class="eyebrow">ReAct Control Surface</p>
      <h2>让推理过程直接撞上屏幕</h2>
      <p>会话、记忆、工具调用和观察结果在同一个工作台里展开。</p>
    </div>
    <div class="topbar-actions">
      <button
        type="button"
        class="panel-toggle"
        :aria-label="isCompact ? '展开主视觉' : '收起主视觉'"
        @click="$emit('togglePanel')"
      >
        <span aria-hidden="true">{{ isCompact ? '⌃' : '⌄' }}</span>
      </button>
      <div class="topbar-stats" aria-label="Message statistics">
        <span>
          <strong>{{ isLoading ? 'RUN' : 'IDLE' }}</strong>
          状态
        </span>
        <span>
          <strong>{{ userMessageCount }}/{{ assistantMessageCount }}</strong>
          问答
        </span>
        <span>
          <strong>{{ messageCount }}</strong>
          消息
        </span>
        <span>
          <strong>{{ latestStepCount }}</strong>
          ReAct
        </span>
        <span>
          <strong>{{ sessionCount }}</strong>
          会话
        </span>
        <span class="session-chip">
          <strong>{{ sessionId ? 'LIVE' : 'NEW' }}</strong>
          {{ activeSessionLabel }}
        </span>
      </div>
    </div>
  </header>
</template>
