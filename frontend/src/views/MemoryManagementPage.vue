<script setup lang="ts">
/**
 * 长期记忆管理页面（P1-2）。
 * <p>
 * 提供长期记忆的浏览、搜索、排序和删除功能。
 * 系统从 Agent 对话中自动提取用户的长期偏好和关键信息存入 MySQL，
 * 本页面让用户可以查看和管理这些自动提取的记忆。
 * <p>
 * 为什么独立为页面而非嵌入设置面板？
 * 长期记忆可能数量较多（几十到上百条），需要搜索、排序、详情查看等
 * 完整管理能力，设置面板的侧边抽屉空间不足以承载这些交互。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { BrainCircuit, RefreshCw, RotateCcw, Search, Trash2, X } from '@lucide/vue'
import {
  deleteLongTermMemory,
  listLongTermMemories,
  restoreLongTermMemory,
} from '@/api/memory'
import { formatDate } from '@/composables/creator/creatorWorkspaceUtils'
import { useModalDialog } from '@/composables/useModalDialog'
import type { LongTermMemoryRecord } from '@/types/memory'

// ── 数据加载 ──

/** 默认用户标识（后续接入多用户时为动态值） */
const DEFAULT_USER_ID = 'default'

const memories = ref<LongTermMemoryRecord[]>([])
const loading = ref(false)
const loadError = ref('')

async function loadMemories() {
  loading.value = true
  loadError.value = ''
  try {
    memories.value = await listLongTermMemories(DEFAULT_USER_ID)
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : String(err)
  } finally {
    loading.value = false
  }
}

// ── 搜索与排序 ──

const searchQuery = ref('')
/** 排序方式：'newest' = 按更新时间倒序，'oldest' = 按更新时间正序 */
type SortMode = 'newest' | 'oldest'
const sortMode = ref<SortMode>('newest')

/** 过滤 + 排序后的记忆列表 */
const filteredMemories = computed(() => {
  let list = [...memories.value]

  // 搜索过滤：匹配 memoryKey 和 content 字段
  if (searchQuery.value.trim()) {
    const query = searchQuery.value.trim().toLowerCase()
    list = list.filter(
      (m) =>
        m.memoryKey.toLowerCase().includes(query) ||
        m.content.toLowerCase().includes(query),
    )
  }

  // 排序
  list.sort((a, b) => {
    const da = new Date(a.updateTime).getTime()
    const db = new Date(b.updateTime).getTime()
    return sortMode.value === 'newest' ? db - da : da - db
  })

  return list
})

// ── 记忆键的可读标签映射 ──
// 后端 LongTermMemory.normalizeMemoryKey() 将关键词归一到 5 个标准键，
// 这里提供对应的中文标签。未匹配的键直接展示原始值。

const KEY_LABEL_MAP: Record<string, string> = {
  'user.preference.example_language': '示例语言偏好',
  'user.preference.explanation_style': '解释风格偏好',
  'user.profile.summary': '用户画像摘要',
  'project.profile.summary': '项目画像摘要',
  'project.constraint.summary': '项目约束摘要',
}

function readableKey(memoryKey: string): string {
  return KEY_LABEL_MAP[memoryKey] ?? memoryKey
}

// ── 详情弹窗 ──

const detailTarget = ref<LongTermMemoryRecord | null>(null)

function openDetail(memory: LongTermMemoryRecord) {
  detailTarget.value = memory
}

function closeDetail() {
  detailTarget.value = null
}

const detailOpen = computed(() => detailTarget.value !== null)
const { dialogRef: detailDialogRef, handleDialogKeydown: handleDetailKeydown } = useModalDialog(
  detailOpen,
  closeDetail,
)

// ── 删除确认 ──

const deletingKey = ref<string | null>(null)
const deleteError = ref('')
const pendingDelete = ref<LongTermMemoryRecord | null>(null)

async function requestDelete(memory: LongTermMemoryRecord) {
  deleteError.value = ''
  if (detailTarget.value?.memoryKey === memory.memoryKey) {
    // 先关闭详情并回到原卡片，再打开确认框，避免两个模态层同时存在和争夺焦点。
    closeDetail()
    await nextTick()
  }
  pendingDelete.value = memory
}

function cancelDelete() {
  if (deletingKey.value) return
  pendingDelete.value = null
  deleteError.value = ''
}

const deleteConfirmOpen = computed(() => pendingDelete.value !== null)
const { dialogRef: deleteDialogRef, handleDialogKeydown: handleDeleteKeydown } = useModalDialog(
  deleteConfirmOpen,
  cancelDelete,
)

