<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import MarkdownIt from 'markdown-it'
import markdownItKatex from 'markdown-it-katex'
import 'katex/dist/katex.min.css'

type AgentStep = {
  stepNumber: number
  thought: string
  action: string | null
  actionInput: string | null
  observation: string | null
}

type AgentChatResponse = {
  sessionId: string
  finalAnswer: string | null
  stopReason: string | null
  totalSteps: number
  steps: AgentStep[]
}

type SessionListItem = {
  sessionId: string
  preview: string
  messageCount: number
}

type SessionMessageItem = {
  role: 'user' | 'assistant' | string
  content: string
}

type ChatMessage = {
  id: number
  role: 'user' | 'assistant'
  content: string
  steps?: AgentStep[]
  stopReason?: string | null
}

const inputMessage = ref('')
const sessionId = ref('')
const sessions = ref<SessionListItem[]>([])
const messages = ref<ChatMessage[]>([])
const isLoading = ref(false)
const isSessionsLoading = ref(false)
const isSessionsOpen = ref(false)
const errorMessage = ref('')
const sessionsError = ref('')
const messageListRef = ref<HTMLElement | null>(null)
const selectedSessionKey = 'link-agent-session-id'

const canSend = computed(() => inputMessage.value.trim().length > 0 && !isLoading.value)
const activeSessionLabel = computed(() => sessionId.value || 'new session')

const markdown = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true,
})

markdown.use(markdownItKatex, {
  throwOnError: false,
  errorColor: '#b43c2d',
})

loadPersistedSession()
void loadSessions()

watch(
  () => messages.value.length,
  async () => {
    await scrollToBottom()
  },
)

async function sendMessage() {
  const message = inputMessage.value.trim()
  if (!message || isLoading.value) {
    return
  }

  errorMessage.value = ''
  inputMessage.value = ''
  messages.value.push({
    id: Date.now(),
    role: 'user',
    content: message,
  })

  await scrollToBottom()
  isLoading.value = true

  try {
    const response = await fetch('/api/agent/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        sessionId: sessionId.value || undefined,
        message,
      }),
    })

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }

    const data = (await response.json()) as AgentChatResponse
    sessionId.value = data.sessionId
    persistSessionId(data.sessionId)
    await loadSessions()
    messages.value.push({
      id: Date.now() + 1,
      role: 'assistant',
      content: data.finalAnswer || data.stopReason || 'Agent 没有返回内容',
      steps: data.steps,
      stopReason: data.stopReason,
    })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '请求失败'
  } finally {
    isLoading.value = false
    await scrollToBottom()
  }
}

function startNewSession() {
  sessionId.value = ''
  messages.value = []
  errorMessage.value = ''
  inputMessage.value = ''
  clearPersistedSession()
}

async function loadSessions() {
  isSessionsLoading.value = true
  sessionsError.value = ''
  try {
    const response = await fetch('/api/agent/sessions')
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }
    sessions.value = (await response.json()) as SessionListItem[]
  } catch (error) {
    sessions.value = []
    sessionsError.value = error instanceof Error ? error.message : 'Failed to load sessions'
  } finally {
    isSessionsLoading.value = false
  }
}

async function openSession(session: SessionListItem) {
  sessionId.value = session.sessionId
  persistSessionId(session.sessionId)
  errorMessage.value = ''
  isSessionsOpen.value = false
  await loadSessionMessages(session.sessionId)
  await loadSessions()
}

async function loadSessionMessages(targetSessionId: string) {
  try {
    const response = await fetch(`/api/agent/sessions/${encodeURIComponent(targetSessionId)}`)
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }
    const data = (await response.json()) as SessionMessageItem[]
    messages.value = data.map((item, index) => ({
      id: Date.now() + index,
      role: normalizeRole(item.role),
      content: item.content,
    }))
  } catch (error) {
    messages.value = []
    errorMessage.value = error instanceof Error ? error.message : 'Failed to load session messages'
  }
}

function persistSessionId(value: string) {
  localStorage.setItem(selectedSessionKey, value)
}

function clearPersistedSession() {
  localStorage.removeItem(selectedSessionKey)
}

