<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { checkSettingsConnectivity, getSettingsStatus, updateRuntimeToggle } from '@/api/settings'
import type { ConnectivityItem, SettingsStatus } from '@/types/settings'
import KnowledgeIndexPanels from '@/components/KnowledgeIndexPanels.vue'

const open = defineModel<boolean>('open', { default: false })
const developerMode = defineModel<boolean>('developerMode', { default: false })

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

watch([open, developerMode], ([isOpen, isDeveloperMode]) => {
  if (isOpen && isDeveloperMode && !hasLoaded.value) {
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
              <h2 id="settings-title">设置</h2>
            </div>
            <button type="button" class="settings-close" aria-label="关闭设置面板" @click="closeDrawer">
              ×
            </button>
          </header>

          <main class="settings-body">
            <section class="creator-section settings-section">
              <div class="creator-section-head"><h3>开发者模式</h3></div>
              <article class="settings-developer-card">
                <div>
                  <strong>{{ developerMode ? '已开启' : '已关闭' }}</strong>
                  <p>
                    开启后显示模型连接、索引状态、生成消耗、评测样例和处理记录。普通创作流程会默认隐藏这些工程信息。
                  </p>
                </div>
                <button
                  type="button"
                  class="settings-switch"
                  :class="{ enabled: developerMode }"
                  :aria-pressed="developerMode"
                  @click="developerMode = !developerMode"
                >
                  <span>{{ developerMode ? '关闭' : '开启' }}</span>
                </button>
              </article>
            </section>

            <template v-if="developerMode">
            <section class="creator-section settings-section">
              <div class="creator-section-head">
                <h3>运行期开关</h3>
                <button type="button" class="creator-secondary-action" :disabled="loading" @click="loadSettings">
                  {{ loading ? '刷新中…' : '刷新' }}
                </button>
              </div>

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
                    <span>{{ savingKey === toggle.key ? '保存中' : toggle.enabled ? '已开启' : '开启' }}</span>
                  </button>
                </article>
              </div>
            </section>

            <section class="creator-section settings-section">
              <div class="creator-section-head"><h3>只读状态</h3></div>
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
              <KnowledgeIndexPanels />
            </section>
            </template>

            <section v-else class="creator-section settings-section">
              <div class="creator-section-head"><h3>普通设置</h3></div>
              <p class="creator-muted">
                当前是普通创作模式。发布方案、观众反馈和复盘报告会优先展示可直接采用的内容。
              </p>
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
  align-items: center;
  justify-content: center;
  padding: var(--s5);
  background: rgba(15, 23, 42, 0.42);
  backdrop-filter: blur(6px);
}

.settings-drawer {
  width: min(820px, calc(100vw - 32px));
  max-height: min(840px, calc(100vh - 40px));
  overflow: hidden;
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-lg);
  box-shadow: 0 24px 80px rgba(15, 23, 42, 0.28);
}

.settings-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--s4);
  padding: var(--s4) var(--s5);
  border-bottom: 1px solid var(--border);
}

.settings-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: var(--fw-bold);
  letter-spacing: 0;
}

.settings-close {
  display: inline-grid;
  place-items: center;
  width: 36px;
  height: 36px;
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
  max-height: calc(min(840px, 100vh - 40px) - 69px);
  overflow-y: auto;
  padding: var(--s4) var(--s5) var(--s5);
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

.settings-developer-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) max-content;
  align-items: center;
  gap: var(--s4);
  padding: var(--s4);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
}

.settings-developer-card div {
  display: grid;
  min-width: 0;
  gap: 6px;
}

.settings-developer-card strong {
  color: var(--ink);
  font-size: 16px;
  font-weight: var(--fw-semibold);
}

.settings-developer-card p {
  margin: 0;
  color: var(--text);
  font-size: 13px;
  line-height: 1.6;
}

.settings-toggle-list {
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
}

.settings-toggle-card,
.settings-readonly-card,
.settings-connectivity-card {
  display: grid;
  gap: 6px;
  padding: var(--s3) var(--s4);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
}

.settings-toggle-card {
  grid-template-columns: minmax(0, 1fr) max-content;
  align-items: center;
  column-gap: var(--s4);
}

.settings-toggle-card strong,
.settings-readonly-card strong,
.settings-connectivity-card strong {
  color: var(--ink);
  font-size: 15px;
  font-weight: var(--fw-semibold);
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
  transition:
    opacity 180ms ease,
    transform 220ms ease;
}

.settings-fade-enter-from .settings-drawer,
.settings-fade-leave-to .settings-drawer {
  opacity: 0;
  transform: translateY(14px) scale(0.98);
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
  .settings-layer {
    padding: var(--s3);
  }

  .settings-drawer {
    width: 100%;
    max-height: calc(100vh - 24px);
  }

  .settings-header,
  .settings-body {
    padding-left: var(--s4);
    padding-right: var(--s4);
  }

  .settings-toggle-list,
  .settings-toggle-card,
  .settings-developer-card {
    grid-template-columns: 1fr;
  }
}
</style>
