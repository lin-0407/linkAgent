<script setup lang="ts">
/**
 * 统一页面头：kicker + h1 + 说明 + 右侧操作区。
 *
 * 五个一级路由共用同一个头部结构，避免出现「有的页面用 h1、有的用 h2 冒充标题」
 * 以及每页头部边框/字号各不相同的情况。宽度交给父容器，组件本身不设 max-width，
 * 保证头部与下方内容严格对齐。
 */
defineProps<{
  /** 页面标题，渲染为 h1（一级路由每页只能有一个） */
  title: string
  /** 标题上方的分类小字，可省略 */
  kicker?: string
  /** 一句话说明这个页面能做什么 */
  description?: string
}>()
</script>

<template>
  <header class="page-header">
    <div class="page-header-main">
      <p v-if="kicker" class="page-header-kicker">{{ kicker }}</p>
      <h1 class="page-header-title">{{ title }}</h1>
      <p v-if="description" class="page-header-description">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="page-header-actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--s3) var(--s5);
  margin-bottom: var(--s5);
  padding-bottom: var(--s4);
  border-bottom: 1px solid var(--border);
}

.page-header-main {
  min-width: 0;
}

.page-header-kicker {
  margin: 0 0 2px;
  color: var(--muted);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  letter-spacing: 0.02em;
}

.page-header-title {
  margin: 0;
  color: var(--ink);
  font-family: var(--font-display);
  font-size: var(--fs-2xl);
  font-weight: var(--fw-bold);
  line-height: var(--lh-tight);
  letter-spacing: 0;
}

.page-header-description {
  max-width: 68ch;
  margin: var(--s2) 0 0;
  color: var(--muted);
  font-size: var(--fs-base);
  line-height: 1.55;
}

.page-header-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s2);
}

@media (max-width: 720px) {
  .page-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .page-header-actions {
    width: 100%;
  }
}
</style>
