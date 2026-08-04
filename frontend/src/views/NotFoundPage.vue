<script setup lang="ts">
import { ArrowLeft, Home } from '@lucide/vue'
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <main class="not-found-page">
    <section class="not-found-content" aria-labelledby="not-found-title">
      <p class="not-found-code">404</p>
      <h1 id="not-found-title">页面不存在</h1>
      <p>这个地址可能已失效，或者页面已经移动。</p>
      <div class="not-found-actions">
        <RouterLink to="/" class="not-found-primary-action">
          <Home :size="17" :stroke-width="1.8" aria-hidden="true" />
          返回首页
        </RouterLink>
        <button type="button" class="not-found-secondary-action" @click="router.back()">
          <ArrowLeft :size="17" :stroke-width="1.8" aria-hidden="true" />
          返回上一页
        </button>
      </div>
    </section>
  </main>
</template>

<style scoped>
.not-found-page {
  display: grid;
  min-height: calc(100vh - var(--surface-topbar-height));
  padding: 48px 24px 88px;
  place-items: start center;
}

.not-found-content {
  display: grid;
  width: min(1280px, 100%);
  gap: 10px;
  padding-top: min(14vh, 112px);
  text-align: center;
}

.not-found-code {
  margin: 0;
  color: var(--accent-strong);
  font-size: 14px;
  font-weight: var(--fw-bold);
}

.not-found-content h1 {
  margin: 0;
  color: var(--ink);
  font-size: 28px;
  line-height: 1.3;
}

.not-found-content > p:last-of-type {
  margin: 0;
  color: var(--muted);
  font-size: 14px;
}

.not-found-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  margin-top: 14px;
}

.not-found-primary-action,
.not-found-secondary-action {
  display: inline-flex;
  min-height: 44px;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 0 16px;
  border-radius: var(--r-sm);
  font-size: 14px;
  font-weight: var(--fw-semibold);
  text-decoration: none;
  cursor: pointer;
}

.not-found-primary-action {
  color: #fff;
  background: var(--accent);
  border: 1px solid var(--accent);
}

.not-found-secondary-action {
  color: var(--text);
  background: var(--surface);
  border: 1px solid var(--border-strong);
}

.not-found-primary-action:hover {
  background: var(--accent-hover);
  border-color: var(--accent-hover);
}

.not-found-secondary-action:hover {
  color: var(--accent-strong);
  background: var(--accent-tint);
  border-color: rgba(8, 126, 167, 0.35);
}

.not-found-primary-action:focus-visible,
.not-found-secondary-action:focus-visible {
  outline: 3px solid var(--accent-ring);
  outline-offset: 2px;
}

@media (max-width: 480px) {
  .not-found-page {
    padding: 32px 16px 72px;
  }

  .not-found-actions {
    display: grid;
  }
}
</style>
