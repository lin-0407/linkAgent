<script setup lang="ts">
import { useCreatorWorkspaceShell } from '@/composables/creator/useCreatorWorkspaceContext'

const {
  isLoadingUsageStats,
  usageSummary,
  hasSelectedTask,
  refreshUsageStats,
  formatUsageToken,
  formatDuration,
  usageCategorySummaries,
  usageCategoryLabel,
  usageCategoryOptions,
  usageCategoryFilter,
  changeUsageCategoryFilter,
  usageCallPage,
  usageCurrentPage,
  usageTotalPages,
  usageStatusClass,
  usageStatusLabel,
  formatInputCount,
  formatDate,
  shortId,
  changeUsagePage,
} = useCreatorWorkspaceShell()
</script>

<template>
  <section class="creator-section creator-usage-section">
    <div class="creator-section-head">
      <div>
        <h3>API 开销统计</h3>
      </div>
      <div class="creator-action-row">
        <span class="creator-parse-status">
          {{ isLoadingUsageStats ? '读取中' : `${usageSummary?.callCount ?? 0} 次调用` }}
        </span>
        <button
          type="button"
          class="creator-secondary-action"
          :disabled="!hasSelectedTask || isLoadingUsageStats"
          @click="refreshUsageStats(1)"
        >
          {{ isLoadingUsageStats ? '刷新中...' : '刷新统计' }}
        </button>
      </div>
    </div>

    <p class="creator-inline-note">
      这里按当前任务汇总文本 LLM、向量化模型和 Rerank 模型的调用记录，便于核对每一步 AI 调用的耗时、Token 和失败原因。
    </p>

    <div class="creator-usage-overview" aria-label="当前任务 API 开销总览">
      <article class="creator-usage-card">
        <span>总 Token</span>
        <strong>{{ formatUsageToken(usageSummary?.totalTokens) }}</strong>
        <small>仅统计模型实际返回的 token usage</small>
      </article>
      <article class="creator-usage-card">
        <span>总耗时</span>
        <strong>{{ formatDuration(usageSummary?.totalElapsedMs) }}</strong>
        <small>平均 {{ formatDuration(usageSummary?.averageElapsedMs) }}</small>
      </article>
      <article class="creator-usage-card danger">
        <span>失败调用</span>
        <strong>{{ usageSummary?.failedCount ?? 0 }}</strong>
        <small>跳过 {{ usageSummary?.skippedCount ?? 0 }} 次</small>
      </article>
    </div>

    <div class="creator-usage-category-grid" aria-label="模型分类开销">
      <article
        v-for="item in usageCategorySummaries"
        :key="item.modelCategory"
        class="creator-usage-category"
      >
        <header>
          <div>
            <span>{{ usageCategoryLabel(item.modelCategory) }}</span>
            <strong>{{ item.callCount }} 次调用</strong>
          </div>
          <small>{{ item.failedCount > 0 ? `${item.failedCount} 失败` : '无失败' }}</small>
        </header>
        <div class="creator-usage-category-metrics">
          <span>
            Token
            <b>{{ formatUsageToken(item.totalTokens) }}</b>
          </span>
          <span>
            耗时
            <b>{{ formatDuration(item.totalElapsedMs) }}</b>
          </span>
          <span>
            成功
            <b>{{ item.successCount }}</b>
          </span>
          <span>
            跳过
            <b>{{ item.skippedCount }}</b>
          </span>
        </div>
      </article>
    </div>

    <div class="creator-usage-toolbar">
      <div class="creator-usage-filter" role="group" aria-label="模型类型筛选">
        <button
          v-for="option in usageCategoryOptions"
          :key="option.value"
          type="button"
          :class="{ active: usageCategoryFilter === option.value }"
          :disabled="isLoadingUsageStats"
          @click="changeUsageCategoryFilter(option.value)"
        >
          {{ option.label }}
        </button>
      </div>
      <span>
        共 {{ usageCallPage?.total ?? 0 }} 条明细，第 {{ usageCurrentPage }} / {{ usageTotalPages }} 页
      </span>
    </div>

    <div
      v-if="usageCallPage && usageCallPage.items.length > 0"
      class="creator-usage-call-list"
      aria-label="模型调用明细"
    >
      <article
        v-for="record in usageCallPage.items"
        :key="record.callId"
        class="creator-usage-call-item"
      >
        <header>
          <div>
            <span>{{ usageCategoryLabel(record.modelCategory) }}</span>
            <strong>{{ record.scene || '未记录场景' }}</strong>
          </div>
          <b
            class="creator-usage-status"
            :class="usageStatusClass(record.status)"
          >
            {{ usageStatusLabel(record.status) }}
          </b>
        </header>
        <div class="creator-usage-call-meta">
          <span>模型：{{ record.modelName || '未返回' }}</span>
          <span>Token：{{ formatUsageToken(record.totalTokens) }}</span>
          <span>耗时：{{ formatDuration(record.elapsedMs) }}</span>
          <span>输入：{{ formatInputCount(record) }}</span>
          <span>时间：{{ formatDate(record.createTime) }}</span>
        </div>
        <div class="creator-usage-call-trace">
          <code>call {{ shortId(record.callId) }}</code>
          <code v-if="record.traceId">trace {{ shortId(record.traceId) }}</code>
          <code v-if="record.requestId">request {{ shortId(record.requestId) }}</code>
        </div>
        <p v-if="record.errorMessage" class="creator-usage-error">
          {{ record.errorMessage }}
        </p>
      </article>
    </div>

    <article v-else class="creator-empty-result">
      <strong>还没有模型调用记录</strong>
      <span>运行发布前优化、评论弹幕分析、反馈追问或证据索引后，这里会显示当前任务的调用明细。</span>
    </article>

    <div class="creator-usage-pagination">
      <button
        type="button"
        class="creator-secondary-action"
        :disabled="usageCurrentPage <= 1 || isLoadingUsageStats"
        @click="changeUsagePage(-1)"
      >
        上一页
      </button>
      <button
        type="button"
        class="creator-secondary-action"
        :disabled="usageCurrentPage >= usageTotalPages || isLoadingUsageStats"
        @click="changeUsagePage(1)"
      >
        下一页
      </button>
    </div>
  </section>
</template>
