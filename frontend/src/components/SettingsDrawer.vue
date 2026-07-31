<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { X } from '@lucide/vue'
import { checkSettingsConnectivity, getSettingsStatus, updateRuntimeToggle, updateRuntimeValue } from '@/api/settings'
import type { ConnectivityItem, SettingsStatus } from '@/types/settings'
import KnowledgeIndexPanels from '@/components/KnowledgeIndexPanels.vue'
import SettingsSectionRuntime from '@/components/settings/SettingsSectionRuntime.vue'
import SettingsSectionReadonly from '@/components/settings/SettingsSectionReadonly.vue'
import SettingsSectionConnectivity from '@/components/settings/SettingsSectionConnectivity.vue'
import SettingsSectionLlmConfig from '@/components/settings/SettingsSectionLlmConfig.vue'
import SettingsSectionPrompts from '@/components/settings/SettingsSectionPrompts.vue'

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

const collapsedSections = ref<Record<string, boolean>>({
  runtime: true,
  readonly: true,
  connectivity: true,
  knowledge: true,
  llmConfig: true,
  prompts: true,
})

function toggleSection(key: string) {
  collapsedSections.value[key] = !collapsedSections.value[key]
}

const dynamicToggles = computed(() => settings.value?.dynamicToggles ?? [])
const dynamicValues = computed(() => settings.value?.dynamicValues ?? [])
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

async function updateSettingValue(key: string, value: string) {
  if (savingKey.value) {
    return
  }
  savingKey.value = key
  loadError.value = ''
  try {
    await updateRuntimeValue(key, value)
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
</script>

<template>
  <Teleport to="body">
    <Transition name="settings-fade">
      <div v-show="open" class="settings-layer" role="presentation" @click.self="closeDrawer">
        <aside class="settings-drawer" role="dialog" aria-modal="true" aria-labelledby="settings-title">
          <header class="settings-header">
            <div>
              <h2 id="settings-title">设置</h2>
            </div>
            <button type="button" class="settings-close" aria-label="关闭设置面板" @click="closeDrawer">
              <X :size="18" :stroke-width="1.8" aria-hidden="true" />
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
              <SettingsSectionRuntime
                :toggles="dynamicToggles"
                :values="dynamicValues"
                :saving-key="savingKey"
                :collapsed="collapsedSections.runtime ?? false"
                :loading="loading"
                :error="loadError"
                @toggle="toggleSetting"
                @value-change="updateSettingValue"
                @load="loadSettings"
                @toggle-section="toggleSection('runtime')"
              />

              <SettingsSectionReadonly
                :settings="readonlySettings"
                :collapsed="collapsedSections.readonly ?? false"
                @toggle-section="toggleSection('readonly')"
              />

              <SettingsSectionConnectivity
                :items="connectivityItems"
                :loading="connectivityLoading"
                :error="connectivityError"
                :collapsed="collapsedSections.connectivity ?? false"
                @check="runConnectivityCheck"
                @toggle-section="toggleSection('connectivity')"
              />

              <section class="creator-section settings-section">
                <button
                  type="button"
                  class="settings-section-toggle"
                  :aria-expanded="!collapsedSections.knowledge"
                  @click="toggleSection('knowledge')"
                >
                  <span class="settings-section-chevron" :class="{ open: !collapsedSections.knowledge }">▸</span>
                  <h3>知识库索引</h3>
                </button>

                <div v-if="!collapsedSections.knowledge" class="settings-section-body">
                  <KnowledgeIndexPanels />
                </div>
              </section>

              <SettingsSectionLlmConfig
                :collapsed="collapsedSections.llmConfig ?? false"
                :load-on-open="open && developerMode"
                @toggle-section="toggleSection('llmConfig')"
              />

              <SettingsSectionPrompts
                :collapsed="collapsedSections.prompts ?? false"
                :load-on-open="open && developerMode"
                @toggle-section="toggleSection('prompts')"
              />
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
  backdrop-filter: none;
}

.settings-drawer {
  width: min(820px, calc(100vw - 32px));
  max-height: min(840px, calc(100vh - 40px));
  overflow: hidden;
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-lg);
}

.settings-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--s3);
  padding: var(--s3) var(--s4);
  border-bottom: 1px solid var(--border);
}

.settings-header h2 {
  margin: 0;
  font-size: 18px;
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
  font-size: 22px;
  line-height: 1;
}

.settings-close:focus-visible,
.settings-switch:focus-visible {
  outline: 3px solid var(--accent-ring);
  outline-offset: 2px;
}

.settings-body {
  display: grid;
  gap: var(--s3);
  max-height: calc(min(840px, 100vh - 40px) - 69px);
  overflow-y: auto;
  padding: var(--s3) var(--s4) var(--s4);
}

.settings-section {
  max-width: none;
  margin: 0;
}

.settings-developer-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) max-content;
  align-items: center;
  gap: var(--s3);
  padding: var(--s3);
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
  font-size: 14px;
  font-weight: var(--fw-semibold);
}

.settings-developer-card p {
  margin: 0;
  color: var(--text);
  font-size: 13px;
  line-height: 1.6;
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

.settings-section-body {
  padding-top: var(--s3);
}

.settings-developer-card + .settings-section {
  margin-top: var(--s2);
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

  .settings-developer-card {
    grid-template-columns: 1fr;
  }
}
</style>
