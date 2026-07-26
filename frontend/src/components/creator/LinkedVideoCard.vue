<script setup lang="ts">
/**
 * 已绑定视频卡片 — P0-3 组件。
 * 展示一个已和平台任务绑定的 B 站视频，包含封面、标题、指标和分析状态。
 * 参照 B 站个人主页视频列表的信息密度，但更简洁，只保留创作者复盘需要的字段。
 */
import type { BilibiliVideo } from '@/types/creator'

defineProps<{
  video: BilibiliVideo
}>()

/** 任务复用同一 BV 时也要保持标题 ID 唯一，避免无障碍关联指向错误卡片。 */
function titleId(video: BilibiliVideo): string {
  return `video-title-${video.taskId || 'unbound'}-${video.bvid}`
}

const emit = defineEmits<{
  select: [video: BilibiliVideo]
}>()

/** 格式化大数字为可读形式（如 1.2万） */
function formatCount(n: number | null): string {
  if (n == null) return '--'
  if (n >= 10000) return (n / 10000).toFixed(1) + '万'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k'
  return String(n)
}

</script>

<template>
  <article
    class="linked-video-card"
    role="button"
    tabindex="0"
    :aria-labelledby="titleId(video)"
    @click="emit('select', video)"
    @keydown.enter="emit('select', video)"
  >
    <!-- 封面区 -->
    <div class="card-cover">
      <img
        v-if="video.coverUrl"
        :src="video.coverUrl"
        :alt="video.title || '视频封面'"
        class="card-cover-img"
        loading="lazy"
        width="320"
        height="180"
      />
      <div v-else class="card-cover-placeholder" aria-hidden="true">
        <svg viewBox="0 0 48 48" fill="none">
          <rect width="48" height="48" rx="8" fill="rgba(0,0,0,0.06)" />
          <path d="M18 14l16 10-16 10V14z" fill="rgba(0,0,0,0.2)" />
        </svg>
      </div>
    </div>

    <!-- 信息区 -->
    <div class="card-body">
      <h4 :id="titleId(video)" class="card-title">{{ video.title || '未获取到标题' }}</h4>

      <div class="card-meta">
        <code class="card-bv">{{ video.bvid }}</code>
        <span v-if="video.publishTime" class="card-time">{{ video.publishTime }}</span>
      </div>

      <!-- 指标行：只展示播放量和点赞量 — 这两个是创作者最关心的基本指标 -->
      <div class="card-stats">
        <span class="card-stat">
          <span class="card-stat-icon" aria-hidden="true">▶</span>
          {{ formatCount(video.viewCount) }}
        </span>
        <span class="card-stat">
          <span class="card-stat-icon" aria-hidden="true">👍</span>
          {{ formatCount(video.likeCount) }}
        </span>
      </div>

      <!-- 任务标签 + 分析状态 -->
      <div class="card-footer">
        <span v-if="video.taskName" class="card-task-tag" :title="video.taskName">
          {{ video.taskName }}
        </span>
        <!-- 当前接口没有分析状态字段，固定显示待分析，避免用预留分支伪造不存在的完成态。 -->
        <span class="card-analysis-tag tag-pending">待分析</span>
      </div>
    </div>
  </article>
</template>

<style scoped>
/* 已绑定视频卡片 — 简洁信息密度，hover 上浮效果增强交互感知 */
.linked-video-card {
  background: var(--creator-panel);
  border: 1px solid var(--creator-line);
  border-radius: 12px;
  overflow: hidden;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s;
  display: flex;
  flex-direction: column;
}

.linked-video-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
}

.linked-video-card:focus-visible {
  outline: 2px solid var(--creator-accent, #0071e3);
  outline-offset: 2px;
}

/* 封面 16:9 比例 */
.card-cover {
  aspect-ratio: 16 / 9;
  background: var(--creator-surface-sub, #f5f5f7);
  overflow: hidden;
}

.card-cover-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.card-cover-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 信息区 */
.card-body {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
}

.card-title {
  margin: 0;
  font-size: 14px;
  font-weight: 500;
  line-height: 1.4;
  color: var(--creator-text, #1d1d1f);
  /* 最多两行，超出省略 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.card-bv {
  font-family: 'SF Mono', 'Cascadia Code', monospace;
  font-size: 11px;
  color: var(--creator-accent, #0071e3);
  background: var(--creator-surface-sub, #f5f5f7);
  padding: 1px 6px;
  border-radius: 3px;
}

.card-time {
  font-size: 12px;
  color: var(--creator-muted-ink, #86868b);
}

/* 指标 */
.card-stats {
  display: flex;
  gap: 16px;
}

.card-stat {
  display: flex;
  align-items: center;
  gap: 2px;
  font-size: 12px;
  color: var(--creator-muted-ink, #86868b);
}

.card-stat-icon {
  font-size: 10px;
}

/* 底部标签 */
.card-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: auto;
  padding-top: 4px;
}

.card-task-tag {
  font-size: 11px;
  color: var(--creator-accent, #0071e3);
  background: rgba(0, 113, 227, 0.08);
  padding: 2px 8px;
  border-radius: 100px;
  max-width: 140px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.card-analysis-tag {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 100px;
  background: rgba(0, 0, 0, 0.05);
  color: var(--creator-muted-ink, #86868b);
}

</style>
