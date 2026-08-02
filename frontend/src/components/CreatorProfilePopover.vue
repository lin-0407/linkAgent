<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  ChevronDown,
  MessageSquareText,
  RefreshCw,
  UserRound,
  UsersRound,
  X,
} from '@lucide/vue'
import { useCreatorProfile } from '@/composables/creator/useCreatorProfile'
import { formatDate } from '@/composables/creator/creatorWorkspaceUtils'

const {
  bilibiliAccount,
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

const panelOpen = ref(false)
const detailsExpanded = ref(false)
const failedAvatarUrl = ref('')
const toneTextRef = ref<HTMLElement | null>(null)
const audienceTextRef = ref<HTMLElement | null>(null)
const hasClippedDetails = ref(false)
let detailsResizeObserver: ResizeObserver | null = null

const avatarUrl = computed(() => bilibiliAccount.value?.avatarUrl?.trim() ?? '')
const shouldShowAvatar = computed(
  () => !!avatarUrl.value && failedAvatarUrl.value !== avatarUrl.value,
)
const creatorName = computed(
  () => bilibiliAccount.value?.nickname?.trim() || '我的创作画像',
)
const profileButtonTitle = computed(() => {
  if (bilibiliAccount.value) return `查看 ${creatorName.value} 的创作者画像`
  return hasProfile.value ? '查看创作者画像' : '暂无画像数据'
})

function handleAvatarError() {
  // 与案例封面一致，图片失败后立即隐藏，稳定回退为默认图标而不是保留浏览器裂图。
  failedAvatarUrl.value = avatarUrl.value
}

function togglePanel() {
  panelOpen.value = !panelOpen.value
  if (panelOpen.value) {
    detailsExpanded.value = false
    loadProfile()
  }
}

function closePanel() {
  panelOpen.value = false
}

function updateClippedDetails() {
  if (detailsExpanded.value) return
  hasClippedDetails.value = [toneTextRef.value, audienceTextRef.value]
    .filter((element): element is HTMLElement => !!element)
    .some((element) => element.scrollHeight > element.clientHeight + 1)
}

async function observeDetailText() {
  await nextTick()
  detailsResizeObserver?.disconnect()
  const detailElements = [toneTextRef.value, audienceTextRef.value]
  detailElements.forEach((element) => {
    if (element) detailsResizeObserver?.observe(element)
  })
  updateClippedDetails()
}

watch([toneGuide, audienceView, panelOpen], () => {
  void observeDetailText()
})

watch(detailsExpanded, (expanded) => {
  if (!expanded) void observeDetailText()
})

onMounted(() => {
  detailsResizeObserver = new ResizeObserver(updateClippedDetails)
  // 提前读取画像和绑定账号，让用户打开面板时能直接看到最新身份与摘要。
  loadProfile()
})

onBeforeUnmount(() => {
  detailsResizeObserver?.disconnect()
  detailsResizeObserver = null
})
</script>

<template>
  <div class="profile-popover-root">
    <button
      type="button"
      class="profile-avatar-btn"
      :class="{ active: panelOpen }"
      aria-label="创作者画像"
      :title="profileButtonTitle"
      @click="togglePanel"
    >
      <img
        v-if="shouldShowAvatar"
        :src="avatarUrl"
        :alt="`${creatorName}的B站头像`"
        class="profile-avatar-image"
        decoding="async"
        referrerpolicy="no-referrer"
        draggable="false"
        @error="handleAvatarError"
      />
      <UserRound
        v-else
        class="profile-avatar-icon"
        :size="18"
        :stroke-width="1.8"
        aria-hidden="true"
      />
      <span v-if="hasProfile" class="profile-avatar-dot" aria-hidden="true"></span>
    </button>

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
            aria-labelledby="creator-profile-title"
          >
            <header class="profile-panel-head">
              <div class="profile-identity">
                <span class="profile-identity-avatar" aria-hidden="true">
                  <img
                    v-if="shouldShowAvatar"
                    :src="avatarUrl"
                    alt=""
                    decoding="async"
                    referrerpolicy="no-referrer"
                    draggable="false"
                    @error="handleAvatarError"
                  />
                  <UserRound v-else :size="22" :stroke-width="1.7" />
                </span>
                <div class="profile-identity-copy">
                  <span>创作者画像</span>
                  <h3 id="creator-profile-title">{{ creatorName }}</h3>
                  <p v-if="bilibiliAccount">B站 UID {{ bilibiliAccount.bilibiliUid }}</p>
                  <p v-else>个人创作偏好</p>
                </div>
              </div>
              <button
                type="button"
                class="profile-icon-button"
                aria-label="关闭创作者画像"
                title="关闭"
                @click="closePanel"
              >
                <X :size="18" :stroke-width="1.8" aria-hidden="true" />
              </button>
            </header>

            <div v-if="isLoading && !hasProfile" class="profile-panel-status" aria-live="polite">
              <span class="profile-loading-mark" aria-hidden="true"></span>
              <p>正在读取画像</p>
            </div>

            <div v-else-if="loadError && !hasProfile" class="profile-panel-status">
              <p class="profile-error-text">画像加载失败</p>
              <button type="button" class="profile-text-button" @click="loadProfile()">
                重试
              </button>
            </div>

            <div v-else-if="!hasProfile" class="profile-panel-empty">
              <strong>画像尚未形成</strong>
              <p>完成几次创作后，这里会沉淀稳定的风格与受众特征。</p>
            </div>

            <template v-else>
              <section
                v-if="styleTagList.length"
                class="profile-primary-section"
                aria-labelledby="profile-style-title"
              >
                <h4 id="profile-style-title">核心风格</h4>
                <div class="profile-tag-list">
                  <span v-for="tag in styleTagList" :key="tag" class="profile-tag">
                    {{ tag }}
                  </span>
                </div>
              </section>

              <div class="profile-insight-list">
                <section v-if="toneGuide" class="profile-insight">
                  <span class="profile-insight-icon" aria-hidden="true">
                    <MessageSquareText :size="17" :stroke-width="1.8" />
                  </span>
                  <div>
                    <h4>表达基调</h4>
                    <p
                      ref="toneTextRef"
                      :class="{ 'is-collapsed': !detailsExpanded }"
                    >{{ toneGuide }}</p>
                  </div>
                </section>

                <section v-if="audienceView" class="profile-insight">
                  <span class="profile-insight-icon" aria-hidden="true">
                    <UsersRound :size="17" :stroke-width="1.8" />
                  </span>
                  <div>
                    <h4>核心受众</h4>
                    <p
                      ref="audienceTextRef"
                      :class="{ 'is-collapsed': !detailsExpanded }"
                    >{{ audienceView }}</p>
                  </div>
                </section>
              </div>

              <button
                v-if="hasClippedDetails"
                type="button"
                class="profile-expand-button"
                :aria-expanded="detailsExpanded"
                @click="detailsExpanded = !detailsExpanded"
              >
                <span>{{ detailsExpanded ? '收起完整画像' : '查看完整画像' }}</span>
                <ChevronDown
                  :size="16"
                  :stroke-width="1.8"
                  :class="{ 'is-expanded': detailsExpanded }"
                  aria-hidden="true"
                />
              </button>

              <footer class="profile-panel-foot">
                <span v-if="lastUpdateTime">更新于 {{ formatDate(lastUpdateTime) }}</span>
                <span v-else></span>
                <button
                  type="button"
                  class="profile-refresh-button"
                  :disabled="isLoading"
                  @click="refreshProfile()"
                >
                  <RefreshCw
                    :size="15"
                    :stroke-width="1.8"
                    :class="{ 'is-spinning': isLoading }"
                    aria-hidden="true"
                  />
                  <span>{{ isLoading ? '更新中' : '刷新画像' }}</span>
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
.profile-popover-root {
  position: relative;
  display: flex;
  align-items: center;
}

.profile-avatar-btn {
  position: relative;
  display: grid;
  width: 36px;
  height: 36px;
  padding: 0;
  overflow: visible;
  color: var(--muted);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 50%;
  place-items: center;
  cursor: pointer;
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.profile-avatar-btn:hover,
.profile-avatar-btn.active {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-ring);
}

.profile-avatar-btn:focus-visible,
.profile-icon-button:focus-visible,
.profile-expand-button:focus-visible,
.profile-refresh-button:focus-visible,
.profile-text-button:focus-visible {
  outline: 3px solid var(--accent-ring);
  outline-offset: 2px;
}

.profile-avatar-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: inherit;
}

