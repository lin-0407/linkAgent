<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import ProductionPositioningForm from './ProductionPositioningForm.vue'
import ProductionStepDetail from './ProductionStepDetail.vue'
import ProductionStepList from './ProductionStepList.vue'
import { useCreatorWorkspaceShell } from '@/composables/creator/useCreatorWorkspaceContext'
import { useProductionPlan } from '@/composables/creator/useProductionPlan'
import type { CreateProductionPlanPayload } from '@/types/creatorProduction'

const emit = defineEmits<{ readyChange: [ready: boolean] }>()
const { selectedTaskId } = useCreatorWorkspaceShell()
const production = useProductionPlan()
const selectedStepId = ref('')
const selectedStep = computed(() => production.workspace.value?.steps.find((step) => step.stepId === selectedStepId.value) ?? production.workspace.value?.steps[0] ?? null)
const plan = computed(() => production.workspace.value?.plan ?? null)
const showForm = computed(() => !plan.value || plan.value.status === 'FAILED' || plan.value.status === 'STALE')

async function load() {
  if (!selectedTaskId.value) return
  await production.load(selectedTaskId.value)
  selectedStepId.value = production.workspace.value?.steps[0]?.stepId ?? ''
  emit('readyChange', production.workspace.value?.readyForMedia ?? false)
}
async function generate(payload: CreateProductionPlanPayload) {
  if (!selectedTaskId.value) return
  if (await production.generate(selectedTaskId.value, payload)) {
    selectedStepId.value = production.workspace.value?.steps[0]?.stepId ?? ''
    emit('readyChange', production.workspace.value?.readyForMedia ?? false)
  }
}
function restartGeneration() {
  production.workspace.value = null
  emit('readyChange', false)
}
async function updateStep(status: Parameters<typeof production.updateStep>[3], rowVersion: number, skipReason?: string) {
  if (!selectedTaskId.value || !plan.value || !selectedStep.value) return
  const updated = await production.updateStep(selectedTaskId.value, plan.value.planId, selectedStep.value.stepId, status, rowVersion, skipReason)
  if (updated) emit('readyChange', production.workspace.value?.readyForMedia ?? false)
}

watch(selectedTaskId, () => { void load() })
onMounted(() => { void load() })
</script>

<template>
  <section class="production-plan-tab">
    <div v-if="production.errorMessage.value" class="production-error" role="alert">{{ production.errorMessage.value }}</div>
    <div v-if="production.isLoading.value" class="production-loading">正在读取制作蓝图…</div>
    <ProductionPositioningForm v-else-if="showForm" :busy="production.isGenerating.value" @submit="generate" />
    <template v-else-if="plan">
      <header class="production-plan-header"><div><p class="production-kicker">{{ plan.videoCategory === 'AI_GENERATED' ? 'AI 视频' : '项目演示' }} · V{{ plan.planVersion }}</p><h2>{{ plan.planTitle || '制作蓝图' }}</h2><p>{{ plan.positioningSummary }}</p></div><button type="button" class="production-regenerate" :disabled="production.isGenerating.value" @click="restartGeneration">重新定位</button></header>
      <div class="production-workspace"><ProductionStepList :steps="production.workspace.value?.steps ?? []" :selected-step-id="selectedStepId" @select="selectedStepId = $event" /><ProductionStepDetail :step="selectedStep" :busy="production.isUpdatingStep.value" @update="updateStep" /></div>
      <div class="production-ready-note" :class="{ ready: production.workspace.value?.readyForMedia }">{{ production.workspace.value?.readyForMedia ? '制作步骤已完成，可以进入成片试映。' : '完成或跳过全部步骤后，才能进入成片试映。' }}</div>
    </template>
  </section>
</template>

<style scoped>
.production-plan-tab { display: grid; gap: 16px; }
.production-error { padding: 11px 14px; color: #9f1239; background: #fff1f2; border: 1px solid #fecdd3; border-radius: 6px; }
.production-loading { padding: 32px; color: #64748b; background: #fff; border: 1px solid #e2e8f0; border-radius: 8px; }
.production-plan-header { display: flex; justify-content: space-between; gap: 18px; align-items: flex-start; padding: 22px 24px; background: #fff; border: 1px solid #e2e8f0; border-radius: 8px; }
.production-kicker { margin: 0 0 5px; color: #0f766e; font-size: 12px; font-weight: 700; } h2 { margin: 0; color: #17212b; font-size: 23px; } .production-plan-header p:last-child { margin: 7px 0 0; color: #64748b; }
.production-regenerate { padding: 9px 12px; color: #475569; background: #f8fafc; border: 1px solid #cbd5e1; border-radius: 5px; cursor: pointer; }
.production-workspace { display: grid; grid-template-columns: minmax(210px, .32fr) minmax(0, 1fr); gap: 16px; }
.production-ready-note { padding: 12px 14px; color: #9a3412; background: #fff7ed; border: 1px solid #fed7aa; border-radius: 6px; font-size: 13px; } .production-ready-note.ready { color: #166534; background: #f0fdf4; border-color: #bbf7d0; }
@media (max-width: 800px) { .production-workspace { grid-template-columns: 1fr; } .production-plan-header { display: grid; } }
</style>
