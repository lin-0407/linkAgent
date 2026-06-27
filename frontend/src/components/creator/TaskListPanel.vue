<script setup lang="ts">
import { useCreatorWorkspaceShell } from '@/composables/creator/useCreatorWorkspaceContext'

const {
  selectedTask,
  isCurrentTaskExpanded,
  startEditTask,
  askDeleteSelectedTask,
  statusLabel,
  formatDate,
  materialPreview,
  openTaskManager,
  toggleCurrentTaskExpanded,
} = useCreatorWorkspaceShell()
</script>

<template>
  <div v-if="selectedTask" class="creator-panel compact-panel creator-current-task-card">
    <div class="creator-panel-title">
      <div>
        <span>当前视频</span>
        <b>{{ selectedTask.taskName }}</b>
      </div>
      <div class="creator-panel-actions">
        <button
          type="button"
          class="creator-secondary-action creator-mini-button"
          @click="startEditTask(selectedTask.taskId)"
        >
          编辑
        </button>
        <button
          type="button"
          class="creator-ghost-button creator-mini-button"
          @click="toggleCurrentTaskExpanded"
        >
          {{ isCurrentTaskExpanded ? '收起' : '展开' }}
        </button>
        <button
          type="button"
          class="creator-danger-action creator-mini-button"
          @click="askDeleteSelectedTask"
        >
          删除
        </button>
      </div>
    </div>
    <div class="creator-current-task-meta">
      <span>{{ selectedTask.videoType || '未分类' }}</span>
      <span>{{ statusLabel(selectedTask.status) }}</span>
      <span>{{ selectedTask.materials.length }} 份材料</span>
      <span>{{ formatDate(selectedTask.updateTime) }}</span>
    </div>
    <code v-if="isCurrentTaskExpanded" class="creator-task-id">{{ selectedTask.taskId }}</code>
    <div v-if="isCurrentTaskExpanded" class="creator-material-list">
      <article v-for="material in materialPreview" :key="material.id">
        <strong>{{ material.label }}</strong>
        <p>{{ material.content }}</p>
      </article>
    </div>
  </div>
  <button
    v-else
    type="button"
    class="creator-panel compact-panel creator-task-empty-panel creator-history-entry"
    aria-label="打开历史项目"
    @click="openTaskManager"
  >
    <span class="creator-history-entry-icon" aria-hidden="true">
      <svg viewBox="0 0 24 24">
        <path d="M7 3.5h7l3.5 3.5v13.5h-11v-17z" />
        <path d="M14 3.5v4h3.5" />
        <path d="M9 12h6M9 16h4" />
      </svg>
    </span>
    <span class="creator-history-entry-copy">
      <strong>历史项目</strong>
      <small>查看和管理过往项目</small>
    </span>
    <span class="creator-history-entry-arrow" aria-hidden="true">
      <svg viewBox="0 0 24 24">
        <path d="M9 5l7 7-7 7" />
      </svg>
    </span>
  </button>
</template>
