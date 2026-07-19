<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ReferenceVideo, ReferenceVideoTopicSearchResult, ReferenceVideoMatchedTopic, ReferenceVideoEvidenceItem } from '@/types/knowledge'

const props = defineProps<{
  searching: boolean
  result: ReferenceVideoTopicSearchResult | null
  searchQuery: string
  searchTier: string
  searchCategory: string
  searchStrategy: string
  pageSize: number
  developerMode: boolean
  analysisLoadingVideoId: string
}>()

const emit = defineEmits<{
  search: [query: string, tier: string, category: string, strategy: string, page: number]
  'result-page-change': [page: number]
  'open-analysis': [video: ReferenceVideo]
  'open-competitor': [video: ReferenceVideo]
}>()

const TIER_OPTIONS = [
  { value: 'BENCHMARK', label: '标杆案例' },
  { value: 'COMPETITOR', label: '竞品案例' },
  { value: 'OWN_HISTORY', label: '自己历史' },
] as const

const STRATEGY_OPTIONS = [
  { value: '', label: '默认（后端配置）' },
  { value: 'REWRITE', label: '查询改写' },
  { value: 'HYDE', label: 'HyDE 假设文档' },
  { value: 'MULTI_QUERY', label: '多查询' },
  { value: 'NONE', label: '不增强' },
] as const

const queryInput = ref(props.searchQuery)
const tierInput = ref(props.searchTier)
const categoryInput = ref(props.searchCategory)
const strategyInput = ref(props.searchStrategy)

watch(() => props.searchQuery, (v) => { queryInput.value = v })
watch(() => props.searchTier, (v) => { tierInput.value = v })
watch(() => props.searchCategory, (v) => { categoryInput.value = v })
watch(() => props.searchStrategy, (v) => { strategyInput.value = v })

const matchedTopicsByVideoId = computed(() => {
  const map: Record<string, ReferenceVideoMatchedTopic[]> = {}
  for (const topic of props.result?.matchedTopics ?? []) {
    const bucket = map[topic.videoId] ?? (map[topic.videoId] = [])
    bucket.push(topic)
  }
  return map
})

const evidenceByVideoId = computed(() => {
  const map: Record<string, ReferenceVideoEvidenceItem[]> = {}
  for (const group of props.result?.evidence ?? []) {
    map[group.videoId] = group.items
  }
  return map
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

function submitSearch(targetPage = 1) {
  const query = queryInput.value.trim()
  if (!query || props.searching) {
    return
  }
  emit('search', query, tierInput.value, categoryInput.value, strategyInput.value, targetPage)
}

function refreshSearchCards() {
  if (!props.result || props.searching) {
    return
  }
  if (!props.result.hasMore) {
    return
  }
  emit('result-page-change', props.result.page + 1)
}
</script>

<template>
  <div class="knowledge-block knowledge-search-block">
    <div class="creator-section-head"><h3>找灵感</h3></div>
    <div class="knowledge-toolbar knowledge-search-toolbar">
      <input
        v-model="queryInput"
        type="text"
        class="knowledge-search-input"
        placeholder="输入想借鉴的方向"
        :disabled="searching"
        @keyup.enter="submitSearch()"
      />
      <select v-model="tierInput" class="knowledge-search-tier" :disabled="searching">
        <option value="">全部层级</option>
        <option v-for="option in TIER_OPTIONS" :key="option.value" :value="option.value">
          {{ option.label }}
        </option>
      </select>
      <input
        v-model="categoryInput"
        type="text"
        class="knowledge-search-category"
        placeholder="分区"
        :disabled="searching"
        @keyup.enter="submitSearch()"
      />
      <select
        v-if="developerMode"
        v-model="strategyInput"
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
        :disabled="searching || !queryInput.trim()"
        @click="submitSearch()"
      >
        {{ searching ? '检索中…' : '检索' }}
      </button>
    </div>

    <template v-if="result">
      <div class="creator-chip-list">
        <b v-if="developerMode">检索模式 {{ searchModeLabel(result.mode) }}</b>
        <b v-if="developerMode">增强策略 {{ strategyLabel(result.strategy) }}</b>
        <b v-if="developerMode && result.reranked">已精排 · qwen3-rerank</b>
        <b>第 {{ result.page }} / {{ result.maxPage }} 批</b>
        <b>展示 {{ result.cards.length }} 张</b>
      </div>
      <p v-if="developerMode && result.enhancedQueries.length" class="knowledge-enhanced">
        <span class="knowledge-enhanced-label">扩展查询</span>
        <span v-for="(q, i) in result.enhancedQueries" :key="i" class="knowledge-enhanced-item">{{ q }}</span>
      </p>
      <p v-if="!result.cards.length" class="creator-muted">
        没有匹配的案例，换个说法，或先在上方添加更多参考案例。
      </p>
      <div v-else class="knowledge-card-list">
        <button
          v-for="hit in result.cards"
          :key="hit.id"
          type="button"
          class="knowledge-card knowledge-card-button"
          :disabled="!!analysisLoadingVideoId"
          @click="emit('open-analysis', hit)"
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
          <span
            v-if="hit.tier === 'COMPETITOR'"
            class="knowledge-card-action knowledge-card-competitor-action"
            @click.stop="emit('open-competitor', hit)"
          >
            对比我的创作
          </span>
        </button>
      </div>
      <div v-if="result.cards.length" class="knowledge-pager">
        <button
          type="button"
          class="creator-secondary-action"
          :disabled="searching || !result.hasMore"
          @click="refreshSearchCards"
        >
          {{ result.hasMore ? '换一批' : '没有更多' }}
        </button>
        <span>最多展示 4 批，共覆盖 top20 候选</span>
      </div>
    </template>
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

.knowledge-search-toolbar {
  max-width: none;
}

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

.knowledge-card > strong {
  color: var(--ink);
  font-size: 15px;
  line-height: 1.45;
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

.knowledge-card-competitor-action {
  color: var(--success, #1e8e3e);
  background: var(--success-tint, #e6f4ea);
  border-color: var(--success-ring, #ceead6);
  cursor: pointer;
}

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

@media (max-width: 820px) {
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
}
</style>
