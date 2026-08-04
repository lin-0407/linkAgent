<script setup lang="ts">
/**
 * 视频分析页面 — P0-3 第三阶段完整页面。
 * 路由 /video-analysis，独立于创作台。
 * 顶部展示 B 站账号绑定状态，主体展示已绑定任务的视频卡片网格。
 * P0-3 为页壳，P0-4 补全点击卡片后的完整分析报告和追问能力。
 */
import { onMounted, ref, watch } from 'vue'
import { ArrowRight, RefreshCw } from '@lucide/vue'
import { useRouter } from 'vue-router'
import { getPostPublishReadiness, listCreatorTasks } from '@/api/creator'
import BilibiliAccountPanel from '@/components/creator/BilibiliAccountPanel.vue'
import BvBindingPanel from '@/components/creator/BvBindingPanel.vue'
import LinkedVideoGrid from '@/components/creator/LinkedVideoGrid.vue'
import { useCreatorStore } from '@/stores/creatorStore'
import type {
  BilibiliAccount,
  BilibiliVideo,
  CreatorTaskSummary,
  PostPublishReadiness,
  TaskVideoBinding,
} from '@/types/creator'

const router = useRouter()
const creatorStore = useCreatorStore()

// 当前绑定的 B 站 UID — 账号加载成功后设置
const currentUid = ref<string | null>(null)
// 同步完成后调用视频网格公开的 refresh，不通过重建整页来刷新数据。
const videoGrid = ref<InstanceType<typeof LinkedVideoGrid> | null>(null)
// 用户选中的视频 — P0-4 展示完整分析
const selectedVideo = ref<BilibiliVideo | null>(null)
const tasks = ref<CreatorTaskSummary[]>([])
const selectedBindTaskId = ref('')
const loadingTasks = ref(false)
const taskError = ref('')
const readiness = ref<PostPublishReadiness | null>(null)
const loadingReadiness = ref(false)
const readinessError = ref('')
let readinessRequestGeneration = 0

onMounted(loadTasks)

watch(selectedBindTaskId, () => {
  void loadReadiness()
})

async function loadTasks() {
  loadingTasks.value = true
  taskError.value = ''
  try {
    tasks.value = await listCreatorTasks(50)
    // 响应到达时再读取当前选择，避免刷新期间的用户切换被旧快照覆盖。
    const currentTaskId = selectedBindTaskId.value
    const nextTaskId = tasks.value.some((task) => task.taskId === currentTaskId)
      ? currentTaskId
      : (tasks.value[0]?.taskId ?? '')
    selectedBindTaskId.value = nextTaskId
    if (nextTaskId === currentTaskId) {
      await loadReadiness()
    }
  } catch (e: any) {
    taskError.value = e?.message || '读取任务列表失败'
  } finally {
    loadingTasks.value = false
  }
}

async function loadReadiness() {
  const taskId = selectedBindTaskId.value
  const requestGeneration = ++readinessRequestGeneration
  readiness.value = null
  readinessError.value = ''
  if (!taskId) {
    loadingReadiness.value = false
    return
  }
  loadingReadiness.value = true
  try {
    const result = await getPostPublishReadiness(taskId)
    if (requestGeneration !== readinessRequestGeneration || taskId !== selectedBindTaskId.value) return
    readiness.value = result
  } catch (e: any) {
    if (requestGeneration !== readinessRequestGeneration || taskId !== selectedBindTaskId.value) return
    readinessError.value = e?.message || '检查任务状态失败'
  } finally {
    if (requestGeneration === readinessRequestGeneration) {
      loadingReadiness.value = false
    }
  }
}

/** 带上任务和目标步骤，创作台仍会按服务端状态执行自身门禁。 */
async function returnToPreflight() {
  const taskId = selectedBindTaskId.value
  if (!taskId) return
  creatorStore.setSelectedTaskId(taskId)
  creatorStore.setRestoredTaskId(taskId)
  creatorStore.setActiveInteractiveTaskId(null)
  creatorStore.setActiveStep('preflight')
  await router.push({
    name: 'creator',
    query: { taskId, step: 'preflight' },
  })
}

/** 账号就绪回调 */
function onAccountReady(account: BilibiliAccount | null) {
  currentUid.value = account?.bilibiliUid || null
}

