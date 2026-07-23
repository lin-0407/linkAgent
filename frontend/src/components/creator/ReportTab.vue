<script setup lang="ts">
import { useCreatorWorkspaceShell } from '@/composables/creator/useCreatorWorkspaceContext'

const {
  selectedTaskId,
  isExportingReportMarkdown,
  downloadReportMarkdown,
  feedbackReport,
  openResultModal,
} = useCreatorWorkspaceShell()
</script>

<template>
  <section class="creator-section">
    <div class="creator-section-head">
      <div>
        <h3>复盘报告</h3>
      </div>
      <div v-if="selectedTaskId" class="creator-action-row">
        <button
          type="button"
          class="creator-secondary-action"
          :disabled="isExportingReportMarkdown"
          @click="downloadReportMarkdown"
        >
          {{ isExportingReportMarkdown ? '导出中...' : '导出复盘 Markdown' }}
        </button>
        <span v-if="feedbackReport" class="creator-parse-status">
          {{ feedbackReport.parseStatus }}
        </span>
        <button
          v-if="feedbackReport"
          type="button"
          class="creator-primary-button"
          @click="openResultModal('feedbackReport')"
        >
          打开分析结果
        </button>
      </div>
    </div>

    <p v-if="!feedbackReport" class="creator-report-empty">
      还没有反馈报告，请先提交观众反馈并完成分析。
    </p>
  </section>
</template>

<style scoped>
.creator-report-empty {
  margin: 0;
  color: var(--muted);
}
</style>
