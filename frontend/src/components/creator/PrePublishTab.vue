<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  FileText,
  ListTree,
  Save,
  Send,
  Settings2,
  Sparkles,
  X,
} from '@lucide/vue'
import PrePublishSuggestionPanel from '@/components/creator/PrePublishSuggestionPanel.vue'
import {
  formatDate,
  getLatestWorkflowFailedStep,
  workflowRoleLabel,
} from '@/composables/creator/creatorWorkspaceUtils'
import { useCreatorWorkspaceShell } from '@/composables/creator/useCreatorWorkspaceContext'

const {
  suggestion,
  selectedTaskId,
  hasSelectedTask,
  hasConfirmedPrePublish,
  openWorkflowMessageModal,
  workflowSteps,
  workflowSession,
  workflowMessages,
  workflowMessageDraft,
  workflowSseText,
  workflowStatusText,
  workflowRunningStep,
  isLoadingWorkflow,
  isSendingWorkflowMessage,
  isConfirmingPrePublish,
  canSendWorkflowMessage,
  sendWorkflowSupplement,
  updateWorkflowMessageDraft,
  isAnalyzingPrePublish,
  isPreparingPrePublishAnalyze,
  runPrePublishAnalyze,
  prePublishAnalyzeUnavailableReason,
  hasPrePublishScriptMaterial,
  canGeneratePrePublishDraft,
  isGeneratingPrePublishDraft,
  generatePrePublishManuscriptDraftForCurrentTask,
  selectedPreferenceModeLabel,
  isLoadingCreatorPreferences,
  creatorPreferencesError,
  retryCreatorPreferences,
  preferenceModeOptions,
  prePublishForm,
  historicalPreferenceGroups,
  historicalPreferenceCount,
  currentVideoType,
  openContextLibrary,
  contextTermChips,
  isLoadingCreatorContextTerms,
  isActiveStepReadOnly,
  hasPrePublishSettingsChanges,
  isLoadingPrePublishSettings,
  isSavingPrePublishSettings,
  prePublishSettingsSaveState,
  prePublishSettingsError,
  prePublishSettingsErrorSource,
  saveCurrentPrePublishSettings,
  reloadCurrentPrePublishSettings,
  showDeveloperTools,
} = useCreatorWorkspaceShell()

const activeView = ref<'chat' | 'plan'>(suggestion.value ? 'plan' : 'chat')
const isSettingsOpen = ref(false)
const messageListRef = ref<HTMLDivElement | null>(null)