function loadPersistedSession() {
  const saved = localStorage.getItem(selectedSessionKey)
  if (saved) {
    sessionId.value = saved
    void loadSessionMessages(saved)
  }
}

async function scrollToBottom() {
  await nextTick()
  const el = messageListRef.value
  if (!el) {
    return
  }

  el.scrollTo({
    top: el.scrollHeight,
    behavior: 'smooth',
  })
}

function renderAssistantContent(content: string) {
  return markdown.render(normalizeMathSyntax(content))
}

function normalizeMathSyntax(content: string) {
  return content
    .replace(/\r\n/g, '\n')
    .replace(/\\\[((?:.|\n)*?)\\\]/g, (_, formula: string) => `\n$$\n${formula.trim()}\n$$\n`)
    .replace(/\\\(((?:.|\n)*?)\\\)/g, (_, formula: string) => `$${formula.trim()}$`)
}

function shortSessionId(value: string) {
  return value.length <= 12 ? value : `${value.slice(0, 6)}...${value.slice(-4)}`
}

function normalizeRole(role: string) {
  const normalized = role.trim().toLowerCase()
  if (normalized === 'assistant' || normalized === 'ai' || normalized === 'bot') {
    return 'assistant'
  }
  return 'user'
}
</script>

<template>
  <main class="app-shell">
    <aside class="sidebar">
      <div>
        <p class="eyebrow">Link Agent</p>
        <h1>ReAct Console</h1>
      </div>

      <section class="panel">
        <span class="label">Session</span>
        <code>{{ activeSessionLabel }}</code>
        <div class="sidebar-actions">
          <button type="button" class="secondary-button" @click="isSessionsOpen = !isSessionsOpen">
            Sessions
          </button>
          <button type="button" class="secondary-button" @click="startNewSession">New</button>
        </div>
        <div v-if="isSessionsOpen" class="session-list">
          <button v-if="isSessionsLoading" type="button" class="session-item muted" disabled>
            Loading sessions...
          </button>
          <template v-else>
            <button
              v-for="item in sessions"
              :key="item.sessionId"
              type="button"
              class="session-item"
              :class="{ active: item.sessionId === sessionId }"
              @click="openSession(item)"
            >
              <strong>{{ shortSessionId(item.sessionId) }}</strong>
              <span>{{ item.preview }}</span>
              <small>{{ item.messageCount }} messages</small>
            </button>
            <p v-if="sessions.length === 0" class="empty-sessions">No saved sessions yet</p>
          </template>
          <p v-if="sessionsError" class="session-error">{{ sessionsError }}</p>
        </div>
      </section>

      <section class="panel">
        <span class="label">Status</span>
        <strong>{{ isLoading ? 'Running' : 'Ready' }}</strong>
        <p>{{ messages.length }} messages</p>
      </section>
    </aside>

    <section class="workspace">
      <header class="topbar">
        <div>
          <h2>Agent Chat</h2>
          <p>多轮会话会自动复用当前 session 的短期记忆。</p>
        </div>
      </header>

      <div ref="messageListRef" class="message-list">
        <div v-if="messages.length === 0" class="empty-state">
          <h3>开始一次 Agent 调用</h3>
          <p>可以先告诉它“我叫 Link”，再追问“我叫什么？”验证短期记忆。</p>
        </div>

        <article
          v-for="message in messages"
          :key="message.id"
          class="message"
          :class="message.role"
        >
          <div class="avatar">{{ message.role === 'user' ? 'U' : 'A' }}</div>
          <div class="bubble">
            <template v-if="message.role === 'assistant'">
              <div class="markdown-body" v-html="renderAssistantContent(message.content)"></div>
            </template>
            <p v-else>{{ message.content }}</p>

            <details v-if="message.steps?.length" class="steps">
              <summary>ReAct steps {{ message.steps.length }}</summary>
              <ol>
                <li v-for="step in message.steps" :key="step.stepNumber">
                  <strong>#{{ step.stepNumber }}</strong>
                  <span>{{ step.thought }}</span>
                  <code v-if="step.action">{{ step.action }}({{ step.actionInput }})</code>
                  <small v-if="step.observation">{{ step.observation }}</small>
                </li>
              </ol>
            </details>

            <small v-if="message.stopReason" class="stop-reason">{{ message.stopReason }}</small>
          </div>
        </article>

        <div v-if="isLoading" class="message assistant">
          <div class="avatar">A</div>
          <div class="bubble loading animated" aria-label="Thinking">
            <span class="thinking-text">Thinking</span>
            <span class="thinking-dots" aria-hidden="true">
              <i></i>
              <i></i>
              <i></i>
            </span>
          </div>
        </div>
      </div>

      <p v-if="errorMessage" class="error">{{ errorMessage }}</p>

      <form class="composer" @submit.prevent="sendMessage">
        <textarea
          v-model="inputMessage"
          rows="3"
          placeholder="Ask Link Agent..."
          @keydown.enter.exact.prevent="sendMessage"
        />
        <button type="submit" :disabled="!canSend">
          {{ isLoading ? 'Running' : 'Send' }}
        </button>
      </form>
    </section>
  </main>
