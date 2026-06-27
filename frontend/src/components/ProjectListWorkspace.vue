<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
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
  { value: 'FEEDBACK_ANALYZED', label: '已有反馈分析' },
  { value: 'COMPETITOR_ANALYZED', label: '已有参考分析' },
  { value: 'ANALYZED', label: '已完成复盘' },
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
  creatorStore.selectedTaskId = task.taskId
  router.push('/creator')
}

function createNewTask() {
  creatorStore.selectedTaskId = null
  router.push('/creator')
}

function statusLabel(status: string) {
  switch (status) {
    case 'DRAFT':
      return '草稿'
    case 'PRE_PUBLISH_ANALYZED':
      return '已有发布方案'
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
    <header class="creator-header">
      <div>
        <p class="creator-kicker">项目列表</p>
        <h2>历史视频项目</h2>
        <p>从这里继续上次的发布方案或复盘，不需要回到技术调试入口。</p>
      </div>
      <div class="creator-header-actions">
        <button type="button" class="creator-primary-button creator-mini-button" @click="createNewTask">
          开始优化一条视频
        </button>
        <button
          type="button"
          class="creator-secondary-action creator-mini-button"
          :disabled="isLoading"
          @click="loadTasks"
        >
          {{ isLoading ? '刷新中...' : '刷新列表' }}
        </button>
      </div>
    </header>

    <section class="creator-section project-list-section">
      <div class="project-list-toolbar">
        <label>
          <span>搜索项目</span>
          <input v-model="searchKeyword" type="search" placeholder="按标题、类型或项目 ID 搜索" />
        </label>
        <label>
          <span>状态</span>
          <select v-model="statusFilter">
            <option v-for="option in taskStatusOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>
      </div>

      <div class="creator-task-overview" aria-label="项目概览">
        <span><b>{{ taskStats.total }}</b> 全部</span>
        <span><b>{{ taskStats.draft }}</b> 草稿</span>
        <span><b>{{ taskStats.inProgress }}</b> 推进中</span>
        <span><b>{{ taskStats.done }}</b> 已复盘</span>
      </div>

      <div v-if="errorMessage" class="creator-alert error-alert">
        <strong>项目列表加载失败</strong>
        <span>{{ errorMessage }}</span>
      </div>

      <div v-else-if="filteredTasks.length > 0" class="project-list">
        <article v-for="task in filteredTasks" :key="task.taskId" class="project-list-item">
          <div>
            <strong>{{ task.taskName }}</strong>
            <span>{{ task.videoType || '未分类' }} · {{ statusLabel(task.status) }}</span>
            <small>{{ shortId(task.taskId) }} · 更新于 {{ formatDate(task.updateTime) }}</small>
          </div>
          <button type="button" class="creator-primary-button creator-mini-button" @click="continueTask(task)">
            继续复盘
          </button>
        </article>
      </div>

      <article v-else class="creator-empty-result">
        <strong>{{ isLoading ? '正在读取项目...' : '还没有匹配的项目' }}</strong>
        <span>可以新建一条视频项目，先把标题、简介和文稿放进去。</span>
      </article>
    </section>
  </section>
</template>

<style scoped>
.project-list-shell {
  min-height: auto;
}

.project-list-section {
  max-width: 1120px;
  margin: 0 auto;
}

.project-list-toolbar {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) minmax(180px, 260px);
  gap: var(--s3);
}

.project-list-toolbar label {
  display: grid;
  gap: var(--s2);
}

.project-list-toolbar span {
  color: var(--text);
  font-size: 13px;
  font-weight: var(--fw-semibold);
}

.project-list-toolbar input,
.project-list-toolbar select {
  width: 100%;
  min-height: 38px;
  padding: 0 var(--s3);
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  outline: none;
}

.project-list-toolbar input:focus,
.project-list-toolbar select:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 4px var(--accent-ring);
}

.project-list {
  display: grid;
  gap: var(--s2);
}

.project-list-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) max-content;
  align-items: center;
  gap: var(--s3);
  padding: var(--s3);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  box-shadow: var(--sh-sm);
}

.project-list-item div {
  display: grid;
  min-width: 0;
  gap: 5px;
}

.project-list-item strong {
  overflow-wrap: anywhere;
  color: var(--ink);
  font-size: 14px;
  line-height: 1.45;
}

.project-list-item span,
.project-list-item small {
  color: var(--muted);
  font-size: 13px;
  line-height: 1.5;
}

@media (max-width: 720px) {
  .project-list-toolbar,
  .project-list-item {
    grid-template-columns: 1fr;
  }

  .project-list-item button {
    width: 100%;
  }
}
</style>
