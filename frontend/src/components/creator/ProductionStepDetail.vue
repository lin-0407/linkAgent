<script setup lang="ts">
import type { ProductionStep, ProductionStepStatus } from '@/types/creatorProduction'

defineProps<{ step: ProductionStep | null; busy: boolean }>()
const emit = defineEmits<{
  update: [status: ProductionStepStatus, rowVersion: number, skipReason?: string]
}>()
const labels = {
  PENDING: '待开始',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  SKIPPED: '已跳过',
}

function update(step: ProductionStep, status: ProductionStepStatus) {
  const reason = status === 'SKIPPED' ? window.prompt('请填写跳过原因')?.trim() : undefined
  if (status === 'SKIPPED' && !reason) return
  emit('update', status, step.rowVersion, reason)
}
</script>

<template>
  <article v-if="step" class="production-step-detail">
    <header>
      <div>
        <p class="production-detail-kicker">{{ step.phase }} · 第 {{ step.sequenceNo }} 步</p>
        <h3>{{ step.stepName }}</h3>
      </div>
      <span class="production-status">{{ labels[step.status] }}</span>
    </header>

    <p class="production-objective">{{ step.objective }}</p>

    <div class="production-detail-grid">
      <section>
        <h4>前置条件</h4>
        <ul>
          <li v-for="item in step.prerequisites" :key="item">{{ item }}</li>
          <li v-if="!step.prerequisites.length">无额外前置条件</li>
        </ul>
      </section>
      <section>
        <h4>操作清单</h4>
        <ol>
          <li v-for="item in step.operations" :key="item">{{ item }}</li>
          <li v-if="!step.operations.length">等待蓝图补充操作</li>
        </ol>
      </section>
      <section>
        <h4>预期产物</h4>
        <ul>
          <li v-for="item in step.expectedOutputs" :key="item">{{ item }}</li>
        </ul>
      </section>
      <section>
        <h4>验收标准</h4>
        <ul>
          <li v-for="item in step.acceptanceCriteria" :key="item">{{ item }}</li>
        </ul>
      </section>
    </div>

    <section v-if="step.toolRefs.length" class="production-tools">
      <h4>工具来源状态</h4>
      <div
        v-for="tool in step.toolRefs"
        :key="`${tool.toolId}-${tool.toolName}`"
        class="production-tool-row"
      >
        <strong>{{ tool.toolName }}</strong>
        <span :class="`tool-${tool.verificationStatus.toLowerCase()}`">
          {{ tool.verificationStatus === 'VERIFIED' ? '已核验' : '需要官方资料' }}
        </span>
        <small>{{ tool.reason || tool.officialUrl }}</small>
      </div>
    </section>

    <footer>
      <button
        type="button"
        :disabled="busy || step.status === 'IN_PROGRESS'"
        @click="update(step, 'IN_PROGRESS')"
      >
        开始执行
      </button>
      <button
        type="button"
        :disabled="busy || step.status === 'COMPLETED'"
        @click="update(step, 'COMPLETED')"
      >
        标记完成
      </button>
      <button
        type="button"
        class="muted-action"
        :disabled="busy || step.status === 'SKIPPED'"
        @click="update(step, 'SKIPPED')"
      >
        跳过步骤
      </button>
    </footer>
  </article>
  <article v-else class="production-empty-detail">
    <strong>选择一个制作步骤</strong>
    <span>步骤导航会保留当前蓝图的执行顺序。</span>
  </article>
</template>

<style scoped>
.production-step-detail,
.production-empty-detail {
  min-width: 0;
  min-height: 0;
  overflow-y: auto;
  padding: 24px 26px;
}

header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
}

.production-detail-kicker {
  margin: 0 0 6px;
  color: var(--accent-strong);
  font-size: 12px;
  font-weight: var(--fw-bold);
}

h3 {
  margin: 0;
  color: var(--ink);
  font-size: 22px;
  letter-spacing: 0;
}

.production-status {
  flex: 0 0 auto;
  color: var(--accent-strong);
  font-size: 12px;
  font-weight: var(--fw-semibold);
  white-space: nowrap;
}

.production-objective {
  max-width: 760px;
  margin: 18px 0 4px;
  color: var(--text);
  line-height: 1.65;
}

.production-detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-top: 18px;
  border-bottom: 1px solid var(--border);
}

.production-detail-grid section {
  min-width: 0;
  padding: 18px 18px 18px 0;
  border-top: 1px solid var(--border);
}

.production-detail-grid section:nth-child(even) {
  padding-right: 0;
  padding-left: 18px;
  border-left: 1px solid var(--border);
}

h4 {
  margin: 0 0 8px;
  color: var(--ink);
  font-size: 13px;
}

ul,
ol {
  margin: 0;
  padding-left: 19px;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.7;
}

.production-tools {
  margin-top: 20px;
  padding-top: 18px;
  border-top: 1px solid var(--border);
}

.production-tool-row {
  display: grid;
  grid-template-columns: auto auto minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  min-height: 44px;
  border-bottom: 1px solid var(--border);
}

.production-tool-row small {
  overflow: hidden;
  color: var(--muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-verified {
  color: var(--ok);
  font-size: 12px;
}

.tool-source_required,
.tool-stale,
.tool-failed {
  color: var(--warn);
  font-size: 12px;
}

footer {
  display: flex;
  flex-wrap: wrap;
  gap: 9px;
  margin-top: 20px;
  padding-top: 18px;
  border-top: 1px solid var(--border);
}

footer button {
  min-height: 44px;
  padding: 0 13px;
  color: var(--accent-strong);
  background: var(--accent-tint);
  border: 1px solid var(--accent-ring);
  border-radius: var(--r-sm);
  cursor: pointer;
}

footer button:hover {
  border-color: var(--accent);
}

footer button:focus-visible {
  outline: 3px solid var(--accent-ring);
  outline-offset: 2px;
}

footer button:disabled {
  opacity: 0.5;
  cursor: wait;
}

footer .muted-action {
  color: var(--muted);
  background: var(--surface-sub);
  border-color: var(--border-strong);
}

.production-empty-detail {
  display: grid;
  place-content: center;
  gap: 8px;
  color: var(--text);
  text-align: center;
}

.production-empty-detail span {
  color: var(--muted);
  font-size: 13px;
}

@media (max-width: 680px) {
  .production-step-detail,
  .production-empty-detail {
    padding: 20px 16px;
  }

  .production-detail-grid {
    grid-template-columns: 1fr;
  }

  .production-detail-grid section,
  .production-detail-grid section:nth-child(even) {
    padding: 16px 0;
    border-left: 0;
  }

  .production-tool-row {
    grid-template-columns: 1fr auto;
    gap: 4px 10px;
    padding: 8px 0;
  }

  .production-tool-row small {
    grid-column: 1 / -1;
    white-space: normal;
  }
}
</style>