/** 选中视频回调 */
function onSelectVideo(video: BilibiliVideo) {
  selectedVideo.value = video
}

/** 同步完成后立即刷新卡片，避免用户手动刷新浏览器。 */
function onSyncCompleted() {
  void videoGrid.value?.refresh()
}

function onVideoBound(_binding: TaskVideoBinding) {
  void videoGrid.value?.refresh()
}
</script>

<template>
  <div class="video-analysis-page">
    <header class="video-analysis-header">
      <h2>视频分析与复盘</h2>
      <p class="video-analysis-subtitle">
        已绑定任务的视频会在同步校验后出现在这里。
      </p>
    </header>

    <BilibiliAccountPanel
      @account-ready="onAccountReady"
      @sync-completed="onSyncCompleted"
    />

    <section v-if="currentUid" class="video-binding-section">
      <header class="video-binding-header">
        <div>
          <h3>发布后绑定视频</h3>
          <p>选择任务，完成成片试映后再填入公开视频 BV；UID 直接沿用上方已绑定账号。</p>
        </div>
        <button
          type="button"
          class="creator-btn creator-btn-secondary"
          :disabled="loadingTasks"
          @click="loadTasks"
        >
          <RefreshCw :size="16" :stroke-width="1.8" aria-hidden="true" />
          {{ loadingTasks ? '读取中...' : '刷新任务' }}
        </button>
      </header>

      <p v-if="taskError" class="video-binding-error">{{ taskError }}</p>

      <template v-else-if="tasks.length > 0">
        <label class="video-binding-task-field" for="video-binding-task">
          <span>关联任务</span>
          <select id="video-binding-task" v-model="selectedBindTaskId">
            <option v-for="task in tasks" :key="task.taskId" :value="task.taskId">
              {{ task.taskName || '未命名任务' }} · {{ task.status }}
            </option>
          </select>
        </label>

        <div
          v-if="selectedBindTaskId && (loadingReadiness || readinessError || !readiness?.ready)"
          class="video-readiness-state"
          aria-live="polite"
        >
          <div v-if="loadingReadiness" class="video-readiness-loading" role="status">
            <RefreshCw class="video-readiness-spinner" :size="18" :stroke-width="1.8" aria-hidden="true" />
            <span>正在检查成片试映状态...</span>
          </div>

          <div v-else-if="readinessError" class="video-readiness-content">
            <div>
              <strong>暂时无法检查任务状态</strong>
              <p role="alert">{{ readinessError }}</p>
            </div>
            <button
              type="button"
              class="creator-btn creator-btn-secondary video-readiness-action"
              @click="loadReadiness"
            >
              <RefreshCw :size="16" :stroke-width="1.8" aria-hidden="true" />
              重试检查
            </button>
          </div>

          <div v-else class="video-readiness-content">
            <div>
              <strong>完成成片试映后才能绑定 BV</strong>
              <p>{{ readiness?.blockingReason || '当前任务尚未达到发布后绑定条件。' }}</p>
            </div>
            <button
              type="button"
              class="creator-primary-button video-readiness-action"
              @click="returnToPreflight"
            >
              返回创作台完成成片试映
              <ArrowRight :size="16" :stroke-width="1.8" aria-hidden="true" />
            </button>
          </div>
        </div>

        <BvBindingPanel
          v-if="selectedBindTaskId && readiness?.ready"
          :key="selectedBindTaskId"
          :task-id="selectedBindTaskId"
          :bilibili-uid="currentUid"
          @bound="onVideoBound"
        />
      </template>

      <p v-else class="video-binding-empty">
        暂无可绑定任务，请先在创作台确认发布方案并完成发布前试映。
      </p>
    </section>

    <LinkedVideoGrid
      v-if="currentUid"
      ref="videoGrid"
      :bilibili-uid="currentUid"
      @select-video="onSelectVideo"
    />

    <!-- 选中视频的详情区 — P0-3 为占位，P0-4 补全完整分析 -->
    <div v-if="selectedVideo" class="video-detail-placeholder">
      <h3>视频详情</h3>
      <p class="video-detail-bv">BV: {{ selectedVideo.bvid }}</p>
      <p class="video-detail-task">任务: {{ selectedVideo.taskName || '未命名任务' }}</p>
      <div class="video-detail-coming">
        <p>视频分析功能即将在后续版本上线。</p>
        <p>届时将自动采集评论弹幕、生成复盘报告，并支持追问 AI。</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 视频分析页面 — P0-3 壳，后续 P0-4~P0-5 在此基础扩展 */
