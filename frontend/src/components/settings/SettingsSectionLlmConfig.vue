<script setup lang="ts">
import { ref, watch } from 'vue'
import {
  deleteLlmConfig,
  listLlmConfigs,
  saveLlmConfig,
  testLlmConnectivity,
} from '@/api/settings'
import type {
  UserLlmConfigRecord,
  UserLlmConfigSavePayload,
  UserLlmConfigTestResult,
} from '@/types/settings'

const props = defineProps<{
  collapsed: boolean
  loadOnOpen: boolean
}>()

const emit = defineEmits<{
  toggleSection: []
}>()

const llmConfigs = ref<UserLlmConfigRecord[]>([])
const llmConfigLoading = ref(false)
const llmConfigError = ref('')
const llmConfigSaving = ref('')
const llmConfigTesting = ref('')

const editingLlmConfig = ref<{
  configId: string
  provider: string
  llmBaseUrl: string
  llmApiKey: string
  llmModelName: string
  embeddingBaseUrl: string
  embeddingApiKey: string
  embeddingModelName: string
} | null>(null)

const showLlmKey = ref(false)
const showEmbeddingKey = ref(false)

const llmTestResult = ref<UserLlmConfigTestResult | null>(null)

const PROVIDER_LABELS: Record<string, string> = {
  DEEPSEEK: 'DeepSeek',
  OPENAI: 'OpenAI',
  SILICONFLOW: 'SiliconFlow',
  CUSTOM: '自定义',
}

watch(() => props.loadOnOpen, (shouldLoad) => {
  if (shouldLoad && llmConfigs.value.length === 0 && !llmConfigLoading.value) {
    void loadLlmConfigs()
  }
}, { immediate: true })

async function loadLlmConfigs() {
  llmConfigLoading.value = true
  llmConfigError.value = ''
  try {
    llmConfigs.value = await listLlmConfigs()
  } catch (e) {
    llmConfigError.value = e instanceof Error ? e.message : String(e)
  } finally {
    llmConfigLoading.value = false
  }
}

function startEditConfig(config: UserLlmConfigRecord) {
  editingLlmConfig.value = {
    configId: config.configId,
    provider: config.provider,
    llmBaseUrl: config.llmBaseUrl ?? '',
    llmApiKey: '',
    llmModelName: config.llmModelName ?? '',
    embeddingBaseUrl: config.embeddingBaseUrl ?? '',
    embeddingApiKey: '',
    embeddingModelName: config.embeddingModelName ?? '',
  }
  showLlmKey.value = false
  showEmbeddingKey.value = false
  llmTestResult.value = null
}

function startNewConfig() {
  editingLlmConfig.value = {
    configId: '',
    provider: 'DEEPSEEK',
    llmBaseUrl: '',
    llmApiKey: '',
    llmModelName: '',
    embeddingBaseUrl: '',
    embeddingApiKey: '',
    embeddingModelName: '',
  }
  showLlmKey.value = false
  showEmbeddingKey.value = false
  llmTestResult.value = null
}

function cancelEditConfig() {
  editingLlmConfig.value = null
  showLlmKey.value = false
  showEmbeddingKey.value = false
  llmTestResult.value = null
}

async function saveLlmConfigForm() {
  const form = editingLlmConfig.value
  if (!form || !form.provider) return
  llmConfigSaving.value = form.provider
  llmConfigError.value = ''
  try {
    const payload: UserLlmConfigSavePayload = {
      provider: form.provider,
      llmBaseUrl: form.llmBaseUrl.trim() || undefined,
      llmApiKey: form.llmApiKey.trim() || undefined,
      llmModelName: form.llmModelName.trim() || undefined,
      embeddingBaseUrl: form.embeddingBaseUrl.trim() || undefined,
      embeddingApiKey: form.embeddingApiKey.trim() || undefined,
      embeddingModelName: form.embeddingModelName.trim() || undefined,
    }
    await saveLlmConfig(payload)
    cancelEditConfig()
    await loadLlmConfigs()
  } catch (e) {
    llmConfigError.value = e instanceof Error ? e.message : String(e)
  } finally {
    llmConfigSaving.value = ''
  }
}

async function removeLlmConfig(configId: string, provider: string) {
  if (!confirm(`确定要删除「${PROVIDER_LABELS[provider] ?? provider}」的 API 配置吗？`)) return
  llmConfigSaving.value = provider
  llmConfigError.value = ''
  try {
    await deleteLlmConfig(configId)
    await loadLlmConfigs()
  } catch (e) {
    llmConfigError.value = e instanceof Error ? e.message : String(e)
  } finally {
    llmConfigSaving.value = ''
  }
}

