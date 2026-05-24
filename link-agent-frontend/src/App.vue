<script setup lang="ts">
import { nextTick, ref } from 'vue'
import 'katex/dist/katex.min.css'
import AgentSidebar from '@/components/AgentSidebar.vue'
import ChatComposer from '@/components/ChatComposer.vue'
import ErrorNotice from '@/components/ErrorNotice.vue'
import MessageList from '@/components/MessageList.vue'
import TopBar from '@/components/TopBar.vue'
import { useAgentChat } from '@/composables/useAgentChat'

const promptExamples = [
  '我叫 Link，请记住这个信息。稍后我会问你。',
  '请用 ReAct 思路帮我拆解一个 Spring AI 学习计划。',
  '帮我计算 128 * 37，并告诉我你是否调用了工具。',
]
const capabilityTags = ['短期记忆', '工具调用', 'ReAct 轨迹', 'Markdown / 公式']
const composerRef = ref<InstanceType<typeof ChatComposer> | null>(null)

const {
  activeSessionLabel,
  assistantMessageCount,
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
  userMessageCount,
} = useAgentChat()

function usePromptExample(example: string) {
  inputMessage.value = example
  void nextTick(() => {
    composerRef.value?.adjustInputHeight()
    composerRef.value?.focusInput()
  })
}
</script>

<template>
  <main class="app-shell">
    <AgentSidebar
      :active-session-label="activeSessionLabel"
      :is-loading="isLoading"
      :is-sessions-loading="isSessionsLoading"
      :is-sessions-open="isSessionsOpen"
      :latest-step-count="latestStepCount"
      :message-count="messages.length"
      :session-id="sessionId"
      :sessions="sessions"
      :sessions-error="sessionsError"
      @open-session="openSession"
      @start-new-session="startNewSession"
      @toggle-sessions="isSessionsOpen = !isSessionsOpen"
    />

    <section class="workspace">
      <TopBar
        :assistant-message-count="assistantMessageCount"
        :user-message-count="userMessageCount"
      />

      <MessageList
        ref="messageListRef"
        :capability-tags="capabilityTags"
        :is-loading="isLoading"
        :messages="messages"
        :prompt-examples="promptExamples"
        @use-prompt-example="usePromptExample"
      />

      <ErrorNotice :error-message="errorMessage" />

      <ChatComposer
        ref="composerRef"
        v-model="inputMessage"
        :can-send="canSend"
        :is-loading="isLoading"
        @send-message="sendMessage"
      />
    </section>
  </main>
</template>

<style>
* {
  box-sizing: border-box;
}

body {
  margin: 0;
  min-width: 320px;
  min-height: 100vh;
  color: #23313d;
  background:
    radial-gradient(circle at 12% 12%, rgba(90, 176, 156, 0.2), transparent 28%),
    radial-gradient(circle at 88% 18%, rgba(226, 128, 87, 0.2), transparent 25%),
    linear-gradient(135deg, #f7f3ea 0%, #edf4f1 45%, #f8efe7 100%);
  font-family:
    'Aptos', 'Segoe UI Variable', 'Microsoft YaHei UI', ui-sans-serif, system-ui, -apple-system,
    BlinkMacSystemFont, sans-serif;
}

button,
textarea {
  font: inherit;
}

.app-shell {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  min-height: 100vh;
  position: relative;
}

.sidebar {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 28px 22px;
  color: #f9fbf8;
  background:
    linear-gradient(160deg, rgba(22, 48, 46, 0.98), rgba(32, 43, 54, 0.96)),
    linear-gradient(180deg, #17312f 0%, #1e2a36 100%);
  border-right: 1px solid rgba(255, 255, 255, 0.22);
  box-shadow: 18px 0 50px rgba(44, 63, 66, 0.18);
}

.brand {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 4px 2px 16px;
}

.brand-mark {
  display: grid;
  width: 48px;
  height: 48px;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, 0.38);
  border-radius: 14px;
  color: #1d3734;
  background: linear-gradient(145deg, #eaf8e8, #f6d2a8);
  box-shadow: 0 18px 34px rgba(0, 0, 0, 0.22);
  font-weight: 900;
}

.eyebrow,
.label {
  margin: 0 0 8px;
  color: rgba(239, 247, 243, 0.7);
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
  font-size: 30px;
  line-height: 1;
  letter-spacing: 0;
}

.panel {
  display: grid;
  gap: 12px;
  padding: 17px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 18px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.12), rgba(255, 255, 255, 0.07));
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.16),
    0 18px 38px rgba(0, 0, 0, 0.12);
  backdrop-filter: blur(16px);
}

