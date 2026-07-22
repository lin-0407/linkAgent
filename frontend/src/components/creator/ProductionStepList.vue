<script setup lang="ts">
import type { ProductionStep } from '@/types/creatorProduction'

defineProps<{ steps: ProductionStep[]; selectedStepId: string }>()
const emit = defineEmits<{ select: [stepId: string] }>()

const labels = { PENDING: '待开始', IN_PROGRESS: '进行中', COMPLETED: '已完成', SKIPPED: '已跳过' }
</script>

<template>
  <aside class="production-step-list" aria-label="制作步骤列表">
    <div class="production-list-heading"><strong>制作步骤</strong><span>{{ steps.length }} 步</span></div>
    <button v-for="step in steps" :key="step.stepId" type="button" :class="['production-step-item', { selected: step.stepId === selectedStepId }]" @click="emit('select', step.stepId)">
      <span class="production-step-number">{{ step.sequenceNo }}</span>
      <span class="production-step-copy"><strong>{{ step.stepName }}</strong><small>{{ step.phase }} · {{ labels[step.status] }}</small></span>
      <span v-if="step.required" class="production-required">必需</span>
    </button>
  </aside>
</template>

<style scoped>
.production-step-list { display: grid; align-content: start; gap: 8px; padding: 16px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; }
.production-list-heading { display: flex; justify-content: space-between; padding: 4px 6px 10px; color: #334155; border-bottom: 1px solid #e2e8f0; }
.production-list-heading span { color: #64748b; font-size: 12px; }
.production-step-item { display: grid; grid-template-columns: 28px minmax(0, 1fr) auto; align-items: center; gap: 10px; padding: 11px 8px; text-align: left; background: transparent; border: 1px solid transparent; border-radius: 6px; cursor: pointer; }
.production-step-item:hover, .production-step-item.selected { background: #fff; border-color: #99f6e4; }
.production-step-number { display: grid; place-items: center; width: 26px; height: 26px; color: #0f766e; background: #ccfbf1; border-radius: 50%; font-size: 12px; font-weight: 700; }
.production-step-copy { display: grid; gap: 3px; min-width: 0; }
.production-step-copy strong { overflow: hidden; color: #1e293b; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.production-step-copy small { color: #64748b; font-size: 11px; }
.production-required { color: #b45309; font-size: 10px; }
</style>
