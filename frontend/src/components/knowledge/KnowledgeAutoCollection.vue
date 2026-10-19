<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Download, LoaderCircle, Square } from '@lucide/vue'
import { getReferenceAutoCollection, startReferenceAutoCollection, stopReferenceAutoCollection } from '@/api/knowledge'
import type { ReferenceAutoCollectionState } from '@/types/knowledge'
import { ApiError } from '@/api/http'

const emit = defineEmits<{ finished: [] }>()
const state = ref<ReferenceAutoCollectionState | null>(null)
const pending = ref(false)
const error = ref('')
const initialized = ref(false)
const minIndex = ref(60)
const maxImports = ref(10)
const maxChecks = ref(100)
const selectedCategories = ref<number[]>([])
const initialCategories = ref<number[]>([])
// 沿用采集脚本的分类映射，页面展示名称，不让用户记接口编号。
const categoryOptions = [
  { id: 1, name: '动画' }, { id: 3, name: '音乐' }, { id: 4, name: '游戏' },
  { id: 36, name: '知识' }, { id: 188, name: '科技' }, { id: 160, name: '生活' },
  { id: 211, name: '美食' }, { id: 181, name: '影视' }, { id: 5, name: '娱乐' },
  { id: 234, name: '运动' }, { id: 119, name: '鬼畜' }, { id: 155, name: '时尚' },
]
const now = ref(Date.now())
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false

const running = computed(() => state.value?.status === 'RUNNING' || state.value?.status === 'STOPPING')
const elapsed = computed(() => {
  if (!state.value?.startedAt) return ''
  const seconds = Math.max(0, Math.floor(((state.value.finishedAt ? Date.parse(state.value.finishedAt) : now.value) - Date.parse(state.value.startedAt)) / 1000))
  return `${Math.floor(seconds / 60)}分${seconds % 60}秒`
})
const statusLabel = computed(() => ({ IDLE: '尚未开始', RUNNING: '采集中', STOPPING: '正在停止', SUCCEEDED: '采集完成', WARNING: '已结束，有提示', FAILED: '采集失败', CANCELLED: '已停止' })[state.value?.status ?? 'IDLE'])
const warnings = computed(() => state.value?.report?.warnings ?? [])

function schedulePoll() {
  clearTimeout(timer)
  if (!disposed && running.value) timer = setTimeout(() => void refreshStatus(), 2000)
}

async function refreshStatus() {
  const wasRunning = running.value
  try {
    const result = await getReferenceAutoCollection()
    if (disposed) return
    state.value = result
    now.value = Date.now()
    error.value = ''
    if (!initialized.value) {
      minIndex.value = result.settings.minIndex
      maxImports.value = result.settings.maxImports
      maxChecks.value = result.settings.maxChecks
      selectedCategories.value = [...result.settings.rids]
      initialCategories.value = [...result.settings.rids]
      initialized.value = true
    }
    if (wasRunning && !running.value) emit('finished')
  } catch (cause) {
    if (!disposed) error.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    schedulePoll()
  }
}

async function start() {
  if (pending.value || running.value) return
  pending.value = true
  error.value = ''
  try {
    state.value = await startReferenceAutoCollection({ minIndex: minIndex.value, maxImports: maxImports.value, maxChecks: maxChecks.value, rids: [...selectedCategories.value] })
    now.value = Date.now()
    schedulePoll()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
    // 重复启动被拒绝时重新读取后台任务，刷新页面后仍能接着查看。
    if (cause instanceof ApiError && cause.status === 409) void refreshStatus()
  } finally {
    pending.value = false
  }
}

async function stop() {
  pending.value = true
  try {
    state.value = await stopReferenceAutoCollection()
    if (!running.value) emit('finished')
    schedulePoll()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    pending.value = false
  }
}

onMounted(() => void refreshStatus())
onUnmounted(() => {
  disposed = true
  clearTimeout(timer)
})
</script>

