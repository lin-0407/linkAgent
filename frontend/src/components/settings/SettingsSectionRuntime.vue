<script setup lang="ts">
import type { RuntimeToggle, RuntimeValue } from '@/types/settings'

const props = defineProps<{
  toggles: RuntimeToggle[]
  values: RuntimeValue[]
  savingKey: string
  collapsed: boolean
  loading: boolean
  error: string
}>()

const emit = defineEmits<{
  toggle: [key: string, enabled: boolean]
  valueChange: [key: string, value: string]
  load: []
  toggleSection: []
}>()

function handleValueChange(key: string, event: Event) {
  const target = event.target
  if (target instanceof HTMLSelectElement) {
    emit('valueChange', key, target.value)
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
      <h3>运行期开关</h3>
      <span class="settings-section-hint" v-if="collapsed">
        {{ toggles.filter(t => t.enabled).length }}/{{ toggles.length }} 已开启
      </span>
      <button
        type="button"
        class="creator-secondary-action"
        :disabled="loading"
        @click.stop="emit('load')"
      >
        {{ loading ? '刷新中…' : '刷新' }}
      </button>
    </button>

    <div v-if="!collapsed" class="settings-section-body">
      <div v-if="error" class="creator-alert error-alert">
        <strong>设置加载失败</strong>
        <span>{{ error }}</span>
      </div>
      <p v-else-if="loading && toggles.length === 0" class="creator-muted">正在读取设置状态…</p>
      <div v-else class="settings-toggle-list">
        <article v-for="toggle in toggles" :key="toggle.key" class="settings-toggle-card">
          <div>
            <strong>{{ toggle.name }}</strong>
            <small>{{ toggle.key }}</small>
            <p>{{ toggle.description }}</p>
          </div>
          <button
            type="button"
            class="settings-switch"
            :class="{ enabled: toggle.enabled }"
            :disabled="savingKey === toggle.key"
            :aria-pressed="toggle.enabled"
            @click="emit('toggle', toggle.key, !toggle.enabled)"
          >
            <span>{{ savingKey === toggle.key ? '保存中' : toggle.enabled ? '已开启' : '开启' }}</span>
          </button>
        </article>
      </div>
      <div v-if="props.values.length" class="settings-value-list">
        <article v-for="setting in props.values" :key="setting.key" class="settings-value-card">
          <div>
            <strong>{{ setting.name }}</strong>
            <small>{{ setting.key }}</small>
            <p>{{ setting.description }}</p>
          </div>
          <select
            class="settings-value-select"
            :value="setting.value"
            :disabled="savingKey === setting.key"
            :aria-label="setting.name"
            @change="handleValueChange(setting.key, $event)"
          >
            <option v-for="option in setting.options" :key="option" :value="option">{{ option }}</option>
          </select>
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

.settings-toggle-list {
  display: grid;
  gap: var(--s3);
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
}

.settings-value-list {
  display: grid;
  gap: var(--s3);
  margin-top: var(--s3);
}

.settings-value-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) max-content;
  align-items: center;
  gap: var(--s3);
  padding: 10px var(--s3);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
}

.settings-value-card strong {
  color: var(--ink);
  font-size: 14px;
  font-weight: var(--fw-semibold);
}

.settings-value-card small {
  display: block;
  margin-top: 2px;
  color: var(--muted);
  font-size: 12px;
}

.settings-value-card p {
  margin: 0;
  color: var(--text);
  font-size: 13px;
  line-height: 1.55;
}

.settings-value-select {
  min-width: 92px;
  min-height: 34px;
  padding: 0 9px;
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-sm);
  font-family: var(--font-code);
  font-size: 13px;
}

.settings-value-select:disabled {
  cursor: not-allowed;
  opacity: 0.62;
}

.settings-toggle-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) max-content;
  align-items: center;
  column-gap: var(--s3);
  gap: 6px;
  padding: 10px var(--s3);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
}

.settings-toggle-card strong {
  color: var(--ink);
  font-size: 14px;
  font-weight: var(--fw-semibold);
}

.settings-toggle-card small {
  display: block;
  margin-top: 2px;
  color: var(--muted);
  font-size: 12px;
}

.settings-toggle-card p {
  margin: 0;
  color: var(--text);
  font-size: 13px;
  line-height: 1.55;
}

.settings-switch {
  min-width: 76px;
  min-height: 34px;
  padding: 0 14px;
  color: var(--surface);
  background: var(--danger);
  border: 1px solid var(--danger);
  border-radius: var(--r-sm);
  cursor: pointer;
  font-weight: var(--fw-semibold);
  font-size: 14px;
  transition:
    background 180ms ease,
    color 180ms ease,
    border-color 180ms ease;
}

.settings-switch.enabled {
  color: var(--muted);
  background: var(--surface-sub);
  border-color: var(--border);
}

.settings-switch:disabled {
  cursor: not-allowed;
  opacity: 0.62;
}

.settings-switch:focus-visible {
  outline: 3px solid var(--accent-ring);
  outline-offset: 2px;
}

@media (max-width: 640px) {
  .settings-toggle-list,
  .settings-toggle-card,
  .settings-value-card {
    grid-template-columns: 1fr;
  }
}
</style>
