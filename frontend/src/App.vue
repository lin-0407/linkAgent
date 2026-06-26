<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import AgentFloatingWindow from '@/components/AgentFloatingWindow.vue'
import SettingsDrawer from '@/components/SettingsDrawer.vue'
import ParticleBackground from '@/components/ParticleBackground.vue'
import SystemStatusBar from '@/components/SystemStatusBar.vue'
import { useAppStore } from '@/stores/appStore'

const appStore = useAppStore()
const { settingsOpen, developerMode } = storeToRefs(appStore)

// 粒子背景：首页完整展示，其他页面降低密度
const route = useRoute()
const particleEnabled = computed(() => true) // 全局启用
const particleDensity = computed(() => route.path === '/' ? 12000 : 20000) // 非首页更稀疏
</script>

<template>
  <!-- 粒子背景层（fixed，不影响文档流） -->
  <ParticleBackground
    :enabled="particleEnabled"
    :density="particleDensity"
  />

  <div class="surface-root">
    <nav class="surface-switch" aria-label="导航">
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
      <button
        type="button"
        :class="{ active: settingsOpen }"
        @click="settingsOpen = true"
      >
        设置
      </button>
    </nav>

    <RouterView v-slot="{ Component }">
      <component :is="Component" :developer-mode="developerMode" />
    </RouterView>
    <AgentFloatingWindow :developer-mode="developerMode" />
    <!-- 底部运行时状态栏：全局可见，展示 LLM/向量库/SSE 健康状态 -->
    <SystemStatusBar />
    <SettingsDrawer v-model:open="settingsOpen" v-model:developer-mode="developerMode" />
  </div>
</template>
