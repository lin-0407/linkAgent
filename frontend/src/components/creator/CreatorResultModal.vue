<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  formatMetric,
  formatValue,
  getRecordText,
  parseJsonArray,
  retrievalModeLabel,
  statBarWidth,
} from '@/composables/creator/creatorWorkspaceUtils'
import FeedbackChatDrawer from './FeedbackChatDrawer.vue'
import type {
  CreatorFeedbackChatResult,
  CreatorFeedbackChatTurn,
  CreatorFeedbackDashboard,
  CreatorFeedbackEvidenceIndexStatus,
  CreatorFeedbackFetchResult,
  CreatorFeedbackReport,
  ResultModalTarget,
} from '@/types/creator'

const props = defineProps<{
  target: ResultModalTarget | null
  title: string
  showDeveloperTools: boolean
  feedbackFetchResult: CreatorFeedbackFetchResult | null
  feedbackDashboard: CreatorFeedbackDashboard | null
  dashboardWarnings: string[]
  feedbackReport: CreatorFeedbackReport | null
  feedbackEvidenceIndexStatus: CreatorFeedbackEvidenceIndexStatus | null
  loadingFeedbackEvidenceIndexStatus: boolean
  rebuildingFeedbackEvidenceIndex: boolean
  feedbackChatResult: CreatorFeedbackChatResult | null
  feedbackEvidenceIndexWarnings: string[]
  feedbackDrawerOpen: boolean
  feedbackChatTurns: CreatorFeedbackChatTurn[]
  feedbackChatQuestion: string
  canAskFeedbackChat: boolean
  askingFeedbackChat: boolean
}>()

const emit = defineEmits<{
  close: []
  'toggle-feedback-drawer': []
  'close-feedback-drawer': []
  'rebuild-evidence-index': []
  'update:feedback-question': [question: string]
  'ask-feedback-chat': []
}>()

// 模板仍沿用原有字段路径，但数据全部来自显式 props，避免子组件触碰主壳内部的 Ref。
const ctx = {
  get shell() {
    return {
      resultModalTarget: props.target,
      resultModalTitle: props.title,
      showDeveloperTools: props.showDeveloperTools,
      feedbackFetchResult: props.feedbackFetchResult,
      feedbackDashboard: props.feedbackDashboard,
      feedbackDashboardWarnings: props.dashboardWarnings,
      feedbackReport: props.feedbackReport,
      feedbackEvidenceIndexStatus: props.feedbackEvidenceIndexStatus,
      isLoadingFeedbackEvidenceIndexStatus: props.loadingFeedbackEvidenceIndexStatus,
      isRebuildingFeedbackEvidenceIndex: props.rebuildingFeedbackEvidenceIndex,
      rebuildFeedbackEvidenceIndex: () => emit('rebuild-evidence-index'),
      feedbackChatResult: props.feedbackChatResult,
      feedbackEvidenceIndexWarnings: props.feedbackEvidenceIndexWarnings,
      isFeedbackChatDrawerOpen: props.feedbackDrawerOpen,
    }
  },
  closeResultModal: () => emit('close'),
  toggleFeedbackChatDrawer: () => emit('toggle-feedback-drawer'),
}

const isResultModalBackdropPointerDown = ref(false)

const hotTopics = computed(() => parseJsonArray(ctx.shell.feedbackReport?.hotTopics))
const controversyPoints = computed(() => parseJsonArray(ctx.shell.feedbackReport?.controversyPoints))
const misunderstandingPoints = computed(() =>
  parseJsonArray(ctx.shell.feedbackReport?.misunderstandingPoints),
)
const nextContentSuggestions = computed(() =>
  parseJsonArray(ctx.shell.feedbackReport?.nextContentSuggestions),
)
const interactionSuggestions = computed(() =>
  parseJsonArray(ctx.shell.feedbackReport?.interactionSuggestions),
)
const misunderstandingSourceAnalysis = computed(() =>
  parseJsonArray(ctx.shell.feedbackReport?.misunderstandingSourceAnalysis),
)
const feedbackActionPlan = computed(() =>
  parseJsonArray(ctx.shell.feedbackReport?.feedbackActionPlan),
)

function handleResultModalBackdropPointerDown(event: PointerEvent) {
  isResultModalBackdropPointerDown.value = event.target === event.currentTarget
}

