<script setup lang="ts">
/**
 * BV 绑定面板 — P0-3 核心组件。
 * 在观众反馈页和视频分析页展示，让用户把已发布视频的 BV 号关联到创作任务。
 * 绑定通过后才能读取反馈，并在视频分析页展示该视频卡片。
 */
import { computed, ref, onMounted } from 'vue'
import { getTaskVideoBinding, bindBvToTask } from '@/api/creator'
import type { TaskVideoBinding } from '@/types/creator'

const props = defineProps<{ taskId: string; bilibiliUid?: string | null }>()
const emit = defineEmits<{
  bound: [binding: TaskVideoBinding]
  bindingReady: [binding: TaskVideoBinding | null]
}>()

// 组件状态
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const binding = ref<TaskVideoBinding | null>(null)
const editing = ref(false)

// 表单输入
const uidInput = ref('')
const bvInput = ref('')
const bvError = ref('')
const effectiveUid = computed(() => props.bilibiliUid?.trim() || uidInput.value.trim())

// BV 格式校验：B 站 BV 号为 BV + 10 位字母数字
const BV_REGEX = /^BV[0-9A-Za-z]{10}$/

// 绑定状态的友好标签
const statusLabels: Record<string, string> = {
  WAITING_VERIFY: '等待校验',
  BOUND: '已绑定',
  UID_MISMATCH: 'UID不匹配',
  VIDEO_NOT_FOUND: '视频未找到',
}

// 组件挂载时检查是否已有绑定
onMounted(async () => {
  loading.value = true
  try {
    binding.value = await getTaskVideoBinding(props.taskId)
    // 上层页面已经绑定账号 UID 时优先沿用，避免用户重复填写造成归属不一致。
    uidInput.value = props.bilibiliUid || binding.value.bilibiliUid || ''
    bvInput.value = binding.value.bvid
    emit('bindingReady', binding.value)
  } catch {
    // 404 表示还没有绑定，正常流程，不报错
    binding.value = null
    emit('bindingReady', null)
  } finally {
    loading.value = false
  }
})

/** 用户点击"绑定 BV"按钮 */
async function handleBind() {
  // 前端 BV 格式校验 — 后端也会校验，但前端拦截能给即时反馈
  if (!BV_REGEX.test(bvInput.value.trim())) {
    bvError.value = 'BV号格式不正确，应为 BV + 10位字母数字，例如 BV1xx411c7mD'
    return
  }
  bvError.value = ''

  saving.value = true
  error.value = ''
  try {
    const result = await bindBvToTask(props.taskId, {
      userId: 'default', // 和现有代码一致，第一版统一使用 default 用户
      bilibiliUid: effectiveUid.value,
      bvid: bvInput.value.trim(),
    })
    binding.value = result
    editing.value = false
    emit('bound', result)
  } catch (e: any) {
    error.value = e?.message || '绑定失败，请稍后重试'
  } finally {
    saving.value = false
  }
}

/** 只有未通过校验的绑定允许编辑，BOUND 状态继续保持不可覆盖。 */
function startEdit() {
  if (!binding.value || binding.value.bindingStatus === 'BOUND') return
  uidInput.value = props.bilibiliUid || binding.value.bilibiliUid || ''
  bvInput.value = binding.value.bvid
  bvError.value = ''
  error.value = ''
  editing.value = true
}

/** 取消修正时恢复只读状态，避免误改原绑定展示。 */
function cancelEdit() {
  editing.value = false
  bvError.value = ''
  error.value = ''
}
</script>

