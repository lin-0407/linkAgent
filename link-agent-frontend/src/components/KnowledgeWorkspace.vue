<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  fetchImportReferenceVideo,
  getReferenceVideoIndexStatus,
  listReferenceVideos,
  rebuildReferenceVideoIndex,
  searchReferenceVideos,
} from '@/api/knowledge'
import type {
  ReferenceVideo,
  ReferenceVideoImportResult,
  ReferenceVideoIndexResult,
  ReferenceVideoIndexStatus,
  ReferenceVideoSearchResult,
} from '@/types/knowledge'

// 案例层级选项：与后端 ALLOWED_TIERS 一致。
const TIER_OPTIONS = [
  { value: 'BENCHMARK', label: '标杆案例' },
  { value: 'COMPETITOR', label: '竞品案例' },
  { value: 'OWN_HISTORY', label: '自己历史' },
] as const

// 查询增强策略选项（5.2b）。空值 = 用后端配置默认（默认 REWRITE）；NONE = 显式不增强。
const STRATEGY_OPTIONS = [
  { value: '', label: '默认（后端配置）' },
  { value: 'REWRITE', label: '查询改写' },
  { value: 'HYDE', label: 'HyDE 假设文档' },
  { value: 'MULTI_QUERY', label: '多查询' },
  { value: 'NONE', label: '不增强' },
] as const

const PAGE_SIZE = 12

const form = reactive({
  bvInput: '',
  tier: 'BENCHMARK',
  category: '',
})
const importing = ref(false)
const importResult = ref<ReferenceVideoImportResult | null>(null)
const importError = ref('')

const items = ref<ReferenceVideo[]>([])
const total = ref(0)
const page = ref(1)
const filterTier = ref('')
const filterCategory = ref('')
const listLoading = ref(false)
const listError = ref('')

// 向量索引状态与重建（5.1c）。RAG 关闭时 indexStatus.vectorStoreReady=false，重建按钮禁用。
const indexStatus = ref<ReferenceVideoIndexStatus | null>(null)
const indexLoading = ref(false)
const indexStatusError = ref('')
const indexing = ref(false)
const indexResult = ref<ReferenceVideoIndexResult | null>(null)
const indexError = ref('')

// 案例检索（5.2a）：query 必填，tier / category 为可选过滤；topK 不在前端暴露，用后端默认（knowledge.rag.top-k=8）。
const searchQuery = ref('')
const searchTier = ref('')
const searchCategory = ref('')
const searchStrategy = ref('')
const searching = ref(false)
const searchError = ref('')
const searchResult = ref<ReferenceVideoSearchResult | null>(null)

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

// 导入结果分三种情况给文案：真入库 / 因 BV 重复跳过 / 脚本没采到视频。
const importSummary = computed(() => {
  const result = importResult.value
  if (!result) {
    return ''
  }
  if (result.importedCount > 0) {
    return `成功导入 ${result.importedCount} 条案例（层级 ${tierLabel(result.tier)}）。`
  }
  if (result.skippedCount > 0) {
    return '该 BV 已在案例库中，本次按 BV 幂等去重跳过。'
  }
  return '脚本未采集到可入库的视频，请换一个 BV 重试。'
})

function tierLabel(value: string) {
  return TIER_OPTIONS.find((option) => option.value === value)?.label ?? value
}

function embeddingLabel(status: string | null) {
  switch (status) {
    case 'INDEXED':
      return '已索引'
    case 'FAILED':
      return '索引失败'
    case 'PENDING':
      return '待索引'
    default:
      return status ?? '待索引'
  }
}

// 大数字压成「万」更易读；空值用占位符，避免显示 null。
function formatCount(value: number | null) {
  if (value === null || value === undefined) {
    return '—'
  }
  if (value >= 10000) {
    return `${(value / 10000).toFixed(1)}万`
  }
  return String(value)
}

async function submitFetchImport() {
  const bvInput = form.bvInput.trim()
  if (!bvInput || importing.value) {
    return
  }
  importing.value = true
  importError.value = ''
  importResult.value = null
  try {
    importResult.value = await fetchImportReferenceVideo({
      bvInput,
      tier: form.tier,
      category: form.category,
    })
    // 成功后清空 BV 输入，避免误重复提交；导入的新案例回到第一页查看。
    form.bvInput = ''
    page.value = 1
    await loadList()
  } catch (error) {
    importError.value = error instanceof Error ? error.message : String(error)
  } finally {
    importing.value = false
  }
}

