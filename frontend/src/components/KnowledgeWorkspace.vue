<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { errorText } from '@/utils/error'
import { useAssistantBusinessRefresh } from '@/composables/useAssistantBusinessRefresh'
useAssistantBusinessRefresh(['references.', 'collection.', 'maintenance.'], () => loadList())
import { ChevronDown, Plus } from '@lucide/vue'
import PageHeader from '@/components/common/PageHeader.vue'
import {
  fetchImportReferenceVideo,
  getReferenceVideoAnalysisContext,
  listReferenceVideos,
  refreshReferenceVideoPublicMetadata,
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
import KnowledgeAutoCollection from '@/components/knowledge/KnowledgeAutoCollection.vue'
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

const pageSize = ref(0)
const TOPIC_SEARCH_PAGE_SIZE = 5

const importing = ref(false)
const importPanelOpen = ref(false)
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
let listRequestVersion = 0

// 单卡回源会启动 Python 进程，因此页面自动补齐严格限制为两个并发任务。
const PUBLIC_METADATA_REFRESH_CONCURRENCY = 2
const publicMetadataRefreshQueue: string[] = []
const publicMetadataRefreshAttemptedVideoIds = new Set<string>()
let publicMetadataRefreshInFlight = 0

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
    showNotice('error', '采集失败', errorText(error, '采集失败，请稍后重试'))
  } finally {
    importing.value = false
  }
}

async function loadList() {
  if (!pageSize.value) return
  const requestVersion = ++listRequestVersion
  listLoading.value = true
  listError.value = ''
  try {
    const result = await listReferenceVideos({
      category: filterCategory.value,
      tier: filterTier.value,
      page: page.value,
      size: pageSize.value,
    })
    // 窗口缩放和筛选可能同时请求，只有最后一次请求可以更新当前分页。
    if (requestVersion !== listRequestVersion) return
    items.value = result.items
    total.value = result.total
    page.value = result.page
  } catch (error) {
    if (requestVersion !== listRequestVersion) return
    listError.value = errorText(error, '操作失败，请稍后重试')
    showNotice('error', '列表加载失败', listError.value)
  } finally {
    if (requestVersion === listRequestVersion) listLoading.value = false
  }
}

function handlePageSizeChange(size: number) {
  if (size === pageSize.value) return
  // 让缩放前当前页的第一条案例仍落在新页内，避免读到一半突然回到第一页。
  const firstItemIndex = (page.value - 1) * pageSize.value
  pageSize.value = size
  page.value = Math.floor(firstItemIndex / size) + 1
  void loadList()
}

function mergeReferenceVideo(updated: ReferenceVideo) {
  items.value = items.value.map((item) => (
    item.videoId === updated.videoId ? updated : item
  ))
}

function drainPublicMetadataRefreshQueue() {
  while (
    publicMetadataRefreshInFlight < PUBLIC_METADATA_REFRESH_CONCURRENCY
    && publicMetadataRefreshQueue.length > 0
  ) {
    const videoId = publicMetadataRefreshQueue.shift()
    if (!videoId) {
      continue
    }

    publicMetadataRefreshInFlight += 1
    void refreshReferenceVideoPublicMetadata(videoId)
      .then(mergeReferenceVideo)
      .catch(() => {
        // 自动补封面失败时保留当前卡片，避免单张图片影响整个案例列表。
      })
      .finally(() => {
        publicMetadataRefreshInFlight -= 1
        drainPublicMetadataRefreshQueue()
      })
  }
}

function schedulePublicMetadataRefresh(video: ReferenceVideo) {
  if (!video.bvId || publicMetadataRefreshAttemptedVideoIds.has(video.videoId)) {
    return
  }

  // 页面生命周期内只允许每个案例自动回源一次，避免坏图反复触发脚本。
  publicMetadataRefreshAttemptedVideoIds.add(video.videoId)
  publicMetadataRefreshQueue.push(video.videoId)
  drainPublicMetadataRefreshQueue()
}

function handleFilterChange(tier: string, category: string) {
  filterTier.value = tier
  filterCategory.value = category
  page.value = 1
  void loadList()
}

function handlePageChange(targetPage: number) {
  if (targetPage < 1 || targetPage > Math.max(1, Math.ceil(total.value / pageSize.value)) || targetPage === page.value) {
    return
  }
  page.value = targetPage
  void loadList()
}

