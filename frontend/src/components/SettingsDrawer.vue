<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { checkSettingsConnectivity, getSettingsStatus, updateRuntimeToggle } from '@/api/settings'
import type { ConnectivityItem, SettingsStatus } from '@/types/settings'
import KnowledgeIndexPanels from '@/components/KnowledgeIndexPanels.vue'
import { listPromptTemplates, updatePromptContent } from '@/api/prompts'
import type { PromptTemplate } from '@/types/prompts'

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

// ---- 设置分区折叠 ----
// 默认全部折叠，只展开开发者模式（第一个分区）
// 做成手机设置那种手风琴：点击标题展开/收缩，互不影响
const collapsedSections = ref<Record<string, boolean>>({
  runtime: true,
  readonly: true,
  connectivity: true,
  knowledge: true,
  prompts: true,
})

function toggleSection(key: string) {
  collapsedSections.value[key] = !collapsedSections.value[key]
}

// ---- 提示词管理 ----
const promptTemplates = ref<PromptTemplate[]>([])
const promptLoading = ref(false)
const promptError = ref('')
const promptSavingKey = ref('')
const expandedPromptKey = ref<string | null>(null)
const editingPromptContent = ref('')

/** 按场景分组 */
const promptGroups = computed(() => {
  const map = new Map<string, PromptTemplate[]>()
  for (const t of promptTemplates.value) {
    const list = map.get(t.scene) || []
    list.push(t)
    map.set(t.scene, list)
  }
  return Array.from(map.entries()).map(([scene, items]) => ({ scene, items }))
})

async function loadPrompts() {
  promptLoading.value = true
  promptError.value = ''
  try {
    promptTemplates.value = await listPromptTemplates()
  } catch (e) {
    promptError.value = e instanceof Error ? e.message : String(e)
  } finally {
    promptLoading.value = false
  }
}

function startEditPrompt(template: PromptTemplate) {
  expandedPromptKey.value = template.promptKey
  editingPromptContent.value = template.content
}

function cancelEditPrompt() {
  expandedPromptKey.value = null
  editingPromptContent.value = ''
}

async function savePrompt(template: PromptTemplate) {
  if (promptSavingKey.value) return
  promptSavingKey.value = template.promptKey
  promptError.value = ''
  try {
    await updatePromptContent(template.promptKey, editingPromptContent.value)
    // 更新本地缓存
    template.content = editingPromptContent.value
    expandedPromptKey.value = null
    editingPromptContent.value = ''
  } catch (e) {
    promptError.value = e instanceof Error ? e.message : String(e)
  } finally {
    promptSavingKey.value = ''
  }
}

const dynamicToggles = computed(() => settings.value?.dynamicToggles ?? [])
const readonlySettings = computed(() => settings.value?.readonlySettings ?? [])

