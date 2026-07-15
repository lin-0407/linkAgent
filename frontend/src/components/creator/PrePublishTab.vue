<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import PrePublishSuggestionPanel from '@/components/creator/PrePublishSuggestionPanel.vue'
import { useCreatorWorkspaceShell } from '@/composables/creator/useCreatorWorkspaceContext'
import type { CreatorWorkflowMessage } from '@/types/creator'

const {
  openGuidanceEditor,
  showDeveloperTools,
  hasSelectedTask,
  openWorkflowMessageModal,
  suggestion,
  hasConfirmedPrePublish,
  workflowSteps,
  workflowSession,
  workflowMessages,
  workflowMessageDraft,
  workflowSseText,
  workflowStatusText,
  workflowRunningStep,
  isAnalyzingPrePublish,
  isConfirmingPrePublish,
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
  selectedTask,
  isActiveStepReadOnly,
} = useCreatorWorkspaceShell()

type WorkspacePanel = 'collaboration' | 'result'

const messageThreadRef = ref<HTMLDivElement | null>(null)
const activeWorkspacePanel = ref<WorkspacePanel>('collaboration')
const isTabbedWorkspace = ref(false)
const mobileFlowCardHeight = ref(0)
let tabbedWorkspaceQuery: MediaQueryList | null = null
let mobileFlowCardResizeObserver: ResizeObserver | null = null

const visibleWorkflowMessages = computed<CreatorWorkflowMessage[]>(() =>
  (workflowMessages.value ?? []).filter(
    (message: CreatorWorkflowMessage) => message.contentType !== 'MATERIAL_SUMMARY',
  ),
)

// 同一会话允许重试，历史失败步骤会保留在执行过程里；首屏只反映最新一步的状态。
const currentWorkflowFailedStep = computed(() => {
  const latestStep = [...(workflowSteps.value ?? [])].sort((left, right) => {
    const leftTime = left.startTime || left.createTime
    const rightTime = right.startTime || right.createTime
    return rightTime.localeCompare(leftTime)
  })[0]
  return latestStep?.status === 'FAILED' ? latestStep : null
})

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

async function scrollMessagesToBottom() {
  await nextTick()
  const thread = messageThreadRef.value
  if (thread) {
    thread.scrollTop = thread.scrollHeight
  }
}

function syncWorkspaceBreakpoint() {
  isTabbedWorkspace.value = Boolean(tabbedWorkspaceQuery?.matches)
  if (isTabbedWorkspace.value && suggestion.value && !hasConfirmedPrePublish.value) {
    activeWorkspacePanel.value = 'result'
  }
}

function selectWorkspacePanel(panel: WorkspacePanel) {
  activeWorkspacePanel.value = panel
  if (panel === 'collaboration') {
    void scrollMessagesToBottom()
  }
}

function handleWorkspaceTabKeydown(event: KeyboardEvent, currentPanel: WorkspacePanel) {
  let nextPanel: WorkspacePanel | null = null
  if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
    nextPanel = currentPanel === 'collaboration' ? 'result' : 'collaboration'
  } else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
    nextPanel = currentPanel === 'collaboration' ? 'result' : 'collaboration'
  } else if (event.key === 'Home') {
    nextPanel = 'collaboration'
  } else if (event.key === 'End') {
    nextPanel = 'result'
  }

  if (!nextPanel) {
    return
  }

  event.preventDefault()
  selectWorkspacePanel(nextPanel)
  void nextTick(() => {
    document.getElementById(`prepublish-${nextPanel}-tab`)?.focus()
  })
}

async function observeMobileFlowCardHeight() {
  await nextTick()
  const flowCard = document.querySelector<HTMLElement>('.creator-workbench-shell .creator-flow-card')
  if (!flowCard) {
    return
  }

  const syncHeight = () => {
    mobileFlowCardHeight.value = Math.ceil(flowCard.getBoundingClientRect().height)
  }
  syncHeight()
  if (typeof ResizeObserver !== 'undefined') {
    mobileFlowCardResizeObserver = new ResizeObserver(syncHeight)
    mobileFlowCardResizeObserver.observe(flowCard)
  }
}

async function submitSupplement() {
  if (isActiveStepReadOnly.value || isAnalyzingPrePublish.value || isConfirmingPrePublish.value) {
    return
  }
  await sendWorkflowSupplement()
}

onMounted(() => {
  tabbedWorkspaceQuery = window.matchMedia('(max-width: 1279px)')
  tabbedWorkspaceQuery.addEventListener('change', syncWorkspaceBreakpoint)
  syncWorkspaceBreakpoint()
  void scrollMessagesToBottom()
  // 流程栏在移动端会吸顶且高度随文案换行变化，读取实际高度才能让面板标签始终排在它下方。
  void observeMobileFlowCardHeight()
})

