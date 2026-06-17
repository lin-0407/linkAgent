<script setup lang="ts">
import { ref } from 'vue'
import AgentFloatingWindow from '@/components/AgentFloatingWindow.vue'
import CreatorWorkspace from '@/components/CreatorWorkspace.vue'
import KnowledgeWorkspace from '@/components/KnowledgeWorkspace.vue'
import SettingsDrawer from '@/components/SettingsDrawer.vue'

type Surface = 'creator' | 'knowledge'

const activeSurface = ref<Surface>('creator')
const settingsOpen = ref(false)
</script>

<template>
  <div class="surface-root">
    <button
      type="button"
      class="global-settings-button"
      aria-label="打开设置面板"
      @click="settingsOpen = true"
    >
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path
          d="M12 8.2a3.8 3.8 0 1 0 0 7.6 3.8 3.8 0 0 0 0-7.6Zm8.2 3.8c0 .5-.04.98-.13 1.45l2.02 1.56-1.92 3.32-2.38-.96c-.72.6-1.54 1.08-2.44 1.4L15 21.32H9l-.35-2.55a7.8 7.8 0 0 1-2.44-1.4l-2.38.96-1.92-3.32 2.02-1.56A8.37 8.37 0 0 1 3.8 12c0-.5.04-.98.13-1.45L1.91 8.99l1.92-3.32 2.38.96c.72-.6 1.54-1.08 2.44-1.4L9 2.68h6l.35 2.55c.9.32 1.72.8 2.44 1.4l2.38-.96 1.92 3.32-2.02 1.56c.09.47.13.95.13 1.45Z"
        />
      </svg>
    </button>

    <nav class="surface-switch" aria-label="工作台切换">
      <button
        type="button"
        :class="{ active: activeSurface === 'creator' }"
        @click="activeSurface = 'creator'"
      >
        创作工作台
      </button>
      <button
        type="button"
        :class="{ active: activeSurface === 'knowledge' }"
        @click="activeSurface = 'knowledge'"
      >
        案例库
      </button>
    </nav>

    <CreatorWorkspace v-if="activeSurface === 'creator'" />
    <KnowledgeWorkspace v-else />
    <AgentFloatingWindow v-if="activeSurface === 'creator'" />
    <SettingsDrawer v-model:open="settingsOpen" />
  </div>
</template>
