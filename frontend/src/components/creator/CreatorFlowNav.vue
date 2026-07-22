<script setup lang="ts">
type CreatorWorkStep = 'prePublish' | 'production' | 'preflight' | 'feedback' | 'report'
type CreatorStepKey = 'task' | CreatorWorkStep

type CreatorStepMeta = {
  key: CreatorStepKey
  label: string
  shortLabel: string
  description: string
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
          <svg v-if="step.key === 'task'" viewBox="0 0 24 24">
            <path d="M7 3.5h7l3.5 3.5v13.5h-11v-17z" />
            <path d="M14 3.5v4h3.5" />
            <path d="M9 11h6M9 15h5" />
          </svg>
          <svg v-else-if="step.key === 'prePublish'" viewBox="0 0 24 24">
            <path d="M6 5.5h12v13h-12z" />
            <path d="M9 9h6M9 12.5h5M9 16h3.5" />
            <path d="M16.5 4v3M18 5.5h-3" />
          </svg>
          <svg v-else-if="step.key === 'preflight'" viewBox="0 0 24 24">
            <path d="M4.5 6h15v11.5h-15z" />
            <path d="M9.5 9l5 2.75-5 2.75z" />
            <path d="M7 20h10" />
          </svg>
          <svg v-else-if="step.key === 'production'" viewBox="0 0 24 24">
            <path d="M5 7.5h14v11H5z" />
            <path d="M8 7.5V5h8v2.5M8 11h8M8 14h5" />
          </svg>
          <svg v-else-if="step.key === 'feedback'" viewBox="0 0 24 24">
            <path d="M5 6.5h14v8.5h-8l-4 3v-3h-2z" />
            <path d="M8.5 10.5h7M8.5 13h4.5" />
          </svg>
          <svg v-else viewBox="0 0 24 24">
            <path d="M6 4.5h12v15h-12z" />
            <path d="M9 16v-3M12 16v-6M15 16v-4" />
            <path d="M8.5 8h7" />
          </svg>
        </span>
        <span class="creator-step-text">
          <strong>{{ step.label }}</strong>
          <small>{{ step.description }}</small>
        </span>
      </button>
    </nav>
  </section>
</template>
