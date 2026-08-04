<script setup lang="ts">
/**
 * B站账号绑定面板 — P0-3 组件。
 * 在需要校验已发布视频的页面展示，让用户绑定 B 站 UID 以同步公开视频列表。
 * 已绑定时展示 UID、昵称和同步状态，未绑定时展示输入区。
 */
import { ref, onMounted } from 'vue'
import { getBilibiliAccount, bindBilibiliAccount, syncBilibiliVideos } from '@/api/creator'
import { ApiError } from '@/api/http'
import type { BilibiliAccount, SyncVideosResult } from '@/types/creator'

const emit = defineEmits<{
  'accountReady': [account: BilibiliAccount | null]
  'syncCompleted': [result: SyncVideosResult]
}>()

// 默认用户 ID — 和现有代码一致
const DEFAULT_USER_ID = 'default'

const loading = ref(false)
const saving = ref(false)
const syncing = ref(false)
const error = ref('')
const loadError = ref('')
const syncMessage = ref('')
const syncTone = ref<'neutral' | 'success' | 'warning' | 'error'>('neutral')
const syncResult = ref<SyncVideosResult | null>(null)
const account = ref<BilibiliAccount | null>(null)

// 输入
const uidInput = ref('')

// 状态标签
const statusLabels: Record<string, string> = {
  ACTIVE: '已绑定',
  UNVERIFIED: '未校验',
  SYNC_FAILED: '同步失败',
}

/** 重新读取账号状态，让昵称、最近同步时间和错误状态与后端保持一致。 */
async function loadAccount(showLoading = false) {
  if (showLoading) loading.value = true
  const previousAccount = account.value
  loadError.value = ''
  try {
    account.value = await getBilibiliAccount(DEFAULT_USER_ID)
  } catch (loadFailure) {
    if (loadFailure instanceof ApiError && loadFailure.status === 404) {
      account.value = null
    } else {
      // 网络或服务异常时保留上次成功结果，不能把故障伪装成账号未绑定。
      account.value = previousAccount
      loadError.value = loadFailure instanceof Error ? loadFailure.message : String(loadFailure)
    }
  } finally {
    if (showLoading) loading.value = false
    emit('accountReady', account.value)
  }
}

onMounted(() => loadAccount(true))

/** 绑定 B 站 UID */
async function handleBind() {
  if (!uidInput.value.trim()) return
  saving.value = true
  error.value = ''
  try {
    const result = await bindBilibiliAccount({
      userId: DEFAULT_USER_ID,
      bilibiliUid: uidInput.value.trim(),
    })
    account.value = result
    emit('accountReady', result)
  } catch (e: any) {
    error.value = e?.message || '绑定失败'
  } finally {
    saving.value = false
  }
}

/** 触发视频同步 */
async function handleSync() {
  syncing.value = true
  syncMessage.value = ''
  syncResult.value = null
  syncTone.value = 'neutral'
  try {
    const result = await syncBilibiliVideos(DEFAULT_USER_ID)
    syncResult.value = result
    syncTone.value = result.syncStatus === 'SUCCESS' ? 'success' : 'warning'
    const pageHint = result.hasMore ? '较早作品未展开，已绑定BV仍会单独校验。' : ''
    syncMessage.value = `${result.message}：读取 ${result.syncedCount} 条，校验通过 ${result.linkedCount} 条，异常 ${result.anomalyCount} 条。${pageHint}`
    await loadAccount()
    emit('syncCompleted', result)
  } catch (e: any) {
    syncMessage.value = e?.message || '同步失败'
    syncTone.value = 'error'
    await loadAccount()
  } finally {
    syncing.value = false
  }
}
</script>

