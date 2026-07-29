<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listLlmApiCallLogs } from '@/api/usage'
import type {
  LlmApiCallRecord,
  LlmApiCallStatus,
  LlmApiModelCategory,
} from '@/types/creator'
import type { LlmApiCallLogFilters, LlmApiCallLogPage } from '@/types/usage'

const PAGE_SIZE = 20

type FilterForm = {
  startTime: string
  endTime: string
  modelName: string
  scene: string
  modelCategory: '' | LlmApiModelCategory
  status: '' | LlmApiCallStatus
}

function pad(value: number) {
  return String(value).padStart(2, '0')
}

function toLocalDateTimeInput(date: Date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function createDefaultFilters(): FilterForm {
  const now = new Date()
  const startOfDay = new Date(now)
  startOfDay.setHours(0, 0, 0, 0)
  return {
    startTime: toLocalDateTimeInput(startOfDay),
    endTime: toLocalDateTimeInput(now),
    modelName: '',
    scene: '',
    modelCategory: '',
    status: '',
  }
}

const filters = ref<FilterForm>(createDefaultFilters())
const appliedFilters = ref<FilterForm>({ ...filters.value })
const result = ref<LlmApiCallLogPage | null>(null)
const currentPage = ref(1)
const loading = ref(false)
const loadError = ref('')
const expandedCallIds = ref<Set<string>>(new Set())

const totalPages = computed(() => Math.max(1, Math.ceil((result.value?.total ?? 0) / PAGE_SIZE)))

function buildRequestFilters(): LlmApiCallLogFilters {
  const active = appliedFilters.value
  return {
    startTime: active.startTime || undefined,
    endTime: active.endTime || undefined,
    modelName: active.modelName.trim() || undefined,
    scene: active.scene.trim() || undefined,
    modelCategory: active.modelCategory || undefined,
    status: active.status || undefined,
    page: currentPage.value,
    pageSize: PAGE_SIZE,
  }
}

async function loadLogs() {
  loading.value = true
  loadError.value = ''
  try {
    result.value = await listLlmApiCallLogs(buildRequestFilters())
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : String(error)
  } finally {
    loading.value = false
  }
}

function search() {
  appliedFilters.value = { ...filters.value }
  currentPage.value = 1
  expandedCallIds.value = new Set()
  void loadLogs()
}

function resetFilters() {
  filters.value = createDefaultFilters()
  search()
}

function changePage(page: number) {
  if (loading.value || page < 1 || page > totalPages.value || page === currentPage.value) return
  currentPage.value = page
  expandedCallIds.value = new Set()
  void loadLogs()
}

function toggleDetails(callId: string) {
  const next = new Set(expandedCallIds.value)
  if (next.has(callId)) next.delete(callId)
  else next.add(callId)
  expandedCallIds.value = next
}

function formatNumber(value: number | null | undefined) {
  return value == null ? '未返回' : new Intl.NumberFormat('zh-CN').format(value)
}

function formatDuration(value: number | null | undefined) {
  if (value == null) return '未返回'
  if (value < 1000) return `${value} ms`
  if (value < 60_000) return `${(value / 1000).toFixed(1)} s`
  return `${(value / 60_000).toFixed(1)} min`
}

function formatDateTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  })
    .format(date)
    .replaceAll('/', '-')
}

function categoryLabel(category: LlmApiModelCategory) {
  return {
    TEXT: '文本模型',
    EMBEDDING: '向量化',
    RERANK: '重排序',
  }[category]
}

function statusLabel(status: LlmApiCallStatus) {
  return {
    SUCCESS: '成功',
    FAILED: '失败',
    SKIPPED: '跳过',
  }[status]
}

function shortId(value: string | null) {
  if (!value) return '未记录'
  return value.length > 14 ? `${value.slice(0, 8)}...${value.slice(-4)}` : value
}

function displayInputCount(record: LlmApiCallRecord) {
  if (record.inputCount == null) return '不适用'
  return `${record.inputCount} 条输入`
}

onMounted(() => {
  void loadLogs()
})
</script>

