<script setup lang="ts">
import AnalysisProgress from '@/components/creator/AnalysisProgress.vue'
import { useCreatorWorkspaceShell } from '@/composables/creator/useCreatorWorkspaceContext'

const {
  openGuidanceEditor,
  showDeveloperTools,
  hasSelectedTask,
  openWorkflowMessageModal,
  suggestion,
  openResultModal,
  hasConfirmedPrePublish,
  canRunPrePublishAnalyze,
  isAnalyzingPrePublish,
  runPrePublishAnalyze,
  workflowSteps,
  workflowSession,
  openWorkflowProcessModal,
  workflowProcessSummary,
  workflowRunningStep,
  workflowFailedStep,
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
} = useCreatorWorkspaceShell()
</script>

<template>
  <section class="creator-section">
    <div class="creator-section-head">
      <div>
        <h3>发布方案</h3>
      </div>
      <div class="creator-action-row">
        <button
          type="button"
          class="creator-secondary-action"
          @click="openGuidanceEditor('prePublish')"
        >
          调整偏好
        </button>
        <button
          v-if="showDeveloperTools"
          type="button"
          class="creator-secondary-action"
          :disabled="!hasSelectedTask"
          @click="openWorkflowMessageModal"
        >
          查看消息流
        </button>
        <button
          v-if="suggestion"
          type="button"
          class="creator-secondary-action"
          @click="openResultModal('prePublishSuggestion')"
        >
          {{ hasConfirmedPrePublish ? '查看建议' : '查看并确认' }}
        </button>
        <button
          type="button"
          class="creator-primary-button"
          :disabled="!canRunPrePublishAnalyze || isAnalyzingPrePublish"
          @click="runPrePublishAnalyze"
        >
          {{ isAnalyzingPrePublish ? '生成中...' : '生成发布方案' }}
        </button>
      </div>
    </div>

    <!-- 分析进度时间轴：分析进行中或已有步骤时展示，让用户实时看到 Agent 在做什么 -->
    <!-- 面向所有用户（不限于开发者），替代原来只有开发者能看的"执行过程"摘要 -->
    <AnalysisProgress
      v-if="isAnalyzingPrePublish || workflowSteps.length > 0"
      :steps="workflowSteps"
    />

    <div
      v-if="showDeveloperTools"
      class="creator-workflow-grid creator-workflow-grid-compact"
    >
      <section class="creator-workflow-steps" aria-label="工作流步骤回放">
        <header class="creator-workflow-head">
          <div>
            <h4>执行过程</h4>
          </div>
          <div class="creator-workflow-head-actions">
            <button
              type="button"
              class="creator-ghost-button"
              :disabled="!workflowSession"
              @click="openWorkflowProcessModal"
            >
              查看过程
            </button>
          </div>
        </header>

        <article class="creator-workflow-process-summary">
          <strong>{{ workflowProcessSummary }}</strong>
          <span v-if="workflowRunningStep">当前步骤：{{ workflowRunningStep.stepName }}</span>
          <span v-else-if="workflowFailedStep">失败原因：{{ workflowFailedStep.errorMessage || '未知错误' }}</span>
          <span v-else>过程细节、原始输出和模型开销已收进弹窗，避免干扰建议阅读。</span>
        </article>
      </section>
    </div>

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

    <div class="creator-form-grid">
      <label>
        <span>创作目标</span>
        <textarea
          v-model="prePublishForm.creatorPreference"
          maxlength="500"
          placeholder="这期最想让观众记住什么？也可以补充表达偏好"
        ></textarea>
      </label>
      <label>
        <span>风格偏好</span>
        <input
          v-model="prePublishForm.titleStyle"
          type="text"
          maxlength="100"
          placeholder="更稳重 / 更有网感 / 更像教程 / 更像故事"
        />
      </label>
      <label class="span-full">
        <span>额外要求</span>
        <textarea
          v-model="prePublishForm.extraRequirement"
          maxlength="500"
          placeholder="补充标题、简介或标签要求"
        ></textarea>
      </label>
    </div>

    <article v-if="suggestion" class="creator-result-entry">
      <div>
        <strong>发布方案已生成</strong>
        <span>
          {{
            hasConfirmedPrePublish
              ? '本轮方案已采用，可以继续导入观众反馈。'
              : '进入弹窗查看标题、简介、标签建议，并决定是否采用。'
          }}
        </span>
      </div>
      <button
        type="button"
        class="creator-primary-button"
        @click="openResultModal('prePublishSuggestion')"
      >
        {{ hasConfirmedPrePublish ? '查看建议' : '查看并确认建议' }}
      </button>
    </article>

  </section>
</template>
