<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useCreatorWorkspaceShell } from '@/composables/creator/useCreatorWorkspaceContext'
import { useMediaUpload } from '@/composables/creator/useMediaUpload'

const { selectedTaskId, selectedTask } = useCreatorWorkspaceShell()
const versionName = ref('V1 初剪')
const selectedFile = ref<File | null>(null)
const localError = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
let isDisposed = false
let viewGeneration = 0

const mediaUpload = useMediaUpload()
const {
  currentUpload,
  completedDraft,
  isUploading,
  isPaused,
  errorMessage,
  statusMessage,
  progressPercent,
  restoreStoredUpload,
  startOrResume,
  pauseUpload,
  resetForTaskChange,
  disposeUpload,
  cancelUpload,
} = mediaUpload

const taskId = computed(() => selectedTaskId.value ?? '')
const fileSummary = computed(() => {
  if (!selectedFile.value) return '尚未选择文件'
  return `${selectedFile.value.name} · ${formatBytes(selectedFile.value.size)}`
})
const visibleError = computed(() => localError.value || errorMessage.value)
const completedPartRatio = computed(() => {
  const upload = currentUpload.value
  if (!upload || upload.totalParts <= 0) return '0 / 0'
  const estimatedCompleted = Math.round((progressPercent.value / 100) * upload.totalParts)
  return `${Math.max(upload.completedPartCount, estimatedCompleted)} / ${upload.totalParts}`
})
const uploadActionLabel = computed(() => {
  if (currentUpload.value?.status === 'VERIFYING') return '确认结果'
  return isPaused.value || currentUpload.value ? '继续上传' : '开始上传'
})

onMounted(async () => {
  const generation = viewGeneration
  if (!taskId.value) return
  try {
    await restoreStoredUpload(taskId.value)
  } catch (error) {
    if (isDisposed || generation !== viewGeneration) return
    localError.value = toMessage(error)
  }
})

watch(taskId, async (nextTaskId) => {
  viewGeneration += 1
  resetForTaskChange()
  selectedFile.value = null
  localError.value = ''
  if (nextTaskId) {
    await restoreStoredUpload(nextTaskId)
  }
})

onBeforeUnmount(() => {
  isDisposed = true
  viewGeneration += 1
  disposeUpload()
})

function selectFile() {
  fileInput.value?.click()
}

function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  selectedFile.value = input.files?.[0] ?? null
  localError.value = ''
}

async function beginUpload() {
  localError.value = ''
  const currentTaskId = taskId.value
  const file = selectedFile.value
  if (!currentTaskId || !file) {
    localError.value = '请先选择当前任务的 MP4 成片'
    return
  }
  try {
    const generation = viewGeneration
    const durationSeconds = await readVideoDuration(file)
    if (isDisposed || generation !== viewGeneration || currentTaskId !== taskId.value) {
      return
    }
    if (durationSeconds > 1800) {
      localError.value = '视频时长不能超过30分钟'
      return
    }
    await startOrResume(currentTaskId, file, versionName.value)
  } catch (error) {
    // composable 已保存详细错误，这里只防止事件处理器产生未处理 Promise。
    if (!errorMessage.value) localError.value = toMessage(error)
  }
}

async function cancelCurrentUpload() {
  if (!taskId.value) return
  try {
    await cancelUpload(taskId.value)
    selectedFile.value = null
    if (fileInput.value) fileInput.value.value = ''
  } catch (error) {
    localError.value = toMessage(error)
  }
}

function formatBytes(bytes: number) {
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
}

function readVideoDuration(file: File): Promise<number> {
  return new Promise((resolve, reject) => {
    const video = document.createElement('video')
    const objectUrl = URL.createObjectURL(file)
    video.preload = 'metadata'
    video.onloadedmetadata = () => {
      URL.revokeObjectURL(objectUrl)
      resolve(Number.isFinite(video.duration) ? video.duration : 0)
    }
    video.onerror = () => {
      URL.revokeObjectURL(objectUrl)
      reject(new Error('浏览器无法读取该 MP4 的时长，请确认文件没有损坏'))
    }
    video.src = objectUrl
  })
}

function toMessage(error: unknown) {
  return error instanceof Error ? error.message : '媒体操作失败'
}
</script>