onBeforeUnmount(() => {
  tabbedWorkspaceQuery?.removeEventListener('change', syncWorkspaceBreakpoint)
  mobileFlowCardResizeObserver?.disconnect()
})

// 新消息抵达后只滚动本工作区，避免继续依赖已经独立出来的消息流弹窗。
watch(
  () => visibleWorkflowMessages.value.length,
  () => {
    void scrollMessagesToBottom()
  },
)

// 中等宽度下生成结果后切到方案面板，让主要产物无需再次寻找入口。
watch(
  () => suggestion.value,
  (nextSuggestion, previousSuggestion) => {
    if (
      nextSuggestion &&
      nextSuggestion !== previousSuggestion &&
      !hasConfirmedPrePublish.value &&
      tabbedWorkspaceQuery?.matches
    ) {
      activeWorkspacePanel.value = 'result'
    }
  },
)
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
        <details v-if="showDeveloperTools" class="creator-ai-more-actions">
          <summary class="creator-secondary-action">更多操作</summary>
          <div class="creator-ai-more-menu">
            <button
              type="button"
              class="creator-ghost-button"
              :disabled="!hasSelectedTask"
              @click="openWorkflowMessageModal"
            >
              查看消息流
            </button>
          </div>
        </details>
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
        :disabled="!workflowSession"
        @click="openWorkflowProcessModal"
      >
        查看执行过程
      </button>
    </section>

    <div
      v-if="isTabbedWorkspace"
      class="creator-ai-workspace-tabs"
      :style="{ '--creator-flow-card-height': `${mobileFlowCardHeight}px` }"
      role="tablist"
      aria-label="发布前优化工作区"
    >
      <button
        id="prepublish-collaboration-tab"
        type="button"
        role="tab"
        :aria-selected="activeWorkspacePanel === 'collaboration'"
        aria-controls="prepublish-collaboration-panel"
        :tabindex="activeWorkspacePanel === 'collaboration' ? 0 : -1"
        :class="{ active: activeWorkspacePanel === 'collaboration' }"
        @click="selectWorkspacePanel('collaboration')"
        @keydown="handleWorkspaceTabKeydown($event, 'collaboration')"
      >
        AI 协作
      </button>
      <button
        id="prepublish-result-tab"
        type="button"
        role="tab"
        :aria-selected="activeWorkspacePanel === 'result'"
        aria-controls="prepublish-result-panel"
        :tabindex="activeWorkspacePanel === 'result' ? 0 : -1"
        :class="{ active: activeWorkspacePanel === 'result' }"
        @click="selectWorkspacePanel('result')"
        @keydown="handleWorkspaceTabKeydown($event, 'result')"
      >
        发布方案
      </button>
    </div>

    <div class="creator-ai-prepublish-layout" :class="{ 'is-tabbed': isTabbedWorkspace }">
      <section
        id="prepublish-collaboration-panel"
        v-show="!isTabbedWorkspace || activeWorkspacePanel === 'collaboration'"
        class="creator-ai-console-panel creator-ai-collaboration-panel"
        role="tabpanel"
        :aria-labelledby="isTabbedWorkspace ? 'prepublish-collaboration-tab' : undefined"
        aria-label="发布前优化 AI 协作区"
      >
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

        <div ref="messageThreadRef" class="creator-ai-message-thread" aria-label="发布前优化对话">
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
          </div>

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
        </details>

        <form class="creator-ai-composer" @submit.prevent="submitSupplement">
          <textarea
            v-model="workflowMessageDraft"
            maxlength="1000"
            :disabled="
              isActiveStepReadOnly ||
              !canSendWorkflowMessage ||
              isSendingWorkflowMessage ||
              isAnalyzingPrePublish ||
              isConfirmingPrePublish
            "
            placeholder="补充你的完整需求、口播风格、标题禁忌或必须出现的信息"
            @keydown.ctrl.enter.prevent="submitSupplement"
          ></textarea>
          <div class="creator-ai-composer-actions">
            <button
              type="submit"
              class="creator-primary-button"
              :disabled="
                isActiveStepReadOnly ||
                !canSendWorkflowMessage ||
                !workflowMessageDraft.trim() ||
                isSendingWorkflowMessage ||
                isAnalyzingPrePublish ||
                isConfirmingPrePublish
              "
            >
              {{ isSendingWorkflowMessage ? '发送中...' : '发送给 AI' }}
            </button>
          </div>
        </form>
      </section>

      <section
        id="prepublish-result-panel"
        v-show="!isTabbedWorkspace || activeWorkspacePanel === 'result'"
        class="creator-ai-result-panel"
        role="tabpanel"
        :aria-labelledby="isTabbedWorkspace ? 'prepublish-result-tab' : undefined"
        aria-label="发布方案"
      >
        <PrePublishSuggestionPanel />
      </section>
    </div>
  </section>
</template>

<style scoped>
.creator-ai-prepublish-section {
  min-width: 0;
}

