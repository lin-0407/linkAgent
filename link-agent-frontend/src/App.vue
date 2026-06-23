<script setup lang="ts">
import { storeToRefs } from 'pinia'
import AgentFloatingWindow from '@/components/AgentFloatingWindow.vue'
import SettingsDrawer from '@/components/SettingsDrawer.vue'
import { useAppStore } from '@/stores/appStore'

const appStore = useAppStore()
const { settingsOpen, developerMode } = storeToRefs(appStore)
</script>

<template>
  <div class="surface-root">
    <nav class="surface-switch" aria-label="导航">
      <RouterLink to="/" custom v-slot="{ navigate, isExactActive }">
        <button type="button" :class="{ active: isExactActive }" @click="navigate">
          首页
        </button>
      </RouterLink>
      <!-- 使用 RouterLink custom v-slot 渲染原生 button，保持现有 CSS 选择器兼容 -->
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

    <!-- RouterView 替代原来的 v-if 三选一切换，避免组件销毁导致 SSE 断开和状态丢失 -->
    <RouterView v-slot="{ Component }">
      <component :is="Component" :developer-mode="developerMode" />
    </RouterView>
    <AgentFloatingWindow :developer-mode="developerMode" />
    <SettingsDrawer v-model:open="settingsOpen" v-model:developer-mode="developerMode" />
  </div>
</template>