<template>
  <div class="creator-bv-binding-panel">
    <h3 class="creator-section-title">绑定已发布视频</h3>
    <p class="creator-section-desc">
      发布后把视频的 BV 号填回来，完成归属校验后即可读取反馈并查看视频数据。
    </p>

    <!-- 已绑定状态 -->
    <div v-if="binding && !editing" class="bv-binding-status">
      <div class="bv-binding-status-row">
        <span class="bv-binding-label">BV 号</span>
        <code class="bv-binding-value">{{ binding.bvid }}</code>
      </div>
      <div class="bv-binding-status-row">
        <span class="bv-binding-label">状态</span>
        <span
          class="bv-binding-status-tag"
          :class="{ 'is-ok': binding.bindingStatus === 'BOUND', 'is-warn': binding.bindingStatus !== 'BOUND' }"
        >
          {{ statusLabels[binding.bindingStatus] || binding.bindingStatus }}
        </span>
      </div>
      <div v-if="binding.verifyMessage" class="bv-binding-status-row">
        <span class="bv-binding-label">说明</span>
        <span class="bv-binding-message">{{ binding.verifyMessage }}</span>
      </div>
      <button
        v-if="binding.bindingStatus !== 'BOUND'"
        type="button"
        class="creator-btn creator-btn-secondary"
        @click="startEdit"
      >
        修改绑定
      </button>
    </div>

    <!-- 绑定输入区：首次绑定或修正异常绑定时展示。 -->
    <div v-else-if="!loading" class="bv-binding-form">
      <p v-if="binding" class="bv-binding-edit-hint">
        当前绑定未通过校验，请修正 BV 号后重新提交。
      </p>
      <div v-if="bilibiliUid" class="bv-binding-status-row">
        <span class="bv-binding-label">B站 UID</span>
        <span class="bv-binding-message">{{ bilibiliUid }}</span>
      </div>
      <label v-else class="bv-binding-field" for="bv-binding-uid">
        <span class="bv-binding-field-label">B站 UID</span>
        <input
          id="bv-binding-uid"
          v-model="uidInput"
          type="text"
          class="creator-input"
          placeholder="你的B站UID，用于校验BV归属"
        />
      </label>
      <label class="bv-binding-field" for="bv-binding-bv">
        <span class="bv-binding-field-label">BV 号</span>
        <input
          id="bv-binding-bv"
          v-model="bvInput"
          type="text"
          class="creator-input"
          :class="{ 'has-error': bvError }"
          placeholder="视频BV号，例如 BV1xx411c7mD"
          @input="bvError = ''"
        />
        <span v-if="bvError" class="bv-binding-field-error">{{ bvError }}</span>
      </label>
      <button
        type="button"
        class="creator-btn creator-btn-primary"
        :disabled="!bvInput.trim() || !effectiveUid || saving"
        @click="handleBind"
      >
        {{ saving ? '绑定中...' : '绑定 BV' }}
      </button>
      <button
        v-if="binding"
        type="button"
        class="creator-btn creator-btn-secondary"
        :disabled="saving"
        @click="cancelEdit"
      >
        取消修改
      </button>
      <p v-if="error" class="bv-binding-error">{{ error }}</p>
    </div>

    <!-- 加载中 -->
    <p v-else class="bv-binding-loading">检查绑定状态...</p>
  </div>
</template>

<style scoped>
/* BV 绑定面板 — 复用全局 creator-* CSS 类，scoped 只定义面板特有样式 */
.creator-bv-binding-panel {
  background: var(--creator-panel);
  border: 1px solid var(--creator-line);
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 16px;
}

.creator-section-title {
  margin: 0 0 8px;
  font-size: 16px;
  font-weight: 600;
  color: var(--creator-text, #1d1d1f);
}

.creator-section-desc {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--creator-muted-ink, #86868b);
  line-height: 1.5;
}

/* 已绑定状态 */
.bv-binding-status {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.bv-binding-status-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.bv-binding-label {
  font-size: 13px;
  color: var(--creator-muted-ink, #86868b);
  min-width: 48px;
  flex-shrink: 0;
}

.bv-binding-value {
  font-family: 'SF Mono', 'Cascadia Code', monospace;
  font-size: 14px;
  color: var(--creator-accent, #0071e3);
  background: var(--creator-surface-sub, #f5f5f7);
  padding: 2px 8px;
  border-radius: 4px;
}

.bv-binding-status-tag {
  font-size: 12px;
  font-weight: 500;
  padding: 2px 10px;
  border-radius: 100px;
  background: rgba(0, 0, 0, 0.06);
  color: var(--creator-muted-ink, #86868b);
}

.bv-binding-status-tag.is-ok {
  background: rgba(52, 199, 89, 0.12);
  color: #248a3d;
}

.bv-binding-status-tag.is-warn {
  background: rgba(255, 149, 0, 0.12);
  color: #c93400;
}

.bv-binding-message {
  font-size: 13px;
  color: var(--creator-muted-ink, #86868b);
  line-height: 1.5;
}

/* 绑定表单 */
.bv-binding-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.bv-binding-edit-hint {
  margin: 0;
  font-size: 13px;
  color: var(--creator-muted-ink, #86868b);
  line-height: 1.5;
}

.bv-binding-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.bv-binding-field-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--creator-text, #1d1d1f);
}

/* .creator-input 通用输入框样式已移至全局 theme.css，此处只保留组件特有覆盖 */
.creator-input.has-error {
  border-color: #ff3b30;
}

.bv-binding-field-error {
  font-size: 12px;
  color: #ff3b30;
}

.bv-binding-error {
  font-size: 13px;
  color: #ff3b30;
  margin: 0;
}

.bv-binding-loading {
  font-size: 14px;
  color: var(--creator-muted-ink, #86868b);
}
</style>