const currentWorkflowFailedStep = computed(() => getLatestWorkflowFailedStep(workflowSteps.value))
const latestAiUnderstanding = computed(() => {
  const messages = [...workflowMessages.value].reverse()
  return messages.find((message) =>
    message.role === 'AGENT' && message.detailRefType === 'INTENT_ALIGNMENT',
  ) ?? messages.find((message) => message.role === 'AGENT') ?? null
})
const visibleWorkflowMessages = computed(() => {
  const collaborationMessages = workflowMessages.value.filter((message) =>
    message.role === 'USER' || message.role === 'AGENT' || message.role === 'RESULT',
  )
  return collaborationMessages.length > 0 ? collaborationMessages : workflowMessages.value
})
const compactWorkflowStatus = computed(() => {
  if (currentWorkflowFailedStep.value) {
    return `执行未完成：${currentWorkflowFailedStep.value.stepName || '未知步骤'}`
  }
  if (workflowRunningStep.value) {
    return `正在${workflowRunningStep.value.stepName || '处理发布方案'}`
  }
  if (workflowSteps.value.length > 0) {
    const completedCount = workflowSteps.value.filter((step) => step.status === 'SUCCESS').length
    return `已完成 ${completedCount}/${workflowSteps.value.length} 步`
  }
  return workflowStatusText.value
})
const settingsSaveLabel = computed(() => {
  if (isSavingPrePublishSettings.value) return '保存中...'
  if (!hasPrePublishSettingsChanges.value && prePublishSettingsSaveState.value === 'saved') return '已保存'
  if (prePublishSettingsErrorSource.value === 'save') return '重试保存'
  return '保存本次设置'
})
const generationDisabled = computed(() =>
  isActiveStepReadOnly.value ||
  hasConfirmedPrePublish.value ||
  isLoadingWorkflow.value ||
  isLoadingPrePublishSettings.value ||
  Boolean(prePublishSettingsError.value) ||
  isSavingPrePublishSettings.value ||
  isPreparingPrePublishAnalyze.value ||
  isSendingWorkflowMessage.value ||
  isGeneratingPrePublishDraft.value ||
  isConfirmingPrePublish.value ||
  isAnalyzingPrePublish.value ||
  workflowSession.value?.status === 'RUNNING' ||
  workflowSession.value?.status === 'CANCELLED' ||
  !hasPrePublishScriptMaterial.value ||
  (workflowSession.value?.planGenerationCount ?? 0) >= 3,
)
const generationHint = computed(() => {
  if (isLoadingPrePublishSettings.value) return '正在恢复当前任务的发布前设置。'
  if (prePublishSettingsError.value) return '当前任务设置读取或保存失败，请打开“偏好与语境”重试。'
  if (isLoadingWorkflow.value) return '正在从服务端同步工作流会话。'
  if (isPreparingPrePublishAnalyze.value) return '正在保存本次设置并校验会话状态。'
  if (isSendingWorkflowMessage.value) return '正在发送补充信息，请等待发送完成。'
  if (isGeneratingPrePublishDraft.value) return '正在补全文稿，请等待文稿保存完成。'
  if (isConfirmingPrePublish.value) return '正在确认当前发布方案，请等待操作完成。'
  if (isAnalyzingPrePublish.value) return '发布方案正在生成，请勿重复提交。'
  if ((workflowSession.value?.planGenerationCount ?? 0) >= 3) {
    return '当前上下文已生成三次，请先给 AI 补充新信息再继续。'
  }
  if (!hasPrePublishScriptMaterial.value) return '当前任务缺少完整文稿或字幕，可先让 AI 补全文稿。'
  if (!workflowSession.value) return '点击生成时会先从服务端恢复工作流会话。'
  return prePublishAnalyzeUnavailableReason.value
})
const generationLabel = computed(() => {
  if (isPreparingPrePublishAnalyze.value) return '准备生成...'
  if (isAnalyzingPrePublish.value) return suggestion.value ? '重新生成中...' : '生成中...'
  if (currentWorkflowFailedStep.value) return '重试生成'
  return suggestion.value ? '重新生成发布方案' : '生成发布方案'
})

watch(
  () => suggestion.value?.suggestionId,
  (suggestionId, previousSuggestionId) => {
    if (suggestionId && suggestionId !== previousSuggestionId) activeView.value = 'plan'
    if (!suggestionId) activeView.value = 'chat'
  },
)

watch(
  () => selectedTaskId.value,
  () => {
    // 切换任务时关闭旧任务抽屉，避免用户误把尚未看清的新任务设置继续编辑并保存。
    isSettingsOpen.value = false
  },
)

watch(
  () => visibleWorkflowMessages.value.length,
  () => {
    if (activeView.value === 'chat') void scrollMessagesToBottom()
  },
)

watch(activeView, (view) => {
  if (view === 'chat') void scrollMessagesToBottom()
})

onMounted(() => window.addEventListener('keydown', handleKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', handleKeydown))

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && isSettingsOpen.value) isSettingsOpen.value = false
}

async function scrollMessagesToBottom() {
  await nextTick()
  if (messageListRef.value) messageListRef.value.scrollTop = messageListRef.value.scrollHeight
}

function updateMessageDraft(event: Event) {
  updateWorkflowMessageDraft((event.target as HTMLTextAreaElement).value)
}

async function sendMessage() {
  await sendWorkflowSupplement()
  await scrollMessagesToBottom()
}

async function generateDraftFromConversation() {
  await generatePrePublishManuscriptDraftForCurrentTask(workflowMessageDraft.value)
}
</script>