</template>

<style scoped>
:global(*) {
  box-sizing: border-box;
}

:global(body) {
  margin: 0;
  min-width: 320px;
  min-height: 100vh;
  color: #d9e2ea;
  background:
    radial-gradient(circle at top left, rgba(90, 140, 255, 0.18), transparent 34%),
    radial-gradient(circle at top right, rgba(61, 208, 175, 0.1), transparent 28%),
    linear-gradient(180deg, #09131b 0%, #0d1720 46%, #101b25 100%);
  font-family:
    'Aptos', 'Segoe UI Variable', 'Inter', ui-sans-serif, system-ui, -apple-system,
    BlinkMacSystemFont, sans-serif;
}

button,
textarea {
  font: inherit;
}

.app-shell {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  min-height: 100vh;
  position: relative;
}

.sidebar {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 24px 20px;
  color: #eef5fa;
  background:
    linear-gradient(180deg, rgba(11, 19, 26, 0.96), rgba(11, 19, 26, 0.9)),
    linear-gradient(180deg, #101923 0%, #0c141c 100%);
  border-right: 1px solid rgba(189, 214, 230, 0.08);
  box-shadow: 12px 0 40px rgba(0, 0, 0, 0.16);
}

.eyebrow,
.label {
  margin: 0 0 8px;
  color: #87a1b6;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

h1,
h2,
h3,
p {
  margin-top: 0;
}

h1 {
  margin-bottom: 0;
  font-size: 31px;
  line-height: 1.02;
  letter-spacing: -0.04em;
}

.panel {
  display: grid;
  gap: 12px;
  padding: 16px;
  border: 1px solid rgba(193, 216, 232, 0.12);
  border-radius: 18px;
  background:
    linear-gradient(180deg, rgba(18, 28, 38, 0.88), rgba(15, 23, 32, 0.92));
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.04),
    0 16px 40px rgba(0, 0, 0, 0.16);
}

.panel code {
  overflow-wrap: anywhere;
  color: #f8fbfd;
  font-size: 13px;
  line-height: 1.55;
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.05);
}

.panel p,
.empty-sessions {
  margin-bottom: 0;
  color: #9db0bf;
}

.session-error {
  margin: 2px 0 0;
  color: #ffb7b7;
  font-size: 12px;
  line-height: 1.4;
}

.secondary-button {
  min-width: 88px;
  min-height: 44px;
  padding: 0 14px;
  border: 1px solid rgba(141, 179, 205, 0.22);
  border-radius: 12px;
  color: #edf5fb;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.08), rgba(255, 255, 255, 0.03));
  cursor: pointer;
  transition:
    background-color 180ms ease,
    border-color 180ms ease,
    color 180ms ease,
    transform 180ms ease,
    box-shadow 180ms ease;
}

.secondary-button:hover {
  border-color: rgba(112, 171, 215, 0.45);
  box-shadow: 0 10px 24px rgba(0, 0, 0, 0.18);
  transform: translateY(-1px);
}

.secondary-button:focus-visible,
.session-item:focus-visible,
.composer button:focus-visible,
textarea:focus-visible {
  outline: 3px solid rgba(104, 171, 255, 0.4);
  outline-offset: 2px;
}

.sidebar-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.session-list {
  display: grid;
  gap: 10px;
  max-height: 320px;
  overflow: auto;
  padding-right: 2px;
}

.session-item {
  display: grid;
  gap: 6px;
  padding: 12px 14px;
  border: 1px solid rgba(193, 216, 232, 0.12);
  border-radius: 14px;
  color: #f7fbfe;
  background: rgba(255, 255, 255, 0.04);
  text-align: left;
  cursor: pointer;
  transition:
    border-color 180ms ease,
    background-color 180ms ease,
    transform 180ms ease,
    box-shadow 180ms ease;
}

.session-item strong,
.session-item span,
.session-item small {
  display: block;
}

.session-item span,
.session-item small {
  color: #98adbd;
}

.session-item:hover {
  transform: translateY(-1px);
  border-color: rgba(106, 172, 226, 0.28);
  background: rgba(255, 255, 255, 0.06);
  box-shadow: 0 12px 24px rgba(0, 0, 0, 0.14);
}

.session-item.active {
  border-color: rgba(93, 176, 255, 0.62);
  background:
    linear-gradient(180deg, rgba(52, 104, 164, 0.46), rgba(31, 61, 95, 0.32));
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.08),
    0 16px 30px rgba(0, 0, 0, 0.14);
}