async function loadList() {
  listLoading.value = true
  listError.value = ''
  try {
    const result = await listReferenceVideos({
      category: filterCategory.value,
      tier: filterTier.value,
      page: page.value,
      size: PAGE_SIZE,
    })
    items.value = result.items
    total.value = result.total
    page.value = result.page
  } catch (error) {
    listError.value = error instanceof Error ? error.message : String(error)
  } finally {
    listLoading.value = false
  }
}

function applyFilters() {
  page.value = 1
  void loadList()
}

function changePage(delta: number) {
  const next = page.value + delta
  if (next < 1 || next > totalPages.value) {
    return
  }
  page.value = next
  void loadList()
}

async function loadIndexStatus() {
  indexLoading.value = true
  indexStatusError.value = ''
  try {
    indexStatus.value = await getReferenceVideoIndexStatus()
  } catch (error) {
    indexStatusError.value = error instanceof Error ? error.message : String(error)
  } finally {
    indexLoading.value = false
  }
}

async function rebuildIndex() {
  if (indexing.value) {
    return
  }
  indexing.value = true
  indexError.value = ''
  indexResult.value = null
  try {
    indexResult.value = await rebuildReferenceVideoIndex()
    // 重建后刷新状态与列表：卡片上的「已索引/待索引」标记随之更新
    await loadIndexStatus()
    await loadList()
  } catch (error) {
    indexError.value = error instanceof Error ? error.message : String(error)
  } finally {
    indexing.value = false
  }
}

function retrievalModeLabel(mode: string) {
  return mode === 'VECTOR' ? '向量检索' : 'SQL 检索'
}

// 检索结果的实际模式标签（三态），与索引状态的两态预测 retrievalModeLabel 刻意分开：
// 这里多一个「向量 + SQL 兜底」，对应后端向量命中不足、合并兜底补足的情况，硬复用会丢掉这个信号。
function searchModeLabel(mode: string) {
  switch (mode) {
    case 'VECTOR':
      return '向量语义检索'
    case 'VECTOR_WITH_SQL_FALLBACK':
      return '向量 + SQL 兜底'
    default:
      return 'SQL 关键词检索'
  }
}

// 增强策略标签（5.2b）：把后端回显的实际生效策略转中文。
function strategyLabel(strategy: string) {
  switch (strategy) {
    case 'REWRITE':
      return '查询改写'
    case 'HYDE':
      return 'HyDE 假设文档'
    case 'MULTI_QUERY':
      return '多查询'
    case 'NONE':
      return '未增强'
    default:
      return strategy
  }
}

async function submitSearch() {
  const query = searchQuery.value.trim()
  if (!query || searching.value) {
    return
  }
  searching.value = true
  searchError.value = ''
  try {
    searchResult.value = await searchReferenceVideos({
      query,
      tier: searchTier.value,
      category: searchCategory.value,
      strategy: searchStrategy.value,
    })
  } catch (error) {
    // 检索失败清掉旧结果，避免把上一次命中误当本次结果展示。
    searchResult.value = null
    searchError.value = error instanceof Error ? error.message : String(error)
  } finally {
    searching.value = false
  }
}

onMounted(() => {
  void loadList()
  void loadIndexStatus()
})
</script>