// ── 删除撤销 ──

const recentlyDeleted = ref<LongTermMemoryRecord | null>(null)
const restoringKey = ref<string | null>(null)
const undoError = ref('')
const undoToastRef = ref<HTMLElement | null>(null)
const undoActionRef = ref<HTMLButtonElement | null>(null)
let undoTimer: ReturnType<typeof window.setTimeout> | undefined

function clearUndoTimer() {
  if (undoTimer === undefined) return
  window.clearTimeout(undoTimer)
  undoTimer = undefined
}

function dismissUndo() {
  if (restoringKey.value) return
  clearUndoTimer()
  recentlyDeleted.value = null
  undoError.value = ''
}

function scheduleUndoDismissal() {
  clearUndoTimer()
  if (!recentlyDeleted.value || undoError.value || restoringKey.value) return

  const toast = undoToastRef.value
  // 用户正在读取或操作撤销提示时暂停计时，避免键盘和鼠标操作被自动关闭打断。
  if (toast?.matches(':hover') || toast?.contains(document.activeElement)) return

  undoTimer = window.setTimeout(() => {
    recentlyDeleted.value = null
    undoTimer = undefined
  }, 8_000)
}

function offerUndo(memory: LongTermMemoryRecord) {
  clearUndoTimer()
  recentlyDeleted.value = memory
  undoError.value = ''
  scheduleUndoDismissal()
  // 原删除按钮已随卡片移除，因此把焦点交给撤销入口，保证键盘用户不会回到文档起点。
  void nextTick(() => undoActionRef.value?.focus())
}

async function undoDelete() {
  const memory = recentlyDeleted.value
  if (!memory || restoringKey.value) return
  clearUndoTimer()
  restoringKey.value = memory.memoryKey
  undoError.value = ''
  try {
    const restored = await restoreLongTermMemory(DEFAULT_USER_ID, memory.memoryKey)
    // 使用后端返回的当前记录，避免把删除前快照覆盖到可能已更新的数据上。
    memories.value = [
      restored,
      ...memories.value.filter((item) => item.memoryKey !== restored.memoryKey),
    ]
    recentlyDeleted.value = null
  } catch (err) {
    undoError.value = err instanceof Error ? err.message : String(err)
  } finally {
    restoringKey.value = null
  }
}

async function confirmDelete() {
  const memory = pendingDelete.value
  const memoryKey = memory?.memoryKey
  if (!memoryKey || deletingKey.value) return
  deletingKey.value = memoryKey
  deleteError.value = ''
  try {
    await deleteLongTermMemory(DEFAULT_USER_ID, memoryKey)
    // 从本地列表中移除，避免重新请求
    memories.value = memories.value.filter((m) => m.memoryKey !== memoryKey)
    // 如果正在查看被删除记忆的详情，关闭弹窗
    if (detailTarget.value?.memoryKey === memoryKey) {
      closeDetail()
    }
    pendingDelete.value = null
    offerUndo(memory)
  } catch (err) {
    deleteError.value = err instanceof Error ? err.message : String(err)
  } finally {
    deletingKey.value = null
  }
}

// ── 生命周期 ──

onMounted(() => {
  loadMemories()
})

onBeforeUnmount(() => {
  clearUndoTimer()
})
</script>