async function testConfigConnectivity(configId: string) {
  llmConfigTesting.value = configId
  llmConfigError.value = ''
  llmTestResult.value = null
  try {
    llmTestResult.value = await testLlmConnectivity(configId)
  } catch (e) {
    llmConfigError.value = e instanceof Error ? e.message : String(e)
  } finally {
    llmConfigTesting.value = ''
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
      <h3>API 配置</h3>
      <span class="settings-section-hint" v-if="collapsed && llmConfigs.length">
        {{ llmConfigs.length }} 个供应商
      </span>
      <button
        type="button"
        class="creator-secondary-action creator-mini-button"
        :disabled="llmConfigLoading"
        @click.stop="loadLlmConfigs"
      >
        {{ llmConfigLoading ? '加载中…' : llmConfigs.length ? '刷新' : '加载配置' }}
      </button>
    </button>

    <div v-if="!collapsed" class="settings-section-body">
      <div v-if="llmConfigError" class="creator-alert error-alert">
        <strong>操作失败</strong>
        <span>{{ llmConfigError }}</span>
      </div>

      <p
        v-if="llmConfigs.length === 0 && !llmConfigLoading && !editingLlmConfig"
        class="creator-muted"
      >
        暂无 API 配置。点击"加载配置"查看已有配置，或点击下方按钮添加新的供应商配置。
      </p>

      <div v-if="llmConfigs.length > 0" class="llm-config-list">
        <article
          v-for="cfg in llmConfigs"
          :key="cfg.configId"
          class="llm-config-card"
        >
          <template v-if="editingLlmConfig?.configId !== cfg.configId">
            <div class="llm-config-card-head">
              <strong>{{ PROVIDER_LABELS[cfg.provider] ?? cfg.provider }}</strong>
              <div class="llm-config-card-actions">
                <button
                  type="button"
                  class="creator-secondary-action creator-mini-button"
                  :disabled="llmConfigTesting === cfg.configId"
                  @click="testConfigConnectivity(cfg.configId)"
                >
                  {{ llmConfigTesting === cfg.configId ? '测试中…' : '测试连接' }}
                </button>
                <button
                  type="button"
                  class="creator-secondary-action creator-mini-button"
                  @click="startEditConfig(cfg)"
                >
                  编辑
                </button>
                <button
                  type="button"
                  class="creator-secondary-action creator-mini-button"
                  :disabled="llmConfigSaving === cfg.provider"
                  @click="removeLlmConfig(cfg.configId, cfg.provider)"
                >
                  删除
                </button>
              </div>
            </div>

            <div
              v-if="llmTestResult && llmConfigTesting !== cfg.configId"
              class="llm-config-test-result"
              :class="llmTestResult.success ? 'success' : 'failed'"
            >
              <span v-if="llmTestResult.success">
                ✓ 连接成功（{{ llmTestResult.elapsedMs }}ms）
              </span>
              <span v-else>
                ✗ 连接失败：{{ llmTestResult.error }}
              </span>
            </div>

            <dl class="llm-config-fields">
              <template v-if="cfg.llmApiKeyMasked">
                <dt>LLM Key</dt>
                <dd><code>{{ cfg.llmApiKeyMasked }}</code></dd>
              </template>
              <template v-if="cfg.llmBaseUrl">
                <dt>LLM URL</dt>
                <dd>{{ cfg.llmBaseUrl }}</dd>
              </template>
              <template v-if="cfg.llmModelName">
                <dt>LLM 模型</dt>
                <dd>{{ cfg.llmModelName }}</dd>
              </template>
              <template v-if="cfg.embeddingApiKeyMasked">
                <dt>Embedding Key</dt>
                <dd><code>{{ cfg.embeddingApiKeyMasked }}</code></dd>
              </template>
              <template v-if="cfg.embeddingBaseUrl">
                <dt>Embedding URL</dt>
                <dd>{{ cfg.embeddingBaseUrl }}</dd>
              </template>
              <template v-if="cfg.embeddingModelName">
                <dt>Embedding 模型</dt>
                <dd>{{ cfg.embeddingModelName }}</dd>
              </template>
            </dl>
          </template>

          <div v-else class="llm-config-form">
            <strong>{{ PROVIDER_LABELS[cfg.provider] ?? cfg.provider }}</strong>

            <label class="llm-config-field">
              <span>LLM Base URL</span>
              <input
                v-model="editingLlmConfig!.llmBaseUrl"
                type="text"
                class="llm-config-input"
                placeholder="如 https://api.deepseek.com"
              />
            </label>

            <label class="llm-config-field">
              <span>LLM API Key</span>
              <div class="llm-config-key-wrap">
                <input
                  v-model="editingLlmConfig!.llmApiKey"
                  :type="showLlmKey ? 'text' : 'password'"
                  class="llm-config-input"
                  placeholder="留空不修改已有 Key"
                />
                <button
                  type="button"
                  class="creator-ghost-button llm-config-eye-btn"
                  @click="showLlmKey = !showLlmKey"
                >
                  {{ showLlmKey ? '隐藏' : '显示' }}
                </button>
              </div>
            </label>

            <label class="llm-config-field">
              <span>LLM 模型名称</span>
              <input
                v-model="editingLlmConfig!.llmModelName"
                type="text"
                class="llm-config-input"
                placeholder="如 deepseek-chat"
              />
            </label>

            <label class="llm-config-field">
              <span>Embedding Base URL</span>
              <input
                v-model="editingLlmConfig!.embeddingBaseUrl"
                type="text"
                class="llm-config-input"
                placeholder="留空复用 LLM URL"
              />
            </label>

            <label class="llm-config-field">
              <span>Embedding API Key</span>
              <div class="llm-config-key-wrap">
                <input
                  v-model="editingLlmConfig!.embeddingApiKey"
                  :type="showEmbeddingKey ? 'text' : 'password'"
                  class="llm-config-input"
                  placeholder="留空不修改已有 Key"
                />
                <button
                  type="button"
                  class="creator-ghost-button llm-config-eye-btn"
                  @click="showEmbeddingKey = !showEmbeddingKey"
                >
                  {{ showEmbeddingKey ? '隐藏' : '显示' }}
                </button>
              </div>
            </label>

            <label class="llm-config-field">
              <span>Embedding 模型名称</span>
              <input
                v-model="editingLlmConfig!.embeddingModelName"
                type="text"
                class="llm-config-input"
                placeholder="如 text-embedding-3-small"
              />
            </label>

            <div class="llm-config-form-actions">
              <button
                type="button"
                class="creator-secondary-action creator-mini-button"
                @click="cancelEditConfig"
              >
                取消
              </button>
              <button
                type="button"
                class="creator-primary-button creator-mini-button"
                :disabled="llmConfigSaving === cfg.provider"
                @click="saveLlmConfigForm"
              >
                {{ llmConfigSaving === cfg.provider ? '保存中…' : '保存' }}
              </button>
            </div>
          </div>
        </article>
      </div>

      <div v-if="editingLlmConfig && !editingLlmConfig.configId" class="llm-config-card">
        <div class="llm-config-form">
          <strong>新建配置</strong>

          <label class="llm-config-field">
            <span>供应商</span>
            <select
              v-model="editingLlmConfig!.provider"
              class="llm-config-input"
            >
              <option value="DEEPSEEK">DeepSeek</option>
              <option value="OPENAI">OpenAI</option>
              <option value="SILICONFLOW">SiliconFlow</option>
              <option value="CUSTOM">自定义</option>
            </select>
          </label>

          <label class="llm-config-field">
            <span>LLM Base URL</span>
            <input
              v-model="editingLlmConfig!.llmBaseUrl"
              type="text"
              class="llm-config-input"
              placeholder="如 https://api.deepseek.com"
            />
          </label>

          <label class="llm-config-field">
            <span>LLM API Key</span>
            <div class="llm-config-key-wrap">
              <input
                v-model="editingLlmConfig!.llmApiKey"
                :type="showLlmKey ? 'text' : 'password'"
                class="llm-config-input"
                placeholder="输入 API Key"
              />
              <button
                type="button"
                class="creator-ghost-button llm-config-eye-btn"
                @click="showLlmKey = !showLlmKey"
              >
                {{ showLlmKey ? '隐藏' : '显示' }}
              </button>
            </div>
          </label>

          <label class="llm-config-field">
            <span>LLM 模型名称</span>
            <input
              v-model="editingLlmConfig!.llmModelName"
              type="text"
              class="llm-config-input"
              placeholder="如 deepseek-chat"
            />
          </label>

          <label class="llm-config-field">
            <span>Embedding Base URL</span>
            <input
              v-model="editingLlmConfig!.embeddingBaseUrl"
              type="text"
              class="llm-config-input"
              placeholder="留空复用 LLM URL"
            />
          </label>

          <label class="llm-config-field">
            <span>Embedding API Key</span>
            <div class="llm-config-key-wrap">
              <input
                v-model="editingLlmConfig!.embeddingApiKey"
                :type="showEmbeddingKey ? 'text' : 'password'"
                class="llm-config-input"
                placeholder="留空复用 LLM Key"
              />
              <button
                type="button"
                class="creator-ghost-button llm-config-eye-btn"
                @click="showEmbeddingKey = !showEmbeddingKey"
              >
                {{ showEmbeddingKey ? '隐藏' : '显示' }}
              </button>
            </div>
          </label>

          <label class="llm-config-field">
            <span>Embedding 模型名称</span>
            <input
              v-model="editingLlmConfig!.embeddingModelName"
              type="text"
              class="llm-config-input"
              placeholder="如 text-embedding-3-small"
            />
          </label>

          <div class="llm-config-form-actions">
            <button
              type="button"
              class="creator-secondary-action creator-mini-button"
              @click="cancelEditConfig"
            >
              取消
            </button>
            <button
              type="button"
              class="creator-primary-button creator-mini-button"
              :disabled="llmConfigSaving === editingLlmConfig!.provider"
              @click="saveLlmConfigForm"
            >
              {{ llmConfigSaving === editingLlmConfig!.provider ? '保存中…' : '保存' }}
            </button>
          </div>
        </div>
      </div>

      <button
        v-if="!editingLlmConfig"
        type="button"
        class="creator-secondary-action"
        @click="startNewConfig"
      >
        + 添加新配置
      </button>
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

.llm-config-list {
  display: grid;
  gap: var(--s3);
  margin-bottom: var(--s3);
}

.llm-config-card {
  padding: var(--s3);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
}

.llm-config-card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--s3);
  margin-bottom: var(--s2);
}

