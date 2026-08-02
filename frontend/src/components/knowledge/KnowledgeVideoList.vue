<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowRight, Play } from '@lucide/vue'
import type { ReferenceVideo } from '@/types/knowledge'
import {
  formatKnowledgeCount,
  KNOWLEDGE_TIER_OPTIONS,
  knowledgeEmbeddingLabel,
  knowledgeQualityScoreLabel,
  knowledgeQualityScoreTitle,
  knowledgeTierLabel,
} from './knowledgeDisplay'

const props = defineProps<{
  items: ReferenceVideo[]
  total: number
  page: number
  loading: boolean
  error: string
  tier: string
  category: string
  pageSize: number
  developerMode: boolean
}>()

const emit = defineEmits<{
  'page-change': [page: number]
  'filter-change': [tier: string, category: string]
  refresh: []
  'refresh-public-metadata': [video: ReferenceVideo]
  'open-analysis': [video: ReferenceVideo]
  'open-competitor': [video: ReferenceVideo]
}>()

const filterCategoryInput = ref(props.category)
const detailTarget = ref<ReferenceVideo | null>(null)
const cardListRef = ref<HTMLElement | null>(null)
const failedCoverItems = ref(new Map<string, ReferenceVideo>())
let missingCoverObserver: IntersectionObserver | null = null

watch(() => props.category, (val) => {
  filterCategoryInput.value = val
})

watch(() => props.items, (items) => {
  if (detailTarget.value) {
    detailTarget.value = items.find((item) => item.videoId === detailTarget.value?.videoId)
      ?? detailTarget.value
  }
  void observeMissingCoverCards()
}, { flush: 'post' })

function shouldShowCover(video: ReferenceVideo) {
  return !!video.coverUrl && failedCoverItems.value.get(video.videoId) !== video
}

function handleCoverError(video: ReferenceVideo) {
  if (!video.coverUrl || failedCoverItems.value.get(video.videoId) === video) {
    return
  }

  // 先隐藏加载失败的图片，刷新失败时继续展示稳定占位而不是浏览器裂图。
  failedCoverItems.value.set(video.videoId, video)
  emit('refresh-public-metadata', video)
}

async function observeMissingCoverCards() {
  await nextTick()
  if (!missingCoverObserver) {
    return
  }

  missingCoverObserver.disconnect()
  cardListRef.value
    ?.querySelectorAll<HTMLElement>('[data-cover-refresh-video-id]')
    .forEach((element) => missingCoverObserver?.observe(element))
}

// 空封面没有 img 可依赖原生懒加载，只在卡片接近视口时才请求后端回源。
onMounted(() => {
  missingCoverObserver = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) {
        return
      }

      missingCoverObserver?.unobserve(entry.target)
      const videoId = (entry.target as HTMLElement).dataset.coverRefreshVideoId
      const video = props.items.find((item) => item.videoId === videoId)
      if (video && video.bvId && !video.coverUrl) {
        emit('refresh-public-metadata', video)
      }
    })
  }, { rootMargin: '200px 0px' })
  void observeMissingCoverCards()
})

onBeforeUnmount(() => {
  missingCoverObserver?.disconnect()
  missingCoverObserver = null
})

function formatExactCount(value: number | null) {
  if (value === null || value === undefined) {
    return '—'
  }
  return value.toLocaleString('zh-CN')
}

function openDetail(video: ReferenceVideo) {
  detailTarget.value = video
}

function closeDetail() {
  detailTarget.value = null
}

function openCompetitorFromDetail(video: ReferenceVideo) {
  closeDetail()
  emit('open-competitor', video)
}

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))

