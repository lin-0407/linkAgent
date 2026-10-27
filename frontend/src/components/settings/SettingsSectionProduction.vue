<script setup lang="ts">
import type { BillingSettings, RuntimeToggle } from '@/types/settings'

defineProps<{
  toggles: RuntimeToggle[]
  billing: BillingSettings | null
  savingKey: string
  collapsed: boolean
  loading: boolean
  error: string
  refreshingRate: boolean
}>()

const emit = defineEmits<{
  toggle: [key: string, enabled: boolean]
  refreshRate: []
  load: []
  toggleSection: []
}>()

function numberValue(value: number | string): number {
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) ? parsed : Number.NaN
}

function rateText(value: number | string): string {
  const parsed = numberValue(value)
  return Number.isFinite(parsed) ? parsed.toFixed(4) : '—'
}

function money(value: number | string): string {
  const parsed = numberValue(value)
  if (!Number.isFinite(parsed)) return '—'
  return parsed < 0.1 ? parsed.toFixed(4) : parsed.toFixed(2)
}

const sourceText: Record<string, string> = {
  LIVE: '实时接口',
  CACHED: '上次获取',
  CONFIG_FALLBACK: '配置回退',
}
</script>

<template>
  <section class="creator-section settings-section">
    <div class="settings-section-heading">
      <button
        type="button"
        class="settings-section-toggle"
        :aria-expanded="!collapsed"
        @click="emit('toggleSection')"
      >
        <span class="settings-section-chevron" :class="{ open: !collapsed }">▸</span>
        <span class="settings-section-title">制作与付费能力</span>
        <span class="settings-section-hint" v-if="collapsed">
          {{ toggles.filter((toggle) => toggle.enabled).length }}/{{ toggles.length }} 已开启
        </span>
      </button>
      <button
        type="button"
        class="creator-secondary-action"
        :disabled="loading"
        @click.stop="emit('load')"
      >
        {{ loading ? '刷新中…' : '刷新' }}
      </button>
    </div>

    <div v-if="!collapsed" class="settings-section-body">
      <div v-if="error" class="creator-alert error-alert">
        <strong>设置加载失败</strong>
        <span>{{ error }}</span>
      </div>
      <p v-else-if="loading && toggles.length === 0" class="creator-muted">正在读取设置状态…</p>
      <template v-else>
        <div class="settings-toggle-list">
          <article v-for="toggle in toggles" :key="toggle.key" class="settings-toggle-card">
            <div>
              <strong>{{ toggle.name }}</strong>
              <small>{{ toggle.key }}</small>
              <p>{{ toggle.description }}</p>
              <p v-if="toggle.locked" class="settings-lock">{{ toggle.lockedReason }}</p>
            </div>
            <button
              type="button"
              class="settings-switch"
              :class="{ enabled: toggle.enabled }"
              :disabled="savingKey === toggle.key || toggle.locked"
              :aria-pressed="toggle.enabled"
              :aria-label="toggle.locked ? toggle.lockedReason : toggle.name"
              @click="emit('toggle', toggle.key, !toggle.enabled)"
            >
              <span>
                {{
                  toggle.locked
                    ? '不可用'
                    : savingKey === toggle.key
                      ? '保存中'
                      : toggle.enabled
                        ? '已开启'
                        : '开启'
                }}
              </span>
            </button>
          </article>
        </div>

        <article v-if="billing" class="settings-billing-card">
          <header class="settings-billing-head">
            <div>
              <strong>汇率与费用参考</strong>
              <small>公开参考汇率，不等于银行成交价或供应商账单</small>
            </div>
            <button
              type="button"
              class="creator-secondary-action"
              :disabled="refreshingRate"
              @click="emit('refreshRate')"
            >
              {{ refreshingRate ? '获取中…' : '刷新汇率' }}
            </button>
          </header>

          <div class="settings-rate">
            <b>1 USD ≈ ¥{{ rateText(billing.usdToCny) }}</b>
            <span class="settings-rate-source">{{ sourceText[billing.source] ?? billing.source }}</span>
            <span class="creator-muted">更新于 {{ billing.fetchedAt }}</span>
          </div>

          <p class="settings-billing-note" :class="{ warning: billing.stale }">{{ billing.note }}</p>

          <table class="settings-price-table">
            <thead>
              <tr>
                <th>能力</th>
                <th>单位</th>
                <th>参考区间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="range in billing.ranges" :key="range.key">
                <td>{{ range.name }}</td>
                <td>{{ range.unit }}</td>
                <td class="settings-price-value">¥{{ money(range.lowCny) }} – ¥{{ money(range.highCny) }}</td>
              </tr>
            </tbody>
          </table>
          <p class="settings-billing-note">
            区间按配置单价 × 当前汇率估算，上限为 1.5 倍；实际金额以供应商结算为准。
          </p>
        </article>
      </template>
    </div>
  </section>
