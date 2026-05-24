<script setup lang="ts">
import type { AgentStep } from '@/types/agent'

defineProps<{
  steps: AgentStep[]
}>()
</script>

<template>
  <details v-if="steps.length" class="steps">
    <summary>查看 ReAct 推理轨迹 {{ steps.length }} 步</summary>
    <div class="step-timeline">
      <section v-for="step in steps" :key="step.stepNumber" class="timeline-item">
        <span class="timeline-index">{{ step.stepNumber }}</span>
        <div class="timeline-content">
          <strong>第 {{ step.stepNumber }} 步</strong>
          <p v-if="step.thought" class="step-block">
            <b>思考</b>
            {{ step.thought }}
          </p>
          <code v-if="step.action" class="step-block">
            {{ step.action }}({{ step.actionInput }})
          </code>
          <small v-if="step.observation" class="step-block">
            <b>观察</b>
            {{ step.observation }}
          </small>
        </div>
      </section>
    </div>
  </details>
</template>
