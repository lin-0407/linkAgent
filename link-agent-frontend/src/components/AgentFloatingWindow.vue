<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import ChatComposer from '@/components/ChatComposer.vue'
import ErrorNotice from '@/components/ErrorNotice.vue'
import MessageBubble from '@/components/MessageBubble.vue'
import { useAgentChat } from '@/composables/useAgentChat'
import type { SessionListItem } from '@/types/agent'

type WindowRect = {
  left: number
  top: number
  width: number
  height: number
}

type DragState = {
  pointerId: number
  offsetX: number
  offsetY: number
}

type ResizeState = {
  pointerId: number
  direction: ResizeDirection
  startX: number
  startY: number
  startLeft: number
  startTop: number
  startWidth: number
  startHeight: number
}

type ResizeDirection = 'n' | 'e' | 's' | 'w' | 'ne' | 'nw' | 'se' | 'sw'

const promptExamples = [
  '帮我用 ReAct 思路拆一下这个创作功能。',
  '结合当前工作台，给我一个排查后端接口问题的步骤。',
]
const capabilityTags = ['会话记忆', '工具调用', '推理轨迹']
const viewportMargin = 18
const minWindowWidth = 420
const minWindowHeight = 420
const maxWindowWidth = 920
const maxWindowHeight = 760
const resizeDirections: ResizeDirection[] = ['n', 'e', 's', 'w', 'ne', 'nw', 'sw']

const composerRef = ref<InstanceType<typeof ChatComposer> | null>(null)
// 默认只展示入口按钮，避免 AI 交互台一进入页面就遮挡创作工作流。
const isOpen = ref(false)
const isMinimized = ref(false)
const isDragging = ref(false)
const isResizing = ref(false)
const windowRect = ref<WindowRect>({
  left: 0,
  top: 0,
  width: 560,
  height: 640,
})

let dragState: DragState | null = null
let resizeState: ResizeState | null = null
let activePointerElement: HTMLElement | null = null
let activePointerId: number | null = null

const {
  activeSessionLabel,
  canSend,
  errorMessage,
  inputMessage,
  isLoading,
  isSessionsLoading,
  isSessionsOpen,
  latestStepCount,
  messageListRef,
  messages,
  openSession,
  sendMessage,
  sessionId,
  sessions,
  sessionsError,
  startNewSession,
} = useAgentChat()

const floatingWindowStyle = computed(() => ({
  left: `${windowRect.value.left}px`,
  top: `${windowRect.value.top}px`,
  width: `${windowRect.value.width}px`,
  height: isMinimized.value ? 'auto' : `${windowRect.value.height}px`,
}))

onMounted(() => {
  placeWindowAtDefaultPosition()
  window.addEventListener('resize', keepWindowInsideViewport)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', keepWindowInsideViewport)
  removePointerListeners()
})

function openFloatingWindow() {
  isOpen.value = true
  isMinimized.value = false
  keepWindowInsideViewport()
  focusComposer()
}

function closeFloatingWindow() {
  isOpen.value = false
  isSessionsOpen.value = false
}

function toggleMinimized() {
  isMinimized.value = !isMinimized.value
  keepWindowInsideViewport()
}

function startNewConversation() {
  startNewSession()
  isSessionsOpen.value = false
  focusComposer()
}

async function openAgentSession(session: SessionListItem) {
  await openSession(session)
  focusComposer()
}

function usePromptExample(example: string) {
  inputMessage.value = example
  focusComposer()
}

async function sendFloatingMessage() {
  await sendMessage()
  await nextTick()
  composerRef.value?.adjustInputHeight()
}

function startDrag(event: PointerEvent) {
  if (event.button !== 0 || shouldIgnorePointerDown(event.target)) {
    return
  }

  isDragging.value = true
  dragState = {
    pointerId: event.pointerId,
    offsetX: event.clientX - windowRect.value.left,
    offsetY: event.clientY - windowRect.value.top,
  }
  capturePointer(event)
  window.addEventListener('pointermove', handleDrag)
  window.addEventListener('pointerup', stopDrag)
  window.addEventListener('pointercancel', stopDrag)
  event.preventDefault()
}

function handleDrag(event: PointerEvent) {
  if (!dragState || event.pointerId !== dragState.pointerId) {
    return
  }

  windowRect.value = constrainRect({
    ...windowRect.value,
    left: event.clientX - dragState.offsetX,
    top: event.clientY - dragState.offsetY,
  })
}

function stopDrag(event?: PointerEvent) {
  if (event && dragState && event.pointerId !== dragState.pointerId) {
    return
  }

  isDragging.value = false
  dragState = null
  releasePointer()
  window.removeEventListener('pointermove', handleDrag)
  window.removeEventListener('pointerup', stopDrag)
  window.removeEventListener('pointercancel', stopDrag)
}

