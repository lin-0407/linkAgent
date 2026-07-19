<script setup lang="ts">
import type { ConnectivityItem } from '@/types/settings'

defineProps<{
  items: ConnectivityItem[]
  loading: boolean
  error: string
  collapsed: boolean
}>()

const emit = defineEmits<{
  check: []
  toggleSection: []
}>()

function statusLabel(status: string) {
  switch (status) {
    case 'UP':
      return '可用'
    case 'DOWN':
      return '异常'
    case 'DISABLED':
      return '未启用'
    default:
      return '未知'
  }
}
</script>

<template>
  <section class="creator-section settings-section">
    <button
      type="button"
      class="settings-section-toggle"
      :aria-expanded="!collapsed"
      @click="emit('toggleSection')"
    >
      <span class="settings-section-chevron" :class="{ open: !collapsed }">▸</span>
      <h3>连通性检测</h3>
      <span class="settings-section-hint" v-if="collapsed">
        点击展开后可检测 MySQL、Redis、向量库和模型连接
      </span>
      <button
        type="button"
        class="creator-secondary-action"
        :disabled="loading"
        @click.stop="emit('check')"
      >
        {{ loading ? '检测中…' : '检测连接' }}
      </button>
    </button>

    <div v-if="!collapsed" class="settings-section-body">
      <div v-if="error" class="creator-alert error-alert">
        <strong>检测失败</strong>
        <span>{{ error }}</span>
      </div>
      <div v-if="items.length" class="settings-connectivity-grid">
        <article
          v-for="item in items"
          :key="item.key"
          class="settings-connectivity-card"
          :class="item.status.toLowerCase()"
        >
          <span>{{ statusLabel(item.status) }}</span>
          <strong>{{ item.name }}</strong>
          <p>{{ item.message }}</p>
        </article>
      </div>
      <p v-else class="creator-muted">点击"检测连接"后查看 MySQL、Redis、向量库和模型 Bean 状态。</p>
    </div>
  </section>
</template>

<style scoped>
.settings-section {
  max-width: none;
  margin: 0;
}

.settings-section-toggle {
  display: flex;
  align-items: center;
  gap: var(--s2);
  width: 100%;
  padding: var(--s3) 0;
  color: var(--ink);
  background: none;
  border: none;
  border-bottom: 1px solid var(--border);
  cursor: pointer;
  text-align: left;
}

.settings-section-toggle:hover {
  color: var(--accent);
}

.settings-section-toggle h3 {
  margin: 0;
  font-size: 14px;
  font-weight: var(--fw-semibold);
  flex: 0 0 auto;
}

.settings-section-chevron {
  display: inline-grid;
  place-items: center;
  width: 20px;
  height: 20px;
  font-size: 14px;
  line-height: 1;
  color: var(--muted);
  transition: transform 180ms ease;
}

.settings-section-chevron.open {
  transform: rotate(90deg);
}

.settings-section-hint {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--muted);
  font-size: 12px;
  font-weight: var(--fw-regular);
}

.settings-section-body {
  padding-top: var(--s3);
}

.settings-connectivity-grid {
  display: grid;
  gap: var(--s3);
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
}

.settings-connectivity-card {
  display: grid;
  gap: 6px;
  padding: 10px var(--s3);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  border-left: 4px solid var(--border);
}

.settings-connectivity-card > span {
  width: fit-content;
  padding: 2px 8px;
  border-radius: var(--r-pill);
  font-size: 12px;
  font-weight: var(--fw-semibold);
}

.settings-connectivity-card.up {
  border-left-color: var(--ok);
}

.settings-connectivity-card.up > span {
  color: var(--ok);
  background: rgba(22, 163, 74, 0.08);
}

.settings-connectivity-card.down {
  border-left-color: var(--danger);
}

.settings-connectivity-card.down > span {
  color: var(--danger);
  background: rgba(220, 38, 38, 0.08);
}

.settings-connectivity-card.disabled {
  border-left-color: var(--muted);
}

.settings-connectivity-card.disabled > span {
  color: var(--muted);
  background: var(--surface-sub);
}

.settings-connectivity-card strong {
  color: var(--ink);
  font-size: 14px;
  font-weight: var(--fw-semibold);
}

.settings-connectivity-card p {
  margin: 0;
  color: var(--text);
  font-size: 13px;
  line-height: 1.55;
}
</style>
