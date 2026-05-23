<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
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

type ChatMessage = {
  id: number
  role: 'user' | 'assistant'
  content: string
  steps?: AgentStep[]
  stopReason?: string | null
}

const inputMessage = ref('')
const sessionId = ref('')
const messages = ref<ChatMessage[]>([])
const isLoading = ref(false)
const errorMessage = ref('')
const messageListRef = ref<HTMLElement | null>(null)

const canSend = computed(() => inputMessage.value.trim().length > 0 && !isLoading.value)
const markdown = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true,
})

markdown.use(markdownItKatex, {
  throwOnError: false,
  errorColor: '#b43c2d',
})

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
}

async function scrollToBottom() {
  await nextTick()
  messageListRef.value?.scrollTo({
    top: messageListRef.value.scrollHeight,
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
        <code>{{ sessionId || 'new session' }}</code>
        <button type="button" class="secondary-button" @click="startNewSession">New</button>
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
              <summary>ReAct steps · {{ message.steps.length }}</summary>
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
  color: #172026;
  background: #f4f6f8;
  font-family:
    Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
}

button,
textarea {
  font: inherit;
}

.app-shell {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  min-height: 100vh;
}

.sidebar {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 28px 22px;
  color: #f7fafc;
  background: #132027;
}

.eyebrow,
.label {
  margin: 0 0 8px;
  color: #9fb6c2;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0;
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
  font-size: 28px;
  line-height: 1.12;
}

.panel {
  display: grid;
  gap: 8px;
  padding: 14px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.05);
}

.panel code {
  overflow-wrap: anywhere;
  color: #ffffff;
  font-size: 12px;
}

.panel p {
  margin-bottom: 0;
  color: #bed0d8;
}

.secondary-button {
  width: 72px;
  min-height: 34px;
  border: 1px solid rgba(255, 255, 255, 0.24);
  border-radius: 6px;
  color: #ffffff;
  background: transparent;
  cursor: pointer;
}

.workspace {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto auto;
  min-width: 0;
  height: 100vh;
}

.topbar {
  display: flex;
  align-items: center;
  min-height: 82px;
  padding: 18px 28px;
  border-bottom: 1px solid #dbe3e8;
  background: #ffffff;
}

.topbar h2 {
  margin-bottom: 4px;
  font-size: 22px;
}

.topbar p {
  margin-bottom: 0;
  color: #63717a;
}

.message-list {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-height: 0;
  padding: 28px;
  overflow-y: auto;
}

.empty-state {
  max-width: 560px;
  margin: auto;
  text-align: center;
}

.empty-state h3 {
  margin-bottom: 8px;
  font-size: 24px;
}

.empty-state p {
  color: #63717a;
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
  background: #266dd3;
}

.avatar {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 50%;
  color: #ffffff;
  background: #56636c;
  font-size: 13px;
  font-weight: 800;
}

.message.user .avatar {
  background: #1f5aa8;
}

.bubble {
  min-width: 0;
  padding: 14px 16px;
  border: 1px solid #dbe3e8;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 8px 24px rgba(19, 32, 39, 0.06);
}

.bubble p {
  margin-bottom: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.65;
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
  background: rgba(19, 32, 39, 0.08);
}

.markdown-body :deep(pre) {
  margin: 10px 0 0;
  padding: 12px;
  border-radius: 6px;
  overflow-x: auto;
  background: rgba(19, 32, 39, 0.08);
}

.markdown-body :deep(pre code) {
  padding: 0;
  background: transparent;
}

.markdown-body :deep(blockquote) {
  margin: 10px 0 0;
  padding-left: 12px;
  border-left: 4px solid rgba(38, 109, 211, 0.35);
  color: #4d5b65;
}

.markdown-body :deep(a) {
  color: #184f95;
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
  color: #63717a;
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
  border-top: 1px solid rgba(99, 113, 122, 0.24);
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
  background: rgba(19, 32, 39, 0.08);
}

.stop-reason {
  display: block;
  margin-top: 10px;
  color: #b43c2d;
}

.error {
  margin: 0 28px 12px;
  padding: 10px 12px;
  border: 1px solid #f0b8ae;
  border-radius: 8px;
  color: #8d2c20;
  background: #fff0ed;
}

.composer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 96px;
  gap: 12px;
  padding: 18px 28px 24px;
  border-top: 1px solid #dbe3e8;
  background: #ffffff;
}

textarea {
  width: 100%;
  min-height: 72px;
  max-height: 180px;
  resize: vertical;
  border: 1px solid #cad5dc;
  border-radius: 8px;
  padding: 12px 14px;
  color: #172026;
  background: #ffffff;
  outline: none;
}

textarea:focus {
  border-color: #266dd3;
  box-shadow: 0 0 0 3px rgba(38, 109, 211, 0.14);
}

.composer button {
  min-height: 72px;
  border: 0;
  border-radius: 8px;
  color: #ffffff;
  background: #1f7a5c;
  font-weight: 800;
  cursor: pointer;
}

.composer button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
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