<template>
  <section class="creator-ai-prepublish-section" aria-label="发布前优化">
    <header class="creator-ai-prepublish-head">
      <div>
        <p class="creator-kicker">发布前优化</p>
        <h3>AI 协作与补充信息</h3>
      </div>
      <button
        type="button"
        class="creator-secondary-action creator-settings-trigger"
        :disabled="!hasSelectedTask"
        @click="isSettingsOpen = true"
      >
        <Settings2 :size="17" :stroke-width="1.8" aria-hidden="true" />
        偏好与语境
        <span v-if="hasPrePublishSettingsChanges" aria-label="有未保存修改"></span>
      </button>
    </header>

    <section
      class="creator-ai-compact-status"
      :class="{ failed: currentWorkflowFailedStep }"
      role="status"
      aria-live="polite"
    >
      <span class="creator-ai-connection-status" :class="{ active: workflowSseText === '实时连接' }">
        {{ workflowSseText }}
      </span>
      <p :class="{ failed: currentWorkflowFailedStep }">{{ compactWorkflowStatus }}</p>
      <button
        type="button"
        class="creator-ghost-button creator-mini-button creator-process-trigger"
        :disabled="!hasSelectedTask || !workflowSession"
        @click="openWorkflowMessageModal"
      >
        <ListTree :size="15" :stroke-width="1.8" aria-hidden="true" />
        查看执行过程
      </button>
    </section>

    <nav v-if="suggestion" class="creator-ai-view-tabs" aria-label="发布前优化视图">
      <button type="button" :class="{ active: activeView === 'chat' }" @click="activeView = 'chat'">
        AI 沟通
      </button>
      <button type="button" :class="{ active: activeView === 'plan' }" @click="activeView = 'plan'">
        发布方案
      </button>
    </nav>

    <div class="creator-ai-main-workspace">
      <section v-show="activeView === 'chat'" class="creator-ai-chat-workspace" aria-label="AI 沟通">
        <header class="creator-ai-understanding">
          <div>
            <span>AI 当前理解</span>
            <p>{{ latestAiUnderstanding?.content || 'AI 尚未形成新的理解，可直接补充这期视频的目标、受众或表达要求。' }}</p>
          </div>
          <b>{{ workflowSession?.planGenerationCount ?? 0 }}/3 次</b>
        </header>

        <div ref="messageListRef" class="creator-ai-message-list" aria-live="polite">
          <article
            v-for="message in visibleWorkflowMessages"
            :key="message.messageId"
            class="creator-ai-message"
            :class="`role-${message.role.toLowerCase()}`"
          >
            <header>
              <strong>{{ workflowRoleLabel(message.role) }}</strong>
              <time>{{ formatDate(message.createTime) }}</time>
            </header>
            <p>{{ message.content }}</p>
          </article>
          <div v-if="isLoadingWorkflow && visibleWorkflowMessages.length === 0" class="creator-ai-message-empty">
            正在恢复此前对话...
          </div>
          <div v-else-if="visibleWorkflowMessages.length === 0" class="creator-ai-message-empty">
            还没有协作消息，可以在下方直接补充信息。
          </div>
        </div>

        <div v-if="!hasPrePublishScriptMaterial" class="creator-ai-material-note">
          <FileText :size="18" :stroke-width="1.8" aria-hidden="true" />
          <p>当前材料还缺完整文稿或字幕。</p>
          <button
            type="button"
            class="creator-secondary-action creator-mini-button"
            :disabled="isActiveStepReadOnly || !canGeneratePrePublishDraft || isGeneratingPrePublishDraft"
            @click="generateDraftFromConversation"
          >
            {{ isGeneratingPrePublishDraft ? '补稿中...' : '让 AI 补全文稿' }}
          </button>
        </div>

        <form class="creator-ai-composer" @submit.prevent="sendMessage">
          <label>
            <span class="sr-only">给 AI 补充信息</span>
            <textarea
              :value="workflowMessageDraft"
              maxlength="2000"
              :disabled="isActiveStepReadOnly || isSendingWorkflowMessage"
              placeholder="补充目标受众、表达边界或本期必须保留的信息"
              @input="updateMessageDraft"
            ></textarea>
          </label>
          <button
            type="submit"
            class="creator-secondary-action creator-send-action"
            :disabled="isActiveStepReadOnly || !canSendWorkflowMessage || !workflowMessageDraft.trim() || isSendingWorkflowMessage"
          >
            <Send :size="16" :stroke-width="1.8" aria-hidden="true" />
            {{ isSendingWorkflowMessage ? '发送中...' : '发送给 AI' }}
          </button>
        </form>
      </section>

      <section v-if="suggestion" v-show="activeView === 'plan'" class="creator-ai-plan-workspace">
        <PrePublishSuggestionPanel />
      </section>
    </div>

    <footer class="creator-ai-generation-bar">
      <p :class="{ failed: currentWorkflowFailedStep }">
        {{ generationHint || '生成时会使用当前表单值，并同步保存为本任务设置。' }}
      </p>
      <button
        type="button"
        class="creator-primary-button creator-generate-action"
        :disabled="generationDisabled"
        :title="generationHint"
        @click="runPrePublishAnalyze"
      >
        <Sparkles :size="17" :stroke-width="1.8" aria-hidden="true" />
        {{ generationLabel }}
      </button>
    </footer>

    <Teleport to="body">
      <Transition name="creator-modal">
        <div
          v-if="isSettingsOpen"
          class="creator-settings-backdrop"
          role="presentation"
          @click.self="isSettingsOpen = false"
        >
          <aside
            class="creator-settings-drawer"
            role="dialog"
            aria-modal="true"
            aria-labelledby="creator-settings-title"
          >
            <header class="creator-settings-head">
              <div>
                <span>当前任务</span>
                <h3 id="creator-settings-title">偏好与语境</h3>
              </div>
              <button
                type="button"
                class="creator-icon-button"
                title="关闭"
                aria-label="关闭偏好与语境"
                @click="isSettingsOpen = false"
              >
                <X :size="18" aria-hidden="true" />
              </button>
            </header>

            <div class="creator-settings-body">
              <section class="creator-settings-section">
                <header>
                  <strong>偏好使用方式</strong>
                  <span>{{ selectedPreferenceModeLabel }}</span>
                </header>
                <div class="creator-preference-modes" role="group" aria-label="偏好使用方式">
                  <button
                    v-for="option in preferenceModeOptions"
                    :key="option.value"
                    type="button"
                    :class="{ active: prePublishForm.preferenceMode === option.value }"
                    :disabled="isActiveStepReadOnly || isLoadingPrePublishSettings || isSavingPrePublishSettings"
                    @click="prePublishForm.preferenceMode = option.value"
                  >
                    <span>{{ option.label }}</span>
                    <small>{{ option.description }}</small>
                  </button>
                </div>
              </section>

              <section class="creator-settings-section creator-history-preferences">
                <header>
                  <strong>历史偏好</strong>
                  <span v-if="historicalPreferenceCount">{{ historicalPreferenceCount }} 条</span>
                </header>
                <p v-if="prePublishForm.preferenceMode === 'IGNORE_HISTORY'" class="creator-history-state">
                  本期不使用历史偏好
                </p>
                <p v-else-if="isLoadingCreatorPreferences" class="creator-history-state">正在读取历史偏好...</p>
                <div v-else-if="creatorPreferencesError" class="creator-history-state is-error">
                  <span>历史偏好读取失败</span>
                  <button type="button" class="creator-ghost-button creator-mini-button" @click="retryCreatorPreferences">
                    重新读取
                  </button>
                </div>
                <p v-else-if="historicalPreferenceCount === 0" class="creator-history-state">暂无历史偏好</p>
                <details v-else class="creator-history-details">
                  <summary>参考最近 {{ historicalPreferenceCount }} 条已采纳偏好</summary>
                  <section v-for="group in historicalPreferenceGroups" :key="group.key">
                    <h4>{{ group.label }}</h4>
                    <ul>
                      <li v-for="item in group.items" :key="`${group.key}-${item.text}`">
                        <p>{{ item.text }}</p>
                        <small>{{ item.sourceTaskName }} · {{ formatDate(item.sourceTime) }}</small>
                        <details v-if="showDeveloperTools" class="creator-history-developer-detail">
                          <summary>开发者字段</summary>
                          <code>{{ item.sourceTaskId }}</code>
                          <pre>{{ item.rawText }}</pre>
                        </details>
                      </li>
                    </ul>
                  </section>
                </details>
              </section>

              <section class="creator-settings-section">
                <header>
                  <strong>本期设置</strong>
                  <span v-if="isLoadingPrePublishSettings">恢复中</span>
                </header>
                <div class="creator-settings-form">
                  <label>
                    <span>创作目标</span>
                    <textarea
                      v-model="prePublishForm.creatorPreference"
                      maxlength="500"
                      :disabled="isActiveStepReadOnly || isLoadingPrePublishSettings || isSavingPrePublishSettings"
                      placeholder="这期最想让观众记住什么？"
                    ></textarea>
                  </label>
                  <label>
                    <span>标题风格</span>
                    <input
                      v-model="prePublishForm.titleStyle"
                      type="text"
                      maxlength="100"
                      :disabled="isActiveStepReadOnly || isLoadingPrePublishSettings || isSavingPrePublishSettings"
                      placeholder="克制、教程感、故事感等"
                    />
                  </label>
                  <label>
                    <span>本期额外要求</span>
                    <textarea
                      v-model="prePublishForm.extraRequirement"
                      maxlength="500"
                      :disabled="isActiveStepReadOnly || isLoadingPrePublishSettings || isSavingPrePublishSettings"
                      placeholder="补充标题、简介或标签要求"
                    ></textarea>
                  </label>
                  <label>
                    <span>其它发布前语境</span>
                    <textarea
                      v-model="prePublishForm.customGuidance"
                      maxlength="2000"
                      :disabled="isActiveStepReadOnly || isLoadingPrePublishSettings || isSavingPrePublishSettings"
                      placeholder="只用于当前任务的其它背景和表达边界"
                    ></textarea>
                  </label>
                </div>
              </section>

              <section class="creator-settings-section">
                <header>
                  <div>
                    <strong>类型语境</strong>
                    <span>{{ currentVideoType === 'GLOBAL' ? '全局通用' : currentVideoType }}</span>
                  </div>
                  <button
                    type="button"
                    class="creator-ghost-button creator-mini-button"
                    :disabled="isActiveStepReadOnly"
                    @click="openContextLibrary"
                  >
                    管理语境
                  </button>
                </header>
                <div class="creator-context-tags">
                  <span v-for="chip in contextTermChips" :key="chip.id" :title="chip.title">
                    {{ chip.label }} · {{ chip.text }}
                  </span>
                  <em v-if="isLoadingCreatorContextTerms">读取中...</em>
                  <em v-else-if="contextTermChips.length === 0">当前类型暂无语境词</em>
                </div>
              </section>
            </div>

            <footer class="creator-settings-footer">
              <p v-if="prePublishSettingsError" class="is-error">{{ prePublishSettingsError }}</p>
              <p v-else-if="!hasPrePublishSettingsChanges && prePublishSettingsSaveState === 'saved'">本次设置已保存</p>
              <span></span>
              <button
                v-if="prePublishSettingsErrorSource === 'load' && !hasPrePublishSettingsChanges"
                type="button"
                class="creator-secondary-action"
                :disabled="isLoadingPrePublishSettings"
                @click="reloadCurrentPrePublishSettings"
              >
                {{ isLoadingPrePublishSettings ? '读取中...' : '重新读取' }}
              </button>
              <button
                type="button"
                class="creator-primary-button"
                :disabled="isActiveStepReadOnly || isLoadingPrePublishSettings || isSavingPrePublishSettings || (!hasPrePublishSettingsChanges && prePublishSettingsErrorSource !== 'save')"
                @click="saveCurrentPrePublishSettings(prePublishSettingsErrorSource === 'save')"
              >
                <Save :size="16" :stroke-width="1.8" aria-hidden="true" />
                {{ settingsSaveLabel }}
              </button>
            </footer>
          </aside>
        </div>
      </Transition>
    </Teleport>
  </section>