<template>
  <main class="memory-page">
    <!-- 页面头部 -->
    <header class="memory-header">
      <div class="memory-header-left">
        <h1>记忆管理</h1>
        <p class="memory-subtitle">
          系统从您的对话中自动提取的长期偏好和关键信息
        </p>
      </div>
      <div class="memory-header-right">
        <span v-if="!loading" class="memory-count">
          共 <strong>{{ filteredMemories.length }}</strong> 条
        </span>
        <button
          type="button"
          class="creator-ghost-button memory-refresh-btn"
          :disabled="loading"
          @click="loadMemories"
        >
          <RefreshCw :size="16" :stroke-width="1.8" aria-hidden="true" />
          {{ loading ? '加载中…' : '刷新' }}
        </button>
      </div>
    </header>

    <!-- 工具栏：搜索 + 排序 -->
    <div class="memory-toolbar">
      <label class="memory-search-wrap">
        <span class="sr-only">搜索记忆</span>
        <Search
          class="memory-search-icon"
          :size="18"
          :stroke-width="1.8"
          aria-hidden="true"
        />
        <input
          v-model="searchQuery"
          type="text"
          class="memory-search-input"
          placeholder="搜索记忆内容或键名…"
        />
      </label>
      <select v-model="sortMode" class="memory-sort-select" aria-label="记忆排序方式">
        <option value="newest">最近更新</option>
        <option value="oldest">最早更新</option>
      </select>
    </div>

    <!-- 加载中 -->
    <div v-if="loading" class="memory-status">
      <p class="creator-muted">正在加载长期记忆…</p>
    </div>

    <!-- 加载失败 -->
    <div v-else-if="loadError" class="memory-status">
      <div class="creator-alert error-alert">
        <strong>加载失败</strong>
        <span>{{ loadError }}</span>
      </div>
      <button type="button" class="creator-ghost-button" @click="loadMemories">
        重试
      </button>
    </div>

    <!-- 空状态 -->
    <div v-else-if="memories.length === 0" class="memory-empty">
      <BrainCircuit class="memory-empty-icon" :size="40" :stroke-width="1.5" aria-hidden="true" />
      <h3>暂无长期记忆</h3>
      <p>完成几次对话后，系统会自动从对话中提取您的偏好和关键信息。</p>
    </div>

    <!-- 搜索无结果 -->
    <div v-else-if="filteredMemories.length === 0" class="memory-empty">
      <p>没有匹配「{{ searchQuery }}」的记忆，试试其他关键词。</p>
    </div>

    <!-- 记忆卡片网格 -->
    <div v-else class="memory-grid">
      <article
        v-for="memory in filteredMemories"
        :key="memory.memoryKey"
        class="memory-card"
        role="button"
        tabindex="0"
        @click="openDetail(memory)"
        @keydown.enter="openDetail(memory)"
        @keydown.space.prevent="openDetail(memory)"
      >
        <div class="memory-card-head">
          <span class="memory-card-key">{{ readableKey(memory.memoryKey) }}</span>
          <span class="memory-card-time">{{
            formatDate(memory.updateTime)
          }}</span>
        </div>
        <p class="memory-card-content">{{ memory.content }}</p>
        <div class="memory-card-foot">
          <small v-if="memory.sourceSessionId" class="memory-card-source">
            来源：{{ memory.sourceSessionId }}
          </small>
          <button
            type="button"
            class="memory-card-delete"
            :disabled="Boolean(deletingKey) || Boolean(recentlyDeleted)"
            :aria-label="`删除${readableKey(memory.memoryKey)}`"
            @click.stop="requestDelete(memory)"
          >
            <Trash2 :size="16" :stroke-width="1.8" aria-hidden="true" />
            删除
          </button>
        </div>
      </article>
    </div>

    <!-- 删除确认必须在请求发出前展示，避免卡片上的高频点击直接造成数据丢失。 -->
    <Teleport to="body">
      <Transition name="creator-modal">
        <div
          v-if="pendingDelete"
          class="creator-modal-backdrop"
          role="presentation"
          @click.self="cancelDelete"
        >
          <section
            ref="deleteDialogRef"
            class="creator-prompt-modal memory-delete-confirm-modal"
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="memory-delete-title"
            aria-describedby="memory-delete-description"
            tabindex="-1"
            @keydown="handleDeleteKeydown"
          >
            <header>
              <span>删除长期记忆</span>
              <h3 id="memory-delete-title">删除「{{ readableKey(pendingDelete.memoryKey) }}」？</h3>
            </header>
            <p id="memory-delete-description">
              删除后，后续对话将不再使用这条偏好或关键信息。请确认这是你要删除的内容。
            </p>
            <blockquote>{{ pendingDelete.content }}</blockquote>
            <p v-if="deleteError" class="creator-alert error-alert" role="alert">
              {{ deleteError }}
            </p>
            <div class="memory-delete-confirm-actions">
              <button
                type="button"
                class="creator-ghost-button"
                :disabled="Boolean(deletingKey)"
                data-dialog-initial-focus
                @click="cancelDelete"
              >
                取消
              </button>
              <button
                type="button"
                class="creator-danger-action"
                :disabled="Boolean(deletingKey)"
                @click="confirmDelete"
              >
                {{ deletingKey ? '删除中…' : '确认删除' }}
              </button>
            </div>
          </section>
        </div>
      </Transition>
    </Teleport>

    <!-- 删除请求成功后提供短时恢复入口；恢复失败时保留提示，允许用户重试。 -->
    <Teleport to="body">
      <Transition name="memory-undo-toast">
        <aside
          v-if="recentlyDeleted"
          ref="undoToastRef"
          class="memory-undo-toast"
          :class="{ 'is-error': undoError }"
          @mouseenter="clearUndoTimer"
          @mouseleave="scheduleUndoDismissal"
          @focusin="clearUndoTimer"
          @focusout="scheduleUndoDismissal"
        >
          <div
            class="memory-undo-copy"
            :role="undoError ? 'alert' : 'status'"
            :aria-live="undoError ? 'assertive' : 'polite'"
            aria-atomic="true"
          >
            <strong>
              {{ undoError ? '撤销失败' : `已删除「${readableKey(recentlyDeleted.memoryKey)}」` }}
            </strong>
            <span>{{ undoError || '可在提示关闭前撤销这次删除。' }}</span>
          </div>
          <div class="memory-undo-actions">
            <button
              ref="undoActionRef"
              type="button"
              class="memory-undo-action"
              :disabled="Boolean(restoringKey)"
              @click="undoDelete"
            >
              <RotateCcw :size="16" :stroke-width="1.8" aria-hidden="true" />
              {{ restoringKey ? '恢复中…' : undoError ? '重试' : '撤销' }}
            </button>
            <button
              type="button"
              class="memory-undo-dismiss"
              :disabled="Boolean(restoringKey)"
              aria-label="关闭撤销提示"
              title="关闭撤销提示"
              @click="dismissUndo"
            >
              <X :size="17" :stroke-width="1.8" aria-hidden="true" />
            </button>
          </div>
        </aside>
      </Transition>
    </Teleport>

    <!-- 详情弹窗 -->
    <Teleport to="body">
      <Transition name="creator-modal">
        <div
          v-if="detailTarget"
          class="creator-modal-backdrop"
          role="presentation"
          @click.self="closeDetail"
        >
          <section
            ref="detailDialogRef"
            class="creator-prompt-modal memory-detail-modal"
            role="dialog"
            aria-modal="true"
            aria-label="记忆详情"
            tabindex="-1"
            @keydown="handleDetailKeydown"
          >
            <header class="creator-result-modal-head">
              <h3>记忆详情</h3>
              <button
                type="button"
                class="creator-ghost-button"
                data-dialog-initial-focus
                @click="closeDetail"
              >
                关闭
              </button>
            </header>

            <dl class="memory-detail-list">
              <dt>记忆键</dt>
              <dd>
                <code>{{ detailTarget.memoryKey }}</code>
              </dd>

              <dt>标签</dt>
              <dd>{{ readableKey(detailTarget.memoryKey) }}</dd>

              <dt>内容</dt>
              <dd class="memory-detail-content">{{ detailTarget.content }}</dd>

              <dt v-if="detailTarget.sourceSessionId">来源会话</dt>
              <dd v-if="detailTarget.sourceSessionId">
                <code>{{ detailTarget.sourceSessionId }}</code>
              </dd>

              <dt>创建时间</dt>
              <dd>{{ formatDate(detailTarget.createTime) }}</dd>

              <dt>更新时间</dt>
              <dd>{{ formatDate(detailTarget.updateTime) }}</dd>
            </dl>

            <div class="memory-detail-actions">
              <button
                type="button"
                class="creator-secondary-action"
                :disabled="Boolean(deletingKey) || Boolean(recentlyDeleted)"
                @click="requestDelete(detailTarget)"
              >
                删除此记忆
              </button>
            </div>
          </section>
        </div>
      </Transition>
    </Teleport>
  </main>