function handleAutoCollectionFinished() {
  page.value = 1
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
    searchError.value = errorText(error, '操作失败，请稍后重试')
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
    showNotice('error', '案例内容加载失败', errorText(error, '案例内容加载失败，请稍后重试'))
  } finally {
    analysisLoadingVideoId.value = ''
  }
}

onBeforeUnmount(() => {
  // 离开页面后不再接收旧列表结果，避免卸载期间弹出过期错误。
  listRequestVersion += 1
})
</script>

<template>
  <div class="creator-shell knowledge-shell">
    <PageHeader
      kicker="参考案例"
      title="给下一条视频，找点灵感"
      description="看看同分区的好内容，找到值得借鉴的表达。"
    >
      <template #actions>
        <div class="creator-status-strip">
          <span class="active">共 {{ total }} 条案例</span>
        </div>
      </template>
    </PageHeader>

    <section class="creator-section knowledge-workspace-section">
      <NotificationToast
        :key="notice?.id ?? 0"
        :type="notice?.type ?? 'success'"
        :title="notice?.title"
        :message="notice?.message ?? ''"
        @close="closeNotice"
      />

      <KnowledgeAutoCollection @finished="handleAutoCollectionFinished" />

      <KnowledgeVideoList
        :items="items"
        :total="total"
        :page="page"
        :loading="listLoading"
        :error="listError"
        :tier="filterTier"
        :category="filterCategory"
        :page-size="pageSize"
        :developer-mode="developerMode"
        @page-change="handlePageChange"
        @page-size-change="handlePageSizeChange"
        @filter-change="handleFilterChange"
        @refresh="loadList"
        @refresh-public-metadata="schedulePublicMetadataRefresh"
        @open-analysis="openVideoAnalysis"
        @open-competitor="openCompetitorAnalysis"
      />

      <div class="knowledge-tools">
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

        <div class="knowledge-import-disclosure">
          <button
            type="button"
            class="creator-secondary-action knowledge-import-toggle"
            :aria-expanded="importPanelOpen"
            aria-controls="knowledge-import-panel"
            @click="importPanelOpen = !importPanelOpen"
          >
            <Plus :size="16" :stroke-width="1.8" aria-hidden="true" />
            添加参考案例
            <ChevronDown
              :size="16"
              :stroke-width="1.8"
              class="knowledge-import-chevron"
              :class="{ 'is-open': importPanelOpen }"
              aria-hidden="true"
            />
          </button>
          <div v-show="importPanelOpen" id="knowledge-import-panel" class="knowledge-import-panel">
            <KnowledgeImportForm
              :importing="importing"
              :clear-token="importClearToken"
              @import="submitFetchImport"
            />
          </div>
        </div>
      </div>
    </section>

    <CompetitorAnalysisModal
      :target="competitorTarget"
      @close="competitorTarget = null"
    />
  </div>
</template>

<style scoped>
.knowledge-workspace-section {
  width: min(1280px, calc(100vw - 48px));
  max-width: none;
  margin: 0 auto;
}

.creator-header {
  width: min(1280px, calc(100vw - 48px));
  margin-inline: auto;
}

/* 先展示带封面的案例列表，工具区随后出现，确保窄屏首屏仍然以视频内容为主。 */
.knowledge-tools {
  display: grid;
  gap: var(--s4);
  padding-top: var(--s4);
  border-top: 1px solid var(--border);
}

.knowledge-tools > :deep(.knowledge-block) {
  min-width: 0;
}

.knowledge-import-disclosure {
  display: grid;
  justify-items: start;
  gap: var(--s3);
  padding-top: var(--s3);
  border-top: 1px solid var(--border);
}

.knowledge-import-toggle {
  gap: var(--s2);
  min-height: 40px;
}

.knowledge-import-chevron {
  transition: transform var(--dur-fast) ease;
}

.knowledge-import-chevron.is-open {
  transform: rotate(180deg);
}

.knowledge-import-panel {
  width: 100%;
}

/* 折叠按钮已经承担区块标题，展开后隐藏重复标题以减少无效层级。 */
.knowledge-import-panel :deep(.knowledge-import-block > .creator-section-head) {
  display: none;
}

.knowledge-import-panel :deep(.knowledge-import-block) {
  gap: 0;
}

@media (max-width: 820px) {
  .knowledge-workspace-section {
    width: 100%;
  }

  .creator-header {
    width: 100%;
  }

  .knowledge-import-toggle {
    width: 100%;
    min-height: 44px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .knowledge-import-chevron {
    transition: none;
  }
}
</style>
