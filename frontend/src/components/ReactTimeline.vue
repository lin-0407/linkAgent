<script setup lang="ts">
import type { AgentStep } from '@/types/agent'

defineProps<{
  steps: AgentStep[]
}>()

function hasStepDetail(step: AgentStep) {
  return Boolean(step.thought?.trim() || step.action?.trim() || step.observation?.trim())
}

function formatAction(step: AgentStep) {
  if (!step.action) {
    return ''
  }

  if (!step.actionInput?.trim()) {
    return step.action
  }

  return `${step.action}(${step.actionInput})`
}
</script>

<template>
  <details v-if="steps.length" class="steps">
    <summary>查看 ReAct 推理轨迹 {{ steps.length }} 步</summary>
    <div class="step-timeline">
      <section
        v-for="(step, index) in steps"
        :key="`${step.stepNumber}-${index}`"
        class="timeline-item"
        :class="{ muted: !hasStepDetail(step) }"
      >
        <span class="timeline-index">{{ step.stepNumber }}</span>
        <div class="timeline-content">
          <strong>第 {{ step.stepNumber }} 步</strong>
          <p v-if="step.thought?.trim()" class="step-block">
            <b>思考</b>
            {{ step.thought }}
          </p>
          <code v-if="step.action?.trim()" class="step-block">
            {{ formatAction(step) }}
          </code>
          <small v-if="step.observation?.trim()" class="step-block">
            <b>观察</b>
            {{ step.observation }}
          </small>
          <small v-if="!hasStepDetail(step)" class="step-empty">
            后端返回了步骤编号，但没有返回本步的思考、行动或观察内容。
          </small>
        </div>
      </section>
    </div>
  </details>
</template>