<template>
  <main class="usage-log-page">
    <header class="usage-log-heading">
      <div>
        <span class="usage-log-kicker">模型可观测性</span>
        <h1>使用日志</h1>
      </div>
      <p>查看文本模型、向量化与重排序的每次调用记录</p>
    </header>

    <section class="usage-log-query" aria-labelledby="usage-log-filter-title">
      <h2 id="usage-log-filter-title" class="sr-only">筛选使用日志</h2>
      <form class="usage-log-filter-grid" @submit.prevent="search">
        <label class="usage-log-field usage-log-time-field">
          <span>开始时间</span>
          <input v-model="filters.startTime" type="datetime-local" :max="filters.endTime || undefined" />
        </label>
        <label class="usage-log-field usage-log-time-field">
          <span>结束时间</span>
          <input v-model="filters.endTime" type="datetime-local" :min="filters.startTime || undefined" />
        </label>
        <label class="usage-log-field">
          <span>模型名称</span>
          <input v-model="filters.modelName" type="search" maxlength="100" placeholder="例如 qwen" />
        </label>
        <label class="usage-log-field">
          <span>业务场景</span>
          <input v-model="filters.scene" type="search" maxlength="100" placeholder="例如发布前优化" />
        </label>
        <label class="usage-log-field">
          <span>模型类型</span>
          <select v-model="filters.modelCategory">
            <option value="">所有类型</option>
            <option value="TEXT">文本模型</option>
            <option value="EMBEDDING">向量化</option>
            <option value="RERANK">重排序</option>
          </select>
        </label>
        <label class="usage-log-field">
          <span>调用状态</span>
          <select v-model="filters.status">
            <option value="">所有状态</option>
            <option value="SUCCESS">成功</option>
            <option value="FAILED">失败</option>
            <option value="SKIPPED">跳过</option>
          </select>
        </label>
        <div class="usage-log-actions">
          <button type="button" class="usage-log-button secondary" :disabled="loading" @click="resetFilters">
            重置
          </button>
          <button type="submit" class="usage-log-button primary" :disabled="loading">
            {{ loading ? '查询中...' : '查询' }}
          </button>
        </div>
      </form>

      <div class="usage-log-summary" aria-live="polite">
        <div>
          <span>调用</span>
          <strong>{{ formatNumber(result?.summary.callCount ?? 0) }}</strong>
        </div>
        <div>
          <span>总 Token</span>
          <strong>{{ formatNumber(result?.summary.totalTokens) }}</strong>
        </div>
        <div>
          <span>平均耗时</span>
          <strong>{{ formatDuration(result?.summary.averageElapsedMs) }}</strong>
        </div>
        <div :class="{ alert: (result?.summary.failedCount ?? 0) > 0 }">
          <span>失败</span>
          <strong>{{ formatNumber(result?.summary.failedCount ?? 0) }}</strong>
        </div>
      </div>
    </section>

    <section class="usage-log-results" aria-labelledby="usage-log-result-title">
      <header class="usage-log-results-head">
        <div>
          <h2 id="usage-log-result-title">调用明细</h2>
          <span>共 {{ formatNumber(result?.total ?? 0) }} 条</span>
        </div>
        <button type="button" class="usage-log-refresh" :disabled="loading" @click="loadLogs">
          {{ loading ? '刷新中...' : '刷新' }}
        </button>
      </header>

      <div v-if="loadError" class="usage-log-state error" role="alert">
        <strong>日志读取失败</strong>
        <span>{{ loadError }}</span>
        <button type="button" class="usage-log-button secondary" @click="loadLogs">重新加载</button>
      </div>

      <div v-else-if="loading && !result" class="usage-log-state">
        <span class="usage-log-loader" aria-hidden="true"></span>
        <span>正在读取调用记录...</span>
      </div>

      <div v-else-if="result && result.items.length > 0" class="usage-log-table-wrap">
        <table class="usage-log-table">
          <thead>
            <tr>
              <th scope="col">时间 / 状态</th>
              <th scope="col">业务场景</th>
              <th scope="col">模型</th>
              <th scope="col">类型</th>
              <th scope="col">Tokens</th>
              <th scope="col">耗时</th>
              <th scope="col" class="usage-log-detail-column">详情</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="record in result.items" :key="record.callId">
              <tr class="usage-log-row">
                <td>
                  <time :datetime="record.createTime">{{ formatDateTime(record.createTime) }}</time>
                  <span class="usage-log-status" :class="record.status.toLowerCase()">
                    {{ statusLabel(record.status) }}
                  </span>
                </td>
                <td>
                  <strong>{{ record.workflowStepName || record.scene || '未记录场景' }}</strong>
                  <small>{{ record.taskId ? `任务 ${shortId(record.taskId)}` : '通用调用' }}</small>
                </td>
                <td>
                  <strong>{{ record.modelName || '未返回模型名' }}</strong>
                  <small>{{ record.scene || '无场景备注' }}</small>
                </td>
                <td><span class="usage-log-category">{{ categoryLabel(record.modelCategory) }}</span></td>
                <td>
                  <strong>{{ formatNumber(record.promptTokens) }} / {{ formatNumber(record.completionTokens) }}</strong>
                  <small>合计 {{ formatNumber(record.totalTokens) }}</small>
                </td>
                <td>
                  <strong>{{ formatDuration(record.elapsedMs) }}</strong>
                  <small>{{ displayInputCount(record) }}</small>
                </td>
                <td class="usage-log-detail-column">
                  <button
                    type="button"
                    class="usage-log-detail-button"
                    :aria-expanded="expandedCallIds.has(record.callId)"
                    @click="toggleDetails(record.callId)"
                  >
                    {{ expandedCallIds.has(record.callId) ? '收起' : '展开' }}
                  </button>
                </td>
              </tr>
              <tr v-if="expandedCallIds.has(record.callId)" class="usage-log-detail-row">
                <td colspan="7">
                  <dl>
                    <div><dt>调用 ID</dt><dd>{{ record.callId }}</dd></div>
                    <div><dt>追踪 ID</dt><dd>{{ record.traceId || '未记录' }}</dd></div>
                    <div><dt>请求 ID</dt><dd>{{ record.requestId || '未记录' }}</dd></div>
                    <div><dt>工作流会话</dt><dd>{{ record.workflowSessionId || '未记录' }}</dd></div>
                    <div><dt>工作流阶段</dt><dd>{{ record.workflowStage || '未记录' }}</dd></div>
                    <div><dt>工作流步骤</dt><dd>{{ record.workflowStepId || '未记录' }}</dd></div>
                  </dl>
                  <p v-if="record.errorMessage" class="usage-log-error-message">
                    <strong>失败原因</strong>
                    <span>{{ record.errorMessage }}</span>
                  </p>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>

      <div v-else class="usage-log-state empty">
        <strong>当前条件下没有调用记录</strong>
        <span>调整时间范围或清空筛选条件后重新查询</span>
      </div>

      <footer v-if="result && result.total > 0" class="usage-log-pagination">
        <span>第 {{ currentPage }} / {{ totalPages }} 页</span>
        <div>
          <button type="button" :disabled="currentPage <= 1 || loading" @click="changePage(currentPage - 1)">
            上一页
          </button>
          <button type="button" :disabled="currentPage >= totalPages || loading" @click="changePage(currentPage + 1)">
            下一页
          </button>
        </div>
      </footer>
    </section>
  </main>
