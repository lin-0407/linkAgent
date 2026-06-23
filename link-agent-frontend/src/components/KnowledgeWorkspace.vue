<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  fetchImportReferenceVideo,
  getReferenceVideoAnalysisContext,
  listReferenceVideos,
  topicSearchReferenceVideos,
} from '@/api/knowledge'
import type {
  ReferenceVideo,
  ReferenceVideoEvidenceItem,
  ReferenceVideoImportResult,
  ReferenceVideoMatchedTopic,
  ReferenceVideoTopicSearchResult,
} from '@/types/knowledge'
import {
  KNOWLEDGE_VIDEO_CONTEXT_EVENT,
  type KnowledgeVideoContextEventDetail,
} from '@/utils/agentContext'

const props = withDefaults(
  defineProps<{
    developerMode?: boolean
  }>(),
  {
    developerMode: false,
  },
)

const developerMode = computed(() => props.developerMode)

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
const TOPIC_SEARCH_PAGE_SIZE = 5

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

// 主题优先检索：query 命中主题中块，后端再按质量信号返回每批 5 张视频卡片。
const searchQuery = ref('')
const searchTier = ref('')
const searchCategory = ref('')
const searchStrategy = ref('')
const searching = ref(false)
const searchError = ref('')
const searchResult = ref<ReferenceVideoTopicSearchResult | null>(null)
const analysisLoadingVideoId = ref('')
const analysisError = ref('')

// 当前批次主题命中按 videoId 分组。卡片只展示和自己有关的主题，避免把未展示候选的主题解释混进来。
const matchedTopicsByVideoId = computed(() => {
  const map: Record<string, ReferenceVideoMatchedTopic[]> = {}
  for (const topic of searchResult.value?.matchedTopics ?? []) {
    const bucket = map[topic.videoId] ?? (map[topic.videoId] = [])
    bucket.push(topic)
  }
  return map
})

// 当前批次相关评论弹幕按 videoId 分组。它比主题更接近排序依据，所以卡片里直接展示。
const evidenceByVideoId = computed(() => {
  const map: Record<string, ReferenceVideoEvidenceItem[]> = {}
  for (const group of searchResult.value?.evidence ?? []) {
    map[group.videoId] = group.items
  }
  return map
})

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
  return '没有采集到可入库的视频，请换一个 BV 重试。'
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

function chunkTypeLabel(chunkType: string) {
  switch (chunkType) {
    case 'TITLE_PACKAGE':
      return '标题包装'
    case 'CONTENT_POSITIONING':
      return '内容定位'
    case 'AUDIENCE_FEEDBACK_SUMMARY':
      return '观众反馈'
    default:
      return chunkType || '主题'
  }
}

function sentimentLabel(sentiment: string) {
  switch (sentiment) {
    case 'POSITIVE':
      return '正向'
    case 'NEGATIVE':
      return '负向'
    default:
      return sentiment || '中性'
  }
}