type PagerItem = {
  key: string
  page: number | null
}

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

  let startPage = Math.max(2, props.page - 1)
  let endPage = Math.min(lastPage - 1, props.page + 1)
  if (props.page <= 4) {
    startPage = 2
    endPage = 5
  } else if (props.page >= lastPage - 3) {
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

function onTierChange(event: Event) {
  const select = event.target as HTMLSelectElement
  emit('filter-change', select.value, filterCategoryInput.value)
}

function emitFilterChange() {
  emit('filter-change', props.tier, filterCategoryInput.value)
}

function changePage(delta: number) {
  goToPage(props.page + delta)
}

function goToPage(targetPage: number | null) {
  if (targetPage === null || targetPage < 1 || targetPage > totalPages.value || targetPage === props.page) {
    return
  }
  emit('page-change', targetPage)
}
</script>

<template>
  <div class="knowledge-block">
    <div class="creator-section-head knowledge-list-head">
      <div class="knowledge-list-title">
        <h3>案例列表</h3>
        <span v-if="total">共 {{ total }} 条，每页 {{ pageSize }} 条</span>
      </div>
      <div class="knowledge-toolbar knowledge-list-toolbar">
        <select :value="tier" @change="onTierChange">
          <option value="">全部层级</option>
          <option v-for="option in KNOWLEDGE_TIER_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
        <input
          v-model="filterCategoryInput"
          type="text"
          placeholder="按分区筛选"
          @keyup.enter="emitFilterChange"
        />
        <button type="button" class="creator-secondary-action" @click="emitFilterChange">筛选</button>
        <button type="button" class="creator-secondary-action" :disabled="loading" @click="emit('refresh')">
          {{ loading ? '加载中…' : '刷新' }}
        </button>
      </div>
    </div>

    <p v-if="!items.length && !loading && !error" class="creator-muted">
      还没有案例，先在上方输入一个 BV 采集试试。
    </p>
    <div v-else-if="!error" ref="cardListRef" class="knowledge-card-list">
      <article
        v-for="item in items"
        :key="item.id"
        class="knowledge-card"
        :data-cover-refresh-video-id="!item.coverUrl && item.bvId ? item.videoId : undefined"
      >
        <div class="knowledge-card-cover">
          <img
            v-if="shouldShowCover(item)"
            :key="item.coverUrl ?? undefined"
            :src="item.coverUrl || undefined"
            :alt="`《${item.title}》视频封面`"
            class="knowledge-card-cover-image"
            loading="lazy"
            decoding="async"
            referrerpolicy="no-referrer"
            @error="handleCoverError(item)"
          />
          <span class="knowledge-card-tier">{{ knowledgeTierLabel(item.tier) }}</span>
          <span class="knowledge-card-play" aria-hidden="true">
            <Play :size="20" :stroke-width="1.8" />
          </span>
          <div class="knowledge-card-cover-stats">
            <span>播放 {{ formatKnowledgeCount(item.viewCount) }}</span>
            <span>弹幕 {{ formatKnowledgeCount(item.danmakuCount) }}</span>
          </div>
        </div>
        <div class="knowledge-card-body">
          <strong :title="item.title">{{ item.title }}</strong>
          <small>
            {{ item.bvId || '无 BV' }}
            <template v-if="item.publishTimeText"> · {{ item.publishTimeText }}</template>
          </small>
          <div class="knowledge-card-chips">
            <b v-if="item.category">{{ item.category }}</b>
            <b v-if="knowledgeQualityScoreLabel(item)" :title="knowledgeQualityScoreTitle(item)">{{ knowledgeQualityScoreLabel(item) }}</b>
            <b v-if="developerMode">{{ knowledgeEmbeddingLabel(item.embeddingStatus) }}</b>
          </div>
          <button
            type="button"
            class="knowledge-card-detail-button"
            :aria-label="`查看《${item.title}》的详细信息`"
            @click="openDetail(item)"
          >
            查看详情
            <ArrowRight :size="16" :stroke-width="1.8" aria-hidden="true" />
          </button>
        </div>
      </article>
    </div>

    <nav v-if="total > 0" class="knowledge-pagination" aria-label="案例列表分页">
      <span class="knowledge-pagination-summary">第 {{ page }} / {{ totalPages }} 页</span>
      <div class="knowledge-pagination-controls">
        <button
          type="button"
          class="creator-secondary-action"
          :disabled="page <= 1 || loading"
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
            :disabled="loading"
            @click="goToPage(pageItem.page)"
          >
            {{ pageItem.page }}
          </button>
        </template>
        <button
          type="button"
          class="creator-secondary-action"
          :disabled="page >= totalPages || loading"
          @click="changePage(1)"
        >
          下一页
        </button>
      </div>
    </nav>

    <Teleport to="body">
      <Transition name="creator-modal">
        <div
          v-if="detailTarget"
          class="creator-modal-backdrop"
          role="presentation"
          @click.self="closeDetail"
        >
          <section
            class="creator-prompt-modal knowledge-detail-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="knowledge-detail-title"
          >
            <header class="creator-result-modal-head knowledge-detail-head">
              <div>
                <span>{{ knowledgeTierLabel(detailTarget.tier) }}</span>
                <h3 id="knowledge-detail-title">案例详情</h3>
              </div>
              <button type="button" class="creator-ghost-button" @click="closeDetail">关闭</button>
            </header>

            <div class="knowledge-detail-title-block">
              <strong>{{ detailTarget.title }}</strong>
              <small>
                {{ detailTarget.bvId || '无 BV' }} · {{ detailTarget.source }}
                <template v-if="detailTarget.publishTimeText"> · {{ detailTarget.publishTimeText }}</template>
              </small>
            </div>

            <section class="knowledge-detail-section">
              <h4>亮点摘要</h4>
              <p>{{ detailTarget.highlightSummary || '暂无亮点摘要。' }}</p>
            </section>

            <section class="knowledge-detail-section">
              <h4>视频简介</h4>
              <p>{{ detailTarget.description || '暂无视频简介。' }}</p>
            </section>

            <section class="knowledge-detail-section">
              <h4>互动数据</h4>
              <dl class="knowledge-detail-stats">
                <div><dt>播放</dt><dd>{{ formatExactCount(detailTarget.viewCount) }}</dd></div>
                <div><dt>点赞</dt><dd>{{ formatExactCount(detailTarget.likeCount) }}</dd></div>
                <div><dt>投币</dt><dd>{{ formatExactCount(detailTarget.coinCount) }}</dd></div>
                <div><dt>收藏</dt><dd>{{ formatExactCount(detailTarget.favoriteCount) }}</dd></div>
                <div><dt>弹幕</dt><dd>{{ formatExactCount(detailTarget.danmakuCount) }}</dd></div>
                <div><dt>评论</dt><dd>{{ formatExactCount(detailTarget.replyCount) }}</dd></div>
              </dl>
            </section>

            <dl class="knowledge-detail-meta">
              <div><dt>案例层级</dt><dd>{{ knowledgeTierLabel(detailTarget.tier) }}</dd></div>
              <div><dt>分区</dt><dd>{{ detailTarget.category || '未设置' }}</dd></div>
              <div><dt>质量评估</dt><dd>{{ knowledgeQualityScoreLabel(detailTarget) || '暂无评分' }}</dd></div>
              <div v-if="developerMode"><dt>索引状态</dt><dd>{{ knowledgeEmbeddingLabel(detailTarget.embeddingStatus) }}</dd></div>
            </dl>

            <footer class="knowledge-detail-actions">
              <button
                v-if="detailTarget.tier === 'COMPETITOR'"
                type="button"
                class="creator-primary-button"
                @click="openCompetitorFromDetail(detailTarget)"
              >
                对比我的创作
              </button>
              <button type="button" class="creator-secondary-action" @click="closeDetail">返回列表</button>
            </footer>
          </section>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.knowledge-block {
  display: grid;
  gap: var(--s3);
}

.knowledge-block > .creator-section-head {
  align-items: flex-start;
  padding-bottom: 0;
  border-bottom: 0;
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
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  outline: none;
}

.knowledge-toolbar input:focus,
.knowledge-toolbar select:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 4px var(--accent-ring);
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

.knowledge-card-list {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s5) var(--s4);
}

