<script setup lang="ts">
import { computed, ref } from 'vue'
import type { CreatorWorkflowStep } from '@/types/creator'

/**
 * Agent 思考过程时间轴。
 *
 * 替代/补充原来"工作流过程回放"弹窗的技术视角展示，
 * 把每一步翻译成用户能看懂的"读取材料 → 提取要点 → 生成建议"进度。
 *
 * 数据来源：父组件传入的 workflowSteps（由 listWorkflowSteps HTTP 全量拉取）。
 * 后端 SSE 推送的 userLabel/userDetail/durationMs 字段已透传到 useWorkflowSSE 的 handler，
 * 父组件可据此做实时增量刷新（刷新后 workflowSteps 变化，本组件自动重渲染）。
 *
 * 设计取舍：不在此组件内部建立 SSE 连接，保持"纯展示"职责，
 * SSE 生命周期由 useCreatorWorkflow 统一管理，避免多组件重复建连。
 */

const props = withDefaults(
  defineProps<{
    /** 工作流步骤列表，按 startTime/createTime 排序后展示 */
    steps: CreatorWorkflowStep[]
    /** 是否展开全部步骤的详情（默认折叠，点击单步才展开） */
    defaultExpanded?: boolean
  }>(),
  {
    defaultExpanded: false,
  },
)

// stepType → 中文标签，取值与 CreatorWorkspace.workflowStepTypeLabel 保持一致
const STEP_TYPE_LABELS: Record<string, string> = {
  LOAD_CONTEXT: '读取上下文',
  AGENT_REASONING: 'Agent 推理',
  TOOL_CALL: '工具调用',
  LLM_CALL: '模型调用',
  SAVE_RESULT: '保存结果',
  CONFIRM_RESULT: '确认结果',
}

// status → 中文标签 + 时间轴节点视觉态
const STEP_STATUS_LABELS: Record<string, string> = {
  PENDING: '等待中',
  RUNNING: '运行中',
  SUCCESS: '成功',
  FAILED: '失败',
}

// 按开始时间排序，保证时间轴顺序与执行顺序一致
const orderedSteps = computed(() =>
  [...props.steps].sort((a, b) => {
    const ta = a.startTime || a.createTime
    const tb = b.startTime || b.createTime
    return ta.localeCompare(tb)
  }),
)

// 汇总：用于顶部进度概览（已完成/总数、是否有失败、是否在运行）
const summary = computed(() => {
  const total = orderedSteps.value.length
  let done = 0
  let failed = 0
  let running = 0
  for (const step of orderedSteps.value) {
    if (step.status === 'SUCCESS') done++
    else if (step.status === 'FAILED') failed++
    else if (step.status === 'RUNNING') running++
  }
  return { total, done, failed, running }
})

// 展开状态：用 stepId 集合记录哪些步骤被用户点开
// defaultExpanded=true 时全部展开，否则由用户点击切换
const manualExpanded = ref<Set<string>>(new Set())

function isExpanded(stepId: string): boolean {
  return props.defaultExpanded || manualExpanded.value.has(stepId)
}

function toggleExpand(stepId: string) {
  if (props.defaultExpanded) return
  const next = new Set(manualExpanded.value)
  if (next.has(stepId)) next.delete(stepId)
  else next.add(stepId)
  manualExpanded.value = next
}

function typeLabel(stepType: string): string {
  return STEP_TYPE_LABELS[stepType] ?? stepType
}

function statusLabel(status: string): string {
  return STEP_STATUS_LABELS[status] ?? status
}