.panel code {
  overflow-wrap: anywhere;
  color: #f9fbf8;
  font-size: 13px;
  line-height: 1.55;
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.12);
}

.panel p,
.empty-sessions {
  margin-bottom: 0;
  color: rgba(239, 247, 243, 0.72);
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
  border: 1px solid rgba(255, 255, 255, 0.22);
  border-radius: 999px;
  color: #f9fbf8;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.14), rgba(255, 255, 255, 0.07));
  cursor: pointer;
  transition:
    background-color 180ms ease,
    border-color 180ms ease,
    color 180ms ease,
    transform 180ms ease,
    box-shadow 180ms ease;
}

.secondary-button:hover {
  border-color: rgba(246, 210, 168, 0.75);
  box-shadow: 0 12px 24px rgba(0, 0, 0, 0.2);
  transform: translateY(-1px);
}

.primary-lite {
  color: #1f3b37;
  background: linear-gradient(180deg, #f9d9ad, #efb36f);
  border-color: rgba(255, 222, 178, 0.8);
  font-weight: 800;
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
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 14px;
  color: #f7fbf8;
  background: rgba(255, 255, 255, 0.08);
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

.session-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.session-title small {
  flex: 0 0 auto;
  padding: 2px 7px;
  border-radius: 999px;
  color: #193b35;
  background: #f6d2a8;
  font-size: 11px;
  font-weight: 800;
}

.session-item span,
.session-item small {
  color: rgba(239, 247, 243, 0.68);
}

.session-item:hover {
  transform: translateY(-1px);
  border-color: rgba(246, 210, 168, 0.48);
  background: rgba(255, 255, 255, 0.12);
  box-shadow: 0 12px 24px rgba(0, 0, 0, 0.14);
}

.session-item.active {
  border-color: rgba(246, 210, 168, 0.72);
  background:
    linear-gradient(180deg, rgba(246, 210, 168, 0.24), rgba(255, 255, 255, 0.1));
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.08),
    0 16px 30px rgba(0, 0, 0, 0.14);
}

.session-item.muted {
  cursor: default;
}

.status-line {
  display: flex;
  align-items: center;
  gap: 10px;
}

.status-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #9ee6b9;
  box-shadow: 0 0 0 6px rgba(158, 230, 185, 0.12);
}

.status-dot.running {
  background: #f3bd6b;
  box-shadow: 0 0 0 6px rgba(243, 189, 107, 0.16);
  animation: status-pulse 1.2s ease-in-out infinite;
}

.metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin: 0;
}

.metrics div {
  padding: 10px 8px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.09);
}

.metrics dt {
  color: rgba(239, 247, 243, 0.65);
  font-size: 11px;
}

.metrics dd {
  margin: 4px 0 0;
  color: #fbfff9;
  font-size: 20px;
  font-weight: 800;
}

.workspace {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto auto;
  min-width: 0;
  height: 100vh;
  background:
    linear-gradient(90deg, rgba(28, 59, 54, 0.035) 1px, transparent 1px),
    linear-gradient(180deg, rgba(28, 59, 54, 0.028) 1px, transparent 1px),
    linear-gradient(180deg, rgba(255, 253, 247, 0.9), rgba(239, 246, 242, 0.86));
  background-size:
    34px 34px,
    34px 34px,
    auto;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  min-height: 82px;
  padding: 18px 28px;
  border-bottom: 1px solid rgba(50, 72, 70, 0.1);
  background: rgba(255, 253, 247, 0.76);
  backdrop-filter: blur(18px);
}

.topbar h2 {
  margin-bottom: 4px;
  font-size: 20px;
  color: #1e332f;
  letter-spacing: 0;
}

.topbar p {
  margin-bottom: 0;
  color: #657771;
}

.topbar-stats {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.topbar-stats span {
  min-width: 76px;
  padding: 8px 10px;
  border: 1px solid rgba(37, 72, 66, 0.12);
  border-radius: 999px;
  color: #36544f;
  background: rgba(255, 255, 255, 0.72);
  text-align: center;
  font-size: 13px;
  box-shadow: 0 10px 24px rgba(48, 72, 70, 0.08);
}

.message-list {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-height: 0;
  padding: 32px;
  overflow-y: auto;
  scrollbar-color: rgba(67, 104, 95, 0.3) transparent;
}

.empty-state {
  width: min(720px, 100%);
  margin: auto;
  text-align: left;
  padding: 34px;
  border: 1px solid rgba(37, 72, 66, 0.1);
  border-radius: 28px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.88), rgba(255, 248, 238, 0.72)),
    rgba(255, 255, 255, 0.7);
  box-shadow: 0 28px 80px rgba(54, 82, 76, 0.16);
}

