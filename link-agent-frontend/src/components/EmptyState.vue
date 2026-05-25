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
      <div class="empty-art" aria-hidden="true">
        <span class="empty-art-core"></span>
        <span class="empty-art-grid"></span>
        <span class="empty-art-line line-a"></span>
        <span class="empty-art-line line-b"></span>
      </div>
      <p class="eyebrow">Agent Playground</p>
      <h3>先点燃一个问题</h3>
      <p>用一个问题验证记忆、工具、推理轨迹和最终回答，观察 Agent 怎么一步步把不确定性压缩成答案。</p>
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
