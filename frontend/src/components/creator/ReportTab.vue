<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  analyzeCreatorReport, exportCreatorReportMarkdown, getCreatorCompetitorReport,
  getCreatorReport, listCreatorReportHistory, getCreatorReportHistory,
} from '@/api/creator'
import { parseJsonArray } from '@/composables/creator/creatorWorkspaceUtils'
import { useCreatorWorkspaceShell } from '@/composables/creator/useCreatorWorkspaceContext'
import type { CreatorCompetitorReport, CreatorReport, CreatorReportHistoryPage } from '@/types/creator'

const emit = defineEmits<{ generated: [report: CreatorReport] }>()
const { selectedTask, selectedTaskId, feedbackReport } = useCreatorWorkspaceShell()
const competitorReport = ref<CreatorCompetitorReport | null>(null)
const creatorReport = ref<CreatorReport | null>(null)
const historicalReport = ref<CreatorReport | null>(null)
const selectedHistoryId = ref('')
const history = ref<CreatorReportHistoryPage>({ items: [], total: 0, page: 1, size: 10 })
const isAnalyzingCreatorReport = ref(false)
const isExportingCreatorReport = ref(false)
const isLoadingHistory = ref(false)
const reportError = ref('')
const historyError = ref('')
let loadSequence = 0
let historySequence = 0
let detailSequence = 0

const hasValidCompetitorReport = computed(() => competitorReport.value?.parseStatus === 'PARSED')
const canGenerateCreatorReport = computed(() =>
  feedbackReport.value?.parseStatus === 'PARSED'
  && ['FEEDBACK_ANALYZED', 'COMPETITOR_ANALYZED', 'ANALYZED'].includes(selectedTask.value?.status || ''),
)
const validReport = computed(() => {
  const report = historicalReport.value || creatorReport.value
  return report?.parseStatus === 'PARSED' ? report : null
})
const reportFieldLabels: Record<string, string> = {
  titleConclusion: '标题结论', descriptionConclusion: '简介结论',
  tagAndPartitionConclusion: '标签与分区结论', riskReminder: '风险提醒',
  benchmarkConclusion: '对标结论', ownAdvantages: '本视频优势',
  ownDisadvantages: '本视频短板', differentiationStrategy: '差异化策略',
  point: '问题点', impact: '影响', action: '处理建议',
  suggestion: '动作建议', reason: '依据', priority: '优先级',
  title: '标题', topic: '选题', target: '目标',
}

// 结构字段已解析成对象，按正文展示可避免重新编码引号，同时保留原文中的反斜杠与换行。
function formatReportValue(value: unknown, indent = ''): string {
  if (value === null || value === undefined) return `${indent}未提供`
  if (Array.isArray(value)) {
    if (!value.length) return `${indent}无条目`
    return value.map((item, index) =>
      item !== null && typeof item === 'object'
        ? `${indent}${index + 1}.\n${formatReportValue(item, `${indent}  `)}`
        : `${indent}${index + 1}. ${formatReportValue(item)}`,
    ).join('\n')
  }
  if (typeof value === 'object') {
    const entries = Object.entries(value)
    if (!entries.length) return `${indent}未提供`
    return entries.map(([key, item]) => {
      const label = Object.prototype.hasOwnProperty.call(reportFieldLabels, key) ? reportFieldLabels[key] : key
      return item !== null && typeof item === 'object'
        ? `${indent}${label}：\n${formatReportValue(item, `${indent}  `)}`
        : `${indent}${label}：${formatReportValue(item)}`
    }).join('\n')
  }
  return `${indent}${typeof value === 'boolean' ? (value ? '是' : '否') : String(value)}`
}

const reportSections = computed(() => {
  const report = validReport.value
  if (!report) return []
  const sections: Array<[string, string | null]> = [
    ['核心卖点', report.coreSellingPoints], ['标题与简介', report.titleDescriptionReview],
    ['竞品对照', report.competitorComparison], ['争议与误解', report.controversyAndMisunderstanding],
    ['下一步动作', report.nextActionSuggestions], ['创作偏好沉淀', report.creatorPreferenceInsight],
  ]
  return sections.map(([title, value]) => ({ title, items: parseJsonArray(value).map((item) => formatReportValue(item)).filter(Boolean) }))
})

