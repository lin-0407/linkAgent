<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { readAssetUrl } from '@/api/creationProduction'
import { errorText } from '@/utils/error'
import type { Scene, CreationAsset, SignedRead } from '@/types/creationProduction'

const scene = defineModel<Scene>({ required: true })
const props = defineProps<{ taskId: string; assets: CreationAsset[]; disabled: boolean; index: number; total: number }>()
const emit = defineEmits<{ change: []; audio: []; move: [direction: number]; remove: []; subtitle: [file: File] }>()
const visual = computed(() => props.assets.find(a => a.assetId === scene.value.visualAssetId))
const media = ref<HTMLVideoElement | null>(null), read = ref<SignedRead | null>(null), previewError = ref('')
const body = computed({ get: () => scene.value.body.join('\n'), set: (value: string) => { scene.value.body = value.split('\n').map(s => s.trim()).filter(Boolean);emit('change') } })
let generation = 0
async function refreshPreview() {
  const token = ++generation, assetId = scene.value.visualAssetId, task = props.taskId
  read.value = null;previewError.value = ''
  if (!assetId) return
  try { const result = await readAssetUrl(task, assetId);if (token === generation && task === props.taskId) read.value = result }
  catch (error) { if (token === generation) previewError.value = errorText(error, '素材预览读取失败') }
}
function onPreviewError() {
  if (read.value && Date.parse(read.value.expiresAt) < Date.now() + 5000) void refreshPreview()
  else previewError.value = '素材暂时无法播放，请刷新读取地址后重试'
}
function chooseVisual(event: Event) {
  const id = (event.target as HTMLSelectElement).value || null
  const asset = props.assets.find(a => a.assetId === id)
  if (scene.value.audioMode === 'ORIGINAL' && id !== scene.value.visualAssetId) scene.value.cues = []
  scene.value.visualAssetId = id
  if (asset?.kind === 'VIDEO_CLIP') {
    scene.value.layout = 'VIDEO_CLIP';scene.value.body = [];scene.value.sourceInMs = 0
    scene.value.sourceOutMs = Math.round(Math.round(scene.value.durationMs * 30 / 1000) * 1000 / 30)
  } else if (asset?.kind === 'IMAGE') {
    scene.value.layout = 'IMAGE_CARD';scene.value.body = [];scene.value.sourceInMs = null;scene.value.sourceOutMs = null
  }
  emit('change')
}
function layoutChanged() {
  if (['TITLE_CARD','COMPARE_CARD'].includes(scene.value.layout)) scene.value.visualAssetId = null
  if (!['VIDEO_CLIP','AI_VIDEO'].includes(scene.value.layout)) { scene.value.sourceInMs = null;scene.value.sourceOutMs = null }
  if (['IMAGE_CARD','VIDEO_CLIP','AI_VIDEO'].includes(scene.value.layout)) scene.value.body = []
  if (scene.value.layout === 'AI_VIDEO') { scene.value.visualAssetId = null;scene.value.sourceInMs = 0;scene.value.sourceOutMs = Math.round(Math.round(scene.value.durationMs * 30 / 1000) * 1000 / 30) }
  emit('change')
}
function durationChanged(event: Event) {
  scene.value.durationMs = Math.round(Number((event.target as HTMLInputElement).value) * 1000)
  if (['VIDEO_CLIP','AI_VIDEO'].includes(scene.value.layout)) scene.value.sourceOutMs = (scene.value.sourceInMs ?? 0) + Math.round(Math.round(scene.value.durationMs * 30 / 1000) * 1000 / 30)
  emit('change')
}
function clipStart(event: Event) {
  scene.value.sourceInMs = Math.round(Number((event.target as HTMLInputElement).value) * 1000)
  scene.value.sourceOutMs = scene.value.sourceInMs + Math.round(Math.round(scene.value.durationMs * 30 / 1000) * 1000 / 30)
  if (scene.value.audioMode === 'ORIGINAL') scene.value.cues = []
  emit('change')
}
function mark(end: boolean) {
  if (!media.value) return
  const value = Math.round(media.value.currentTime * 1000)
  if (end) { scene.value.sourceOutMs = value;scene.value.durationMs = Math.max(1, value - (scene.value.sourceInMs ?? 0)) }
  else { scene.value.sourceInMs = value;scene.value.sourceOutMs = value + Math.round(Math.round(scene.value.durationMs * 30 / 1000) * 1000 / 30) }
  if (scene.value.audioMode === 'ORIGINAL') scene.value.cues = []
  emit('change')
}
function playRange() { if (media.value) { media.value.currentTime = (scene.value.sourceInMs ?? 0) / 1000;void media.value.play().catch(() => { previewError.value = '浏览器未能开始播放，请使用播放器控件重试' }) } }
function stopAtEnd() { if (media.value && scene.value.sourceOutMs != null && media.value.currentTime * 1000 >= scene.value.sourceOutMs) media.value.pause() }
function audioChanged() {
  scene.value.narrationAssetId = null;scene.value.cues = []
  emit('audio');emit('change')
}
function recordingChanged() { scene.value.cues = [];emit('change') }
function subtitleChanged(event: Event) { const input = event.target as HTMLInputElement;const file = input.files?.[0];input.value = '';if (file) emit('subtitle', file) }
watch(() => [props.taskId, scene.value.visualAssetId], () => { void refreshPreview() }, { immediate: true })
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <article class="creation-scene">
    <header><strong>场景 {{ index + 1 }}</strong><span>{{ (scene.durationMs / 1000).toFixed(2) }} 秒</span><div class="scene-order"><button type="button" :disabled="disabled || index === 0" aria-label="上移场景" @click="emit('move', -1)">上移</button><button type="button" :disabled="disabled || index === total - 1" aria-label="下移场景" @click="emit('move', 1)">下移</button><button type="button" :disabled="disabled || total <= 1" @click="emit('remove')">删除</button></div></header>
    <div class="scene-columns">
      <div class="scene-picture">
        <video v-if="read && visual?.kind === 'VIDEO_CLIP'" ref="media" :src="read.url" controls preload="metadata" @error="onPreviewError" @timeupdate="stopAtEnd"></video>
        <img v-else-if="read && visual?.kind === 'IMAGE'" :src="read.url" alt="当前场景素材" @error="onPreviewError" />
        <div v-else class="scene-text-preview"><strong>{{ scene.title }}</strong><p v-for="(line, n) in scene.body" :key="n">{{ line }}</p><small v-if="scene.layout === 'AI_VIDEO'">画面将按描述生成</small></div>
        <p v-if="previewError" role="alert">{{ previewError }} <button type="button" @click="refreshPreview">刷新预览</button></p>
        <div v-if="visual?.kind === 'VIDEO_CLIP'" class="scene-range"><button type="button" @click="playRange">播放选段</button><button type="button" :disabled="disabled" @click="mark(false)">设为起点</button><button type="button" :disabled="disabled" @click="mark(true)">设为终点</button></div>
      </div>
      <fieldset :disabled="disabled" class="scene-fields">
        <label>场景标题<input v-model="scene.title" maxlength="60" @input="emit('change')" /></label>
        <label>画面形式<select v-model="scene.layout" @change="layoutChanged"><option value="TITLE_CARD">文字卡</option><option value="IMAGE_CARD">图片</option><option value="SPLIT_CARD">图文并排</option><option value="COMPARE_CARD">双栏对比</option><option value="VIDEO_CLIP">自有视频</option><option value="AI_VIDEO">AI 视频</option></select></label>
        <label v-if="['IMAGE_CARD','SPLIT_CARD','VIDEO_CLIP'].includes(scene.layout)">画面素材<select :value="scene.visualAssetId ?? ''" @change="chooseVisual"><option value="">请选择已准备素材</option><option v-for="asset in assets.filter(a => ['IMAGE','VIDEO_CLIP'].includes(a.kind))" :key="asset.assetId" :value="asset.assetId">{{ asset.fileName }}</option></select></label>
        <label>时长（秒）<input type="number" :value="scene.durationMs / 1000" min="0.034" max="30" step="0.001" @change="durationChanged" /></label>
        <label v-if="scene.layout === 'VIDEO_CLIP'">截取起点（秒）<input type="number" :value="(scene.sourceInMs ?? 0) / 1000" min="0" step="0.001" @change="clipStart" /><small>终点 {{ ((scene.sourceOutMs ?? 0) / 1000).toFixed(3) }} 秒</small></label>
        <label>画面适配<select v-model="scene.fit" @change="emit('change')"><option value="CONTAIN">完整显示</option><option value="COVER">铺满并居中裁切</option></select></label>
        <label v-if="['TITLE_CARD','SPLIT_CARD','COMPARE_CARD'].includes(scene.layout)" class="wide">屏幕正文（每行一项）<textarea v-model="body" rows="3" placeholder="最多四项；双栏对比需要两项"></textarea></label>
        <label v-if="scene.layout === 'AI_VIDEO'" class="wide">画面描述<textarea v-model="scene.visualPrompt" maxlength="600" rows="3" @input="emit('change')"></textarea></label>
        <label>声音来源<select v-model="scene.audioMode" @change="audioChanged"><option value="TTS">AI 配音</option><option value="NARRATION">自己的录音</option><option value="ORIGINAL" :disabled="!['VIDEO_CLIP'].includes(scene.layout)">保留片段原声</option><option value="NO_DIALOGUE">无对白</option></select></label>
        <label v-if="scene.audioMode === 'NARRATION'">录音素材<select v-model="scene.narrationAssetId" @change="recordingChanged"><option :value="null">请选择录音</option><option v-for="asset in assets.filter(a => a.kind === 'NARRATION' && !(a.metadata?.generated))" :key="asset.assetId" :value="asset.assetId">{{ asset.fileName }}</option></select></label>
        <label v-if="scene.audioMode === 'TTS'" class="wide">旁白文稿<textarea v-model="scene.narration" maxlength="1000" rows="3" @input="emit('change')"></textarea></label>
        <div class="wide scene-subtitle"><span>{{ scene.cues.length }} 条字幕</span><label class="file-button">导入本场景 SRT<input type="file" accept=".srt" @change="subtitleChanged" /></label></div>
      </fieldset>
    </div>
  </article>
