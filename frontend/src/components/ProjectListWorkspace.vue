<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowRight, Plus, RefreshCw } from '@lucide/vue'
import { useRouter } from 'vue-router'
import { listCreatorTasks } from '@/api/creator'
import type { CreatorTaskSummary } from '@/types/creator'
import { useCreatorStore } from '@/stores/creatorStore'

const router = useRouter()
const creatorStore = useCreatorStore()

const taskStatusOptions: Array<{
  value: 'ALL' | CreatorTaskSummary['status']
  label: string
}> = [
  { value: 'ALL', label: '全部项目' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'PRE_PUBLISH_ANALYZED', label: '已有发布方案' },
  { value: 'FEEDBACK_COLLECTING', label: '反馈待分析' },
  { value: 'FEEDBACK_ANALYZED', label: '已有反馈分析' },
  { value: 'COMPETITOR_ANALYZED', label: '已有参考分析' },
  { value: 'ANALYZED', label: '已完成总体复盘' },
]

const tasks = ref<CreatorTaskSummary[]>([])
const searchKeyword = ref('')
const statusFilter = ref<'ALL' | CreatorTaskSummary['status']>('ALL')
const isLoading = ref(false)
const errorMessage = ref('')

const filteredTasks = computed(() => {
  const keyword = searchKeyword.value.trim().toLowerCase()
  return tasks.value.filter((task) => {
    const matchStatus = statusFilter.value === 'ALL' || task.status === statusFilter.value
    if (!matchStatus) {
      return false
    }
    if (!keyword) {
      return true
    }
    return [task.taskName, task.videoType, task.taskId, statusLabel(task.status)]
      .join(' ')
      .toLowerCase()
      .includes(keyword)
  })
})

const taskStats = computed(() => {
  const stats = {
    total: tasks.value.length,
    draft: 0,
    inProgress: 0,
    done: 0,
  }
  for (const task of tasks.value) {
    if (task.status === 'DRAFT') {
      stats.draft += 1
      continue
    }
    if (task.status === 'ANALYZED') {
      stats.done += 1
      continue
    }
    stats.inProgress += 1
  }
  return stats
})

const statCards = computed(() => [
  { key: 'total', label: '全部项目', value: taskStats.value.total, tone: 'total' },
  { key: 'draft', label: '草稿', value: taskStats.value.draft, tone: 'draft' },
  { key: 'inProgress', label: '推进中', value: taskStats.value.inProgress, tone: 'progress' },
  { key: 'done', label: '已复盘', value: taskStats.value.done, tone: 'done' },
])

onMounted(() => {
  void loadTasks()
})

async function loadTasks() {
  isLoading.value = true
  errorMessage.value = ''
  try {
    tasks.value = await listCreatorTasks(50)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : String(error)
  } finally {
    isLoading.value = false
  }
}

// 通过 Pinia creatorStore 持久化选中的任务 ID，替代原来的 localStorage 直写通道。
// Pinia persist 插件自动同步到 localStorage，CreatorWorkspace 从同一 store 读取恢复。
function continueTask(task: CreatorTaskSummary) {
  creatorStore.setActiveInteractiveTaskId(null)
  creatorStore.selectedTaskId = task.taskId
  router.push('/creator')
}

function createNewTask() {
  creatorStore.selectedTaskId = null
  creatorStore.setActiveInteractiveTaskId(null)
  creatorStore.clearNewInteractiveTaskDraft()
  router.push('/creator')
}

function statusLabel(status: string) {
  switch (status) {
    case 'DRAFT':
      return '草稿'
    case 'PRE_PUBLISH_ANALYZED':
      return '已有发布方案'
    case 'FEEDBACK_COLLECTING':
      return '反馈待分析'
    case 'FEEDBACK_ANALYZED':
      return '已有反馈分析'
    case 'COMPETITOR_ANALYZED':
      return '已有参考分析'
    case 'ANALYZED':
      return '已完成复盘'
    default:
      return status || '未知状态'
  }
}

function statusTone(status: string) {
  switch (status) {
    case 'DRAFT':
      return 'is-draft'
    case 'ANALYZED':
      return 'is-done'
    default:
      return 'is-progress'
  }
}

function shortId(value: string) {
  return value.length <= 12 ? value : `${value.slice(0, 6)}...${value.slice(-4)}`
}

function formatDate(value: string) {
  if (!value) {
    return '未知时间'
  }
  return value.replace('T', ' ').slice(0, 16)
}
</script>

