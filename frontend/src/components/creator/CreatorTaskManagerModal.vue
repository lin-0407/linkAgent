<script setup lang="ts">
import { computed } from 'vue'
import { FolderOpen, Plus, RefreshCw, Search, X } from '@lucide/vue'
import { formatDate, statusLabel } from '@/composables/creator/creatorWorkspaceUtils'
import { useModalDialog } from '@/composables/useModalDialog'
import type { CreatorTaskSummary } from '@/types/creator'

type TaskSummaryStats = {
  total: number
  draft: number
  inProgress: number
  done: number
}

type TaskStatusOption = {
  value: 'ALL' | CreatorTaskSummary['status']
  label: string
}

const {
  open,
  tasks,
  filteredTasks,
  summary,
  selectedTaskId,
  loading,
  searchQuery,
  statusFilter,
  statusOptions,
  canEditTask,
} = defineProps<{
  open: boolean
  tasks: CreatorTaskSummary[]
  filteredTasks: CreatorTaskSummary[]
  summary: TaskSummaryStats
  selectedTaskId: string
  loading: boolean
  searchQuery: string
  statusFilter: 'ALL' | CreatorTaskSummary['status']
  statusOptions: TaskStatusOption[]
  canEditTask: (task: CreatorTaskSummary) => boolean
}>()

const emit = defineEmits<{
  close: []
  create: []
  refresh: []
  select: [taskId: string]
  edit: [taskId: string]
  delete: [task: CreatorTaskSummary]
  'update:searchQuery': [searchQuery: string]
  'update:statusFilter': [statusFilter: 'ALL' | CreatorTaskSummary['status']]
}>()

const { dialogRef, handleDialogKeydown } = useModalDialog(
  computed(() => open),
  () => emit('close'),
)

function updateSearchQuery(event: Event) {
  emit('update:searchQuery', (event.target as HTMLInputElement).value)
}

function updateStatusFilter(event: Event) {
  emit(
    'update:statusFilter',
    (event.target as HTMLSelectElement).value as 'ALL' | CreatorTaskSummary['status'],
  )
}
</script>

<template>
  <Teleport to="body">
    <Transition name="creator-modal">
      <div
        v-if="open"
        class="creator-modal-backdrop creator-task-manager-backdrop"
        @click.self="emit('close')"
      >
        <section
          ref="dialogRef"
          class="creator-task-manager-modal"
          role="dialog"
          aria-modal="true"
          aria-labelledby="creator-task-manager-title"
          tabindex="-1"
          @keydown="handleDialogKeydown"
        >
          <header class="creator-result-modal-head creator-task-manager-head">
            <div>
              <h3 id="creator-task-manager-title">我的创作</h3>
              <p>{{ summary.total }} 个项目，接着上次的进度继续。</p>
            </div>
            <div class="creator-panel-actions">
              <button type="button" class="creator-primary-button" @click="emit('create')">
                <Plus :size="16" aria-hidden="true" />新建创作
              </button>
              <button
                type="button"
                class="creator-ghost-button creator-icon-button"
                :disabled="loading"
                aria-label="刷新项目"
                @click="emit('refresh')"
              >
                <RefreshCw :size="16" aria-hidden="true" />
              </button>
              <button
                type="button"
                class="creator-ghost-button creator-icon-button"
                aria-label="关闭项目列表"
                @click="emit('close')"
              >
                <X :size="18" aria-hidden="true" />
              </button>
            </div>
          </header>

          <div class="creator-task-manager-body">
            <div class="creator-task-toolbar">
              <label class="creator-task-search">
                <span class="sr-only">搜索项目</span>
                <Search :size="16" aria-hidden="true" />
                <input
                  :value="searchQuery"
                  type="search"
                  placeholder="搜索创作名称、编号或状态"
                  @input="updateSearchQuery"
                />
              </label>
              <label class="creator-task-filter">
                <span class="sr-only">项目状态</span>
                <select :value="statusFilter" @change="updateStatusFilter">
                  <option v-for="option in statusOptions" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </option>
                </select>
              </label>
            </div>

            <div class="creator-task-overview" aria-label="项目概览">
              <span
                >草稿 <b>{{ summary.draft }}</b></span
              >
              <span
                >进行中 <b>{{ summary.inProgress }}</b></span
              >
              <span
                >已复盘 <b>{{ summary.done }}</b></span
              >
              <small>{{ filteredTasks.length }} 个匹配项目</small>
            </div>

            <div class="creator-task-list creator-task-manager-list">
              <article
                v-for="task in filteredTasks"
                :key="task.taskId"
                class="creator-task-item"
                :class="{ active: task.taskId === selectedTaskId }"
              >
                <button
                  type="button"
                  class="creator-task-select"
                  @click="emit('select', task.taskId)"
                >
                  <span class="creator-task-list-symbol" aria-hidden="true"
                    ><FolderOpen :size="19" :stroke-width="1.6"
                  /></span>
                  <span class="creator-task-list-copy">
                    <strong :title="task.taskName">{{ task.taskName }}</strong>
                    <span
                      ><span>{{ statusLabel(task.status) }}</span
                      ><span>{{ task.videoType || '未分类' }}</span
                      ><time>{{ formatDate(task.updateTime) }}</time></span
                    >
                  </span>
                </button>
                <div class="creator-task-actions">
                  <button
                    type="button"
                    class="creator-ghost-button creator-mini-button"
                    @click="emit('select', task.taskId)"
                  >
                    查看
                  </button>
                  <button
                    v-if="canEditTask(task)"
                    type="button"
                    class="creator-secondary-action creator-mini-button"
                    @click="emit('edit', task.taskId)"
                  >
                    编辑
                  </button>
                  <button
                    type="button"
                    class="creator-danger-action creator-mini-button"
                    @click="emit('delete', task)"
                  >
                    删除
                  </button>
                </div>
              </article>
              <div v-if="!loading && tasks.length === 0" class="creator-task-empty-state">
                <strong>还没有视频项目</strong>
                <span>先新建一条视频资料，后续发布方案和复盘报告都会沉淀在这里。</span>
                <button
                  type="button"
                  class="creator-primary-button creator-mini-button"
                  @click="emit('create')"
                >
                  新建项目
                </button>
              </div>
              <div
                v-else-if="!loading && filteredTasks.length === 0"
                class="creator-task-empty-state"
              >
                <strong>没有匹配的项目</strong>
                <span>换一个关键词，或把状态筛选切回全部状态。</span>
              </div>
            </div>
          </div>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>
