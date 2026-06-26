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
  <div v-else class="creator-panel compact-panel creator-task-empty-panel">
    <div class="creator-panel-title">
      <div>
        <span>当前视频</span>
        <b>未选择</b>
      </div>
    </div>
    <p class="creator-muted">打开项目列表选择历史项目，或直接在右侧创建新视频项目。</p>
    <button type="button" class="creator-secondary-action" @click="openTaskManager">
      打开项目列表
    </button>
  </div>
</template>
