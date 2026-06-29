<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import AgentFloatingWindow from '@/components/AgentFloatingWindow.vue'
import SettingsDrawer from '@/components/SettingsDrawer.vue'
import ParticleBackground from '@/components/ParticleBackground.vue'
import SystemStatusBar from '@/components/SystemStatusBar.vue'
import { useAppStore } from '@/stores/appStore'

const appStore = useAppStore()
const { settingsOpen, developerMode } = storeToRefs(appStore)

// 粒子背景全局启用；创作台降低密度，既保留动态氛围，又不干扰表单阅读。
const route = useRoute()
const particleEnabled = computed(() => true)
const particleDensity = computed(() => route.path === '/' ? 12000 : 22000)
const isCreatorRoute = computed(() => route.path === '/creator')
const guideOpen = ref(false)
</script>

<template>
  <!-- 粒子背景层（fixed，不影响文档流） -->
  <ParticleBackground
    :key="route.path"
    :enabled="particleEnabled"
    :density="particleDensity"
  />

  <div class="surface-root">
    <header class="surface-topbar">
      <div class="surface-topbar-left">
        <RouterLink to="/" class="surface-brand" aria-label="返回首页">
          <span class="surface-brand-mark" aria-hidden="true"></span>
          <strong>视频发布助手</strong>
          <b>AI</b>
        </RouterLink>
        <nav v-if="isCreatorRoute" class="surface-breadcrumb" aria-label="当前位置">
          <RouterLink to="/creator">创作台</RouterLink>
          <span>/</span>
          <strong>视频发布与复盘助手</strong>
        </nav>
      </div>

      <nav v-if="!isCreatorRoute" class="surface-switch" aria-label="导航">
        <RouterLink to="/" custom v-slot="{ navigate, isExactActive }">
          <button type="button" :class="{ active: isExactActive }" @click="navigate">
            首页
          </button>
        </RouterLink>
        <RouterLink to="/creator" custom v-slot="{ navigate, isActive }">
          <button type="button" :class="{ active: isActive }" @click="navigate">
            创作台
          </button>
        </RouterLink>
        <RouterLink to="/knowledge" custom v-slot="{ navigate, isActive }">
          <button type="button" :class="{ active: isActive }" @click="navigate">
            参考案例
          </button>
        </RouterLink>
        <RouterLink to="/projects" custom v-slot="{ navigate, isActive }">
          <button type="button" :class="{ active: isActive }" @click="navigate">
            项目列表
          </button>
        </RouterLink>
        <!-- P0-3: 视频分析独立页面入口 — 展示已绑定任务的视频并支持复盘追问 -->
        <RouterLink to="/video-analysis" custom v-slot="{ navigate, isActive }">
          <button type="button" :class="{ active: isActive }" @click="navigate">
            视频分析
          </button>
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
        <span v-if="isCreatorRoute" class="surface-notice-dot" aria-hidden="true"></span>
        <button
          type="button"
          class="surface-settings-button"
          :class="{ active: settingsOpen }"
          aria-label="打开设置"
          @click="settingsOpen = true"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M4 7h16M4 12h16M4 17h16" />
            <circle cx="9" cy="7" r="2" />
            <circle cx="15" cy="12" r="2" />
            <circle cx="11" cy="17" r="2" />
          </svg>
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
          <section class="surface-guide-panel" role="dialog" aria-modal="true" aria-labelledby="surface-guide-title">
            <header class="surface-guide-head">
              <div>
                <span>使用指南</span>
                <h2 id="surface-guide-title">视频发布与复盘助手</h2>
              </div>
              <button type="button" class="surface-guide-close" aria-label="关闭使用指南" @click="guideOpen = false">
                ×
              </button>
            </header>

            <ol class="surface-guide-steps">
              <li>
                <b>1</b>
                <div>
                  <strong>填写视频资料</strong>
                  <p>先录入视频主题、标题草稿、简介、文稿和字幕。资料越完整，后续建议越稳定。</p>
                </div>
              </li>
              <li>
                <b>2</b>
                <div>
                  <strong>生成发布方案</strong>
                  <p>让系统检查标题、简介、标签和潜在风险，挑选适合当前视频的发布建议。</p>
                </div>
              </li>
              <li>
                <b>3</b>
                <div>
                  <strong>导入观众反馈</strong>
                  <p>发布后粘贴评论、弹幕或整理后的反馈文本，用来识别争议点、关注点和误解。</p>
                </div>
              </li>
              <li>
                <b>4</b>
                <div>
                  <strong>生成复盘报告</strong>
                  <p>根据发布方案和观众反馈沉淀结论，得到下一期选题、标题和内容调整方向。</p>
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
    <AgentFloatingWindow :developer-mode="developerMode" />
    <!-- 底部运行时状态栏：全局可见，展示 LLM/向量库/SSE 健康状态 -->
    <SystemStatusBar />
    <SettingsDrawer v-model:open="settingsOpen" v-model:developer-mode="developerMode" />
  </div>
</template>
