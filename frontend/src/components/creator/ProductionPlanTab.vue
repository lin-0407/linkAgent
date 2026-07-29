<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import ProductionBlueprintModal from './ProductionBlueprintModal.vue'
import ProductionPositioningForm from './ProductionPositioningForm.vue'
import { useCreatorWorkspaceShell } from '@/composables/creator/useCreatorWorkspaceContext'
import { useProductionPlan } from '@/composables/creator/useProductionPlan'
import type { CreateProductionPlanPayload } from '@/types/creatorProduction'

const emit = defineEmits<{
  readyChange: [ready: boolean]
  regenerated: []
}>()
const { selectedTaskId, currentDraftVideo } = useCreatorWorkspaceShell()
const production = useProductionPlan()
const selectedStepId = ref('')
const isBlueprintOpen = ref(false)
const isRegenerating = ref(false)
const selectedStep = computed(
  () =>
    production.workspace.value?.steps.find((step) => step.stepId === selectedStepId.value) ??
    production.workspace.value?.steps[0] ??
    null,
)
const plan = computed(() => production.workspace.value?.plan ?? null)
const showForm = computed(
  () =>
    isRegenerating.value ||
    !plan.value ||
    plan.value.status === 'FAILED' ||
    plan.value.status === 'STALE',
)

async function load() {
  if (!selectedTaskId.value) return
  isRegenerating.value = false
  isBlueprintOpen.value = false
  await production.load(selectedTaskId.value)
  selectedStepId.value = production.workspace.value?.steps[0]?.stepId ?? ''
  isBlueprintOpen.value = false
  emit('readyChange', production.workspace.value?.readyForMedia ?? false)
}

async function generate(payload: CreateProductionPlanPayload) {
  if (!selectedTaskId.value) return
  const wasRegenerating = isRegenerating.value
  if (await production.generate(selectedTaskId.value, payload)) {
    selectedStepId.value = production.workspace.value?.steps[0]?.stepId ?? ''
    isBlueprintOpen.value = true
    emit('readyChange', production.workspace.value?.readyForMedia ?? false)
    if (wasRegenerating) emit('regenerated')
  }
  // 重新定位失败时回到仍保留的旧蓝图，避免错误响应把既有工作区一起丢掉。
  if (wasRegenerating) isRegenerating.value = false
}

function restartGeneration() {
  if (
    currentDraftVideo.value &&
    !window.confirm('生成新蓝图后，当前成片、媒体处理和试映结果都会失效。确定继续吗？')
  ) {
    return
  }
  isRegenerating.value = Boolean(plan.value)
  isBlueprintOpen.value = false
}

async function updateStep(
  status: Parameters<typeof production.updateStep>[3],
  rowVersion: number,
  skipReason?: string,
) {
  if (!selectedTaskId.value || !plan.value || !selectedStep.value) return
  const updated = await production.updateStep(
    selectedTaskId.value,
    plan.value.planId,
    selectedStep.value.stepId,
    status,
    rowVersion,
    skipReason,
  )
  if (updated) emit('readyChange', production.workspace.value?.readyForMedia ?? false)
}

watch(selectedTaskId, () => {
  void load()
})
onMounted(() => {
  void load()
})
</script>

<template>
  <section class="production-plan-tab">
    <p v-if="production.errorMessage.value" class="production-error" role="alert">
      {{ production.errorMessage.value }}
    </p>
    <p v-if="production.isLoading.value" class="production-loading">正在读取制作蓝图...</p>
    <ProductionPositioningForm
      v-else-if="showForm"
      :key="selectedTaskId"
      :busy="production.isGenerating.value"
      @submit="generate"
    />
    <div v-else-if="plan" class="production-plan-entry">
      <button type="button" class="production-open-entry" @click="isBlueprintOpen = true">
        <span class="production-open-entry-copy">
          <span class="production-kicker">
            {{ plan.videoCategory === 'AI_GENERATED' ? 'AI 视频' : '项目演示' }} · V{{
              plan.planVersion
            }}
          </span>
          <strong>{{ plan.planTitle || '制作蓝图' }}</strong>
          <small>
            {{
              production.workspace.value?.readyForMedia
                ? '制作步骤已完成，可进入成片试映。'
                : '打开蓝图继续执行制作步骤。'
            }}
          </small>
        </span>
        <span class="production-open-entry-arrow" aria-hidden="true">
          <svg viewBox="0 0 24 24">
            <path d="M5 12h13M13 6l6 6-6 6" />
          </svg>
        </span>
      </button>
      <button
        type="button"
        class="production-regenerate"
        :disabled="production.isGenerating.value"
        @click="restartGeneration"
      >
        重新定位
      </button>
    </div>

    <ProductionBlueprintModal
      :open="isBlueprintOpen"
      :plan="plan"
      :steps="production.workspace.value?.steps ?? []"
      :selected-step="selectedStep"
      :selected-step-id="selectedStepId"
      :ready-for-media="production.workspace.value?.readyForMedia ?? false"
      :busy="production.isUpdatingStep.value"
      :error-message="production.errorMessage.value"
      @close="isBlueprintOpen = false"
      @restart="restartGeneration"
      @select="selectedStepId = $event"
      @update="updateStep"
    />
  </section>
</template>

<style scoped>
.production-plan-tab {
  display: grid;
  gap: 16px;
}

.production-error,
.production-loading {
  margin: 0;
  font-size: 13px;
}

.production-error {
  color: var(--danger);
}

.production-loading {
  color: var(--muted);
}

.production-plan-entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  border-top: 1px solid var(--border);
  border-bottom: 1px solid var(--border);
}

.production-open-entry {
  display: flex;
  min-width: 0;
  min-height: 104px;
  flex: 1;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 16px 0;
  color: var(--ink);
  text-align: left;
  background: transparent;
  border: 0;
  cursor: pointer;
}

.production-open-entry:hover .production-open-entry-copy strong {
  color: var(--accent-strong);
}

.production-open-entry:focus-visible,
.production-regenerate:focus-visible {
  outline: 3px solid var(--accent-ring);
  outline-offset: 3px;
}

.production-open-entry-copy {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.production-kicker {
  margin: 0;
  color: var(--accent-strong);
  font-size: 12px;
  font-weight: var(--fw-bold);
}

.production-open-entry-copy strong,
.production-open-entry-copy small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.production-open-entry-copy strong {
  font-size: 18px;
  transition: color 180ms ease;
}

.production-open-entry-copy small {
  color: var(--muted);
}

.production-open-entry-arrow {
  display: grid;
  width: 44px;
  height: 44px;
  flex: 0 0 auto;
  place-items: center;
  color: var(--accent-strong);
}

.production-open-entry-arrow svg {
  width: 20px;
  height: 20px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.8;
}

.production-regenerate {
  min-height: 44px;
  flex: 0 0 auto;
  padding: 0 12px;
  color: var(--muted);
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  cursor: pointer;
}

.production-regenerate:hover {
  color: var(--ink);
  background: var(--surface-sub);
}

.production-regenerate:disabled {
  opacity: 0.5;
  cursor: wait;
}

@media (max-width: 620px) {
  .production-plan-entry {
    align-items: flex-start;
  }

  .production-open-entry {
    min-height: 92px;
  }

  .production-open-entry-copy strong {
    max-width: 55vw;
  }

  .production-regenerate {
    margin-top: 16px;
    padding-inline: 8px;
  }
}
</style>
