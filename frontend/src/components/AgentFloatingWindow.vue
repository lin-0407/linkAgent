<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useAppStore } from '@/stores/appStore'
import { useModalDialog } from '@/composables/useModalDialog'
import type { AssistantAction, AssistantPlan } from '@/api/assistant'
import { useCreatorStore } from '@/stores/creatorStore'
import CreatorAssistantPanel from '@/components/CreatorAssistantPanel.vue'
import { ChevronRight, History, Sparkles, SquarePen, X } from '@lucide/vue'
import ChatComposer from '@/components/ChatComposer.vue'
import NotificationToast from '@/components/NotificationToast.vue'
import {
  chunkTypeLabel,
  formatKnowledgeCount,
  knowledgeQualityScoreLabel,
  knowledgeCategoryRankLabel,
  knowledgeTierLabel,
  sentimentLabel,
  sourceTypeLabel,
} from '@/components/knowledge/knowledgeDisplay'
import { useAgentChat } from '@/composables/useAgentChat'
import type { AgentExecutionMode, SessionListItem } from '@/types/agent'
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

// 消息气泡独占 markdown-it + KaTeX（约 190 kB）。改为异步组件后，这两个库只在真正出现
// 聊天消息时才加载，首屏不必为一条还不存在的消息付出体积；气泡本身是纯展示组件，
// 不参与全局事件监听，异步化不影响任何交互时序。
const MessageBubble = defineAsyncComponent(() => import('@/components/MessageBubble.vue'))

const props = withDefaults(
  defineProps<{
    developerMode?: boolean
    backgroundInert?: boolean
  }>(),
  {
    developerMode: false,
    backgroundInert: false,
  },
)

const promptExamples = [
  '帮我创建一期视频任务',
  '检查当前工作，列出下一步计划',
  '我已有成片，应该从哪里开始？',
]
const route = useRoute()
const creatorStore = useCreatorStore()
const assistantTaskId = computed(() => {
  if (route.path === '/creator') return creatorStore.activeInteractiveTaskId || creatorStore.selectedTaskId || undefined
  if (route.path === '/video-analysis') return creatorStore.selectedTaskId || undefined
  return undefined
})
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
const appStore = useAppStore()
const { assistantOpen: isOpen } = storeToRefs(appStore)
const composerRef = ref<InstanceType<typeof ChatComposer> | null>(null)
const businessPanelRef = ref<InstanceType<typeof CreatorAssistantPanel> | null>(null)
const planListRef = ref<HTMLElement | null>(null)
const assistantView = ref<'chat' | 'plans'>('chat')
const attentionPlans = ref<AssistantPlan[]>([])
const attentionActions = ref<AssistantAction[]>([])
const pendingPlans = computed(() => attentionPlans.value.filter(plan => plan.status === 'PENDING'))
const pendingActions = computed(() => attentionActions.value.filter(action => action.status === 'PENDING'))
const attentionCount = computed(() => attentionPlans.value.length + attentionActions.value.length)
const needsConfirmation = computed(() => pendingPlans.value.length > 0 || pendingActions.value.length > 0)
const attentionLabel = computed(() => pendingPlans.value.length
  ? pendingPlans.value.length + ' 份计划待确认'
  : pendingActions.value.length ? pendingActions.value.length + ' 项操作待确认'
    : attentionPlans.value.some(plan => plan.status === 'WAIT_INPUT') ? '计划等你补充信息' : '有操作正在处理')
const attentionTitle = computed(() => (pendingPlans.value[0] || attentionPlans.value[0])?.title
  || (pendingActions.value[0] || attentionActions.value[0])?.description)
const dockMedia = window.matchMedia('(min-width: 1280px)')
const isDocked = ref(dockMedia.matches)
const modalOpen = computed(() => isOpen.value && !isDocked.value)
const { dialogRef, handleDialogKeydown } = useModalDialog(modalOpen, closeFloatingWindow)
const panelStyle = computed(() => ({ '--assistant-bottom': route.path === '/usage-logs' ? '0px' : '36px' }))

