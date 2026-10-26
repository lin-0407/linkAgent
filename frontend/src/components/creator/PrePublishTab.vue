<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { FileText, ListTree, Save, Send, Settings2, Sparkles, X } from '@lucide/vue'
import PrePublishSuggestionPanel from '@/components/creator/PrePublishSuggestionPanel.vue'
import CreatorStageState from '@/components/creator/CreatorStageState.vue'
import {
  formatDate,
  getLatestWorkflowFailedStep,
  workflowRoleLabel,
} from '@/composables/creator/creatorWorkspaceUtils'
import { useCreatorWorkspaceShell } from '@/composables/creator/useCreatorWorkspaceContext'

const {
  suggestion,
  selectedTask,
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
const sourceManuscript = computed(
  () =>
    selectedTask.value?.materials
      .filter(
        (material) =>
          material.materialType === 'MANUSCRIPT' || material.materialType === 'SUBTITLE',
      )
      .map((material) => (material.content || '').trim())
      .filter(Boolean)
      .join('\n\n') || '',
)
const visibleWorkflowMessages = computed(() => {
  const collaborationMessages = workflowMessages.value.filter(
    (message) => message.role === 'USER' || message.role === 'AGENT' || message.role === 'RESULT',
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
  if (!hasPrePublishSettingsChanges.value && prePublishSettingsSaveState.value === 'saved')
    return '已保存'
  if (prePublishSettingsErrorSource.value === 'save') return '重试保存'
  return '保存本次设置'
})
const generationDisabled = computed(
  () =>
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
  <section class="studio-collab-prepublish-section" aria-label="发布前优化">
    <header class="studio-collab-prepublish-head">
      <div>
        <h3>发布方案</h3>
        <p class="creator-stage-description">AI 整理标题、简介与内容建议，你来决定采用哪一版。</p>
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
      class="studio-collab-compact-status"
      :class="{ failed: currentWorkflowFailedStep }"
      role="status"
      aria-live="polite"
    >
      <span
        class="studio-collab-connection-status"
        :class="{ active: workflowSseText === '实时连接' }"
      >
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

    <nav class="studio-collab-view-tabs" aria-label="沟通与作品视图">
      <button
        type="button"
        :class="{ active: activeView === 'chat' }"
        :aria-pressed="activeView === 'chat'"
        @click="activeView = 'chat'"
      >
        AI 沟通
      </button>
      <button
        type="button"
        :class="{ active: activeView === 'plan' }"
        :aria-pressed="activeView === 'plan'"
        @click="activeView = 'plan'"
      >
        {{ suggestion ? '发布方案' : '创作底稿' }}
      </button>
    </nav>

    <div
      class="studio-collab-main-workspace studio-dual-workspace"
      :class="{ 'has-plan': Boolean(suggestion) }"
    >
      <section
        class="studio-collab-chat-workspace"
        :class="{ 'is-selected': activeView === 'chat' }"
        aria-label="AI 沟通"
      >
        <div class="studio-pane-heading">
          <span class="studio-agent-mark" aria-hidden="true">L</span
          ><strong>一起把方向说清楚</strong>
        </div>
        <div
          ref="messageListRef"
          class="studio-collab-message-list"
          role="region"
          aria-label="创作沟通记录"
          tabindex="0"
          aria-live="polite"
        >
          <article
            v-for="message in visibleWorkflowMessages"
            :key="message.messageId"
            class="studio-collab-message"
            :class="`role-${message.role.toLowerCase()}`"
          >
            <header>
              <strong>{{ workflowRoleLabel(message.role) }}</strong
              ><time>{{ formatDate(message.createTime) }}</time>
            </header>
            <p>{{ message.content }}</p>
          </article>
          <CreatorStageState
            v-if="!visibleWorkflowMessages.length"
            :state="isLoadingWorkflow ? 'running' : 'waiting'"
            :title="isLoadingWorkflow ? '正在读取此前的沟通' : '哪些内容，是你想保留的？'"
            description="说说受众、表达方式或需要注意的细节。AI 会结合右侧底稿整理方案。"
          />
        </div>
        <form
          v-if="!isActiveStepReadOnly && !hasConfirmedPrePublish"
          class="studio-collab-composer"
          @submit.prevent="sendMessage"
        >
          <label
            ><span class="sr-only">给 AI 补充信息</span
            ><textarea
              :value="workflowMessageDraft"
              maxlength="2000"
              :disabled="isActiveStepReadOnly || isSendingWorkflowMessage"
              placeholder="告诉 AI 你的想法，或补充需要调整的地方…"
              @input="updateMessageDraft"
            ></textarea>
          </label>
          <div class="studio-composer-bottom">
            <span>你的判断，决定作品的方向</span
            ><button
              type="submit"
              class="creator-primary-button creator-send-action"
              :disabled="
                isActiveStepReadOnly ||
                !canSendWorkflowMessage ||
                !workflowMessageDraft.trim() ||
                isSendingWorkflowMessage
              "
            >
              <Send :size="15" aria-hidden="true" />{{
                isSendingWorkflowMessage ? '发送中' : '发送'
              }}
            </button>
          </div>
        </form>
        <div v-else class="studio-conversation-complete">方案已采用，沟通记录保留在这里。</div>
      </section>
      <section
        class="studio-collab-plan-workspace"
        :class="{ 'is-selected': activeView === 'plan' }"
        aria-label="作品内容"
      >
        <PrePublishSuggestionPanel v-if="suggestion" />
        <article v-else class="studio-source-document">
          <header class="studio-document-heading">
            <FileText :size="18" aria-hidden="true" /><strong>创作底稿</strong
            ><span>{{ hasPrePublishScriptMaterial ? '可生成方案' : '等待完善文稿' }}</span>
          </header>
          <div class="studio-document-body" role="region" aria-label="当前创作文稿" tabindex="0">
            <span class="studio-document-type">{{ selectedTask?.videoType || '本期视频' }}</span>
            <h2>{{ selectedTask?.taskName }}</h2>
            <p v-if="sourceManuscript" class="studio-manuscript">{{ sourceManuscript }}</p>
            <p v-else class="studio-document-placeholder">
              你提供的文稿和 AI 整理的内容将在这里逐步成形。可以回到“内容与资料”补充，也可以先与 AI
              沟通。
            </p>
          </div>
          <footer
            v-if="!hasPrePublishScriptMaterial && !hasConfirmedPrePublish"
            class="studio-document-footer"
          >
            <div>
              <strong>先准备好完整文稿</strong><span>结合已有资料与本轮沟通，生成可编辑草稿。</span>
            </div>
            <button
              type="button"
              class="creator-primary-button"
              :disabled="
                isActiveStepReadOnly || !canGeneratePrePublishDraft || isGeneratingPrePublishDraft
              "
              @click="generateDraftFromConversation"
            >
              <Sparkles :size="16" aria-hidden="true" />{{
                isGeneratingPrePublishDraft ? '补稿中…' : '让 AI 补全文稿'
              }}
            </button>
          </footer>
        </article>
      </section>
    </div>

    <footer
      v-if="!hasConfirmedPrePublish && !isActiveStepReadOnly && hasPrePublishScriptMaterial"
      class="studio-collab-generation-bar"
    >
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
                    :disabled="
                      isActiveStepReadOnly ||
                      isLoadingPrePublishSettings ||
                      isSavingPrePublishSettings
                    "
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
                <p
                  v-if="prePublishForm.preferenceMode === 'IGNORE_HISTORY'"
                  class="creator-history-state"
                >
                  本期不使用历史偏好
                </p>
                <p v-else-if="isLoadingCreatorPreferences" class="creator-history-state">
                  正在读取历史偏好...
                </p>
                <div v-else-if="creatorPreferencesError" class="creator-history-state is-error">
                  <span>历史偏好读取失败</span>
                  <button
                    type="button"
                    class="creator-ghost-button creator-mini-button"
                    @click="retryCreatorPreferences"
                  >
                    重新读取
                  </button>
                </div>
                <p v-else-if="historicalPreferenceCount === 0" class="creator-history-state">
                  暂无历史偏好
                </p>
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
                      :disabled="
                        isActiveStepReadOnly ||
                        isLoadingPrePublishSettings ||
                        isSavingPrePublishSettings
                      "
                      placeholder="这期最想让观众记住什么？"
                    ></textarea>
                  </label>
                  <label>
                    <span>标题风格</span>
                    <input
                      v-model="prePublishForm.titleStyle"
                      type="text"
                      maxlength="100"
                      :disabled="
                        isActiveStepReadOnly ||
                        isLoadingPrePublishSettings ||
                        isSavingPrePublishSettings
                      "
                      placeholder="克制、教程感、故事感等"
                    />
                  </label>
                  <label>
                    <span>本期额外要求</span>
                    <textarea
                      v-model="prePublishForm.extraRequirement"
                      maxlength="500"
                      :disabled="
                        isActiveStepReadOnly ||
                        isLoadingPrePublishSettings ||
                        isSavingPrePublishSettings
                      "
                      placeholder="补充标题、简介或标签要求"
                    ></textarea>
                  </label>
                  <label>
                    <span>其它发布前语境</span>
                    <textarea
                      v-model="prePublishForm.customGuidance"
                      maxlength="2000"
                      :disabled="
                        isActiveStepReadOnly ||
                        isLoadingPrePublishSettings ||
                        isSavingPrePublishSettings
                      "
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
              <p
                v-else-if="!hasPrePublishSettingsChanges && prePublishSettingsSaveState === 'saved'"
              >
                本次设置已保存
              </p>
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
                :disabled="
                  isActiveStepReadOnly ||
                  isLoadingPrePublishSettings ||
                  isSavingPrePublishSettings ||
                  (!hasPrePublishSettingsChanges && prePublishSettingsErrorSource !== 'save')
                "
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
  font-size: var(--fs-xs);
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
  font-size: var(--fs-sm);
}

.creator-send-action {
  min-width: 124px;
  min-height: 42px;
}

.creator-generate-action {
  flex: 0 0 auto;
  min-width: 210px;
  min-height: 44px;
}

.creator-settings-backdrop {
  position: fixed;
  inset: 0;
  z-index: var(--z-modal);
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
  font-size: var(--fs-xs);
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
  border-radius: var(--r-xs);
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
  font-size: var(--fs-base);
}

.creator-settings-section header span {
  color: var(--muted);
  font-size: var(--fs-xs);
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
  border-radius: var(--r-sm);
  cursor: pointer;
}

.creator-preference-modes button.active {
  color: var(--accent-strong);
  background: var(--accent-tint);
  border-color: var(--accent-ring);
}

.creator-preference-modes button span {
  font-size: var(--fs-sm);
  font-weight: 700;
}

.creator-preference-modes button small {
  color: var(--muted);
  font-size: var(--fs-2xs);
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
  font-size: var(--fs-sm);
}

.creator-history-state.is-error {
  color: var(--danger);
  border-left-color: var(--danger);
}

.creator-history-details > summary {
  min-height: 40px;
  color: var(--ink);
  cursor: pointer;
  font-size: var(--fs-sm);
  font-weight: 700;
}

.creator-history-details > section {
  padding: 12px 0;
  border-top: 1px solid var(--border);
}

.creator-history-details h4 {
  margin: 0 0 8px;
  color: var(--ink);
  font-size: var(--fs-sm);
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
  font-size: var(--fs-sm);
  line-height: 1.5;
}

.creator-history-details small {
  color: var(--muted);
  font-size: var(--fs-2xs);
}

.creator-history-developer-detail summary {
  color: var(--muted);
  cursor: pointer;
  font-size: var(--fs-2xs);
}

.creator-history-developer-detail code,
.creator-history-developer-detail pre {
  display: block;
  max-width: 100%;
  margin: 5px 0 0;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  color: var(--muted);
  font-size: var(--fs-2xs);
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
  font-size: var(--fs-xs);
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
  border-radius: var(--r-sm);
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
  font-size: var(--fs-xs);
}

.creator-context-tags em {
  color: var(--muted);
  font-size: var(--fs-xs);
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
  font-size: var(--fs-xs);
}

.creator-settings-footer > span {
  flex: 1 1 auto;
}

.creator-settings-footer .creator-primary-button {
  flex: 0 0 auto;
  min-width: 152px;
}

@media (max-width: 640px) {
  .creator-settings-trigger,
  .creator-send-action,
  .creator-generate-action {
    width: 100%;
    min-width: 0;
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
