<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import AnalysisProgress from '@/components/creator/AnalysisProgress.vue'
import { useCreatorWorkspaceShell } from '@/composables/creator/useCreatorWorkspaceContext'
import type { CreatorWorkflowMessage } from '@/types/creator'

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
  workflowMessages,
  workflowMessageDraft,
  workflowSseText,
  workflowStatusText,
  workflowProcessSummary,
  workflowRunningStep,
  workflowFailedStep,
  canSendWorkflowMessage,
  isSendingWorkflowMessage,
  sendWorkflowSupplement,
  openWorkflowProcessModal,
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
  hasFullScriptMaterial,
  canGeneratePrePublishDraft,
  isGeneratingPrePublishDraft,
  generatePrePublishManuscriptDraftForCurrentTask,
  canConfirmPrePublish,
  isConfirmingPrePublish,
  confirmPrePublishResult,
  selectedTask,
} = useCreatorWorkspaceShell()

const composerRef = ref<HTMLTextAreaElement | null>(null)
const draftRequirement = ref('')

const visibleWorkflowMessages = computed<CreatorWorkflowMessage[]>(() =>
  (workflowMessages.value ?? []).filter((message: CreatorWorkflowMessage) => message.contentType !== 'MATERIAL_SUMMARY'),
)

const assistantLeadText = computed(() => {
  if (!hasSelectedTask.value) {
    return '先确认一条创作任务，我再继续推进发布前优化。'
  }
  if (!workflowSession.value) {
    return '我正在准备发布前优化上下文，稍后会把需要你补充的内容列出来。'
  }
  if (!hasFullScriptMaterial.value) {
    return '当前还缺完整文稿或字幕。你可以继续贴素材，也可以让我先按现有创意方向补一版可编辑草稿。'
  }
  if (suggestion.value && !hasConfirmedPrePublish.value) {
    return '发布方案已生成。请先检查标题、简介和标签建议，再决定采用或继续补充修改要求。'
  }
  if (hasConfirmedPrePublish.value) {
    return '本轮发布方案已采用，后续会进入视频数据和观众反馈分析。'
  }
  return '素材已经足够。我可以开始生成发布前优化方案，并在结果里让你确认是否采用。'
})

const nextActionTitle = computed(() => {
  if (!hasFullScriptMaterial.value) {
    return '补齐文稿'
  }
  if (suggestion.value && !hasConfirmedPrePublish.value) {
    return '确认方案'
  }
  return '生成方案'
})

const nextActionText = computed(() => {
  if (!hasFullScriptMaterial.value) {
    return '优先补齐可分析的文稿或字幕，标题和简介建议才不会只基于大纲空转。'
  }
  if (suggestion.value && !hasConfirmedPrePublish.value) {
    return '确认后，本轮建议会写入任务状态；继续修改则把要求发给 AI 后重新生成。'
  }
  return 'AI 会读取当前材料、偏好记忆和你在消息流里的补充要求。'
})

function roleLabel(role: CreatorWorkflowMessage['role']) {
  switch (role) {
    case 'USER':
      return '你'
    case 'RESULT':
      return '方案'
    case 'SYSTEM':
      return '系统'
    case 'TOOL':
      return '工具'
    default:
      return 'AI'
  }
}

function focusComposer() {
  void nextTick(() => {
    composerRef.value?.focus()
  })
}

async function submitSupplement() {
  await sendWorkflowSupplement()
}

async function generateDraft() {
  await generatePrePublishManuscriptDraftForCurrentTask(draftRequirement.value)
  draftRequirement.value = ''
}
</script>

