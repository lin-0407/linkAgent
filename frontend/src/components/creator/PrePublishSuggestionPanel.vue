<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import SuggestionCard from '@/components/creator/SuggestionCard.vue'
import { useCreatorWorkspaceContext, useCreatorWorkspaceShell } from '@/composables/creator/useCreatorWorkspaceContext'
import {
  contextSaveKey,
  formatValue,
  getLatestWorkflowFailedStep,
  getRecordText,
  parseJsonArray,
} from '@/composables/creator/creatorWorkspaceUtils'
import type {
  CreatorContextTermType,
  CreatorEventType,
  CreatorRejectReason,
} from '@/types/creator'

type SuggestionCardItem = {
  content: string
  viewerPsychology?: string
  clickReason?: string
  trustRisk?: string
  bestScenario?: string
  reason?: string
  risk?: string
}

const { feedbackEvent } = useCreatorWorkspaceContext()
const {
  suggestion,
  hasPrePublishScriptMaterial,
  hasConfirmedPrePublish,
  canRunPrePublishAnalyze,
  isAnalyzingPrePublish,
  runPrePublishAnalyze,
  canGeneratePrePublishDraft,
  isGeneratingPrePublishDraft,
  generatePrePublishManuscriptDraftForCurrentTask,
  canConfirmPrePublish,
  isConfirmingPrePublish,
  confirmPrePublishResult,
  isActiveStepReadOnly,
  selectedTaskId,
  isSavingCreatorContextTerm,
  savingContextTermKey,
  saveContextTermFromSuggestion,
  hasPrePublishPreferenceModeSnapshot,
  lastPreferenceModeLabel,
  lastPreferenceModeNote,
  workflowSteps,
} = useCreatorWorkspaceShell()

const draftRequirement = ref('')
const suggestionPanelBodyRef = ref<HTMLDivElement | null>(null)
const acceptedTitleContents = ref<Set<string>>(new Set())
const acceptedTagContents = ref<Set<string>>(new Set())

const sellingPoints = computed(() => parseJsonArray(suggestion.value?.sellingPoints))
const riskPoints = computed(() => parseJsonArray(suggestion.value?.riskPoints))
const titleSuggestions = computed(() => parseJsonArray(suggestion.value?.titleSuggestions))
const actionableRevisionPlan = computed(() =>
  parseJsonArray(suggestion.value?.actionableRevisionPlan),
)
const tagSuggestions = computed(() => parseJsonArray(suggestion.value?.tagSuggestions))

const currentWorkflowFailedStep = computed(() => getLatestWorkflowFailedStep(workflowSteps.value))

const titleSuggestionCards = computed<SuggestionCardItem[]>(() =>
  titleSuggestions.value.map((raw) => ({
    content: getRecordText(raw, 'title') || formatValue(raw),
    viewerPsychology: getRecordText(raw, 'viewerPsychology') || undefined,
    clickReason: getRecordText(raw, 'clickReason') || undefined,
    trustRisk: getRecordText(raw, 'trustRisk') || undefined,
    bestScenario: getRecordText(raw, 'bestScenario') || undefined,
    reason: getRecordText(raw, 'reason') || undefined,
    risk: getRecordText(raw, 'risk') || undefined,
  })),
)

const tagSuggestionCards = computed<SuggestionCardItem[]>(() =>
  tagSuggestions.value.map((raw) => ({ content: formatValue(raw) })),
)

const panelStatus = computed(() => {
  if (hasConfirmedPrePublish.value) {
    return '已采用'
  }
  if (currentWorkflowFailedStep.value) {
    return suggestion.value ? '新版生成失败' : '生成失败'
  }
  if (isAnalyzingPrePublish.value && suggestion.value) {
    return '正在生成新版'
  }
  if (suggestion.value) {
    return '待采用'
  }
  if (!hasPrePublishScriptMaterial.value) {
    return '缺少完整文稿'
  }
  return isAnalyzingPrePublish.value ? '正在生成' : '等待生成'
})

const preferenceSnapshotNote = computed(() => {
  if (!hasPrePublishPreferenceModeSnapshot.value) {
    return '历史结果未保存偏好使用方式；重新生成后会记录。'
  }
  return lastPreferenceModeNote.value
})

