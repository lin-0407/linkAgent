<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  analyzeCreatorReport,
  exportCreatorReportMarkdown,
  getCreatorCompetitorReport,
  getCreatorReport,
} from '@/api/creator'
import { formatValue, parseJsonArray } from '@/composables/creator/creatorWorkspaceUtils'
import { useCreatorWorkspaceShell } from '@/composables/creator/useCreatorWorkspaceContext'
import type { CreatorCompetitorReport, CreatorReport } from '@/types/creator'

const emit = defineEmits<{ generated: [report: CreatorReport] }>()

const {
  selectedTask,
  selectedTaskId,
  feedbackReport,
} = useCreatorWorkspaceShell()

const competitorReport = ref<CreatorCompetitorReport | null>(null)
const creatorReport = ref<CreatorReport | null>(null)
const isAnalyzingCreatorReport = ref(false)
const isExportingCreatorReport = ref(false)
const reportError = ref('')

const hasValidCompetitorReport = computed(
  () => competitorReport.value?.parseStatus === 'PARSED',
)
const canGenerateCreatorReport = computed(
  () => feedbackReport.value?.parseStatus === 'PARSED' && hasValidCompetitorReport.value,
)

const validReport = computed(() =>
  creatorReport.value?.parseStatus === 'PARSED' ? creatorReport.value : null,
)

const reportSections = computed(() => {
  const report = validReport.value
  if (!report) return []
  const sections: Array<[string, string | null]> = [
    ['核心卖点', report.coreSellingPoints],
    ['标题与简介', report.titleDescriptionReview],
    ['竞品对照', report.competitorComparison],
    ['争议与误解', report.controversyAndMisunderstanding],
    ['下一步动作', report.nextActionSuggestions],
    ['创作偏好沉淀', report.creatorPreferenceInsight],
  ]
  return sections.map(([title, value]) => ({
    title,
    items: parseJsonArray(value).map(formatValue).filter(Boolean),
  }))
})

watch(selectedTaskId, loadReportData, { immediate: true })

async function loadReportData() {
  const taskId = selectedTaskId.value
  const status = selectedTask.value?.status
  competitorReport.value = null
  creatorReport.value = null
  reportError.value = ''
  if (!taskId || !status) return

  const [loadedCompetitor, loadedReport] = await Promise.all([
    ['COMPETITOR_ANALYZED', 'ANALYZED', 'ARCHIVED'].includes(status)
      ? optionalRequest(() => getCreatorCompetitorReport(taskId))
      : null,
    ['ANALYZED', 'ARCHIVED'].includes(status)
      ? optionalRequest(() => getCreatorReport(taskId))
      : null,
  ])
  if (selectedTaskId.value !== taskId) return
  competitorReport.value = loadedCompetitor
  creatorReport.value = loadedReport
}

async function optionalRequest<T>(request: () => Promise<T>): Promise<T | null> {
  try {
    return await request()
  } catch {
    return null
  }
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
    emit('generated', report)
  } catch (error) {
    if (selectedTaskId.value === taskId) {
      reportError.value = error instanceof Error ? error.message : '总体复盘生成失败'
    }
  } finally {
    if (selectedTaskId.value === taskId) isAnalyzingCreatorReport.value = false
  }
}

async function downloadCreatorReportMarkdown() {
  const taskId = selectedTaskId.value
  if (!taskId || !creatorReport.value || isExportingCreatorReport.value) return
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
    if (selectedTaskId.value === taskId) {
      reportError.value = error instanceof Error ? error.message : '总体复盘导出失败'
    }
  } finally {
    if (selectedTaskId.value === taskId) isExportingCreatorReport.value = false
  }
}
</script>

<template>
  <section class="creator-section creator-overall-report">
    <div class="creator-section-head">
      <h3>总体复盘</h3>
      <div v-if="selectedTaskId" class="creator-action-row">
        <button
          v-if="validReport"
          type="button"
          class="creator-secondary-action"
          :disabled="isExportingCreatorReport"
          @click="downloadCreatorReportMarkdown"
        >
          {{ isExportingCreatorReport ? '导出中...' : '导出 Markdown' }}
        </button>
        <button
          v-else
          type="button"
          class="creator-primary-button"
          :disabled="!canGenerateCreatorReport || isAnalyzingCreatorReport"
          @click="runCreatorReportAnalyze"
        >
          {{ isAnalyzingCreatorReport ? '生成中...' : '生成总体复盘' }}
        </button>
      </div>
    </div>

    <p v-if="!validReport" class="creator-report-status">
      反馈分析：{{ feedbackReport?.parseStatus === 'PARSED' ? '已完成' : '待完成' }}；
      竞品分析：{{ hasValidCompetitorReport ? '已完成' : '待完成' }}。
    </p>
    <p v-if="reportError" class="creator-report-error">{{ reportError }}</p>

    <template v-else-if="validReport">
      <section class="creator-report-summary">
        <h4>总体结论</h4>
        <p>{{ validReport.overallConclusion }}</p>
      </section>
      <section class="creator-report-summary">
        <h4>内容总结</h4>
        <p>{{ validReport.contentSummary }}</p>
      </section>
      <section class="creator-report-summary">
        <h4>观众反馈</h4>
        <p>{{ validReport.audienceFeedbackSummary }}</p>
      </section>
      <section
        v-for="section in reportSections"
        :key="section.title"
        class="creator-report-detail"
      >
        <h4>{{ section.title }}</h4>
        <ul>
          <li v-for="(item, index) in section.items" :key="index">{{ item }}</li>
        </ul>
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
</style>