function startResize(event: PointerEvent, direction: ResizeDirection = 'se') {
  if (event.button !== 0) {
    return
  }

  isMinimized.value = false
  isResizing.value = true
  resizeState = {
    pointerId: event.pointerId,
    direction,
    startX: event.clientX,
    startY: event.clientY,
    startLeft: windowRect.value.left,
    startTop: windowRect.value.top,
    startWidth: windowRect.value.width,
    startHeight: windowRect.value.height,
  }
  capturePointer(event)
  window.addEventListener('pointermove', handleResize)
  window.addEventListener('pointerup', stopResize)
  window.addEventListener('pointercancel', stopResize)
  event.preventDefault()
}

function handleResize(event: PointerEvent) {
  if (!resizeState || event.pointerId !== resizeState.pointerId) {
    return
  }

  windowRect.value = constrainResizeRect(resizeState, event)
}

function stopResize(event?: PointerEvent) {
  if (event && resizeState && event.pointerId !== resizeState.pointerId) {
    return
  }

  isResizing.value = false
  resizeState = null
  releasePointer()
  window.removeEventListener('pointermove', handleResize)
  window.removeEventListener('pointerup', stopResize)
  window.removeEventListener('pointercancel', stopResize)
}

function placeWindowAtDefaultPosition() {
  const maxSize = getViewportBoundedSize()
  const width = clampNumber(560, minWindowWidth, maxSize.width)
  const height = clampNumber(640, minWindowHeight, maxSize.height)
  windowRect.value = constrainRect({
    left: window.innerWidth - width - 28,
    top: window.innerHeight - height - 28,
    width,
    height,
  })
}

function keepWindowInsideViewport() {
  windowRect.value = constrainRect(windowRect.value)
}

function constrainRect(rect: WindowRect): WindowRect {
  const maxSize = getViewportBoundedSize()
  const width = clampNumber(rect.width, minWindowWidth, maxSize.width)
  const height = clampNumber(rect.height, minWindowHeight, maxSize.height)
  const visibleHeight = isMinimized.value ? 66 : height
  const maxLeft = Math.max(viewportMargin, window.innerWidth - width - viewportMargin)
  const maxTop = Math.max(viewportMargin, window.innerHeight - visibleHeight - viewportMargin)

  return {
    left: clampNumber(rect.left, viewportMargin, maxLeft),
    top: clampNumber(rect.top, viewportMargin, maxTop),
    width,
    height,
  }
}

function constrainResizeRect(state: ResizeState, event: PointerEvent): WindowRect {
  const deltaX = event.clientX - state.startX
  const deltaY = event.clientY - state.startY
  const direction = state.direction
  const maxSize = getViewportBoundedSize()
  const right = state.startLeft + state.startWidth
  const bottom = state.startTop + state.startHeight

  let left = state.startLeft
  let top = state.startTop
  let width = state.startWidth
  let height = state.startHeight

  if (direction.includes('e')) {
    width = clampNumber(state.startWidth + deltaX, minWindowWidth, maxSize.width)
  }

  if (direction.includes('s')) {
    height = clampNumber(state.startHeight + deltaY, minWindowHeight, maxSize.height)
  }

  if (direction.includes('w')) {
    const nextLeft = clampNumber(
      state.startLeft + deltaX,
      viewportMargin,
      right - minWindowWidth,
    )
    left = nextLeft
    width = clampNumber(right - nextLeft, minWindowWidth, maxSize.width)
  }

  if (direction.includes('n')) {
    const nextTop = clampNumber(
      state.startTop + deltaY,
      viewportMargin,
      bottom - minWindowHeight,
    )
    top = nextTop
    height = clampNumber(bottom - nextTop, minWindowHeight, maxSize.height)
  }

  return constrainRect({
    left,
    top,
    width,
    height,
  })
}

function getViewportBoundedSize() {
  // 拖拽和缩放都依赖同一组上限，避免 CSS 限制尺寸但脚本仍把窗口拖出视口。
  return {
    width: Math.max(minWindowWidth, Math.min(maxWindowWidth, window.innerWidth - viewportMargin * 2)),
    height: Math.max(
      minWindowHeight,
      Math.min(maxWindowHeight, window.innerHeight - viewportMargin * 2),
    ),
  }
}

function clampNumber(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max)
}

function shouldIgnorePointerDown(target: EventTarget | null) {
  return target instanceof HTMLElement && Boolean(target.closest('button, textarea, input, select, a'))
}

function capturePointer(event: PointerEvent) {
  activePointerElement = event.currentTarget as HTMLElement
  activePointerId = event.pointerId
  activePointerElement.setPointerCapture(event.pointerId)
}

function releasePointer() {
  if (!activePointerElement) {
    activePointerId = null
    return
  }

  if (activePointerId !== null && activePointerElement.hasPointerCapture(activePointerId)) {
    activePointerElement.releasePointerCapture(activePointerId)
  }

  activePointerElement = null
  activePointerId = null
}

function removePointerListeners() {
  stopDrag()
  stopResize()
}

function focusComposer() {
  void nextTick(() => {
    composerRef.value?.adjustInputHeight()
    composerRef.value?.focusInput()
  })
}

function shortSessionId(value: string) {
  return value.length <= 12 ? value : `${value.slice(0, 6)}...${value.slice(-4)}`
}