async function resetSuggestionScroll() {
  await nextTick()
  if (suggestionPanelBodyRef.value) {
    suggestionPanelBodyRef.value.scrollTop = 0
  }
}

// 新方案替换旧方案时清空局部采纳标记并回到顶部，避免相同文案和滚动位置沿用上一轮。
watch(
  () => suggestion.value?.suggestionId,
  () => {
    acceptedTitleContents.value = new Set()
    acceptedTagContents.value = new Set()
    void resetSuggestionScroll()
  },
)

watch(isAnalyzingPrePublish, (isAnalyzing, wasAnalyzing) => {
  if (wasAnalyzing && !isAnalyzing && suggestion.value) {
    void resetSuggestionScroll()
  }
})

async function generateDraft() {
  if (isActiveStepReadOnly.value) {
    return
  }
  const generated = await generatePrePublishManuscriptDraftForCurrentTask(draftRequirement.value)
  if (generated) {
    draftRequirement.value = ''
  }
}

async function generatePlan() {
  if (isActiveStepReadOnly.value) {
    return
  }
  await runPrePublishAnalyze()
}

async function confirmPlan() {
  if (isActiveStepReadOnly.value) {
    return
  }
  await confirmPrePublishResult()
}

async function acceptSuggestion(
  type: 'title' | 'tag',
  item: SuggestionCardItem,
  rank?: number,
) {
  const eventType: Extract<CreatorEventType, 'TITLE_ACCEPTED' | 'TAG_ACCEPTED'> =
    type === 'title' ? 'TITLE_ACCEPTED' : 'TAG_ACCEPTED'
  const accepted = await feedbackEvent.reportAccept(
    eventType,
    selectedTaskId.value,
    item.content,
    rank,
  )
  if (accepted) {
    const acceptedContents = type === 'title' ? acceptedTitleContents : acceptedTagContents
    acceptedContents.value.add(item.content)
  }
}

async function copySuggestion(item: SuggestionCardItem) {
  try {
    await navigator.clipboard.writeText(item.content)
  } catch {
    // 浏览器在非 HTTPS 或失焦时可能拒绝剪贴板写入，卡片仍保留自身的轻量反馈。
  }
}

function rejectSuggestion(
  type: 'title' | 'tag',
  item: SuggestionCardItem,
  reason: CreatorRejectReason,
  reasonText: string,
  rank?: number,
) {
  const eventType: Extract<CreatorEventType, 'TITLE_REJECTED' | 'TAG_REJECTED'> =
    type === 'title' ? 'TITLE_REJECTED' : 'TAG_REJECTED'
  void feedbackEvent.reportReject(eventType, selectedTaskId.value, item.content, reason, reasonText, rank)
}

function saveSuggestionContext(
  term: string,
  termType: CreatorContextTermType,
  evidenceText?: string,
) {
  void saveContextTermFromSuggestion(term, termType, evidenceText)
}
</script>