watch([selectedTaskId, () => selectedTask.value?.status], loadReportData, { immediate: true })

async function optionalRequest<T>(request: () => Promise<T>): Promise<T | null> {
  try { return await request() } catch { return null }
}

async function loadReportData() {
  const taskId = selectedTaskId.value
  const sequence = ++loadSequence
  ++detailSequence
  competitorReport.value = null
  creatorReport.value = null
  historicalReport.value = null
  selectedHistoryId.value = ''
  reportError.value = ''
  history.value = { items: [], total: 0, page: 1, size: 10 }
  if (!taskId) return
  const [loadedCompetitor, loadedReport] = await Promise.all([
    optionalRequest(() => getCreatorCompetitorReport(taskId)),
    optionalRequest(() => getCreatorReport(taskId)),
    loadHistory(1),
  ])
  if (selectedTaskId.value !== taskId || loadSequence !== sequence) return
  competitorReport.value = loadedCompetitor
  creatorReport.value = loadedReport
}

async function loadHistory(page: number) {
  const taskId = selectedTaskId.value
  const sequence = ++historySequence
  if (!taskId) return
  isLoadingHistory.value = true
  historyError.value = ''
  try {
    const result = await listCreatorReportHistory(taskId, page)
    if (selectedTaskId.value === taskId && historySequence === sequence) history.value = result
  } catch (error) {
    if (selectedTaskId.value === taskId && historySequence === sequence)
      historyError.value = error instanceof Error ? error.message : '复盘历史加载失败'
  } finally {
    if (historySequence === sequence) isLoadingHistory.value = false
  }
}

async function viewHistory(historyId: string) {
  const taskId = selectedTaskId.value
  const sequence = ++detailSequence
  reportError.value = ''
  try {
    const report = await getCreatorReportHistory(taskId, historyId)
    if (selectedTaskId.value !== taskId || detailSequence !== sequence) return
    historicalReport.value = report
    selectedHistoryId.value = historyId
  } catch (error) {
    if (selectedTaskId.value === taskId && detailSequence === sequence)
      reportError.value = error instanceof Error ? error.message : '复盘历史读取失败'
  }
}

function returnToLatest() {
  ++detailSequence
  historicalReport.value = null
  selectedHistoryId.value = ''
}

async function runCreatorReportAnalyze() {
  const taskId = selectedTaskId.value
  if (!taskId || !canGenerateCreatorReport.value || isAnalyzingCreatorReport.value) return
  isAnalyzingCreatorReport.value = true
  reportError.value = ''
  try {
    const report = await analyzeCreatorReport(taskId)
    if (selectedTaskId.value !== taskId) return
    creatorReport.value = report
    returnToLatest()
    await loadHistory(1)
    emit('generated', report)
  } catch (error) {
    if (selectedTaskId.value === taskId)
      reportError.value = error instanceof Error ? error.message : '总体复盘生成失败'
  } finally {
    isAnalyzingCreatorReport.value = false
  }
}

async function downloadCreatorReportMarkdown() {
  const taskId = selectedTaskId.value
  if (!taskId || !creatorReport.value || selectedHistoryId.value || isExportingCreatorReport.value) return
  isExportingCreatorReport.value = true
  reportError.value = ''
  try {
    const { blob, filename } = await exportCreatorReportMarkdown(taskId)
    if (selectedTaskId.value !== taskId) return
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = filename || `creator-report-${taskId}.md`
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    if (selectedTaskId.value === taskId)
      reportError.value = error instanceof Error ? error.message : '总体复盘导出失败'
  } finally {
    isExportingCreatorReport.value = false
  }
}
</script>