</template>

<style scoped>
.settings-section {
  max-width: none;
  margin: 0;
}

.settings-section-heading {
  display: flex;
  align-items: center;
  gap: var(--s2);
}

.settings-section-heading .settings-section-toggle {
  flex: 1 1 auto;
  min-width: 0;
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

.settings-section-title {
  flex: 0 0 auto;
  font-size: var(--fs-base);
  font-weight: var(--fw-semibold);
}

.settings-section-chevron {
  display: inline-grid;
  place-items: center;
  width: 20px;
  height: 20px;
  font-size: var(--fs-base);
  line-height: 1;
  color: var(--muted);
  transition: transform var(--dur-fast) ease;
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
  font-size: var(--fs-xs);
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
  font-size: var(--fs-base);
  font-weight: var(--fw-semibold);
}

.settings-toggle-card small {
  display: block;
  margin-top: 2px;
  color: var(--muted);
  font-size: var(--fs-xs);
}

.settings-toggle-card p {
  margin: 0;
  color: var(--text);
  font-size: var(--fs-sm);
  line-height: 1.55;
}

.settings-toggle-card .settings-lock {
  margin-top: 4px;
  color: var(--muted);
  font-size: var(--fs-xs);
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
  font-size: var(--fs-base);
  transition:
    background var(--dur-fast) ease,
    color var(--dur-fast) ease,
    border-color var(--dur-fast) ease;
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

.settings-billing-card {
  margin-top: var(--s3);
  padding: var(--s3);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
}

.settings-billing-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s3);
}

.settings-billing-head strong {
  color: var(--ink);
  font-size: var(--fs-base);
  font-weight: var(--fw-semibold);
}

.settings-billing-head small {
  display: block;
  margin-top: 2px;
  color: var(--muted);
  font-size: var(--fs-xs);
}

.settings-rate {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--s2);
  margin-top: var(--s3);
}

.settings-rate b {
  color: var(--ink);
  font-family: var(--font-code);
  font-size: var(--fs-lg);
}

.settings-rate-source {
  padding: 1px 7px;
  color: var(--accent);
  background: var(--accent-tint);
  border-radius: 999px;
  font-size: var(--fs-xs);
}

.settings-billing-note {
  margin: var(--s2) 0 0;
  color: var(--muted);
  font-size: var(--fs-xs);
  line-height: 1.55;
}

.settings-billing-note.warning {
  color: var(--danger);
}

.settings-price-table {
  width: 100%;
  margin-top: var(--s3);
  border-collapse: collapse;
  font-size: var(--fs-sm);
}

.settings-price-table th,
.settings-price-table td {
  padding: 7px 8px;
  border-bottom: 1px solid var(--border);
  text-align: left;
  vertical-align: top;
}

.settings-price-table th {
  color: var(--muted);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
}

.settings-price-table td {
  color: var(--text);
}

.settings-price-value {
  color: var(--ink);
  font-family: var(--font-code);
  white-space: nowrap;
}

@media (max-width: 640px) {
  .settings-toggle-list,
  .settings-toggle-card {
    grid-template-columns: 1fr;
  }

  .settings-billing-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .settings-price-table th:nth-child(2),
  .settings-price-table td:nth-child(2) {
    display: none;
  }
}
</style>