<template>
  <section class="pre-publish-suggestion-panel" aria-label="发布方案内容">
    <header class="suggestion-panel-head">
      <div>
        <span>发布方案</span>
        <strong>{{ panelStatus }}</strong>
      </div>
      <b
        class="suggestion-status-badge"
        :class="{
          confirmed: hasConfirmedPrePublish,
          pending: suggestion && !hasConfirmedPrePublish && !isAnalyzingPrePublish,
          running: isAnalyzingPrePublish,
          failed: currentWorkflowFailedStep,
        }"
      >
        {{ panelStatus }}
      </b>
    </header>

    <div ref="suggestionPanelBodyRef" class="suggestion-panel-body">
      <section v-if="!suggestion && !hasPrePublishScriptMaterial" class="suggestion-empty-state">
        <div>
          <strong>还缺完整文稿或字幕</strong>
          <p>补齐可分析的内容后，标题、简介和标签建议才能贴合视频本身。</p>
        </div>
        <textarea
          v-model="draftRequirement"
          maxlength="1000"
          :disabled="isActiveStepReadOnly"
          placeholder="让 AI 补稿时的额外要求，例如节奏、口播语气、必须保留的观点"
        ></textarea>
        <button
          type="button"
          class="creator-primary-button"
          :disabled="
            isActiveStepReadOnly ||
            !canGeneratePrePublishDraft ||
            isGeneratingPrePublishDraft
          "
          @click="generateDraft"
        >
          {{ isGeneratingPrePublishDraft ? '补稿中...' : '让 AI 补一版' }}
        </button>
      </section>

      <section v-else-if="!suggestion && isAnalyzingPrePublish" class="suggestion-loading-state">
        <div class="suggestion-skeleton title"></div>
        <div class="suggestion-skeleton"></div>
        <div class="suggestion-skeleton short"></div>
        <button type="button" class="creator-primary-button" disabled>生成中...</button>
      </section>

      <section v-else-if="!suggestion && currentWorkflowFailedStep" class="suggestion-empty-state failed">
        <div>
          <strong>生成没有完成</strong>
          <p>失败步骤：{{ currentWorkflowFailedStep.stepName || '未知步骤' }}。请重新生成后再确认方案。</p>
        </div>
        <button
          type="button"
          class="creator-primary-button"
          :disabled="isActiveStepReadOnly || !canRunPrePublishAnalyze || isAnalyzingPrePublish"
          @click="generatePlan"
        >
          重新生成
        </button>
      </section>

      <section v-else-if="!suggestion" class="suggestion-empty-state">
        <div>
          <strong>材料已就绪</strong>
          <p>AI 会结合当前材料、偏好记忆和消息流中的补充要求生成发布方案。</p>
        </div>
        <button
          type="button"
          class="creator-primary-button"
          :disabled="isActiveStepReadOnly || !canRunPrePublishAnalyze || isAnalyzingPrePublish"
          @click="generatePlan"
        >
          {{ isAnalyzingPrePublish ? '生成中...' : '生成发布方案' }}
        </button>
      </section>

      <template v-else>
        <p v-if="currentWorkflowFailedStep" class="suggestion-refresh-failure">
          新版生成未完成，当前保留上一轮方案。失败步骤：{{ currentWorkflowFailedStep.stepName || '未知步骤' }}。
        </p>

        <section class="suggestion-content-section suggestion-title-section">
          <header>
            <span>标题建议{{ titleSuggestionCards.length ? `（${titleSuggestionCards.length} 个）` : '' }}</span>
          </header>
          <p v-if="titleSuggestionCards.length === 0" class="creator-muted">未解析到标题建议</p>
          <div v-else class="suggestion-card-grid">
            <SuggestionCard
              v-for="(cardItem, index) in titleSuggestionCards"
              :key="`${suggestion.suggestionId}-title-${index}`"
              type="title"
              :item="cardItem"
              :rank="index + 1"
              :feedback-enabled="!isActiveStepReadOnly && !isConfirmingPrePublish"
              :reporting="feedbackEvent.isReporting(cardItem.content, 'TITLE_ACCEPTED')"
              :accepted="acceptedTitleContents.has(cardItem.content)"
              @accept="acceptSuggestion('title', $event, index + 1)"
              @copy="copySuggestion"
              @reject="(item, reason, reasonText) => rejectSuggestion('title', item, reason, reasonText, index + 1)"
            >
              <template v-if="!isActiveStepReadOnly && !isConfirmingPrePublish" #secondary-actions>
                <button
                  type="button"
                  class="creator-ghost-button creator-mini-button"
                  :disabled="
                    isSavingCreatorContextTerm &&
                    savingContextTermKey === contextSaveKey(cardItem.content, 'TITLE_PATTERN')
                  "
                  @click="saveSuggestionContext(cardItem.content, 'TITLE_PATTERN', cardItem.reason || cardItem.clickReason)"
                >
                  保存为标题套路
                </button>
              </template>
            </SuggestionCard>
          </div>
        </section>

        <section class="suggestion-content-section">
          <header>
            <span>简介建议</span>
          </header>
          <p>{{ suggestion.descriptionSuggestion || '未解析到简介建议' }}</p>
        </section>

        <section class="suggestion-content-section suggestion-tag-section">
          <header>
            <span>标签与分区</span>
            <small v-if="suggestion.partitionSuggestion">推荐分区：{{ suggestion.partitionSuggestion }}</small>
          </header>
          <p v-if="tagSuggestionCards.length === 0" class="creator-muted">未解析到标签建议</p>
          <div v-else class="suggestion-card-grid suggestion-card-grid-tags">
            <SuggestionCard
              v-for="(cardItem, index) in tagSuggestionCards"
              :key="`${suggestion.suggestionId}-tag-${index}`"
              type="tag"
              :item="cardItem"
              :rank="index + 1"
              :feedback-enabled="!isActiveStepReadOnly && !isConfirmingPrePublish"
              :reporting="feedbackEvent.isReporting(cardItem.content, 'TAG_ACCEPTED')"
              :accepted="acceptedTagContents.has(cardItem.content)"
              @accept="acceptSuggestion('tag', $event, index + 1)"
              @copy="copySuggestion"
              @reject="(item, reason, reasonText) => rejectSuggestion('tag', item, reason, reasonText, index + 1)"
            >
              <template v-if="!isActiveStepReadOnly && !isConfirmingPrePublish" #secondary-actions>
                <button
                  type="button"
                  class="creator-ghost-button creator-mini-button"
                  :disabled="
                    isSavingCreatorContextTerm &&
                    savingContextTermKey === contextSaveKey(cardItem.content, 'KEYWORD')
                  "
                  @click="saveSuggestionContext(cardItem.content, 'KEYWORD', '来自发布前优化的标签建议')"
                >
                  存为关键词
                </button>
              </template>
            </SuggestionCard>
          </div>
        </section>

        <details class="suggestion-disclosure">
          <summary>可执行修改计划</summary>
          <p v-if="actionableRevisionPlan.length === 0" class="creator-muted">未解析到可执行修改计划</p>
          <div v-else class="suggestion-plan-list">
            <section v-for="(item, index) in actionableRevisionPlan" :key="index">
              <strong>
                {{
                  getRecordText(item, 'target') ||
                  getRecordText(item, 'priority') ||
                  formatValue(item)
                }}
              </strong>
              <p v-if="getRecordText(item, 'problem')">问题：{{ getRecordText(item, 'problem') }}</p>
              <p v-if="getRecordText(item, 'action')">动作：{{ getRecordText(item, 'action') }}</p>
              <p v-if="getRecordText(item, 'expectedEffect')">
                预期效果：{{ getRecordText(item, 'expectedEffect') }}
              </p>
            </section>
          </div>
        </details>

        <details class="suggestion-disclosure">
          <summary>受众、卖点与风险</summary>
          <div class="suggestion-insight-grid">
            <section>
              <span>目标受众</span>
              <p>{{ suggestion.audienceProfile || '未解析到受众判断' }}</p>
              <button
                v-if="suggestion.audienceProfile && !isActiveStepReadOnly && !isConfirmingPrePublish"
                type="button"
                class="creator-ghost-button creator-mini-button"
                :disabled="isSavingCreatorContextTerm || isConfirmingPrePublish"
                @click="saveSuggestionContext(suggestion.audienceProfile, 'AUDIENCE_CONCERN', '来自发布前优化的目标受众判断')"
              >
                保存为观众关注点
              </button>
            </section>
            <section>
              <span>观众钩子</span>
              <p>{{ suggestion.audienceHook || '未解析到观众钩子' }}</p>
              <button
                v-if="suggestion.audienceHook && !isActiveStepReadOnly && !isConfirmingPrePublish"
                type="button"
                class="creator-ghost-button creator-mini-button"
                :disabled="isSavingCreatorContextTerm || isConfirmingPrePublish"
                @click="saveSuggestionContext(suggestion.audienceHook, 'AUDIENCE_CONCERN', '来自发布前优化的观众钩子判断')"
              >
                保存为观众关注点
              </button>
            </section>
            <section>
              <span>核心卖点</span>
              <ul v-if="sellingPoints.length > 0">
                <li v-for="(item, index) in sellingPoints" :key="index">{{ formatValue(item) }}</li>
              </ul>
              <p v-else>未解析到核心卖点</p>
            </section>
            <section>
              <span>风险点</span>
              <ul v-if="riskPoints.length > 0">
                <li v-for="(item, index) in riskPoints" :key="index">{{ formatValue(item) }}</li>
              </ul>
              <p v-else>未解析到风险点</p>
            </section>
          </div>
        </details>

        <details class="suggestion-disclosure">
          <summary>方案依据与本轮语境</summary>
          <div class="suggestion-insight-grid">
            <section>
              <span>内容摘要</span>
              <p>{{ suggestion.contentSummary || '未解析到摘要' }}</p>
            </section>
            <section>
              <span>创作者困境</span>
              <p>{{ suggestion.creatorDilemma || '未解析到创作者困境' }}</p>
            </section>
            <section>
              <span>内容定位</span>
              <p>{{ suggestion.contentPositioning || '未解析到内容定位' }}</p>
            </section>
            <section>
              <span>偏好使用方式</span>
              <strong>{{ hasPrePublishPreferenceModeSnapshot ? lastPreferenceModeLabel : '未记录生成方式' }}</strong>
              <p>{{ preferenceSnapshotNote }}</p>
            </section>
          </div>
        </details>
      </template>
    </div>

    <footer v-if="suggestion && !hasConfirmedPrePublish" class="suggestion-panel-actions">
      <button
        type="button"
        class="creator-secondary-action"
        :disabled="isActiveStepReadOnly || !canRunPrePublishAnalyze || isAnalyzingPrePublish || isConfirmingPrePublish"
        @click="generatePlan"
      >
        {{ isAnalyzingPrePublish ? '重新生成中...' : '重新生成' }}
      </button>
      <button
        type="button"
        class="creator-primary-button"
        :disabled="isActiveStepReadOnly || !canConfirmPrePublish || isConfirmingPrePublish || isAnalyzingPrePublish"
        @click="confirmPlan"
      >
        {{ isConfirmingPrePublish ? '确认中...' : '采用本轮方案' }}
      </button>
    </footer>
  </section>
