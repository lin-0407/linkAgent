<script setup lang="ts">
import { ref } from 'vue'
import AgentFloatingWindow from '@/components/AgentFloatingWindow.vue'
import CreatorWorkspace from '@/components/CreatorWorkspace.vue'
import KnowledgeWorkspace from '@/components/KnowledgeWorkspace.vue'

type Surface = 'creator' | 'knowledge'

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
        :class="{ active: activeSurface === 'knowledge' }"
        @click="activeSurface = 'knowledge'"
      >
        <span aria-hidden="true">▣</span>
        案例库
      </button>
    </nav>

    <CreatorWorkspace v-if="activeSurface === 'creator'" />
    <KnowledgeWorkspace v-else />
    <AgentFloatingWindow v-if="activeSurface === 'creator'" />
  </div>
</template>
