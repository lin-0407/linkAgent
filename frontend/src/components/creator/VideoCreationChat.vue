<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { Paperclip, Play, Send, SlidersHorizontal, X } from '@lucide/vue'
import { createInteractiveTask, getInteractiveTask, uploadContextDocuments } from '@/api/creator'
import { executionDownload } from '@/api/creationProduction'
import { useVideoDrafts } from '@/composables/creator/useVideoDrafts'
import { useCreationAssets } from '@/composables/creator/useCreationAssets'
import { useCreatorStore } from '@/stores/creatorStore'
import { errorText } from '@/utils/error'
import type { InteractiveTask } from '@/types/creator'
import type { CreationAsset } from '@/types/creationProduction'

const props = withDefaults(
  defineProps<{ resumeTaskId?: string; skippingToPreflight?: boolean }>(),
  { resumeTaskId: '', skippingToPreflight: false },
)
const emit = defineEmits<{
  taskCreated: [taskId: string]
  pendingUploadChange: [pending: boolean]
  confirmed: [taskId: string]
  skipToPreflight: [taskId: string]
}>()
const AiCreationConsole = defineAsyncComponent(() => import('./AiCreationConsole.vue'))
const store = useCreatorStore()
const taskId = ref(''),
  task = ref<InteractiveTask | null>(null)
const idea = ref(store.newInteractiveTaskDraft.idea),
  feedback = ref('')
const budget = ref<number | ''>(store.newVideoCreationDraft ? store.newVideoCreationDraft.budgetLimitCny ?? '' : 20),
  duration = ref<number | ''>(store.newVideoCreationDraft
    ? store.newVideoCreationDraft.targetDurationMs == null ? '' : store.newVideoCreationDraft.targetDurationMs / 1000
    : 60),
  working = ref(false),
  restoring = ref(false),
  localError = ref('')
const advanced = ref(false),
  selectedDraftId = ref(''),
  previewUrl = ref(''),
  previewError = ref('')
const files = ref<File[]>([]),
  uploadInput = ref<HTMLInputElement | null>(null),
  conversation = ref<HTMLElement | null>(null)
const audioRoles = ref<Record<string, 'MUSIC' | 'NARRATION'>>({})
const p = useVideoDrafts(taskId),
  library = useCreationAssets(taskId)
const { workspace, latest, running, busy, loading, error } = p
const selected = computed(
  () => workspace.value?.versions.find((v) => v.draftId === selectedDraftId.value) ?? latest.value,
)
const versionsInOrder = computed(() => [...(workspace.value?.versions ?? [])].reverse())
const selectedAssetIds = ref<string[]>([])
const availableAssets = computed(() => library.assets.value.filter(
  (asset) => asset.status === 'READY' && !(asset.metadata.generated as { kind?: string } | undefined)?.kind,
))
const selectedAssets = computed(() => availableAssets.value.filter((asset) => selectedAssetIds.value.includes(asset.assetId)))
const tooManyAssets = computed(() => selectedAssets.value.length > 32)
let assetSelectionTaskId = '', knownReadyAssetIds = new Set<string>()