</template>

<style scoped>
/**
 * 记忆管理页样式。
 * 复用全局的 creator-modal-* / creator-ghost-button / creator-alert 等类，
 * 这里只定义页面独有的布局、卡片、搜索栏和详情弹窗样式。
 */

.memory-page {
  max-width: 1280px;
  margin: 0 auto;
  padding: 22px var(--s4) 72px;
}

/* ── 头部 ── */

.memory-header {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s3);
  margin-bottom: var(--s4);
  padding-bottom: 14px;
  border-bottom: 1px solid var(--border);
}

.memory-header-left h1 {
  margin: 0;
  font-size: 24px;
  font-weight: var(--fw-bold);
  color: var(--ink);
}

.memory-subtitle {
  margin: var(--s1) 0 0;
  color: var(--text-secondary, #666);
  font-size: 14px;
}

.memory-header-right {
  display: flex;
  align-items: center;
  gap: var(--s3);
}

.memory-count {
  flex: none;
  font-size: 14px;
  color: var(--text-secondary, #666);
  white-space: nowrap;
}

.memory-count strong {
  color: var(--text);
}

.memory-refresh-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--s2);
  font-size: 13px;
}

/* ── 工具栏 ── */

.memory-toolbar {
  display: flex;
  gap: var(--s3);
  margin-bottom: var(--s4);
  align-items: center;
  padding: 12px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
}

