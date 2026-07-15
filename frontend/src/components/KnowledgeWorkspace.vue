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
import CompetitorAnalysisModal from '@/components/creator/CompetitorAnalysisModal.vue'
import NotificationToast from '@/components/NotificationToast.vue'

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

// 案例卡片包含摘要和数据指标，单页控制在 8 条内，避免列表重新变成长页面。
const PAGE_SIZE = 8
const TOPIC_SEARCH_PAGE_SIZE = 5

const form = reactive({
  bvInput: '',
  tier: 'BENCHMARK',
  category: '',
})
const importing = ref(false)

type KnowledgeNotice = {
  id: number
  type: 'success' | 'error'
  title: string
  message: string
}

// 同一时间只展示一条提示，避免连续请求时多个右上角弹窗相互遮挡。
const notice = ref<KnowledgeNotice | null>(null)
let noticeSequence = 0

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

// P1-1 竞品分析弹窗状态：记录用户点击了哪个竞品卡片，传给 CompetitorAnalysisModal
const competitorTarget = ref<ReferenceVideo | null>(null)

/** 打开竞品分析弹窗 */
function openCompetitorAnalysis(video: ReferenceVideo) {
  competitorTarget.value = video
}

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

type PagerItem = {
  key: string
  page: number | null
}

// 分页器始终保留首尾页和当前页附近页码，大量案例时不把所有页码横向堆满。
const visiblePages = computed<PagerItem[]>(() => {
  const lastPage = totalPages.value
  const items: PagerItem[] = []
  const addPage = (targetPage: number) => {
    items.push({ key: `page-${targetPage}`, page: targetPage })
  }

  if (lastPage <= 7) {
    for (let targetPage = 1; targetPage <= lastPage; targetPage += 1) {
      addPage(targetPage)
    }
    return items
  }

  let startPage = Math.max(2, page.value - 1)
  let endPage = Math.min(lastPage - 1, page.value + 1)
  if (page.value <= 4) {
    startPage = 2
    endPage = 5
  } else if (page.value >= lastPage - 3) {
    startPage = lastPage - 4
    endPage = lastPage - 1
  }

  addPage(1)
  if (startPage > 2) {
    items.push({ key: 'leading-ellipsis', page: null })
  }
  for (let targetPage = startPage; targetPage <= endPage; targetPage += 1) {
    addPage(targetPage)
  }
  if (endPage < lastPage - 1) {
    items.push({ key: 'trailing-ellipsis', page: null })
  }
  addPage(lastPage)
  return items
})

// 导入结果分三种情况给文案：真入库 / 因 BV 重复跳过 / 脚本没采到视频。
function importSummary(result: ReferenceVideoImportResult) {
  if (result.importedCount > 0) {
    return `成功导入 ${result.importedCount} 条案例（层级 ${tierLabel(result.tier)}）。`
  }
  if (result.skippedCount > 0) {
    return '该 BV 已在案例库中，本次按 BV 幂等去重跳过。'
  }
  return '没有采集到可入库的视频，请换一个 BV 重试。'
}

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

function showNotice(type: KnowledgeNotice['type'], title: string, message: string) {
  notice.value = { id: ++noticeSequence, type, title, message }
}

function closeNotice() {
  notice.value = null
}

async function submitFetchImport() {
  const bvInput = form.bvInput.trim()
  if (!bvInput || importing.value) {
    return
  }
  importing.value = true
  try {
    const result = await fetchImportReferenceVideo({
      bvInput,
      tier: form.tier,
      category: form.category,
    })
    showNotice('success', '采集完成', importSummary(result))
    // 成功后清空 BV 输入，避免误重复提交；导入的新案例回到第一页查看。
    form.bvInput = ''
    page.value = 1
    await loadList()
  } catch (error) {
    showNotice('error', '采集失败', error instanceof Error ? error.message : String(error))
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
    showNotice('error', '列表加载失败', listError.value)
  } finally {
    listLoading.value = false
  }
}

function applyFilters() {
  page.value = 1
  void loadList()
}

function changePage(delta: number) {
  goToPage(page.value + delta)
}