/** 计算单步耗时，后端 endTime-startTime，转成"2.3s"这种人话 */
function durationText(step: CreatorWorkflowStep): string {
  if (!step.startTime || !step.endTime) return ''
  const start = new Date(step.startTime).getTime()
  const end = new Date(step.endTime).getTime()
  const ms = end - start
  if (ms < 0) return ''
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

// 节点视觉态映射到 class
function nodeClass(status: string): string {
  switch (status) {
    case 'SUCCESS':
      return 'node-done'
    case 'RUNNING':
      return 'node-running'
    case 'FAILED':
      return 'node-failed'
    default:
      return 'node-pending'
  }
}
</script>

<template>
  <section class="analysis-progress" aria-label="分析进度">
    <header class="progress-header">
      <h4>分析进度</h4>
      <!-- 顶部概览：完成数/总数，失败和运行中单独标出 -->
      <span class="progress-summary">
        {{ summary.done }}/{{ summary.total }} 步
        <span v-if="summary.running > 0" class="summary-running">· {{ summary.running }} 进行中</span>
        <span v-if="summary.failed > 0" class="summary-failed">· {{ summary.failed }} 失败</span>
      </span>
    </header>

    <!-- 空状态：还没开始分析 -->
    <p v-if="orderedSteps.length === 0" class="progress-empty">
      触发分析后，这里会实时展示每一步在做什么。
    </p>

    <ol v-else class="timeline">
      <li
        v-for="step in orderedSteps"
        :key="step.stepId"
        class="timeline-item"
        :class="nodeClass(step.status)"
      >
        <!-- 节点图标：✓/●/○/✗，用 CSS 控制 -->
        <span class="timeline-node" :class="nodeClass(step.status)" aria-hidden="true">
          <template v-if="step.status === 'SUCCESS'">✓</template>
          <template v-else-if="step.status === 'FAILED'">✗</template>
          <template v-else-if="step.status === 'RUNNING'">●</template>
          <template v-else>○</template>
        </span>

        <div class="timeline-body">
          <button
            type="button"
            class="timeline-head"
            :disabled="!step.outputSummary && !step.errorMessage && !step.inputSummary"
            @click="toggleExpand(step.stepId)"
          >
            <strong class="timeline-title">{{ step.stepName || typeLabel(step.stepType) }}</strong>
            <span class="timeline-meta">
              <span class="meta-type">{{ typeLabel(step.stepType) }}</span>
              <span class="meta-status">{{ statusLabel(step.status) }}</span>
              <span v-if="durationText(step)" class="meta-duration">{{ durationText(step) }}</span>
            </span>
          </button>

          <!-- 详情区：默认折叠，点击展开看输出摘要/错误/输入 -->
          <div v-if="isExpanded(step.stepId)" class="timeline-detail">
            <p v-if="step.inputSummary" class="detail-row">
              <span>输入</span>{{ step.inputSummary }}
            </p>
            <p v-if="step.outputSummary" class="detail-row">
              <span>输出</span>{{ step.outputSummary }}
            </p>
            <p v-if="step.errorMessage" class="detail-row detail-error">
              <span>错误</span>{{ step.errorMessage }}
            </p>
          </div>
        </div>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.analysis-progress {
  display: grid;
  gap: var(--s3);
  padding: var(--s3);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(248, 252, 255, 0.94));
  border: 1px solid rgba(23, 32, 51, 0.1);
  border-radius: var(--r);
  box-shadow: 0 1px 2px rgba(23, 32, 51, 0.04);
}

.progress-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s3);
}

.progress-header h4 {
  margin: 0;
  font-size: 15px;
  font-weight: var(--fw-semibold);
  color: var(--ink);
}

.progress-summary {
  display: inline-flex;
  align-items: center;
  min-height: 26px;
  padding: 0 var(--s2);
  color: var(--muted);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
}

.progress-summary > span {
  margin-left: 4px;
}

.summary-running {
  color: var(--accent-strong);
}

.summary-failed {
  color: var(--danger);
}

.progress-empty {
  margin: 0;
  padding: var(--s3) 0;
  color: var(--muted);
  text-align: center;
  font-size: 13px;
}

/* 时间轴：每一步使用紧凑行卡片，避免进度信息挤成一整块色块。 */
.timeline {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.timeline-item {
  position: relative;
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr);
  gap: var(--s2);
  min-width: 0;
}