</template>

<style scoped>
.creation-scene{border-top:1px solid var(--border);padding:22px 0}.creation-scene header,.scene-order,.scene-range,.scene-subtitle{display:flex;align-items:center;gap:10px}.creation-scene header>span,small{color:var(--muted);font-size:var(--fs-xs)}.scene-order{margin-left:auto}.scene-columns{display:grid;grid-template-columns:minmax(180px,.8fr) minmax(260px,1.2fr);gap:22px;margin-top:14px}.scene-picture img,.scene-picture video,.scene-text-preview{width:100%;aspect-ratio:16/9;object-fit:contain;border-radius:var(--r-sm);background:var(--surface-soft,#f6f7f9)}.scene-text-preview{display:flex;box-sizing:border-box;flex-direction:column;justify-content:center;padding:24px;gap:6px}.scene-text-preview strong{font-size:var(--fs-lg)}.scene-text-preview p{margin:0}.scene-fields{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin:0;padding:0;border:0;min-width:0}.scene-fields label{display:grid;gap:6px;min-width:0;font-size:var(--fs-sm)}.wide{grid-column:1/-1}input,select,textarea{box-sizing:border-box;width:100%;min-width:0;padding:9px 10px;border:1px solid var(--border);border-radius:var(--r-xs);background:var(--surface,#fff);color:var(--ink);font:inherit}textarea{resize:vertical}button,.file-button{padding:7px 10px;border:1px solid var(--border);border-radius:var(--r-xs);background:var(--surface,#fff);color:var(--ink);font:inherit;font-size:var(--fs-xs);cursor:pointer}button:disabled,fieldset:disabled{opacity:.6;cursor:default}.file-button input{width:0;height:0;opacity:0;position:absolute}.scene-range{margin-top:8px;flex-wrap:wrap}.scene-subtitle{justify-content:space-between}button:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible,.file-button:focus-within{outline:3px solid var(--accent-ring);outline-offset:2px}@media(max-width:800px){.scene-columns{grid-template-columns:1fr}.scene-picture{max-width:500px}}@media(max-width:480px){.scene-fields{grid-template-columns:1fr}.creation-scene header{flex-wrap:wrap}.scene-order{margin-left:0}.wide{grid-column:auto}}
</style>
