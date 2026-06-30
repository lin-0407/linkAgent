<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'

/**
 * 统一通知弹窗组件 —— 固定在视口右上角，替代原先分散在各处的内联提示。
 *
 * 为什么抽成独立组件：
 * 1. CreatorWorkspace 的错误横条和 AgentFloatingWindow 的 ErrorNotice 视觉位置不一致
 * 2. 没有统一的警告通知通道
 * 3. 成功提示已经是右上角弹窗模式，错误/警告应保持一致
 */

const props = withDefaults(
  defineProps<{
    /** 通知类型：success 成功 / warning 警告 / error 错误 */
    type: 'success' | 'warning' | 'error'
    /** 通知正文 */
    message: string
    /**
     * 自动消失时间（毫秒）。
     * - success 默认 2800ms（短暂操作反馈）
     * - warning 默认 5000ms（留足阅读时间但不需要手动关闭）
     * - error  默认 0（不自动消失，必须手动关闭）
     */
    duration?: number
    /** 自定义标题，不传则根据 type 自动生成中文标题 */
    title?: string
  }>(),
  {
    type: 'success',
    duration: undefined,
    title: undefined,
  },
)

const emit = defineEmits<{
  /** 通知关闭事件，父组件收到后应清空对应的 message 状态 */
  (e: 'close'): void
}>()

/** 根据类型自动生成中文标题，同时允许调用方覆盖 */
const resolvedTitle = props.title ?? titleByType(props.type)

function titleByType(t: string) {
  switch (t) {
    case 'success':
      return '操作完成'
    case 'warning':
      return '请注意'
    case 'error':
      return '请求失败'
    default:
      return '提示'
  }
}

/**
 * 解析实际使用的自动消失时长。
 * 为什么用 resolvedDuration 而不直接用 props.duration：
 * - props.duration 可能为 undefined（使用默认值），需要按类型兜底
 * - 0 表示不自动消失，需要特殊处理
 */
const resolvedDuration =
  props.duration ??
  (props.type === 'success' ? 2800 : props.type === 'warning' ? 5000 : 0)

let timer: ReturnType<typeof window.setTimeout> | undefined

/** 启动自动消失定时器 */
function startTimer() {
  clearTimer()
  // 为什么 duration 为 0 时跳过：表示该通知需要用户主动关闭，不应自动消失
  if (resolvedDuration <= 0) return
  timer = window.setTimeout(() => {
    emit('close')
  }, resolvedDuration)
}

/** 清理定时器，避免组件销毁后仍然触发 close 事件 */
function clearTimer() {
  if (timer === undefined) return
  window.clearTimeout(timer)
  timer = undefined
}

/** 手动关闭 */
function handleClose() {
  clearTimer()
  emit('close')
}

// 当 message 从空变为非空时自动启动定时器
watch(
  () => props.message,
  (val) => {
    if (val) startTimer()
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  clearTimer()
})
</script>

<template>
  <Transition name="notification-toast">
    <div
      v-if="message"
      class="notification-toast"
      :class="`notification-toast--${type}`"
      role="alert"
      :aria-live="type === 'error' ? 'assertive' : 'polite'"
    >
      <div class="notification-toast__body">
        <strong class="notification-toast__title">{{ resolvedTitle }}</strong>
        <span class="notification-toast__message">{{ message }}</span>
      </div>
      <button
        type="button"
        class="notification-toast__close"
        :aria-label="`关闭${resolvedTitle}`"
        @click="handleClose"
      >
        ×
      </button>
    </div>
  </Transition>
</template>

<style scoped>
/* ===== 容器：固定右上角，复用现有 toast 定位策略 ===== */
.notification-toast {
  position: fixed;
  top: 22px;
  right: 22px;
  z-index: 90;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  width: min(420px, calc(100vw - 44px));
  padding: 12px 12px 12px 14px;
  color: var(--text);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  box-shadow: var(--sh-md);
  backdrop-filter: blur(14px);
}

/* ===== 左侧色条：按类型区分语义，比全背景色更克制、更易扫描 ===== */
.notification-toast--success {
  border-left: 3px solid var(--ok);
}

.notification-toast--warning {
  border-left: 3px solid var(--warn);
}

.notification-toast--error {
  border-left: 3px solid var(--danger);
}

/* ===== 内容区 ===== */
.notification-toast__body {
  display: grid;
  flex: 1;
  gap: 2px;
  min-width: 0;
}

.notification-toast__title {
  font-size: 13px;
  font-weight: 650;
  line-height: 1.35;
}

.notification-toast__message {
  color: var(--muted);
  font-size: 14px;
  line-height: 1.55;
  /* 长错误信息自动折行 */
  word-break: break-word;
}

/* ===== 关闭按钮 ===== */
.notification-toast__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 32px;
  width: 32px;
  height: 32px;
  color: var(--muted);
  background: var(--surface-sub);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  cursor: pointer;
  font-size: 18px;
  font-weight: var(--fw-semibold);
  line-height: 1;
}

.notification-toast__close:hover {
  color: var(--ink);
  background: var(--surface);
}

/* ===== 进入/离开过渡 ===== */
.notification-toast-enter-active,
.notification-toast-leave-active {
  transition:
    opacity 180ms ease,
    transform 180ms ease;
}

.notification-toast-enter-from,
.notification-toast-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
