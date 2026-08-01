<script setup lang="ts">
import { formatDate, shortId, statusLabel } from '@/composables/creator/creatorWorkspaceUtils'
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

function updateSearchQuery(event: Event) {
  emit('update:searchQuery', (event.target as HTMLInputElement).value)
}

function updateStatusFilter(event: Event) {
  emit('update:statusFilter', (event.target as HTMLSelectElement).value as 'ALL' | CreatorTaskSummary['status'])
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
          class="creator-task-manager-modal"
          role="dialog"
          aria-modal="true"
          aria-labelledby="creator-task-manager-title"
        >
          <header class="creator-result-modal-head creator-task-manager-head">
            <div>
              <span>项目列表</span>
              <h3 id="creator-task-manager-title">
                历史视频项目
              </h3>
              <p>{{ filteredTasks.length }} 个匹配项目，共 {{ summary.total }} 个项目</p>
            </div>
            <div class="creator-panel-actions">
              <button type="button" class="creator-ghost-button" @click="emit('create')">
                新建任务
              </button>
              <button type="button" class="creator-ghost-button" @click="emit('refresh')">
                {{ loading ? '读取中' : '刷新' }}
              </button>
              <button type="button" class="creator-ghost-button" @click="emit('close')">
                关闭
              </button>
            </div>
          </header>

          <div class="creator-task-manager-body">
            <div class="creator-task-toolbar">
              <label class="creator-task-search">
                <span>搜索</span>
                <input
                  :value="searchQuery"
                  type="search"
                  placeholder="名称 / 项目编号 / 状态"
                  @input="updateSearchQuery"
                />
              </label>
              <label class="creator-task-filter">
                <span>状态</span>
                <select :value="statusFilter" @change="updateStatusFilter">
                  <option
                    v-for="option in statusOptions"
                    :key="option.value"
                    :value="option.value"
                  >
                    {{ option.label }}
                  </option>
                </select>
              </label>
            </div>

            <div class="creator-task-overview" aria-label="项目概览">
              <span><b>{{ summary.draft }}</b> 草稿</span>
              <span><b>{{ summary.inProgress }}</b> 推进中</span>
              <span><b>{{ summary.done }}</b> 已复盘</span>
            </div>

            <div class="creator-task-list creator-task-manager-list">
              <article
                v-for="task in filteredTasks"
                :key="task.taskId"
                class="creator-task-item"
                :class="{ active: task.taskId === selectedTaskId }"
              >
                <button type="button" class="creator-task-select" @click="emit('select', task.taskId)">
                  <strong>{{ task.taskName }}</strong>
                  <span>{{ task.videoType }} · {{ statusLabel(task.status) }} · {{ task.materialCount }} 份材料</span>
                  <small>{{ shortId(task.taskId) }} · {{ formatDate(task.updateTime) }}</small>
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
                <button type="button" class="creator-primary-button creator-mini-button" @click="emit('create')">
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
