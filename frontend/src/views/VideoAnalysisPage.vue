<script setup lang="ts">
/**
 * 视频分析页面 — P0-3 第三阶段完整页面。
 * 路由 /video-analysis，独立于创作台。
 * 顶部展示 B 站账号绑定状态，主体展示已绑定任务的视频卡片网格。
 * P0-3 为页壳，P0-4 补全点击卡片后的完整分析报告和追问能力。
 */
import { computed, onMounted, ref } from 'vue'
import { listCreatorTasks } from '@/api/creator'
import BilibiliAccountPanel from '@/components/creator/BilibiliAccountPanel.vue'
import BvBindingPanel from '@/components/creator/BvBindingPanel.vue'
import LinkedVideoGrid from '@/components/creator/LinkedVideoGrid.vue'
import type { BilibiliAccount, BilibiliVideo, CreatorTaskSummary, TaskVideoBinding } from '@/types/creator'

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

const bindableTasks = computed(() => tasks.value.filter((task) => task.status !== 'DRAFT'))

onMounted(loadTasks)

async function loadTasks() {
  loadingTasks.value = true
  taskError.value = ''
  try {
    tasks.value = await listCreatorTasks(50)
    selectedBindTaskId.value = bindableTasks.value[0]?.taskId ?? ''
  } catch (e: any) {
    taskError.value = e?.message || '读取任务列表失败'
  } finally {
    loadingTasks.value = false
  }
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
          <p>选择已完成发布方案的任务，填入公开视频 BV；UID 直接沿用上方已绑定账号。</p>
        </div>
        <button
          type="button"
          class="creator-btn creator-btn-secondary"
          :disabled="loadingTasks"
          @click="loadTasks"
        >
          {{ loadingTasks ? '读取中...' : '刷新任务' }}
        </button>
      </header>

      <p v-if="taskError" class="video-binding-error">{{ taskError }}</p>

      <template v-else-if="bindableTasks.length > 0">
        <label class="video-binding-task-field" for="video-binding-task">
          <span>关联任务</span>
          <select id="video-binding-task" v-model="selectedBindTaskId">
            <option v-for="task in bindableTasks" :key="task.taskId" :value="task.taskId">
              {{ task.taskName || '未命名任务' }} · {{ task.status }}
            </option>
          </select>
        </label>
        <BvBindingPanel
          v-if="selectedBindTaskId"
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

    <!-- 未绑定账号提示 -->
    <div v-else class="no-account-hint">
      <p>请先在上方绑定 B 站 UID，才能查看已关联的视频。</p>
    </div>

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
  max-width: 1200px;
  margin: 0 auto;
  padding: 32px 24px 64px;
}

.video-analysis-header {
  margin-bottom: 24px;
}

.video-analysis-header h2 {
  margin: 0 0 8px;
  font-size: 24px;
  font-weight: 700;
  color: var(--creator-text, #1d1d1f);
}

.video-analysis-subtitle {
  margin: 0;
  font-size: 14px;
  color: var(--creator-muted-ink, #86868b);
  line-height: 1.5;
}

.video-binding-section {
  background: var(--creator-panel);
  border: 1px solid var(--creator-line);
  border-radius: 12px;
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
  color: var(--creator-text, #1d1d1f);
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
  border: 1px solid var(--creator-line);
  border-radius: 8px;
  background: var(--creator-surface, #fff);
  color: var(--creator-text, #1d1d1f);
  font-size: 14px;
  min-height: 38px;
  padding: 0 12px;
}

.video-binding-empty {
  padding-bottom: 16px;
}

/* 未绑定账号提示 */
.no-account-hint {
  text-align: center;
  padding: 48px 24px;
  color: var(--creator-muted-ink, #86868b);
  font-size: 14px;
}

/* 详情占位区 */
.video-detail-placeholder {
  margin-top: 24px;
  background: var(--creator-panel);
  border: 1px solid var(--creator-line);
  border-radius: 12px;
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
}
</style>