.profile-avatar-dot {
  position: absolute;
  top: -1px;
  right: -1px;
  width: 9px;
  height: 9px;
  background: var(--ok);
  border: 2px solid var(--surface);
  border-radius: 50%;
}

.profile-panel-backdrop {
  position: fixed;
  inset: 0;
  z-index: 9000;
}

.profile-panel {
  position: fixed;
  top: calc(var(--surface-topbar-height, 60px) + 8px);
  right: 16px;
  width: min(380px, calc(100vw - 24px));
  max-height: calc(100dvh - var(--surface-topbar-height, 60px) - 24px);
  padding: 18px;
  overflow-y: auto;
  color: var(--text);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  box-shadow: var(--sh-lg);
}

.profile-panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s3);
  padding-bottom: var(--s4);
  border-bottom: 1px solid var(--border);
}

.profile-identity {
  display: grid;
  grid-template-columns: 46px minmax(0, 1fr);
  align-items: center;
  min-width: 0;
  gap: var(--s3);
}

.profile-identity-avatar {
  display: grid;
  width: 46px;
  height: 46px;
  overflow: hidden;
  color: var(--accent-strong);
  background: var(--accent-tint);
  border: 1px solid rgba(8, 126, 167, 0.18);
  border-radius: 50%;
  place-items: center;
}

.profile-identity-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.profile-identity-copy {
  min-width: 0;
}