.creator-ai-prepublish-head {
  position: relative;
  z-index: 3;
}

.creator-ai-more-actions {
  position: relative;
}

.creator-ai-more-actions summary {
  display: inline-flex;
  min-height: 44px;
  align-items: center;
  cursor: pointer;
  list-style: none;
}

.creator-ai-more-actions summary::-webkit-details-marker {
  display: none;
}

.creator-ai-more-menu {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  z-index: 4;
  display: grid;
  min-width: 148px;
  gap: 6px;
  padding: 6px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 8px;
  box-shadow: var(--sh-md);
}

.creator-ai-more-menu button {
  justify-content: flex-start;
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

.creator-ai-workspace-tabs {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  padding: 4px;
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: 8px;
}

.creator-ai-workspace-tabs button {
  min-height: 44px;
  padding: 0 12px;
  color: var(--muted);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 6px;
  cursor: pointer;
  font: inherit;
  font-size: 14px;
  font-weight: var(--fw-semibold);
  transition: color 180ms, background-color 180ms, border-color 180ms;
}

.creator-ai-workspace-tabs button.active {
  color: var(--accent-strong);
  background: var(--surface);
  border-color: var(--accent-ring);
}

.creator-ai-workspace-tabs button:focus-visible,
.creator-ai-more-actions summary:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

.creator-ai-prepublish-layout {
  display: grid;
  min-width: 0;
  gap: 16px;
}

.creator-ai-collaboration-panel {
  grid-template-rows: auto auto minmax(160px, 1fr) auto auto;
  min-height: 0;
}

.creator-ai-lead {
  background: var(--surface-sub);
  border-color: var(--border);
}

.creator-ai-lead span {
  background: var(--accent);
}

.creator-ai-message-thread {
  min-height: 160px;
  max-height: none;
}

.creator-ai-support-details {
  min-height: 0;
  max-height: 286px;
  overflow: auto;
}

.creator-ai-result-panel {
  display: flex;
  min-width: 0;
  min-height: 0;
}

.creator-ai-result-panel > :deep(*) {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
}

@media (min-width: 1280px) {
  .creator-ai-prepublish-layout {
    grid-template-columns: minmax(320px, 380px) minmax(560px, 1fr);
    align-items: stretch;
  }

  .creator-ai-collaboration-panel,
  .creator-ai-result-panel {
    overflow: hidden;
  }
}

/* 仅在有足够垂直空间时锁定工作区高度，低高度屏幕仍可由页面自然滚动。 */
@media (min-width: 1280px) and (min-height: 760px) {
  .creator-ai-prepublish-layout {
    height: calc(100dvh - var(--surface-topbar-height) - 156px);
    min-height: 560px;
    max-height: 800px;
  }
}

@media (min-width: 981px) and (max-width: 1279px) {
  .creator-ai-prepublish-layout.is-tabbed {
    grid-template-columns: minmax(0, 1fr);
    min-height: 620px;
  }

  .creator-ai-prepublish-layout.is-tabbed > section {
    min-height: 0;
  }

  .creator-ai-prepublish-layout.is-tabbed .creator-ai-collaboration-panel,
  .creator-ai-prepublish-layout.is-tabbed .creator-ai-result-panel {
    overflow: hidden;
  }
}

@media (max-width: 980px) {
  .creator-ai-workspace-tabs {
    position: sticky;
    top: calc(var(--surface-topbar-height) + 8px);
    z-index: 2;
  }

  .creator-ai-compact-status {
    grid-template-columns: 1fr auto;
  }

  .creator-ai-compact-status p {
    grid-column: 1 / -1;
    overflow: visible;
    text-overflow: clip;
    white-space: normal;
  }

  .creator-ai-prepublish-layout {
    grid-template-columns: minmax(0, 1fr);
    min-height: auto;
  }

  .creator-ai-collaboration-panel,
  .creator-ai-result-panel {
    min-height: auto;
    overflow: visible;
  }

  .creator-ai-message-thread,
  .creator-ai-support-details {
    max-height: none;
    overflow: visible;
  }
}

@media (max-width: 820px) {
  .creator-ai-workspace-tabs {
    top: calc(var(--creator-mobile-flow-top, 62px) + var(--creator-flow-card-height, 0px) + 8px);
    z-index: 16;
  }

  .creator-ai-prepublish-section button,
  .creator-ai-more-actions summary {
    min-height: 44px;
  }
}

@media (max-width: 480px) {
  .creator-ai-prepublish-head,
  .creator-ai-prepublish-head .creator-action-row {
    align-items: stretch;
  }

  .creator-ai-prepublish-head .creator-action-row,
  .creator-ai-more-actions,
  .creator-ai-more-actions summary {
    width: 100%;
  }

  .creator-ai-more-menu {
    right: auto;
    left: 0;
    width: 100%;
  }
}
</style>
