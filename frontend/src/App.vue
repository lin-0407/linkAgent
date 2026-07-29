<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { SlidersHorizontal, X } from '@lucide/vue'
import { useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import AgentFloatingWindow from '@/components/AgentFloatingWindow.vue'
import CreatorProfilePopover from '@/components/CreatorProfilePopover.vue'
import SettingsDrawer from '@/components/SettingsDrawer.vue'
import SystemStatusBar from '@/components/SystemStatusBar.vue'
import { useAppStore } from '@/stores/appStore'

const appStore = useAppStore()
const { settingsOpen, developerMode } = storeToRefs(appStore)

const route = useRoute()
const isHomeRoute = computed(() => route.path === '/')
const isCreatorRoute = computed(() => route.path === '/creator')
const isUsageLogRoute = computed(() => route.path === '/usage-logs')
const guideOpen = ref(false)
const surfaceSwitchRef = ref<HTMLElement | null>(null)

function revealActiveNavigation() {
  window.requestAnimationFrame(() => {
    const navigation = surfaceSwitchRef.value
    const activeButton = navigation?.querySelector<HTMLElement>('button.active')
    if (!navigation || !activeButton) return
    navigation.scrollLeft = Math.max(
      0,
      activeButton.offsetLeft - (navigation.clientWidth - activeButton.offsetWidth) / 2,
    )
  })
}

onMounted(() => {
  revealActiveNavigation()
  window.setTimeout(revealActiveNavigation, 100)
})

watch(
  () => route.path,
  async () => {
    await nextTick()
    // 移动端一级导航可横向滚动，需要主动露出当前页入口，避免新页面入口藏在屏幕外。
    revealActiveNavigation()
  },
)
</script>

<template>
  <div
    class="surface-root"
    :class="{ 'surface-root-home': isHomeRoute, 'surface-root-creator': isCreatorRoute }"
  >
    <header
      class="surface-topbar"
      :class="{ 'surface-topbar-home': isHomeRoute, 'surface-topbar-creator': isCreatorRoute }"
    >
      <div class="surface-topbar-left">
        <RouterLink to="/" class="surface-brand" aria-label="返回首页">
          <span class="surface-brand-mark" aria-hidden="true"></span>
          <strong>LinkAgent</strong>
          <b>AI</b>
        </RouterLink>
        <nav v-if="isCreatorRoute" class="surface-breadcrumb" aria-label="当前位置">
          <RouterLink to="/creator">创作台</RouterLink>
          <span>/</span>
          <strong>视频发布与复盘助手</strong>
        </nav>
      </div>

      <nav ref="surfaceSwitchRef" class="surface-switch" aria-label="导航">
        <RouterLink to="/" custom v-slot="{ navigate, isExactActive }">
          <button type="button" :class="{ active: isExactActive }" @click="navigate">首页</button>
        </RouterLink>
        <RouterLink to="/creator" custom v-slot="{ navigate, isActive }">
          <button type="button" :class="{ active: isActive }" @click="navigate">创作台</button>
        </RouterLink>
        <RouterLink to="/knowledge" custom v-slot="{ navigate, isActive }">
          <button type="button" :class="{ active: isActive }" @click="navigate">参考案例</button>
        </RouterLink>
        <RouterLink to="/projects" custom v-slot="{ navigate, isActive }">
          <button type="button" :class="{ active: isActive }" @click="navigate">项目列表</button>
        </RouterLink>
        <!-- P1-2: 长期记忆管理页入口 — 查看和管理系统自动提取的长期记忆 -->
        <RouterLink to="/memory" custom v-slot="{ navigate, isActive }">
          <button type="button" :class="{ active: isActive }" @click="navigate">记忆管理</button>
        </RouterLink>
        <!-- P0-3: 视频分析独立页面入口 — 展示已绑定任务的视频并支持复盘追问 -->
        <RouterLink to="/video-analysis" custom v-slot="{ navigate, isActive }">
          <button type="button" :class="{ active: isActive }" @click="navigate">视频分析</button>
        </RouterLink>
        <RouterLink to="/usage-logs" custom v-slot="{ navigate, isActive }">
          <button type="button" :class="{ active: isActive }" @click="navigate">使用日志</button>
        </RouterLink>
      </nav>

      <div class="surface-topbar-actions" aria-label="辅助入口">
        <button
          v-if="isCreatorRoute"
          type="button"
          class="surface-action-pill"
          :class="{ active: guideOpen }"
          @click="guideOpen = true"
        >
          使用指南
        </button>
        <!-- 首页不预取画像数据，避免产品入口在未启动后端时产生无意义请求。 -->
        <CreatorProfilePopover v-if="!isHomeRoute" />
        <button
          type="button"
          class="surface-settings-button"
          :class="{ active: settingsOpen }"
          aria-label="打开设置"
          @click="settingsOpen = true"
        >
          <SlidersHorizontal :size="18" :stroke-width="1.8" aria-hidden="true" />
        </button>
      </div>
    </header>

    <Teleport to="body">
      <Transition name="surface-guide">
        <div
          v-if="guideOpen"
          class="surface-guide-backdrop"
          role="presentation"
          @click.self="guideOpen = false"
        >
          <section
            class="surface-guide-panel"
            role="dialog"
            aria-modal="true"
            aria-labelledby="surface-guide-title"
          >
            <header class="surface-guide-head">
              <div>
                <span>使用指南</span>
                <h2 id="surface-guide-title">从创意到复盘的工作流</h2>
              </div>
              <button
                type="button"
                class="surface-guide-close"
                aria-label="关闭使用指南"
                @click="guideOpen = false"
              >
                <X :size="19" :stroke-width="1.8" aria-hidden="true" />
              </button>
            </header>

            <ol class="surface-guide-steps">
              <li>
                <b>1</b>
                <div>
                  <strong>输入创意或创建任务</strong>
                  <p>
                    从一个选题想法、视频类型或已有任务开始，让 AI
                    先理解目标受众、表达方向和缺失素材。
                  </p>
                </div>
              </li>
              <li>
                <b>2</b>
                <div>
                  <strong>确认创意方向</strong>
                  <p>
                    查看 AI
                    生成的创意卡片，选择最适合的一版；确认后会回写到任务材料，继续进入发布前优化。
                  </p>
                </div>
              </li>
              <li>
                <b>3</b>
                <div>
                  <strong>生成发布前优化</strong>
                  <p>
                    基于材料、历史偏好、视频语境和案例证据生成标题、简介、标签、风险点和发布检查清单。
                  </p>
                </div>
              </li>
              <li>
                <b>4</b>
                <div>
                  <strong>确认方案并进入试映</strong>
                  <p>采用发布建议后，先上传成片做发布前试映，确认问题收敛后再正式发布。</p>
                </div>
              </li>
              <li>
                <b>5</b>
                <div>
                  <strong>导入反馈并追问</strong>
                  <p>发布后在视频分析页绑定公开视频 BV，同步评论弹幕并生成观众反馈报告。</p>
                </div>
              </li>
              <li>
                <b>6</b>
                <div>
                  <strong>复盘并沉淀偏好</strong>
                  <p>
                    综合发布建议、反馈分析和竞品/案例结论生成复盘报告，把有效偏好沉淀到下一次发布前优化中。
                  </p>
                </div>
              </li>
            </ol>
          </section>
        </div>
      </Transition>
    </Teleport>

    <RouterView v-slot="{ Component }">
      <component :is="Component" :developer-mode="developerMode" />
    </RouterView>
    <!-- 首页保持产品入口的视觉聚焦，诊断状态和悬浮 Agent 只在实际功能页出现。 -->
    <AgentFloatingWindow
      v-if="!isCreatorRoute && !isHomeRoute && !isUsageLogRoute"
      :developer-mode="developerMode"
    />
    <SystemStatusBar v-if="!isHomeRoute && !isUsageLogRoute" />
    <SettingsDrawer v-model:open="settingsOpen" v-model:developer-mode="developerMode" />
  </div>
</template>
