<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type Component } from 'vue'
import {
  ArrowRight,
  BarChart3,
  Check,
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
const navigation = ref<HTMLElement | null>(null)
let navigationResizeObserver: ResizeObserver | null = null
async function revealCurrentStep() {
  await nextTick()
  const nav = navigation.value
  const current = nav?.querySelector<HTMLElement>('[aria-current="step"]')
  if (nav && current && nav.scrollWidth > nav.clientWidth) {
    const currentBounds = current.getBoundingClientRect()
    const navigationBounds = nav.getBoundingClientRect()
    nav.scrollLeft =
      nav.scrollLeft +
      currentBounds.left -
      navigationBounds.left -
      (nav.clientWidth - currentBounds.width) / 2
  }
}
onMounted(() => {
  void revealCurrentStep()
  // 屏幕缩窄或助手展开时，仍让当前阶段保留在可见区域。
  navigationResizeObserver = new ResizeObserver(() => void revealCurrentStep())
  if (navigation.value) navigationResizeObserver.observe(navigation.value)
})
onBeforeUnmount(() => navigationResizeObserver?.disconnect())
watch(() => activeStep, revealCurrentStep)
</script>

<template>
  <section class="creator-flow-card" aria-label="视频发布流程">
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

    <nav
      ref="navigation"
      class="creator-tabs creator-tabs-vertical"
      aria-label="创作步骤"
      :style="{ '--creator-step-total': steps.length }"
    >
      <button
        v-for="(step, index) in steps"
        :key="step.key"
        type="button"
        :disabled="!canNavigate(step.key)"
        :class="{ active: activeStep === step.key, completed: isCompleted(step.key) }"
        :aria-current="activeStep === step.key ? 'step' : undefined"
        :title="step.description"
        @click="emit('navigate', step.key)"
      >
        <span class="creator-step-count" aria-hidden="true">
          <Check
            v-if="isCompleted(step.key) && activeStep !== step.key"
            :size="15"
            :stroke-width="2"
          />
          <component
            v-else-if="activeStep === step.key"
            :is="stepIcons[step.key]"
            :size="17"
            :stroke-width="1.8"
          />
          <span v-else>{{ index + 1 }}</span>
        </span>
        <span class="creator-step-text">
          <strong>{{ step.label }}</strong>
          <small>{{ step.description }}</small>
        </span>
      </button>
    </nav>
  </section>
</template>
