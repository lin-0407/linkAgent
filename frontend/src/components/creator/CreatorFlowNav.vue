<script setup lang="ts">
import { computed, type Component } from 'vue'
import {
  ArrowRight,
  BarChart3,
  Clapperboard,
  FileText,
  ListChecks,
  MessageSquareText,
  MonitorPlay,
} from '@lucide/vue'

type CreatorWorkStep = 'prePublish' | 'production' | 'preflight' | 'feedback' | 'report'
type CreatorStepKey = 'task' | CreatorWorkStep

type CreatorStepMeta = {
  key: CreatorStepKey
  label: string
  shortLabel: string
  description: string
}

const stepIcons: Record<CreatorStepKey, Component> = {
  task: FileText,
  prePublish: ListChecks,
  production: Clapperboard,
  preflight: MonitorPlay,
  feedback: MessageSquareText,
  report: BarChart3,
}

const {
  activeStep,
  steps,
  activeStepIndex,
  activeStepMeta,
  progressPercent,
  canNavigate,
  isCompleted,
} = defineProps<{
  activeStep: string
  steps: CreatorStepMeta[]
  activeStepIndex: number
  activeStepMeta: CreatorStepMeta
  progressPercent: string
  canNavigate: (stepKey: CreatorStepKey) => boolean
  isCompleted: (stepKey: CreatorStepKey) => boolean
}>()

const emit = defineEmits<{
  navigate: [stepKey: CreatorStepKey]
}>()

const nextStep = computed(() => steps[activeStepIndex + 1] ?? null)
</script>

<template>
  <section class="creator-flow-card" aria-label="视频发布流程">
    <header class="creator-flow-head">
      <strong>视频发布流程</strong>
      <span>完成以下步骤，高效发布视频</span>
    </header>

    <div
      class="creator-mobile-progress"
      aria-label="当前创作进度"
      :style="{ '--creator-progress-percent': progressPercent }"
    >
      <div class="creator-mobile-progress-head">
        <span>第 {{ activeStepIndex + 1 }} / {{ steps.length }} 步</span>
        <strong>{{ activeStepMeta.label }}</strong>
      </div>
      <div class="creator-mobile-progress-track" aria-hidden="true">
        <span></span>
      </div>
      <p>{{ activeStepMeta.description }}</p>
      <button
        v-if="nextStep"
        type="button"
        class="creator-mobile-next-action"
        :disabled="!canNavigate(nextStep.key)"
        @click="emit('navigate', nextStep.key)"
      >
        <span>进入{{ nextStep.label }}</span>
        <ArrowRight :size="17" :stroke-width="1.9" aria-hidden="true" />
      </button>
    </div>

    <nav class="creator-tabs creator-tabs-vertical" aria-label="创作步骤">
      <button
        v-for="(step, index) in steps"
        :key="step.key"
        type="button"
        :disabled="!canNavigate(step.key)"
        :class="{ active: activeStep === step.key, completed: isCompleted(step.key) }"
        :aria-current="activeStep === step.key ? 'step' : undefined"
        @click="emit('navigate', step.key)"
      >
        <span class="creator-step-count">{{ index + 1 }}</span>
        <span class="creator-step-icon" aria-hidden="true">
          <component :is="stepIcons[step.key]" :size="17" :stroke-width="1.7" />
        </span>
        <span class="creator-step-text">
          <strong>{{ step.label }}</strong>
          <small>{{ step.description }}</small>
        </span>
      </button>
    </nav>
  </section>
</template>
