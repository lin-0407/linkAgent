<script setup lang="ts">
import type { ProductionStep, ProductionStepStatus } from '@/types/creatorProduction'

defineProps<{ step: ProductionStep | null; busy: boolean }>()
const emit = defineEmits<{ update: [status: ProductionStepStatus, rowVersion: number, skipReason?: string] }>()
const labels = { PENDING: '待开始', IN_PROGRESS: '进行中', COMPLETED: '已完成', SKIPPED: '已跳过' }

function update(step: ProductionStep, status: ProductionStepStatus) {
  const reason = status === 'SKIPPED' ? window.prompt('请填写跳过原因')?.trim() : undefined
  if (status === 'SKIPPED' && !reason) return
  emit('update', status, step.rowVersion, reason)
}
</script>

<template>
  <article v-if="step" class="production-step-detail">
    <header><div><p class="production-detail-kicker">{{ step.phase }} · 第 {{ step.sequenceNo }} 步</p><h3>{{ step.stepName }}</h3></div><span class="production-status">{{ labels[step.status] }}</span></header>
    <p class="production-objective">{{ step.objective }}</p>
    <div class="production-detail-grid">
      <section><h4>前置条件</h4><ul><li v-for="item in step.prerequisites" :key="item">{{ item }}</li><li v-if="!step.prerequisites.length">无额外前置条件</li></ul></section>
      <section><h4>操作清单</h4><ol><li v-for="item in step.operations" :key="item">{{ item }}</li><li v-if="!step.operations.length">等待蓝图补充操作</li></ol></section>
      <section><h4>预期产物</h4><ul><li v-for="item in step.expectedOutputs" :key="item">{{ item }}</li></ul></section>
      <section><h4>验收标准</h4><ul><li v-for="item in step.acceptanceCriteria" :key="item">{{ item }}</li></ul></section>
    </div>
    <section v-if="step.toolRefs.length" class="production-tools"><h4>工具来源状态</h4><div v-for="tool in step.toolRefs" :key="`${tool.toolId}-${tool.toolName}`" class="production-tool-row"><strong>{{ tool.toolName }}</strong><span :class="`tool-${tool.verificationStatus.toLowerCase()}`">{{ tool.verificationStatus === 'VERIFIED' ? '已核验' : '需要官方资料' }}</span><small>{{ tool.reason || tool.officialUrl }}</small></div></section>
    <footer><button type="button" :disabled="busy || step.status === 'IN_PROGRESS'" @click="update(step, 'IN_PROGRESS')">开始执行</button><button type="button" :disabled="busy || step.status === 'COMPLETED'" @click="update(step, 'COMPLETED')">标记完成</button><button type="button" class="muted-action" :disabled="busy || step.status === 'SKIPPED'" @click="update(step, 'SKIPPED')">跳过步骤</button></footer>
  </article>
  <article v-else class="production-empty-detail"><strong>选择一个制作步骤</strong><span>左侧列表会保留当前蓝图的执行顺序。</span></article>
</template>

<style scoped>
.production-step-detail, .production-empty-detail { min-width: 0; padding: 24px; background: #fff; border: 1px solid #e2e8f0; border-radius: 8px; }
header { display: flex; justify-content: space-between; gap: 18px; align-items: flex-start; }
.production-detail-kicker { margin: 0 0 6px; color: #0f766e; font-size: 12px; font-weight: 700; }
h3 { margin: 0; color: #17212b; font-size: 22px; }
.production-status { padding: 6px 9px; color: #0f766e; background: #f0fdfa; border-radius: 5px; font-size: 12px; white-space: nowrap; }
.production-objective { margin: 18px 0; color: #475569; line-height: 1.65; }
.production-detail-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
section { padding: 14px; background: #f8fafc; border-radius: 6px; } h4 { margin: 0 0 8px; color: #334155; font-size: 13px; } ul, ol { margin: 0; padding-left: 19px; color: #64748b; font-size: 13px; line-height: 1.65; }
.production-tools { margin-top: 16px; } .production-tool-row { display: grid; grid-template-columns: auto auto 1fr; gap: 10px; align-items: center; padding: 8px 0; border-top: 1px solid #e2e8f0; } .production-tool-row small { overflow: hidden; color: #64748b; text-overflow: ellipsis; white-space: nowrap; } .tool-verified { color: #15803d; font-size: 12px; } .tool-source_required { color: #b45309; font-size: 12px; }
footer { display: flex; flex-wrap: wrap; gap: 9px; margin-top: 18px; } footer button { padding: 9px 12px; color: #0f766e; background: #f0fdfa; border: 1px solid #99f6e4; border-radius: 5px; cursor: pointer; } footer button:disabled { opacity: .5; cursor: wait; } footer .muted-action { color: #64748b; background: #f8fafc; border-color: #cbd5e1; }
.production-empty-detail { display: grid; place-content: center; min-height: 260px; color: #475569; text-align: center; gap: 8px; } .production-empty-detail span { color: #94a3b8; font-size: 13px; }
@media (max-width: 680px) { .production-detail-grid { grid-template-columns: 1fr; } .production-tool-row { grid-template-columns: 1fr; gap: 3px; } }
</style>