function goToPage(targetPage: number | null) {
  if (targetPage === null || targetPage < 1 || targetPage > totalPages.value || targetPage === page.value) {
    return
  }
  page.value = targetPage
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
    showNotice('error', '检索失败', searchError.value)
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
  try {
    const context = await getReferenceVideoAnalysisContext(hit.videoId)
    const detail: KnowledgeVideoContextEventDetail = {
      query: searchQuery.value.trim(),
      context,
    }
    window.dispatchEvent(new CustomEvent(KNOWLEDGE_VIDEO_CONTEXT_EVENT, { detail }))
  } catch (error) {
    showNotice('error', '案例内容加载失败', error instanceof Error ? error.message : String(error))
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

    <section class="creator-section knowledge-workspace-section">
      <NotificationToast
        :key="notice?.id ?? 0"
        :type="notice?.type ?? 'success'"
        :title="notice?.title"
        :message="notice?.message ?? ''"
        @close="closeNotice"
      />

      <div class="knowledge-top-grid">
        <div class="knowledge-block knowledge-import-block">
          <div class="creator-section-head"><h3>添加参考案例</h3></div>
          <div class="knowledge-form">
            <label>
              <span>BV 号 / 视频链接</span>
              <input
                v-model="form.bvInput"
                type="text"
                placeholder="BV 号或视频链接"
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
                placeholder="分区"
                :disabled="importing"
              />
            </label>
            <button
              type="button"
              class="creator-primary-button knowledge-import-submit"
              :disabled="importing || !form.bvInput.trim()"
              @click="submitFetchImport"
            >
              {{ importing ? '采集中…' : '采集并导入' }}
            </button>
          </div>
        </div>

        <div class="knowledge-block knowledge-search-block">
          <div class="creator-section-head"><h3>找灵感</h3></div>
          <div class="knowledge-toolbar knowledge-search-toolbar">
            <input
              v-model="searchQuery"
              type="text"
              class="knowledge-search-input"
              placeholder="输入想借鉴的方向"
              :disabled="searching"
              @keyup.enter="submitSearch()"
            />
            <select v-model="searchTier" class="knowledge-search-tier" :disabled="searching">
              <option value="">全部层级</option>
              <option v-for="option in TIER_OPTIONS" :key="option.value" :value="option.value">
                {{ option.label }}
              </option>
            </select>
            <input
              v-model="searchCategory"
              type="text"
              class="knowledge-search-category"
              placeholder="分区"
              :disabled="searching"
              @keyup.enter="submitSearch()"
            />
            <select
              v-if="developerMode"
              v-model="searchStrategy"
              class="knowledge-search-strategy"
              :disabled="searching"
              title="查询增强策略"
            >
              <option v-for="option in STRATEGY_OPTIONS" :key="option.value" :value="option.value">
                {{ option.label }}
              </option>
            </select>
            <button
              type="button"
              class="creator-primary-button knowledge-search-submit"
              :disabled="searching || !searchQuery.trim()"
              @click="submitSearch()"
            >
              {{ searching ? '检索中…' : '检索' }}
            </button>
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
              <!-- P1-1：竞品卡片上的「对比我的创作」入口，用 @click.stop 防止触发外层按钮的 openVideoAnalysis -->
              <span
                v-if="hit.tier === 'COMPETITOR'"
                class="knowledge-card-action knowledge-card-competitor-action"
                @click.stop="openCompetitorAnalysis(hit)"
              >
                对比我的创作
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
      </div>
      </div>

      <div class="knowledge-block">
        <div class="creator-section-head knowledge-list-head">
          <div class="knowledge-list-title">
            <h3>案例列表</h3>
            <span v-if="total">共 {{ total }} 条，每页 {{ PAGE_SIZE }} 条</span>
          </div>
          <div class="knowledge-toolbar knowledge-list-toolbar">
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

        <p v-if="!items.length && !listLoading && !listError" class="creator-muted">
          还没有案例，先在上方输入一个 BV 采集试试。
        </p>
        <div v-else-if="!listError" class="knowledge-card-list">
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
            <!-- P1-1：竞品卡片上的「对比我的创作」按钮 -->
            <button
              v-if="item.tier === 'COMPETITOR'"
              type="button"
              class="creator-secondary-action knowledge-card-competitor-btn"
              @click="openCompetitorAnalysis(item)"
            >
              对比我的创作
            </button>
          </article>
        </div>

        <nav v-if="total > 0" class="knowledge-pagination" aria-label="案例列表分页">
          <span class="knowledge-pagination-summary">第 {{ page }} / {{ totalPages }} 页</span>
          <div class="knowledge-pagination-controls">
            <button
              type="button"
              class="creator-secondary-action"
              :disabled="page <= 1 || listLoading"
              @click="changePage(-1)"
            >
              上一页
            </button>
            <template v-for="pageItem in visiblePages" :key="pageItem.key">
              <span v-if="pageItem.page === null" class="knowledge-pagination-ellipsis" aria-hidden="true">...</span>
              <button
                v-else
                type="button"
                class="creator-secondary-action knowledge-page-button"
                :class="{ 'is-current': pageItem.page === page }"
                :aria-current="pageItem.page === page ? 'page' : undefined"
                :disabled="listLoading"
                @click="goToPage(pageItem.page)"
              >
                {{ pageItem.page }}
              </button>
            </template>
            <button
              type="button"
              class="creator-secondary-action"
              :disabled="page >= totalPages || listLoading"
              @click="changePage(1)"
            >
              下一页
            </button>
          </div>
        </nav>
      </div>
    </section>

    <!-- P1-1 竞品分析弹窗 -->
    <CompetitorAnalysisModal
      :target="competitorTarget"
      @close="competitorTarget = null"
    />
  </div>
</template>

<!--
  案例库专用排版。外层仍使用全局 creator-* 设计系统，这里只处理案例库内部的表单、工具栏和列表密度。
  放 scoped 而非全局 theme.css：避免影响创作台其他模块，也避开全局主题文件的协作冲突。
-->
<style scoped>
/* 与全局 creator-header 使用同一宽度规则，让页头和主体在桌面端左边线对齐。 */
.knowledge-workspace-section {
  width: min(1540px, calc(100vw - 96px));
  max-width: none;
  margin: 0 auto;
}

.knowledge-block {
  display: grid;
  gap: var(--s3);
}

/* 两个高频入口并列，减少创作者在导入和检索之间的页面滚动。 */
.knowledge-top-grid {
  display: grid;
  grid-template-columns: minmax(0, 0.92fr) minmax(0, 1.08fr);
  align-items: start;
  gap: var(--s4);
}

.knowledge-top-grid > .knowledge-block {
  min-width: 0;
}

/* 导入属于低频补充动作，收紧留白后让检索和案例列表优先占据首屏。 */
.knowledge-import-block {
  gap: var(--s2);
}

.knowledge-block + .knowledge-block {
  padding-top: var(--s4);
  border-top: 1px solid var(--border);
}

.knowledge-top-grid > .knowledge-block + .knowledge-block {
  padding-top: 0;
  padding-left: var(--s4);
  border-top: 0;
  border-left: 1px solid var(--border);
}

.knowledge-top-grid + .knowledge-block {
  padding-top: var(--s4);
  border-top: 1px solid var(--border);
}

.knowledge-block > .creator-section-head {
  align-items: flex-start;
  padding-bottom: 0;
  border-bottom: 0;
}

.knowledge-form {
  display: grid;
  grid-template-columns: minmax(280px, 1.35fr) minmax(136px, 168px) minmax(180px, 0.65fr) auto;
  align-items: end;
  max-width: 1120px;
  gap: var(--s3);
}

.knowledge-top-grid .knowledge-form {
  grid-template-columns: minmax(190px, 1.35fr) minmax(116px, 138px) minmax(130px, 0.65fr) auto;
  gap: var(--s2);
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
  min-height: 36px;
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
  padding-bottom: 0;
  border-bottom: 0;
}

.knowledge-toolbar input,
.knowledge-toolbar select {
  width: auto;
  min-height: 36px;
  padding: 0 10px;
}

.knowledge-import-submit {
  align-self: end;
  min-width: 112px;
  white-space: nowrap;
}

.knowledge-search-toolbar {
  max-width: 1180px;
}

.knowledge-top-grid .knowledge-search-toolbar {
  max-width: none;
}

/* 右侧检索工具栏需要容纳开发策略，限制各控件宽度后可稳定保持单行。 */
.knowledge-search-input {
  flex: 1 1 190px;
  min-width: 190px;
}

.knowledge-search-tier {
  flex: 0 0 112px;
  width: 112px;
}

.knowledge-search-category {
  flex: 0 1 134px;
  width: 134px;
  min-width: 112px;
}

.knowledge-search-strategy {
  flex: 0 0 146px;
  width: 146px;
}

.knowledge-search-submit {
  flex: 0 0 auto;
  min-width: 58px;
}

.knowledge-list-head {
  justify-content: space-between;
}

.knowledge-list-title {
  display: flex;
  align-items: baseline;
  gap: var(--s2);
  min-width: 0;
}

.knowledge-list-title > span {
  color: var(--muted);
  font-size: 12px;
  white-space: nowrap;
}

.knowledge-list-toolbar {
  margin-left: auto;
}

.knowledge-list-toolbar select {
  width: 126px;
}

.knowledge-list-toolbar input {
  width: 180px;
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
  grid-template-columns: 1fr;
  gap: 0;
  border-top: 1px solid rgba(23, 32, 51, 0.1);
}

.knowledge-card {
  display: grid;
  align-content: start;
  gap: 10px;
  padding: var(--s4) 0;
  color: inherit;
  background: transparent;
  border: 0;
  border-bottom: 1px solid rgba(23, 32, 51, 0.08);
  border-radius: 0;
}

.knowledge-card:last-child {
  border-bottom: 0;
}

.knowledge-card-button {
  width: 100%;
  margin: 0;
  text-align: left;
  cursor: pointer;
  transition:
    background-color 180ms ease,
    box-shadow 180ms ease;
}

.knowledge-card-button:hover:not(:disabled),
.knowledge-card-button:focus-visible {
  background: rgba(0, 174, 236, 0.05);
  box-shadow: inset 3px 0 0 var(--accent);
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
  font-size: 13px;
  line-height: 1.56;
}

.knowledge-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
}

.knowledge-stats span {
  padding: 0;
  color: var(--muted);
  background: transparent;
  border: 0;
  border-radius: 0;
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
  padding: 2px 0 2px 10px;
  color: var(--text);
  overflow-wrap: anywhere;
  background: transparent;
  border: 0;
  border-left: 2px solid var(--accent-ring);
  border-radius: 0;
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
  min-height: 30px;
  padding: 5px 10px;
  color: var(--accent);
  background: var(--accent-tint);
  border: 1px solid var(--accent-ring);
  border-radius: var(--r-sm);
  font-size: 13px;
  font-weight: var(--fw-semibold);
}

/* P1-1 竞品卡片上的「对比我的创作」按钮/操作条，与「用这个案例帮我改」并列但用不同底色区分语义 */
.knowledge-card-competitor-action {
  color: var(--success, #1e8e3e);
  background: var(--success-tint, #e6f4ea);
  border-color: var(--success-ring, #ceead6);
  cursor: pointer;
}

/* 列表卡片（article）中的竞品按钮，留出上边距与统计数据隔开 */
.knowledge-card-competitor-btn {
  margin-top: var(--s2);
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
  padding: 2px 0 2px 10px;
  border-left: 2px solid transparent;
  border-radius: 0;
  background: transparent;
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

.knowledge-pagination {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--s3);
  margin-top: var(--s2);
  padding-top: var(--s3);
  border-top: 1px solid rgba(23, 32, 51, 0.08);
}

.knowledge-pagination-summary {
  color: var(--muted);
  font-size: 13px;
}

.knowledge-pagination-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
}

.knowledge-page-button {
  width: 36px;
  min-width: 36px;
  padding: 0;
}

.knowledge-page-button.is-current {
  color: #fff;
  background: var(--accent);
  border-color: var(--accent);
}

.knowledge-page-button.is-current:hover:not(:disabled) {
  color: #fff;
  background: var(--accent-hover);
}

.knowledge-pagination-ellipsis {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  min-height: 36px;
  color: var(--muted);
}

@media (max-width: 1280px) {
  .knowledge-top-grid {
    grid-template-columns: 1fr;
  }

  .knowledge-top-grid > .knowledge-block + .knowledge-block {
    padding-top: var(--s4);
    padding-left: 0;
    border-top: 1px solid var(--border);
    border-left: 0;
  }
}

@media (max-width: 820px) {
  .knowledge-workspace-section {
    width: 100%;
  }

  .knowledge-form {
    grid-template-columns: 1fr;
  }

  .knowledge-top-grid .knowledge-form {
    grid-template-columns: 1fr;
  }

  .knowledge-import-submit {
    width: 100%;
  }

  .knowledge-toolbar input,
  .knowledge-toolbar select,
  .knowledge-toolbar button {
    width: 100%;
  }

  .knowledge-search-input,
  .knowledge-search-tier,
  .knowledge-search-category,
  .knowledge-search-strategy,
  .knowledge-search-submit {
    flex: 1 1 100%;
    width: 100%;
  }

  .knowledge-list-toolbar {
    margin-left: 0;
  }

  .knowledge-pagination {
    grid-template-columns: 1fr;
  }

  .knowledge-pagination-summary {
    text-align: center;
  }

  .knowledge-pagination-controls {
    justify-content: center;
  }
}
</style>