<template>
  <section class="auto-collection" aria-label="自动采集参考视频">
    <div class="collection-heading">
      <div>
        <h3>自动采集参考视频</h3>
        <p>自动发现热门及常用分类中近半年的视频，按视频指数筛选后入库。直接开始即可。</p>
      </div>
      <button v-if="!running" class="creator-primary-button" type="button" :disabled="pending || !initialized" @click="start">
        <Download :size="16" aria-hidden="true" />{{ pending ? '正在启动…' : '开始自动采集' }}
      </button>
      <button v-else class="creator-secondary-action" type="button" :disabled="pending || state?.status === 'STOPPING'" @click="stop">
        <Square :size="14" aria-hidden="true" />{{ state?.status === 'STOPPING' ? '正在停止…' : '停止采集' }}
      </button>
    </div>

    <details class="collection-settings">
      <summary>采集设置 · 指数 &gt; {{ minIndex }} · 单轮最多 {{ maxImports }} 条</summary>
      <form class="collection-fields" @submit.prevent="start">
        <label>最低视频指数<input v-model.number="minIndex" type="number" min="0" max="100" step="0.1" required :disabled="running" /></label>
        <label>单轮入库上限<input v-model.number="maxImports" type="number" min="1" required :disabled="running" /></label>
        <label>单轮检查上限<input v-model.number="maxChecks" type="number" min="1" max="10000" required :disabled="running" /></label>
        <fieldset class="category-field" :disabled="running">
          <legend>视频分类（可选）</legend>
          <p>范围已经预选好，无需设置。想限定视频领域时再调整；热门视频会同时采集。</p>
          <div class="category-options">
            <label v-for="category in categoryOptions" :key="category.id" class="category-option" :class="{ 'is-selected': selectedCategories.includes(category.id) }">
              <input v-model="selectedCategories" type="checkbox" :value="category.id" />{{ category.name }}
            </label>
          </div>
          <div class="category-actions">
            <button type="button" @click="selectedCategories = [...initialCategories]">恢复预选范围</button>
            <button type="button" @click="selectedCategories = []">仅采集热门</button>
            <span v-if="!selectedCategories.length">当前仅采集热门视频</span>
          </div>
        </fieldset>
      </form>
    </details>

    <div v-if="state?.runId" class="collection-status" aria-live="polite">
      <span class="status-badge" :data-status="state.status"><LoaderCircle v-if="running" class="spin" :size="14" aria-hidden="true" />{{ statusLabel }}</span>
      <span>{{ elapsed }}</span>
      <p>{{ state.message }}</p>
      <p v-if="running" class="collection-hint">可以离开页面，返回后继续查看；完成后会自动刷新案例列表。</p>
    </div>
    <div v-if="state?.report" class="collection-results">
      <span>发现 <strong>{{ state.report.discovered ?? 0 }}</strong></span>
      <span>检查 <strong>{{ state.report.checked ?? 0 }}</strong></span>
      <span>合格 <strong>{{ state.report.eligible ?? 0 }}</strong></span>
      <span>新增 <strong>{{ state.report.imported ?? 0 }}</strong></span>
      <span>更新 <strong>{{ state.report.updated ?? 0 }}</strong></span>
      <span>跳过 <strong>{{ state.report.skipped ?? 0 }}</strong></span>
      <span>失败 <strong>{{ state.report.failed ?? 0 }}</strong></span>
    </div>
    <details v-if="warnings.length" class="collection-warnings"><summary>查看 {{ warnings.length }} 条采集提示</summary><ul><li v-for="(warning, index) in warnings" :key="index">{{ warning }}</li></ul></details>
    <p v-if="error" class="collection-error" role="alert">{{ error }} <button type="button" @click="refreshStatus">重试连接</button></p>
  </section>
</template>

<style scoped>
.auto-collection { padding: var(--s4); border: 1px solid var(--border); border-radius: 12px; background: var(--surface); display: grid; gap: var(--s3); }
.collection-heading { display: flex; align-items: center; justify-content: space-between; gap: var(--s4); }
h3 { margin: 0 0 6px; font-size: 16px; }
p { margin: 0; font-size: 13px; color: var(--muted); line-height: 1.6; }
button { flex-shrink: 0; }
summary { cursor: pointer; font-size: 13px; color: var(--muted); }
.collection-fields { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--s3); padding-top: var(--s3); }
label { display: grid; gap: 6px; font-size: 12px; color: var(--muted); }
input { width: 100%; box-sizing: border-box; padding: 9px 10px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface-sub); color: var(--ink); font: inherit; }
.category-field { grid-column: 1 / -1; min-width: 0; margin: 0; padding: 0; border: 0; display: grid; gap: 10px; }
.category-field legend { padding: 0 0 6px; font-size: 13px; font-weight: 600; color: var(--ink); }
.category-options { display: flex; flex-wrap: wrap; gap: 8px; }
.category-option { display: inline-flex; align-items: center; gap: 7px; padding: 8px 12px; border: 1px solid var(--border); border-radius: 8px; cursor: pointer; }
.category-option.is-selected { color: var(--accent-strong); border-color: var(--accent); background: var(--accent-tint); }
.category-option input { width: 15px; height: 15px; margin: 0; padding: 0; accent-color: var(--accent); }
.category-field:disabled .category-option { cursor: default; opacity: .7; }
.category-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; color: var(--muted); font-size: 12px; }
.category-actions button { border: 0; padding: 0; background: none; color: var(--accent); font: inherit; cursor: pointer; }
.category-field:disabled .category-actions button { cursor: default; opacity: .7; }
.collection-status { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; font-size: 12px; }
.collection-status p { width: 100%; }
.status-badge { display: inline-flex; align-items: center; gap: 6px; border-radius: 6px; padding: 4px 8px; background: var(--surface-sub); }
.status-badge[data-status='SUCCEEDED'] { color: #258a57; }
.status-badge[data-status='FAILED'], .collection-error { color: #c64242; }
.status-badge[data-status='WARNING'] { color: #a56d15; }
.collection-hint { opacity: .75; }
.collection-results { display: flex; flex-wrap: wrap; gap: 10px 20px; font-size: 13px; }
.collection-results strong { margin-left: 4px; }
.collection-warnings ul { max-height: 180px; overflow: auto; padding-left: 20px; font-size: 12px; line-height: 1.7; }
.collection-error button { cursor: pointer; color: inherit; background: none; border: 0; text-decoration: underline; }
.spin { animation: collection-spin 1.2s linear infinite; }
@keyframes collection-spin { to { transform: rotate(360deg); } }
@media (max-width: 640px) { .collection-heading { align-items: stretch; flex-direction: column; } .collection-fields { grid-template-columns: 1fr; } }
@media (prefers-reduced-motion: reduce) { .spin { animation: none; } }
</style>
