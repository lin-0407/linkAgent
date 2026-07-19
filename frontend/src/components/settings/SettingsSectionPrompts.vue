<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { listPromptTemplates, updatePromptContent } from '@/api/prompts'
import type { PromptTemplate } from '@/types/prompts'

const props = defineProps<{
  collapsed: boolean
  loadOnOpen: boolean
}>()

const emit = defineEmits<{
  toggleSection: []
}>()

const promptTemplates = ref<PromptTemplate[]>([])
const promptLoading = ref(false)
const promptError = ref('')
const promptSavingKey = ref('')
const expandedPromptKey = ref<string | null>(null)
const editingPromptContent = ref('')

const promptGroups = computed(() => {
  const map = new Map<string, PromptTemplate[]>()
  for (const t of promptTemplates.value) {
    const list = map.get(t.scene) || []
    list.push(t)
    map.set(t.scene, list)
  }
  return Array.from(map.entries()).map(([scene, items]) => ({ scene, items }))
})

watch(() => props.loadOnOpen, (shouldLoad) => {
  if (shouldLoad && promptTemplates.value.length === 0 && !promptLoading.value) {
    void loadPrompts()
  }
}, { immediate: true })

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
    template.content = editingPromptContent.value
    expandedPromptKey.value = null
    editingPromptContent.value = ''
  } catch (e) {
    promptError.value = e instanceof Error ? e.message : String(e)
  } finally {
    promptSavingKey.value = ''
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
      <h3>提示词模板管理</h3>
      <span class="settings-section-hint" v-if="collapsed && promptTemplates.length">
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

    <div v-if="!collapsed" class="settings-section-body">
      <div v-if="promptError" class="creator-alert error-alert">
        <strong>提示词加载失败</strong>
        <span>{{ promptError }}</span>
      </div>

      <div v-if="promptTemplates.length === 0 && !promptLoading && !promptError" class="creator-muted">
        点击"加载提示词"查看所有提示词模板。
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