function sourceTypeLabel(sourceType: string) {
  return sourceType === 'DANMAKU' ? '弹幕' : '评论'
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

function qualityScoreLabel(video: ReferenceVideo) {
  if (video.qualityScoreReliable && video.qualityScore !== null) {
    return `质量分 ${video.qualityScore}`
  }
  if (video.rawQualityScore !== null) {
    return '质量样本不足'
  }
  return ''
}

function qualityScoreTitle(video: ReferenceVideo) {
  if (video.qualityScoreReliable && video.qualityScore !== null) {
    return `同分区有效样本 ${video.qualitySampleCount} 条`
  }
  if (video.rawQualityScore !== null) {
    return `同分区有效样本 ${video.qualitySampleCount} 条，暂不展示相对质量分`
  }
  return ''
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

// 检索结果的实际模式标签：主题向量不可用时，后端会退回 SQL 质量信号兜底。
function searchModeLabel(mode: string) {
  switch (mode) {
    case 'TOPIC_VECTOR':
      return '主题向量检索'
    case 'TOPIC_HYBRID':
      return '主题混合检索'
    case 'SQL':
      return 'SQL 质量兜底'
    default:
      return mode || 'SQL 质量兜底'
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

// targetPage 表示第几批结果：1=top1-5，2=top6-10，最多由后端限制到 4。
async function submitSearch(targetPage = 1) {
  const query = searchQuery.value.trim()
  if (!query || searching.value) {
    return
  }
  searching.value = true
  searchError.value = ''
  analysisError.value = ''
  try {
    searchResult.value = await topicSearchReferenceVideos({
      query,
      tier: searchTier.value,
      category: searchCategory.value,
      strategy: searchStrategy.value,
      page: targetPage,
      size: TOPIC_SEARCH_PAGE_SIZE,
    })
  } catch (error) {
    // 检索失败清掉旧结果，避免把上一次命中误当本次结果展示。
    searchResult.value = null
    searchError.value = error instanceof Error ? error.message : String(error)
  } finally {
    searching.value = false
  }
}

function refreshSearchCards() {
  if (!searchResult.value || searching.value) {
    return
  }
  if (!searchResult.value.hasMore) {
    return
  }
  void submitSearch(searchResult.value.page + 1)
}

async function openVideoAnalysis(hit: ReferenceVideo) {
  if (analysisLoadingVideoId.value) {
    return
  }
  analysisLoadingVideoId.value = hit.videoId
  analysisError.value = ''
  try {
    const context = await getReferenceVideoAnalysisContext(hit.videoId)
    const detail: KnowledgeVideoContextEventDetail = {
      query: searchQuery.value.trim(),
      context,
    }
    window.dispatchEvent(new CustomEvent(KNOWLEDGE_VIDEO_CONTEXT_EVENT, { detail }))
  } catch (error) {
    analysisError.value = error instanceof Error ? error.message : String(error)
  } finally {
    analysisLoadingVideoId.value = ''
  }
}

onMounted(() => {
  void loadList()
})
</script>

<template>
  <div class="creator-shell">
    <header class="creator-header">
      <div>
        <p class="creator-kicker">参考案例</p>
        <h2>找灵感</h2>
        <p>用一句话描述想借鉴的方向，查看相近视频为什么值得参考，以及观众是怎么反馈的。</p>
      </div>
      <div class="creator-header-actions">
        <div class="creator-status-strip">
          <span class="active">共 {{ total }} 条案例</span>
        </div>
      </div>
    </header>

    <section class="creator-section">
      <div class="creator-section-head"><h3>添加参考案例</h3></div>
      <p class="creator-inline-note">
        输入单个 BV 号或视频链接，系统会整理视频信息、评论和弹幕，沉淀成可参考的案例。采集约需 10-60 秒，请耐心等待。
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
      <div class="creator-section-head"><h3>找灵感</h3></div>
      <p class="creator-inline-note">
        可以输入“开场如何留住观众”“标题怎么更像教程”等问题，系统会找出更接近的案例和观众原话。
      </p>
      <div class="knowledge-toolbar">
        <input
          v-model="searchQuery"
          type="text"
          class="knowledge-search-input"
          placeholder="想参考什么？例如：美食视频封面怎么做更吸引人"
          :disabled="searching"
          @keyup.enter="submitSearch()"
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
          @keyup.enter="submitSearch()"
        />
        <select
          v-if="developerMode"
          v-model="searchStrategy"
          :disabled="searching"
          title="查询增强策略"
        >
          <option v-for="option in STRATEGY_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
        <button
          type="button"
          class="creator-primary-button"
          :disabled="searching || !searchQuery.trim()"
          @click="submitSearch()"
        >
          {{ searching ? '检索中…' : '检索' }}
        </button>
      </div>

      <div v-if="searchError" class="creator-alert error-alert">
        <strong>检索失败</strong>
        <span>{{ searchError }}</span>
      </div>
      <div v-if="analysisError" class="creator-alert error-alert">
        <strong>上下文加载失败</strong>
        <span>{{ analysisError }}</span>
      </div>
      <template v-if="!searchError && searchResult">
        <div class="creator-chip-list">
          <b v-if="developerMode">检索模式 {{ searchModeLabel(searchResult.mode) }}</b>
          <b v-if="developerMode">增强策略 {{ strategyLabel(searchResult.strategy) }}</b>
          <b v-if="developerMode && searchResult.reranked">已精排 · qwen3-rerank</b>
          <b>第 {{ searchResult.page }} / {{ searchResult.maxPage }} 批</b>
          <b>展示 {{ searchResult.cards.length }} 张</b>
        </div>
        <p v-if="developerMode && searchResult.enhancedQueries.length" class="knowledge-enhanced">
          <span class="knowledge-enhanced-label">扩展查询</span>
          <span v-for="(q, i) in searchResult.enhancedQueries" :key="i" class="knowledge-enhanced-item">{{ q }}</span>
        </p>
        <p v-if="!searchResult.cards.length" class="creator-muted">
          没有匹配的案例，换个说法，或先在上方添加更多参考案例。
        </p>
        <div v-else class="knowledge-card-list">
          <button
            v-for="hit in searchResult.cards"
            :key="hit.id"
            type="button"
            class="knowledge-card knowledge-card-button"
            :disabled="!!analysisLoadingVideoId"
            @click="openVideoAnalysis(hit)"
          >
            <span class="knowledge-card-title">{{ hit.title }}</span>
            <span class="creator-chip-list">
              <b>{{ tierLabel(hit.tier) }}</b>
              <b v-if="hit.category">{{ hit.category }}</b>
              <b v-if="qualityScoreLabel(hit)" :title="qualityScoreTitle(hit)">{{ qualityScoreLabel(hit) }}</b>
              <b v-if="developerMode">{{ embeddingLabel(hit.embeddingStatus) }}</b>
            </span>
            <small>
              {{ hit.bvId || '无 BV' }} · {{ hit.source }}
              <template v-if="hit.publishTimeText"> · {{ hit.publishTimeText }}</template>
            </small>
            <span v-if="hit.highlightSummary" class="knowledge-card-summary">
              {{ hit.highlightSummary }}
            </span>
            <span v-else class="knowledge-card-summary creator-muted">（暂无亮点摘要）</span>
            <span class="knowledge-stats">
              <span>播放 {{ formatCount(hit.viewCount) }}</span>
              <span>点赞 {{ formatCount(hit.likeCount) }}</span>
              <span>投币 {{ formatCount(hit.coinCount) }}</span>
              <span>收藏 {{ formatCount(hit.favoriteCount) }}</span>
              <span>弹幕 {{ formatCount(hit.danmakuCount) }}</span>
              <span>评论 {{ formatCount(hit.replyCount) }}</span>
            </span>
            <span v-if="matchedTopicsByVideoId[hit.videoId]?.length" class="knowledge-topic-hits">
              <span class="knowledge-topic-hits-label">为什么推荐它</span>
              <span
                v-for="topic in matchedTopicsByVideoId[hit.videoId]"
                :key="topic.chunkId"
                class="knowledge-topic-hit"
              >
                <b>{{ chunkTypeLabel(topic.chunkType) }} · {{ topic.chunkTitle }}</b>
                {{ topic.preview }}
              </span>
            </span>
            <span v-if="evidenceByVideoId[hit.videoId]?.length" class="knowledge-evidence">
              <span class="knowledge-evidence-label">观众怎么说</span>
              <span
                v-for="ev in evidenceByVideoId[hit.videoId]"
                :key="ev.itemId"
                class="knowledge-evidence-item"
                :class="ev.sentiment === 'NEGATIVE' ? 'is-negative' : 'is-positive'"
              >
                <b>{{ sourceTypeLabel(ev.sourceType) }} · {{ sentimentLabel(ev.sentiment) }}</b>
                {{ ev.content }}
              </span>
            </span>
            <span class="knowledge-card-action">
              {{ analysisLoadingVideoId === hit.videoId ? '加载案例内容...' : '用这个案例帮我改当前视频' }}
            </span>
          </button>
        </div>
        <div v-if="searchResult.cards.length" class="knowledge-pager">
          <button
            type="button"
            class="creator-secondary-action"
            :disabled="searching || !searchResult.hasMore"
            @click="refreshSearchCards"
          >
            {{ searchResult.hasMore ? '换一批' : '没有更多' }}
          </button>
          <span>最多展示 4 批，共覆盖 top20 候选</span>
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
            <b v-if="qualityScoreLabel(item)" :title="qualityScoreTitle(item)">{{ qualityScoreLabel(item) }}</b>
            <b v-if="developerMode">{{ embeddingLabel(item.embeddingStatus) }}</b>
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
  margin-top: var(--s4);
}

.knowledge-form {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) minmax(140px, 0.34fr) minmax(220px, 0.66fr);
  gap: var(--s4);
}

.knowledge-form label {
  display: grid;
  align-content: start;
  gap: var(--s2);
}

.knowledge-form label > span {
  color: var(--text);
  font-size: 13px;
  font-weight: var(--fw-semibold);
}

.knowledge-form input,
.knowledge-form select,
.knowledge-toolbar input,
.knowledge-toolbar select {
  width: 100%;
  min-height: 44px;
  padding: 0 var(--s3);
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  outline: none;
}

.knowledge-form input:focus,
.knowledge-form select:focus,
.knowledge-toolbar input:focus,
.knowledge-toolbar select:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 4px var(--accent-ring);
}

.knowledge-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s2);
  align-items: center;
  padding-bottom: var(--s3);
  border-bottom: 1px solid var(--border);
}