</template>

<style scoped>
.creator-ai-prepublish-section {
  display: grid;
  grid-template-rows: auto auto auto minmax(0, 1fr) auto;
  min-width: 0;
  min-height: calc(100dvh - var(--surface-topbar-height) - 40px);
  gap: 12px;
  padding: 16px;
  background: var(--surface);
}

.creator-ai-prepublish-head,
.creator-ai-compact-status,
.creator-ai-generation-bar,
.creator-ai-understanding,
.creator-ai-composer,
.creator-settings-head,
.creator-settings-footer,
.creator-settings-section > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.creator-ai-prepublish-head > div {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.creator-ai-prepublish-head h3,
.creator-ai-prepublish-head p,
.creator-settings-head h3 {
  margin: 0;
}

.creator-ai-prepublish-head h3 {
  color: var(--ink);
  font-size: 20px;
}

.creator-settings-trigger,
.creator-process-trigger,
.creator-send-action,
.creator-generate-action,
.creator-settings-footer .creator-primary-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
}

.creator-settings-trigger {
  position: relative;
}

.creator-settings-trigger > span {
  position: absolute;
  top: 5px;
  right: 5px;
  width: 7px;
  height: 7px;
  background: var(--warn);
  border: 2px solid var(--surface);
  border-radius: 50%;
}