function syncAssetSelection() {
  const id = taskId.value
  if (!id) return
  const readyIds = availableAssets.value.map((asset) => asset.assetId)
  if (assetSelectionTaskId !== id) {
    selectedAssetIds.value = (store.videoCreationAssetSelections[id] ?? readyIds).filter((assetId) => readyIds.includes(assetId))
    assetSelectionTaskId = id
  } else {
    selectedAssetIds.value = [...new Set([
      ...selectedAssetIds.value.filter((assetId) => readyIds.includes(assetId)),
      ...readyIds.filter((assetId) => !knownReadyAssetIds.has(assetId)),
    ])]
  }
  knownReadyAssetIds = new Set(readyIds)
  saveAssetSelection()
}
function saveAssetSelection() {
  if (taskId.value) store.setVideoCreationAssets(taskId.value, selectedAssetIds.value)
}
watch(library.assets, () => {
  // 等首次真实素材读取完成再恢复选择，切任务时的空数组不能覆盖该任务的本机选择。
  if (assetSelectionTaskId === taskId.value) syncAssetSelection()
}, { flush: 'sync' })
const pendingUpload = computed(() => files.value.length > 0 || library.busy.value || working.value)
const locked = computed(
  () =>
    working.value ||
    busy.value ||
    restoring.value ||
    loading.value ||
    running.value ||
    props.skippingToPreflight,
)
const canStart = computed(
  () =>
    !locked.value && !tooManyAssets.value &&
    (taskId.value
      ? !latest.value || ['COMPLETED', 'CANCELLED'].includes(latest.value.status)
      : idea.value.trim().length > 0) &&
    (!latest.value || !!feedback.value.trim()),
)
const statusText = computed(() => {
  const value = latest.value
  if (!value) return '先做出来，再一起调整'
  if (value.status === 'PREPARING') return '正在组织内容和画面'
  if (value.status === 'EXECUTING')
    return value.execution?.stage === 'VOICE' ? '正在制作配音和字幕' : '正在制作视频'
  if (value.status === 'WAITING_USER') return '制作暂停，需要处理'
  if (value.status === 'CANCEL_REQUESTED') return '正在结束制作'
  return value.status === 'COMPLETED'
    ? `V${value.version} 已完成，可以观看`
    : '制作已结束，可以继续调整'
})
let generation = 0,
  previewGeneration = 0,
  disposed = false
let signedUntil = 0,
  previewRetried = false

function savedVideoSettings(id = taskId.value || props.resumeTaskId) {
  return id ? store.videoCreationDrafts[id] : store.newVideoCreationDraft
}
function saveVideoSettings() {
  // 空输入也保留，不能因另一项尚未填写就丢掉刚改的预算，或刷新后擅自恢复默认金额。
  const amount = budget.value === '' ? null : Number(budget.value)
  const seconds = duration.value === '' ? null : Number(duration.value)
  if ((amount !== null && !Number.isFinite(amount)) ||
      (seconds !== null && !Number.isFinite(seconds))) return
  store.setVideoCreationDraft(taskId.value || props.resumeTaskId || null, {
    budgetLimitCny: amount,
    targetDurationMs: seconds === null ? null : Math.round(seconds * 1000),
  })
}

watch(pendingUpload, (value) => emit('pendingUploadChange', value), { immediate: true })
watch(idea, (value) => {
  if (!taskId.value) store.setNewInteractiveTaskDraft(value, '未分类')
})
watch(feedback, (value) => {
  if (taskId.value) store.setInteractiveTaskDraft(taskId.value, { feedbackDraft: value })
})
watch(latest, async (value, previous) => {
  const changed = value?.draftId !== previous?.draftId || value?.status !== previous?.status
  const element = conversation.value
  const following = !element || element.scrollHeight - element.scrollTop - element.clientHeight < 80
  if (value?.draftId !== previous?.draftId) selectedDraftId.value = value?.draftId ?? ''
  if (
    value?.targetDurationMs && !savedVideoSettings() &&
    (value.draftId !== previous?.draftId ||
      (value.status === 'COMPLETED' && previous?.status !== 'COMPLETED'))
  )
    duration.value = value.targetDurationMs / 1000
  if (value?.instruction && value.instruction === feedback.value.trim()) feedback.value = ''
  if (!changed || (!following && value?.draftId === previous?.draftId)) return
  await nextTick()
  conversation.value?.scrollTo({ top: conversation.value.scrollHeight, behavior: 'smooth' })
})
watch(
  () => props.resumeTaskId,
  async (id) => {
    if (id === taskId.value) return
    const token = ++generation
    taskId.value = ''
    task.value = null
    feedback.value = ''
    files.value = []
    selectedAssetIds.value = []
    assetSelectionTaskId = ''
    knownReadyAssetIds = new Set()
    previewUrl.value = ''
    localError.value = ''
    const settings = savedVideoSettings(id)
    budget.value = settings ? settings.budgetLimitCny ?? '' : 20
    duration.value = settings ? settings.targetDurationMs == null ? '' : settings.targetDurationMs / 1000 : 60
    if (!id) {
      idea.value = store.newInteractiveTaskDraft.idea
      return
    }
    restoring.value = true
    try {
      const restored = await getInteractiveTask(id)
      if (token !== generation || disposed) return
      task.value = restored
      idea.value = restored.idea
      taskId.value = id
      feedback.value = store.interactiveTaskDrafts[id]?.feedbackDraft ?? ''
      await library.refresh()
      if (token === generation && !disposed) syncAssetSelection()
    } catch (reason) {
      if (token === generation && !disposed) localError.value = errorText(reason, '创作恢复失败')
    } finally {
      if (token === generation && !disposed) restoring.value = false
    }
  },
  { immediate: true },
)
watch(
  () => workspace.value?.runtime.budget?.limitCny,
  (value) => {
    if (value != null && !savedVideoSettings()) budget.value = Number(value)
  },
)
watch(
  () => `${selected.value?.draftId}:${selected.value?.execution?.previewAvailable}`,
  () => {
    previewRetried = false
    void loadPreview()
  },
)

