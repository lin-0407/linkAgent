<script setup lang="ts">
import { computed } from 'vue'
import PrePublishSuggestionPanel from '@/components/creator/PrePublishSuggestionPanel.vue'
import { useCreatorWorkspaceShell } from '@/composables/creator/useCreatorWorkspaceContext'

const {
  openGuidanceEditor,
  hasSelectedTask,
  openWorkflowMessageModal,
  workflowSteps,
  workflowSession,
  workflowSseText,
  workflowStatusText,
  workflowRunningStep,
  selectedPreferenceModeLabel,
  isLoadingCreatorPreferences,
  preferenceModeNote,
  preferenceModeOptions,
  prePublishForm,
  historicalPreferenceChips,
  currentVideoType,
  openContextLibrary,
  contextTermChips,
  isLoadingCreatorContextTerms,
  isActiveStepReadOnly,
} = useCreatorWorkspaceShell()

// 同一会话允许重试，状态条只显示最近一次步骤，避免历史失败遮盖当前可继续的操作。
const currentWorkflowFailedStep = computed(() => {
  const latestStep = [...(workflowSteps.value ?? [])].sort((left, right) => {
    const leftTime = left.startTime || left.createTime
    const rightTime = right.startTime || right.createTime
    return rightTime.localeCompare(leftTime)
  })[0]
  return latestStep?.status === 'FAILED' ? latestStep : null
})

const compactWorkflowStatus = computed(() => {
  if (currentWorkflowFailedStep.value) {
    return `执行未完成：${currentWorkflowFailedStep.value.stepName || '未知步骤'}`
  }
  if (workflowRunningStep.value) {
    return `正在${workflowRunningStep.value.stepName || '处理发布方案'}`
  }

  const steps = workflowSteps.value ?? []
  if (steps.length > 0) {
    const completedCount = steps.filter((step: { status: string }) => step.status === 'SUCCESS').length
    return `已完成 ${completedCount}/${steps.length} 步`
  }

  return workflowStatusText.value
})
</script>

<template>
  <section class="creator-section creator-ai-prepublish-section">
    <header class="creator-section-head creator-ai-prepublish-head">
      <div>
        <h3>发布前优化</h3>
      </div>
      <div class="creator-action-row">
        <button
          type="button"
          class="creator-secondary-action"
          :disabled="isActiveStepReadOnly"
          @click="openGuidanceEditor('prePublish')"
        >
          调整偏好
        </button>
      </div>
    </header>

    <section
      class="creator-ai-compact-status"
      :class="{ failed: currentWorkflowFailedStep }"
      role="status"
      aria-live="polite"
    >
      <span
        class="creator-ai-connection-status"
        :class="{ active: workflowSseText === '实时连接' }"
      >
        {{ workflowSseText }}
      </span>
      <p :class="{ failed: currentWorkflowFailedStep }">{{ compactWorkflowStatus }}</p>
      <button
        type="button"
        class="creator-ghost-button creator-mini-button"
        :disabled="!hasSelectedTask || !workflowSession"
        @click="openWorkflowMessageModal"
      >
        查看消息流
      </button>
    </section>

    <div class="creator-ai-prepublish-layout">
      <section class="creator-ai-preference-workspace" aria-label="偏好与语境">
        <header class="creator-ai-preference-workspace-head">
          <div>
            <span>发布偏好</span>
            <strong>偏好与语境</strong>
          </div>
          <b>{{ selectedPreferenceModeLabel }}</b>
        </header>

        <div class="creator-ai-preference-workspace-body">
          <article class="creator-preference-panel">
            <div class="creator-preference-head">
              <div>
                <span>偏好记忆</span>
                <strong>{{ selectedPreferenceModeLabel }}</strong>
              </div>
              <b>{{ isLoadingCreatorPreferences ? '读取中' : preferenceModeNote }}</b>
            </div>

            <div class="creator-preference-modes" role="group" aria-label="偏好使用方式">
              <button
                v-for="option in preferenceModeOptions"
                :key="option.value"
                type="button"
                :class="{ active: prePublishForm.preferenceMode === option.value }"
                :disabled="isActiveStepReadOnly"
                @click="prePublishForm.preferenceMode = option.value"
              >
                <span>{{ option.label }}</span>
                <small>{{ option.description }}</small>
              </button>
            </div>

            <div class="creator-preference-tags">
              <span
                v-for="chip in historicalPreferenceChips"
                :key="`${chip.sourceTaskId}-${chip.text}`"
                :class="{ muted: prePublishForm.preferenceMode === 'IGNORE_HISTORY' }"
                :title="`来源任务：${chip.sourceTaskId}`"
              >
                {{ chip.text }}
              </span>
              <em v-if="!isLoadingCreatorPreferences && historicalPreferenceChips.length === 0">
                暂无历史偏好
              </em>
            </div>
          </article>

          <article class="creator-preference-panel">
            <div class="creator-preference-head">
              <div>
                <span>类型语境库</span>
                <strong>{{ currentVideoType === 'GLOBAL' ? '全局通用' : currentVideoType }}</strong>
              </div>
              <button
                type="button"
                class="creator-secondary-action creator-mini-button"
                :disabled="isActiveStepReadOnly"
                @click="openContextLibrary"
              >
                管理语境
              </button>
            </div>

            <div class="creator-preference-tags">
              <span v-for="chip in contextTermChips" :key="chip.id" :title="chip.title">
                {{ chip.label }} · {{ chip.text }}
              </span>
              <em v-if="!isLoadingCreatorContextTerms && contextTermChips.length === 0">
                当前类型暂无语境词
              </em>
              <em v-else-if="isLoadingCreatorContextTerms">读取中</em>
            </div>
          </article>

          <div class="creator-form-grid creator-ai-preference-form">
            <label>
              <span>创作目标</span>
              <textarea
                v-model="prePublishForm.creatorPreference"
                maxlength="500"
                :disabled="isActiveStepReadOnly"
                placeholder="这期最想让观众记住什么？也可以补充表达偏好"
              ></textarea>
            </label>
            <label>
              <span>风格偏好</span>
              <input
                v-model="prePublishForm.titleStyle"
                type="text"
                maxlength="100"
                :disabled="isActiveStepReadOnly"
                placeholder="更稳重 / 更有网感 / 更像教程 / 更像故事"
              />
            </label>
            <label class="span-full">
              <span>额外要求</span>
              <textarea
                v-model="prePublishForm.extraRequirement"
                maxlength="500"
                :disabled="isActiveStepReadOnly"
                placeholder="补充标题、简介或标签要求"
              ></textarea>
            </label>
          </div>
        </div>
      </section>

      <section class="creator-ai-result-panel" aria-label="发布方案">
        <PrePublishSuggestionPanel />
      </section>
    </div>
  </section>