const assistantStatus = ref<'loading' | 'ready' | 'error'>('loading')
const knowledgeContext = ref<ReferenceVideoAnalysisContext | null>(null)
const knowledgeContextQuery = ref('')
watch(() => [route.path, assistantTaskId.value], () => {
  if (route.path !== '/knowledge') {
    knowledgeContext.value = null
    knowledgeContextQuery.value = ''
  }
})
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
  streamingThinking,
  isSessionsLoading,
  isSessionsOpen,
  latestStepCount,
  messageListRef,
  messages,
  openSession,
  sendMessage,
  stopStreaming,
  sessionId,
  scrollToBottom,
  sessions,
  sessionsError,
  startNewSession,
  warningMessage,
} = useAgentChat(() => ({ page: route.path, taskId: assistantTaskId.value,
  step: route.path === '/creator' ? creatorStore.activeStep : undefined }))

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
  return props.developerMode ? activeSessionLabel.value : '聊想法、查进度，操作前先确认'
})

/**
 * 首字未到：SSE 连接已建立，但思考内容、步骤、正文一个都没来。
 * 单独判断这个状态，是为了显示动态思考占位，而不是渲染一个没有内容的空气泡。
 */
const isStreamingIdle = computed(
  () =>
    !streamingContent.value &&
    !streamingThinking.value.trim() &&
    streamingSteps.value.length === 0,
)

const visibleKnowledgeTopics = computed(() => knowledgeContext.value?.topics.slice(0, 4) ?? [])
const visibleKnowledgeEvidence = computed(() => knowledgeContext.value?.evidenceItems.slice(0, 5) ?? [])

function updateDockMode() { isDocked.value = dockMedia.matches }
onMounted(() => {
  dockMedia.addEventListener('change', updateDockMode)
  window.addEventListener(KNOWLEDGE_VIDEO_CONTEXT_EVENT, handleKnowledgeVideoContext)
})
onBeforeUnmount(() => {
  dockMedia.removeEventListener('change', updateDockMode)
  window.removeEventListener(KNOWLEDGE_VIDEO_CONTEXT_EVENT, handleKnowledgeVideoContext)
})

watch(isOpen, async open => {
  if (open) {
    await nextTick()
    // 打开助手不抢输入焦点，也不把主页面滚到表单底部。
    dialogRef.value?.focus({ preventScroll: true })
    if (assistantView.value === 'chat') {
      composerRef.value?.adjustInputHeight()
      void scrollToBottom()
    }
  } else {
    isSessionsOpen.value = false
    // 从并排切到抽屉后，弹层记录的旧焦点可能已被移除，统一回到稳定入口。
    await nextTick()
    document.getElementById('workbench-assistant-toggle')?.focus({ preventScroll: true })
  }
})

function openFloatingWindow() { isOpen.value = true }
function closeFloatingWindow() { isOpen.value = false }
function handlePanelKeydown(event: KeyboardEvent) {
  if (modalOpen.value) handleDialogKeydown(event)
  else if (event.key === 'Escape') { event.preventDefault(); closeFloatingWindow() }
}
async function showPlans() {
  assistantView.value = 'plans'
  isSessionsOpen.value = false
  await nextTick()
  const list = planListRef.value
  // 单项操作和多步计划共用入口，直接定位待处理卡片，避免历史记录把按钮挤到下方。
  const target = list?.querySelector(pendingPlans.value.length ? '.assistant-plan[data-status="PENDING"]'
    : pendingActions.value.length ? '.assistant-action[data-status="PENDING"]' : '.assistant-plan')
  list?.scrollTo({ top: target && list ? target.getBoundingClientRect().top - list.getBoundingClientRect().top + list.scrollTop - 16 : 0 })
  list?.focus({ preventScroll: true })
}

function startNewConversation() {
  assistantView.value = 'chat'
  startNewSession()
  knowledgeContext.value = null
  knowledgeContextQuery.value = ''
  isSessionsOpen.value = false
  focusComposer()
}

