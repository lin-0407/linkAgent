<script setup lang="ts">
/**
 * 已绑定视频网格 — P0-3 组件。
 * 展示某 B 站 UID 下所有已绑定平台任务的视频卡片列表。
 * 在视频分析页使用，只展示和任务关联的视频，不是账号下全部视频。
 */
import { ref, watch, onMounted } from 'vue'
import { listLinkedVideos } from '@/api/creator'
import type { BilibiliVideo } from '@/types/creator'
import LinkedVideoCard from './LinkedVideoCard.vue'

const props = defineProps<{
  bilibiliUid: string
}>()

const emit = defineEmits<{
  selectVideo: [video: BilibiliVideo]
}>()

const loading = ref(false)
const error = ref('')
const videos = ref<BilibiliVideo[]>([])

/** 加载视频列表 */
async function loadVideos() {
  if (!props.bilibiliUid) return
  loading.value = true
  error.value = ''
  try {
    videos.value = await listLinkedVideos(props.bilibiliUid)
  } catch (e: any) {
    error.value = e?.message || '加载视频列表失败'
    videos.value = []
  } finally {
    loading.value = false
  }
}

// UID 变化时自动重新加载
watch(() => props.bilibiliUid, loadVideos)
onMounted(loadVideos)
</script>

<template>
  <div class="linked-video-grid-wrapper">
    <h3 class="grid-title">已绑定视频</h3>

    <!-- 加载中 -->
    <p v-if="loading" class="grid-status">加载视频列表...</p>

    <!-- 错误 + 重试 -->
    <div v-else-if="error" class="grid-error">
      <p>{{ error }}</p>
      <button type="button" class="creator-btn creator-btn-secondary" @click="loadVideos">
        重试
      </button>
    </div>

    <!-- 空状态 -->
    <div v-else-if="videos.length === 0" class="grid-empty">
      <p class="grid-empty-text">
        还没有绑定视频的任务。
      </p>
      <p class="grid-empty-hint">
        发布视频后，在创作任务的发布前优化阶段填回 BV 号，即可在这里看到视频数据。
      </p>
    </div>

    <!-- 视频卡片网格 -->
    <div v-else class="linked-video-grid">
      <LinkedVideoCard
        v-for="video in videos"
        :key="video.bvid"
        :video="video"
        @select="emit('selectVideo', $event)"
      />
    </div>
  </div>
</template>

<style scoped>
.linked-video-grid-wrapper {
  margin-top: 4px;
}

.grid-title {
  margin: 0 0 16px;
  font-size: 18px;
  font-weight: 600;
  color: var(--creator-text, #1d1d1f);
}

.grid-status {
  font-size: 14px;
  color: var(--creator-muted-ink, #86868b);
}

.grid-error {
  background: rgba(255, 59, 48, 0.06);
  border: 1px solid rgba(255, 59, 48, 0.15);
  border-radius: 10px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.grid-error p {
  margin: 0;
  font-size: 14px;
  color: #c93400;
}

/* 空状态：给用户明确指引下一步做什么 */
.grid-empty {
  text-align: center;
  padding: 48px 24px;
}

.grid-empty-text {
  font-size: 16px;
  color: var(--creator-text, #1d1d1f);
  margin: 0 0 8px;
}

.grid-empty-hint {
  font-size: 13px;
  color: var(--creator-muted-ink, #86868b);
  margin: 0;
  max-width: 360px;
  margin-left: auto;
  margin-right: auto;
  line-height: 1.6;
}

/* 响应式网格：移动端单列，中等屏 2 列，宽屏 3 列 */
.linked-video-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 16px;
}

@media (min-width: 640px) {
  .linked-video-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (min-width: 1024px) {
  .linked-video-grid {
    grid-template-columns: repeat(3, 1fr);
  }
}
</style>
