<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  fetchImportReferenceVideo,
  getReferenceVideoAnalysisContext,
  listReferenceVideos,
  topicSearchReferenceVideos,
} from '@/api/knowledge'
import type {
  ReferenceVideo,
  ReferenceVideoImportResult,
} from '@/types/knowledge'
import {
  KNOWLEDGE_VIDEO_CONTEXT_EVENT,
  type KnowledgeVideoContextEventDetail,
} from '@/utils/agentContext'
import CompetitorAnalysisModal from '@/components/creator/CompetitorAnalysisModal.vue'
import NotificationToast from '@/components/NotificationToast.vue'
import KnowledgeImportForm from '@/components/knowledge/KnowledgeImportForm.vue'
import KnowledgeVideoList from '@/components/knowledge/KnowledgeVideoList.vue'
import KnowledgeTopicSearch from '@/components/knowledge/KnowledgeTopicSearch.vue'
import { knowledgeTierLabel } from '@/components/knowledge/knowledgeDisplay'

const props = withDefaults(
  defineProps<{
    developerMode?: boolean
  }>(),
  {
    developerMode: false,
  },
)

const developerMode = computed(() => props.developerMode)

const PAGE_SIZE = 8
const TOPIC_SEARCH_PAGE_SIZE = 5

const importing = ref(false)
// BV 输入仅在父组件确认采集成功后清空，失败时保留用户输入便于直接重试。
const importClearToken = ref(0)

type KnowledgeNotice = {
  id: number
  type: 'success' | 'error'
  title: string
  message: string
}

const notice = ref<KnowledgeNotice | null>(null)
let noticeSequence = 0

const items = ref<ReferenceVideo[]>([])
const total = ref(0)
const page = ref(1)
const filterTier = ref('')
const filterCategory = ref('')
const listLoading = ref(false)
const listError = ref('')

const searchQuery = ref('')
const searchTier = ref('')
const searchCategory = ref('')
const searchStrategy = ref('')
const searching = ref(false)
const searchError = ref('')
const searchResult = ref<Awaited<ReturnType<typeof topicSearchReferenceVideos>> | null>(null)

const analysisLoadingVideoId = ref('')

const competitorTarget = ref<ReferenceVideo | null>(null)

function openCompetitorAnalysis(video: ReferenceVideo) {
  competitorTarget.value = video
}

function importSummary(result: ReferenceVideoImportResult) {
  if (result.importedCount > 0) {
    return `成功导入 ${result.importedCount} 条案例（层级 ${knowledgeTierLabel(result.tier)}）。`
  }
  if (result.skippedCount > 0) {
    return '该 BV 已在案例库中，本次按 BV 幂等去重跳过。'
  }
  return '没有采集到可入库的视频，请换一个 BV 重试。'
}

function showNotice(type: KnowledgeNotice['type'], title: string, message: string) {
  notice.value = { id: ++noticeSequence, type, title, message }
}

function closeNotice() {
  notice.value = null
}

async function submitFetchImport(videoId: string, tier: string, category: string) {
  if (importing.value) {
    return
  }
  importing.value = true
  try {
    const result = await fetchImportReferenceVideo({
      bvInput: videoId,
      tier,
      category,
    })
    showNotice('success', '采集完成', importSummary(result))
    importClearToken.value += 1
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

function handleFilterChange(tier: string, category: string) {
  filterTier.value = tier
  filterCategory.value = category
  page.value = 1
  void loadList()
}

function handlePageChange(targetPage: number) {
  if (targetPage < 1 || targetPage > Math.max(1, Math.ceil(total.value / PAGE_SIZE)) || targetPage === page.value) {
    return
  }
  page.value = targetPage
  void loadList()
}

async function submitSearch(query: string, tier: string, category: string, strategy: string, targetPage: number) {
  if (searching.value) {
    return
  }
  searchQuery.value = query
  searchTier.value = tier
  searchCategory.value = category
  searchStrategy.value = strategy
  searching.value = true
  searchError.value = ''
  try {
    searchResult.value = await topicSearchReferenceVideos({
      query,
      tier,
      category,
      strategy,
      page: targetPage,
      size: TOPIC_SEARCH_PAGE_SIZE,
    })
  } catch (error) {
    searchResult.value = null
    searchError.value = error instanceof Error ? error.message : String(error)
    showNotice('error', '检索失败', searchError.value)
  } finally {
    searching.value = false
  }
}

function handleResultPageChange(targetPage: number) {
  void submitSearch(searchQuery.value, searchTier.value, searchCategory.value, searchStrategy.value, targetPage)
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
        <h2>案例检索</h2>
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
        <KnowledgeImportForm
          :importing="importing"
          :clear-token="importClearToken"
          @import="submitFetchImport"
        />
        <KnowledgeTopicSearch
          :searching="searching"
          :result="searchResult"
          :search-query="searchQuery"
          :search-tier="searchTier"
          :search-category="searchCategory"
          :search-strategy="searchStrategy"
          :page-size="TOPIC_SEARCH_PAGE_SIZE"
          :developer-mode="developerMode"
          :analysis-loading-video-id="analysisLoadingVideoId"
          @search="submitSearch"
          @result-page-change="handleResultPageChange"
          @open-analysis="openVideoAnalysis"
          @open-competitor="openCompetitorAnalysis"
        />
      </div>

      <KnowledgeVideoList
        :items="items"
        :total="total"
        :page="page"
        :loading="listLoading"
        :error="listError"
        :tier="filterTier"
        :category="filterCategory"
        :page-size="PAGE_SIZE"
        :developer-mode="developerMode"
        @page-change="handlePageChange"
        @filter-change="handleFilterChange"
        @refresh="loadList"
        @open-analysis="openVideoAnalysis"
        @open-competitor="openCompetitorAnalysis"
      />
    </section>

    <CompetitorAnalysisModal
      :target="competitorTarget"
      @close="competitorTarget = null"
    />
  </div>
</template>

<style scoped>
.knowledge-workspace-section {
  width: min(1540px, calc(100vw - 96px));
  max-width: none;
  margin: 0 auto;
}

/* 两个高频入口并列，减少创作者在导入和检索之间的页面滚动。 */
.knowledge-top-grid {
  display: grid;
  grid-template-columns: minmax(0, 0.92fr) minmax(0, 1.08fr);
  align-items: start;
  gap: var(--s4);
}

.knowledge-top-grid > :deep(.knowledge-block) {
  min-width: 0;
}

.knowledge-top-grid > :deep(.knowledge-block + .knowledge-block) {
  padding-top: 0;
  padding-left: var(--s4);
  border-top: 0;
  border-left: 1px solid var(--border);
}

/* 案例列表保持在导入和检索区之后，恢复原有连续浏览流程。 */
.knowledge-top-grid + :deep(.knowledge-block) {
  padding-top: var(--s4);
  border-top: 1px solid var(--border);
}

@media (max-width: 1280px) {
  .knowledge-top-grid {
    grid-template-columns: 1fr;
  }

  .knowledge-top-grid > :deep(.knowledge-block + .knowledge-block) {
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
}
</style>