async function openAgentSession(session: SessionListItem) {
  assistantView.value = 'chat'
  isSessionsOpen.value = false
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
  if (assistantStatus.value !== 'ready') return
  const displayMessage = inputMessage.value.trim()
  await sendMessage({
    displayMessage,
    outboundMessage: buildKnowledgeContextMessage(displayMessage),
  })
  await businessPanelRef.value?.reload()
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
  assistantView.value = 'chat'
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
    `BV：${video.bvId || '无'}；分区：${video.category || '未标注'}；层级：${knowledgeTierLabel(video.tier) || '未标注'}；分区互动分：${formatKnowledgeQuality(video)}（仅同分区可比）；${knowledgeCategoryRankLabel(video) || '暂无区内排名'}`,
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
  return typeof video.qualityScore === 'number' ? video.qualityScore.toFixed(2) : '统计待更新'
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

function modeButtonTitle(option: { label: string; description: string }) {
  return `${option.label}：${option.description}`
}

function formatReferenceCount(value: number | null) {
  return value === null || value === undefined ? '无' : formatKnowledgeCount(value)
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
  <Teleport to="body">
  <div v-if="isOpen" class="assistant-workspace-layer" :class="{ 'assistant-workspace-modal': modalOpen }" :style="panelStyle" @click.self="modalOpen && closeFloatingWindow()">
    <section
      id="workbench-assistant-panel"
      ref="dialogRef"
      class="agent-floating-window"
      :inert="props.backgroundInert"
      :role="modalOpen ? 'dialog' : 'complementary'"
      :aria-modal="modalOpen || undefined"
      aria-label="创作助手"
      tabindex="-1"
      @keydown="handlePanelKeydown"
    >
      <header class="agent-floating-head">
        <div class="agent-floating-title">
          <span class="agent-floating-status" :class="{ running: isLoading, unavailable: assistantStatus === 'error', checking: assistantStatus === 'loading' }" :title="assistantStatus === 'ready' ? '助手已就绪' : assistantStatus === 'error' ? '助手暂时不可用' : '正在连接助手'"></span>
          <div>
            <strong>创作助手</strong>
            <small>{{ floatingWindowSubtitle }}</small>
          </div>
        </div>
        <div class="agent-floating-actions">
          <button type="button" title="历史会话" aria-label="历史会话" :aria-expanded="isSessionsOpen" @click.stop="assistantView = 'chat'; isSessionsOpen = !isSessionsOpen">
            <History :size="17" aria-hidden="true" />
          </button>
          <button type="button" title="新建会话" aria-label="新建会话" @click.stop="startNewConversation"><SquarePen :size="17" aria-hidden="true" /></button>
          <button type="button" title="收起创作助手" aria-label="关闭创作助手" @click.stop="closeFloatingWindow">
            <X :size="16" :stroke-width="1.8" aria-hidden="true" />
          </button>
        </div>
      </header>

      <nav class="assistant-view-tabs" aria-label="助手工作区">
        <button type="button" :class="{ active: assistantView === 'chat' }" :aria-pressed="assistantView === 'chat'" @click="assistantView = 'chat'">对话</button>
        <button type="button" :class="{ active: assistantView === 'plans' }" :aria-pressed="assistantView === 'plans'" @click="showPlans">操作计划 <span v-if="attentionCount">{{ attentionCount }}</span></button>
      </nav>
      <div class="agent-floating-body">
        <div v-show="assistantView === 'plans'" ref="planListRef" class="agent-floating-content assistant-plan-content" tabindex="-1" aria-label="操作计划列表">
          <CreatorAssistantPanel ref="businessPanelRef"
            :task-id="assistantTaskId" :page="route.path" :generating="isStreaming"
            :step-count="streamingSteps.length" @status="assistantStatus = $event"
            @attention="attentionPlans = $event"
            @action-attention="attentionActions = $event"
          />
        </div>
        <div v-show="assistantView === 'chat'" ref="messageListRef" class="agent-floating-content">
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
              <strong>{{ props.developerMode ? shortSessionId(item.sessionId) : formatSessionPreview(item.preview) }}</strong>
              <span v-if="props.developerMode">{{ formatSessionPreview(item.preview) }}</span>
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
            <span>{{ knowledgeTierLabel(knowledgeContext.video.tier) || '未标注' }}</span>
            <span v-if="knowledgeContext.video.category">{{ knowledgeContext.video.category }}</span>
            <span v-if="knowledgeQualityScoreLabel(knowledgeContext.video)">
              {{ knowledgeQualityScoreLabel(knowledgeContext.video) }}
            </span>
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

        <div class="message-list agent-floating-message-list">
          <div v-if="messages.length === 0" class="agent-floating-empty">
            <span class="assistant-welcome-mark" aria-hidden="true"><Sparkles :size="26" :stroke-width="1.6" /></span>
            <h3>今天想完成什么？</h3>
            <p>从一个想法开始，或让我帮你看看下一步。</p>
            <div class="agent-floating-prompts">
              <button
                v-for="example in promptExamples"
                :key="example"
                type="button"
                @click="usePromptExample(example)"
              >
                {{ example }}
                <ChevronRight :size="15" aria-hidden="true" />
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
              v-for="(step, index) in (props.developerMode ? streamingSteps : [])"
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
                <!--
                  思考区：后端只在模型真的返回 reasoning_content 时才推 thinking 事件，
                  没有思考内容时这块整个不出现（不是空占位）。
                  默认展开，因为生成中用户最想知道"它在想什么"；生成结束后会收进消息里变成可折叠块。
                -->
                <details v-if="streamingThinking.trim()" class="agent-streaming-thinking" open>
                  <summary>思考过程</summary>
                  <div class="agent-streaming-thinking-body">{{ streamingThinking }}</div>
                </details>
                <!--
                  首字未到时的动态占位：SSE 连接已建立，但思考内容、步骤、正文一个都还没来。
                  这段时间以前渲染的是空气泡加一个光标，用户只看到一个没有内容的框，
                  所以换成会动的思考占位，让"请求已发出、还在跑"这件事可见。
                  正文和光标只在真有正文时才渲染：正文为空时那个光标只会加深"这个框是空的"的感觉。
                -->
                <div v-if="isStreamingIdle" class="streaming-thinking" aria-label="AI 正在思考">
                  AI 正在思考
                  <span class="thinking-dots" aria-hidden="true">
                    <i></i>
                    <i></i>
                    <i></i>
                  </span>
                </div>
                <div v-else-if="streamingContent" class="streaming-content">
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
        </div>

        <div v-if="props.developerMode && assistantView === 'chat'" class="agent-floating-meta">
          <span>{{ messages.length }} 条消息</span>
          <span v-if="props.developerMode">{{ latestStepCount }} 个推理步骤</span>
          <span>{{ sessionId && props.developerMode ? '当前会话' : '当前对话' }}</span>
        </div>

        <div v-if="props.developerMode && assistantView === 'chat'" class="agent-mode-switch" aria-label="AI 执行模式">
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

        <!-- 非致命告警：连接长时间没有事件、事件格式不识别等。只提示，不打断生成 -->
        <NotificationToast
          type="warning"
          :message="warningMessage"
          @close="warningMessage = ''"
        />

        <NotificationToast
          type="error"
          :message="errorMessage"
          @close="errorMessage = ''"
        />

        <div v-if="assistantView === 'chat' && attentionCount" class="assistant-plan-attention" role="status">
          <div><strong>{{ attentionLabel }}</strong><span>{{ attentionTitle }}</span></div>
          <button type="button" @click="showPlans">{{ needsConfirmation ? '查看并确认' : '查看计划' }}<ChevronRight :size="14" aria-hidden="true" /></button>
        </div>
        <div v-if="assistantView === 'chat' && assistantStatus === 'error'" class="assistant-connection-notice" role="alert">助手连接异常 <button type="button" @click="showPlans">查看原因与重试</button></div>
        <ChatComposer v-show="assistantView === 'chat'"
          ref="composerRef"
          placeholder="描述你想完成的工作，我会先列出具体计划…"
          v-model="inputMessage"
          :can-send="canSend && assistantStatus === 'ready'"
          :is-loading="isLoading"
          :is-streaming="isStreaming"
          @send-message="sendFloatingMessage"
          @stop-streaming="stopStreaming"
        />
      </div>

    </section>
  </div>
  </Teleport>
</template>