</template>

<style scoped>
.pre-publish-suggestion-panel {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  min-width: 0;
  min-height: 0;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 8px;
  box-shadow: var(--sh-sm);
}

.suggestion-panel-head,
.suggestion-content-section > header,
.suggestion-panel-actions,
.suggestion-disclosure summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.suggestion-panel-head {
  min-height: 65px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--border);
}

.suggestion-panel-head > div {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.suggestion-panel-head span,
.suggestion-content-section > header,
.suggestion-insight-grid span {
  color: var(--muted);
  font-size: 12px;
  font-weight: var(--fw-semibold);
}

.suggestion-panel-head strong {
  overflow-wrap: anywhere;
  color: var(--ink);
  font-size: 17px;
}

.suggestion-status-badge {
  flex: 0 0 auto;
  padding: 4px 8px;
  color: var(--muted);
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  font-size: 12px;
}

.suggestion-status-badge.pending {
  color: var(--accent-strong);
  border-color: var(--accent-ring);
}

.suggestion-status-badge.confirmed {
  color: #087a3d;
  background: rgba(34, 197, 94, 0.12);
  border-color: rgba(34, 197, 94, 0.24);
}

.suggestion-status-badge.running {
  color: var(--accent-strong);
  background: var(--accent-tint);
  border-color: var(--accent-ring);
}

.suggestion-status-badge.failed {
  color: var(--danger);
  border-color: rgba(220, 38, 38, 0.24);
}

.suggestion-panel-body {
  min-height: 0;
  padding: 0 14px 104px;
  overflow-x: hidden;
  overflow-y: scroll;
  overscroll-behavior: contain;
  scrollbar-color: rgba(104, 117, 136, 0.5) transparent;
  scrollbar-gutter: stable;
  scrollbar-width: thin;
}

.suggestion-panel-body::-webkit-scrollbar {
  width: 8px;
}

.suggestion-panel-body::-webkit-scrollbar-track {
  background: transparent;
}

.suggestion-panel-body::-webkit-scrollbar-thumb {
  background: rgba(104, 117, 136, 0.5);
  background-clip: content-box;
  border: 2px solid transparent;
  border-radius: var(--r-pill);
}

.suggestion-empty-state,
.suggestion-loading-state {
  display: grid;
  align-content: center;
  justify-items: start;
  min-height: 360px;
  gap: 16px;
  padding: 24px 0;
}

.suggestion-empty-state > div {
  display: grid;
  gap: 8px;
}

.suggestion-empty-state strong {
  color: var(--ink);
  font-size: 16px;
}

.suggestion-empty-state p,
.suggestion-content-section p,
.suggestion-insight-grid p,
.suggestion-plan-list p {
  margin: 0;
  color: var(--text);
  line-height: 1.65;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.suggestion-empty-state.failed strong {
  color: var(--danger);
}

.suggestion-refresh-failure {
  margin: 16px 0 0;
  padding: 10px 12px;
  color: var(--danger);
  background: rgba(220, 38, 38, 0.06);
  border: 1px solid rgba(220, 38, 38, 0.18);
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.suggestion-empty-state textarea {
  width: min(100%, 640px);
  min-height: 116px;
  padding: 11px 12px;
  color: var(--text);
  resize: vertical;
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: 8px;
  outline: none;
  line-height: 1.55;
}

.suggestion-empty-state textarea:focus-visible {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-ring);
}

.suggestion-skeleton {
  width: 100%;
  height: 112px;
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: 8px;
}

.suggestion-skeleton.title {
  height: 164px;
}

.suggestion-skeleton.short {
  width: 58%;
  height: 68px;
}

.suggestion-content-section {
  display: grid;
  gap: 10px;
  padding: 16px 0;
  border-bottom: 1px solid var(--border);
}

.suggestion-content-section > header small {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.45;
  text-align: right;
}

.suggestion-card-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.suggestion-card-grid-tags {
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
}

.suggestion-disclosure {
  padding: 14px 0;
  border-bottom: 1px solid var(--border);
}

.suggestion-disclosure summary {
  color: var(--ink);
  cursor: pointer;
  font-size: 14px;
  font-weight: var(--fw-semibold);
}

.suggestion-disclosure summary::marker {
  color: var(--muted);
}

.suggestion-disclosure summary:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 3px;
}

.suggestion-disclosure > :not(summary) {
  margin-top: 14px;
}

.suggestion-plan-list {
  display: grid;
  gap: 10px;
}

.suggestion-plan-list section {
  display: grid;
  gap: 5px;
  padding: 12px;
  background: var(--surface-sub);
  border-left: 2px solid var(--accent-ring);
}

.suggestion-plan-list strong,
.suggestion-insight-grid strong {
  color: var(--ink);
  line-height: 1.5;
}

.suggestion-insight-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.suggestion-insight-grid section {
  display: grid;
  align-content: start;
  gap: 8px;
  min-width: 0;
}

.suggestion-insight-grid ul {
  display: grid;
  gap: 6px;
  margin: 0;
  padding-left: 18px;
  color: var(--text);
  line-height: 1.6;
}

.suggestion-panel-actions {
  position: sticky;
  bottom: 0;
  min-height: 65px;
  padding: 10px 14px;
  background: var(--surface);
  border-top: 1px solid var(--border);
  box-shadow: 0 -8px 18px rgba(23, 32, 51, 0.04);
}

.suggestion-panel-actions .creator-primary-button,
.suggestion-panel-actions .creator-secondary-action {
  min-height: 44px;
}

.suggestion-panel-actions .creator-primary-button {
  margin-left: auto;
}

@media (max-width: 1279px) {
  .pre-publish-suggestion-panel {
    min-height: 620px;
  }
}

@media (max-width: 980px) {
  .pre-publish-suggestion-panel {
    min-height: auto;
  }

  .suggestion-panel-body {
    overflow: visible;
  }

  .suggestion-empty-state,
  .suggestion-loading-state {
    min-height: 280px;
  }

  .suggestion-panel-actions {
    position: static;
  }
}

@media (max-width: 640px) {
  .suggestion-card-grid,
  .suggestion-insight-grid {
    grid-template-columns: 1fr;
  }

  .suggestion-content-section > header {
    align-items: flex-start;
    flex-direction: column;
  }

  .suggestion-content-section > header small {
    text-align: left;
  }
}

@media (max-width: 820px) {
  .suggestion-card-grid :deep(.creator-mini-button),
  .suggestion-insight-grid :deep(.creator-mini-button),
  .suggestion-empty-state .creator-primary-button,
  .suggestion-disclosure summary {
    min-height: 44px;
  }
}

@media (max-width: 480px) {
  .suggestion-panel-head,
  .suggestion-panel-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .suggestion-status-badge {
    align-self: flex-start;
  }

  .suggestion-panel-actions .creator-primary-button {
    margin-left: 0;
  }

  .suggestion-panel-actions .creator-primary-button,
  .suggestion-panel-actions .creator-secondary-action {
    width: 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .pre-publish-suggestion-panel,
  .suggestion-panel-actions {
    scroll-behavior: auto;
  }
}
</style>
