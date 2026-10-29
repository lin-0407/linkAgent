<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { errorText } from '@/utils/error'
import { useAssistantBusinessRefresh } from '@/composables/useAssistantBusinessRefresh'
import { CircleCheck, CircleX } from '@lucide/vue'
import {
  deleteLlmConfig,
  activateTextLlmConfig,
  getCurrentTextModel,
  listLlmConfigs,
  saveLlmConfig,
  testLlmConnectivity,
  useEnvironmentTextModel,
} from '@/api/settings'
import type {
  UserLlmConfigRecord,
  CurrentTextModel,
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
useAssistantBusinessRefresh(['models.'], () => {
  if (props.loadOnOpen && !editingLlmConfig.value && !llmConfigBusy.value) return loadLlmConfigs()
})

const llmConfigs = ref<UserLlmConfigRecord[]>([])
const llmConfigLoading = ref(false)
const llmConfigError = ref('')
const llmConfigSaving = ref('')
const llmConfigTesting = ref('')
const llmConfigActivating = ref('')
const llmConfigNotice = ref('')
const currentTextModel = ref<CurrentTextModel | null>(null)
const testedConfigId = ref('')
const llmConfigBusy = computed(() => Boolean(llmConfigLoading.value || llmConfigSaving.value
  || llmConfigTesting.value || llmConfigActivating.value))

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
  if (shouldLoad && !editingLlmConfig.value && !llmConfigBusy.value) {
    void loadLlmConfigs()
  }
}, { immediate: true })

async function loadLlmConfigs() {
  llmConfigLoading.value = true
  llmConfigError.value = ''
  try {
    const [configs, current] = await Promise.all([listLlmConfigs(), getCurrentTextModel()])
    llmConfigs.value = configs
    currentTextModel.value = current
    return true
  } catch (e) {
    llmConfigError.value = errorText(e, '操作失败，请稍后重试')
    return false
  } finally {
    llmConfigLoading.value = false
  }
}

function startEditConfig(config: UserLlmConfigRecord) {
  if (llmConfigBusy.value) return
  llmConfigNotice.value = ''
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
  if (llmConfigBusy.value) return
  llmConfigNotice.value = ''
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
  if (!form || !form.provider || llmConfigBusy.value) return
  llmConfigSaving.value = form.provider
  llmConfigError.value = ''
  llmConfigNotice.value = ''
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
    const saved = await saveLlmConfig(payload)
    llmConfigs.value = [...llmConfigs.value.filter(config => config.configId !== saved.configId), saved]
    if (saved.activeForText) currentTextModel.value = null
    cancelEditConfig()
    await loadLlmConfigs()
    llmConfigNotice.value = saved.activeForText
      ? '已保存当前文本模型的参数，后续调用使用更新后的配置。'
      : '配置已保存。点击“设为当前文本模型”后切换后续文本调用。'
  } catch (e) {
    llmConfigError.value = errorText(e, '操作失败，请稍后重试')
  } finally {
    llmConfigSaving.value = ''
  }
}

async function removeLlmConfig(config: UserLlmConfigRecord) {
  if (llmConfigBusy.value) return
  const { configId, provider } = config
  const fallback = config.activeForText ? '删除后，后续文本调用将使用环境配置。' : ''
  if (!confirm(`确定要删除「${PROVIDER_LABELS[provider] ?? provider}」的 API 配置吗？${fallback}`)) return
  llmConfigSaving.value = provider
  llmConfigError.value = ''
  llmConfigNotice.value = ''
  try {
    await deleteLlmConfig(configId)
    llmConfigs.value = llmConfigs.value.filter(item => item.configId !== configId)
    if (config.activeForText) currentTextModel.value = null
    await loadLlmConfigs()
    llmConfigNotice.value = config.activeForText ? '已删除配置，当前文本模型已恢复为环境配置。' : '配置已删除。'
  } catch (e) {
    llmConfigError.value = errorText(e, '操作失败，请稍后重试')
  } finally {
    llmConfigSaving.value = ''
  }
}

async function activateConfig(config: UserLlmConfigRecord) {
  if (llmConfigBusy.value || editingLlmConfig.value || config.activeForText || !config.llmApiKeyMasked) return
  llmConfigActivating.value = config.configId
  llmConfigError.value = ''
  llmConfigNotice.value = ''
  try {
    const active = await activateTextLlmConfig(config.configId)
    llmConfigs.value = llmConfigs.value.map(item => item.configId === active.configId
      ? active : { ...item, activeForText: false })
    currentTextModel.value = null
    if (await loadLlmConfigs()) llmConfigNotice.value = '当前文本模型已切换，后续文本调用使用所选配置。'
  } catch (e) {
    llmConfigError.value = errorText(e, '切换文本模型失败')
  } finally {
    llmConfigActivating.value = ''
  }
}