<template>
  <section class="creator-section preflight-studio">
    <header class="preflight-hero">
      <div>
        <p class="preflight-eyebrow">FINAL CUT REVIEW</p>
        <h3>成片试映</h3>
        <p>
          上传最终成片并保留传输进度，随后进入发布前画面与声音体检。
        </p>
      </div>
      <div class="preflight-limit-plate" aria-label="视频上传限制">
        <span>MP4 ONLY</span>
        <strong>30:00</strong>
        <small>最大 1.5 GB</small>
      </div>
    </header>

    <div class="preflight-route" aria-label="媒体上传链路">
      <span>选择成片</span><i aria-hidden="true"></i><span>分片上传</span><i aria-hidden="true"></i><span>发布前体检</span>
    </div>

    <div class="preflight-work-grid">
        <section class="preflight-file-card">
          <span class="preflight-index">01</span>
          <div class="preflight-card-head">
            <div>
              <h4>选择成片</h4>
              <p>{{ selectedTask?.taskName || '当前创作任务' }}</p>
            </div>
            <span v-if="currentUpload" class="preflight-state-chip">{{ currentUpload.status }}</span>
          </div>

          <label class="preflight-version-field">
            <span>版本名称</span>
            <input v-model="versionName" type="text" maxlength="128" :disabled="isUploading" />
          </label>

          <button type="button" class="preflight-dropzone" :disabled="isUploading" @click="selectFile">
            <span class="preflight-reel" aria-hidden="true">◉</span>
            <strong>{{ selectedFile ? '已选择成片' : '选择 MP4 文件' }}</strong>
            <small>{{ fileSummary }}</small>
          </button>
          <input
            ref="fileInput"
            class="preflight-hidden-input"
            type="file"
            accept="video/mp4,.mp4"
            @change="handleFileChange"
          />

          <p v-if="currentUpload && !selectedFile && currentUpload.status !== 'COMPLETED'" class="preflight-resume-note">
            已恢复服务器端分片记录。浏览器不会保存你的本地文件，请重新选择同一个文件继续。
          </p>
        </section>

        <section class="preflight-meter-card">
          <span class="preflight-index">02</span>
          <div class="preflight-meter-head">
            <div>
              <h4>分片传输</h4>
              <p>支持暂停与继续</p>
            </div>
            <strong>{{ progressPercent.toFixed(1) }}%</strong>
          </div>

          <div class="preflight-progress-track" aria-label="上传进度">
            <span :style="{ width: `${progressPercent}%` }"></span>
          </div>
          <div class="preflight-meter-meta">
            <span>分片 {{ completedPartRatio }}</span>
            <span>{{ currentUpload ? formatBytes(currentUpload.expectedSize) : '等待文件' }}</span>
          </div>

          <p class="preflight-status-copy">
            {{ statusMessage || '上传进度会自动保存，刷新页面后可以继续。' }}
          </p>

          <div class="preflight-actions">
            <button
              v-if="!isUploading"
              type="button"
              class="preflight-primary"
              :disabled="!selectedFile || Boolean(completedDraft) || currentUpload?.status === 'COMPLETED'"
              @click="beginUpload"
            >
              {{ uploadActionLabel }}
            </button>
            <button v-else type="button" class="preflight-secondary" @click="pauseUpload">
              暂停
            </button>
            <button
              v-if="currentUpload && currentUpload.status !== 'COMPLETED'"
              type="button"
              class="preflight-ghost"
              :disabled="isUploading || currentUpload.status === 'VERIFYING'"
              @click="cancelCurrentUpload"
            >
              取消上传
            </button>
          </div>
        </section>
    </div>

      <div v-if="completedDraft || currentUpload?.status === 'COMPLETED'" class="preflight-complete-card">
        <span aria-hidden="true">✓</span>
        <div>
          <strong>成片对象已完成校验</strong>
          <p>
            {{ completedDraft?.originalFileName || selectedFile?.name || '已上传成片' }} ·
            {{ formatBytes(completedDraft?.fileSize || currentUpload?.expectedSize || 0) }}
          </p>
        </div>
        <small>下一步：发布前画面与声音体检</small>
      </div>
    <p v-if="visibleError" class="preflight-error" role="alert">{{ visibleError }}</p>

    <footer class="preflight-privacy-note">
      <strong>隐私边界</strong>
      <span>原片使用私有存储；发布确认后删除可还原媒体，分析建议和成本记录继续保留。</span>
    </footer>
  </section>
</template>