.knowledge-card {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-width: 0;
  overflow: hidden;
  color: inherit;
  background: var(--surface);
  border: 1px solid rgba(23, 32, 51, 0.1);
  border-radius: var(--r);
  box-shadow: none;
  transition:
    border-color 180ms ease,
    background-color 180ms ease;
}

.knowledge-card:hover {
  background: #f9fcfd;
  border-color: rgba(8, 126, 167, 0.32);
}

.knowledge-card-cover {
  position: relative;
  display: grid;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  place-items: center;
  background: #edf2f5;
  border-bottom: 1px solid var(--border);
}

.knowledge-card-cover::before,
.knowledge-card-cover::after {
  display: none;
}

.knowledge-card-cover-image {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.knowledge-card-tier {
  position: absolute;
  z-index: 1;
  top: var(--s3);
  left: var(--s3);
  padding: 4px 8px;
  color: #fff;
  background: #344054;
  border-radius: var(--r-sm);
  font-size: 11px;
  font-weight: var(--fw-semibold);
}

.knowledge-card-play {
  display: inline-grid;
  place-items: center;
  position: relative;
  z-index: 1;
  width: 42px;
  height: 42px;
  color: var(--accent-strong);
  background: var(--surface);
  border: 1px solid var(--border-strong);
  border-radius: var(--r);
}

.knowledge-card-cover-stats {
  position: absolute;
  z-index: 1;
  right: var(--s3);
  bottom: var(--s2);
  left: var(--s3);
  display: flex;
  justify-content: space-between;
  gap: var(--s2);
  padding: 5px 7px;
  color: var(--text);
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  font-size: 11px;
}

.knowledge-card-body {
  display: grid;
  grid-template-rows: auto auto auto 1fr;
  align-content: start;
  gap: var(--s2);
  min-width: 0;
  padding: var(--s3);
}

.knowledge-card-body > strong {
  display: -webkit-box;
  min-height: 44px;
  overflow: hidden;
  color: var(--ink);
  font-size: 14px;
  line-height: 1.45;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.knowledge-card-body small {
  overflow: hidden;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.knowledge-card-chips {
  display: flex;
  gap: 6px;
  min-height: 25px;
  overflow: hidden;
}

.knowledge-card-chips b {
  flex: 0 0 auto;
  padding: 3px 7px;
  color: var(--muted);
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  font-size: 11px;
  font-weight: var(--fw-medium);
  white-space: nowrap;
}

.knowledge-card-detail-button {
  align-self: end;
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  min-height: 36px;
  margin-top: var(--s1);
  padding: 0 11px;
  color: var(--accent-strong);
  background: var(--accent-tint);
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  font-size: 13px;
  font-weight: var(--fw-semibold);
  cursor: pointer;
  transition:
    color 160ms ease,
    background 160ms ease,
    border-color 160ms ease;
}

.knowledge-card-detail-button:hover {
  color: #fff;
  background: var(--accent);
}

.knowledge-card-detail-button:focus-visible {
  outline: 3px solid var(--accent-ring);
  outline-offset: 2px;
}

.knowledge-detail-modal {
  width: min(820px, 100%);
}

.knowledge-detail-head > div {
  display: grid;
  gap: 2px;
}

.knowledge-detail-head span {
  color: var(--accent-strong);
  font-size: 12px;
  font-weight: var(--fw-semibold);
}

.knowledge-detail-title-block {
  display: grid;
  gap: var(--s2);
  padding: var(--s4);
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: var(--r);
}

.knowledge-detail-title-block strong {
  color: var(--ink);
  font-size: 17px;
  line-height: 1.5;
}

.knowledge-detail-title-block small {
  color: var(--muted);
  line-height: 1.5;
}

.knowledge-detail-section {
  display: grid;
  gap: var(--s2);
}

.knowledge-detail-section h4 {
  margin: 0;
  color: var(--ink);
  font-size: 14px;
}

.knowledge-detail-section p {
  margin: 0;
  color: var(--text);
  font-size: 13px;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
}

.knowledge-detail-stats {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: var(--s2);
  margin: 0;
}

.knowledge-detail-stats > div {
  display: grid;
  gap: 3px;
  padding: var(--s3) var(--s2);
  text-align: center;
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
}

.knowledge-detail-stats dt,
.knowledge-detail-meta dt {
  color: var(--muted);
  font-size: 11px;
}

.knowledge-detail-stats dd,
.knowledge-detail-meta dd {
  margin: 0;
  color: var(--ink);
  font-size: 13px;
  font-weight: var(--fw-semibold);
}

.knowledge-detail-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s2) var(--s5);
  margin: 0;
  padding-top: var(--s3);
  border-top: 1px solid var(--border);
}

.knowledge-detail-meta > div {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s3);
}

.knowledge-detail-actions {
  justify-content: flex-end !important;
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

@media (max-width: 820px) {
  .knowledge-toolbar input,
  .knowledge-toolbar select,
  .knowledge-toolbar button {
    width: 100%;
  }

  .knowledge-list-toolbar {
    margin-left: 0;
  }

  .knowledge-card-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .knowledge-detail-stats {
    grid-template-columns: repeat(3, minmax(0, 1fr));
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

@media (max-width: 520px) {
  .knowledge-list-title {
    align-items: flex-start;
    flex-direction: column;
    gap: var(--s1);
  }

  .knowledge-card-list,
  .knowledge-detail-meta {
    grid-template-columns: 1fr;
  }

  .knowledge-detail-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .knowledge-detail-actions {
    display: grid !important;
  }
}
</style>