<template>
  <div class="creator-shell">
    <header class="creator-header">
      <div>
        <p class="creator-kicker">案例库 · 跨分区参照</p>
        <h2>视频案例库</h2>
        <p>输入 BV，自动采集视频信息与评论弹幕，清洗后沉淀为可参照的创作案例。榜单批量请用离线脚本。</p>
      </div>
      <div class="creator-header-actions">
        <div class="creator-status-strip">
          <span class="active">共 {{ total }} 条案例</span>
        </div>
      </div>
    </header>

    <section class="creator-section">
      <div class="creator-section-head"><h3>采集导入</h3></div>
      <p class="creator-inline-note">
        输入单个 BV 号或视频链接，后端会显式调用采集脚本限量抓取（视频信息 + 评论 + 弹幕），清洗后入库。采集约需 10–60 秒，请耐心等待。
      </p>
      <div class="knowledge-form">
        <label>
          <span>BV 号 / 视频链接</span>
          <input
            v-model="form.bvInput"
            type="text"
            placeholder="BV1xxxxxxxxx 或 https://www.bilibili.com/video/BV..."
            :disabled="importing"
            @keyup.enter="submitFetchImport"
          />
        </label>
        <label>
          <span>案例层级</span>
          <select v-model="form.tier" :disabled="importing">
            <option v-for="option in TIER_OPTIONS" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>
        <label>
          <span>分区（可选）</span>
          <input
            v-model="form.category"
            type="text"
            placeholder="留空则用视频自身分区"
            :disabled="importing"
          />
        </label>
      </div>
      <div class="creator-action-row">
        <button
          type="button"
          class="creator-primary-button"
          :disabled="importing || !form.bvInput.trim()"
          @click="submitFetchImport"
        >
          {{ importing ? '采集中…（约 10–60 秒）' : '采集并导入' }}
        </button>
      </div>
      <div v-if="importResult" class="creator-alert success-alert">
        <strong>采集完成</strong>
        <span>{{ importSummary }}</span>
      </div>
      <div v-if="importError" class="creator-alert error-alert">
        <strong>采集失败</strong>
        <span>{{ importError }}</span>
      </div>
    </section>

    <section class="creator-section">
      <div class="creator-section-head">
        <h3>向量索引</h3>
        <div class="knowledge-toolbar">
          <button type="button" class="creator-secondary-action" :disabled="indexLoading" @click="loadIndexStatus">
            {{ indexLoading ? '刷新中…' : '刷新状态' }}
          </button>
          <button
            type="button"
            class="creator-primary-button"
            :disabled="indexing || !indexStatus?.vectorStoreReady"
            @click="rebuildIndex"
          >
            {{ indexing ? '索引中…' : '重建索引' }}
          </button>
        </div>
      </div>
      <p class="creator-inline-note">
        把案例卡片写入向量库供语义检索（5.2 用）。需开启 knowledge.rag 并配置 Embedding 与 Milvus；关闭时此处显示降级状态、重建按钮不可用。
      </p>

      <div v-if="indexStatusError" class="creator-alert error-alert">
        <strong>状态加载失败</strong>
        <span>{{ indexStatusError }}</span>
      </div>
      <div v-else-if="indexStatus" class="creator-chip-list">
        <b>{{ indexStatus.ragEnabled ? 'RAG 已启用' : 'RAG 关闭' }}</b>
        <b>{{ indexStatus.vectorStoreReady ? '向量库就绪' : '向量库未就绪' }}</b>
        <b>检索模式 {{ retrievalModeLabel(indexStatus.retrievalMode) }}</b>
        <b>已索引 {{ indexStatus.indexedCount }}</b>
        <b>待索引 {{ indexStatus.pendingCount }}</b>
        <b v-if="indexStatus.failedCount > 0">失败 {{ indexStatus.failedCount }}</b>
        <b>共 {{ indexStatus.totalCount }}</b>
      </div>

      <div v-if="indexResult" class="creator-alert success-alert">
        <strong>重建完成</strong>
        <span>
          本次索引 {{ indexResult.indexedCount }} 条<template v-if="indexResult.failedCount > 0">，失败 {{ indexResult.failedCount }} 条</template>。
        </span>
      </div>
      <div v-if="indexError" class="creator-alert error-alert">
        <strong>重建失败</strong>
        <span>{{ indexError }}</span>
      </div>
      <ul v-if="indexResult && indexResult.warnings.length" class="knowledge-warnings">
        <li v-for="(warning, idx) in indexResult.warnings" :key="idx">{{ warning }}</li>
      </ul>
    </section>

    <section class="creator-section">
      <div class="creator-section-head"><h3>案例检索</h3></div>
      <p class="creator-inline-note">
        用一句话按语义检索最相关的案例（如「开场如何快速留住观众」）。RAG 关闭或向量库未就绪时自动降级为关键词检索（SQL 兜底），结果上方标签会标明本次实际走的检索模式。
      </p>
      <div class="knowledge-toolbar">
        <input
          v-model="searchQuery"
          type="text"
          class="knowledge-search-input"
          placeholder="想参考什么？例如：美食视频封面怎么做更吸引人"
          :disabled="searching"
          @keyup.enter="submitSearch"
        />
        <select v-model="searchTier" :disabled="searching">
          <option value="">全部层级</option>
          <option v-for="option in TIER_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
        <input
          v-model="searchCategory"
          type="text"
          placeholder="按分区筛选（可选）"
          :disabled="searching"
          @keyup.enter="submitSearch"
        />
        <select v-model="searchStrategy" :disabled="searching" title="查询增强策略">
          <option v-for="option in STRATEGY_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
        <button
          type="button"
          class="creator-primary-button"
          :disabled="searching || !searchQuery.trim()"
          @click="submitSearch"
        >
          {{ searching ? '检索中…' : '检索' }}
        </button>
      </div>

      <div v-if="searchError" class="creator-alert error-alert">
        <strong>检索失败</strong>
        <span>{{ searchError }}</span>
      </div>
      <template v-else-if="searchResult">
        <div class="creator-chip-list">
          <b>检索模式 {{ searchModeLabel(searchResult.mode) }}</b>
          <b>增强策略 {{ strategyLabel(searchResult.strategy) }}</b>
          <b>命中 {{ searchResult.items.length }} 条</b>
        </div>
        <p v-if="searchResult.enhancedQueries.length" class="knowledge-enhanced">
          <span class="knowledge-enhanced-label">扩展查询</span>
          <span v-for="(q, i) in searchResult.enhancedQueries" :key="i" class="knowledge-enhanced-item">{{ q }}</span>
        </p>
        <p v-if="!searchResult.items.length" class="creator-muted">
          没有匹配的案例，换个说法，或先在上方采集 / 索引更多案例。
        </p>
        <div v-else class="knowledge-card-list">
          <article v-for="hit in searchResult.items" :key="hit.id" class="knowledge-card">
            <strong>{{ hit.title }}</strong>
            <div class="creator-chip-list">
              <b>{{ tierLabel(hit.tier) }}</b>
              <b v-if="hit.category">{{ hit.category }}</b>
              <b v-if="hit.qualityScore !== null">质量分 {{ hit.qualityScore }}</b>
              <b>{{ embeddingLabel(hit.embeddingStatus) }}</b>
            </div>
            <small>
              {{ hit.bvId || '无 BV' }} · {{ hit.source }}
              <template v-if="hit.publishTimeText"> · {{ hit.publishTimeText }}</template>
            </small>
            <p v-if="hit.highlightSummary">{{ hit.highlightSummary }}</p>
            <p v-else class="creator-muted">（暂无亮点摘要）</p>
            <div class="knowledge-stats">
              <span>播放 {{ formatCount(hit.viewCount) }}</span>
              <span>点赞 {{ formatCount(hit.likeCount) }}</span>
              <span>投币 {{ formatCount(hit.coinCount) }}</span>
              <span>收藏 {{ formatCount(hit.favoriteCount) }}</span>
              <span>弹幕 {{ formatCount(hit.danmakuCount) }}</span>
              <span>评论 {{ formatCount(hit.replyCount) }}</span>
            </div>
          </article>
        </div>
      </template>
    </section>

    <section class="creator-section">
      <div class="creator-section-head">
        <h3>案例列表</h3>
        <div class="knowledge-toolbar">
          <select v-model="filterTier" @change="applyFilters">
            <option value="">全部层级</option>
            <option v-for="option in TIER_OPTIONS" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
          <input
            v-model="filterCategory"
            type="text"
            placeholder="按分区筛选"
            @keyup.enter="applyFilters"
          />
          <button type="button" class="creator-secondary-action" @click="applyFilters">筛选</button>
          <button type="button" class="creator-secondary-action" :disabled="listLoading" @click="loadList">
            {{ listLoading ? '加载中…' : '刷新' }}
          </button>
        </div>
      </div>

      <div v-if="listError" class="creator-alert error-alert">
        <strong>列表加载失败</strong>
        <span>{{ listError }}</span>
      </div>
      <p v-else-if="!items.length && !listLoading" class="creator-muted">
        还没有案例，先在上方输入一个 BV 采集试试。
      </p>
      <div v-else class="knowledge-card-list">
        <article v-for="item in items" :key="item.id" class="knowledge-card">
          <strong>{{ item.title }}</strong>
          <div class="creator-chip-list">
            <b>{{ tierLabel(item.tier) }}</b>
            <b v-if="item.category">{{ item.category }}</b>
            <b v-if="item.qualityScore !== null">质量分 {{ item.qualityScore }}</b>
            <b>{{ embeddingLabel(item.embeddingStatus) }}</b>
          </div>
          <small>
            {{ item.bvId || '无 BV' }} · {{ item.source }}
            <template v-if="item.publishTimeText"> · {{ item.publishTimeText }}</template>
          </small>
          <p v-if="item.highlightSummary">{{ item.highlightSummary }}</p>
          <p v-else class="creator-muted">（暂无亮点摘要）</p>
          <div class="knowledge-stats">
            <span>播放 {{ formatCount(item.viewCount) }}</span>
            <span>点赞 {{ formatCount(item.likeCount) }}</span>
            <span>投币 {{ formatCount(item.coinCount) }}</span>
            <span>收藏 {{ formatCount(item.favoriteCount) }}</span>
            <span>弹幕 {{ formatCount(item.danmakuCount) }}</span>
            <span>评论 {{ formatCount(item.replyCount) }}</span>
          </div>
        </article>
      </div>

      <div v-if="total > PAGE_SIZE" class="knowledge-pager">
        <button
          type="button"
          class="creator-secondary-action"
          :disabled="page <= 1 || listLoading"
          @click="changePage(-1)"
        >
          上一页
        </button>
        <span>第 {{ page }} / {{ totalPages }} 页 · 共 {{ total }} 条</span>
        <button
          type="button"
          class="creator-secondary-action"
          :disabled="page >= totalPages || listLoading"
          @click="changePage(1)"
        >
          下一页
        </button>
      </div>
    </section>
  </div>