.profile-identity-copy > span {
  display: block;
  margin-bottom: 2px;
  color: var(--accent-strong);
  font-size: 11px;
  font-weight: var(--fw-semibold);
}

.profile-identity-copy h3 {
  margin: 0;
  overflow: hidden;
  color: var(--ink);
  font-size: 16px;
  font-weight: var(--fw-semibold);
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile-identity-copy p {
  margin: 2px 0 0;
  overflow: hidden;
  color: var(--muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile-icon-button {
  display: grid;
  flex: 0 0 auto;
  width: 32px;
  height: 32px;
  padding: 0;
  color: var(--muted);
  background: transparent;
  border: 0;
  border-radius: var(--r-sm);
  place-items: center;
  cursor: pointer;
}

.profile-icon-button:hover {
  color: var(--ink);
  background: var(--surface-sub);
}

.profile-panel-status,
.profile-panel-empty {
  display: grid;
  min-height: 150px;
  padding: var(--s6) var(--s3);
  text-align: center;
  place-content: center;
  justify-items: center;
  gap: var(--s2);
}

.profile-panel-status p,
.profile-panel-empty p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.65;
}

.profile-panel-empty strong {
  color: var(--ink);
  font-size: 14px;
}

.profile-loading-mark {
  width: 22px;
  height: 22px;
  border: 2px solid var(--border);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: profile-spin 700ms linear infinite;
}

.profile-error-text {
  color: var(--danger) !important;
}

.profile-text-button {
  padding: 4px 8px;
  color: var(--accent-strong);
  background: transparent;
  border: 0;
  border-radius: var(--r-sm);
  cursor: pointer;
}

.profile-primary-section {
  padding: var(--s4) 0;
}

.profile-primary-section h4,
.profile-insight h4 {
  margin: 0;
  color: var(--ink);
  font-size: 12px;
  font-weight: var(--fw-semibold);
}

.profile-tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 9px;
}

.profile-tag {
  display: inline-flex;
  min-height: 26px;
  padding: 4px 9px;
  align-items: center;
  color: var(--accent-strong);
  background: var(--accent-tint);
  border: 1px solid rgba(8, 126, 167, 0.14);
  border-radius: var(--r-sm);
  font-size: 12px;
  font-weight: var(--fw-medium);
  line-height: 1.3;
}

.profile-insight-list {
  border-top: 1px solid var(--border);
}

.profile-insight {
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr);
  gap: var(--s3);
  padding: 14px 0;
  border-bottom: 1px solid var(--border);
}

.profile-insight-icon {
  display: grid;
  width: 30px;
  height: 30px;
  color: var(--accent-strong);
  background: var(--surface-sub);
  border-radius: var(--r-sm);
  place-items: center;
}

.profile-insight p {
  margin: 5px 0 0;
  overflow-wrap: anywhere;
  color: var(--text);
  font-size: 13px;
  line-height: 1.65;
  white-space: pre-wrap;
}

.profile-insight p.is-collapsed {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.profile-expand-button {
  display: flex;
  width: 100%;
  min-height: 36px;
  padding: 0;
  align-items: center;
  justify-content: space-between;
  color: var(--accent-strong);
  background: transparent;
  border: 0;
  border-bottom: 1px solid var(--border);
  font-size: 12px;
  font-weight: var(--fw-semibold);
  cursor: pointer;
}

.profile-expand-button svg {
  transition: transform 160ms ease;
}

.profile-expand-button svg.is-expanded {
  transform: rotate(180deg);
}

.profile-panel-foot {
  display: flex;
  min-height: 48px;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--s3);
  padding-top: var(--s3);
}

.profile-panel-foot > span {
  color: var(--faint);
  font-size: 11px;
  line-height: 1.4;
}

.profile-refresh-button {
  display: inline-flex;
  min-height: 32px;
  padding: 0 10px;
  align-items: center;
  gap: 6px;
  color: var(--accent-strong);
  background: var(--surface);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-sm);
  font-size: 12px;
  font-weight: var(--fw-semibold);
  cursor: pointer;
}

.profile-refresh-button:hover:not(:disabled) {
  background: var(--accent-tint);
  border-color: rgba(8, 126, 167, 0.32);
}

.profile-refresh-button:disabled {
  cursor: wait;
  opacity: 0.7;
}

.profile-refresh-button .is-spinning {
  animation: profile-spin 700ms linear infinite;
}

.profile-panel-enter-active,
.profile-panel-leave-active {
  transition: opacity 150ms ease, transform 150ms ease;
}

.profile-panel-enter-from,
.profile-panel-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

@keyframes profile-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 520px) {
  .profile-panel {
    right: 8px;
    width: calc(100vw - 16px);
    padding: var(--s4);
  }
}

@media (prefers-reduced-motion: reduce) {
  .profile-panel-enter-active,
  .profile-panel-leave-active,
  .profile-expand-button svg {
    transition: none;
  }
}
</style>
