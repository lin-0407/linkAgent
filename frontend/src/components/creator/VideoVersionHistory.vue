<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ApiError } from '@/api/http'
import { getCreationVersions, selectCreationVersion, type CreationVersionList, type CreationVersion } from '@/api/creation'

const props = defineProps<{ taskId: string; activeVersionId: string; busy: boolean }>()
const emit = defineEmits<{ selected: [taskId: string]; uploadNew: [] }>()
const history = ref<CreationVersionList | null>(null)
const page = ref(1)
const loading = ref(false)
const selecting = ref(false)
const error = ref('')
let generation = 0
let listRequest = 0
const pages = computed(() => Math.max(1, Math.ceil((history.value?.total ?? 0) / 10)))

async function load() {
  const taskId = props.taskId
  if (!taskId) return
  const view = generation
  const request = ++listRequest
  loading.value = true
  try {
    const result = await getCreationVersions(taskId, page.value)
    if (view === generation && request === listRequest) history.value = result
  } catch (cause) {
    if (view === generation && request === listRequest) error.value = cause instanceof Error ? cause.message : '读取版本失败'
  } finally {
    if (view === generation && request === listRequest) loading.value = false
  }
}

function unavailable(version: CreationVersion) {
  if (!version.mediaAvailable) return '媒体已删除，保留历史报告'
  if (!version.contextAvailable) return '历史制作依据未知，仅供查看'
  if (!version.reviewReady || version.status !== 'READY_FOR_REVIEW') return '需完成此版本的媒体处理和试映'
  return ''
}

async function select(version: CreationVersion) {
  if (!history.value || selecting.value || props.busy) return
  const view = generation
  const taskId = props.taskId
  selecting.value = true
  error.value = ''
  try {
    await selectCreationVersion(taskId, version.versionId, history.value.workspaceRevision)
    if (view !== generation) return
    emit('selected', taskId)
    await load()
  } catch (cause) {
    if (view !== generation) return
    error.value = cause instanceof Error ? cause.message : '选择版本失败'
    if (cause instanceof ApiError && cause.status === 409) {
      emit('selected', taskId)
      await load()
    }
  } finally {
    if (view === generation) selecting.value = false
  }
}

watch(() => props.taskId, () => {
  generation += 1
  history.value = null
  loading.value = false
  page.value = 1
  selecting.value = false
  error.value = ''
  void load()
}, { immediate: true })
watch(() => props.activeVersionId, () => { void load() })
watch(page, () => { void load() })
onBeforeUnmount(() => { generation += 1 })
</script>

<template>
  <section class="video-version-history" aria-label="视频版本历史" :aria-busy="loading || selecting">
    <div class="version-heading">
      <h4>视频版本 <small v-if="history">{{ history.total }} 个</small></h4>
      <div>
        <button type="button" :disabled="loading || selecting" @click="error = ''; load()">刷新</button>
        <button v-if="history?.replacementAllowed && activeVersionId" type="button"
          :disabled="busy || selecting || loading" @click="emit('uploadNew')">上传新版本</button>
      </div>
    </div>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="!history && loading">正在读取版本…</p>
    <p v-else-if="history?.total === 0">上传完成的视频会保存在这里。</p>
    <p v-if="history && history.total > 1">切换会保留历史视频和报告，已发布的 BV 绑定保持原版本。</p>
    <ul v-if="history?.versions.length">
      <li v-for="version in history.versions" :key="version.versionId">
        <div>
          <strong>V{{ version.versionNo }} · {{ version.versionName }}</strong>
          <small v-if="version.published">已绑定发布</small>
          <span v-if="unavailable(version)">{{ unavailable(version) }}</span>
        </div>
        <button type="button" :disabled="busy || selecting || loading || version.selected || !!unavailable(version)"
          @click="select(version)">{{ version.selected ? '当前版本' : selecting ? '正在切换…' : '选择此版本' }}</button>
      </li>
    </ul>
    <div v-if="pages > 1" class="version-heading">
      <button type="button" :disabled="page <= 1 || loading || selecting" @click="page--">上一页</button>
      <span>{{ page }} / {{ pages }}</span>
      <button type="button" :disabled="page >= pages || loading || selecting" @click="page++">下一页</button>
    </div>
  </section>
</template>

<style scoped>
.video-version-history { margin-block: 16px; padding: 18px; border: 1px solid var(--border-color, #e5e7eb); border-radius: 12px; }
.version-heading, li { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.version-heading > div { display: flex; gap: 8px; flex-wrap: wrap; }
h4 { margin: 0; } small, li span { color: var(--text-secondary, #64748b); font-size: 12px; }
ul { list-style: none; margin: 12px 0 0; padding: 0; }
li { padding-block: 10px; border-top: 1px solid var(--border-color, #e5e7eb); }
li > div { display: grid; gap: 4px; min-width: 0; overflow-wrap: anywhere; }
button { flex-shrink: 0; padding: 6px 10px; border: 1px solid var(--border-color, #e5e7eb); border-radius: 6px; background: var(--bg-primary, white); color: inherit; cursor: pointer; }
button:disabled { opacity: .5; cursor: default; }
[role="alert"] { color: #b42318; }
@media (max-width: 560px) { .version-heading { align-items: flex-start; flex-wrap: wrap; } }
</style>
