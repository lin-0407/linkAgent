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

    <article v-if="feedbackReport" class="creator-result-entry">
      <div>
        <strong>复盘报告已生成</strong>
        <span>
          先看一句话结论、保留项、修改清单和下一期选题；需要归档时可导出 Markdown。
        </span>
      </div>
      <button
        type="button"
        class="creator-primary-button"
        @click="openResultModal('feedbackReport')"
      >
        查看完整报告
      </button>
    </article>

    <article v-else class="creator-empty-result">
      <strong>还没有反馈报告</strong>
      <span>先提交观众反馈并点击“读懂反馈”，生成后这里会给出下一期行动建议。</span>
    </article>
  </section>
</template>
