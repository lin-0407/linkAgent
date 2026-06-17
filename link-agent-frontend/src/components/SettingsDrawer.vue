<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { checkSettingsConnectivity, getSettingsStatus, updateRuntimeToggle } from '@/api/settings'
import type { ConnectivityItem, SettingsStatus } from '@/types/settings'
import KnowledgeIndexPanels from '@/components/KnowledgeIndexPanels.vue'

const open = defineModel<boolean>('open', { default: false })

const settings = ref<SettingsStatus | null>(null)
const loading = ref(false)
const loadError = ref('')
const savingKey = ref('')

const connectivityItems = ref<ConnectivityItem[]>([])
const connectivityLoading = ref(false)
const connectivityError = ref('')

const hasLoaded = ref(false)

const dynamicToggles = computed(() => settings.value?.dynamicToggles ?? [])
const readonlySettings = computed(() => settings.value?.readonlySettings ?? [])

watch(open, (value) => {
  if (value && !hasLoaded.value) {
    void loadSettings()
  }
})

async function loadSettings() {
  loading.value = true
  loadError.value = ''
  try {
    settings.value = await getSettingsStatus()
    hasLoaded.value = true
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : String(error)
  } finally {
    loading.value = false
  }
}

async function toggleSetting(key: string, enabled: boolean) {
  if (savingKey.value) {
    return
  }
  savingKey.value = key
  loadError.value = ''
  try {
    await updateRuntimeToggle(key, enabled)
    await loadSettings()
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : String(error)
  } finally {
    savingKey.value = ''
  }
}

async function runConnectivityCheck() {
  connectivityLoading.value = true
  connectivityError.value = ''
  try {
    const result = await checkSettingsConnectivity()
    connectivityItems.value = result.items
  } catch (error) {
    connectivityError.value = error instanceof Error ? error.message : String(error)
  } finally {
    connectivityLoading.value = false
  }
}

function closeDrawer() {
  open.value = false
}

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
  <Teleport to="body">
    <Transition name="settings-fade">
      <div v-if="open" class="settings-layer" role="presentation" @click.self="closeDrawer">
        <aside class="settings-drawer" role="dialog" aria-modal="true" aria-labelledby="settings-title">
          <header class="settings-header">
            <div>
              <p class="creator-kicker">系统设置</p>
              <h2 id="settings-title">运行状态与运维</h2>
              <p>动态开关即时生效；启动期配置只读展示，修改配置后需重启。</p>
            </div>
            <button type="button" class="settings-close" aria-label="关闭设置面板" @click="closeDrawer">
              ×
            </button>
          </header>

          <main class="settings-body">
            <section class="creator-section settings-section">
              <div class="creator-section-head">
                <h3>运行期开关</h3>
                <button type="button" class="creator-secondary-action" :disabled="loading" @click="loadSettings">
                  {{ loading ? '刷新中…' : '刷新' }}
                </button>
              </div>
              <p class="creator-inline-note">
                这些开关在业务调用前读取，适合运行期调整。设置会写入数据库，重启后仍保留。
              </p>

              <div v-if="loadError" class="creator-alert error-alert">
                <strong>设置加载失败</strong>
                <span>{{ loadError }}</span>
              </div>
              <p v-else-if="loading && !settings" class="creator-muted">正在读取设置状态…</p>
              <div v-else class="settings-toggle-list">
                <article v-for="toggle in dynamicToggles" :key="toggle.key" class="settings-toggle-card">
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
                    @click="toggleSetting(toggle.key, !toggle.enabled)"
                  >
                    <span>{{ savingKey === toggle.key ? '保存中' : toggle.enabled ? '开启' : '关闭' }}</span>
                  </button>
                </article>
              </div>
            </section>

            <section class="creator-section settings-section">
              <div class="creator-section-head"><h3>只读状态</h3></div>
              <p class="creator-inline-note">
                这些配置影响 Spring 启动期 Bean 装配。设置页只展示当前值，不在运行期修改。
              </p>
              <div class="settings-readonly-grid">
                <article v-for="item in readonlySettings" :key="item.key" class="settings-readonly-card">
                  <strong>{{ item.name }}</strong>
                  <b>{{ item.value }}</b>
                  <small>{{ item.key }}</small>
                  <p>{{ item.description }}</p>
                </article>
              </div>
            </section>

            <section class="creator-section settings-section">
              <div class="creator-section-head">
                <h3>连通性检测</h3>
                <button
                  type="button"
                  class="creator-secondary-action"
                  :disabled="connectivityLoading"
                  @click="runConnectivityCheck"
                >
                  {{ connectivityLoading ? '检测中…' : '检测连接' }}
                </button>
              </div>
              <p class="creator-inline-note">
                检测只验证连接或 Bean 是否存在，不主动调用 LLM 或 Embedding，避免设置页产生模型成本。
              </p>
              <div v-if="connectivityError" class="creator-alert error-alert">
                <strong>检测失败</strong>
                <span>{{ connectivityError }}</span>
              </div>
              <div v-if="connectivityItems.length" class="settings-connectivity-grid">
                <article
                  v-for="item in connectivityItems"
                  :key="item.key"
                  class="settings-connectivity-card"
                  :class="item.status.toLowerCase()"
                >
                  <span>{{ statusLabel(item.status) }}</span>
                  <strong>{{ item.name }}</strong>
                  <p>{{ item.message }}</p>
                </article>
              </div>
              <p v-else class="creator-muted">点击“检测连接”后查看 MySQL、Redis、向量库和模型 Bean 状态。</p>
            </section>

            <section class="creator-section settings-section">
              <div class="creator-section-head"><h3>知识库索引</h3></div>
              <p class="creator-inline-note">
                这里集中处理案例库索引运维。案例库页面只保留采集、检索和列表。
              </p>
              <KnowledgeIndexPanels />
            </section>
          </main>
        </aside>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.settings-layer {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: flex;
  justify-content: flex-end;
  background: rgba(15, 23, 42, 0.36);
  backdrop-filter: blur(8px);
}