</template>

<style scoped>
.usage-log-page {
  min-height: calc(100vh - var(--surface-topbar-height));
  padding: 22px clamp(14px, 2vw, 28px) 72px;
  color: var(--text);
  background: #f7f9fc;
}

.usage-log-heading,
.usage-log-query,
.usage-log-results {
  width: min(1540px, 100%);
  margin-inline: auto;
}

.usage-log-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 20px;
  padding: 0 2px 16px;
}

.usage-log-heading > div {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.usage-log-kicker {
  color: var(--accent-strong);
  font-size: 12px;
  font-weight: var(--fw-bold);
}

.usage-log-heading h1,
.usage-log-results-head h2 {
  margin: 0;
  color: var(--ink);
  letter-spacing: 0;
}

.usage-log-heading h1 {
  font-size: 24px;
  line-height: 1.2;
}

.usage-log-heading p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
}

.usage-log-query,
.usage-log-results {
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  box-shadow: var(--sh-sm);
}

.usage-log-filter-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(190px, 1.2fr)) repeat(4, minmax(150px, 1fr)) auto;
  align-items: end;
  gap: 10px;
  padding: 14px;
}

.usage-log-field {
  display: grid;
  min-width: 0;
  gap: 6px;
}

.usage-log-field > span {
  color: var(--muted);
  font-size: 11px;
  font-weight: var(--fw-semibold);
}

