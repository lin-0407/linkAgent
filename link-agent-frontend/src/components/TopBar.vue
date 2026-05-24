<script setup lang="ts">
defineProps<{
  assistantMessageCount: number
  userMessageCount: number
}>()
</script>

<template>
  <header class="topbar">
    <div>
      <h2>和你的 Agent 一起推理</h2>
      <p>每一次回答都能展开查看思考、工具调用和观察结果。</p>
    </div>
    <div class="topbar-stats" aria-label="Message statistics">
      <span>{{ userMessageCount }} 次提问</span>
      <span>{{ assistantMessageCount }} 次回答</span>
    </div>
  </header>
</template>
