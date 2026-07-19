<script setup lang="ts">
import type { ReadonlySetting } from '@/types/settings'

defineProps<{
  settings: ReadonlySetting[]
  collapsed: boolean
}>()

const emit = defineEmits<{
  toggleSection: []
}>()
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
      <h3>只读状态</h3>
      <span class="settings-section-hint" v-if="collapsed">
        {{ settings.length }} 项
      </span>
    </button>

    <div v-if="!collapsed" class="settings-section-body">
      <div class="settings-readonly-grid">
        <article v-for="item in settings" :key="item.key" class="settings-readonly-card">
          <strong>{{ item.name }}</strong>
          <b>{{ item.value }}</b>
          <small>{{ item.key }}</small>
          <p>{{ item.description }}</p>
        </article>
      </div>
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

.settings-readonly-grid {
  display: grid;
  gap: var(--s3);
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
}

.settings-readonly-card {
  display: grid;
  gap: 6px;
  padding: 10px var(--s3);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
}

.settings-readonly-card strong {
  color: var(--ink);
  font-size: 14px;
  font-weight: var(--fw-semibold);
}

.settings-readonly-card small {
  display: block;
  margin-top: 2px;
  color: var(--muted);
  font-size: 12px;
}

.settings-readonly-card p {
  margin: 0;
  color: var(--text);
  font-size: 13px;
  line-height: 1.55;
}

.settings-readonly-card b {
  width: fit-content;
  padding: 3px 10px;
  color: var(--ink);
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  font-size: 12px;
}
</style>
