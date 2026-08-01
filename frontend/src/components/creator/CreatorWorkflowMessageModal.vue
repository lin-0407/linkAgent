<script setup lang="ts">
import { computed } from 'vue'
import { RefreshCw, X } from '@lucide/vue'
import { formatDate } from '@/composables/creator/creatorWorkspaceUtils'
import type { CreatorWorkflowStep } from '@/types/creator'

const props = defineProps<{
  open: boolean
  statusText: string
  sseText: string
  loading: boolean
  steps: CreatorWorkflowStep[]
}>()

const emit = defineEmits<{
  close: []
  refresh: []
}>()

const completedCount = computed(() =>
  props.steps.filter((step) => step.status === 'SUCCESS').length,
)

function stepStatusLabel(status: string) {
  if (status === 'SUCCESS') return '已完成'
  if (status === 'RUNNING') return '执行中'
  if (status === 'FAILED') return '失败'
  return '等待中'
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="creator-modal-backdrop"
      role="presentation"
      @click.self="emit('close')"
    >
      <section
        class="creator-process-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="creator-process-title"
      >
        <header class="creator-process-head">
          <div>
            <p class="creator-kicker">执行过程</p>
            <h3 id="creator-process-title">发布方案处理步骤</h3>
            <span>{{ statusText }} · {{ completedCount }}/{{ steps.length }} 步完成</span>
          </div>
          <div class="creator-process-actions">
            <span class="creator-sse-status" :class="{ active: sseText === '实时连接' }">
              {{ sseText }}
            </span>
            <button
              type="button"
              class="creator-icon-button"
              :disabled="loading"
              :title="loading ? '正在刷新' : '刷新执行过程'"
              aria-label="刷新执行过程"
              @click="emit('refresh')"
            >
              <RefreshCw :size="17" :class="{ spinning: loading }" aria-hidden="true" />
            </button>
            <button
              type="button"
              class="creator-icon-button"
              title="关闭"
              aria-label="关闭执行过程"
              @click="emit('close')"
            >
              <X :size="18" aria-hidden="true" />
            </button>
          </div>
        </header>

        <div class="creator-process-body">
          <ol v-if="steps.length" class="creator-process-list">
            <li
              v-for="step in steps"
              :key="step.stepId"
              :class="`status-${step.status.toLowerCase()}`"
            >
              <span class="creator-process-marker" aria-hidden="true"></span>
              <div>
                <header>
                  <strong>{{ step.stepName || '工作流步骤' }}</strong>
                  <b>{{ stepStatusLabel(step.status) }}</b>
                </header>
                <p v-if="step.errorMessage" class="is-error">{{ step.errorMessage }}</p>
                <p v-else-if="step.outputSummary">{{ step.outputSummary }}</p>
                <p v-else-if="step.inputSummary">{{ step.inputSummary }}</p>
                <small>{{ formatDate(step.startTime || step.createTime) }}</small>
              </div>
            </li>
          </ol>
          <div v-else class="creator-process-empty">
            <strong>{{ loading ? '正在读取执行过程' : '还没有执行步骤' }}</strong>
            <span>生成发布方案后，服务端处理进度会显示在这里。</span>
          </div>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.creator-process-modal {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  width: min(760px, calc(100vw - 32px));
  max-height: min(760px, calc(100dvh - 32px));
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 8px;
  box-shadow: var(--sh-lg);
}

.creator-process-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--border);
}

.creator-process-head > div:first-child {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.creator-process-head h3,
.creator-process-head p {
  margin: 0;
}

.creator-process-head h3 {
  color: var(--ink);
  font-size: 18px;
}

.creator-process-head span {
  color: var(--muted);
  font-size: 12px;
}

.creator-process-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
}

.creator-icon-button {
  display: inline-grid;
  width: 38px;
  height: 38px;
  place-items: center;
  padding: 0;
  color: var(--text);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 6px;
  cursor: pointer;
}

.creator-icon-button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.creator-icon-button .spinning {
  animation: process-spin 900ms linear infinite;
}

.creator-process-body {
  min-height: 0;
  padding: 4px 20px 20px;
  overflow-x: hidden;
  overflow-y: auto;
}

.creator-process-list {
  display: grid;
  margin: 0;
  padding: 0;
  list-style: none;
}

.creator-process-list li {
  position: relative;
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr);
  gap: 10px;
  padding: 16px 0;
  border-bottom: 1px solid var(--border);
}

.creator-process-list li:last-child {
  border-bottom: 0;
}

.creator-process-marker {
  width: 10px;
  height: 10px;
  margin-top: 5px;
  background: var(--surface-sub);
  border: 2px solid var(--muted);
  border-radius: 50%;
}

.status-success .creator-process-marker {
  background: var(--ok);
  border-color: var(--ok);
}

.status-running .creator-process-marker {
  background: var(--accent);
  border-color: var(--accent);
}

.status-failed .creator-process-marker {
  background: var(--danger);
  border-color: var(--danger);
}

.creator-process-list li > div {
  display: grid;
  min-width: 0;
  gap: 6px;
}

.creator-process-list header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.creator-process-list strong {
  overflow-wrap: anywhere;
  color: var(--ink);
  font-size: 14px;
}

.creator-process-list b,
.creator-process-list small {
  color: var(--muted);
  font-size: 12px;
  font-weight: 600;
}

.creator-process-list p {
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--text);
  font-size: 13px;
  line-height: 1.55;
}

.creator-process-list p.is-error {
  color: var(--danger);
}

.creator-process-empty {
  display: grid;
  min-height: 240px;
  place-content: center;
  gap: 6px;
  color: var(--muted);
  text-align: center;
}

.creator-process-empty strong {
  color: var(--ink);
}

@keyframes process-spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 560px) {
  .creator-process-modal {
    width: 100%;
    max-height: calc(100dvh - 24px);
  }

  .creator-process-head {
    align-items: stretch;
    flex-direction: column;
  }

  .creator-process-actions {
    justify-content: space-between;
  }
}

@media (prefers-reduced-motion: reduce) {
  .creator-icon-button .spinning {
    animation: none;
  }
}
</style>
