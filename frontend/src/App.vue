<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, ref, watch } from 'vue'
import {
  Activity,
  BrainCircuit,
  ChevronRight,
  Clapperboard,
  FolderOpen,
  HelpCircle,
  Library,
  Menu,
  MessageCircle,
  MonitorPlay,
  SlidersHorizontal,
  X,
} from '@lucide/vue'
import { useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import AgentFloatingWindow from '@/components/AgentFloatingWindow.vue'
import CreatorProfilePopover from '@/components/CreatorProfilePopover.vue'
import SystemStatusBar from '@/components/SystemStatusBar.vue'
import { useModalDialog } from '@/composables/useModalDialog'
import { useAppStore } from '@/stores/appStore'

// 设置抽屉及其 5 个配置分区体积不小，且只有用户主动点开才需要。
// 首次打开挂载后保持常驻，避免每次开关都重新拉一遍设置与前端的折叠状态。
const SettingsDrawer = defineAsyncComponent(() => import('@/components/SettingsDrawer.vue'))

const appStore = useAppStore()
const { settingsOpen, assistantOpen, developerMode } = storeToRefs(appStore)
const route = useRoute()
// 桌面侧栏和手机菜单共用入口，避免两端的功能导航逐渐不一致。
const navigationGroups = [
  {
    label: '创作空间',
    items: [
      { path: '/creator', label: '创作台', icon: Clapperboard },
      { path: '/knowledge', label: '参考案例', icon: Library },
      { path: '/projects', label: '项目列表', icon: FolderOpen },
      { path: '/video-analysis', label: '视频分析', icon: MonitorPlay },
    ],
  },
  {
    label: '工作台管理',
    items: [
      { path: '/memory', label: '记忆管理', icon: BrainCircuit },
      { path: '/usage-logs', label: '使用日志', icon: Activity },
    ],
  },
]
const routeLabel = computed(
  () =>
    navigationGroups.flatMap((group) => group.items).find((item) => item.path === route.path)
      ?.label ?? '页面未找到',
)
const guideOpen = ref(false)
const mobileNavigationOpen = ref(false)
/** 设置抽屉首次打开后常驻：既省首屏体积，又不让每次开关抽屉都重拉配置。 */
const settingsDrawerMounted = ref(false)
watch(settingsOpen, (open) => {
  if (open) settingsDrawerMounted.value = true
})

function closeGuide() {
  guideOpen.value = false
}
function closeMobileNavigation() {
  mobileNavigationOpen.value = false
}
async function openGuideFromNavigation() {
  closeMobileNavigation()
  // 先释放导航弹层的焦点，再打开指南，避免回焦到即将移除的菜单按钮。
  await nextTick()
  guideOpen.value = true
}
const { dialogRef: guideDialogRef, handleDialogKeydown: handleGuideKeydown } = useModalDialog(
  guideOpen,
  closeGuide,
)
const { dialogRef: mobileNavigationDialogRef, handleDialogKeydown: handleMobileNavigationKeydown } =
  useModalDialog(mobileNavigationOpen, closeMobileNavigation)
watch(() => route.path, closeMobileNavigation)
</script>

<template>
  <div
    class="surface-root workbench-app"
    :class="{
      'surface-root-creator': route.path === '/creator',
      'workbench-with-assistant': assistantOpen,
    }"
  >
    <a class="workbench-skip-link" href="#workspace-content">跳到页面内容</a>
    <header class="workbench-topbar">
      <RouterLink to="/creator" class="workbench-brand" aria-label="LinkAgent 创作台">
        <span class="workbench-brand-mark" aria-hidden="true"
          ><svg viewBox="0 0 32 32" fill="none">
            <path d="M7 5h18v22H7z" stroke="currentColor" stroke-width="1.8" />
            <path
              d="M3 10v17a4 4 0 0 0 4 4h13M13 11l8 5-8 5V11Z"
              stroke="currentColor"
              stroke-width="1.8"
              stroke-linejoin="round"
            /></svg
        ></span>
        <span class="workbench-brand-copy"><strong>LinkAgent</strong><small>创作片场</small></span>
      </RouterLink>
      <nav class="workbench-breadcrumb" aria-label="当前位置">
        <span>我的工作空间</span><ChevronRight :size="14" aria-hidden="true" /><strong>{{
          routeLabel
        }}</strong>
      </nav>
      <button
        type="button"
        class="workbench-mobile-toggle"
        aria-label="打开页面导航"
        :aria-expanded="mobileNavigationOpen"
        aria-controls="surface-mobile-navigation-menu"
        @click="mobileNavigationOpen = true"
      >
        <Menu :size="19" :stroke-width="1.8" aria-hidden="true" /><span>{{ routeLabel }}</span>
      </button>
      <div class="workbench-topbar-actions" aria-label="辅助入口">
        <button
          id="workbench-assistant-toggle"
          type="button"
          class="workbench-assistant-button"
          :class="{ active: assistantOpen }"
          :aria-label="assistantOpen ? '收起创作助手' : '打开创作助手'"
          :aria-expanded="assistantOpen"
          aria-controls="workbench-assistant-panel"
          @click="assistantOpen = !assistantOpen"
        >
          <MessageCircle :size="19" :stroke-width="1.8" aria-hidden="true" /><span>创作助手</span>
        </button>
        <button type="button" class="workbench-guide-button" @click="guideOpen = true">
          <HelpCircle :size="17" :stroke-width="1.8" aria-hidden="true" /><span>使用指南</span>
        </button>
        <span class="workbench-topbar-divider" aria-hidden="true"></span>
        <CreatorProfilePopover />
        <button
          type="button"
          class="workbench-settings-button"
          :class="{ active: settingsOpen }"
          aria-label="打开设置"
          @click="settingsOpen = true"
        >
          <SlidersHorizontal :size="19" :stroke-width="1.8" aria-hidden="true" />
        </button>
      </div>
    </header>

    <aside class="workbench-sidebar" aria-label="工作台导航">
      <nav
        v-for="group in navigationGroups"
        :key="group.label"
        class="workbench-nav-group"
        :aria-label="group.label"
      >
        <span class="workbench-nav-caption">{{ group.label }}</span>
        <RouterLink
          v-for="item in group.items"
          :key="item.path"
          :to="item.path"
          :title="item.label"
          :aria-label="item.label"
          class="workbench-nav-link"
        >
          <component :is="item.icon" :size="19" :stroke-width="1.7" aria-hidden="true" /><span>{{
            item.label
          }}</span>
        </RouterLink>
      </nav>
    </aside>

    <div id="workspace-content" class="workbench-content" tabindex="-1">
      <RouterView v-slot="{ Component }"
        ><component :is="Component" :developer-mode="developerMode"
      /></RouterView>
    </div>

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
            class="surface-mobile-navigation-menu workbench-mobile-menu"
            role="dialog"
            aria-modal="true"
            aria-label="页面导航"
            tabindex="-1"
            @keydown="handleMobileNavigationKeydown"
          >
            <header>
              <strong>我的创作工作台</strong>
              <button
                type="button"
                aria-label="关闭页面导航"
                data-dialog-initial-focus
                @click="closeMobileNavigation"
              >
                <X :size="19" :stroke-width="1.8" aria-hidden="true" />
              </button>
            </header>
            <div
              v-for="group in navigationGroups"
              :key="group.label"
              class="workbench-mobile-group"
            >
              <RouterLink
                v-for="item in group.items"
                :key="item.path"
                :to="item.path"
                @click="closeMobileNavigation"
              >
                <component :is="item.icon" :size="18" :stroke-width="1.7" aria-hidden="true" />{{
                  item.label
                }}
              </RouterLink>
            </div>
            <button type="button" class="workbench-mobile-help" @click="openGuideFromNavigation">
              <HelpCircle :size="18" :stroke-width="1.7" aria-hidden="true" />
              使用指南
            </button>
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
                  <strong>说说你的视频想法</strong>
                  <p>从一个选题或已有任务开始，补充文稿与素材，让 AI 先理解你的表达方向。</p>
                </div>
              </li>
              <li>
                <b>2</b>
                <div>
                  <strong>对齐想法，确认发布方案</strong>
                  <p>直接纠正 AI 没理解的地方。方向对齐后，由你主动生成并确认发布方案。</p>
                </div>
              </li>
              <li>
                <b>3</b>
                <div>
                  <strong>完成制作蓝图</strong>
                  <p>把方案拆成内容结构、镜头和制作步骤，再开始制作成片。</p>
                </div>
              </li>
              <li>
                <b>4</b>
                <div>
                  <strong>上传成片并完成试映</strong>
                  <p>
                    上传私有成片，完成媒体处理和发布前试映。已有成片可在创建任务后显式跳过前期策划，直接进入这里。
                  </p>
                </div>
              </li>
              <li>
                <b>5</b>
                <div>
                  <strong>实际发布并绑定 BV</strong>
                  <p>在 B 站实际发布视频后，再到视频分析页绑定 BV。</p>
                </div>
              </li>
              <li>
                <b>6</b>
                <div>
                  <strong>收集反馈，完成复盘</strong>
                  <p>同步评论和弹幕，查看观众反馈并生成总体复盘，把有效偏好留给下一次创作。</p>
                </div>
              </li>
            </ol>
          </section>
        </div>
      </Transition>
    </Teleport>
    <AgentFloatingWindow
      :developer-mode="developerMode"
      :background-inert="settingsOpen || guideOpen || mobileNavigationOpen"
    />
    <SystemStatusBar v-if="route.path !== '/usage-logs'" />
    <SettingsDrawer
      v-if="settingsDrawerMounted"
      v-model:open="settingsOpen"
      v-model:developer-mode="developerMode"
    />
  </div>
</template>
