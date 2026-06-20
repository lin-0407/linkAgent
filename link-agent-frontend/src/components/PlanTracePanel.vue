<script setup lang="ts">
import type { AgentPlanTrace, AgentWorkerTrace, PlanStepStatus, WorkerStatus } from '@/types/agent'

defineProps<{
  planTrace?: AgentPlanTrace | null
  workerTraces?: AgentWorkerTrace[]
}>()

function statusLabel(status: PlanStepStatus | WorkerStatus) {
  switch (status) {
    case 'SUCCESS':
      return '成功'
    case 'FAILED':
      return '失败'
    case 'SKIPPED':
      return '跳过'
    default:
      return status
  }
}

function clipText(value: string | null | undefined, maxLength = 220) {
  const text = value?.trim() ?? ''
  if (!text) {
    return '无'
  }
  return text.length <= maxLength ? text : `${text.slice(0, maxLength)}...`
}
</script>

<template>
  <details v-if="planTrace" class="plan-trace">
    <summary>查看计划执行轨迹 {{ planTrace.executions.length }} 步</summary>
    <div class="plan-trace-body">
      <section class="plan-trace-overview">
        <strong>{{ planTrace.objective }}</strong>
        <p>{{ planTrace.rationale }}</p>
        <small>{{ planTrace.coverageCheck }}</small>
      </section>

      <div class="plan-trace-list">
        <article
          v-for="execution in planTrace.executions"
          :key="execution.stepId"
          class="plan-trace-step"
          :class="execution.status.toLowerCase()"
        >
          <header>
            <span>{{ execution.stepId }}</span>
            <div>
              <strong>{{ execution.description }}</strong>
              <small>{{ execution.action }}({{ execution.actionInput || '无输入' }})</small>
            </div>
            <b>{{ statusLabel(execution.status) }}</b>
          </header>
          <p v-if="execution.expectedObservation">预期：{{ execution.expectedObservation }}</p>
          <p>观察：{{ clipText(execution.observation) }}</p>
          <p v-if="execution.errorMessage" class="plan-trace-error">
            {{ execution.errorMessage }}
          </p>
        </article>
      </div>
    </div>
  </details>

  <details v-if="workerTraces?.length" class="plan-trace worker-trace">
    <summary>查看 Multi Agent Worker {{ workerTraces.length }} 个</summary>
    <div class="plan-trace-list">
      <article
        v-for="worker in workerTraces"
        :key="`${worker.callId}-${worker.workerName}`"
        class="plan-trace-step"
        :class="worker.status.toLowerCase()"
      >
        <header>
          <span>{{ worker.callId }}</span>
          <div>
            <strong>{{ worker.role }} · {{ worker.workerName }}</strong>
            <small>{{ worker.subTask }}</small>
          </div>
          <b>{{ statusLabel(worker.status) }}</b>
        </header>
        <p>{{ clipText(worker.summary, 320) }}</p>
        <p v-if="worker.errorMessage" class="plan-trace-error">{{ worker.errorMessage }}</p>
        <small>{{ worker.capability }}</small>
      </article>
    </div>
  </details>
</template>
