<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ReferenceVideo } from '@/types/knowledge'

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
  'open-analysis': [video: ReferenceVideo]
  'open-competitor': [video: ReferenceVideo]
}>()

const TIER_OPTIONS = [
  { value: 'BENCHMARK', label: '标杆案例' },
  { value: 'COMPETITOR', label: '竞品案例' },
  { value: 'OWN_HISTORY', label: '自己历史' },
] as const

const filterCategoryInput = ref(props.category)

watch(() => props.category, (val) => {
  filterCategoryInput.value = val
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

function formatCount(value: number | null) {
  if (value === null || value === undefined) {
    return '—'
  }
  if (value >= 10000) {
    return `${(value / 10000).toFixed(1)}万`
  }
  return String(value)
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
          <option v-for="option in TIER_OPTIONS" :key="option.value" :value="option.value">
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
    <div v-else-if="!error" class="knowledge-card-list">
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
        <button
          v-if="item.tier === 'COMPETITOR'"
          type="button"
          class="creator-secondary-action knowledge-card-competitor-btn"
          @click="emit('open-competitor', item)"
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

.knowledge-card > strong {
  color: var(--ink);
  font-size: 15px;
  line-height: 1.45;
}

.knowledge-card small {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.5;
}

.knowledge-card p {
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

.knowledge-card-competitor-btn {
  margin-top: var(--s2);
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