.usage-log-field input,
.usage-log-field select {
  width: 100%;
  height: 38px;
  min-width: 0;
  padding: 0 10px;
  color: var(--ink);
  background: #fbfcfe;
  border: 1px solid var(--border-strong);
  border-radius: var(--r-sm);
  outline: none;
}

.usage-log-field input:focus,
.usage-log-field select:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-tint);
}

.usage-log-actions {
  display: flex;
  gap: 8px;
}

.usage-log-button,
.usage-log-refresh,
.usage-log-detail-button,
.usage-log-pagination button {
  min-height: 38px;
  padding: 0 14px;
  color: var(--text);
  background: var(--surface);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-sm);
  font-weight: var(--fw-semibold);
  cursor: pointer;
}

.usage-log-button.primary {
  color: #fff;
  background: var(--accent);
  border-color: var(--accent);
}

.usage-log-button:hover:not(:disabled),
.usage-log-refresh:hover:not(:disabled),
.usage-log-detail-button:hover:not(:disabled),
.usage-log-pagination button:hover:not(:disabled) {
  color: var(--accent-strong);
  border-color: var(--accent);
}

.usage-log-button.primary:hover:not(:disabled) {
  color: #fff;
  background: var(--accent-hover);
}

.usage-log-button:disabled,
.usage-log-refresh:disabled,
.usage-log-pagination button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.usage-log-summary {
  display: flex;
  align-items: center;
  gap: 0;
  padding: 10px 14px;
  background: var(--surface-sub);
  border-top: 1px solid var(--border);
}

.usage-log-summary > div {
  display: flex;
  align-items: baseline;
  gap: 7px;
  min-width: 130px;
  padding: 0 18px;
  border-right: 1px solid var(--border);
}

.usage-log-summary > div:first-child {
  padding-left: 0;
}

.usage-log-summary > div:last-child {
  border-right: 0;
}

.usage-log-summary span {
  color: var(--muted);
  font-size: 12px;
}

.usage-log-summary strong {
  color: var(--ink);
  font-family: var(--font-code);
  font-size: 14px;
}

.usage-log-summary .alert strong {
  color: var(--danger);
}

.usage-log-results {
  margin-top: 14px;
}

.usage-log-results-head {
  display: flex;
  min-height: 54px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 8px 14px;
  border-bottom: 1px solid var(--border);
}

