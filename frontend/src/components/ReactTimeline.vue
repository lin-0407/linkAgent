<script setup lang="ts">
import { computed } from 'vue'
import type { AgentStep } from '@/types/agent'

const props = defineProps<{
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

/**
 * 折叠标题里带上本轮调用过的工具名。
 * 为什么这么做：流式期间的「步骤 N」实时条在回答产出后会收进这个折叠块，
 * 只写「查看 ReAct 推理轨迹 N 步」的话，用户会觉得"刚才那一步凭空消失了"。
 * 带上工具名后，不展开也能看到这一轮到底调了什么。
 */
const summaryText = computed(() => {
  const actions = props.steps
    .map((step) => step.action?.trim())
    .filter((action): action is string => Boolean(action))
  const unique = Array.from(new Set(actions))
  if (unique.length === 0) {
    return `查看 ReAct 推理轨迹 ${props.steps.length} 步`
  }
  const shown = unique.slice(0, 3).join('、')
  const suffix = unique.length > 3 ? `${shown} 等 ${unique.length} 个工具` : shown
  return `查看 ReAct 推理轨迹 ${props.steps.length} 步 · ${suffix}`
})
</script>

<template>
  <details v-if="steps.length" class="steps">
    <summary>{{ summaryText }}</summary>
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
          <!--
            观察内容是工具返回的原文，可能很长且带换行。
            用 pre + pre-wrap 保住原始换行，再限高滚动，避免像以前那样糊成一大坨还把窗口撑爆。
          -->
          <div v-if="step.observation?.trim()" class="step-block step-observation-block">
            <b>观察</b>
            <pre class="step-observation">{{ step.observation }}</pre>
          </div>
          <small v-if="!hasStepDetail(step)" class="step-empty">
            后端返回了步骤编号，但没有返回本步的思考、行动或观察内容。
          </small>
        </div>
      </section>
    </div>
  </details>
</template>

<style scoped>
.step-observation-block {
  display: block;
}

.step-observation {
  margin: 0.35em 0 0;
  padding: 0.55em 0.7em;
  /* 保留工具原文的换行与缩进，同时允许长行换行，不再横向溢出 */
  white-space: pre-wrap;
  word-break: break-word;
  background: rgba(15, 23, 42, 0.05);
  border-left: 2px solid rgba(15, 23, 42, 0.15);
  border-radius: 4px;
  font-family: inherit;
  font-size: 0.86em;
  line-height: 1.6;
  /* 限高滚动：单步观察再长也不会把整个对话窗口撑开 */
  max-height: 13em;
  overflow-y: auto;
}
</style>
