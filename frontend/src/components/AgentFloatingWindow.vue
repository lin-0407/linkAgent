<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import ChatComposer from '@/components/ChatComposer.vue'
import NotificationToast from '@/components/NotificationToast.vue'
import MessageBubble from '@/components/MessageBubble.vue'
import { useAgentChat } from '@/composables/useAgentChat'
import type { SessionListItem } from '@/types/agent'
import type {
  ReferenceVideo,
  ReferenceVideoAnalysisContext,
  ReferenceVideoEvidenceItem,
  ReferenceVideoMatchedTopic,
} from '@/types/knowledge'
import {
  KNOWLEDGE_VIDEO_CONTEXT_EVENT,
  type KnowledgeVideoContextEventDetail,
} from '@/utils/agentContext'
import type { AgentExecutionMode } from '@/types/agent'

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

const props = withDefaults(
  defineProps<{
    developerMode?: boolean
  }>(),
  {
    developerMode: false,
  },
)

const promptExamples = [
  '这个标题哪里弱？',
  '观众可能会误解什么？',
  '下一期选题怎么延展？',
]
const capabilityTags = ['标题建议', '观众误解', '下一期选题']
const executionModeOptions: Array<{
  value: AgentExecutionMode
  label: string
  description: string
}> = [
  { value: 'AUTO', label: 'Auto', description: '自动选择' },
  { value: 'REACT', label: 'ReAct', description: '边想边做' },
  { value: 'PLAN_EXECUTE', label: 'PaE', description: '先计划后执行' },
  { value: 'MULTI_AGENT', label: 'Multi', description: '多 Agent 协作' },
]
const viewportMargin = 18
const minWindowWidth = 520
const minWindowHeight = 420
const maxWindowWidth = 1040
const maxWindowHeight = 760
const resizeDirections: ResizeDirection[] = ['n', 'e', 's', 'w', 'ne', 'nw', 'sw']

const composerRef = ref<InstanceType<typeof ChatComposer> | null>(null)
// 默认只展示入口按钮，避免 AI 交互台一进入页面就遮挡创作工作流。
const isOpen = ref(false)
const isMinimized = ref(false)
const isDragging = ref(false)
const isResizing = ref(false)
const knowledgeContext = ref<ReferenceVideoAnalysisContext | null>(null)
const knowledgeContextQuery = ref('')
const windowRect = ref<WindowRect>({
  left: 0,
  top: 0,
  width: 720,
  height: 660,
})

let dragState: DragState | null = null
let resizeState: ResizeState | null = null
let activePointerElement: HTMLElement | null = null
let activePointerId: number | null = null