</template>

<style scoped>
.creator-ai-prepublish-section {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
  gap: 16px;
  min-width: 0;
}

.creator-ai-compact-status {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  min-width: 0;
  padding: 10px 12px;
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: 8px;
}

.creator-ai-compact-status p {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  color: var(--text);
  font-size: 13px;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.creator-ai-compact-status.failed {
  background: rgba(220, 38, 38, 0.04);
  border-color: rgba(220, 38, 38, 0.24);
}

.creator-ai-compact-status p.failed {
  color: var(--danger);
}

.creator-ai-connection-status {
  display: inline-flex;
  align-items: center;
  min-height: 26px;
  padding: 0 8px;
  color: var(--muted);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  font-size: 12px;
  font-weight: var(--fw-semibold);
  white-space: nowrap;
}

.creator-ai-connection-status.active {
  color: #087a3d;
  background: rgba(34, 197, 94, 0.12);
  border-color: rgba(34, 197, 94, 0.24);
}

.creator-ai-prepublish-layout {
  display: grid;
  grid-template-columns: minmax(300px, 360px) minmax(0, 1fr);
  gap: 16px;
  min-width: 0;
  min-height: 0;
}

.creator-ai-preference-workspace,
.creator-ai-result-panel {
  min-width: 0;
  min-height: 0;
}

.creator-ai-preference-workspace {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 8px;
  box-shadow: var(--sh-sm);
  container-type: inline-size;
}

.creator-ai-preference-workspace-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  min-height: 72px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--border);
}

.creator-ai-preference-workspace-head > div {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.creator-ai-preference-workspace-head span {
  color: var(--muted);
  font-size: 12px;
  font-weight: var(--fw-semibold);
}

.creator-ai-preference-workspace-head strong {
  color: var(--ink);
  font-size: 17px;
}

.creator-ai-preference-workspace-head b {
  flex: 0 0 auto;
  padding: 4px 8px;
  color: var(--accent-strong);
  background: var(--accent-tint);
  border: 1px solid var(--accent-ring);
  border-radius: var(--r-pill);
  font-size: 12px;
}

.creator-ai-preference-workspace-body {
  min-height: 0;
  padding: 16px;
  overflow: auto;
}

.creator-ai-preference-workspace-body .creator-preference-panel:last-of-type {
  margin-bottom: 16px;
}

.creator-ai-preference-form {
  grid-template-columns: minmax(0, 1fr);
  gap: 14px;
}

.creator-ai-preference-form .span-full {
  grid-column: auto;
}

.creator-ai-result-panel {
  display: flex;
  overflow: hidden;
}

.creator-ai-result-panel > :deep(.pre-publish-suggestion-panel) {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
}

/* 宽屏把左右工具面板锁定在可视工作区，消息或方案变长只影响各自的内部滚动。 */
@media (min-width: 1280px) and (min-height: 760px) {
  .creator-ai-prepublish-section {
    height: calc(100dvh - var(--surface-topbar-height));
    overflow: hidden;
  }

  .creator-ai-prepublish-layout {
    height: 100%;
    overflow: hidden;
  }
}

@container (max-width: 480px) {
  .creator-preference-modes {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .creator-preference-modes button:last-child {
    grid-column: 1 / -1;
  }

  .creator-preference-tags span {
    align-items: flex-start;
    max-width: 100%;
    min-height: 0;
    padding-block: 6px;
    line-height: 1.45;
    overflow-wrap: anywhere;
    white-space: normal;
  }
}

/* 宽度不足时才收为单列，确保发布方案在中等屏幕仍有足够的阅读宽度。 */
@media (max-width: 1279px) {
  .creator-ai-prepublish-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .creator-ai-preference-workspace,
  .creator-ai-result-panel {
    min-height: auto;
    overflow: visible;
  }

  .creator-ai-preference-workspace-body {
    overflow: visible;
  }
}

/* 低高度桌面保留双栏，把超出的内容交给页面滚动，避免缩放后工作区突然切换布局。 */
@media (min-width: 1280px) and (max-height: 759px) {
  .creator-ai-preference-workspace,
  .creator-ai-result-panel {
    min-height: auto;
    overflow: visible;
  }

  .creator-ai-preference-workspace-body {
    overflow: visible;
  }
}

@media (max-width: 640px) {
  .creator-ai-compact-status {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .creator-ai-compact-status p {
    grid-column: 1 / -1;
    overflow: visible;
    text-overflow: clip;
    white-space: normal;
  }

  .creator-ai-preference-workspace-head {
    align-items: center;
  }
}
</style>