watch([open, developerMode], ([isOpen, isDeveloperMode]) => {
  if (isOpen && isDeveloperMode && !hasLoaded.value) {
    void loadSettings()
  }
  // 打开设置且开发者模式时自动加载提示词
  if (isOpen && isDeveloperMode && promptTemplates.value.length === 0 && !promptLoading.value) {
    void loadPrompts()
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
            <!-- ═══ 运行期开关 ═══ -->
            <section class="creator-section settings-section">
              <button
                type="button"
                class="settings-section-toggle"
                :aria-expanded="!collapsedSections.runtime"
                @click="toggleSection('runtime')"
              >
                <span class="settings-section-chevron" :class="{ open: !collapsedSections.runtime }">▸</span>
                <h3>运行期开关</h3>
                <span class="settings-section-hint" v-if="collapsedSections.runtime">
                  {{ dynamicToggles.filter(t => t.enabled).length }}/{{ dynamicToggles.length }} 已开启
                </span>
                <button
                  type="button"
                  class="creator-secondary-action"
                  :disabled="loading"
                  @click.stop="loadSettings"
                >
                  {{ loading ? '刷新中…' : '刷新' }}
                </button>
              </button>

              <div v-if="!collapsedSections.runtime" class="settings-section-body">
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
              </div>
            </section>

            <!-- ═══ 只读状态 ═══ -->
            <section class="creator-section settings-section">
              <button
                type="button"
                class="settings-section-toggle"
                :aria-expanded="!collapsedSections.readonly"
                @click="toggleSection('readonly')"
              >
                <span class="settings-section-chevron" :class="{ open: !collapsedSections.readonly }">▸</span>
                <h3>只读状态</h3>
                <span class="settings-section-hint" v-if="collapsedSections.readonly">
                  {{ readonlySettings.length }} 项
                </span>
              </button>

              <div v-if="!collapsedSections.readonly" class="settings-section-body">
                <div class="settings-readonly-grid">
                  <article v-for="item in readonlySettings" :key="item.key" class="settings-readonly-card">
                    <strong>{{ item.name }}</strong>
                    <b>{{ item.value }}</b>
                    <small>{{ item.key }}</small>
                    <p>{{ item.description }}</p>
                  </article>
                </div>
              </div>
            </section>

            <!-- ═══ 连通性检测 ═══ -->
            <section class="creator-section settings-section">
              <button
                type="button"
                class="settings-section-toggle"
                :aria-expanded="!collapsedSections.connectivity"
                @click="toggleSection('connectivity')"
              >
                <span class="settings-section-chevron" :class="{ open: !collapsedSections.connectivity }">▸</span>
                <h3>连通性检测</h3>
                <span class="settings-section-hint" v-if="collapsedSections.connectivity">
                  点击展开后可检测 MySQL、Redis、向量库和模型连接
                </span>
                <button
                  type="button"
                  class="creator-secondary-action"
                  :disabled="connectivityLoading"
                  @click.stop="runConnectivityCheck"
                >
                  {{ connectivityLoading ? '检测中…' : '检测连接' }}
                </button>
              </button>

              <div v-if="!collapsedSections.connectivity" class="settings-section-body">
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
                <p v-else class="creator-muted">点击"检测连接"后查看 MySQL、Redis、向量库和模型 Bean 状态。</p>
              </div>
            </section>

            <!-- ═══ 知识库索引 ═══ -->
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

            <!-- ═══ 提示词管理 ═══ -->
            <section class="creator-section settings-section">
              <button
                type="button"
                class="settings-section-toggle"
                :aria-expanded="!collapsedSections.prompts"
                @click="toggleSection('prompts')"
              >
                <span class="settings-section-chevron" :class="{ open: !collapsedSections.prompts }">▸</span>
                <h3>提示词管理（Agent Prompt）</h3>
                <span class="settings-section-hint" v-if="collapsedSections.prompts && promptTemplates.length">
                  {{ promptTemplates.length }} 条模板
                </span>
                <button
                  type="button"
                  class="creator-secondary-action creator-mini-button"
                  :disabled="promptLoading"
                  @click.stop="loadPrompts"
                >
                  {{ promptLoading ? '加载中…' : promptTemplates.length ? '刷新' : '加载提示词' }}
                </button>
              </button>

              <div v-if="!collapsedSections.prompts" class="settings-section-body">
                <div v-if="promptError" class="creator-alert error-alert">
                  <strong>提示词加载失败</strong>
                  <span>{{ promptError }}</span>
                </div>

                <div v-if="promptTemplates.length === 0 && !promptLoading && !promptError" class="creator-muted">
                  点击"加载提示词"查看所有 Agent 提示词模板。
                </div>

                <div v-for="group in promptGroups" :key="group.scene" class="prompt-group">
                  <h4 class="prompt-group-title">{{ group.scene }}</h4>
                  <div class="prompt-list">
                    <article
                      v-for="tpl in group.items"
                      :key="tpl.promptKey"
                      class="prompt-card"
                      :class="{ expanded: expandedPromptKey === tpl.promptKey }"
                    >
                      <div class="prompt-card-head">
                        <div>
                          <strong>{{ tpl.promptKey }}</strong>
                          <div class="prompt-card-meta">
                            <span class="prompt-type-badge">{{ tpl.promptType }}</span>
                            <small v-if="tpl.description">{{ tpl.description }}</small>
                          </div>
                        </div>
                        <button
                          v-if="expandedPromptKey !== tpl.promptKey"
                          type="button"
                          class="creator-secondary-action creator-mini-button"
                          @click="startEditPrompt(tpl)"
                        >
                          编辑
                        </button>
                      </div>

                      <template v-if="expandedPromptKey === tpl.promptKey">
                        <textarea
                          v-model="editingPromptContent"
                          class="prompt-edit-area"
                          rows="8"
                          spellcheck="false"
                        />
                        <div class="prompt-edit-actions">
                          <button
                            type="button"
                            class="creator-secondary-action creator-mini-button"
                            @click="cancelEditPrompt"
                          >
                            取消
                          </button>
                          <button
                            type="button"
                            class="creator-primary-button creator-mini-button"
                            :disabled="promptSavingKey === tpl.promptKey"
                            @click="savePrompt(tpl)"
                          >
                            {{ promptSavingKey === tpl.promptKey ? '保存中…' : '保存并生效' }}
                          </button>
                        </div>
                      </template>
                    </article>
                  </div>
                </div>
              </div>
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

/* ---- 提示词管理 ---- */
/* ---- 手风琴折叠分区 ---- */
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
  font-size: 16px;
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

/* 分隔首屏固定展开的开发者模式卡片与手风琴分区 */
.settings-developer-card + .settings-section {
  margin-top: var(--s2);
}

/* ---- 提示词管理 ---- */
.prompt-group {
  margin-bottom: var(--s4);
}

.prompt-group-title {
  margin: 0 0 var(--s2);
  color: var(--ink);
  font-size: 14px;
  font-weight: var(--fw-semibold);
  padding-bottom: var(--s2);
  border-bottom: 1px solid var(--border);
}

.prompt-list {
  display: grid;
  gap: var(--s2);
}

.prompt-card {
  display: grid;
  gap: var(--s3);
  padding: var(--s3);
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
}

.prompt-card.expanded {
  background: var(--surface);
  border-color: var(--accent);
}

.prompt-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s3);
}

.prompt-card-head > div {
  min-width: 0;
  display: grid;
  gap: 4px;
}

.prompt-card-head strong {
  color: var(--ink);
  font-size: 13px;
  font-family: var(--font-code);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.prompt-card-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s2);
}

.prompt-type-badge {
  display: inline-block;
  padding: 1px 6px;
  color: var(--accent);
  background: var(--accent-tint);
  border: 1px solid var(--accent-ring);
  border-radius: var(--r-pill);
  font-size: 11px;
  font-weight: var(--fw-semibold);
  line-height: 1.4;
}

.prompt-card-meta small {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.4;
}

.prompt-edit-area {
  width: 100%;
  padding: var(--s3);
  color: var(--ink);
  font-family: var(--font-code);
  font-size: 13px;
  line-height: 1.6;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  outline: none;
  resize: vertical;
}

.prompt-edit-area:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-ring);
}

.prompt-edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--s2);
}

@media (max-width: 640px) {
  .prompt-card-head {
    flex-direction: column;
  }

  .prompt-edit-actions {
    flex-direction: column;
  }
}
</style>