.llm-config-card-head strong {
  color: var(--ink);
  font-size: 14px;
  font-weight: var(--fw-semibold);
}

.llm-config-card-actions {
  display: flex;
  gap: var(--s1);
}

.llm-config-test-result {
  margin-bottom: var(--s2);
  padding: var(--s1) var(--s2);
  border-radius: var(--r-sm);
  font-size: 13px;
}

.llm-config-test-result.success {
  color: var(--success, #1e8e3e);
  background: rgba(30, 142, 62, 0.08);
}

.llm-config-test-result.failed {
  color: var(--danger, #d93025);
  background: rgba(217, 48, 37, 0.06);
}

.llm-config-fields {
  display: grid;
  grid-template-columns: 110px 1fr;
  gap: var(--s1) var(--s2);
  margin: 0;
  font-size: 13px;
}

.llm-config-fields dt {
  color: var(--text-secondary, #666);
  font-weight: var(--fw-semibold);
  text-align: right;
  padding-top: 1px;
}

.llm-config-fields dd {
  margin: 0;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.llm-config-fields code {
  font-size: 12px;
  padding: 1px 6px;
  background: var(--surface-dim, #f0f4ff);
  border-radius: var(--r-sm);
  color: var(--accent, #1a73e8);
}

.llm-config-form {
  display: grid;
  gap: var(--s2);
}

.llm-config-form strong {
  color: var(--ink);
  font-size: 14px;
}

.llm-config-field {
  display: grid;
  gap: 4px;
}

.llm-config-field > span {
  font-size: 13px;
  color: var(--text-secondary, #666);
  font-weight: var(--fw-medium);
}

.llm-config-input {
  width: 100%;
  min-height: 34px;
  padding: 0 var(--s2);
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  font-size: 13px;
}

.llm-config-input:focus {
  outline: none;
  border-color: var(--accent, #1a73e8);
}

.llm-config-key-wrap {
  display: flex;
  gap: var(--s1);
}

.llm-config-key-wrap .llm-config-input {
  flex: 1;
}

.llm-config-eye-btn {
  flex-shrink: 0;
  font-size: 12px;
  padding: 0 var(--s2);
  min-height: 34px;
}

.llm-config-form-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--s2);
  margin-top: var(--s1);
}
</style>
