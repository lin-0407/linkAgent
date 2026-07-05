<script setup lang="ts">
/**
 * 创作者画像弹出面板（P1-3）。
 * <p>
 * 在顶部全局导航栏显示头像按钮，点击弹出画像面板，
 * 展示 AI 根据用户创作行为自动提取的风格标签、语气偏好和受众认知。
 * <p>
 * 为什么用点击弹出而非 hover 浮层？
 * 画像内容较多（标签 + 多段文字 + 刷新按钮），hover 浮层容易误触消失；
 * 点击弹出给用户明确的打开/关闭控制，体验更稳定。
 * <p>
 * 为什么所有路由下都渲染？
 * 画像属于用户级别的全局信息，与当前在哪个页面无关，
 * 用户在任何页面都可能想查看或刷新自己的创作画像。
 */
import { onMounted, ref } from 'vue'
import { useCreatorProfile } from '@/composables/creator/useCreatorProfile'
import { formatDate } from '@/composables/creator/creatorWorkspaceUtils'

const {
  isLoading,
  loadError,
  styleTagList,
  toneGuide,
  audienceView,
  hasProfile,
  lastUpdateTime,
  loadProfile,
  refreshProfile,
} = useCreatorProfile()

// ── 面板开关 ──

const panelOpen = ref(false)

function togglePanel() {
  panelOpen.value = !panelOpen.value
  // 每次打开面板时重新加载画像，确保数据是最新的
  if (panelOpen.value) {
    loadProfile()
  }
}

function closePanel() {
  panelOpen.value = false
}

// ── 生命周期 ──

onMounted(() => {
  // 组件挂载时预加载画像，这样用户点击时数据大概率已就绪
  loadProfile()
})
</script>

<template>
  <div class="profile-popover-root">
    <!-- 头像按钮 -->
    <button
      type="button"
      class="profile-avatar-btn"
      :class="{ active: panelOpen }"
      aria-label="创作者画像"
      :title="hasProfile ? '查看创作者画像' : '暂无画像数据'"
      @click="togglePanel"
    >
      <span class="profile-avatar-icon" aria-hidden="true">👤</span>
      <!-- 有画像时显示小绿点，暗示"已有数据" -->
      <span v-if="hasProfile" class="profile-avatar-dot" aria-hidden="true"></span>
    </button>

    <!-- 弹出面板 -->
    <Teleport to="body">
      <Transition name="profile-panel">
        <div
          v-if="panelOpen"
          class="profile-panel-backdrop"
          role="presentation"
          @click.self="closePanel"
        >
          <section
            class="profile-panel"
            role="dialog"
            aria-modal="true"
            aria-label="创作者画像"
          >
            <header class="profile-panel-head">
              <div>
                <span class="profile-panel-icon" aria-hidden="true">👤</span>
                <h3>创作者画像</h3>
              </div>
              <button
                type="button"
                class="creator-ghost-button"
                @click="closePanel"
              >
                关闭
              </button>
            </header>

            <p class="profile-panel-desc">
              AI 根据您的创作行为学习到的风格特征
            </p>

            <!-- 加载中 -->
            <div v-if="isLoading" class="profile-panel-status">
              <p class="creator-muted">加载中…</p>
            </div>

            <!-- 加载失败 -->
            <div v-else-if="loadError" class="profile-panel-status">
              <p class="creator-muted error-text">加载失败：{{ loadError }}</p>
              <button
                type="button"
                class="creator-ghost-button"
                @click="loadProfile()"
              >
                重试
              </button>
            </div>

            <!-- 空状态（新用户） -->
            <div v-else-if="!hasProfile" class="profile-panel-empty">
              <p>
                尚未积累足够数据。完成几次创作后，画像将根据您的风格偏好自动生成。
              </p>
            </div>

            <!-- 画像内容 -->
            <template v-else>
              <!-- 风格标签 -->
              <div class="profile-section">
                <h4>风格标签</h4>
                <div class="profile-tag-list">
                  <span
                    v-for="tag in styleTagList"
                    :key="tag"
                    class="profile-tag"
                  >{{ tag }}</span>
                </div>
              </div>

              <!-- 语气偏好 -->
              <div v-if="toneGuide" class="profile-section">
                <h4>语气偏好</h4>
                <p>{{ toneGuide }}</p>
              </div>

              <!-- 受众认知 -->
              <div v-if="audienceView" class="profile-section">
                <h4>受众认知</h4>
                <p>{{ audienceView }}</p>
              </div>

              <!-- 底部信息 + 刷新 -->
              <footer class="profile-panel-foot">
                <span v-if="lastUpdateTime" class="profile-update-time">
                  画像更新于 {{ formatDate(lastUpdateTime) }}
                </span>
                <button
                  type="button"
                  class="creator-secondary-action profile-refresh-btn"
                  :disabled="isLoading"
                  @click="refreshProfile()"
                >
                  {{ isLoading ? '刷新中…' : '刷新画像' }}
                </button>
              </footer>
            </template>
          </section>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