<template>
  <div class="bilibili-account-panel">
    <div class="account-panel-header">
      <h3 class="account-panel-title">B站账号</h3>
      <span
        v-if="account"
        class="account-status-tag"
        :class="{
          'is-active': account.bindStatus === 'ACTIVE',
          'is-failed': account.bindStatus === 'SYNC_FAILED',
        }"
      >
        {{ statusLabels[account.bindStatus] || account.bindStatus }}
      </span>
    </div>

    <!-- 已绑定 -->
    <div v-if="account" class="account-info">
      <div class="account-info-row">
        <span class="account-info-label">UID</span>
        <span class="account-info-value">{{ account.bilibiliUid }}</span>
      </div>
      <div class="account-info-row">
        <span class="account-info-label">昵称</span>
        <span class="account-info-value">{{ account.nickname || '--' }}</span>
      </div>
      <div v-if="account.lastSyncTime" class="account-info-row">
        <span class="account-info-label">最近同步</span>
        <span class="account-info-value">{{ account.lastSyncTime }}</span>
      </div>
      <div class="account-actions">
        <button
          type="button"
          class="creator-btn creator-btn-secondary"
          :disabled="syncing"
          @click="handleSync"
        >
          {{ syncing ? '同步中...' : '同步视频' }}
        </button>
      </div>
      <p
        v-if="syncMessage"
        class="account-sync-message"
        :class="`is-${syncTone}`"
      >
        {{ syncMessage }}
      </p>
      <ul v-if="syncResult?.warnings.length" class="account-sync-warnings">
        <li v-for="warning in syncResult.warnings" :key="warning">{{ warning }}</li>
      </ul>
      <p
        v-else-if="account.lastSyncError && !syncMessage"
        class="account-sync-message"
        :class="account.bindStatus === 'SYNC_FAILED' ? 'is-error' : 'is-warning'"
      >
        {{ account.lastSyncError }}
      </p>
      <div v-if="loadError" class="account-load-error" role="alert">
        <span>账号状态刷新失败：{{ loadError }}</span>
        <button type="button" class="creator-btn creator-btn-secondary" @click="loadAccount(true)">
          重试
        </button>
      </div>
    </div>

    <div v-else-if="!loading && loadError" class="account-load-error" role="alert">
      <strong>暂时无法读取账号状态</strong>
      <span>{{ loadError }}</span>
      <button type="button" class="creator-btn creator-btn-secondary" @click="loadAccount(true)">
        重新读取
      </button>
    </div>

    <!-- 未绑定 -->
    <div v-else-if="!loading && !loadError" class="account-bind-form">
      <label class="account-bind-field" for="account-bind-uid">
        <span class="account-bind-label">B站 UID</span>
        <input
          id="account-bind-uid"
          v-model="uidInput"
          type="text"
          class="creator-input"
          placeholder="输入你的B站UID"
        />
      </label>
      <button
        type="button"
        class="creator-btn creator-btn-primary"
        :disabled="!uidInput.trim() || saving"
        @click="handleBind"
      >
        {{ saving ? '绑定中...' : '绑定' }}
      </button>
      <p v-if="error" class="account-error">{{ error }}</p>
    </div>

    <!-- 加载 -->
    <p v-else class="account-loading">检查账号绑定...</p>
  </div>
</template>

<style scoped>
.bilibili-account-panel {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  padding: 16px 20px;
  margin-bottom: 20px;
}

.account-panel-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.account-panel-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--creator-text, #1d1d1f);
}

.account-status-tag {
  font-size: 11px;
  font-weight: 500;
  padding: 2px 8px;
  border-radius: 100px;
  background: rgba(0, 0, 0, 0.06);
  color: var(--creator-muted-ink, #86868b);
}

.account-status-tag.is-active {
  background: rgba(52, 199, 89, 0.12);
  color: #248a3d;
}

.account-status-tag.is-failed {
  background: rgba(255, 59, 48, 0.1);
  color: #c93400;
}

.account-info {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.account-info-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.account-info-label {
  font-size: 13px;
  color: var(--creator-muted-ink, #86868b);
  min-width: 64px;
}

.account-info-value {
  font-size: 14px;
  color: var(--creator-text, #1d1d1f);
}

.account-actions {
  margin-top: 4px;
}

.account-sync-message {
  font-size: 13px;
  color: var(--creator-muted-ink, #86868b);
  margin: 4px 0 0;
  line-height: 1.5;
}

.account-sync-message.is-success {
  color: #248a3d;
}

.account-sync-message.is-warning {
  color: #9a5b00;
}

.account-sync-message.is-error {
  color: #c93400;
}

.account-sync-warnings {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.5;
  color: #9a5b00;
}

.account-bind-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.account-bind-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.account-bind-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--creator-text, #1d1d1f);
}

.account-error {
  font-size: 13px;
  color: #ff3b30;
  margin: 0;
}

.account-loading {
  font-size: 14px;
  color: var(--creator-muted-ink, #86868b);
  margin: 0;
}

.account-load-error {
  display: grid;
  justify-items: start;
  gap: 8px;
  margin-top: 8px;
  padding: 10px 12px;
  color: var(--danger);
  background: rgba(220, 38, 38, 0.06);
  border: 1px solid rgba(220, 38, 38, 0.16);
  border-radius: var(--r-sm);
  font-size: 13px;
  line-height: 1.5;
}
</style>