.knowledge-toolbar input,
.knowledge-toolbar select {
  width: auto;
  min-height: 38px;
  padding: 0 10px;
}

/* 检索框是该工具栏的主输入，让它拉伸占据主要宽度，过滤项与按钮跟随其后 */
.knowledge-search-input {
  flex: 1 1 360px;
  min-width: 280px;
}

/* 扩展查询回显：让 LLM 实际扩出的查询可见，便于核对增强是否合理 */
.knowledge-enhanced {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s2);
  margin: 0;
}

.knowledge-enhanced-label {
  color: var(--muted);
  font-size: 12px;
  font-weight: var(--fw-semibold);
}

.knowledge-enhanced-item {
  padding: 2px 8px;
  color: var(--text);
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  font-size: 12px;
}

.knowledge-card-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
  gap: var(--s4);
}

.knowledge-card {
  display: grid;
  align-content: start;
  gap: var(--s3);
  padding: var(--s4);
  color: inherit;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
}

.knowledge-card-button {
  width: 100%;
  text-align: left;
  cursor: pointer;
  transition:
    border-color 180ms ease,
    box-shadow 180ms ease,
    background-color 180ms ease;
}

.knowledge-card-button:hover:not(:disabled),
.knowledge-card-button:focus-visible {
  background: #fff;
  border-color: var(--accent);
  box-shadow: inset 3px 0 0 var(--accent), var(--sh-sm);
}