.creator-ai-compact-status {
  min-width: 0;
  padding: 8px 10px;
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: 8px;
}

.creator-ai-compact-status.failed {
  background: rgba(220, 38, 38, 0.04);
  border-color: rgba(220, 38, 38, 0.22);
}

.creator-ai-compact-status p,
.creator-ai-generation-bar p {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--text);
  font-size: 13px;
  line-height: 1.45;
}

.creator-ai-compact-status p.failed,
.creator-ai-generation-bar p.failed,
.creator-settings-footer .is-error {
  color: var(--danger);
}

.creator-ai-connection-status {
  flex: 0 0 auto;
  padding: 5px 8px;
  color: var(--muted);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

.creator-ai-connection-status.active {
  color: var(--ok);
  background: rgba(22, 163, 74, 0.08);
  border-color: rgba(22, 163, 74, 0.2);
}

.creator-ai-view-tabs {
  display: inline-flex;
  width: fit-content;
  max-width: 100%;
  gap: 4px;
  padding: 4px;
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: 7px;
}

.creator-ai-view-tabs button {
  min-height: 34px;
  padding: 0 14px;
  color: var(--muted);
  background: transparent;
  border: 0;
  border-radius: 5px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 700;
}

.creator-ai-view-tabs button.active {
  color: var(--ink);
  background: var(--surface);
  box-shadow: 0 1px 3px rgba(23, 32, 51, 0.1);
}

.creator-ai-main-workspace,
.creator-ai-chat-workspace,
.creator-ai-plan-workspace {
  min-width: 0;
  min-height: 0;
}

.creator-ai-chat-workspace {
  display: grid;
  grid-template-rows: auto minmax(220px, 1fr) auto auto;
  height: 100%;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 8px;
}

.creator-ai-understanding {
  align-items: flex-start;
  padding: 14px 16px;
  background: var(--accent-tint);
  border-bottom: 1px solid var(--accent-ring);
}

.creator-ai-understanding > div {
  display: grid;
  min-width: 0;
  gap: 5px;
}

.creator-ai-understanding span,
.creator-ai-understanding b {
  color: var(--accent-strong);
  font-size: 12px;
  font-weight: 750;
}

.creator-ai-understanding p {
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--text);
  line-height: 1.6;
  white-space: pre-wrap;
}