.session-item.muted {
  cursor: default;
}

.workspace {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto auto;
  min-width: 0;
  height: 100vh;
  background:
    radial-gradient(circle at top right, rgba(63, 117, 193, 0.06), transparent 24%),
    linear-gradient(180deg, rgba(10, 17, 24, 0.92), rgba(12, 20, 29, 0.96));
}

.topbar {
  display: flex;
  align-items: center;
  min-height: 82px;
  padding: 18px 28px;
  border-bottom: 1px solid rgba(189, 216, 232, 0.1);
  background: rgba(8, 14, 20, 0.58);
  backdrop-filter: blur(14px);
}

.topbar h2 {
  margin-bottom: 4px;
  font-size: 20px;
  color: #f7fbfe;
  letter-spacing: -0.03em;
}

.topbar p {
  margin-bottom: 0;
  color: #9bb0c0;
}

.message-list {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-height: 0;
  padding: 28px;
  overflow-y: auto;
  scrollbar-color: rgba(142, 180, 210, 0.38) transparent;
}

.empty-state {
  max-width: 560px;
  margin: auto;
  text-align: center;
}

.empty-state h3 {
  margin-bottom: 8px;
  font-size: 24px;
  color: #f7fbfe;
  letter-spacing: -0.03em;
}

.empty-state p {
  color: #a0b2c0;
}

.message {
  display: grid;
  grid-template-columns: 36px minmax(0, 760px);
  gap: 12px;
  align-items: start;
}

.message.user {
  justify-content: end;
}