.usage-log-results-head > div {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.usage-log-results-head h2 {
  font-size: 16px;
}

.usage-log-results-head span {
  color: var(--muted);
  font-size: 12px;
}

.usage-log-refresh,
.usage-log-detail-button {
  min-height: 32px;
  padding-inline: 10px;
  font-size: 12px;
}

.usage-log-table-wrap {
  width: 100%;
  overflow-x: auto;
}

.usage-log-table {
  width: 100%;
  min-width: 1120px;
  border-collapse: collapse;
  table-layout: fixed;
}

.usage-log-table th,
.usage-log-table td {
  padding: 11px 12px;
  text-align: left;
  vertical-align: middle;
  border-bottom: 1px solid var(--border);
}

.usage-log-table th {
  color: var(--muted);
  background: #fbfcfe;
  font-size: 11px;
  font-weight: var(--fw-semibold);
}

.usage-log-table th:nth-child(1) { width: 184px; }
.usage-log-table th:nth-child(2) { width: 210px; }
.usage-log-table th:nth-child(3) { width: 190px; }
.usage-log-table th:nth-child(4) { width: 110px; }
.usage-log-table th:nth-child(5) { width: 180px; }
.usage-log-table th:nth-child(6) { width: 120px; }
.usage-log-table th:nth-child(7) { width: 70px; }

.usage-log-row:hover td {
  background: rgba(0, 174, 236, 0.035);
}

.usage-log-table td > strong,
.usage-log-table td > small,
.usage-log-table time {
  display: block;
  min-width: 0;
}

.usage-log-table td > strong,
.usage-log-table time {
  overflow: hidden;
  color: var(--ink);
  font-size: 12px;
  font-weight: var(--fw-semibold);
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.usage-log-table td:nth-child(5) > strong,
.usage-log-table td:nth-child(6) > strong,
.usage-log-table time {
  font-family: var(--font-code);
  font-weight: 500;
}

.usage-log-table td > small {
  margin-top: 3px;
  overflow: hidden;
  color: var(--faint);
  font-size: 11px;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.usage-log-status,
.usage-log-category {
  display: inline-flex;
  width: fit-content;
  align-items: center;
  min-height: 22px;
  margin-top: 5px;
  padding: 0 7px;
  border-radius: var(--r-pill);
  font-size: 11px;
  font-weight: var(--fw-semibold);
}

.usage-log-status.success {
  color: var(--ok);
  background: rgba(22, 163, 74, 0.08);
}

.usage-log-status.failed {
  color: var(--danger);
  background: rgba(220, 38, 38, 0.08);
}

.usage-log-status.skipped {
  color: var(--warn);
  background: rgba(217, 119, 6, 0.09);
}

.usage-log-category {
  margin-top: 0;
  color: var(--accent-strong);
  background: var(--accent-tint);
}

.usage-log-detail-row td {
  padding: 14px;
  background: var(--surface-sub);
}

.usage-log-detail-row dl {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px 18px;
  margin: 0;
}

.usage-log-detail-row dl > div {
  min-width: 0;
}

.usage-log-detail-row dt {
  color: var(--muted);
  font-size: 11px;
}

.usage-log-detail-row dd {
  margin: 4px 0 0;
  overflow-wrap: anywhere;
  color: var(--ink);
  font-family: var(--font-code);
  font-size: 11px;
}

.usage-log-error-message {
  display: grid;
  gap: 4px;
  margin: 12px 0 0;
  padding: 10px;
  color: var(--danger);
  background: rgba(220, 38, 38, 0.06);
  border: 1px solid rgba(220, 38, 38, 0.16);
  border-radius: var(--r-sm);
  font-size: 12px;
}

.usage-log-state {
  display: grid;
  min-height: 260px;
  place-content: center;
  justify-items: center;
  gap: 8px;
  padding: 24px;
  color: var(--muted);
  text-align: center;
}

.usage-log-state strong {
  color: var(--ink);
}

.usage-log-state.error span {
  max-width: 560px;
  color: var(--danger);
  overflow-wrap: anywhere;
}

.usage-log-loader {
  width: 24px;
  height: 24px;
  border: 2px solid var(--border-strong);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: usage-log-spin 800ms linear infinite;
}

.usage-log-pagination {
  display: flex;
  min-height: 54px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 8px 14px;
  color: var(--muted);
  border-top: 1px solid var(--border);
  font-size: 12px;
}

.usage-log-pagination > div {
  display: flex;
  gap: 8px;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

@keyframes usage-log-spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 1320px) {
  .usage-log-filter-grid {
    grid-template-columns: repeat(4, minmax(150px, 1fr));
  }

  .usage-log-actions {
    grid-column: span 2;
    justify-content: flex-end;
  }
}

@media (max-width: 820px) {
  .usage-log-page {
    min-height: auto;
    padding: 14px 10px 64px;
  }

  .usage-log-heading {
    display: grid;
    gap: 6px;
    padding-bottom: 12px;
  }

  .usage-log-heading p {
    font-size: 12px;
  }

  .usage-log-filter-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    padding: 12px;
  }

  .usage-log-time-field,
  .usage-log-actions {
    grid-column: span 2;
  }

  .usage-log-actions .usage-log-button {
    flex: 1;
  }

  .usage-log-summary {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }

  .usage-log-summary > div,
  .usage-log-summary > div:first-child {
    min-width: 0;
    padding: 3px 8px;
    border-right: 0;
  }

  .usage-log-detail-row dl {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 480px) {
  .usage-log-heading > div {
    display: grid;
    gap: 4px;
  }

  .usage-log-filter-grid {
    grid-template-columns: 1fr;
  }

  .usage-log-field,
  .usage-log-time-field,
  .usage-log-actions {
    grid-column: 1;
  }

  .usage-log-results-head {
    align-items: flex-start;
  }

  .usage-log-detail-row dl {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-motion: reduce) {
  .usage-log-loader {
    animation: none;
  }
}
</style>