</template>

<!--
  案例库专用排版。其余视觉（面板/按钮/标签/提示）直接复用全局 creator-* 设计系统，这里只补本组件独有的部分。
  放 scoped 而非全局 theme.css：保持组件自包含；creator-shell 的 CSS 变量是运行时继承，scoped 内照常可用。
-->
<style scoped>
/* creator-shell 自身不带 gap：让两个区块与居中的页头（max-width 1540 居中）对齐，并补上区块间距 */
.creator-section {
  max-width: 1540px;
  margin: 0 auto;
}

.creator-section + .creator-section {
  margin-top: 14px;
}

.knowledge-form {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(140px, 0.6fr) minmax(0, 1fr);
  gap: 12px;
}

.knowledge-form label {
  display: grid;
  align-content: start;
  gap: 7px;
}

.knowledge-form label > span {
  color: var(--color-ink-2);
  font-size: 13px;
  font-weight: 900;
}

.knowledge-form input,
.knowledge-form select,
.knowledge-toolbar input,
.knowledge-toolbar select {
  width: 100%;
  min-height: 44px;
  padding: 0 12px;
  color: #111827;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(15, 23, 42, 0.12);
  border-radius: 8px;
  outline: none;
}

.knowledge-form input:focus,
.knowledge-form select:focus,
.knowledge-toolbar input:focus,
.knowledge-toolbar select:focus {
  border-color: rgba(0, 174, 236, 0.58);
  box-shadow: 0 0 0 4px rgba(0, 174, 236, 0.12);
}