function formatSessionPreview(value: string) {
  if (!value.trim()) {
    return '这个会话还没有摘要'
  }

  return value.length <= 34 ? value : `${value.slice(0, 34)}...`
}
</script>

<template>
  <div class="agent-floating-layer">
    <button
      v-if="!isOpen"
      type="button"
      class="agent-floating-launcher"
      aria-label="打开 AI 交互台浮窗"
      @click="openFloatingWindow"
    >
      <strong>AI</strong>
      <span>交互台</span>
    </button>

    <section
      v-else
      class="agent-floating-window"
      :class="{ minimized: isMinimized, dragging: isDragging, resizing: isResizing }"
      :style="floatingWindowStyle"
      aria-label="AI 交互台浮窗"
    >
      <header class="agent-floating-head" @pointerdown="startDrag">
        <div class="agent-floating-title">
          <span class="agent-floating-status" :class="{ running: isLoading }"></span>
          <div>
            <strong>AI 交互台</strong>
            <small>{{ isLoading ? 'Agent 正在思考' : activeSessionLabel }}</small>
          </div>
        </div>
        <div class="agent-floating-actions">
          <button type="button" title="切换会话" @click.stop="isSessionsOpen = !isSessionsOpen">
            会话
          </button>
          <button type="button" title="新建会话" @click.stop="startNewConversation">新建</button>
          <button
            type="button"
            :title="isMinimized ? '展开浮窗' : '最小化浮窗'"
            @click.stop="toggleMinimized"
          >
            {{ isMinimized ? '展开' : '收起' }}
          </button>
          <button type="button" title="关闭浮窗" aria-label="关闭 AI 交互台" @click.stop="closeFloatingWindow">
            ×
          </button>
        </div>
      </header>

      <div v-if="!isMinimized" class="agent-floating-body">
        <section v-if="isSessionsOpen" class="agent-floating-session-panel" aria-label="Agent 会话列表">
          <button v-if="isSessionsLoading" type="button" class="agent-floating-session-item muted" disabled>
            正在读取会话...
          </button>
          <template v-else>
            <button
              v-for="item in sessions"
              :key="item.sessionId"
              type="button"
              class="agent-floating-session-item"
              :class="{ active: item.sessionId === sessionId }"
              @click="openAgentSession(item)"
            >
              <strong>{{ shortSessionId(item.sessionId) }}</strong>
              <span>{{ formatSessionPreview(item.preview) }}</span>
              <small>{{ item.messageCount }} 条消息</small>
            </button>
            <p v-if="sessions.length === 0" class="agent-floating-session-empty">还没有保存的会话</p>
          </template>
          <p v-if="sessionsError" class="agent-floating-session-error">{{ sessionsError }}</p>
        </section>

        <div ref="messageListRef" class="message-list agent-floating-message-list">
          <div v-if="messages.length === 0" class="agent-floating-empty">
            <p>从这里直接调用通用 Agent，适合临时拆解问题、验证工具调用和查看推理轨迹。</p>
            <div class="agent-floating-tags">
              <span v-for="tag in capabilityTags" :key="tag">{{ tag }}</span>
            </div>
            <div class="agent-floating-prompts">
              <button
                v-for="example in promptExamples"
                :key="example"
                type="button"
                @click="usePromptExample(example)"
              >
                {{ example }}
              </button>
            </div>
          </div>

          <MessageBubble v-for="message in messages" :key="message.id" :message="message" />

          <div v-if="isLoading" class="message assistant">
            <div class="avatar">A</div>
            <div class="bubble loading animated" aria-label="Agent 正在思考">
              <span class="thinking-text">Agent 正在组织思路</span>
              <span class="thinking-dots" aria-hidden="true">
                <i></i>
                <i></i>
                <i></i>
              </span>
            </div>
          </div>
        </div>

        <div class="agent-floating-meta">
          <span>{{ messages.length }} 条消息</span>
          <span>{{ latestStepCount }} 个 ReAct 步骤</span>
          <span>{{ sessionId ? '当前会话' : '新会话' }}</span>
        </div>

        <ErrorNotice :error-message="errorMessage" />

        <ChatComposer
          ref="composerRef"
          v-model="inputMessage"
          :can-send="canSend"
          :is-loading="isLoading"
          @send-message="sendFloatingMessage"
        />
      </div>

      <button
        v-if="!isMinimized"
        type="button"
        class="agent-floating-resize agent-floating-resize-se"
        aria-label="拖动调整 AI 交互台大小"
        title="拖动调整大小"
        @pointerdown.stop="startResize($event, 'se')"
      ></button>
      <template v-if="!isMinimized">
        <button
          v-for="direction in resizeDirections"
          :key="direction"
          type="button"
          class="agent-floating-resize-edge"
          :class="`agent-floating-resize-${direction}`"
          aria-label="拖动调整 AI 交互台大小"
          title="拖动调整大小"
          @pointerdown.stop="startResize($event, direction)"
        ></button>
      </template>
    </section>
  </div>
</template>