function handleResultModalBackdropClick(event: MouseEvent) {
  if (isResultModalBackdropPointerDown.value && event.target === event.currentTarget) {
    ctx.closeResultModal()
    return
  }
  isResultModalBackdropPointerDown.value = false
}

function addOne(n: number | string) {
  return Number(n) + 1
}
</script>

<template>
  <div
    v-if="ctx.shell.resultModalTarget"
    class="creator-modal-backdrop"
    role="presentation"
    @pointerdown="handleResultModalBackdropPointerDown"
    @click="handleResultModalBackdropClick"
  >
    <section
      class="creator-result-modal"
      :class="{
        'with-feedback-drawer':
          ctx.shell.resultModalTarget === 'feedbackReport' && ctx.shell.isFeedbackChatDrawerOpen,
      }"
      role="dialog"
      aria-modal="true"
      :aria-label="ctx.shell.resultModalTitle"
    >
      <header class="creator-result-modal-head">
        <div>
          <p class="creator-kicker">阶段结果</p>
          <h3>{{ ctx.shell.resultModalTitle }}</h3>
        </div>
        <div class="creator-panel-actions">
          <button
            v-if="ctx.shell.resultModalTarget === 'feedbackReport'"
            type="button"
            class="creator-secondary-action"
            @click="ctx.toggleFeedbackChatDrawer"
          >
            {{ ctx.shell.isFeedbackChatDrawerOpen ? '收起追问' : '追问报告' }}
          </button>
          <button type="button" class="creator-ghost-button" @click="ctx.closeResultModal">
            关闭
          </button>
        </div>
      </header>

      <div class="creator-result-modal-body">
        <template v-if="ctx.shell.resultModalTarget === 'feedbackDashboard'">
          <div class="creator-result-grid">
            <article
              v-if="ctx.shell.showDeveloperTools && ctx.shell.feedbackFetchResult"
              class="creator-result-block span-full"
            >
              <span>脚本输出</span>
              <div class="creator-script-output">
                <span>输出目录</span>
                <code>{{ ctx.shell.feedbackFetchResult.outputDirectory }}</code>
                <span>生成文件</span>
                <ul>
                  <li v-for="filePath in ctx.shell.feedbackFetchResult.outputFiles" :key="filePath">
                    {{ filePath }}
                  </li>
                </ul>
              </div>
            </article>
            <article
              v-else-if="ctx.shell.feedbackFetchResult && !ctx.shell.feedbackDashboard"
              class="creator-result-block span-full"
            >
              <span>读取完成</span>
              <p>
                已读取 {{ ctx.shell.feedbackFetchResult.commentCount }} 条评论、{{
                  ctx.shell.feedbackFetchResult.danmakuCount
                }} 条弹幕。导入明细暂时没有返回，可以稍后刷新或直接继续分析。
              </p>
            </article>

            <template v-if="ctx.shell.feedbackDashboard">
              <article class="creator-result-block">
                <span>导入概览</span>
                <div class="creator-metric-grid">
                  <section>
                    <strong>{{ formatMetric(ctx.shell.feedbackDashboard.commentCount) }}</strong>
                    <small>评论</small>
                  </section>
                  <section>
                    <strong>{{ formatMetric(ctx.shell.feedbackDashboard.danmakuCount) }}</strong>
                    <small>弹幕</small>
                  </section>
                  <section>
                    <strong>{{ formatMetric(ctx.shell.feedbackDashboard.noiseCount) }}</strong>
                    <small>无意义/重复</small>
                  </section>
                </div>
              </article>

              <article class="creator-result-block">
                <span>视频指标</span>
                <div v-if="ctx.shell.feedbackDashboard.metric" class="creator-metric-grid">
                  <section>
                    <strong>{{ formatMetric(ctx.shell.feedbackDashboard.metric.viewCount) }}</strong>
                    <small>播放</small>
                  </section>
                  <section>
                    <strong>{{ formatMetric(ctx.shell.feedbackDashboard.metric.likeCount) }}</strong>
                    <small>点赞</small>
                  </section>
                  <section>
                    <strong>{{ formatMetric(ctx.shell.feedbackDashboard.metric.coinCount) }}</strong>
                    <small>投币</small>
                  </section>
                  <section>
                    <strong>{{ formatMetric(ctx.shell.feedbackDashboard.metric.favoriteCount) }}</strong>
                    <small>收藏</small>
                  </section>
                </div>
                <p v-else>当前文件没有视频指标，仪表盘只展示评论和弹幕明细。</p>
              </article>

              <article class="creator-result-block">
                <span>评论分类</span>
                <div class="creator-stat-bars">
                  <section
                    v-for="item in ctx.shell.feedbackDashboard.commentCategoryStats"
                    :key="`comment-${item.name}`"
                  >
                    <div>
                      <strong>{{ item.label }}</strong>
                      <small>{{ item.count }} 条</small>
                    </div>
                    <i
                      :style="{
                        width: statBarWidth(item.count, ctx.shell.feedbackDashboard.commentCount),
                      }"
                    ></i>
                  </section>
                </div>
              </article>

              <article class="creator-result-block">
                <span>弹幕分类</span>
                <div class="creator-stat-bars">
                  <section
                    v-for="item in ctx.shell.feedbackDashboard.danmakuCategoryStats"
                    :key="`danmaku-${item.name}`"
                  >
                    <div>
                      <strong>{{ item.label }}</strong>
                      <small>{{ item.count }} 条</small>
                    </div>
                    <i
                      :style="{
                        width: statBarWidth(item.count, ctx.shell.feedbackDashboard.danmakuCount),
                      }"
                    ></i>
                  </section>
                </div>
              </article>

              <article class="creator-result-block">
                <span>情绪分布</span>
                <div class="creator-stat-bars">
                  <section
                    v-for="item in ctx.shell.feedbackDashboard.sentimentStats"
                    :key="`sentiment-${item.name}`"
                  >
                    <div>
                      <strong>{{ item.label }}</strong>
                      <small>{{ item.count }} 条</small>
                    </div>
                    <i
                      :style="{
                        width: statBarWidth(
                          item.count,
                          ctx.shell.feedbackDashboard.commentCount + ctx.shell.feedbackDashboard.danmakuCount,
                        ),
                      }"
                    ></i>
                  </section>
                </div>
              </article>

              <article class="creator-result-block">
                <span>高频关键词</span>
                <div class="creator-chip-list">
                  <b v-for="item in ctx.shell.feedbackDashboard.keywords" :key="item.keyword">
                    {{ item.keyword }} · {{ item.count }}
                  </b>
                  <p v-if="ctx.shell.feedbackDashboard.keywords.length === 0">
                    暂未识别出明显关键词，可以补充更多评论或弹幕后再分析。
                  </p>
                </div>
              </article>

              <article class="creator-result-block span-full">
                <span>弹幕时间段热度</span>
                <div v-if="ctx.shell.feedbackDashboard.danmakuTimeline.length" class="creator-stat-bars">
                  <section
                    v-for="item in ctx.shell.feedbackDashboard.danmakuTimeline"
                    :key="item.timeBucket"
                  >
                    <div>
                      <strong>{{ item.timeBucket }}</strong>
                      <small>{{ item.count }} 条</small>
                    </div>
                    <i
                      :style="{
                        width: statBarWidth(item.count, ctx.shell.feedbackDashboard.danmakuCount),
                      }"
                    ></i>
                  </section>
                </div>
                <p v-else>当前弹幕没有时间戳，暂不展示时间段热度。</p>
              </article>

              <article class="creator-result-block span-full">
                <span>高反馈评论</span>
                <div
                  v-if="ctx.shell.feedbackDashboard.topCommentItems.length"
                  class="creator-feedback-item-list"
                >
                  <section v-for="item in ctx.shell.feedbackDashboard.topCommentItems" :key="item.itemId">
                    <small>
                      {{ item.categoryLabel }} · {{ item.sentimentLabel }}
                      <template v-if="item.likeCount !== null">
                        · 点赞 {{ formatMetric(item.likeCount) }}
                      </template>
                      <template v-if="item.replyCount !== null">
                        · 回复 {{ formatMetric(item.replyCount) }}
                      </template>
                      <template v-if="item.occurTimeText"> · {{ item.occurTimeText }}</template>
                    </small>
                    <p>{{ item.content }}</p>
                  </section>
                </div>
                <p v-else>当前导入没有可排序的评论点赞数据。</p>
              </article>

              <article class="creator-result-block span-full">
                <span>最近导入明细</span>
                <div class="creator-feedback-item-list">
                  <section v-for="item in ctx.shell.feedbackDashboard.recentItems" :key="item.itemId">
                    <small>
                      {{ item.sourceLabel }} · {{ item.categoryLabel }} ·
                      {{ item.sentimentLabel }}
                      <template v-if="item.likeCount !== null">
                        · 点赞 {{ formatMetric(item.likeCount) }}
                      </template>
                      <template v-if="item.replyCount !== null">
                        · 回复 {{ formatMetric(item.replyCount) }}
                      </template>
                      <template v-if="item.occurTimeText"> · {{ item.occurTimeText }}</template>
                    </small>
                    <p>{{ item.content }}</p>
                  </section>
                </div>
              </article>

              <article
                v-if="ctx.shell.feedbackDashboardWarnings.length"
                class="creator-result-block span-full"
              >
                <span>导入提示</span>
                <ul>
                  <li v-for="warning in ctx.shell.feedbackDashboardWarnings" :key="warning">
                    {{ warning }}
                  </li>
                </ul>
              </article>
            </template>

            <article v-else class="creator-empty-result span-full">
              <strong>还没有可展示的导入仪表盘</strong>
              <span>请先导入 JSON/TXT 文件，或通过单个 BV 显式触发限量样例采集。</span>
            </article>
          </div>
        </template>

        <template v-else-if="ctx.shell.resultModalTarget === 'feedbackReport' && ctx.shell.feedbackReport">
          <div class="creator-report">
            <section v-if="ctx.shell.showDeveloperTools" class="creator-feedback-index-status">
              <div class="creator-feedback-index-line">
                <div>
                  <strong>证据索引</strong>
                  <small>
                    {{
                      ctx.shell.feedbackEvidenceIndexStatus
                        ? retrievalModeLabel(ctx.shell.feedbackEvidenceIndexStatus.retrievalMode)
                        : ctx.shell.isLoadingFeedbackEvidenceIndexStatus
                          ? '读取中'
                          : '未读取'
                    }}
                  </small>
                </div>
                <button
                  type="button"
                  class="creator-ghost-button creator-mini-button"
                  :disabled="ctx.shell.isRebuildingFeedbackEvidenceIndex"
                  @click="ctx.shell.rebuildFeedbackEvidenceIndex"
                >
                  {{ ctx.shell.isRebuildingFeedbackEvidenceIndex ? '索引中...' : '重建证据索引' }}
                </button>
              </div>
              <p v-if="ctx.shell.feedbackEvidenceIndexStatus" class="creator-feedback-index-hint">
                <template
                  v-if="
                    ctx.shell.feedbackEvidenceIndexStatus.ragEnabled &&
                    ctx.shell.feedbackEvidenceIndexStatus.vectorStoreReady
                  "
                >
                  已索引 {{ ctx.shell.feedbackEvidenceIndexStatus.indexedCount }}/{{
                    ctx.shell.feedbackEvidenceIndexStatus.totalItems
                  }} 条，待索引 {{ ctx.shell.feedbackEvidenceIndexStatus.pendingCount }} 条，失败
                  {{ ctx.shell.feedbackEvidenceIndexStatus.failedCount }} 条。
                </template>
                <template v-else>
                  当前使用 SQL 证据检索（{{
                    ctx.shell.feedbackEvidenceIndexStatus.ragEnabled ? 'Milvus 未就绪' : 'RAG 未启用'
                  }}）。
                </template>
              </p>
              <p v-else class="creator-feedback-index-hint">
                {{
                  ctx.shell.isLoadingFeedbackEvidenceIndexStatus
                    ? '正在读取证据索引状态...'
                    : '暂未读取证据索引状态。'
                }}
              </p>
              <p v-if="ctx.shell.feedbackChatResult" class="creator-feedback-index-hint">
                最近追问：{{ ctx.shell.feedbackChatResult.reportUsed ? '含报告' : '仅明细' }} ·
                {{ retrievalModeLabel(ctx.shell.feedbackChatResult.retrievalMode) }} ·
                {{ ctx.shell.feedbackChatResult.modelName || '未记录模型' }} · Token
                {{ formatMetric(ctx.shell.feedbackChatResult.totalTokens) }} ·
                {{ formatMetric(ctx.shell.feedbackChatResult.elapsedMs) }} ms
              </p>
              <ul v-if="ctx.shell.feedbackEvidenceIndexWarnings.length" class="creator-feedback-index-warnings">
                <li v-for="(warning, index) in ctx.shell.feedbackEvidenceIndexWarnings" :key="index">
                  {{ warning }}
                </li>
              </ul>
              <details
                v-if="ctx.shell.feedbackChatResult && ctx.shell.feedbackChatResult.evidenceItems.length > 0"
                class="creator-feedback-index-evidence"
              >
                <summary>
                  最近追问依据
                  <small>{{ ctx.shell.feedbackChatResult.evidenceItems.length }} 条证据</small>
                </summary>
                <div class="creator-feedback-evidence-list">
                  <section
                    v-for="(item, index) in ctx.shell.feedbackChatResult.evidenceItems"
                    :key="item.itemId"
                  >
                    <small>
                      证据{{ addOne(index) }} · {{ item.sourceLabel }} ·
                      {{ item.categoryLabel }} · {{ item.sentimentLabel }}
                      <template v-if="item.occurTimeText"> · {{ item.occurTimeText }}</template>
                    </small>
                    <p>{{ item.content }}</p>
                  </section>
                </div>
              </details>
            </section>

            <section class="creator-report-group">
              <h4 class="creator-report-group-title">概览</h4>
              <div class="creator-report-overview">
                <div class="creator-report-row">
                  <span>整体反馈</span>
                  <p>{{ ctx.shell.feedbackReport.feedbackSummary || '未解析到整体反馈' }}</p>
                </div>
                <div class="creator-report-row">
                  <span>创作者复盘困境</span>
                  <p>{{ ctx.shell.feedbackReport.creatorFeedbackDilemma || '未解析到创作者复盘困境' }}</p>
                </div>
                <div class="creator-report-row">
                  <span>观众核心关注</span>
                  <p>{{ ctx.shell.feedbackReport.audienceCoreConcern || '未解析到观众核心关注' }}</p>
                </div>
                <div class="creator-report-row">
                  <span>情绪倾向</span>
                  <p>{{ ctx.shell.feedbackReport.sentimentSummary || '未解析到情绪倾向' }}</p>
                </div>
              </div>
            </section>

            <section class="creator-report-group">
              <h4 class="creator-report-group-title">观众怎么想</h4>
              <div class="creator-report-cards">
                <article class="creator-result-block">
                  <span>高频观点</span>
                  <div class="creator-list">
                    <section v-for="(item, index) in hotTopics" :key="index">
                      <strong>{{ getRecordText(item, 'topic') || formatValue(item) }}</strong>
                      <p v-if="getRecordText(item, 'evidence')" class="creator-kv">
                        <span>依据</span>{{ getRecordText(item, 'evidence') }}
                      </p>
                      <p v-if="getRecordText(item, 'creatorDecision')" class="creator-kv">
                        <span>判断</span>{{ getRecordText(item, 'creatorDecision') }}
                      </p>
                      <p v-if="getRecordText(item, 'suggestion')" class="creator-kv">
                        <span>建议</span>{{ getRecordText(item, 'suggestion') }}
                      </p>
                    </section>
                  </div>
                </article>
                <article class="creator-result-block">
                  <span>下一期内容建议</span>
                  <div class="creator-list">
                    <section v-for="(item, index) in nextContentSuggestions" :key="index">
                      <strong>{{ getRecordText(item, 'topic') || formatValue(item) }}</strong>
                      <p v-if="getRecordText(item, 'sourceSignal')" class="creator-kv">
                        <span>信号</span>{{ getRecordText(item, 'sourceSignal') }}
                      </p>
                      <p v-if="getRecordText(item, 'executionHint')" class="creator-kv">
                        <span>做法</span>{{ getRecordText(item, 'executionHint') }}
                      </p>
                      <p v-if="getRecordText(item, 'risk')" class="creator-kv">
                        <span>注意</span>{{ getRecordText(item, 'risk') }}
                      </p>
                    </section>
                  </div>
                </article>
              </div>
            </section>

            <section class="creator-report-group">
              <h4 class="creator-report-group-title">风险与误解</h4>
              <div class="creator-report-cards">
                <article class="creator-result-block">
                  <span>争议点</span>
                  <div class="creator-list">
                    <section v-for="(item, index) in controversyPoints" :key="index">
                      <strong>{{ getRecordText(item, 'point') || formatValue(item) }}</strong>
                      <p v-if="getRecordText(item, 'risk')" class="creator-kv">
                        <span>风险</span>{{ getRecordText(item, 'risk') }}
                      </p>
                      <p v-if="getRecordText(item, 'responseBoundary')" class="creator-kv">
                        <span>边界</span>{{ getRecordText(item, 'responseBoundary') }}
                      </p>
                      <p v-if="getRecordText(item, 'responseAdvice')" class="creator-kv">
                        <span>回应</span>{{ getRecordText(item, 'responseAdvice') }}
                      </p>
                    </section>
                  </div>
                </article>
                <article class="creator-result-block">
                  <span>误解点</span>
                  <div class="creator-list">
                    <section v-for="(item, index) in misunderstandingPoints" :key="index">
                      <strong>{{ getRecordText(item, 'point') || formatValue(item) }}</strong>
                      <p v-if="getRecordText(item, 'source')" class="creator-kv">
                        <span>来源</span>{{ getRecordText(item, 'source') }}
                      </p>
                      <p v-if="getRecordText(item, 'clarificationAdvice')" class="creator-kv">
                        <span>澄清</span>{{ getRecordText(item, 'clarificationAdvice') }}
                      </p>
                    </section>
                  </div>
                </article>
                <article class="creator-result-block">
                  <span>误解来源分析</span>
                  <div v-if="misunderstandingSourceAnalysis.length" class="creator-list">
                    <section
                      v-for="(item, index) in misunderstandingSourceAnalysis"
                      :key="index"
                    >
                      <strong>{{ getRecordText(item, 'source') || formatValue(item) }}</strong>
                      <p v-if="getRecordText(item, 'reason')" class="creator-kv">
                        <span>成因</span>{{ getRecordText(item, 'reason') }}
                      </p>
                      <p v-if="getRecordText(item, 'repairAction')" class="creator-kv">
                        <span>修复</span>{{ getRecordText(item, 'repairAction') }}
                      </p>
                    </section>
                  </div>
                  <p v-else class="creator-report-empty">未解析到误解来源分析</p>
                </article>
              </div>
            </section>

            <section class="creator-report-group">
              <h4 class="creator-report-group-title">我该做什么</h4>
              <div class="creator-report-cards">
                <article class="creator-result-block">
                  <span>互动建议</span>
                  <div class="creator-list">
                    <section v-for="(item, index) in interactionSuggestions" :key="index">
                      <strong>{{ getRecordText(item, 'channel') || formatValue(item) }}</strong>
                      <p v-if="getRecordText(item, 'message')" class="creator-kv">
                        <span>内容</span>{{ getRecordText(item, 'message') }}
                      </p>
                      <p v-if="getRecordText(item, 'purpose')" class="creator-kv">
                        <span>目的</span>{{ getRecordText(item, 'purpose') }}
                      </p>
                    </section>
                  </div>
                </article>
                <article class="creator-result-block">
                  <span>反馈行动计划</span>
                  <div v-if="feedbackActionPlan.length" class="creator-list">
                    <section v-for="(item, index) in feedbackActionPlan" :key="index">
                      <strong>
                        <span
                          v-if="getRecordText(item, 'priority')"
                          class="creator-badge"
                        >{{ getRecordText(item, 'priority') }}</span>
                        {{ getRecordText(item, 'action') || formatValue(item) }}
                      </strong>
                      <p v-if="getRecordText(item, 'reason')" class="creator-kv">
                        <span>原因</span>{{ getRecordText(item, 'reason') }}
                      </p>
                      <p v-if="getRecordText(item, 'expectedResult')" class="creator-kv">
                        <span>预期</span>{{ getRecordText(item, 'expectedResult') }}
                      </p>
                    </section>
                  </div>
                  <p v-else class="creator-report-empty">未解析到反馈行动计划</p>
                </article>
              </div>
            </section>

          </div>
        </template>
      </div>

      <FeedbackChatDrawer
        :open="props.target === 'feedbackReport' && props.feedbackDrawerOpen"
        :turns="props.feedbackChatTurns"
        :question="props.feedbackChatQuestion"
        :can-ask="props.canAskFeedbackChat"
        :asking="props.askingFeedbackChat"
        @close="emit('close-feedback-drawer')"
        @update:question="emit('update:feedback-question', $event)"
        @ask="emit('ask-feedback-chat')"
      />
    </section>
  </div>
</template>
