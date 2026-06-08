<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  fetchImportReferenceVideo,
  getReferenceVideoHybridIndexStatus,
  getReferenceVideoIndexStatus,
  getReferenceVideoItemHybridIndexStatus,
  getReferenceVideoItemIndexStatus,
  listReferenceVideos,
  rebuildReferenceVideoHybridIndex,
  rebuildReferenceVideoIndex,
  rebuildReferenceVideoItemHybridIndex,
  rebuildReferenceVideoItemIndex,
  searchReferenceVideos,
} from '@/api/knowledge'
import type {
  ReferenceVideo,
  ReferenceVideoEvidenceItem,
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

// 子条目向量索引状态与重建（5.2c-1，small-to-big 的 small 端）。与父索引状态同形，复用同一套类型；
// vectorStoreReady 在此语义下表示「子向量库是否就绪」，子集合未就绪时重建按钮禁用。
const itemIndexStatus = ref<ReferenceVideoIndexStatus | null>(null)
const itemIndexLoading = ref(false)
const itemIndexStatusError = ref('')
const itemIndexing = ref(false)
const itemIndexResult = ref<ReferenceVideoIndexResult | null>(null)
const itemIndexError = ref('')

// 原生 hybrid 索引状态与重灌（5.2d-1）。复用同形类型；vectorStoreReady=hybrid 库是否就绪，关时重灌按钮禁用。
const hybridIndexStatus = ref<ReferenceVideoIndexStatus | null>(null)
const hybridIndexLoading = ref(false)
const hybridIndexStatusError = ref('')
const hybridIndexing = ref(false)
const hybridIndexResult = ref<ReferenceVideoIndexResult | null>(null)
const hybridIndexError = ref('')

// 子条目原生 hybrid 索引状态与重灌（5.2d-3）。复用同形类型；vectorStoreReady=子 hybrid 库是否就绪，关时重灌按钮禁用。
const childHybridIndexStatus = ref<ReferenceVideoIndexStatus | null>(null)
const childHybridIndexLoading = ref(false)
const childHybridIndexStatusError = ref('')
const childHybridIndexing = ref(false)
const childHybridIndexResult = ref<ReferenceVideoIndexResult | null>(null)
const childHybridIndexError = ref('')

// 案例检索（5.2a）：query 必填，tier / category 为可选过滤；topK 不在前端暴露，用后端默认（knowledge.rag.top-k=8）。
const searchQuery = ref('')
const searchTier = ref('')
const searchCategory = ref('')
const searchStrategy = ref('')
const searching = ref(false)
const searchError = ref('')
const searchResult = ref<ReferenceVideoSearchResult | null>(null)

// 证据按 videoId 建查找表（5.2c-2，响应方案 a）：响应里 evidence 与 items 平级、按 videoId 关联，
// 这里转成 map 让卡片渲染时 O(1) 取到「这张卡片被哪几条子条目召回」。
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

async function loadItemIndexStatus() {
  itemIndexLoading.value = true
  itemIndexStatusError.value = ''
  try {
    itemIndexStatus.value = await getReferenceVideoItemIndexStatus()
  } catch (error) {
    itemIndexStatusError.value = error instanceof Error ? error.message : String(error)
  } finally {
    itemIndexLoading.value = false
  }
}

async function rebuildItemIndex() {
  if (itemIndexing.value) {
    return
  }
  itemIndexing.value = true
  itemIndexError.value = ''
  itemIndexResult.value = null
  try {
    itemIndexResult.value = await rebuildReferenceVideoItemIndex()
    // 子条目索引只影响子集合、不改父卡片的 embedding 标记，故只刷新子索引状态、不必重载案例列表。
    await loadItemIndexStatus()
  } catch (error) {
    itemIndexError.value = error instanceof Error ? error.message : String(error)
  } finally {
    itemIndexing.value = false
  }
}

async function loadHybridIndexStatus() {
  hybridIndexLoading.value = true
  hybridIndexStatusError.value = ''
  try {
    hybridIndexStatus.value = await getReferenceVideoHybridIndexStatus()
  } catch (error) {
    hybridIndexStatusError.value = error instanceof Error ? error.message : String(error)
  } finally {
    hybridIndexLoading.value = false
  }
}

async function rebuildHybridIndex() {
  if (hybridIndexing.value) {
    return
  }
  hybridIndexing.value = true
  hybridIndexError.value = ''
  hybridIndexResult.value = null
  try {
    // 整库重灌：drop 旧 hybrid 集合 → 自建 schema 重建 → 从 MySQL 重灌，耗时随案例数增长。
    hybridIndexResult.value = await rebuildReferenceVideoHybridIndex()
    await loadHybridIndexStatus()
  } catch (error) {
    hybridIndexError.value = error instanceof Error ? error.message : String(error)
  } finally {
    hybridIndexing.value = false
  }
}

async function loadChildHybridIndexStatus() {
  childHybridIndexLoading.value = true
  childHybridIndexStatusError.value = ''
  try {
    childHybridIndexStatus.value = await getReferenceVideoItemHybridIndexStatus()
  } catch (error) {
    childHybridIndexStatusError.value = error instanceof Error ? error.message : String(error)
  } finally {
    childHybridIndexLoading.value = false
  }
}

async function rebuildChildHybridIndex() {
  if (childHybridIndexing.value) {
    return
  }
  childHybridIndexing.value = true
  childHybridIndexError.value = ''
  childHybridIndexResult.value = null
  try {
    // 整库重灌子集合：drop 旧子 hybrid 集合 → 自建 schema 重建 → 从 MySQL 重灌未删子条目。
    childHybridIndexResult.value = await rebuildReferenceVideoItemHybridIndex()
    await loadChildHybridIndexStatus()
  } catch (error) {
    childHybridIndexError.value = error instanceof Error ? error.message : String(error)
  } finally {
    childHybridIndexing.value = false
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
    case 'HYBRID':
      return '原生 hybrid（dense+BM25）'
    case 'HYBRID_WITH_SQL_FALLBACK':
      return 'hybrid + SQL 兜底'
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

// 证据情绪 / 来源标签（5.2c-2）：把后端枚举转中文，正负向在样式上区分。
function sentimentLabel(sentiment: string) {
  switch (sentiment) {
    case 'POSITIVE':
      return '正向'
    case 'NEGATIVE':
      return '负向'
    default:
      return sentiment
  }
}

function sourceTypeLabel(sourceType: string) {
  return sourceType === 'DANMAKU' ? '弹幕' : '评论'
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
  void loadItemIndexStatus()
  void loadHybridIndexStatus()
  void loadChildHybridIndexStatus()
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
      <div class="creator-section-head">
        <h3>子条目索引</h3>
        <div class="knowledge-toolbar">
          <button type="button" class="creator-secondary-action" :disabled="itemIndexLoading" @click="loadItemIndexStatus">
            {{ itemIndexLoading ? '刷新中…' : '刷新状态' }}
          </button>
          <button
            type="button"
            class="creator-primary-button"
            :disabled="itemIndexing || !itemIndexStatus?.vectorStoreReady"
            @click="rebuildItemIndex"
          >
            {{ itemIndexing ? '索引中…' : '重建子条目索引' }}
          </button>
        </div>
      </div>
      <p class="creator-inline-note">
        把优质评论 / 弹幕原文写入独立子集合，作为「父子召回（small-to-big）」的小颗粒召回端（5.2c 检索侧用）。与上方父集合索引相互独立：子集合未就绪只影响子召回、不影响案例检索。
      </p>

      <div v-if="itemIndexStatusError" class="creator-alert error-alert">
        <strong>状态加载失败</strong>
        <span>{{ itemIndexStatusError }}</span>
      </div>
      <div v-else-if="itemIndexStatus" class="creator-chip-list">
        <b>{{ itemIndexStatus.ragEnabled ? 'RAG 已启用' : 'RAG 关闭' }}</b>
        <b>{{ itemIndexStatus.vectorStoreReady ? '子向量库就绪' : '子向量库未就绪' }}</b>
        <b>已索引 {{ itemIndexStatus.indexedCount }}</b>
        <b>待索引 {{ itemIndexStatus.pendingCount }}</b>
        <b v-if="itemIndexStatus.failedCount > 0">失败 {{ itemIndexStatus.failedCount }}</b>
        <b>共 {{ itemIndexStatus.totalCount }}</b>
      </div>

      <div v-if="itemIndexResult" class="creator-alert success-alert">
        <strong>重建完成</strong>
        <span>
          本次索引 {{ itemIndexResult.indexedCount }} 条子条目<template v-if="itemIndexResult.failedCount > 0">，失败 {{ itemIndexResult.failedCount }} 条</template>。
        </span>
      </div>
      <div v-if="itemIndexError" class="creator-alert error-alert">
        <strong>重建失败</strong>
        <span>{{ itemIndexError }}</span>
      </div>
      <ul v-if="itemIndexResult && itemIndexResult.warnings.length" class="knowledge-warnings">
        <li v-for="(warning, idx) in itemIndexResult.warnings" :key="idx">{{ warning }}</li>
      </ul>
    </section>

    <section class="creator-section">
      <div class="creator-section-head">
        <h3>原生 hybrid 索引</h3>
        <div class="knowledge-toolbar">
          <button type="button" class="creator-secondary-action" :disabled="hybridIndexLoading" @click="loadHybridIndexStatus">
            {{ hybridIndexLoading ? '刷新中…' : '刷新状态' }}
          </button>
          <button
            type="button"
            class="creator-primary-button"
            :disabled="hybridIndexing || !hybridIndexStatus?.vectorStoreReady"
            @click="rebuildHybridIndex"
          >
            {{ hybridIndexing ? '重灌中…' : '重建 hybrid 索引（重灌）' }}
          </button>
        </div>
      </div>
      <p class="creator-inline-note">
        把案例卡片灌入自建 schema 的 hybrid 集合（dense 语义 + BM25 关键词），供原生混合检索用。需 Milvus 服务端 ≥2.5 并开启 knowledge.rag.hybrid；整库重灌（drop 后从 MySQL 重建，无损）。关闭时此处显示未就绪、按钮不可用。
      </p>

      <div v-if="hybridIndexStatusError" class="creator-alert error-alert">
        <strong>状态加载失败</strong>
        <span>{{ hybridIndexStatusError }}</span>
      </div>
      <div v-else-if="hybridIndexStatus" class="creator-chip-list">
        <b>{{ hybridIndexStatus.ragEnabled ? 'RAG 已启用' : 'RAG 关闭' }}</b>
        <b>{{ hybridIndexStatus.vectorStoreReady ? 'hybrid 库就绪' : 'hybrid 库未就绪' }}</b>
        <b>检索模式 {{ hybridIndexStatus.retrievalMode }}</b>
        <b>可重灌案例 {{ hybridIndexStatus.totalCount }}</b>
      </div>

      <div v-if="hybridIndexResult" class="creator-alert success-alert">
        <strong>重灌完成</strong>
        <span>
          本次灌入 {{ hybridIndexResult.indexedCount }} 条案例<template v-if="hybridIndexResult.failedCount > 0">，失败 {{ hybridIndexResult.failedCount }} 条</template>。
        </span>
      </div>
      <div v-if="hybridIndexError" class="creator-alert error-alert">
        <strong>重灌失败</strong>
        <span>{{ hybridIndexError }}</span>
      </div>
      <ul v-if="hybridIndexResult && hybridIndexResult.warnings.length" class="knowledge-warnings">
        <li v-for="(warning, idx) in hybridIndexResult.warnings" :key="idx">{{ warning }}</li>
      </ul>
    </section>

    <section class="creator-section">
      <div class="creator-section-head">
        <h3>子条目 hybrid 索引</h3>
        <div class="knowledge-toolbar">
          <button type="button" class="creator-secondary-action" :disabled="childHybridIndexLoading" @click="loadChildHybridIndexStatus">
            {{ childHybridIndexLoading ? '刷新中…' : '刷新状态' }}
          </button>
          <button
            type="button"
            class="creator-primary-button"
            :disabled="childHybridIndexing || !childHybridIndexStatus?.vectorStoreReady"
            @click="rebuildChildHybridIndex"
          >
            {{ childHybridIndexing ? '重灌中…' : '重建子 hybrid 索引（重灌）' }}
          </button>
        </div>
      </div>
      <p class="creator-inline-note">
        把优质评论 / 弹幕原文灌入子 hybrid 集合（dense 语义 + BM25 关键词），hybrid 开启时 small-to-big 子召回走它。需开启 knowledge.rag.hybrid；整库重灌（drop 后从 MySQL 重建，无损）。关闭时此处显示未就绪、按钮不可用。
      </p>

      <div v-if="childHybridIndexStatusError" class="creator-alert error-alert">
        <strong>状态加载失败</strong>
        <span>{{ childHybridIndexStatusError }}</span>
      </div>
      <div v-else-if="childHybridIndexStatus" class="creator-chip-list">
        <b>{{ childHybridIndexStatus.ragEnabled ? 'RAG 已启用' : 'RAG 关闭' }}</b>
        <b>{{ childHybridIndexStatus.vectorStoreReady ? '子 hybrid 库就绪' : '子 hybrid 库未就绪' }}</b>
        <b>检索模式 {{ childHybridIndexStatus.retrievalMode }}</b>
        <b>可重灌子条目 {{ childHybridIndexStatus.totalCount }}</b>
      </div>

      <div v-if="childHybridIndexResult" class="creator-alert success-alert">
        <strong>重灌完成</strong>
        <span>
          本次灌入 {{ childHybridIndexResult.indexedCount }} 条子条目<template v-if="childHybridIndexResult.failedCount > 0">，失败 {{ childHybridIndexResult.failedCount }} 条</template>。
        </span>
      </div>
      <div v-if="childHybridIndexError" class="creator-alert error-alert">
        <strong>重灌失败</strong>
        <span>{{ childHybridIndexError }}</span>
      </div>
      <ul v-if="childHybridIndexResult && childHybridIndexResult.warnings.length" class="knowledge-warnings">
        <li v-for="(warning, idx) in childHybridIndexResult.warnings" :key="idx">{{ warning }}</li>
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
          <b v-if="searchResult.reranked">已精排 · qwen3-rerank</b>
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
            <div v-if="evidenceByVideoId[hit.videoId]?.length" class="knowledge-evidence">
              <span class="knowledge-evidence-label">召回证据 · 观众原话</span>
              <p
                v-for="ev in evidenceByVideoId[hit.videoId]"
                :key="ev.itemId"
                class="knowledge-evidence-item"
                :class="ev.sentiment === 'NEGATIVE' ? 'is-negative' : 'is-positive'"
              >
                <b>{{ sourceTypeLabel(ev.sourceType) }} · {{ sentimentLabel(ev.sentiment) }}</b>
                {{ ev.content }}
              </p>
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

/* 召回证据（5.2c-2）：small-to-big 命中的观众原话，以「引用条」形式展示，正/负向用左色条区分。
   选择器特意带父类 .knowledge-evidence 提高特异性，压过更泛的 .knowledge-card p（否则字号/颜色被它覆盖）。 */
.knowledge-evidence {
  display: grid;
  gap: 5px;
  margin-top: 2px;
  padding-top: 8px;
  border-top: 1px dashed rgba(15, 23, 42, 0.12);
}

.knowledge-evidence-label {
  color: var(--color-ink-2);
  font-size: 12px;
  font-weight: 900;
}

.knowledge-evidence .knowledge-evidence-item {
  margin: 0;
  padding: 6px 10px;
  border-left: 3px solid transparent;
  border-radius: 6px;
  background: rgba(248, 250, 252, 0.85);
  color: var(--color-ink-2);
  font-size: 13px;
  line-height: 1.55;
}

.knowledge-evidence .knowledge-evidence-item > b {
  margin-right: 6px;
  font-size: 12px;
  font-weight: 900;
}

.knowledge-evidence .knowledge-evidence-item.is-positive {
  border-left-color: rgba(34, 197, 94, 0.66);
}

.knowledge-evidence .knowledge-evidence-item.is-positive > b {
  color: rgba(21, 128, 61, 0.92);
}

.knowledge-evidence .knowledge-evidence-item.is-negative {
  border-left-color: rgba(239, 68, 68, 0.6);
}

.knowledge-evidence .knowledge-evidence-item.is-negative > b {
  color: rgba(185, 28, 28, 0.92);
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