<template>
  <section class="creator-section creator-ai-prepublish-section">
    <div class="creator-section-head">
      <div>
        <h3>发布前优化</h3>
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

    <AnalysisProgress
      v-if="isAnalyzingPrePublish || isGeneratingPrePublishDraft || workflowSteps.length > 0"
      :steps="workflowSteps"
    />

    <div class="creator-ai-prepublish-layout">
      <section class="creator-ai-console-panel" aria-label="发布前优化 AI 交互台">
        <header class="creator-ai-console-head">
          <div>
            <span>{{ workflowStatusText }}</span>
            <strong>{{ selectedTask?.taskName || '当前视频任务' }}</strong>
          </div>
          <b :class="{ active: workflowSseText === '实时连接' }">{{ workflowSseText }}</b>
        </header>

        <article class="creator-ai-lead">
          <span>AI</span>
          <p>{{ assistantLeadText }}</p>
        </article>

        <div class="creator-ai-message-thread" aria-label="发布前优化对话">
          <article
            v-for="message in visibleWorkflowMessages"
            :key="message.messageId"
            class="creator-ai-message"
            :class="`role-${message.role.toLowerCase()}`"
          >
            <small>#{{ message.sequenceNo }} · {{ roleLabel(message.role) }}</small>
            <p>{{ message.content }}</p>
          </article>
          <article v-if="visibleWorkflowMessages.length === 0" class="creator-ai-message empty">
            <small>AI</small>
            <p>等待工作流消息。</p>
          </article>
        </div>

        <form class="creator-ai-composer" @submit.prevent="submitSupplement">
          <textarea
            ref="composerRef"
            v-model="workflowMessageDraft"
            maxlength="1000"
            :disabled="!canSendWorkflowMessage || isSendingWorkflowMessage"
            placeholder="补充你的完整需求、口播风格、标题禁忌或必须出现的信息"
            @keydown.ctrl.enter.prevent="submitSupplement"
          ></textarea>
          <div class="creator-ai-composer-actions">
            <button type="button" class="creator-ghost-button" @click="focusComposer">
              补充素材
            </button>
            <button
              type="submit"
              class="creator-primary-button"
              :disabled="!canSendWorkflowMessage || !workflowMessageDraft.trim() || isSendingWorkflowMessage"
            >
              {{ isSendingWorkflowMessage ? '发送中...' : '发送给 AI' }}
            </button>
          </div>
        </form>
      </section>

      <aside class="creator-ai-next-panel" aria-label="下一步">
        <header>
          <span>下一步</span>
          <strong>{{ nextActionTitle }}</strong>
        </header>
        <p>{{ nextActionText }}</p>

        <textarea
          v-if="!hasFullScriptMaterial"
          v-model="draftRequirement"
          maxlength="1000"
          placeholder="让 AI 补稿时的额外要求，例如节奏、口播语气、必须保留的观点"
        ></textarea>

        <div class="creator-ai-next-actions">
          <template v-if="!hasFullScriptMaterial">
            <button type="button" class="creator-secondary-action" @click="focusComposer">
              我来补充
            </button>
            <button
              type="button"
              class="creator-primary-button"
              :disabled="!canGeneratePrePublishDraft || isGeneratingPrePublishDraft"
              @click="generateDraft"
            >
              {{ isGeneratingPrePublishDraft ? '补稿中...' : '让 AI 补一版' }}
            </button>
          </template>
          <template v-else-if="suggestion && !hasConfirmedPrePublish">
            <button type="button" class="creator-secondary-action" @click="focusComposer">
              继续修改
            </button>
            <button
              type="button"
              class="creator-primary-button"
              :disabled="!canConfirmPrePublish || isConfirmingPrePublish"
              @click="confirmPrePublishResult"
            >
              {{ isConfirmingPrePublish ? '确认中...' : '采用这个方案' }}
            </button>
          </template>
          <template v-else>
            <button
              type="button"
              class="creator-primary-button"
              :disabled="!canRunPrePublishAnalyze || isAnalyzingPrePublish"
              @click="runPrePublishAnalyze"
            >
              {{ isAnalyzingPrePublish ? '生成中...' : '生成发布方案' }}
            </button>
          </template>
        </div>

        <button
          v-if="workflowSession"
          type="button"
          class="creator-ghost-button creator-mini-button"
          @click="openWorkflowProcessModal"
        >
          查看执行过程
        </button>
      </aside>
    </div>

    <article v-if="suggestion" class="creator-result-entry">
      <div>
        <strong>发布方案已生成</strong>
        <span>
          {{
            hasConfirmedPrePublish
              ? '本轮方案已采用，后续可以进入视频分析。'
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

    <article
      v-if="showDeveloperTools"
      class="creator-workflow-process-summary creator-ai-process-summary"
    >
      <strong>{{ workflowProcessSummary }}</strong>
      <span v-if="workflowRunningStep">当前步骤：{{ workflowRunningStep.stepName }}</span>
      <span v-else-if="workflowFailedStep">失败原因：{{ workflowFailedStep.errorMessage || '未知错误' }}</span>
      <span v-else>执行细节已收进过程弹窗。</span>
    </article>

    <details class="creator-ai-support-details">
      <summary>
        <span>偏好与语境</span>
        <strong>{{ selectedPreferenceModeLabel }}</strong>
      </summary>

      <div class="creator-ai-support-grid">
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
      </div>

      <div class="creator-form-grid creator-ai-preference-form">
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
    </details>
  </section>
</template>