.settings-drawer {
  width: min(760px, calc(100vw - 18px));
  height: 100vh;
  overflow: hidden;
  color: var(--ink);
  background:
    radial-gradient(circle at 12% 0%, rgba(34, 197, 94, 0.12), transparent 32%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(248, 250, 252, 0.98));
  border-left: 1px solid var(--border);
  box-shadow: -24px 0 70px rgba(15, 23, 42, 0.24);
}

.settings-header {
  display: flex;
  justify-content: space-between;
  gap: var(--s4);
  padding: var(--s5);
  border-bottom: 1px solid var(--border);
}

.settings-header h2 {
  margin: 0;
  font-size: clamp(24px, 3vw, 36px);
}

.settings-header p {
  margin: var(--s2) 0 0;
  color: var(--muted);
  line-height: 1.6;
}

.settings-close {
  display: inline-grid;
  place-items: center;
  width: 44px;
  height: 44px;
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  cursor: pointer;
  font-size: 26px;
  line-height: 1;
}

.settings-close:focus-visible,
.settings-switch:focus-visible {
  outline: 3px solid var(--accent-ring);
  outline-offset: 2px;
}

.settings-body {
  display: grid;
  gap: var(--s4);
  height: calc(100vh - 142px);
  overflow-y: auto;
  padding: var(--s4) var(--s5) var(--s6);
}

.settings-section {
  max-width: none;
  margin: 0;
}

.settings-toggle-list,
.settings-readonly-grid,
.settings-connectivity-grid {
  display: grid;
  gap: var(--s3);
}

.settings-toggle-card,
.settings-readonly-card,
.settings-connectivity-card {
  display: grid;
  gap: var(--s2);
  padding: var(--s4);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
}

.settings-toggle-card {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
}

.settings-toggle-card strong,
.settings-readonly-card strong,
.settings-connectivity-card strong {
  color: var(--ink);
}

.settings-toggle-card small,
.settings-readonly-card small {
  display: block;
  margin-top: 2px;
  color: var(--muted);
  font-size: 12px;
}

.settings-toggle-card p,
.settings-readonly-card p,
.settings-connectivity-card p {
  margin: var(--s2) 0 0;
  color: var(--text);
  font-size: 13px;
  line-height: 1.55;
}

.settings-switch {
  min-width: 88px;
  min-height: 44px;
  padding: 0 16px;
  color: var(--muted);
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  cursor: pointer;
  font-weight: var(--fw-semibold);
  transition:
    background 180ms ease,
    color 180ms ease,
    border-color 180ms ease;
}

.settings-switch.enabled {
  color: white;
  background: var(--accent);
  border-color: var(--accent);
}

.settings-switch:disabled {
  cursor: not-allowed;
  opacity: 0.62;
}

.settings-readonly-grid {
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
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

.settings-connectivity-grid {
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
}

.settings-connectivity-card {
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

.settings-fade-enter-active,
.settings-fade-leave-active {
  transition: opacity 180ms ease;
}

.settings-fade-enter-from,
.settings-fade-leave-to {
  opacity: 0;
}

.settings-fade-enter-active .settings-drawer,
.settings-fade-leave-active .settings-drawer {
  transition: transform 220ms ease;
}

.settings-fade-enter-from .settings-drawer,
.settings-fade-leave-to .settings-drawer {
  transform: translateX(28px);
}

@media (prefers-reduced-motion: reduce) {
  .settings-fade-enter-active,
  .settings-fade-leave-active,
  .settings-fade-enter-active .settings-drawer,
  .settings-fade-leave-active .settings-drawer {
    transition: none;
  }
}

@media (max-width: 640px) {
  .settings-header,
  .settings-body {
    padding-left: var(--s4);
    padding-right: var(--s4);
  }

  .settings-toggle-card {
    grid-template-columns: 1fr;
  }
}
</style>
