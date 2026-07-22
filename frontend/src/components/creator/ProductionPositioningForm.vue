<script setup lang="ts">
import { reactive } from 'vue'
import type {
  CreateProductionPlanPayload,
  PreferredTool,
  ProductionMethod,
  ProductionVideoCategory,
} from '@/types/creatorProduction'

defineProps<{ busy: boolean }>()
const emit = defineEmits<{ submit: [payload: CreateProductionPlanPayload] }>()

const form = reactive({
  videoCategory: 'AI_GENERATED' as ProductionVideoCategory,
  productionMethod: 'AI_GENERATION' as ProductionMethod,
  targetAudience: '',
  corePromise: '',
  targetDurationSeconds: 90,
  availableAssetsText: '',
  constraints: '',
  preferredToolsText: '',
})

function submit() {
  const preferredTools: PreferredTool[] = form.preferredToolsText
    .split('\n')
    .map((name) => name.trim())
    .filter(Boolean)
    .slice(0, 10)
    .map((name) => ({ name }))
  emit('submit', {
    videoCategory: form.videoCategory,
    productionMethod: form.productionMethod,
    targetAudience: form.targetAudience.trim(),
    corePromise: form.corePromise.trim(),
    targetDurationSeconds: Number(form.targetDurationSeconds),
    availableAssets: form.availableAssetsText.split('\n').map((item) => item.trim()).filter(Boolean).slice(0, 20),
    constraints: form.constraints.trim() || undefined,
    preferredTools,
  })
}
</script>

<template>
  <form class="production-positioning" @submit.prevent="submit">
    <div class="production-form-heading">
      <div>
        <p class="production-kicker">P0-1 · 制作蓝图</p>
        <h2>先把成片路径定下来</h2>
        <p>蓝图会把定位、素材、工具和验收标准拆成可勾选的步骤。</p>
      </div>
      <span class="production-form-badge">需要发布方案已确认</span>
    </div>
    <div class="production-form-grid">
      <label>
        <span>视频制作类型</span>
        <select v-model="form.videoCategory">
          <option value="AI_GENERATED">AI 生成视频</option>
          <option value="PROJECT_DEMO">项目演示视频</option>
        </select>
      </label>
      <label>
        <span>主要制作方式</span>
        <select v-model="form.productionMethod">
          <option value="AI_GENERATION">AI 生成</option>
          <option value="SCREEN_RECORDING">屏幕录制</option>
          <option value="EXISTING_ASSET_EDITING">已有素材剪辑</option>
          <option value="HUMAN_SHOOTING">真人拍摄</option>
          <option value="MIXED">混合制作</option>
        </select>
      </label>
      <label>
        <span>目标时长（秒）</span>
        <input v-model.number="form.targetDurationSeconds" type="number" min="60" max="1800" />
      </label>
      <label class="production-field-wide">
        <span>目标观众</span>
        <textarea v-model="form.targetAudience" required rows="2" placeholder="例如：希望快速看懂项目价值的开发者"></textarea>
      </label>
      <label class="production-field-wide">
        <span>核心承诺</span>
        <textarea v-model="form.corePromise" required rows="2" placeholder="观众看完后能得到什么明确结果？"></textarea>
      </label>
      <label>
        <span>可用素材（每行一项）</span>
        <textarea v-model="form.availableAssetsText" rows="4" placeholder="项目截图&#10;录屏文件&#10;旁白草稿"></textarea>
      </label>
      <label>
        <span>指定工具（每行一个，可选）</span>
        <textarea v-model="form.preferredToolsText" rows="4" placeholder="OBS Studio&#10;DaVinci Resolve"></textarea>
      </label>
      <label class="production-field-wide">
        <span>制作约束</span>
        <textarea v-model="form.constraints" rows="2" placeholder="例如：不露脸、只使用现有素材、需要保留可复现证据"></textarea>
      </label>
    </div>
    <button class="production-primary-button" type="submit" :disabled="busy">
      {{ busy ? '正在生成蓝图…' : '生成制作蓝图' }}
    </button>
  </form>
</template>

<style scoped>
.production-positioning { display: grid; gap: 24px; padding: 28px; background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; }
.production-form-heading { display: flex; justify-content: space-between; gap: 20px; align-items: flex-start; }
.production-kicker { margin: 0 0 6px; color: #0f766e; font-size: 12px; font-weight: 700; letter-spacing: .08em; }
h2 { margin: 0; color: #17212b; font-size: 24px; }
.production-form-heading p:not(.production-kicker) { margin: 8px 0 0; color: #64748b; }
.production-form-badge { padding: 7px 10px; color: #9a3412; background: #fff7ed; border: 1px solid #fed7aa; border-radius: 6px; font-size: 12px; white-space: nowrap; }
.production-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
label { display: grid; gap: 7px; color: #334155; font-size: 13px; font-weight: 650; }
.production-field-wide { grid-column: 1 / -1; }
input, select, textarea { width: 100%; box-sizing: border-box; padding: 10px 12px; color: #17212b; background: #f8fafc; border: 1px solid #cbd5e1; border-radius: 6px; font: inherit; resize: vertical; }
input:focus, select:focus, textarea:focus { outline: 2px solid #99f6e4; border-color: #0f766e; }
.production-primary-button { justify-self: start; padding: 11px 18px; color: #fff; background: #0f766e; border: 0; border-radius: 6px; font-weight: 700; cursor: pointer; }
.production-primary-button:disabled { opacity: .55; cursor: wait; }
@media (max-width: 680px) { .production-form-heading, .production-form-grid { display: grid; grid-template-columns: 1fr; } .production-field-wide { grid-column: auto; } .production-positioning { padding: 20px; } }
</style>