async function ensureTask() {
  if (taskId.value) return taskId.value
  const created = await createInteractiveTask({ idea: idea.value.trim(), videoType: '未分类' })
  if (disposed) return ''
  // 任务创建与费用受理是两步；先迁移输入，接口失败后仍能恢复刚才选择的金额。
  if (store.newVideoCreationDraft)
    store.setVideoCreationDraft(created.taskId, store.newVideoCreationDraft)
  task.value = created
  taskId.value = created.taskId
  store.clearNewInteractiveTaskDraft()
  emit('taskCreated', created.taskId)
  return created.taskId
}
function selectFiles(event: Event) {
  const input = event.target as HTMLInputElement
  files.value.push(...Array.from(input.files ?? []))
  input.value = ''
}
function kind(file: File): CreationAsset['kind'] | null {
  if (file.type.startsWith('image/') || /\.(png|jpe?g|webp)$/i.test(file.name)) return 'IMAGE'
  if (file.type.startsWith('video/') || /\.(mp4|mov|webm)$/i.test(file.name)) return 'VIDEO_CLIP'
  if (file.type.startsWith('audio/') || /\.(wav|mp3|m4a|flac)$/i.test(file.name)) return 'MUSIC'
  return null
}
const fileKey = (file: File) => `${file.name}:${file.size}:${file.lastModified}`
async function makeVideo() {
  if (!canStart.value) return
  working.value = true
  localError.value = ''
  const token = generation
  const check = () => {
    if (disposed || token !== generation) throw new DOMException('任务已切换', 'AbortError')
  }
  try {
    const id = await ensureTask()
    check()
    if (!id) return
    const uploadedAssets: { file: File; assetId: string }[] = []
    for (const file of [...files.value]) {
      const assetKind = audioRoles.value[fileKey(file)] ?? kind(file)
      if (assetKind) {
        const assetId = await library.upload(file, assetKind)
        check()
        if (library.error.value) throw new Error(library.error.value)
        if (!assetId) throw new Error(`素材“${file.name}”的上传结果未确认，请重试`)
        uploadedAssets.push({ file, assetId })
      } else {
        task.value = await uploadContextDocuments(id, [file])
        check()
        files.value = files.value.filter((pending) => pending !== file)
      }
    }
    await library.refresh()
    check()
    syncAssetSelection()
    const deadline = Date.now() + 180000
    // 只等待这次明确添加的素材，历史失败或未完成的上传不应永久锁住整个任务。
    while (library.assets.value.some((asset) =>
      uploadedAssets.some((uploaded) => uploaded.assetId === asset.assetId) && !['READY', 'CANCELLED', 'FAILED'].includes(asset.status),
    )) {
      if (Date.now() > deadline) throw new Error('素材仍在准备，进度已保存。准备完成后可继续制作。')
      await new Promise((resolve) => setTimeout(resolve, 1000))
      check()
      await library.refresh()
      check()
    }
    for (const { file, assetId } of uploadedAssets) {
      const asset = library.assets.value.find((item) => item.assetId === assetId)
      // 未成功的文件继续留在输入区，重试或明确移除前不能悄悄漏用。
      if (asset?.status !== 'READY')
        throw new Error(`素材“${file.name}”未准备完成：${asset?.failureMessage || '已取消或暂不可用，请重试或移除文件'}`)
      if (!selectedAssetIds.value.includes(assetId)) selectedAssetIds.value.push(assetId)
      files.value = files.value.filter((pending) => pending !== file)
    }
    saveAssetSelection()
    await p.refresh()
    check()
    if (!workspace.value) throw new Error(error.value || '制作工作区读取失败，请重试')
    if (tooManyAssets.value) throw new Error('本次素材超过32份，请在已提供的素材中取消部分选择后再制作')
    const success = await p.start({
      expectedWorkspaceRevision: workspace.value.runtime.workspaceRevision,
      baseDraftId: latest.value?.draftId ?? null,
      instruction: feedback.value.trim(),
      budgetLimitCny: Number(budget.value),
      targetDurationMs: Math.round(Number(duration.value) * 1000),
      assetIds: selectedAssets.value.map((asset) => asset.assetId),
    })
    check()
    if (success) feedback.value = ''
  } catch (reason) {
    if (!disposed && token === generation) localError.value = errorText(reason, '视频制作未能开始')
  } finally {
    if (!disposed && token === generation) working.value = false
  }
}
async function loadPreview() {
  const token = ++previewGeneration,
    version = selected.value,
    id = taskId.value
  previewUrl.value = ''
  previewError.value = ''
  if (!id || !version?.execution?.previewAvailable) return
  try {
    const signed = await executionDownload(id, version.execution.executionId, 'video.mp4')
    if (token === previewGeneration && !disposed) {
      previewUrl.value = signed.url
      signedUntil = Date.parse(signed.expiresAt)
    }
  } catch (reason) {
    if (token === previewGeneration && !disposed)
      previewError.value = errorText(reason, '视频暂时无法加载，可重试')
  }
}
async function onPlaybackError() {
  if (previewRetried) {
    previewError.value = '视频加载失败，请重新加载视频。'
    return
  }
  previewRetried = true
  await loadPreview()
}
function onPlayback() {
  if (Date.now() > signedUntil - 15000) void loadPreview()
}
async function download(name: string) {
  const version = selected.value
  if (!version?.execution) return
  try {
    const signed = await executionDownload(taskId.value, version.execution.executionId, name)
    if (disposed) return
    const link = document.createElement('a')
    link.href = signed.url
    link.target = '_blank'
    link.rel = 'noopener'
    link.download = name
    link.click()
  } catch (reason) {
    localError.value = errorText(reason, '下载失败，请重试')
  }
}
function beforeUnload(event: BeforeUnloadEvent) {
  if (pendingUpload.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
window.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => {
  disposed = true
  generation++
  previewGeneration++
  window.removeEventListener('beforeunload', beforeUnload)
  emit('pendingUploadChange', false)
})
</script>

<template>
  <div v-if="advanced" class="video-chat-advanced">
    <button type="button" @click="advanced = false">返回视频创作</button>
    <AiCreationConsole
      :resume-task-id="taskId"
      :skipping-to-preflight="skippingToPreflight"
      @task-created="emit('taskCreated', $event)"
      @confirmed="emit('confirmed', $event)"
      @skip-to-preflight="emit('skipToPreflight', $event)"
      @pending-upload-change="emit('pendingUploadChange', $event)"
    />
  </div>
  <section v-else class="video-chat" :class="{ 'has-video': !!latest }" aria-label="视频创作">
    <header class="video-chat-header">
      <div>
        <h2>{{ task ? '一起把这条视频做完' : '把想法变成第一版视频' }}</h2>
        <p>{{ statusText }}</p>
      </div>
      <div class="video-chat-tools">
        <button v-if="taskId && !locked" type="button" @click="emit('skipToPreflight', taskId)">
          已有成片
        </button>
        <button type="button" :disabled="locked" @click="advanced = true">
          <SlidersHorizontal :size="15" />策划工具
        </button>
      </div>
    </header>
    <div class="video-chat-workspace">
      <div class="video-chat-dialogue">
        <div ref="conversation" class="video-chat-messages" aria-live="polite">
          <div v-if="!task" class="video-chat-welcome">
            <Play :size="26" />
            <h3>想法还模糊，也可以开始</h3>
            <p>先说你想做什么。AI 会组织内容、选择画面并制作完整初稿，看完再一起改。</p>
          </div>
          <article v-if="task" class="video-chat-message is-user">
            <span>你的想法</span>
            <p>{{ task.idea }}</p>
          </article>
          <template v-for="version in versionsInOrder" :key="version.draftId">
            <article v-if="version.instruction" class="video-chat-message is-user">
              <span>你的修改意见</span>
              <p>{{ version.instruction }}</p>
            </article>
            <article class="video-chat-message is-agent">
              <span>视频 V{{ version.version }}</span>
              <template v-if="version.status === 'COMPLETED'">
                <p>这一版已经做好了。先看视频，哪里不合适，直接告诉我。</p>
                <button type="button" @click="selectedDraftId = version.draftId">
                  <Play :size="14" />观看 V{{ version.version }}
                </button>
              </template>
              <p v-else-if="version.status === 'WAITING_USER'">
                {{ version.message || '制作遇到实际问题，请结束本次制作后重试。' }}
              </p>
              <p v-else-if="version.status === 'CANCELLED'">这次制作已结束，之前的版本保留。</p>
              <p v-else>
                {{
                  version.draftId === latest?.draftId ? statusText : '进度已保存'
                }}，完成后会显示可播放的视频。
              </p>
            </article>
          </template>
          <p v-if="restoring || loading" class="video-chat-muted">正在恢复作品和制作进度…</p>
        </div>
        <form class="video-chat-composer" @submit.prevent="makeVideo">
          <p v-if="localError || error" class="video-chat-error" role="alert">
            {{ localError || error }}
          </p>
          <p v-if="library.progress.value && working" class="video-chat-muted" role="status">
            {{ library.progress.value }}
          </p>
          <div v-if="files.length" class="video-chat-files">
            <span v-for="(file, index) in files" :key="fileKey(file)"
              >{{ file.name
              }}<select
                v-if="kind(file) === 'MUSIC'"
                v-model="audioRoles[fileKey(file)]"
                :aria-label="`${file.name}的音频用途`"
                :disabled="working"
              >
                <option :value="undefined">配乐或环境声</option>
                <option value="NARRATION">旁白录音</option></select
              ><button
                type="button"
                :disabled="working"
                :aria-label="`移除${file.name}`"
                @click="files.splice(index, 1)"
              >
                <X :size="13" /></button
            ></span>
          </div>
          <details
            v-if="library.assets.value.length || task?.uploadedDocuments.length"
            class="video-chat-assets"
            :open="tooManyAssets || undefined"
          >
            <summary>已提供的素材和资料</summary>
            <p>本次选用 {{ selectedAssets.length }} / 32 份素材，共 {{ availableAssets.length }} 份可用；取消勾选会保留原文件。</p>
            <div class="video-chat-asset-list">
            <p v-for="asset in library.assets.value" :key="asset.assetId" class="video-chat-asset-row">
              <label v-if="availableAssets.some((item) => item.assetId === asset.assetId)">
                <input v-model="selectedAssetIds" type="checkbox" :value="asset.assetId"
                  :aria-label="`本次使用${asset.fileName}`" :disabled="locked" @change="saveAssetSelection" />
                {{ asset.fileName }}
              </label>
              <span v-else>{{ asset.fileName }}</span> ·
              {{
                asset.status === 'READY'
                  ? '可使用'
                  : asset.status === 'FAILED'
                    ? '准备失败，本次不使用；可重新添加文件'
                    : asset.status === 'CANCELLED'
                      ? '已移除，本次不使用'
                    : '正在准备'
              }}
            </p>
            <p v-for="doc in task?.uploadedDocuments" :key="doc.documentId">{{ doc.fileName }}</p>
            </div>
          </details>
          <p v-if="availableAssets.length > selectedAssets.length" class="video-chat-muted">
            还有 {{ availableAssets.length - selectedAssets.length }} 份可用素材未选用，可展开上方列表调整。
          </p>
          <p v-if="tooManyAssets" class="video-chat-error" role="alert">
            本次已选择 {{ selectedAssets.length }} 份素材，最多32份；请取消部分勾选后制作。
          </p>
          <label class="sr-only" :for="task ? 'video-feedback' : 'video-idea'">{{
            task ? '修改意见' : '视频想法'
          }}</label>
          <textarea
            v-if="!task"
            id="video-idea"
            v-model="idea"
            rows="4"
            maxlength="3000"
            :disabled="locked"
            placeholder="比如：想做一个月亮维修员的小故事，氛围安静一点。其他细节你先来定。"
          />
          <textarea
            v-else
            id="video-feedback"
            v-model="feedback"
            rows="3"
            maxlength="8000"
            :disabled="locked"
            :placeholder="
              latest
                ? '看完后告诉我怎么改，也可以补充图片、视频或资料…'
                : '可以继续补充，也可以直接开始制作第一版…'
            "
          />
          <div class="video-chat-options">
            <label
              >本任务累计预算上限
              <input
                v-model.number="budget"
                @input="saveVideoSettings"
                aria-label="本任务累计预算上限"
                type="number"
                required
                min="0.01"
                max="10000"
                step="0.01"
                :disabled="locked"
              />
              元</label
            ><label
              >目标时长
              <input
                v-model.number="duration"
                @input="saveVideoSettings"
                aria-label="目标时长"
                type="number"
                required
                min="1"
                max="180"
                :disabled="locked"
              />
              秒</label
            >
          </div>
          <div class="video-chat-submit">
            <input
              ref="uploadInput"
              type="file"
              multiple
              hidden
              accept="image/png,image/jpeg,image/webp,video/mp4,video/quicktime,video/webm,audio/*,.pdf,.docx,.txt,.md,.html"
              @change="selectFiles"
            /><button type="button" :disabled="locked" @click="uploadInput?.click()">
              <Paperclip :size="17" />添加素材</button
            ><button
              v-if="latest && !['COMPLETED', 'CANCELLED'].includes(latest.status)"
              type="button"
              :disabled="busy || working"
              @click="p.cancel()"
            >
              {{ latest.status === 'CANCEL_REQUESTED' ? '正在结束…' : '结束本次制作' }}</button
            ><button v-else class="video-chat-primary" type="submit" :disabled="!canStart">
              <Send :size="16" />{{
                working || busy ? '正在准备…' : latest ? '按反馈制作新版' : '制作第一版视频'
              }}
            </button>
          </div>
          <p class="video-chat-footnote">
            点击制作即授权在上述累计预算内准备内容并制作这一版，进度自动保存。
          </p>
        </form>
      </div>
      <aside class="video-chat-preview" aria-label="视频版本与改进说明">
        <header>
          <h3>{{ selected ? `视频 V${selected.version}` : '视频会出现在这里' }}</h3>
          <select
            v-if="workspace?.versions.length"
            v-model="selectedDraftId"
            aria-label="选择视频版本"
          >
            <option
              v-for="version in workspace.versions"
              :key="version.draftId"
              :value="version.draftId"
            >
              V{{ version.version }}{{ version.status === 'COMPLETED' ? ' · 已完成' : '' }}
            </option>
          </select>
        </header>
        <video
          v-if="previewUrl"
          :key="previewUrl"
          :src="previewUrl"
          controls
          playsinline
          preload="metadata"
          aria-label="完整视频初稿"
          @error="onPlaybackError"
          @play="onPlayback"
        />
        <div v-else class="video-chat-empty">
          <Play :size="32" />
          <p>
            {{
              selected?.status === 'COMPLETED'
                ? '正在加载视频…'
                : latest
                  ? statusText
                  : '先制作一版，再根据实际画面调整'
            }}
          </p>
        </div>
        <p v-if="previewError" class="video-chat-error" role="alert">{{ previewError }}</p>
        <div v-if="selected?.execution?.previewAvailable" class="video-chat-downloads">
          <button type="button" @click="download('video.mp4')">下载视频</button
          ><button type="button" @click="download('subtitles.srt')">下载字幕</button
          ><button type="button" @click="loadPreview">重新加载视频</button>
        </div>
        <template v-if="selected?.status === 'COMPLETED'">
          <section v-if="selected.creativeChoices.length" class="video-chat-review">
            <h4>这一版的处理</h4>
            <p v-for="(item, index) in selected.creativeChoices" :key="index">{{ item }}</p>
          </section>
          <section class="video-chat-review">
            <h4>看完可以再改的地方</h4>
            <p v-for="(item, index) in selected.reviewNotes" :key="index">{{ item }}</p>
            <small>这些是修改参考，可以直接继续观看和制作。</small>
          </section>
        </template>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.video-chat {
  --ink: #202630;
  --muted: #687383;
  --line: #e2e6ec;
  --blue: #245bd6;
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  width: 100%;
  overflow: hidden;
  color: var(--ink);
  background: #fff;
  text-align: left;
}
.video-chat-header {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 22px 28px;
  border-bottom: 1px solid var(--line);
}
.video-chat-header h2 {
  font-size: 20px;
  font-weight: 650;
  margin: 0 0 6px;
  letter-spacing: -0.3px;
}
.video-chat-header p,
.video-chat-muted {
  font-size: 13px;
  color: var(--muted);
  margin: 0;
  line-height: 1.7;
}
.video-chat-tools {
  display: flex;
  gap: 12px;
}
.video-chat button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  font: inherit;
  font-size: 13px;
  border: 1px solid var(--line);
  border-radius: 7px;
  padding: 8px 12px;
  background: #fff;
  color: var(--ink);
  cursor: pointer;
  white-space: nowrap;
}
.video-chat button:hover {
  background: #f2f5fa;
}
.video-chat button:disabled {
  opacity: 0.45;
  cursor: default;
}
.video-chat :is(button, input, textarea, select):focus-visible {
  outline: 2px solid var(--blue);
  outline-offset: 3px;
}
.video-chat-workspace {
  display: grid;
  grid-template-columns: minmax(300px, 0.9fr) minmax(350px, 1.1fr);
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.video-chat-dialogue {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  border-right: 1px solid var(--line);
}
.video-chat-messages {
  flex: 1;
  overflow: auto;
  padding: 28px;
  min-height: 0;
  scrollbar-gutter: stable;
}
.video-chat-welcome {
  max-width: 420px;
  margin: 28px 0 48px;
  line-height: 1.85;
}
.video-chat-welcome > svg {
  color: var(--blue);
}
.video-chat-welcome h3 {
  font-size: 19px;
  font-weight: 600;
  margin: 18px 0 10px;
}
.video-chat-welcome p {
  color: var(--muted);
  font-size: 14px;
}
.video-chat-message {
  max-width: 70ch;
  margin-bottom: 24px;
  font-size: 14px;
  line-height: 1.85;
  overflow-wrap: anywhere;
}
.video-chat-message > span {
  display: block;
  font-size: 12px;
  color: var(--muted);
  margin-bottom: 6px;
}
.video-chat-message p {
  margin: 0 0 10px;
  white-space: pre-wrap;
}
.video-chat-message.is-user {
  padding: 14px 16px;
  background: #f2f5fa;
  border-radius: 10px 10px 2px 10px;
}
.video-chat-message.is-agent {
  padding-left: 4px;
}
.video-chat-composer {
  min-height: 0;
  max-height: 100%;
  box-sizing: border-box;
  overflow-y: auto;
  padding: 18px 24px 16px;
  border-top: 1px solid var(--line);
  background: #fff;
}
.video-chat-composer textarea {
  display: block;
  box-sizing: border-box;
  width: 100%;
  resize: vertical;
  min-height: 80px;
  max-height: 220px;
  padding: 12px 14px;
  font: inherit;
  font-size: 14px;
  line-height: 1.8;
  color: var(--ink);
  background: #fafbfd;
  border: 1px solid #d7dfe9;
  border-radius: 8px;
}
.video-chat-composer textarea::placeholder {
  color: #8490a0;
}
.video-chat-options {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  margin: 12px 0;
  font-size: 12px;
  color: var(--muted);
}
.video-chat-options input {
  width: 68px;
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 4px;
  color: var(--ink);
  font: inherit;
}
.video-chat-submit {
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: space-between;
}
.video-chat .video-chat-primary {
  background: var(--blue);
  border-color: var(--blue);
  color: #fff;
  padding: 10px 16px;
}
.video-chat .video-chat-primary:hover {
  background: #1949b7;
}
.video-chat-footnote {
  font-size: 11px;
  line-height: 1.6;
  color: var(--muted);
  margin: 10px 0 0;
}
.video-chat-files {
  max-height: 84px;
  overflow-y: auto;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
}
.video-chat-files > span {
  display: flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  overflow-wrap: anywhere;
  padding: 3px 8px;
  background: #f2f5fa;
  font-size: 12px;
  border-radius: 4px;
}
.video-chat-files button {
  padding: 2px;
  border: 0;
  background: none;
}
.video-chat-assets {
  font-size: 12px;
  color: var(--muted);
  margin-bottom: 10px;
}
.video-chat-assets p {
  margin: 5px 0;
}
.video-chat-asset-list {
  max-height: 120px;
  overflow-y: auto;
}
.video-chat-asset-row label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  overflow-wrap: anywhere;
}
.video-chat-asset-row input[type='checkbox'] {
  width: auto;
  min-height: 0;
  flex-shrink: 0;
  margin: 0;
}
.video-chat-error {
  color: #b53333;
  font-size: 13px;
  line-height: 1.7;
  overflow-wrap: anywhere;
  margin: 0 0 10px;
}
.video-chat-preview {
  padding: 24px 28px;
  overflow: auto;
  background: #f8f9fc;
  min-width: 0;
}
.video-chat-preview > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px;
}
.video-chat-preview h3 {
  font-size: 15px;
  font-weight: 600;
  margin: 0;
}
.video-chat-preview select {
  max-width: 140px;
  padding: 6px 8px;
  border: 1px solid var(--line);
  border-radius: 5px;
  background: #fff;
  font: inherit;
  font-size: 12px;
}
.video-chat-preview video {
  display: block;
  width: 100%;
  max-height: 50vh;
  aspect-ratio: 16/9;
  background: #111;
  border-radius: 5px;
}
.video-chat-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  aspect-ratio: 16/9;
  min-height: 160px;
  background: #eef1f6;
  border: 1px solid var(--line);
  border-radius: 5px;
  color: #7a8798;
  text-align: center;
  font-size: 13px;
  padding: 16px;
  box-sizing: border-box;
}
.video-chat-downloads {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 14px 0 24px;
}
.video-chat-review {
  margin-top: 26px;
  max-width: 70ch;
}
.video-chat-review h4 {
  font-size: 14px;
  margin: 0 0 12px;
}
.video-chat-review p {
  font-size: 13px;
  line-height: 1.85;
  margin: 0 0 9px;
  overflow-wrap: anywhere;
}
.video-chat-review small {
  font-size: 12px;
  color: var(--muted);
}
.video-chat .video-chat-options label {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0;
  font: inherit;
  color: inherit;
}
.video-chat .video-chat-options input {
  box-sizing: border-box;
  width: 76px;
  max-width: 76px;
  min-width: 0;
  flex: 0 0 76px;
  margin: 0;
}
.video-chat-advanced {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  width: 100%;
}
.video-chat-advanced > button {
  align-self: flex-start;
  margin: 12px 24px;
  padding: 7px 12px;
  border: 1px solid #d7dfe9;
  border-radius: 6px;
  background: #fff;
  font: inherit;
  font-size: 13px;
}
@media (max-width: 1000px) {
  .video-chat-workspace {
    grid-template-columns: minmax(250px, 1fr) minmax(280px, 1fr);
  }
  .video-chat-header,
  .video-chat-preview {
    padding: 18px;
  }
  .video-chat-messages {
    padding: 20px;
  }
  .video-chat-composer {
    padding: 16px;
  }
  .video-chat-tools {
    gap: 6px;
  }
  .video-chat-tools button {
    font-size: 12px;
    padding: 6px 8px;
  }
}
@media (max-width: 720px) {
  .video-chat {
    overflow: auto;
  }
  .video-chat-header {
    align-items: flex-start;
    gap: 10px;
  }
  .video-chat-header h2 {
    font-size: 17px;
  }
  .video-chat-tools {
    flex-direction: column;
  }
  .video-chat-workspace {
    display: flex;
    flex-direction: column;
    overflow: visible;
  }
  .video-chat-dialogue {
    border-right: 0;
    min-height: 400px;
    flex: none;
  }
  .video-chat-messages {
    max-height: 45vh;
  }
  .video-chat-preview {
    overflow: visible;
    border-top: 1px solid var(--line);
  }
  .video-chat-submit {
    flex-wrap: wrap;
  }
  .video-chat-preview video {
    max-height: none;
  }
  .video-chat-options {
    font-size: 11px;
    gap: 10px;
  }
  .video-chat-composer textarea {
    font-size: 16px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .video-chat-messages {
    scroll-behavior: auto;
  }
}
</style>