.creator-ai-understanding b {
  flex: 0 0 auto;
  padding: 4px 7px;
  background: var(--surface);
  border: 1px solid var(--accent-ring);
  border-radius: var(--r-pill);
}

.creator-ai-message-list {
  display: grid;
  align-content: start;
  min-height: 0;
  gap: 10px;
  padding: 16px;
  overflow-x: hidden;
  overflow-y: auto;
  background: var(--surface);
}

.creator-ai-message {
  display: grid;
  width: min(82%, 760px);
  min-width: 0;
  gap: 6px;
  padding: 11px 13px;
  background: var(--surface-sub);
  border-left: 3px solid var(--border-strong);
  border-radius: 4px;
}

.creator-ai-message.role-user {
  justify-self: end;
  background: rgba(23, 32, 51, 0.05);
  border-left-color: var(--muted);
}

.creator-ai-message.role-agent,
.creator-ai-message.role-result {
  justify-self: start;
  background: var(--accent-tint);
  border-left-color: var(--accent);
}

.creator-ai-message header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.creator-ai-message strong,
.creator-ai-message time {
  color: var(--muted);
  font-size: 11px;
  font-weight: 700;
}

.creator-ai-message p {
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--text);
  line-height: 1.6;
  white-space: pre-wrap;
}

.creator-ai-message-empty {
  display: grid;
  min-height: 160px;
  place-items: center;
  color: var(--muted);
  text-align: center;
}

.creator-ai-material-note {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  color: var(--warn);
  background: rgba(217, 119, 6, 0.06);
  border-top: 1px solid rgba(217, 119, 6, 0.18);
}

