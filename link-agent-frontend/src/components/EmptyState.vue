<script setup lang="ts">
defineProps<{
  capabilityTags: string[]
  promptExamples: string[]
}>()

defineEmits<{
  usePromptExample: [example: string]
}>()
</script>

<template>
  <div class="empty-state">
    <div class="empty-hero">
      <span class="empty-orbit"></span>
      <p class="eyebrow">Agent Playground</p>
      <h3>从一个小问题开始</h3>
      <p>你可以先验证记忆，再观察它如何拆解任务、选择工具、形成最终回答。</p>
      <div class="capability-tags">
        <span v-for="tag in capabilityTags" :key="tag">{{ tag }}</span>
      </div>
    </div>
    <div class="prompt-examples">
      <button
        v-for="example in promptExamples"
        :key="example"
        type="button"
        @click="$emit('usePromptExample', example)"
      >
        <span>试试</span>
        {{ example }}
      </button>
    </div>
  </div>
</template>