.message.user .bubble {
  color: #ffffff;
  background: linear-gradient(180deg, #2c73d7, #245eb0);
  border-color: rgba(108, 164, 238, 0.26);
}

.avatar {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 50%;
  color: #ffffff;
  background: linear-gradient(180deg, #33485c, #273746);
  font-size: 13px;
  font-weight: 800;
}

.message.user .avatar {
  background: linear-gradient(180deg, #2e6fd0, #1d4f99);
}

.bubble {
  min-width: 0;
  padding: 14px 16px;
  border: 1px solid rgba(193, 216, 232, 0.1);
  border-radius: 18px;
  background: rgba(15, 22, 30, 0.88);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.04),
    0 14px 34px rgba(0, 0, 0, 0.18);
}

.bubble p {
  margin-bottom: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.65;
  color: #eff4f8;
}

.markdown-body :deep(p) {
  margin: 0 0 10px;
  white-space: normal;
}

.markdown-body :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(code) {
  padding: 1px 5px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.08);
}

.markdown-body :deep(pre) {
  margin: 10px 0 0;
  padding: 12px;
  border-radius: 6px;
  overflow-x: auto;
  background: rgba(255, 255, 255, 0.06);
}

.markdown-body :deep(pre code) {
  padding: 0;
  background: transparent;
}

.markdown-body :deep(blockquote) {
  margin: 10px 0 0;
  padding-left: 12px;
  border-left: 4px solid rgba(91, 165, 247, 0.5);
  color: #c0ced9;
}

.markdown-body :deep(a) {
  color: #8fc0ff;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 8px 0 0;
  padding-left: 22px;
}

.markdown-body :deep(.katex-display) {
  margin: 10px 0;
  overflow-x: auto;
  overflow-y: hidden;
}

.markdown-body :deep(.katex) {
  font-size: 1.06em;
}

.loading {
  color: #c1cfda;
}

.loading.animated {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-width: 120px;
  animation: loading-pulse 1.4s ease-in-out infinite;
}

.thinking-text {
  letter-spacing: 0;
}

.thinking-dots {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.thinking-dots i {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.35;
  animation: dot-bounce 1.05s ease-in-out infinite;
}

.thinking-dots i:nth-child(2) {
  animation-delay: 0.15s;
}

.thinking-dots i:nth-child(3) {
  animation-delay: 0.3s;
}

.steps {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid rgba(193, 216, 232, 0.12);
}

.steps summary {
  cursor: pointer;
  color: inherit;
  font-weight: 700;
}

.steps ol {
  display: grid;
  gap: 10px;
  margin: 12px 0 0;
  padding-left: 20px;
}

.steps li {
  line-height: 1.5;
}

.steps span,
.steps code,
.steps small {
  display: block;
  margin-top: 4px;
  overflow-wrap: anywhere;
}

.steps code {
  padding: 6px 8px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.07);
}

.stop-reason {
  display: block;
  margin-top: 10px;
  color: #ff8f8f;
}

.error {
  margin: 0 28px 12px;
  padding: 10px 12px;
  border: 1px solid rgba(255, 160, 160, 0.22);
  border-radius: 12px;
  color: #ffd1d1;
  background: rgba(142, 45, 45, 0.16);
}

.composer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 112px;
  gap: 12px;
  padding: 18px 28px 24px;
  border-top: 1px solid rgba(189, 216, 232, 0.1);
  background: rgba(8, 14, 20, 0.68);
  backdrop-filter: blur(14px);
}

textarea {
  width: 100%;
  min-height: 72px;
  max-height: 180px;
  resize: vertical;
  border: 1px solid rgba(193, 216, 232, 0.16);
  border-radius: 16px;
  padding: 12px 14px;
  color: #eff4f8;
  background: rgba(255, 255, 255, 0.05);
  outline: none;
}

textarea:focus {
  border-color: rgba(104, 171, 255, 0.6);
  box-shadow: 0 0 0 3px rgba(104, 171, 255, 0.16);
}

.composer button {
  min-height: 72px;
  border: 0;
  border-radius: 16px;
  color: #ffffff;
  background: linear-gradient(180deg, #2c8f72, #1f6d57);
  font-weight: 800;
  letter-spacing: 0.01em;
  cursor: pointer;
  box-shadow: 0 16px 28px rgba(19, 90, 70, 0.25);
  transition:
    transform 180ms ease,
    box-shadow 180ms ease,
    filter 180ms ease;
}

.composer button:hover {
  transform: translateY(-1px);
  filter: brightness(1.04);
  box-shadow: 0 18px 32px rgba(19, 90, 70, 0.34);
}

.composer button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
  box-shadow: none;
  transform: none;
}

@media (max-width: 760px) {
  .app-shell {
    grid-template-columns: 1fr;
  }

  .sidebar {
    padding: 18px;
  }

  .workspace {
    height: auto;
    min-height: 72vh;
  }

  .topbar,
  .message-list,
  .composer {
    padding-right: 18px;
    padding-left: 18px;
  }

  .message {
    grid-template-columns: 32px minmax(0, 1fr);
  }

  .avatar {
    width: 32px;
    height: 32px;
  }

  .composer {
    grid-template-columns: 1fr;
  }

  .composer button {
    min-height: 48px;
  }
}

@keyframes dot-bounce {
  0%,
  80%,
  100% {
    transform: translateY(0);
    opacity: 0.35;
  }

  40% {
    transform: translateY(-4px);
    opacity: 1;
  }
}

@keyframes loading-pulse {
  0%,
  100% {
    transform: translateY(0);
  }

  50% {
    transform: translateY(-1px);
  }
}
</style>
