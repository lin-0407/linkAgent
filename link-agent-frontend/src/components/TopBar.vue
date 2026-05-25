<script setup lang="ts">
defineProps<{
  activeSessionLabel: string
  assistantMessageCount: number
  canUseFullscreen: boolean
  isFullscreen: boolean
  isLoading: boolean
  isCompact: boolean
  latestStepCount: number
  messageCount: number
  sessionCount: number
  sessionId: string
  userMessageCount: number
}>()

defineEmits<{
  toggleFullscreen: []
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
      <div class="topbar-tools" aria-label="Workspace controls">
        <button
          type="button"
          class="tool-button panel-toggle"
          :aria-label="isCompact ? '展开主视觉' : '收起主视觉'"
          title="展开或收起主视觉"
          @click="$emit('togglePanel')"
        >
          <span aria-hidden="true">{{ isCompact ? '⌃' : '⌄' }}</span>
        </button>
        <button
          type="button"
          class="tool-button fullscreen-toggle"
          :class="{ active: isFullscreen }"
          :disabled="!canUseFullscreen"
          :aria-label="isFullscreen ? '退出全屏专注模式' : '进入全屏专注模式'"
          :title="isFullscreen ? '退出全屏' : '全屏专注'"
          @click="$emit('toggleFullscreen')"
        >
          <span class="fullscreen-glyph" aria-hidden="true"></span>
        </button>
      </div>
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