<template>
  <section class="creator-shell project-list-shell">
    <div class="project-list-page">
      <header class="creator-header project-list-header">
        <div>
          <p class="creator-kicker">项目列表</p>
          <h2>历史视频项目</h2>
          <p>从这里继续上次的发布方案或复盘，不需要回到技术调试入口。</p>
        </div>
        <div class="creator-header-actions project-list-actions">
          <button type="button" class="creator-primary-button creator-mini-button" @click="createNewTask">
            <Plus :size="16" :stroke-width="1.8" aria-hidden="true" />
            开始优化一条视频
          </button>
          <button
            type="button"
            class="creator-secondary-action creator-mini-button"
            :disabled="isLoading"
            @click="loadTasks"
          >
            <RefreshCw :size="16" :stroke-width="1.8" aria-hidden="true" />
            {{ isLoading ? '刷新中...' : '刷新列表' }}
          </button>
        </div>
      </header>

      <section class="creator-section project-list-section" :aria-busy="isLoading">
        <div class="project-list-controls">
          <div class="project-list-toolbar">
            <label class="project-filter-field">
              <span>搜索项目</span>
              <input v-model="searchKeyword" type="search" placeholder="按标题、类型或项目 ID 搜索" />
            </label>
            <label class="project-filter-field">
              <span>状态</span>
              <select v-model="statusFilter">
                <option v-for="option in taskStatusOptions" :key="option.value" :value="option.value">
                  {{ option.label }}
                </option>
              </select>
            </label>
          </div>

          <div class="project-list-summary" aria-label="项目概览">
            <article
              v-for="stat in statCards"
              :key="stat.key"
              class="project-stat-card"
              :class="`tone-${stat.tone}`"
            >
              <b>{{ stat.value }}</b>
              <span>{{ stat.label }}</span>
            </article>
          </div>
        </div>

        <div v-if="errorMessage" class="creator-alert error-alert project-message project-message-error">
          <div>
            <strong>项目列表加载失败</strong>
            <span>当前请求没有成功返回，确认服务恢复后可以重新刷新列表。</span>
          </div>
          <code>{{ errorMessage }}</code>
        </div>

        <div v-else-if="filteredTasks.length > 0" class="project-list">
          <article v-for="task in filteredTasks" :key="task.taskId" class="project-list-item">
            <div class="project-list-item-main">
              <div class="project-list-item-head">
                <strong>{{ task.taskName }}</strong>
                <span class="project-status-badge" :class="statusTone(task.status)">
                  {{ statusLabel(task.status) }}
                </span>
              </div>
              <div class="project-list-item-meta">
                <span>{{ task.videoType || '未分类' }}</span>
                <span>{{ task.materialCount }} 份素材</span>
                <span>{{ shortId(task.taskId) }}</span>
                <span>更新于 {{ formatDate(task.updateTime) }}</span>
              </div>
            </div>
            <button type="button" class="creator-primary-button creator-mini-button" @click="continueTask(task)">
              继续复盘
              <ArrowRight :size="16" :stroke-width="1.8" aria-hidden="true" />
            </button>
          </article>
        </div>

        <article v-else class="creator-empty-result project-empty-result">
          <strong>{{ isLoading ? '正在读取项目...' : '还没有匹配的项目' }}</strong>
          <span>可以调整搜索条件，或新建一条视频项目，把标题、简介和文稿先放进去。</span>
          <div class="project-empty-actions">
            <button type="button" class="creator-primary-button creator-mini-button" @click="createNewTask">
              <Plus :size="16" :stroke-width="1.8" aria-hidden="true" />
              新建视频项目
            </button>
            <button
              type="button"
              class="creator-secondary-action creator-mini-button"
              :disabled="isLoading"
              @click="loadTasks"
            >
              <RefreshCw :size="16" :stroke-width="1.8" aria-hidden="true" />
              重新读取
            </button>
          </div>
        </article>
      </section>
    </div>
  </section>
</template>

<style scoped>
.project-list-shell {
  min-height: calc(100vh - var(--surface-topbar-height));
  padding-bottom: 96px;
}

.project-list-page {
  display: grid;
  width: min(1440px, calc(100vw - 48px));
  gap: var(--s4);
  margin: 0 auto;
}

.project-list-header,
.project-list-section {
  width: 100%;
  max-width: none;
  margin: 0;
}

.project-list-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  align-items: center;
}

.project-list-section {
  display: grid;
  align-content: start;
  gap: var(--s4);
  padding: var(--s4);
  overflow: hidden;
}

.project-list-controls {
  display: grid;
  gap: var(--s3);
  padding-bottom: var(--s4);
  border-bottom: 1px solid rgba(23, 32, 51, 0.08);
}

.project-list-toolbar {
  display: grid;
  grid-template-columns: minmax(360px, 1fr) minmax(200px, 280px);
  align-items: end;
  gap: var(--s3);
}

.project-filter-field {
  display: grid;
  min-width: 0;
  gap: var(--s2);
}

.project-filter-field span {
  color: var(--text);
  font-size: 13px;
  font-weight: var(--fw-semibold);
}

.project-filter-field input,
.project-filter-field select {
  width: 100%;
  min-height: 42px;
  padding: 0 var(--s3);
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  outline: none;
}

