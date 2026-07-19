<script setup lang="ts">
import { ref, toRefs } from 'vue'
import {
  evalResultStatusLabel,
  evalStageLabel,
  formatDate,
  formatMetric,
  formatPercent,
} from '@/composables/creator/creatorWorkspaceUtils'
import type { CreatorEvaluationResultDraft } from '@/composables/creator/useCreatorEvaluation'
import type {
  CreatorEvalCase,
  CreatorEvalPromptVersionStats,
  CreatorEvalResult,
  CreatorWorkflowStage,
} from '@/types/creator'

type EvaluationStats = {
  total: number
  prePublish: number
  feedback: number
  report: number
}

const props = defineProps<{
  open: boolean
  stageFilter: 'ALL' | CreatorWorkflowStage
  stats: EvaluationStats
  filteredCases: CreatorEvalCase[]
  selectedCase: CreatorEvalCase | null
  results: CreatorEvalResult[]
  selectedResult: CreatorEvalResult | null
  promptVersionStats: CreatorEvalPromptVersionStats[]
  draft: CreatorEvaluationResultDraft
  loadingCases: boolean
  loadingResults: boolean
  recording: boolean
  canRecord: boolean
}>()

const emit = defineEmits<{
  close: []
  'update:stageFilter': [stage: 'ALL' | CreatorWorkflowStage]
  'update:selectedResultId': [resultId: string]
  'update:draft': [patch: Partial<CreatorEvaluationResultDraft>]
  reloadCases: []
  selectCase: [caseId: string]
  submitResult: []
  refreshResults: [caseId: string]
}>()

const {
  open,
  stageFilter,
  stats,
  filteredCases,
  selectedCase,
  results,
  selectedResult,
  promptVersionStats,
  draft,
  loadingCases,
  loadingResults,
  recording,
  canRecord,
} = toRefs(props)

const evalStageOptions: Array<{
  value: 'ALL' | CreatorWorkflowStage
  label: string
}> = [
  { value: 'ALL', label: '全部样例' },
  { value: 'PRE_PUBLISH', label: '发布前优化' },
  { value: 'FEEDBACK', label: '评论弹幕' },
  { value: 'REPORT', label: '复盘报告' },
]

const evalScoreOptions = [1, 2, 3, 4, 5]
const isBackdropPointerDown = ref(false)

type NullableNumberDraftField =
  | 'elapsedMs'
  | 'promptTokens'
  | 'completionTokens'
  | 'totalTokens'

type ScoreDraftField =
  | 'readabilityScore'
  | 'relevanceScore'
  | 'completenessScore'
  | 'accuracyScore'
  | 'stabilityScore'
  | 'costScore'
  | 'explainabilityScore'

function handleBackdropPointerDown(event: PointerEvent) {
  isBackdropPointerDown.value = event.target === event.currentTarget
}

function handleBackdropClick(event: MouseEvent) {
  if (isBackdropPointerDown.value && event.target === event.currentTarget) {
    emit('close')
    return
  }
  isBackdropPointerDown.value = false
}

function updateStageFilter(event: Event) {
  emit('update:stageFilter', (event.target as HTMLSelectElement).value as 'ALL' | CreatorWorkflowStage)
}

function updateTextDraft(field: keyof CreatorEvaluationResultDraft, event: Event) {
  emit('update:draft', { [field]: (event.target as HTMLInputElement | HTMLTextAreaElement).value })
}

function updateNullableNumberDraft(field: NullableNumberDraftField, event: Event) {
  const value = (event.target as HTMLInputElement).value
  emit('update:draft', { [field]: value === '' ? null : Number(value) })
}

function updateScoreDraft(field: ScoreDraftField, event: Event) {
  emit('update:draft', { [field]: Number((event.target as HTMLSelectElement).value) })
}
</script>