.empty-hero {
  position: relative;
  min-height: 142px;
  padding-right: 150px;
}

.empty-orbit {
  position: absolute;
  top: 6px;
  right: 8px;
  width: 120px;
  height: 120px;
  border: 1px solid rgba(44, 114, 98, 0.18);
  border-radius: 50%;
  background:
    radial-gradient(circle at 50% 50%, #2d7766 0, #2d7766 10px, transparent 11px),
    radial-gradient(circle at 80% 25%, #eda969 0, #eda969 8px, transparent 9px),
    radial-gradient(circle at 24% 76%, #8fc9ad 0, #8fc9ad 7px, transparent 8px);
  box-shadow:
    inset 0 0 0 18px rgba(45, 119, 102, 0.05),
    inset 0 0 0 42px rgba(237, 169, 105, 0.06);
  animation: orbit-breathe 4s ease-in-out infinite;
}

.empty-state h3 {
  margin-bottom: 8px;
  font-size: 34px;
  color: #1f3732;
  letter-spacing: 0;
}

.empty-state p {
  color: #657771;
  line-height: 1.7;
}

.capability-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 18px;
}

.capability-tags span {
  padding: 6px 10px;
  border: 1px solid rgba(47, 138, 114, 0.12);
  border-radius: 999px;
  color: #2f6258;
  background: rgba(232, 244, 237, 0.74);
  font-size: 13px;
  font-weight: 700;
}

.prompt-examples {
  display: grid;
  gap: 10px;
  margin-top: 20px;
}

.prompt-examples button {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 48px;
  padding: 12px 14px;
  border: 1px solid rgba(37, 72, 66, 0.1);
  border-radius: 16px;
  color: #2b423e;
  background: rgba(255, 255, 255, 0.7);
  text-align: left;
  cursor: pointer;
  box-shadow: 0 10px 22px rgba(54, 82, 76, 0.07);
  transition:
    border-color 180ms ease,
    background-color 180ms ease,
    box-shadow 180ms ease,
    transform 180ms ease;
}

.prompt-examples button span {
  flex: 0 0 auto;
  padding: 4px 8px;
  border-radius: 999px;
  color: #1f473f;
  background: #e8f4ed;
  font-size: 12px;
  font-weight: 800;
}

.prompt-examples button:hover {
  transform: translateY(-2px);
  border-color: rgba(45, 119, 102, 0.28);
  background: #ffffff;
  box-shadow: 0 16px 30px rgba(54, 82, 76, 0.12);
}

.message {
  display: grid;
  grid-template-columns: 40px minmax(0, 760px);
  gap: 12px;
  align-items: start;
}

.message.user {
  justify-content: end;
  grid-template-columns: minmax(0, 560px) 40px;
}

.message.user .avatar {
  grid-column: 2;
  grid-row: 1;
}

.message.user .bubble {
  grid-column: 1;
  grid-row: 1;
}

.message.user .bubble {
  color: #f8fffb;
  background: linear-gradient(160deg, #2f8a72, #216556);
  border-color: rgba(45, 119, 102, 0.16);
}

.avatar {
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  border-radius: 14px;
  color: #ffffff;
  background: linear-gradient(160deg, #eaa86d, #b96d4d);
  box-shadow: 0 12px 24px rgba(89, 76, 63, 0.16);
  font-size: 13px;
  font-weight: 800;
}

.message.user .avatar {
  background: linear-gradient(160deg, #2f8a72, #216556);
}

.bubble {
  min-width: 0;
  padding: 16px 18px;
  border: 1px solid rgba(37, 72, 66, 0.08);
  border-radius: 20px;
  color: #2b3a36;
  background: rgba(255, 255, 255, 0.82);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.72),
    0 16px 38px rgba(54, 82, 76, 0.11);
}

.markdown-body {
  color: #263a35;
  font-family:
    'LXGW WenKai Screen', '霞鹜文楷', 'Microsoft YaHei UI', 'PingFang SC',
    'Hiragino Sans GB', sans-serif;
  font-size: 16px;
  font-weight: 400;
  line-height: 1.82;
  letter-spacing: 0;
}

.bubble p {
  margin-bottom: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.65;
  color: inherit;
}

.markdown-body p {
  margin: 0 0 12px;
  white-space: normal;
}

.markdown-body p:last-child {
  margin-bottom: 0;
}

.markdown-body h1,
.markdown-body h2,
.markdown-body h3,
.markdown-body h4 {
  margin: 18px 0 10px;
  color: #18342f;
  font-family:
    'Aptos Display', 'Microsoft YaHei UI', 'PingFang SC', 'Hiragino Sans GB',
    sans-serif;
  font-weight: 760;
  line-height: 1.35;
}

.markdown-body h1:first-child,
.markdown-body h2:first-child,
.markdown-body h3:first-child,
.markdown-body h4:first-child {
  margin-top: 0;
}

.markdown-body h1 {
  font-size: 24px;
}

.markdown-body h2 {
  font-size: 21px;
}

.markdown-body h3 {
  font-size: 18px;
}

.markdown-body strong {
  color: #173a33;
  font-weight: 700;
}

.markdown-body code {
  padding: 2px 6px;
  border-radius: 7px;
  color: #28675b;
  background: rgba(47, 138, 114, 0.08);
  font-family:
    'Cascadia Code', 'JetBrains Mono', 'SFMono-Regular', Consolas, monospace;
  font-size: 0.88em;
  font-weight: 500;
}

.markdown-body pre {
  margin: 12px 0 0;
  padding: 14px;
  border-radius: 14px;
  overflow-x: auto;
  background: rgba(30, 51, 47, 0.055);
}

.markdown-body pre code {
  padding: 0;
  background: transparent;
}

.markdown-body blockquote {
  margin: 12px 0 0;
  padding: 8px 0 8px 14px;
  border-left: 3px solid rgba(47, 138, 114, 0.26);
  color: #667b74;
}

.markdown-body a {
  color: #1f7b69;
}

.markdown-body ul,
.markdown-body ol {
  margin: 10px 0 0;
  padding-left: 24px;
}

.markdown-body li {
  margin: 5px 0;
}

.markdown-body hr {
  height: 1px;
  margin: 18px 0;
  border: 0;
  background: rgba(37, 72, 66, 0.12);
}

.markdown-body table {
  display: block;
  width: 100%;
  margin-top: 12px;
  overflow-x: auto;
  border-collapse: collapse;
}

.markdown-body th,
.markdown-body td {
  padding: 9px 10px;
  border: 1px solid rgba(37, 72, 66, 0.1);
  text-align: left;
  vertical-align: top;
}

.markdown-body th {
  color: #1f473f;
  background: rgba(232, 244, 237, 0.72);
  font-weight: 800;
}

.markdown-body td {
  background: rgba(255, 255, 255, 0.44);
}

.markdown-body .katex-display {
  margin: 10px 0;
  overflow-x: auto;
  overflow-y: hidden;
}

.markdown-body .katex {
  font-size: 1.06em;
}

.loading {
  color: #60736d;
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
  background: #2f8a72;
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
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid rgba(37, 72, 66, 0.1);
}

.steps summary {
  cursor: pointer;
  color: #2c6258;
  font-weight: 700;
  list-style-position: outside;
}

.step-timeline {
  display: grid;
  gap: 12px;
  margin-top: 14px;
}

.timeline-item {
  position: relative;
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr);
  gap: 10px;
}

.timeline-item:not(:last-child)::after {
  position: absolute;
  top: 32px;
  bottom: -12px;
  left: 14px;
  width: 1px;
  background: rgba(47, 138, 114, 0.18);
  content: '';
}

.timeline-index {
  position: relative;
  z-index: 1;
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border: 1px solid rgba(47, 138, 114, 0.2);
  border-radius: 50%;
  color: #246657;
  background: #f3faf5;
  font-size: 13px;
  font-weight: 800;
}

.timeline-content {
  padding: 11px 12px;
  border: 1px solid rgba(47, 138, 114, 0.1);
  border-radius: 16px;
  background: rgba(247, 253, 249, 0.68);
}

.timeline-content strong {
  display: block;
  margin-bottom: 6px;
  color: #1e4f45;
  font-size: 14px;
}

.step-block b {
  display: block;
  margin-bottom: 2px;
  color: #2d7766;
  font-size: 11px;
  text-transform: uppercase;
}

.step-block {
  display: block;
  margin: 7px 0 0;
  overflow-wrap: anywhere;
  line-height: 1.6;
}

code.step-block {
  padding: 8px 10px;
  border-radius: 12px;
  color: #244c45;
  background: rgba(47, 138, 114, 0.08);
  font-family:
    'Cascadia Code', 'JetBrains Mono', 'SFMono-Regular', Consolas, monospace;
  font-size: 13px;
}

small.step-block {
  color: #667b74;
}

.stop-reason {
  display: block;
  margin-top: 10px;
  color: #c4584f;
}

.error {
  display: grid;
  gap: 6px;
  margin: 0 32px 12px;
  padding: 13px 15px;
  border: 1px solid rgba(190, 83, 73, 0.18);
  border-radius: 16px;
  color: #9b352f;
  background: rgba(255, 235, 229, 0.8);
  box-shadow: 0 14px 32px rgba(150, 78, 66, 0.12);
}

.error strong {
  color: #803029;
}

.error span {
  color: #8f514b;
  line-height: 1.55;
}

.error code {
  width: fit-content;
  padding: 3px 7px;
  border-radius: 8px;
  color: #8a3730;
  background: rgba(255, 255, 255, 0.6);
  font-size: 12px;
}

.composer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 112px;
  gap: 12px;
  padding: 18px 32px 26px;
  border-top: 1px solid rgba(37, 72, 66, 0.1);
  background: rgba(255, 253, 247, 0.78);
  backdrop-filter: blur(18px);
}

.input-wrap {
  position: relative;
  min-width: 0;
}

textarea {
  width: 100%;
  min-height: 72px;
  max-height: 180px;
  resize: vertical;
  border: 1px solid rgba(37, 72, 66, 0.12);
  border-radius: 20px;
  padding: 12px 14px 28px;
  color: #243833;
  background: rgba(255, 255, 255, 0.86);
  outline: none;
  box-shadow: 0 12px 32px rgba(54, 82, 76, 0.08);
}

.input-wrap span {
  position: absolute;
  right: 12px;
  bottom: 9px;
  color: #7a8b85;
  font-size: 12px;
  pointer-events: none;
}

textarea:focus {
  border-color: rgba(47, 138, 114, 0.42);
  box-shadow:
    0 0 0 4px rgba(47, 138, 114, 0.12),
    0 16px 36px rgba(54, 82, 76, 0.1);
}

.composer button {
  min-height: 72px;
  border: 0;
  border-radius: 20px;
  color: #ffffff;
  background: linear-gradient(160deg, #2f8a72, #226b5b);
  font-weight: 800;
  letter-spacing: 0.01em;
  cursor: pointer;
  box-shadow: 0 16px 28px rgba(47, 138, 114, 0.22);
  transition:
    transform 180ms ease,
    box-shadow 180ms ease,
    filter 180ms ease;
}

.composer button:hover:not(:disabled) {
  transform: translateY(-1px);
  filter: brightness(1.04);
  box-shadow: 0 20px 36px rgba(47, 138, 114, 0.3);
}

.composer button:disabled {
  cursor: not-allowed;
  opacity: 0.62;
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

  .topbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .topbar-stats {
    justify-content: stretch;
    width: 100%;
  }

  .topbar-stats span {
    flex: 1;
  }

  .message {
    grid-template-columns: 32px minmax(0, 1fr);
  }

  .message.user {
    grid-template-columns: minmax(0, 1fr) 32px;
  }

  .avatar {
    width: 32px;
    height: 32px;
    border-radius: 11px;
  }

  .empty-state {
    padding: 22px;
    border-radius: 22px;
  }

  .empty-hero {
    min-height: 0;
    padding-right: 0;
  }

  .empty-orbit {
    position: relative;
    display: block;
    top: auto;
    right: auto;
    width: 86px;
    height: 86px;
    margin-bottom: 18px;
  }

  .empty-state h3 {
    font-size: 27px;
  }

  .composer {
    grid-template-columns: 1fr;
  }

  .composer button {
    min-height: 48px;
  }
}

@keyframes orbit-breathe {
  0%,
  100% {
    transform: translateY(0) scale(1);
  }

  50% {
    transform: translateY(-4px) scale(1.03);
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

@keyframes status-pulse {
  0%,
  100% {
    transform: scale(1);
  }

  50% {
    transform: scale(1.18);
  }
}
</style>
