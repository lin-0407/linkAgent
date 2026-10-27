<script setup lang="ts">
import { Ellipsis, FolderOpen, Pencil, Plus, Trash2 } from '@lucide/vue'
import { useCreatorWorkspaceShell } from '@/composables/creator/useCreatorWorkspaceContext'

const {
  selectedTask,
  startEditTask,
  askDeleteSelectedTask,
  statusLabel,
  formatDate,
  openTaskManager,
  startAiCreationTask,
} = useCreatorWorkspaceShell()
</script>

<template>
  <div class="creator-project-identity">
    <div class="creator-project-copy">
      <h1 class="creator-project-title" :title="selectedTask?.taskName">
        {{ selectedTask?.taskName || '新的创作' }}
      </h1>
      <div class="creator-project-meta">
        <span>{{ selectedTask?.videoType || '从一个想法开始' }}</span>
        <span v-if="selectedTask">{{ selectedTask.materials.length }} 份资料</span>
      </div>
    </div>
    <span v-if="selectedTask" class="creator-project-state">{{
      statusLabel(selectedTask.status)
    }}</span>
  </div>
  <div class="creator-project-actions">
    <button type="button" class="creator-ghost-button" @click="openTaskManager">
      <FolderOpen :size="16" aria-hidden="true" /><span>作品库</span>
    </button>
    <button
      type="button"
      class="creator-secondary-action"
      aria-label="新建创作"
      @click="startAiCreationTask"
    >
      <Plus :size="16" aria-hidden="true" /><span>新建</span>
    </button>
    <details v-if="selectedTask" class="creator-project-menu">
      <summary aria-label="更多项目操作"><Ellipsis :size="18" aria-hidden="true" /></summary>
      <div class="creator-project-edit-actions">
        <span class="studio-menu-meta">{{ formatDate(selectedTask.updateTime) }} 更新</span>
        <button
          type="button"
          :disabled="selectedTask.status !== 'DRAFT'"
          @click="startEditTask(selectedTask.taskId)"
        >
          <Pencil :size="15" aria-hidden="true" />{{
            selectedTask.status !== 'DRAFT' ? '资料只读' : '编辑资料'
          }}
        </button>
        <button type="button" class="creator-project-delete-button" @click="askDeleteSelectedTask">
          <Trash2 :size="15" aria-hidden="true" />删除作品
        </button>
      </div>
    </details>
  </div>
</template>