.project-filter-field input:focus,
.project-filter-field select:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 4px var(--accent-ring);
}

.project-list-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s3);
}

.project-stat-card {
  position: relative;
  display: grid;
  min-height: 74px;
  align-content: center;
  gap: 3px;
  padding: 12px 14px 12px 16px;
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  box-shadow: none;
}

.project-stat-card::before {
  position: absolute;
  top: 12px;
  bottom: 12px;
  left: 0;
  width: 3px;
  background: var(--accent);
  border-radius: 0 3px 3px 0;
  content: '';
}

.project-stat-card.tone-draft::before {
  background: var(--warn);
}

.project-stat-card.tone-progress::before {
  background: var(--accent);
}

.project-stat-card.tone-done::before {
  background: var(--ok);
}

.project-stat-card b {
  color: var(--ink);
  font-size: 26px;
  font-weight: var(--fw-bold);
  line-height: 1;
}

.project-stat-card span {
  color: var(--muted);
  font-size: 13px;
  line-height: 1.35;
}

.project-list {
  display: grid;
  gap: 0;
  border-top: 1px solid var(--border);
}

.project-list-item {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) max-content;
  align-items: center;
  min-height: 76px;
  gap: var(--s4);
  padding: var(--s3) 0 var(--s3) var(--s4);
  background: transparent;
  border-bottom: 1px solid var(--border);
  transition:
    background-color 180ms ease,
    box-shadow 180ms ease;
}

.project-list-item::before {
  position: absolute;
  top: 16px;
  bottom: 16px;
  left: 0;
  width: 3px;
  background: rgba(23, 32, 51, 0.12);
  border-radius: 3px;
  content: '';
}

.project-list-item:last-child {
  border-bottom: 0;
}

.project-list-item:hover {
  background: #f4fafc;
  box-shadow: none;
}

.project-list-item:hover::before {
  background: var(--accent);
}

.project-list-item-main {
  display: grid;
  min-width: 0;
  gap: 8px;
}

.project-list-item-head {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s2);
}

.project-list-item strong {
  overflow-wrap: anywhere;
  color: var(--ink);
  font-size: 14px;
  line-height: 1.45;
}

.project-status-badge {
  flex: none;
  min-height: 24px;
  padding: 3px 8px;
  color: var(--accent-strong);
  background: var(--accent-tint);
  border: 1px solid rgba(0, 174, 236, 0.18);
  border-radius: var(--r-pill);
  font-size: 12px;
  font-weight: var(--fw-semibold);
  line-height: 1.3;
}

.project-status-badge.is-draft {
  color: var(--warn);
  background: rgba(217, 119, 6, 0.08);
  border-color: rgba(217, 119, 6, 0.2);
}

.project-status-badge.is-done {
  color: var(--ok);
  background: rgba(22, 163, 74, 0.08);
  border-color: rgba(22, 163, 74, 0.2);
}

.project-list-item-meta {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  gap: 6px 10px;
}

.project-list-item-meta span {
  color: var(--muted);
  font-size: 13px;
  line-height: 1.5;
}

.project-list-item-meta span:not(:last-child)::after {
  margin-left: 10px;
  color: rgba(104, 117, 136, 0.55);
  content: '/';
}

.project-message {
  align-items: start;
  grid-template-columns: minmax(0, 1fr);
  margin: 0;
}

.project-message div {
  display: grid;
  gap: 4px;
}

.project-message code {
  width: fit-content;
  max-width: 100%;
  padding: 4px 8px;
  overflow-wrap: anywhere;
  color: var(--danger);
  background: rgba(220, 38, 38, 0.07);
  border: 1px solid rgba(220, 38, 38, 0.12);
  border-radius: var(--r-sm);
  font-family: var(--font-code);
  font-size: 12px;
}

.project-empty-result {
  align-items: center;
  min-height: 220px;
  text-align: center;
}

.project-empty-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--s2);
  margin-top: var(--s2);
}

@media (max-width: 1180px) {
  .project-list-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 820px) {
  .project-list-shell {
    padding-bottom: 80px;
  }

  .project-list-page {
    width: min(100%, calc(100vw - 24px));
  }

  .project-list-header {
    grid-template-columns: 1fr;
  }

  .project-list-actions {
    justify-content: flex-start;
  }
}

@media (max-width: 720px) {
  .project-list-toolbar,
  .project-list-item {
    grid-template-columns: 1fr;
  }

  .project-list-toolbar {
    gap: var(--s4);
  }

  .project-list-item button {
    width: 100%;
  }
}

@media (max-width: 480px) {
  .project-list-page {
    width: 100%;
  }

  .project-list-section {
    padding: var(--s3);
  }

  .project-list-actions,
  .project-empty-actions {
    display: grid;
    grid-template-columns: 1fr;
    width: 100%;
  }

  .project-list-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