<style scoped>
.preflight-studio {
  --lab-ink: #102235;
  --lab-cyan: var(--bili-blue);
  position: relative;
  overflow: hidden;
  background:
    linear-gradient(90deg, rgba(0, 174, 236, 0.045) 1px, transparent 1px),
    linear-gradient(rgba(0, 174, 236, 0.045) 1px, transparent 1px),
    var(--surface);
  background-size: 28px 28px;
}

.preflight-studio::before {
  position: absolute;
  top: 0;
  right: 0;
  width: 220px;
  height: 6px;
  content: '';
  background: linear-gradient(90deg, var(--lab-cyan), var(--bili-pink));
}

.preflight-hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s7);
  padding-bottom: var(--s6);
  border-bottom: 1px solid var(--border-strong);
}

.preflight-eyebrow {
  margin-bottom: var(--s2);
  color: var(--accent-strong);
  font-family: var(--font-code);
  font-size: 11px;
  font-weight: var(--fw-bold);
  letter-spacing: 0;
}

.preflight-hero h3 {
  margin-bottom: var(--s2);
  color: var(--lab-ink);
  font-size: 38px;
  letter-spacing: 0;
}

.preflight-hero p:not(.preflight-eyebrow) {
  max-width: 680px;
  margin-bottom: 0;
  color: var(--muted);
  line-height: 1.75;
}

.preflight-limit-plate {
  display: grid;
  min-width: 138px;
  padding: var(--s4);
  color: #fff;
  background: var(--lab-ink);
  border-radius: var(--r-sm);
  box-shadow: 8px 8px 0 rgba(0, 174, 236, 0.14);
}

.preflight-limit-plate span,
.preflight-limit-plate small {
  color: rgba(255, 255, 255, 0.68);
  font-family: var(--font-code);
  font-size: 10px;
  letter-spacing: 0;
}

.preflight-limit-plate strong {
  margin: 4px 0;
  font-family: var(--font-code);
  font-size: 28px;
}

.preflight-route {
  display: flex;
  align-items: center;
  gap: var(--s3);
  margin: var(--s5) 0;
  color: var(--muted);
  font-family: var(--font-code);
  font-size: 11px;
  letter-spacing: 0;
  text-transform: uppercase;
}

.preflight-route i {
  flex: 1;
  height: 1px;
  background: linear-gradient(90deg, var(--border-strong), var(--lab-cyan));
}

.preflight-file-card,
.preflight-meter-card {
  position: relative;
  padding: var(--s6);
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid var(--border-strong);
  border-radius: var(--r);
  box-shadow: var(--sh-sm);
}

.preflight-index {
  position: absolute;
  top: var(--s4);
  right: var(--s4);
  color: rgba(0, 174, 236, 0.28);
  font-family: var(--font-code);
  font-size: 28px;
  font-weight: var(--fw-bold);
}

.preflight-file-card h4,
.preflight-meter-card h4 {
  margin: 0 0 var(--s2);
  color: var(--lab-ink);
  font-size: 19px;
}

.preflight-card-head p,
.preflight-meter-head p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}

.preflight-version-field span {
  display: block;
  margin-bottom: var(--s2);
  color: var(--muted);
  font-size: 12px;
  font-weight: var(--fw-semibold);
}

.preflight-version-field input {
  min-width: 0;
  padding: 11px 12px;
  color: var(--ink);
  background: var(--surface-sub);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-sm);
  outline: none;
}

.preflight-version-field input:focus {
  border-color: var(--lab-cyan);
  box-shadow: 0 0 0 3px var(--accent-ring);
}

.preflight-primary,
.preflight-secondary,
.preflight-ghost {
  padding: 10px 16px;
  border-radius: var(--r-sm);
  cursor: pointer;
}

.preflight-primary {
  color: #fff;
  background: var(--lab-ink);
  border: 1px solid var(--lab-ink);
}

.preflight-work-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(320px, 0.85fr);
  gap: var(--s4);
}

.preflight-card-head,
.preflight-meter-head,
.preflight-meter-meta,
.preflight-actions,
.preflight-complete-card,
.preflight-privacy-note {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s3);
}

.preflight-state-chip {
  padding: 4px 8px;
  color: var(--accent-strong);
  background: var(--accent-tint);
  border-radius: var(--r-pill);
  font-family: var(--font-code);
  font-size: 10px;
}

.preflight-version-field {
  display: block;
  margin-top: var(--s5);
}