.memory-search-wrap {
  flex: 1;
  position: relative;
  display: flex;
  align-items: center;
}

.memory-search-icon {
  position: absolute;
  left: var(--s3);
  width: 18px;
  height: 18px;
  color: var(--text-secondary, #999);
  pointer-events: none;
}

.memory-search-input {
  width: 100%;
  height: 40px;
  padding: 0 var(--s3) 0 40px;
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  font-size: 14px;
}

.memory-search-input:focus {
  outline: none;
  border-color: var(--accent, #1a73e8);
  box-shadow: 0 0 0 3px var(--accent-ring);
}

.memory-sort-select {
  height: 40px;
  padding: 0 var(--s3);
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  font-size: 14px;
  cursor: pointer;
  /* 给排序下拉框一个最小宽度，防止选项文字截断 */
  min-width: 120px;
}

/* ── 状态提示 ── */

.memory-status {
  text-align: center;
  padding: var(--s6) 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--s3);
}

/* ── 空状态 ── */

.memory-empty {
  text-align: center;
  padding: var(--s8) var(--s4);
}

.memory-empty-icon {
  margin: 0 auto var(--s3);
  color: var(--accent-strong);
}

.memory-empty h3 {
  margin: 0 0 var(--s2);
  font-size: 18px;
  color: var(--text);
}

.memory-empty p {
  margin: 0;
  color: var(--text-secondary, #666);
  font-size: 14px;
}

/* ── 卡片网格 ── */
/* 为什么用 CSS Grid 而非 flex？
 * Grid 能保证每行卡片等高，视觉上更整齐；且 3 列布局用 repeat(auto-fill) 实现
 * 响应式断行，容器缩窄时自动变为 2 列 / 1 列。 */

.memory-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--s3);
}

.memory-card {
  display: flex;
  flex-direction: column;
  padding: var(--s4);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  cursor: pointer;
  transition: background-color 0.15s ease, border-color 0.15s ease;
}

.memory-card:hover {
  border-color: var(--accent, #1a73e8);
  background: #f9fcfd;
}

.memory-card:focus-visible {
  outline: 3px solid var(--accent-ring);
  outline-offset: 2px;
}

.memory-card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--s3);
}

