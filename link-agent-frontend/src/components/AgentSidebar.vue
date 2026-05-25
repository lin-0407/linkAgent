<script setup lang="ts">
import type { SessionListItem } from '@/types/agent'

defineProps<{
  activeSessionLabel: string
  isLoading: boolean
  isSessionsLoading: boolean
  isSessionsOpen: boolean
  latestStepCount: number
  messageCount: number
  sessionId: string
  sessions: SessionListItem[]
  sessionsError: string
}>()

defineEmits<{
  openSession: [session: SessionListItem]
  startNewSession: []
  toggleSessions: []
}>()

function shortSessionId(value: string) {
  return value.length <= 12 ? value : `${value.slice(0, 6)}...${value.slice(-4)}`
}

function formatSessionPreview(value: string) {
  if (!value.trim()) {
    return '这个会话还没有摘要'
  }

  return value.length <= 44 ? value : `${value.slice(0, 44)}...`
}
</script>

<template>
  <aside class="sidebar">
    <div class="brand">
      <span class="brand-mark" aria-hidden="true">
        <i></i>
        <b>LA</b>
      </span>
      <div>
        <p class="eyebrow">Link Agent</p>
        <h1>ReAct Atelier</h1>
      </div>
    </div>

    <section class="sidebar-manifesto">
      <span>Qwen / DeepSeek / Claude</span>
      <strong>把后端 Agent 的推理现场，做成作品集里最醒目的主入口。</strong>
    </section>

    <section class="panel">
      <span class="label">Session</span>
      <code>{{ activeSessionLabel }}</code>
      <div class="sidebar-actions">
        <button type="button" class="secondary-button" @click="$emit('toggleSessions')">
          会话
        </button>
        <button type="button" class="secondary-button primary-lite" @click="$emit('startNewSession')">
          新建
        </button>
      </div>
      <div v-if="isSessionsOpen" class="session-list">
        <button v-if="isSessionsLoading" type="button" class="session-item muted" disabled>
          正在读取会话...
        </button>
        <template v-else>
          <button
            v-for="item in sessions"
            :key="item.sessionId"
            type="button"
            class="session-item"
            :class="{ active: item.sessionId === sessionId }"
            @click="$emit('openSession', item)"
          >
            <span class="session-title">
              <strong>{{ shortSessionId(item.sessionId) }}</strong>
              <small v-if="item.sessionId === sessionId">当前</small>
            </span>
            <span>{{ formatSessionPreview(item.preview) }}</span>
            <small>{{ item.messageCount }} 条消息 · 继续这个会话</small>
          </button>
          <p v-if="sessions.length === 0" class="empty-sessions">还没有保存的会话</p>
        </template>
        <p v-if="sessionsError" class="session-error">{{ sessionsError }}</p>
      </div>
    </section>

    <section class="panel">
      <span class="label">Status</span>
      <div class="status-line">
        <span class="status-dot" :class="{ running: isLoading }"></span>
        <strong>{{ isLoading ? 'Agent 正在思考' : '可以开始提问' }}</strong>
      </div>
      <dl class="metrics">
        <div>
          <dt>会话</dt>
          <dd>{{ sessions.length }}</dd>
        </div>
        <div>
          <dt>消息</dt>
          <dd>{{ messageCount }}</dd>
        </div>
        <div>
          <dt>步骤</dt>
          <dd>{{ latestStepCount }}</dd>
        </div>
      </dl>
    </section>
  </aside>
</template>
