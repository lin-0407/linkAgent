<script setup lang="ts">
import { ref } from 'vue'
import AgentConsole from '@/components/AgentConsole.vue'
import CreatorWorkspace from '@/components/CreatorWorkspace.vue'

type Surface = 'creator' | 'agent'

const activeSurface = ref<Surface>('creator')
</script>

<template>
  <div class="surface-root">
    <nav class="surface-switch" aria-label="工作台切换">
      <button
        type="button"
        :class="{ active: activeSurface === 'creator' }"
        @click="activeSurface = 'creator'"
      >
        <span aria-hidden="true">◆</span>
        创作工作台
      </button>
      <button
        type="button"
        :class="{ active: activeSurface === 'agent' }"
        @click="activeSurface = 'agent'"
      >
        <span aria-hidden="true">◇</span>
        Agent 控制台
      </button>
    </nav>

    <CreatorWorkspace v-if="activeSurface === 'creator'" />
    <AgentConsole v-else />
  </div>
</template>
