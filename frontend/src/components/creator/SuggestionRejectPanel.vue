<script setup lang="ts">
import { ref } from 'vue'
import type { CreatorRejectReason } from '@/types/creator'

/**
 * 建议反馈的"不太好"展开面板。
 *
 * 为什么单独抽一个组件：拒绝原因的选择逻辑（单选 + 其他自定义文本）
 * 在标题建议、标签建议等场景完全一样，抽出来后 SuggestionCard 只负责"显示/隐藏"它。
 *
 * 预设原因用枚举值而非自由文本，是为了让后端画像更新能稳定聚类用户的排斥倾向
 * （自由文本无法聚合）。选"其他"时才允许补充自定义说明。
 */

// 预设原因选项：label 给用户看，value 提交给后端做聚合
const reasonOptions: ReadonlyArray<{ value: CreatorRejectReason; label: string }> = [
  { value: 'STYLE_MISMATCH', label: '风格不符合我的定位' },
  { value: 'LENGTH_AWKWARD', label: '太长或太短' },
  { value: 'TOO_CLICKBAIT', label: '太夸张 / 震惊体' },
  { value: 'NOT_ATTRACTIVE', label: '不够吸引人' },
  { value: 'OFF_TOPIC', label: '偏离视频主题' },
  { value: 'OTHER', label: '其他（请补充说明）' },
]

// 当前选中的原因，默认空表示未选
const selected = ref<CreatorRejectReason | ''>('')
// 选"其他"时的自定义文本
const customText = ref('')

const emit = defineEmits<{
  /** 用户提交拒绝：带上原因和（可选的）自定义说明 */
  submit: [reason: CreatorRejectReason, reasonText: string]
  /** 用户取消，关闭面板 */
  cancel: []
}>()

function handleSubmit() {
  if (!selected.value) return
  // 只有选"其他"时 customText 才有意义；其他原因把 label 作为说明，便于后端日志可读
  const reasonText =
    selected.value === 'OTHER' ? customText.value.trim() : labelOf(selected.value)
  emit('submit', selected.value, reasonText)
  // 提交后重置，下次打开是干净状态
  reset()
}

function handleCancel() {
  emit('cancel')
  reset()
}

function reset() {
  selected.value = ''
  customText.value = ''
}

function labelOf(value: CreatorRejectReason): string {
  return reasonOptions.find((item) => item.value === value)?.label ?? ''
}
</script>

<template>
  <div class="reject-panel" role="dialog" aria-label="建议反馈">
    <p class="reject-title">为什么觉得这条建议不好？</p>

    <div class="reject-options" role="radiogroup">
      <label
        v-for="option in reasonOptions"
        :key="option.value"
        class="reject-option"
        :class="{ active: selected === option.value }"
      >
        <input
          v-model="selected"
          type="radio"
          name="reject-reason"
          :value="option.value"
        />
        <span>{{ option.label }}</span>
      </label>
    </div>

    <!-- 仅当选择"其他"时展开自定义输入框，避免给用户多余的输入压力 -->
    <textarea
      v-if="selected === 'OTHER'"
      v-model="customText"
      class="reject-custom-input"
      placeholder="说说具体哪里不合适…"
      rows="2"
      maxlength="120"
    />

    <div class="reject-actions">
      <button
        type="button"
        class="creator-ghost-button creator-mini-button"
        @click="handleCancel"
      >
        取消
      </button>
      <button
        type="button"
        class="creator-primary-button creator-mini-button"
        :disabled="!selected || (selected === 'OTHER' && !customText.trim())"
        @click="handleSubmit"
      >
        提交
      </button>
    </div>
  </div>
</template>

<style scoped>
.reject-panel {
  display: grid;
  gap: var(--s2);
  margin-top: var(--s2);
  padding: var(--s3);
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: var(--r);
}

.reject-title {
  margin: 0;
  font-size: 13px;
  font-weight: var(--fw-semibold);
  color: var(--ink);
}

/* 原因选项竖排，每项可点整行选中，移动端友好 */
.reject-options {
  display: grid;
  gap: var(--s1);
}

.reject-option {
  display: flex;
  align-items: center;
  gap: var(--s2);
  padding: 7px var(--s3);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  cursor: pointer;
  font-size: 13px;
  color: var(--ink);
  transition: border-color 0.15s, background 0.15s;
}

.reject-option:hover {
  border-color: var(--accent-ring);
}

.reject-option.active {
  border-color: var(--accent);
  background: var(--accent-tint);
}

/* 隐藏原生 radio 圆点，用整行高亮表达选中态 */
.reject-option input {
  width: 16px;
  height: 16px;
  accent-color: var(--accent);
  margin: 0;
}

.reject-custom-input {
  width: 100%;
  padding: var(--s2) var(--s3);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  font-size: 13px;
  color: var(--ink);
  resize: vertical;
}

.reject-custom-input:focus-visible {
  outline: 2px solid var(--accent-ring);
  outline-offset: 1px;
}

.reject-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--s2);
}
</style>