<template>
  <div
    v-if="open"
    class="creator-modal-backdrop creator-dev-test-backdrop"
    role="presentation"
    @pointerdown="handleBackdropPointerDown"
    @click="handleBackdropClick"
  >
    <section
      class="creator-result-modal creator-dev-test-modal"
      role="dialog"
      aria-modal="true"
      aria-label="开发者功能测试"
    >
      <header class="creator-result-modal-head creator-dev-test-head">
        <div>
          <h3>开发者功能测试</h3>
        </div>
        <div class="creator-action-row">
          <label class="creator-eval-filter">
            <span>阶段</span>
            <select :value="stageFilter" @change="updateStageFilter">
              <option
                v-for="option in evalStageOptions"
                :key="option.value"
                :value="option.value"
              >
                {{ option.label }}
              </option>
            </select>
          </label>
          <button
            type="button"
            class="creator-secondary-action"
            :disabled="loadingCases"
            @click="emit('reloadCases')"
          >
            {{ loadingCases ? '读取中...' : '刷新样例' }}
          </button>
          <button type="button" class="creator-ghost-button" @click="emit('close')">
            关闭
          </button>
        </div>
      </header>

      <div class="creator-result-modal-body creator-dev-test-body">
        <div class="creator-eval-overview" aria-label="评测样例概览">
          <span><b>{{ stats.total }}</b> 样例</span>
          <span><b>{{ stats.prePublish }}</b> 发布前</span>
          <span><b>{{ stats.feedback }}</b> 反馈</span>
          <span><b>{{ stats.report }}</b> 复盘</span>
        </div>

        <div class="creator-eval-grid">
          <section class="creator-eval-list-panel" aria-label="评测样例列表">
            <header class="creator-workflow-head">
              <div>
                <h4>样例列表</h4>
              </div>
              <span class="creator-parse-status">{{ filteredCases.length }} 个</span>
            </header>

            <div class="creator-eval-case-list">
              <button
                v-for="item in filteredCases"
                :key="item.caseId"
                type="button"
                class="creator-eval-case"
                :class="{ active: item.caseId === selectedCase?.caseId }"
                @click="emit('selectCase', item.caseId)"
              >
                <small>{{ evalStageLabel(item.targetStage) }} · {{ item.status }}</small>
                <strong>{{ item.caseName }}</strong>
                <span>{{ item.taskId || '未绑定任务' }}</span>
              </button>

              <p v-if="!loadingCases && filteredCases.length === 0" class="creator-muted">
                当前筛选条件下没有评测样例。
              </p>
            </div>
          </section>

          <section v-if="selectedCase" class="creator-eval-detail-panel" aria-label="评测样例详情">
            <header class="creator-workflow-head">
              <div>
                <h4>{{ selectedCase.caseName }}</h4>
              </div>
              <span class="creator-parse-status">{{ selectedCase.status }}</span>
            </header>

            <div class="creator-eval-snapshot">
              <span>输入快照</span>
              <pre>{{ selectedCase.inputSnapshot }}</pre>
              <span>期望要点</span>
              <pre>{{ selectedCase.expectedPoints || '未填写' }}</pre>
              <span>评分说明</span>
              <pre>{{ selectedCase.scoringRubric || '未填写' }}</pre>
            </div>

            <form class="creator-eval-result-form" @submit.prevent="emit('submitResult')">
              <header>
                <div>
                  <span>记录一次评测结果</span>
                  <strong>{{ evalStageLabel(draft.targetStage) }}</strong>
                </div>
                <button
                  type="submit"
                  class="creator-primary-button"
                  :disabled="!canRecord"
                >
                  {{ recording ? '记录中...' : '记录结果' }}
                </button>
              </header>

              <div class="creator-form-grid">
                <label>
                  <span>模型名称</span>
                  <input
                    :value="draft.modelName"
                    type="text"
                    maxlength="128"
                    @input="updateTextDraft('modelName', $event)"
                  />
                </label>
                <label>
                  <span>Prompt 版本</span>
                  <input
                    :value="draft.promptVersion"
                    type="text"
                    maxlength="64"
                    placeholder="例如 prepublish-v2"
                    @input="updateTextDraft('promptVersion', $event)"
                  />
                </label>
                <label>
                  <span>Prompt 哈希</span>
                  <input
                    :value="draft.promptHash"
                    type="text"
                    maxlength="64"
                    placeholder="可选，留空由后端根据快照计算"
                    @input="updateTextDraft('promptHash', $event)"
                  />
                </label>
                <label>
                  <span>关联任务</span>
                  <input
                    :value="draft.taskId"
                    type="text"
                    maxlength="64"
                    placeholder="可选"
                    @input="updateTextDraft('taskId', $event)"
                  />
                </label>
                <label>
                  <span>工作流会话</span>
                  <input
                    :value="draft.workflowSessionId"
                    type="text"
                    maxlength="64"
                    placeholder="可选"
                    @input="updateTextDraft('workflowSessionId', $event)"
                  />
                </label>
                <label>
                  <span>耗时毫秒</span>
                  <input
                    :value="draft.elapsedMs ?? ''"
                    type="number"
                    min="0"
                    @input="updateNullableNumberDraft('elapsedMs', $event)"
                  />
                </label>
                <label>
                  <span>Prompt Token</span>
                  <input
                    :value="draft.promptTokens ?? ''"
                    type="number"
                    min="1"
                    @input="updateNullableNumberDraft('promptTokens', $event)"
                  />
                </label>
                <label>
                  <span>Completion Token</span>
                  <input
                    :value="draft.completionTokens ?? ''"
                    type="number"
                    min="1"
                    @input="updateNullableNumberDraft('completionTokens', $event)"
                  />
                </label>
                <label class="span-full">
                  <span>Prompt 快照</span>
                  <textarea
                    :value="draft.promptSnapshot"
                    maxlength="20000"
                    placeholder="粘贴本轮 system prompt 和 user prompt，后续用于复现和版本对比"
                    @input="updateTextDraft('promptSnapshot', $event)"
                  ></textarea>
                </label>
                <label class="span-full">
                  <span>输出摘要</span>
                  <textarea
                    :value="draft.outputSummary"
                    maxlength="4000"
                    placeholder="概括这次输出的主要结论"
                    @input="updateTextDraft('outputSummary', $event)"
                  ></textarea>
                </label>
                <label class="span-full">
                  <span>模型原始输出</span>
                  <textarea
                    :value="draft.rawOutput"
                    maxlength="20000"
                    placeholder="粘贴本轮模型输出；失败时可以留空并填写失败原因"
                    @input="updateTextDraft('rawOutput', $event)"
                  ></textarea>
                </label>
                <label class="span-full">
                  <span>失败原因</span>
                  <textarea
                    :value="draft.failureReason"
                    maxlength="500"
                    placeholder="成功时可留空"
                    @input="updateTextDraft('failureReason', $event)"
                  ></textarea>
                </label>
              </div>

              <div class="creator-eval-score-grid" aria-label="人工评分">
                <label>
                  <span>可读性</span>
                  <select :value="draft.readabilityScore" @change="updateScoreDraft('readabilityScore', $event)">
                    <option v-for="score in evalScoreOptions" :key="`read-${score}`" :value="score">
                      {{ score }}
                    </option>
                  </select>
                </label>
                <label>
                  <span>贴合度</span>
                  <select :value="draft.relevanceScore" @change="updateScoreDraft('relevanceScore', $event)">
                    <option v-for="score in evalScoreOptions" :key="`rel-${score}`" :value="score">
                      {{ score }}
                    </option>
                  </select>
                </label>
                <label>
                  <span>完整性</span>
                  <select :value="draft.completenessScore" @change="updateScoreDraft('completenessScore', $event)">
                    <option v-for="score in evalScoreOptions" :key="`comp-${score}`" :value="score">
                      {{ score }}
                    </option>
                  </select>
                </label>
                <label>
                  <span>准确性</span>
                  <select :value="draft.accuracyScore" @change="updateScoreDraft('accuracyScore', $event)">
                    <option v-for="score in evalScoreOptions" :key="`acc-${score}`" :value="score">
                      {{ score }}
                    </option>
                  </select>
                </label>
                <label>
                  <span>稳定性</span>
                  <select :value="draft.stabilityScore" @change="updateScoreDraft('stabilityScore', $event)">
                    <option v-for="score in evalScoreOptions" :key="`sta-${score}`" :value="score">
                      {{ score }}
                    </option>
                  </select>
                </label>
                <label>
                  <span>成本</span>
                  <select :value="draft.costScore" @change="updateScoreDraft('costScore', $event)">
                    <option v-for="score in evalScoreOptions" :key="`cost-${score}`" :value="score">
                      {{ score }}
                    </option>
                  </select>
                </label>
                <label>
                  <span>可解释性</span>
                  <select :value="draft.explainabilityScore" @change="updateScoreDraft('explainabilityScore', $event)">
                    <option v-for="score in evalScoreOptions" :key="`exp-${score}`" :value="score">
                      {{ score }}
                    </option>
                  </select>
                </label>
              </div>

              <label class="creator-eval-note-field">
                <span>人工备注</span>
                <textarea
                  :value="draft.reviewerNote"
                  maxlength="1000"
                  placeholder="记录这次评分的判断依据"
                  @input="updateTextDraft('reviewerNote', $event)"
                ></textarea>
              </label>
            </form>

            <section class="creator-eval-results" aria-label="评测结果列表">
              <header class="creator-workflow-head">
                <div>
                  <h4>最近结果</h4>
                </div>
                <button
                  type="button"
                  class="creator-ghost-button"
                  :disabled="loadingResults"
                  @click="emit('refreshResults', selectedCase.caseId)"
                >
                  {{ loadingResults ? '读取中' : '刷新结果' }}
                </button>
              </header>

              <div class="creator-eval-result-list">
                <article
                  v-if="promptVersionStats.length > 0"
                  class="creator-eval-prompt-stats"
                  aria-label="Prompt版本对比"
                >
                  <strong>Prompt 版本对比</strong>
                  <div>
                    <span v-for="item in promptVersionStats" :key="item.promptVersion">
                      {{ item.promptVersion }} · {{ item.resultCount }} 次 · 成功率
                      {{ formatPercent(item.successRatePercent) }} · 均分
                      {{ formatMetric(item.averageScore) }} · 准确
                      {{ formatMetric(item.averageAccuracyScore) }} · Token
                      {{ formatMetric(item.averageTotalTokens) }} · 覆盖
                      {{ formatPercent(item.fullScoreCoverageRatePercent) }} · 波动
                      {{ formatMetric(item.scoreStandardDeviation) }}
                    </span>
                  </div>
                </article>

                <button
                  v-for="item in results"
                  :key="item.resultId"
                  type="button"
                  class="creator-eval-result-item"
                  :class="{ active: item.resultId === selectedResult?.resultId }"
                  @click="emit('update:selectedResultId', item.resultId)"
                >
                  <small>
                    {{ evalResultStatusLabel(item.runStatus) }} ·
                    {{ item.modelName || '未记录模型' }} · {{ formatDate(item.updateTime) }}
                  </small>
                  <strong>{{ item.outputSummary || item.failureReason || '未填写摘要' }}</strong>
                  <span>
                    {{ item.promptVersion || '未记录Prompt版本' }} · Token
                    {{ formatMetric(item.totalTokens) }} · {{ item.parseStatus }}
                  </span>
                </button>

                <p v-if="results.length === 0" class="creator-muted">
                  当前样例还没有评测结果。
                </p>
              </div>

              <article v-if="selectedResult" class="creator-eval-result-detail">
                <small>
                  {{ evalResultStatusLabel(selectedResult.runStatus) }} ·
                  {{ selectedResult.resultId }}
                </small>
                <strong>{{ selectedResult.outputSummary || '未填写输出摘要' }}</strong>
                <p v-if="selectedResult.failureReason">
                  失败原因：{{ selectedResult.failureReason }}
                </p>
                <p v-if="selectedResult.promptVersion || selectedResult.promptHash">
                  Prompt：{{ selectedResult.promptVersion || '未记录版本' }}
                  <span v-if="selectedResult.promptHash">
                    · {{ selectedResult.promptHash.slice(0, 12) }}
                  </span>
                </p>
                <pre v-if="selectedResult.promptSnapshot">{{ selectedResult.promptSnapshot }}</pre>
                <pre>{{ selectedResult.rawOutput }}</pre>
              </article>
            </section>
          </section>

          <article v-else class="creator-empty-result">
            <strong>还没有评测样例</strong>
            <span>请先执行数据库初始化脚本，导入阶段 4.6 内置样例。</span>
          </article>
        </div>
      </div>
    </section>
  </div>
</template>