.memory-card-key {
  display: inline-block;
  min-width: 0;
  overflow-wrap: anywhere;
  padding: 2px var(--s2);
  background: var(--surface-dim, #f0f4ff);
  color: var(--accent, #1a73e8);
  border-radius: var(--r-sm);
  font-size: 12px;
  font-weight: var(--fw-semibold);
}

.memory-card-time {
  font-size: 12px;
  color: var(--text-secondary, #999);
  white-space: nowrap;
}

.memory-card-content {
  flex: 1;
  margin: 0;
  font-size: 14px;
  line-height: 1.6;
  color: var(--text);
  /* 超出 4 行省略 */
  display: -webkit-box;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.memory-card-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: var(--s3);
  padding-top: var(--s2);
  border-top: 1px solid var(--border);
}

.memory-card-source {
  color: var(--text-secondary, #999);
  font-size: 12px;
  /* 超长 sessionId 截断 */
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.memory-card-delete {
  display: inline-flex;
  min-width: 44px;
  min-height: 44px;
  align-items: center;
  justify-content: center;
  gap: 5px;
  padding: 0 var(--s2);
  background: transparent;
  color: var(--danger, #d93025);
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  font-size: 12px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.memory-card-delete:hover:not(:disabled) {
  background: var(--danger, #d93025);
  color: #fff;
}

.memory-card-delete:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* ── 删除确认 ── */

.memory-delete-confirm-modal {
  width: min(520px, 100%);
}

.memory-delete-confirm-modal header {
  display: grid;
  gap: 5px;
}

.memory-delete-confirm-modal header span {
  color: var(--danger);
  font-size: 12px;
  font-weight: var(--fw-semibold);
}

.memory-delete-confirm-modal h3,
.memory-delete-confirm-modal p,
.memory-delete-confirm-modal blockquote {
  margin: 0;
}

.memory-delete-confirm-modal h3 {
  min-width: 0;
  overflow-wrap: anywhere;
}

.memory-delete-confirm-modal blockquote {
  max-height: 120px;
  overflow-x: hidden;
  overflow-y: auto;
  overflow-wrap: anywhere;
  padding: var(--s3);
  color: var(--text);
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-left: 3px solid var(--danger);
  border-radius: var(--r-sm);
  font-size: 13px;
  line-height: 1.6;
}

.memory-delete-confirm-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--s2);
}

/* ── 删除撤销提示 ── */

.memory-undo-toast {
  position: fixed;
  right: 16px;
  bottom: 52px;
  z-index: 70;
  display: flex;
  width: min(460px, calc(100vw - 32px));
  align-items: center;
  justify-content: space-between;
  gap: var(--s3);
  padding: 12px;
  color: var(--text);
  background: var(--surface);
  border: 1px solid var(--border);
  border-left: 3px solid var(--accent);
  border-radius: var(--r-sm);
  box-shadow: var(--sh-sm);
}

.memory-undo-toast.is-error {
  border-left-color: var(--danger);
}

.memory-undo-copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.memory-undo-copy strong,
.memory-undo-copy span {
  overflow-wrap: anywhere;
}

.memory-undo-copy strong {
  font-size: 13px;
  line-height: 1.4;
}

.memory-undo-copy span {
  color: var(--muted);
  font-size: 13px;
  line-height: 1.45;
}

.memory-undo-actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: var(--s1);
}

.memory-undo-action,
.memory-undo-dismiss {
  display: inline-flex;
  min-height: 44px;
  align-items: center;
  justify-content: center;
  color: var(--accent-strong);
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  cursor: pointer;
}

.memory-undo-action {
  gap: 6px;
  padding: 0 var(--s2);
  font-size: 13px;
  font-weight: var(--fw-semibold);
}

.memory-undo-dismiss {
  width: 44px;
  padding: 0;
  color: var(--muted);
}

.memory-undo-action:hover:not(:disabled),
.memory-undo-dismiss:hover:not(:disabled) {
  background: var(--surface-sub);
  border-color: var(--border);
}

.memory-undo-action:focus-visible,
.memory-undo-dismiss:focus-visible {
  outline: 3px solid var(--accent-ring);
  outline-offset: 1px;
}

.memory-undo-action:disabled,
.memory-undo-dismiss:disabled {
  cursor: wait;
  opacity: 0.55;
}

.memory-undo-toast-enter-active,
.memory-undo-toast-leave-active {
  transition: opacity 180ms ease, transform 180ms ease;
}

.memory-undo-toast-enter-from,
.memory-undo-toast-leave-to {
  opacity: 0;
  transform: translateY(8px);
}

/* ── 详情弹窗 ── */

.memory-detail-modal {
  max-width: 640px;
  max-height: 85vh;
  overflow-y: auto;
}

.memory-detail-list {
  display: grid;
  grid-template-columns: 100px 1fr;
  gap: var(--s2) var(--s3);
  margin: 0 0 var(--s4);
}

.memory-detail-list dt {
  font-size: 13px;
  font-weight: var(--fw-semibold);
  color: var(--text-secondary, #666);
  text-align: right;
  padding-top: 2px;
}

.memory-detail-list dd {
  margin: 0;
  font-size: 14px;
  color: var(--text);
  word-break: break-word;
}

.memory-detail-list code {
  font-size: 12px;
  padding: 1px 6px;
  background: var(--surface-dim, #f0f4ff);
  border-radius: var(--r-sm);
  color: var(--accent, #1a73e8);
}

.memory-detail-content {
  line-height: 1.7;
  white-space: pre-wrap;
}

.memory-detail-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--s2);
}

/* ── 错误提示 ── */

.error-alert {
  display: flex;
  flex-direction: column;
  gap: var(--s1);
}

@media (max-width: 820px) {
  .memory-search-input,
  .memory-sort-select {
    height: 44px;
  }
}

@media (max-width: 640px) {
  .memory-undo-toast {
    right: 12px;
    bottom: 12px;
    width: calc(100vw - 24px);
  }

}

@media (prefers-reduced-motion: reduce) {
  .memory-undo-toast-enter-active,
  .memory-undo-toast-leave-active {
    transition: opacity 150ms ease;
  }

  .memory-undo-toast-enter-from,
  .memory-undo-toast-leave-to {
    transform: none;
  }
}
</style>
