<script setup lang="ts">
import { CircleCheck, CircleHelp, LoaderCircle, TriangleAlert } from '@lucide/vue'

withDefaults(
  defineProps<{
    title: string
    description: string
    state?: 'waiting' | 'running' | 'ready' | 'error'
  }>(),
  { state: 'waiting' },
)
</script>

<template>
  <div class="creator-stage-state" :data-state="state" role="status">
    <span class="creator-stage-state-icon" aria-hidden="true">
      <LoaderCircle v-if="state === 'running'" :size="20" :stroke-width="1.7" />
      <CircleCheck v-else-if="state === 'ready'" :size="20" :stroke-width="1.7" />
      <TriangleAlert v-else-if="state === 'error'" :size="20" :stroke-width="1.7" />
      <CircleHelp v-else :size="20" :stroke-width="1.7" />
    </span>
    <div class="creator-stage-state-copy">
      <strong>{{ title }}</strong>
      <p>{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="creator-stage-state-actions"><slot name="actions" /></div>
  </div>
</template>