.creator-ai-material-note p {
  margin: 0;
  color: var(--text);
  font-size: 13px;
}

.creator-ai-composer {
  align-items: end;
  padding: 12px 14px;
  background: var(--surface);
  border-top: 1px solid var(--border);
}

.creator-ai-composer label {
  flex: 1 1 auto;
  min-width: 0;
}

.creator-ai-composer textarea {
  width: 100%;
  min-height: 84px;
  max-height: 220px;
  padding: 10px 12px;
  resize: vertical;
  color: var(--text);
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: 7px;
  outline: none;
  line-height: 1.55;
}

.creator-ai-composer textarea:focus-visible {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-ring);
}

.creator-send-action {
  min-width: 124px;
  min-height: 42px;
}

.creator-ai-plan-workspace,
.creator-ai-plan-workspace :deep(.pre-publish-suggestion-panel) {
  height: 100%;
}

.creator-ai-generation-bar {
  position: sticky;
  bottom: 0;
  z-index: 5;
  min-height: 64px;
  padding: 10px 12px;
  background: var(--surface);
  border-top: 1px solid var(--border);
  box-shadow: 0 -8px 18px rgba(23, 32, 51, 0.05);
}

.creator-ai-generation-bar p {
  max-width: 760px;
  color: var(--muted);
}

.creator-generate-action {
  flex: 0 0 auto;
  min-width: 210px;
  min-height: 44px;
}

.creator-settings-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1800;
  display: flex;
  justify-content: flex-end;
  background: rgba(23, 32, 51, 0.38);
}

.creator-settings-drawer {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  width: min(560px, 100vw);
  height: 100dvh;
  overflow: hidden;
  background: var(--surface);
  border-left: 1px solid var(--border);
  box-shadow: -16px 0 36px rgba(23, 32, 51, 0.14);
}

.creator-settings-head {
  padding: 16px 18px;
  border-bottom: 1px solid var(--border);
}

.creator-settings-head > div {
  display: grid;
  gap: 3px;
}

.creator-settings-head span {
  color: var(--muted);
  font-size: 12px;
}

.creator-icon-button {
  display: inline-grid;
  width: 38px;
  height: 38px;
  place-items: center;
  padding: 0;
  color: var(--text);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 6px;
  cursor: pointer;
}

.creator-settings-body {
  min-height: 0;
  padding: 0 18px;
  overflow-x: hidden;
  overflow-y: auto;
}

.creator-settings-section {
  display: grid;
  gap: 12px;
  padding: 18px 0;
  border-bottom: 1px solid var(--border);
}

.creator-settings-section > header {
  align-items: flex-start;
}

.creator-settings-section > header > div {
  display: grid;
  gap: 3px;
}

.creator-settings-section header strong,
.creator-settings-section header span {
  color: var(--ink);
  font-size: 14px;
}

.creator-settings-section header span {
  color: var(--muted);
  font-size: 12px;
}