.knowledge-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.knowledge-toolbar input,
.knowledge-toolbar select {
  width: auto;
  min-height: 38px;
  padding: 0 10px;
}

/* 检索框是该工具栏的主输入，让它拉伸占据主要宽度，过滤项与按钮跟随其后 */
.knowledge-search-input {
  flex: 1 1 280px;
  min-width: 240px;
}

/* 扩展查询回显：让 LLM 实际扩出的查询可见，便于核对增强是否合理 */
.knowledge-enhanced {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin: 0;
}

.knowledge-enhanced-label {
  color: var(--color-ink-2);
  font-size: 12px;
  font-weight: 900;
}

.knowledge-enhanced-item {
  padding: 2px 8px;
  color: rgba(44, 55, 74, 0.78);
  background: rgba(0, 174, 236, 0.08);
  border: 1px solid rgba(0, 174, 236, 0.2);
  border-radius: 999px;
  font-size: 12px;
}

.knowledge-card-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 12px;
}

.knowledge-card {
  display: grid;
  align-content: start;
  gap: 8px;
  padding: 14px;
  background: rgba(255, 255, 255, 0.82);
  border: 1px solid rgba(15, 23, 42, 0.09);
  border-radius: 8px;
}

.knowledge-card > strong {
  color: var(--color-ink);
  font-size: 15px;
  line-height: 1.45;
}

.knowledge-card small {
  color: rgba(63, 38, 49, 0.58);
  font-size: 12px;
  line-height: 1.5;
}

.knowledge-card p {
  margin: 0;
  color: var(--color-ink-2);
  font-size: 14px;
  line-height: 1.62;
}

.knowledge-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.knowledge-stats span {
  padding: 3px 9px;
  color: rgba(44, 55, 74, 0.7);
  background: rgba(248, 250, 252, 0.85);
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 999px;
  font-size: 12px;
  font-weight: 850;
}

.knowledge-pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  margin-top: 2px;
  color: rgba(63, 38, 49, 0.66);
  font-size: 13px;
}

.knowledge-warnings {
  display: grid;
  gap: 4px;
  margin: 0;
  padding-left: 18px;
  color: rgba(63, 38, 49, 0.7);
  font-size: 12px;
  line-height: 1.5;
}

@media (max-width: 820px) {
  .knowledge-form {
    grid-template-columns: 1fr;
  }
}
</style>