.preflight-version-field input {
  width: 100%;
}

.preflight-dropzone {
  display: grid;
  place-items: center;
  width: 100%;
  min-height: 160px;
  margin-top: var(--s3);
  padding: var(--s5);
  color: var(--ink);
  background:
    repeating-linear-gradient(135deg, rgba(0, 174, 236, 0.04) 0 8px, transparent 8px 16px),
    var(--surface-sub);
  border: 1px dashed rgba(0, 138, 197, 0.45);
  border-radius: var(--r-sm);
  cursor: pointer;
  transition: border-color 160ms ease, transform 160ms ease, background 160ms ease;
}

.preflight-dropzone:hover:not(:disabled) {
  background-color: rgba(0, 174, 236, 0.05);
  border-color: var(--lab-cyan);
  transform: translateY(-1px);
}

.preflight-reel {
  color: var(--lab-cyan);
  font-size: 34px;
}

.preflight-dropzone small {
  max-width: 100%;
  margin-top: 5px;
  overflow: hidden;
  color: var(--muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preflight-hidden-input {
  display: none;
}

.preflight-resume-note,
.preflight-status-copy {
  margin: var(--s3) 0 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.preflight-meter-head strong {
  color: var(--lab-ink);
  font-family: var(--font-code);
  font-size: 28px;
}

.preflight-progress-track {
  height: 10px;
  margin: var(--s7) 0 var(--s2);
  overflow: hidden;
  background: #dfe8ef;
  border-radius: 2px;
}

.preflight-progress-track span {
  display: block;
  width: 0;
  height: 100%;
  background: linear-gradient(90deg, var(--lab-cyan), var(--bili-pink));
  transition: width 240ms ease;
}

.preflight-meter-meta {
  color: var(--muted);
  font-family: var(--font-code);
  font-size: 11px;
}

.preflight-actions {
  justify-content: flex-start;
  margin-top: var(--s6);
}

.preflight-secondary {
  color: var(--lab-ink);
  background: var(--accent-tint);
  border: 1px solid rgba(0, 138, 197, 0.24);
}

.preflight-ghost {
  color: var(--danger);
  background: transparent;
  border: 1px solid rgba(220, 38, 38, 0.2);
}

button:disabled {
  cursor: not-allowed;
  opacity: 0.48;
}

.preflight-complete-card {
  margin-top: var(--s4);
  padding: var(--s4) var(--s5);
  background: linear-gradient(90deg, rgba(22, 163, 74, 0.09), rgba(0, 174, 236, 0.06));
  border: 1px solid rgba(22, 163, 74, 0.2);
  border-radius: var(--r);
}

.preflight-complete-card > span {
  display: grid;
  flex: 0 0 34px;
  place-items: center;
  width: 34px;
  height: 34px;
  color: #fff;
  background: var(--ok);
  border-radius: 50%;
}

.preflight-complete-card div {
  flex: 1;
  min-width: 0;
}

.preflight-complete-card p,
.preflight-complete-card small {
  margin: 3px 0 0;
  color: var(--muted);
  font-size: 12px;
  overflow-wrap: anywhere;
}

.preflight-error {
  margin: var(--s4) 0 0;
  padding: 11px 13px;
  color: #991b1b;
  background: rgba(220, 38, 38, 0.07);
  border-left: 3px solid var(--danger);
  overflow-wrap: anywhere;
}

.preflight-privacy-note {
  align-items: flex-start;
  justify-content: flex-start;
  margin-top: var(--s5);
  padding-top: var(--s4);
  color: var(--muted);
  border-top: 1px solid var(--border);
  font-size: 12px;
  line-height: 1.6;
}

.preflight-privacy-note strong {
  flex: 0 0 auto;
  color: var(--ink);
}

@media (max-width: 860px) {
  .preflight-work-grid {
    grid-template-columns: 1fr;
  }

  .preflight-hero {
    flex-direction: column;
  }

  .preflight-limit-plate {
    width: 100%;
  }
}

@media (max-width: 560px) {
  .preflight-hero h3 {
    font-size: 30px;
  }

  .preflight-route {
    align-items: flex-start;
    flex-direction: column;
  }

  .preflight-route i {
    width: 1px;
    height: 18px;
    margin-left: 20px;
  }

  .preflight-complete-card {
    align-items: stretch;
    grid-template-columns: 1fr;
    flex-direction: column;
  }
}
</style>
