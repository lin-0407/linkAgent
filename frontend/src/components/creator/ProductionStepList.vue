<script setup lang="ts">
import type { ProductionStep } from '@/types/creatorProduction'

defineProps<{ steps: ProductionStep[]; selectedStepId: string }>()
const emit = defineEmits<{ select: [stepId: string] }>()

const labels = {
  PENDING: '待开始',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  SKIPPED: '已跳过',
}
</script>

<template>
  <aside class="production-step-list" aria-label="制作步骤列表">
    <div class="production-list-heading">
      <strong>制作步骤</strong>
      <span>{{ steps.length }} 步</span>
    </div>
    <div class="production-list-items">
      <button
        v-for="step in steps"
        :key="step.stepId"
        type="button"
        :class="['production-step-item', { selected: step.stepId === selectedStepId }]"
        :aria-current="step.stepId === selectedStepId ? 'step' : undefined"
        @click="emit('select', step.stepId)"
      >
        <span class="production-step-number">{{ step.sequenceNo }}</span>
        <span class="production-step-copy">
          <strong>{{ step.stepName }}</strong>
          <small>{{ step.phase }} · {{ labels[step.status] }}</small>
        </span>
        <span v-if="step.required" class="production-required">必需</span>
      </button>
    </div>
  </aside>
</template>

<style scoped>
.production-step-list {
  display: grid;
  min-width: 0;
  min-height: 0;
  grid-template-rows: auto minmax(0, 1fr);
  padding: 18px 14px;
  background: var(--surface-sub);
  border-right: 1px solid var(--border);
}

.production-list-heading {
  display: flex;
  justify-content: space-between;
  padding: 4px 8px 14px;
  color: var(--ink);
}

.production-list-heading span {
  color: var(--muted);
  font-size: 12px;
}

.production-list-items {
  min-height: 0;
  overflow-y: auto;
}

.production-step-item {
  display: grid;
  width: 100%;
  min-height: 62px;
  grid-template-columns: 30px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 10px 8px;
  color: var(--text);
  text-align: left;
  background: transparent;
  border: 0;
  border-bottom: 1px solid var(--border);
  cursor: pointer;
  transition:
    color 180ms ease,
    background 180ms ease,
    box-shadow 180ms ease;
}

.production-step-item:hover,
.production-step-item.selected {
  color: var(--ink);
  background: var(--surface);
  box-shadow: inset 3px 0 0 var(--accent);
}

.production-step-item:focus-visible {
  position: relative;
  outline: 3px solid var(--accent-ring);
  outline-offset: -3px;
}

.production-step-number {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  color: var(--accent-strong);
  background: var(--accent-tint);
  border-radius: 50%;
  font-size: 12px;
  font-weight: var(--fw-bold);
}

.production-step-copy {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.production-step-copy strong,
.production-step-copy small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.production-step-copy strong {
  color: currentColor;
  font-size: 13px;
}

.production-step-copy small {
  color: var(--muted);
  font-size: 11px;
}

.production-required {
  color: var(--warn);
  font-size: 10px;
}

@media (max-width: 800px) {
  .production-step-list {
    display: block;
    padding: 10px 12px;
    border-right: 0;
    border-bottom: 1px solid var(--border);
  }

  .production-list-heading {
    display: none;
  }

  .production-list-items {
    display: flex;
    gap: 8px;
    overflow-x: auto;
  }

  .production-step-item {
    width: min(220px, 72vw);
    min-width: min(220px, 72vw);
    border: 1px solid var(--border);
    border-radius: var(--r-sm);
  }

  .production-step-item:hover,
  .production-step-item.selected {
    box-shadow: inset 0 3px 0 var(--accent);
  }
}
</style>