const {
  activeSessionLabel,
  canSend,
  errorMessage,
  executionMode,
  inputMessage,
  isLoading,
  isStreaming,
  streamingContent,
  streamingSteps,
  isSessionsLoading,
  isSessionsOpen,
  latestStepCount,
  messageListRef,
  messages,
  openSession,
  sendMessage,
  stopStreaming,
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

const floatingWindowSubtitle = computed(() => {
  const title = knowledgeContext.value?.video.title
  if (title) {
    return clipText(title, 26)
  }
  if (isStreaming.value) {
    const stepCount = streamingSteps.value.length
    return stepCount > 0 ? `AI 正在思考...（已执行 ${stepCount} 步）` : 'AI 正在生成回复...'
  }
  if (isLoading.value) {
    return 'AI 正在整理建议'
  }
  return props.developerMode ? activeSessionLabel.value : '围绕当前视频追问'
})

const visibleKnowledgeTopics = computed(() => knowledgeContext.value?.topics.slice(0, 4) ?? [])
const visibleKnowledgeEvidence = computed(() => knowledgeContext.value?.evidenceItems.slice(0, 5) ?? [])

onMounted(() => {
  placeWindowAtDefaultPosition()
  window.addEventListener('resize', keepWindowInsideViewport)
  window.addEventListener(KNOWLEDGE_VIDEO_CONTEXT_EVENT, handleKnowledgeVideoContext)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', keepWindowInsideViewport)
  window.removeEventListener(KNOWLEDGE_VIDEO_CONTEXT_EVENT, handleKnowledgeVideoContext)
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
  knowledgeContext.value = null
  knowledgeContextQuery.value = ''
  isSessionsOpen.value = false
  focusComposer()
}

async function openAgentSession(session: SessionListItem) {
  knowledgeContext.value = null
  knowledgeContextQuery.value = ''
  await openSession(session)
  focusComposer()
}

function usePromptExample(example: string) {
  inputMessage.value = example
  focusComposer()
}

async function sendFloatingMessage() {
  const displayMessage = inputMessage.value.trim()
  await sendMessage({
    displayMessage,
    outboundMessage: buildKnowledgeContextMessage(displayMessage),
  })
  await nextTick()
  composerRef.value?.adjustInputHeight()
}

function handleKnowledgeVideoContext(event: Event) {
  const detail = (event as CustomEvent<KnowledgeVideoContextEventDetail>).detail
  if (!detail?.context?.video) {
    return
  }

  // 选中一张视频卡片时新开会话，避免不同视频的评论弹幕混在同一轮上下文里。
  startNewSession()
  knowledgeContext.value = detail.context
  knowledgeContextQuery.value = detail.query.trim()
  inputMessage.value = '基于这个视频的主题和观众反馈，分析它值得参考的点，并给我 3 条可直接改到我新视频里的建议。'
  openFloatingWindow()
}

function buildKnowledgeContextMessage(userMessage: string) {
  const context = knowledgeContext.value
  if (!context) {
    return userMessage
  }

  const video = context.video
  const topicLines = context.topics.slice(0, 8).map(formatTopicForPrompt)
  const evidenceLines = context.evidenceItems.slice(0, 18).map(formatEvidenceForPrompt)

  // 每次追问都带上当前视频上下文，因为普通 Agent 会话本身不知道用户刚点击了哪张案例卡片。
  return [
    '你是面向 B 站 UP 主的创作案例分析助手。',
    '请优先依据下面的视频案例上下文回答；如果证据不足，请明确指出不足，不要编造评论或弹幕。',
    `用户原问题：${userMessage}`,
    '',
    '【已选视频】',
    `标题：${video.title}`,
    `BV：${video.bvId || '无'}；分区：${video.category || '未标注'}；层级：${tierLabel(video.tier)}；质量分：${formatKnowledgeQuality(video)}`,
    `数据：播放 ${formatReferenceCount(video.viewCount)}，点赞 ${formatReferenceCount(video.likeCount)}，投币 ${formatReferenceCount(video.coinCount)}，收藏 ${formatReferenceCount(video.favoriteCount)}，弹幕 ${formatReferenceCount(video.danmakuCount)}，评论 ${formatReferenceCount(video.replyCount)}`,
    `摘要：${clipText(video.highlightSummary || video.description || '暂无摘要', 520)}`,
    '',
    '【主题中块】',
    topicLines.length ? topicLines.join('\n') : '暂无主题中块。',
    '',
    '【评论弹幕证据】',
    evidenceLines.length ? evidenceLines.join('\n') : '暂无评论弹幕证据。',
  ].join('\n')
}

function formatKnowledgeQuality(video: ReferenceVideo) {
  if (video.qualityScoreReliable && video.qualityScore !== null) {
    return String(video.qualityScore)
  }
  if (video.rawQualityScore !== null) {
    return `样本不足（同分区有效样本 ${video.qualitySampleCount} 条）`
  }
  return '无'
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
  const width = clampNumber(720, minWindowWidth, maxSize.width)
  const height = clampNumber(660, minWindowHeight, maxSize.height)
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
  const availableWidth = Math.max(320, window.innerWidth - viewportMargin * 2)
  const availableHeight = Math.max(minWindowHeight, window.innerHeight - viewportMargin * 2)
  return {
    width: Math.min(maxWindowWidth, availableWidth),
    height: Math.min(maxWindowHeight, availableHeight),
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

function formatTopicForPrompt(topic: ReferenceVideoMatchedTopic, index: number) {
  return `${index + 1}. ${chunkTypeLabel(topic.chunkType)}｜${topic.chunkTitle}：${clipText(topic.preview, 420)}`
}

function formatEvidenceForPrompt(item: ReferenceVideoEvidenceItem, index: number) {
  return `${index + 1}. ${sourceTypeLabel(item.sourceType)}｜${sentimentLabel(item.sentiment)}：${clipText(item.content, 240)}`
}

function chunkTypeLabel(chunkType: string) {
  switch (chunkType) {
    case 'TITLE_PACKAGE':
      return '标题包装'
    case 'CONTENT_POSITIONING':
      return '内容定位'
    case 'AUDIENCE_FEEDBACK_SUMMARY':
      return '观众反馈'
    default:
      return chunkType || '主题'
  }
}

function sentimentLabel(sentiment: string) {
  switch (sentiment) {
    case 'POSITIVE':
      return '正向'
    case 'NEGATIVE':
      return '负向'
    default:
      return sentiment || '中性'
  }
}

function sourceTypeLabel(sourceType: string) {
  return sourceType === 'DANMAKU' ? '弹幕' : '评论'
}

function modeButtonTitle(option: { label: string; description: string }) {
  return `${option.label}：${option.description}`
}

function tierLabel(value: string) {
  switch (value) {
    case 'BENCHMARK':
      return '标杆案例'
    case 'COMPETITOR':
      return '竞品案例'
    case 'OWN_HISTORY':
      return '自己历史'
    default:
      return value || '未标注'
  }
}

function formatReferenceCount(value: number | null) {
  if (value === null || value === undefined) {
    return '无'
  }
  if (value >= 10000) {
    return `${(value / 10000).toFixed(1)}万`
  }
  return String(value)
}

function clipText(value: string, maxLength: number) {
  const text = value.trim()
  if (text.length <= maxLength) {
    return text
  }
  return `${text.slice(0, maxLength)}...`
}
</script>

<template>
  <div class="agent-floating-layer">
    <button
      v-if="!isOpen"
      type="button"
      class="agent-floating-launcher"
      aria-label="打开问问 AI 浮窗"
      @click="openFloatingWindow"
    >
      <strong>AI</strong>
      <span>问问</span>
    </button>

    <section
      v-else
      class="agent-floating-window"
      :class="{ minimized: isMinimized, dragging: isDragging, resizing: isResizing }"
      :style="floatingWindowStyle"
      aria-label="问问 AI 浮窗"
    >
      <header class="agent-floating-head" @pointerdown="startDrag">
        <div class="agent-floating-title">
          <span class="agent-floating-status" :class="{ running: isLoading }"></span>
          <div>
            <strong>问问 AI</strong>
            <small>{{ floatingWindowSubtitle }}</small>
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
          <button type="button" title="关闭浮窗" aria-label="关闭问问 AI" @click.stop="closeFloatingWindow">
            ×
          </button>
        </div>
      </header>

      <div v-if="!isMinimized" class="agent-floating-body">
        <section v-if="isSessionsOpen" class="agent-floating-session-panel" aria-label="AI 会话列表">
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
          <NotificationToast
            type="warning"
            :message="sessionsError"
            @close="sessionsError = ''"
          />
        </section>

        <section v-if="knowledgeContext" class="agent-floating-context-panel" aria-label="已加载的视频上下文">
          <div class="agent-floating-context-head">
            <div>
              <span>已加载视频上下文</span>
              <strong>{{ knowledgeContext.video.title }}</strong>
            </div>
            <b>{{ knowledgeContext.evidenceItems.length }} 条评论弹幕</b>
          </div>
          <div class="agent-floating-context-meta">
            <span v-if="knowledgeContextQuery">检索：{{ knowledgeContextQuery }}</span>
            <span>{{ tierLabel(knowledgeContext.video.tier) }}</span>
            <span v-if="knowledgeContext.video.category">{{ knowledgeContext.video.category }}</span>
            <span v-if="knowledgeContext.video.qualityScoreReliable && knowledgeContext.video.qualityScore !== null">
              质量分 {{ knowledgeContext.video.qualityScore }}
            </span>
            <span v-else-if="knowledgeContext.video.rawQualityScore !== null">质量样本不足</span>
          </div>
          <div class="agent-floating-context-grid">
            <p v-for="topic in visibleKnowledgeTopics" :key="topic.chunkId">
              <b>{{ chunkTypeLabel(topic.chunkType) }}</b>
              {{ topic.chunkTitle }}
            </p>
          </div>
          <div v-if="visibleKnowledgeEvidence.length" class="agent-floating-context-evidence">
            <p v-for="item in visibleKnowledgeEvidence" :key="item.itemId">
              <b>{{ sourceTypeLabel(item.sourceType) }}</b>
              {{ clipText(item.content, 72) }}
            </p>
          </div>
        </section>

        <div ref="messageListRef" class="message-list agent-floating-message-list">
          <div v-if="messages.length === 0" class="agent-floating-empty">
            <p>可以问：这个标题哪里弱？观众可能会误解什么？下一期选题怎么延展？</p>
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

          <MessageBubble
            v-for="message in messages"
            :key="message.id"
            :message="message"
            :show-diagnostics="props.developerMode"
          />

          <!-- 流式输出区域：显示正在生成的内容，替代原来的静态 loading 提示 -->
          <div v-if="isStreaming" class="agent-streaming-block" aria-label="AI 正在生成回复">
            <!-- 实时显示的 ReAct 步骤 -->
            <div
              v-for="(step, index) in streamingSteps"
              :key="index"
              class="agent-streaming-step"
            >
              <span class="streaming-step-number">步骤 {{ step.stepNumber }}</span>
              <span v-if="step.action" class="streaming-step-action">
                {{ step.action }}{{ step.actionInput ? `(${step.actionInput.slice(0, 40)}${step.actionInput.length > 40 ? '...' : ''})` : '' }}
              </span>
            </div>
            <!-- 流式文本内容（打字机效果） -->
            <div class="message assistant">
              <div class="avatar">A</div>
              <div class="bubble">
                <div class="streaming-content">
                  {{ streamingContent }}
                  <span class="streaming-cursor" aria-hidden="true">▍</span>
                </div>
                <div v-if="streamingSteps.length > 0 && !streamingContent" class="streaming-thinking">
                  AI 正在整理建议
                  <span class="thinking-dots" aria-hidden="true">
                    <i></i>
                    <i></i>
                    <i></i>
                  </span>
                </div>
              </div>
            </div>
          </div>

          <!-- 非流式加载（兜底：当 isLoading 为 true 但 isStreaming 为 false 时显示） -->
          <div v-else-if="isLoading" class="message assistant">
            <div class="avatar">A</div>
            <div class="bubble loading animated" aria-label="AI 正在整理建议">
              <span class="thinking-text">AI 正在整理建议</span>
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
          <span v-if="props.developerMode">{{ latestStepCount }} 个推理步骤</span>
          <span>{{ sessionId && props.developerMode ? '当前会话' : '当前对话' }}</span>
        </div>

        <div v-if="props.developerMode" class="agent-mode-switch" aria-label="AI 执行模式">
          <button
            v-for="option in executionModeOptions"
            :key="option.value"
            type="button"
            :class="{ active: executionMode === option.value }"
            :title="modeButtonTitle(option)"
            @click="executionMode = option.value"
          >
            <strong>{{ option.label }}</strong>
            <small>{{ option.description }}</small>
          </button>
        </div>

        <NotificationToast
          type="error"
          :message="errorMessage"
          @close="errorMessage = ''"
        />

        <ChatComposer
          ref="composerRef"
          v-model="inputMessage"
          :can-send="canSend"
          :is-loading="isLoading"
          :is-streaming="isStreaming"
          @send-message="sendFloatingMessage"
          @stop-streaming="stopStreaming"
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