/* 连接线：除最后一个外，每个节点向下延伸竖线 */
.timeline-item:not(:last-child)::before {
  position: absolute;
  top: 30px;
  bottom: -8px;
  left: 14px;
  width: 1px;
  background: rgba(23, 32, 51, 0.1);
  content: '';
}

.timeline-node {
  position: relative;
  z-index: 1;
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  color: var(--muted);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  font-size: 12px;
  font-weight: var(--fw-bold);
  line-height: 1;
}

/* 节点视觉态只作用于圆点，不能污染整行背景。 */
.timeline-node.node-done {
  color: #0f8f5a;
  background: rgba(16, 185, 129, 0.12);
  border-color: rgba(16, 185, 129, 0.28);
}

.timeline-node.node-running {
  color: var(--accent-strong);
  background: var(--accent-tint);
  border-color: var(--accent-ring);
  /* 运行中脉冲动画，让用户知道"正在动"而不是卡死 */
  animation: pulse 1.4s ease-in-out infinite;
}

.timeline-node.node-failed {
  color: var(--danger);
  background: rgba(220, 38, 38, 0.08);
  border-color: rgba(220, 38, 38, 0.24);
}

.timeline-node.node-pending {
  color: var(--muted);
  background: var(--surface-sub);
}

@keyframes pulse {
  0%, 100% { box-shadow: 0 0 0 0 var(--accent-ring); }
  50% { box-shadow: 0 0 0 5px transparent; }
}

@media (prefers-reduced-motion: reduce) {
  .timeline-node.node-running {
    animation: none;
  }
}

.timeline-body {
  min-width: 0;
  padding: 9px var(--s3);
  background: rgba(255, 255, 255, 0.82);
  border: 1px solid rgba(23, 32, 51, 0.08);
  border-radius: var(--r-sm);
}

.timeline-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--s3);
  width: 100%;
  padding: 0;
  background: none;
  border: none;
  text-align: left;
  cursor: pointer;
  font: inherit;
}

.timeline-head:disabled {
  cursor: default;
}

.timeline-title {
  overflow: hidden;
  color: var(--ink);
  font-size: 14px;
  font-weight: var(--fw-semibold);
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.timeline-meta {
  display: inline-flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
  font-size: 11px;
  color: var(--muted);
}

.meta-type,
.meta-status {
  display: inline-flex;
  align-items: center;
  min-height: 20px;
  padding: 0 var(--s2);
  border-radius: var(--r-pill);
  background: var(--surface);
  border: 1px solid var(--border);
  line-height: 1;
}

.timeline-item.node-failed .meta-status {
  color: var(--danger);
  border-color: rgba(220, 38, 38, 0.24);
}

.timeline-item.node-running .meta-status {
  color: var(--accent-strong);
  border-color: var(--accent-ring);
}

.timeline-item.node-done .meta-status {
  color: #0f8f5a;
  border-color: rgba(16, 185, 129, 0.28);
}

.meta-duration {
  display: inline-flex;
  align-items: center;
  font-variant-numeric: tabular-nums;
  color: var(--muted);
}

.timeline-detail {
  display: grid;
  gap: var(--s1);
  margin-top: var(--s2);
  padding: var(--s2);
  background: rgba(248, 250, 252, 0.9);
  border: 1px solid rgba(23, 32, 51, 0.06);
  border-radius: var(--r-sm);
}

.detail-row {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink);
  word-break: break-word;
}

.detail-row span {
  display: inline-block;
  min-width: 36px;
  margin-right: var(--s2);
  color: var(--muted);
  font-weight: var(--fw-medium);
}

.detail-error {
  color: var(--danger);
}

@media (max-width: 720px) {
  .progress-header,
  .timeline-head {
    align-items: flex-start;
  }

  .progress-header {
    flex-direction: column;
  }

  .timeline-head {
    grid-template-columns: 1fr;
    gap: var(--s2);
  }

  .timeline-meta {
    justify-content: flex-start;
  }
}
</style>