.creator-preference-modes {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.creator-preference-modes button {
  display: grid;
  min-width: 0;
  min-height: 62px;
  align-content: center;
  gap: 3px;
  padding: 8px;
  color: var(--text);
  text-align: left;
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: 7px;
  cursor: pointer;
}

.creator-preference-modes button.active {
  color: var(--accent-strong);
  background: var(--accent-tint);
  border-color: var(--accent-ring);
}

.creator-preference-modes button span {
  font-size: 13px;
  font-weight: 700;
}

.creator-preference-modes button small {
  color: var(--muted);
  font-size: 11px;
}

.creator-history-state {
  display: flex;
  min-height: 48px;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin: 0;
  padding: 10px 12px;
  color: var(--muted);
  background: var(--surface-sub);
  border-left: 3px solid var(--border-strong);
  font-size: 13px;
}

.creator-history-state.is-error {
  color: var(--danger);
  border-left-color: var(--danger);
}

.creator-history-details > summary {
  min-height: 40px;
  color: var(--ink);
  cursor: pointer;
  font-size: 13px;
  font-weight: 700;
}

.creator-history-details > section {
  padding: 12px 0;
  border-top: 1px solid var(--border);
}

.creator-history-details h4 {
  margin: 0 0 8px;
  color: var(--ink);
  font-size: 13px;
}

.creator-history-details ul {
  display: grid;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.creator-history-details li {
  display: grid;
  gap: 3px;
  padding-left: 10px;
  border-left: 2px solid var(--accent-ring);
}

.creator-history-details p {
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--text);
  font-size: 13px;
  line-height: 1.5;
}

.creator-history-details small {
  color: var(--muted);
  font-size: 11px;
}

.creator-history-developer-detail summary {
  color: var(--muted);
  cursor: pointer;
  font-size: 11px;
}

.creator-history-developer-detail code,
.creator-history-developer-detail pre {
  display: block;
  max-width: 100%;
  margin: 5px 0 0;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  color: var(--muted);
  font-size: 11px;
}

.creator-settings-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.creator-settings-form label {
  display: grid;
  min-width: 0;
  gap: 6px;
  color: var(--text);
  font-size: 12px;
  font-weight: 700;
}

.creator-settings-form textarea,
.creator-settings-form input {
  width: 100%;
  min-height: 42px;
  padding: 9px 10px;
  color: var(--text);
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: 7px;
  outline: none;
  line-height: 1.5;
}

.creator-settings-form textarea {
  min-height: 96px;
  resize: vertical;
}

.creator-settings-form label:first-child,
.creator-settings-form label:nth-child(4) {
  grid-column: 1 / -1;
}

.creator-settings-form textarea:focus-visible,
.creator-settings-form input:focus-visible {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-ring);
}

.creator-context-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}

.creator-context-tags span {
  max-width: 100%;
  padding: 5px 8px;
  overflow-wrap: anywhere;
  color: var(--text);
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  font-size: 12px;
}

.creator-context-tags em {
  color: var(--muted);
  font-size: 12px;
  font-style: normal;
}

.creator-settings-footer {
  flex-wrap: wrap;
  min-height: 68px;
  padding: 10px 18px;
  background: var(--surface);
  border-top: 1px solid var(--border);
}

.creator-settings-footer p {
  min-width: 0;
  margin: 0;
  color: var(--muted);
  font-size: 12px;
}

.creator-settings-footer > span {
  flex: 1 1 auto;
}

.creator-settings-footer .creator-primary-button {
  flex: 0 0 auto;
  min-width: 152px;
}

@media (min-width: 1080px) and (min-height: 680px) {
  .creator-ai-prepublish-section {
    height: calc(100dvh - var(--surface-topbar-height) - 40px);
    overflow: hidden;
  }
}

@media (max-width: 820px) {
  .creator-ai-prepublish-section {
    min-height: 0;
    padding: 12px;
  }

  .creator-ai-main-workspace {
    min-height: 560px;
  }

  .creator-ai-chat-workspace,
  .creator-ai-plan-workspace {
    height: auto;
  }

  .creator-ai-chat-workspace {
    min-height: 560px;
  }

  .creator-ai-plan-workspace,
  .creator-ai-plan-workspace :deep(.pre-publish-suggestion-panel) {
    height: 560px;
  }

  .creator-ai-message-list {
    max-height: 420px;
  }

  .creator-ai-generation-bar {
    position: static;
  }
}

@media (max-width: 640px) {
  .creator-ai-prepublish-head,
  .creator-ai-generation-bar,
  .creator-ai-composer {
    align-items: stretch;
    flex-direction: column;
  }

  .creator-settings-trigger,
  .creator-send-action,
  .creator-generate-action {
    width: 100%;
    min-width: 0;
  }

  .creator-ai-compact-status {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .creator-ai-compact-status p {
    grid-column: 1 / -1;
    grid-row: 2;
  }

  .creator-ai-message {
    width: 94%;
  }

  .creator-ai-material-note {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .creator-ai-material-note button {
    grid-column: 1 / -1;
    width: 100%;
  }

  .creator-preference-modes,
  .creator-settings-form {
    grid-template-columns: 1fr;
  }

  .creator-settings-form label:first-child,
  .creator-settings-form label:nth-child(4) {
    grid-column: auto;
  }

  .creator-settings-footer {
    align-items: stretch;
    flex-direction: column;
  }

  .creator-settings-footer > span {
    display: none;
  }

  .creator-settings-footer .creator-primary-button {
    width: 100%;
  }
}
</style>