.knowledge-card-button:disabled {
  cursor: wait;
  opacity: 0.72;
}

.knowledge-card > strong {
  color: var(--ink);
  font-size: 15px;
  line-height: 1.45;
}

.knowledge-card-title {
  color: var(--ink);
  font-size: 15px;
  font-weight: var(--fw-semibold);
  line-height: 1.45;
}

.knowledge-card small {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.5;
}

.knowledge-card p,
.knowledge-card-summary {
  margin: 0;
  color: var(--text);
  font-size: 14px;
  line-height: 1.62;
}

.knowledge-stats {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s2);
}

.knowledge-stats span {
  padding: 3px 9px;
  color: var(--muted);
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  font-size: 12px;
  font-weight: var(--fw-medium);
}

.knowledge-topic-hits {
  display: grid;
  gap: var(--s2);
  padding-top: var(--s3);
  border-top: 1px dashed rgba(15, 23, 42, 0.12);
}

.knowledge-topic-hits-label {
  color: var(--muted);
  font-size: 12px;
  font-weight: var(--fw-semibold);
}

.knowledge-topic-hit {
  display: grid;
  gap: 4px;
  padding: 8px 10px;
  color: var(--text);
  overflow-wrap: anywhere;
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  font-size: 13px;
  line-height: 1.55;
}

.knowledge-topic-hit b {
  color: var(--ink);
  font-size: 12px;
  font-weight: var(--fw-semibold);
}

.knowledge-card-action {
  justify-self: start;
  min-height: 34px;
  padding: 7px 12px;
  color: var(--accent);
  background: var(--accent-tint);
  border: 1px solid var(--accent-ring);
  border-radius: var(--r-sm);
  font-size: 13px;
  font-weight: var(--fw-semibold);
}

/* 召回证据（5.2c-2）：small-to-big 命中的观众原话，以「引用条」形式展示，正/负向用左色条区分。
   选择器特意带父类 .knowledge-evidence 提高特异性，压过更泛的 .knowledge-card p（否则字号/颜色被它覆盖）。 */
.knowledge-evidence {
  display: grid;
  gap: var(--s2);
  margin-top: var(--s1);
  padding-top: var(--s3);
  border-top: 1px dashed rgba(15, 23, 42, 0.12);
}

.knowledge-evidence-label {
  color: var(--muted);
  font-size: 12px;
  font-weight: var(--fw-semibold);
}

.knowledge-evidence .knowledge-evidence-item {
  margin: 0;
  padding: 6px 10px;
  border-left: 3px solid transparent;
  border-radius: var(--r-sm);
  background: var(--surface-sub);
  color: var(--text);
  font-size: 13px;
  line-height: 1.55;
}

.knowledge-evidence .knowledge-evidence-item > b {
  margin-right: 6px;
  font-size: 12px;
  font-weight: var(--fw-semibold);
}

.knowledge-evidence .knowledge-evidence-item.is-positive {
  border-left-color: var(--ok);
}

.knowledge-evidence .knowledge-evidence-item.is-positive > b {
  color: var(--ok);
}

.knowledge-evidence .knowledge-evidence-item.is-negative {
  border-left-color: var(--danger);
}

.knowledge-evidence .knowledge-evidence-item.is-negative > b {
  color: var(--danger);
}

.knowledge-pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  margin-top: 2px;
  color: var(--muted);
  font-size: 13px;
}

@media (max-width: 820px) {
  .knowledge-form {
    grid-template-columns: 1fr;
  }
}
</style>