async function selectEnvironmentModel() {
  if (llmConfigBusy.value || editingLlmConfig.value || currentTextModel.value?.source !== 'SAVED_CONFIG') return
  llmConfigActivating.value = 'environment'
  llmConfigError.value = ''
  llmConfigNotice.value = ''
  try {
    await useEnvironmentTextModel()
    llmConfigs.value = llmConfigs.value.map(item => ({ ...item, activeForText: false }))
    currentTextModel.value = null
    if (await loadLlmConfigs()) llmConfigNotice.value = '当前文本模型已恢复为环境配置，已保存配置均保留。'
  } catch (e) {
    llmConfigError.value = errorText(e, '恢复环境配置失败')
  } finally {
    llmConfigActivating.value = ''
  }
}

async function testConfigConnectivity(configId: string) {
  if (llmConfigBusy.value) return
  llmConfigTesting.value = configId
  llmConfigError.value = ''
  llmTestResult.value = null
  testedConfigId.value = configId
  try {
    llmTestResult.value = await testLlmConnectivity(configId)
  } catch (e) {
    llmConfigError.value = errorText(e, '操作失败，请稍后重试')
  } finally {
    llmConfigTesting.value = ''
  }
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
        <span class="settings-section-title">API 配置</span>
        <span class="settings-section-hint" v-if="collapsed && llmConfigs.length">
          {{ llmConfigs.length }} 个供应商
        </span>
      </button>
      <button
        type="button"
        class="creator-secondary-action creator-mini-button"
        :disabled="llmConfigBusy"
        @click.stop="loadLlmConfigs"
      >
        {{ llmConfigLoading ? '加载中…' : llmConfigs.length ? '刷新' : '加载配置' }}
      </button>
    </div>

    <div v-if="!collapsed" class="settings-section-body">
      <div v-if="llmConfigError" class="creator-alert error-alert">
        <strong>操作失败</strong>
        <span>{{ llmConfigError }}</span>
      </div>

      <div class="llm-current-selection" aria-live="polite">
        <template v-if="currentTextModel">
          <strong>当前文本模型：{{ currentTextModel.source === 'ENVIRONMENT'
            ? '环境配置' : PROVIDER_LABELS[currentTextModel.provider ?? ''] ?? currentTextModel.provider ?? '已保存配置' }}</strong>
          <dl class="llm-config-fields">
            <dt>模型</dt><dd>{{ currentTextModel.modelName }}</dd>
            <dt>端点</dt><dd :title="currentTextModel.baseUrl">{{ currentTextModel.baseUrl }}</dd>
          </dl>
          <button
            v-if="currentTextModel.source === 'SAVED_CONFIG'"
            type="button"
            class="creator-secondary-action creator-mini-button"
            :disabled="llmConfigBusy || !!editingLlmConfig"
            @click="selectEnvironmentModel"
          >使用环境配置</button>
        </template>
        <span v-else class="creator-muted">{{ llmConfigLoading ? '正在读取当前文本模型…' : '当前文本模型信息未加载，请刷新。' }}</span>
      </div>
      <p class="creator-muted">保存不会自动切换当前文本模型。Embedding 仍使用部署配置。</p>
      <p v-if="llmConfigNotice" class="creator-muted" role="status">{{ llmConfigNotice }}</p>

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
                  :disabled="llmConfigBusy || !!editingLlmConfig || cfg.activeForText || !cfg.llmApiKeyMasked"
                  :title="!cfg.llmApiKeyMasked ? '请先保存 LLM API Key；仅有 Embedding 配置不能设为文本模型' : undefined"
                  @click="activateConfig(cfg)"
                >{{ cfg.activeForText ? '当前文本模型' : llmConfigActivating === cfg.configId ? '切换中…' : '设为当前文本模型' }}</button>
                <button
                  type="button"
                  class="creator-secondary-action creator-mini-button"
                  :disabled="llmConfigBusy || !!editingLlmConfig"
                  @click="testConfigConnectivity(cfg.configId)"
                >
                  {{ llmConfigTesting === cfg.configId ? '测试中…' : '测试连接' }}
                </button>
                <button
                  type="button"
                  class="creator-secondary-action creator-mini-button"
                  :disabled="llmConfigBusy || !!editingLlmConfig"
                  @click="startEditConfig(cfg)"
                >
                  编辑
                </button>
                <button
                  type="button"
                  class="creator-secondary-action creator-mini-button"
                  :disabled="llmConfigBusy || !!editingLlmConfig"
                  @click="removeLlmConfig(cfg)"
                >
                  删除
                </button>
              </div>
            </div>

            <div
              v-if="llmTestResult && testedConfigId === cfg.configId && llmConfigTesting !== cfg.configId"
              class="llm-config-test-result"
              :class="llmTestResult.success ? 'success' : 'failed'"
            >
              <span v-if="llmTestResult.success">
                <CircleCheck :size="15" :stroke-width="1.8" aria-hidden="true" />
                连接成功（{{ llmTestResult.elapsedMs }}ms）
              </span>
              <span v-else>
                <CircleX :size="15" :stroke-width="1.8" aria-hidden="true" />
                连接失败：{{ llmTestResult.error }}
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
            <p v-if="cfg.activeForText" class="creator-muted">当前文本模型。保存后，后续调用使用更新后的参数。</p>

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
                :disabled="llmConfigBusy"
                @click="cancelEditConfig"
              >
                取消
              </button>
              <button
                type="button"
                class="creator-primary-button creator-mini-button"
                :disabled="llmConfigBusy"
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
              :disabled="llmConfigBusy"
              @click="cancelEditConfig"
            >
              取消
            </button>
            <button
              type="button"
              class="creator-primary-button creator-mini-button"
              :disabled="llmConfigBusy"
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
        :disabled="llmConfigBusy"
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

.settings-section-heading > .creator-secondary-action {
  width: auto;
  flex: 0 0 auto;
}

.settings-section-title {
  flex-shrink: 0;
  white-space: nowrap;
}

.settings-section-toggle h3 {
  margin: 0;
  font-size: var(--fs-base);
  font-weight: var(--fw-semibold);
  flex: 0 0 auto;
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

.llm-config-list {
  display: grid;
  gap: var(--s3);
  margin-bottom: var(--s3);
}

.llm-current-selection {
  display: grid;
  gap: var(--s2);
  padding: var(--s3);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
}

.llm-current-selection > button {
  justify-self: start;
}

.llm-current-selection .llm-config-fields {
  grid-template-columns: 65px minmax(0, 1fr);
}

.llm-current-selection .llm-config-fields dd {
  white-space: normal;
  overflow-wrap: anywhere;
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
  flex-wrap: wrap;
}

.llm-config-card-head strong {
  color: var(--ink);
  font-size: var(--fs-base);
  font-weight: var(--fw-semibold);
}

.llm-config-card-actions {
  display: flex;
  gap: var(--s1);
  flex-wrap: wrap;
}

.llm-config-test-result {
  margin-bottom: var(--s2);
  padding: var(--s1) var(--s2);
  border-radius: var(--r-sm);
  font-size: var(--fs-sm);
}

.llm-config-test-result span {
  display: inline-flex;
  align-items: center;
  gap: var(--s1);
}

.llm-config-test-result.success {
  color: var(--success, #1e8e3e);
  background: rgba(30, 142, 62, 0.08);
}

.llm-config-test-result.failed {
  color: var(--danger);
  background: rgba(217, 48, 37, 0.06);
}

.llm-config-fields {
  display: grid;
  grid-template-columns: 110px 1fr;
  gap: var(--s1) var(--s2);
  margin: 0;
  font-size: var(--fs-sm);
}

.llm-config-fields dt {
  color: var(--text-secondary, var(--muted));
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
  font-size: var(--fs-xs);
  padding: 1px 6px;
  background: var(--surface-dim, #f0f4ff);
  border-radius: var(--r-sm);
  color: var(--accent);
}

.llm-config-form {
  display: grid;
  gap: var(--s2);
}

.llm-config-form strong {
  color: var(--ink);
  font-size: var(--fs-base);
}

.llm-config-field {
  display: grid;
  gap: 4px;
}

.llm-config-field > span {
  font-size: var(--fs-sm);
  color: var(--text-secondary, var(--muted));
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
  font-size: var(--fs-sm);
}

.llm-config-input:focus {
  outline: none;
  border-color: var(--accent);
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
  font-size: var(--fs-xs);
  padding: 0 var(--s2);
  min-height: 34px;
}

.llm-config-form-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--s2);
  margin-top: var(--s1);
}

@media (max-width: 480px) {
  .llm-config-fields {
    grid-template-columns: 85px minmax(0, 1fr);
  }
}
</style>