<template>
  <section class="creator-section creator-overall-report">
    <div class="creator-section-head">
      <h3>总体复盘</h3>
      <div v-if="selectedTaskId" class="creator-action-row">
        <button type="button" class="creator-secondary-action" :disabled="isAnalyzingCreatorReport" @click="loadReportData">刷新复盘数据</button>
        <button v-if="creatorReport?.parseStatus === 'PARSED' && !selectedHistoryId" type="button" class="creator-secondary-action"
          :disabled="isExportingCreatorReport" @click="downloadCreatorReportMarkdown">
          {{ isExportingCreatorReport ? '导出中...' : '导出 Markdown' }}
        </button>
        <button type="button" class="creator-primary-button" :disabled="!canGenerateCreatorReport || isAnalyzingCreatorReport" @click="runCreatorReportAnalyze">
          {{ isAnalyzingCreatorReport ? '生成中...' : (creatorReport || history.total > 0 ? '重新生成总体复盘' : '生成总体复盘') }}
        </button>
      </div>
    </div>
    <p class="creator-report-status">
      反馈分析：{{ feedbackReport?.parseStatus === 'PARSED' ? '已完成' : '待完成' }}；
      竞品分析：{{ hasValidCompetitorReport ? '已完成，本次纳入' : '未提供（可选）' }}。
    </p>
    <p v-if="reportError" class="creator-report-error">{{ reportError }}</p>
    <section v-if="selectedTaskId" class="creator-report-history">
      <div class="creator-section-head"><h4>复盘历史（{{ history.total }}）</h4></div>
      <p v-if="historyError" class="creator-report-error">{{ historyError }}</p>
      <p v-if="!history.total && !isLoadingHistory" class="creator-report-status">成功生成的复盘会保存在这里。</p>
      <ul v-if="history.items.length">
        <li v-for="item in history.items" :key="item.historyId">
          <button type="button" class="creator-secondary-action" :aria-pressed="selectedHistoryId === item.historyId" @click="viewHistory(item.historyId)">
            查看复盘 · {{ item.createTime.replace('T', ' ') }} · {{ item.hasCompetitor === null ? '旧报告，来源未记录' : (item.hasCompetitor ? '含竞品分析' : '仅自身反馈') }}
          </button>
          <p>{{ item.overallConclusion }}</p>
        </li>
      </ul>
      <div v-if="history.total > history.size" class="creator-action-row">
        <button type="button" :disabled="isLoadingHistory || history.page <= 1" @click="loadHistory(history.page - 1)">上一页复盘历史</button>
        <span>第 {{ history.page }} 页</span>
        <button type="button" :disabled="isLoadingHistory || history.page * history.size >= history.total" @click="loadHistory(history.page + 1)">下一页复盘历史</button>
      </div>
    </section>
    <div v-if="selectedHistoryId" class="creator-action-row">
      <span>正在查看历史复盘</span>
      <button type="button" class="creator-secondary-action" @click="returnToLatest">返回最新复盘</button>
    </div>
    <template v-if="validReport">
      <section class="creator-report-summary"><h4>总体结论</h4><p>{{ validReport.overallConclusion }}</p></section>
      <section class="creator-report-summary"><h4>内容总结</h4><p>{{ validReport.contentSummary }}</p></section>
      <section class="creator-report-summary"><h4>观众反馈</h4><p>{{ validReport.audienceFeedbackSummary }}</p></section>
      <section v-for="section in reportSections" :key="section.title" class="creator-report-detail">
        <h4>{{ section.title }}</h4>
        <ul><li v-for="(item, index) in section.items" :key="index">{{ item }}</li></ul>
      </section>
    </template>
  </section>
</template>

<style scoped>
.creator-overall-report {
  display: grid;
  gap: 18px;
}

.creator-report-status {
  margin: 0;
  color: var(--muted);
}

.creator-report-error {
  margin: 0;
  color: var(--danger);
}

.creator-report-summary,
.creator-report-detail {
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border);
}

.creator-report-summary h4,
.creator-report-detail h4 {
  margin: 0 0 8px;
  font-size: 14px;
}

.creator-report-summary p,
.creator-report-detail ul {
  margin: 0;
  line-height: 1.7;
}

.creator-report-detail ul {
  display: grid;
  gap: 8px;
  padding-left: 20px;
}

.creator-report-detail li {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.creator-report-history ul { display: grid; gap: 12px; margin: 0; padding: 0; list-style: none; }
.creator-report-history li p { margin: 6px 0 0; color: var(--muted); overflow-wrap: anywhere; }
.creator-report-history h4 { margin: 0; }
</style>
