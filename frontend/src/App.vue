<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Menu, SlidersHorizontal, X } from '@lucide/vue'
import { useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import AgentFloatingWindow from '@/components/AgentFloatingWindow.vue'
import CreatorProfilePopover from '@/components/CreatorProfilePopover.vue'
import SettingsDrawer from '@/components/SettingsDrawer.vue'
import SystemStatusBar from '@/components/SystemStatusBar.vue'
import { useModalDialog } from '@/composables/useModalDialog'
import { useAppStore } from '@/stores/appStore'

const appStore = useAppStore()
const { settingsOpen, developerMode } = storeToRefs(appStore)

const route = useRoute()
const isHomeRoute = computed(() => route.path === '/')
const isCreatorRoute = computed(() => route.path === '/creator')
const isUsageLogRoute = computed(() => route.path === '/usage-logs')
const routeDirectory = computed(
  () => {
    const directory = {
        '/': { label: '首页', detail: '创作总览' },
        '/creator': { label: '创作台', detail: '视频发布与复盘助手' },
        '/knowledge': { label: '参考案例', detail: '案例检索' },
        '/projects': { label: '项目列表', detail: '历史视频项目' },
        '/memory': { label: '记忆管理', detail: '长期偏好与关键信息' },
        '/video-analysis': { label: '视频分析', detail: '视频分析与复盘' },
        '/usage-logs': { label: '使用日志', detail: '模型调用记录' },
      } as Record<string, { label: string; detail: string }>
    return directory[route.path]
      ?? (route.name === 'notFound' ? { label: '页面未找到', detail: '导航恢复' } : undefined)
  },
)
const guideOpen = ref(false)
const mobileNavigationOpen = ref(false)

function closeGuide() {
  guideOpen.value = false
}

function closeMobileNavigation() {
  mobileNavigationOpen.value = false
}

const { dialogRef: guideDialogRef, handleDialogKeydown: handleGuideKeydown } = useModalDialog(
  guideOpen,
  closeGuide,
)
const {
  dialogRef: mobileNavigationDialogRef,
  handleDialogKeydown: handleMobileNavigationKeydown,
} = useModalDialog(mobileNavigationOpen, closeMobileNavigation)

watch(
  () => route.path,
  closeMobileNavigation,
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
        <nav v-if="routeDirectory" class="surface-breadcrumb" aria-label="当前位置">
          <RouterLink :to="route.path">{{ routeDirectory.label }}</RouterLink>
          <span>/</span>
          <strong>{{ routeDirectory.detail }}</strong>
        </nav>
      </div>

      <nav class="surface-switch" aria-label="导航">
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

      <div class="surface-mobile-navigation">
        <button
          type="button"
          class="surface-mobile-navigation-toggle"
          :aria-expanded="mobileNavigationOpen"
          aria-controls="surface-mobile-navigation-menu"
          @click="mobileNavigationOpen = !mobileNavigationOpen"
        >
          <Menu :size="18" :stroke-width="1.8" aria-hidden="true" />
          <span>{{ routeDirectory?.label || '页面导航' }}</span>
        </button>
      </div>

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
      <Transition name="surface-mobile-menu">
        <div
          v-if="mobileNavigationOpen"
          class="surface-mobile-navigation-backdrop"
          role="presentation"
          @click.self="closeMobileNavigation"
        >
          <nav
            id="surface-mobile-navigation-menu"
            ref="mobileNavigationDialogRef"
            class="surface-mobile-navigation-menu"
            role="dialog"
            aria-modal="true"
            aria-label="页面导航"
            tabindex="-1"
            @keydown="handleMobileNavigationKeydown"
          >
            <header>
              <strong>页面导航</strong>
              <button
                type="button"
                aria-label="关闭页面导航"
                data-dialog-initial-focus
                @click="closeMobileNavigation"
              >
                <X :size="19" :stroke-width="1.8" aria-hidden="true" />
              </button>
            </header>
            <RouterLink to="/" @click="closeMobileNavigation">首页</RouterLink>
            <RouterLink to="/creator" @click="closeMobileNavigation">创作台</RouterLink>
            <RouterLink to="/knowledge" @click="closeMobileNavigation">参考案例</RouterLink>
            <RouterLink to="/projects" @click="closeMobileNavigation">项目列表</RouterLink>
            <RouterLink to="/memory" @click="closeMobileNavigation">记忆管理</RouterLink>
            <RouterLink to="/video-analysis" @click="closeMobileNavigation">视频分析</RouterLink>
            <RouterLink to="/usage-logs" @click="closeMobileNavigation">使用日志</RouterLink>
          </nav>
        </div>
      </Transition>
    </Teleport>

    <Teleport to="body">
      <Transition name="surface-guide">
        <div
          v-if="guideOpen"
          class="surface-guide-backdrop"
          role="presentation"
          @click.self="closeGuide"
        >
          <section
            ref="guideDialogRef"
            class="surface-guide-panel"
            role="dialog"
            aria-modal="true"
            aria-labelledby="surface-guide-title"
            tabindex="-1"
            @keydown="handleGuideKeydown"
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
                data-dialog-initial-focus
                @click="closeGuide"
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
                  <strong>完成制作蓝图</strong>
                  <p>确认发布方案后，先拆解内容结构、镜头和制作步骤，再开始制作成片。</p>
                </div>
              </li>
              <li>
                <b>5</b>
                <div>
                  <strong>上传成片并完成试映</strong>
                  <p>上传私有成片，完成媒体处理和发布前试映。已有成片也必须从这里进入。</p>
                </div>
              </li>
              <li>
                <b>6</b>
                <div>
                  <strong>实际发布并绑定 BV</strong>
                  <p>在 B 站完成实际发布后，再到视频分析页绑定公开视频 BV。</p>
                </div>
              </li>
              <li>
                <b>7</b>
                <div>
                  <strong>导入反馈并复盘</strong>
                  <p>同步评论弹幕，结合竞品结论生成复盘报告，并把有效偏好沉淀到下一次创作中。</p>
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
