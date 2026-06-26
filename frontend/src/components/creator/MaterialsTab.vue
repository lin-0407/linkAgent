<script setup lang="ts">
import { useCreatorWorkspaceShell } from '@/composables/creator/useCreatorWorkspaceContext'

const {
  taskFormTitle,
  taskManageMode,
  isUpdatingTask,
  cancelEditTask,
  hasTaskMaterialInput,
  isCreatingTask,
  submitTask,
  taskSubmitLabel,
  taskFormHint,
  taskForm,
  videoTypeOptions,
} = useCreatorWorkspaceShell()
</script>

<template>
  <section class="creator-section">
    <div class="creator-section-head">
      <div>
        <h3>{{ taskFormTitle }}</h3>
      </div>
      <div class="creator-action-row">
        <button
          v-if="taskManageMode === 'edit'"
          type="button"
          class="creator-secondary-action"
          :disabled="isUpdatingTask"
          @click="cancelEditTask"
        >
          取消编辑
        </button>
        <button
          type="button"
          class="creator-primary-button"
          :disabled="!hasTaskMaterialInput || isCreatingTask || isUpdatingTask"
          @click="submitTask"
        >
          {{ taskSubmitLabel }}
        </button>
      </div>
    </div>

    <p class="creator-inline-note">{{ taskFormHint }}</p>

    <div class="creator-form-grid">
      <label>
        <span>视频主题</span>
        <input
          v-model="taskForm.taskName"
          type="text"
          maxlength="128"
          placeholder="例如：第一次做个人知识库踩了哪些坑"
        />
      </label>
      <label>
        <span>内容类型</span>
        <select v-model="taskForm.videoType">
          <option v-for="option in videoTypeOptions" :key="option" :value="option">
            {{ option === 'GLOBAL' ? '全局通用' : option }}
          </option>
        </select>
      </label>
      <label>
        <span>现在想到的标题</span>
        <input
          v-model="taskForm.titleDraft"
          type="text"
          maxlength="200"
          placeholder="先写一个粗标题，后面再优化"
        />
      </label>
      <label>
        <span>准备发到简介里的内容</span>
        <textarea
          v-model="taskForm.descriptionDraft"
          maxlength="2000"
          placeholder="可以先粘贴简介初稿、链接说明或补充信息"
        ></textarea>
      </label>
      <label class="span-full">
        <span>视频主要内容</span>
        <textarea
          v-model="taskForm.manuscript"
          maxlength="20000"
          placeholder="粘贴脚本、口播稿或整理后的文稿"
        ></textarea>
      </label>
      <label class="span-full">
        <span>字幕 / 补充材料</span>
        <textarea
          v-model="taskForm.subtitle"
          maxlength="20000"
          placeholder="可选：粘贴字幕文本"
        ></textarea>
      </label>
    </div>

  </section>
</template>