.video-analysis-page {
  max-width: 1280px;
  margin: 0 auto;
  padding: 22px 24px 72px;
}

.video-analysis-header {
  margin-bottom: 24px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--border);
}

.video-analysis-header h2 {
  margin: 0 0 8px;
  font-size: 24px;
  font-weight: 700;
  color: var(--ink);
}

.video-analysis-subtitle {
  margin: 0;
  font-size: 14px;
  color: var(--creator-muted-ink, #86868b);
  line-height: 1.5;
}

.video-binding-section {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  margin-bottom: 20px;
  padding: 16px 20px 4px;
}

.video-binding-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.video-binding-header h3 {
  margin: 0 0 6px;
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
}

.video-binding-header p,
.video-binding-empty,
.video-binding-error {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--creator-muted-ink, #86868b);
}

.video-binding-error {
  color: #c93400;
  margin-bottom: 12px;
}

.video-binding-task-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 14px;
}

.video-binding-task-field span {
  font-size: 13px;
  color: var(--creator-muted-ink, #86868b);
}

.video-binding-task-field select {
  border: 1px solid var(--border-strong);
  border-radius: var(--r-sm);
  background: var(--surface);
  color: var(--ink);
  font-size: 14px;
  min-height: 44px;
  padding: 0 12px;
}

.video-binding-task-field select:focus {
  border-color: var(--accent);
  outline: none;
  box-shadow: 0 0 0 3px var(--accent-ring);
}

.video-readiness-state {
  min-height: 92px;
  margin: 2px 0 16px;
  padding: 16px 0;
  border-top: 1px solid var(--border);
  border-bottom: 1px solid var(--border);
}

.video-readiness-loading,
.video-readiness-content {
  min-height: 58px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.video-readiness-loading {
  justify-content: center;
  color: var(--creator-muted-ink, #86868b);
  font-size: 13px;
}

.video-readiness-spinner {
  flex: 0 0 auto;
  animation: video-readiness-spin 0.9s linear infinite;
}

.video-readiness-content {
  justify-content: space-between;
}

.video-readiness-content > div {
  min-width: 0;
}

.video-readiness-content strong {
  display: block;
  margin-bottom: 5px;
  color: var(--ink);
  font-size: 14px;
  line-height: 1.5;
}

.video-readiness-content p {
  margin: 0;
  color: var(--creator-muted-ink, #68686f);
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.video-readiness-action {
  flex: 0 0 auto;
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

@keyframes video-readiness-spin {
  to { transform: rotate(360deg); }
}

.video-binding-header .creator-btn {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 44px;
}

.video-binding-empty {
  padding-bottom: 16px;
}

/* 详情占位区 */
.video-detail-placeholder {
  margin-top: 24px;
  background: var(--creator-panel);
  border: 1px solid var(--creator-line);
  border-radius: var(--r);
  padding: 24px;
}

.video-detail-placeholder h3 {
  margin: 0 0 12px;
  font-size: 18px;
  font-weight: 600;
  color: var(--creator-text, #1d1d1f);
}

.video-detail-bv,
.video-detail-task {
  margin: 0 0 4px;
  font-size: 14px;
  color: var(--creator-text, #1d1d1f);
}

.video-detail-coming {
  margin-top: 16px;
  padding: 16px;
  background: var(--creator-surface-sub, #f5f5f7);
  border-radius: 8px;
}

.video-detail-coming p {
  margin: 0;
  font-size: 14px;
  color: var(--creator-muted-ink, #86868b);
  line-height: 1.6;
}

.video-detail-coming p + p {
  margin-top: 4px;
}

/* 响应式 */
@media (max-width: 640px) {
  .video-analysis-page {
    padding: 20px 16px 48px;
  }

  .video-analysis-header h2 {
    font-size: 20px;
  }

  .video-binding-header {
    flex-direction: column;
  }

  .video-readiness-content {
    align-items: stretch;
    flex-direction: column;
  }

  .video-readiness-action {
    width: 100%;
    justify-content: center;
  }
}

@media (prefers-reduced-motion: reduce) {
  .video-readiness-spinner {
    animation: none;
  }
}
</style>