/**
 * 画像弹出面板样式。
 * 按钮融入 topbar 的风格，面板采用固定定位 + 右对齐，
 * 宽度控制在 320px，内容超出时内部滚动。
 */

/* ── 根容器（按钮 + 面板的定位上下文） ── */

.profile-popover-root {
  position: relative;
  display: flex;
  align-items: center;
}

/* ── 头像按钮 ── */

.profile-avatar-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  width: 36px;
  height: 36px;
  padding: 0;
  background: transparent;
  border: 1px solid var(--border);
  border-radius: 50%;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.profile-avatar-btn:hover,
.profile-avatar-btn.active {
  border-color: var(--accent, #1a73e8);
  background: var(--surface-dim, #f0f4ff);
}

.profile-avatar-icon {
  font-size: 18px;
  line-height: 1;
}

/* 已存在画像数据时的小绿点指示 */
.profile-avatar-dot {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 8px;
  height: 8px;
  background: var(--success, #1e8e3e);
  border-radius: 50%;
  border: 1px solid var(--surface, #fff);
}

/* ── 背景遮罩 ── */

.profile-panel-backdrop {
  position: fixed;
  inset: 0;
  z-index: 9000;
  /* 不设背景色：点击空白区域关闭，但不遮挡页面内容 */
}

/* ── 面板本体 ── */

.profile-panel {
  position: fixed;
  top: 56px;
  right: 16px;
  width: 320px;
  max-height: calc(100vh - 80px);
  overflow-y: auto;
  padding: var(--s4);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.12);
}

.profile-panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--s2);
}

.profile-panel-head > div {
  display: flex;
  align-items: center;
  gap: var(--s2);
}

.profile-panel-icon {
  font-size: 20px;
}

.profile-panel-head h3 {
  margin: 0;
  font-size: 16px;
  font-weight: var(--fw-semibold);
}

.profile-panel-desc {
  margin: 0 0 var(--s4);
  font-size: 13px;
  color: var(--text-secondary, #666);
}

/* ── 状态占位 ── */

.profile-panel-status {
  text-align: center;
  padding: var(--s4) 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--s2);
}

/* ── 空状态 ── */

.profile-panel-empty {
  text-align: center;
  padding: var(--s4) var(--s2);
}

.profile-panel-empty p {
  margin: 0;
  font-size: 14px;
  color: var(--text-secondary, #666);
  line-height: 1.6;
}

/* ── 画像内容区块 ── */

.profile-section {
  margin-bottom: var(--s3);
}

.profile-section h4 {
  margin: 0 0 var(--s2);
  font-size: 13px;
  font-weight: var(--fw-semibold);
  color: var(--text-secondary, #666);
  text-transform: none;
}

.profile-section p {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text);
}

/* 风格标签列表 */
.profile-tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s1);
}

.profile-tag {
  display: inline-block;
  padding: 2px var(--s2);
  background: var(--surface-dim, #f0f4ff);
  color: var(--accent, #1a73e8);
  border-radius: var(--r-sm);
  font-size: 12px;
  font-weight: var(--fw-semibold);
}

/* ── 底部信息 ── */

.profile-panel-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: var(--s3);
  border-top: 1px solid var(--border);
  margin-top: var(--s2);
}

.profile-update-time {
  font-size: 12px;
  color: var(--text-secondary, #999);
}

.profile-refresh-btn {
  font-size: 12px;
  padding: 4px var(--s3);
}

/* ── 错误文本 ── */

.error-text {
  color: var(--danger, #d93025);
}

/* ── 过渡动画 ── */

.profile-panel-enter-active,
.profile-panel-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}

.profile-panel-enter-from,
.profile-panel-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
