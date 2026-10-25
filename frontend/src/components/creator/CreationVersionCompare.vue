<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { executionDownload } from '@/api/creationProduction'
import { errorText } from '@/utils/error'
import type { Execution, SignedRead } from '@/types/creationProduction'
const props = defineProps<{ taskId: string; executions: Execution[] }>()
const versions = computed(() => props.executions.filter(e => e.kind !== 'SAMPLE' && e.previewAvailable))
const left = ref(''), right = ref(''), urls = ref<(SignedRead | null)[]>([null, null]), error = ref('')
let generation = 0
async function load() {
  const token = ++generation, task = props.taskId
  urls.value = [null,null];error.value = ''
  try {
    const result = await Promise.all([left.value,right.value].map(id => id ? executionDownload(task,id,'video.mp4') : Promise.resolve(null)))
    if (token === generation && task === props.taskId) urls.value = result
  } catch(reason) { if (token === generation) error.value = errorText(reason, '版本对比读取失败') }
}
function playbackError(index: number) {
  if (urls.value[index] && Date.parse(urls.value[index]!.expiresAt) < Date.now() + 5000) void load()
  else error.value = '该版本暂时无法播放，请核对媒体状态或点击刷新播放地址'
}
watch(() => [props.taskId, left.value, right.value], () => { void load() })
watch(() => props.taskId, () => { left.value = '';right.value = '';urls.value = [null,null] })
onBeforeUnmount(() => { generation++ })
</script>
<template>
  <details v-if="versions.length >= 2" class="creation-compare"><summary>对比两个成片版本</summary><p>分别读取精确版本，不切换当前成片，也不覆盖各自的试映报告。</p><div class="compare-columns"><label v-for="(_, index) in [0,1]" :key="index">{{ index === 0 ? '对照版本' : '修改版本' }}<select :value="index === 0 ? left : right" @change="index === 0 ? left = ($event.target as HTMLSelectElement).value : right = ($event.target as HTMLSelectElement).value"><option value="">请选择版本</option><option v-for="item in versions" :key="item.executionId" :value="item.executionId">{{ item.createTime }} · {{ item.kind === 'REVISION' ? '修改版' : '整片' }}</option></select><video v-if="urls[index]" :src="urls[index]!.url" controls preload="metadata" @error="playbackError(index)"></video><span v-if="index === 0 ? left : right">本次已知制作费用 ¥{{ Number(versions.find(e => e.executionId === (index === 0 ? left : right))?.costs.knownCny ?? 0).toFixed(6) }}</span></label></div><p v-if="error" role="alert">{{ error }}</p><button type="button" @click="load">刷新两个播放地址</button></details>
</template>
<style scoped>
.creation-compare{padding:18px 0;border-top:1px solid var(--border);font-size:var(--fs-sm)}summary{cursor:pointer;font-weight:650}.creation-compare>p{color:var(--muted)}.compare-columns{display:grid;grid-template-columns:1fr 1fr;gap:18px}.compare-columns label{display:grid;gap:10px;align-content:start}select,button{padding:10px;border:1px solid var(--border);border-radius:var(--r-xs);color:var(--ink);background:var(--surface,#fff);font:inherit;min-width:0}video{width:100%;aspect-ratio:16/9;background:#101820}button{margin-top:12px;cursor:pointer}select:focus-visible,button:focus-visible,summary:focus-visible{outline:3px solid var(--accent-ring);outline-offset:2px}@media(max-width:650px){.compare-columns{grid-template-columns:1fr}}
</style>
