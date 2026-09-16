<script setup lang="ts">
import MarkdownIt from 'markdown-it'
import markdownItKatex from 'markdown-it-katex'
import type { ChatMessage } from '@/types/agent'
import PlanTracePanel from './PlanTracePanel.vue'
import ReactTimeline from './ReactTimeline.vue'

withDefaults(defineProps<{
  message: ChatMessage
  showDiagnostics?: boolean
}>(), {
  showDiagnostics: true,
})

const markdown = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true,
})

markdown.use(markdownItKatex, {
  throwOnError: false,
  errorColor: '#b43c2d',
})

function renderAssistantContent(content: string) {
  return markdown.render(normalizeMathSyntax(content))
}

function normalizeMathSyntax(content: string) {
  return content
    .replace(/\r\n/g, '\n')
    .replace(/\\\[((?:.|\n)*?)\\\]/g, (_, formula: string) => `\n$$\n${formula.trim()}\n$$\n`)
    .replace(/\\\(((?:.|\n)*?)\\\)/g, (_, formula: string) => `$${formula.trim()}$`)
}

function executionModeLabel(mode: ChatMessage['executionMode']) {
  switch (mode) {
    case 'REACT':
      return 'ReAct'
    case 'PLAN_EXECUTE':
      return 'Plan-and-Execute'
    case 'MULTI_AGENT':
      return 'Multi Agent'
    default:
      return ''
  }
}
</script>

<template>
  <article class="message" :class="message.role">
    <div class="avatar">{{ message.role === 'user' ? 'U' : 'A' }}</div>
    <div class="bubble">
      <template v-if="message.role === 'assistant'">
        <span v-if="showDiagnostics && message.executionMode" class="agent-mode-badge">
          {{ executionModeLabel(message.executionMode) }}
        </span>
        <!--
          思考过程：模型开启思考模式并真的返回 reasoning_content 时才渲染。
          没有思考内容时整块不出现，不做空占位。
        -->
        <details v-if="message.thinking?.trim()" class="thinking-block">
          <summary>思考过程</summary>
          <div class="thinking-body">{{ message.thinking }}</div>
        </details>
        <div class="markdown-body" v-html="renderAssistantContent(message.content)"></div>
      </template>
      <p v-else>{{ message.content }}</p>

      <ReactTimeline v-if="showDiagnostics && message.steps?.length" :steps="message.steps" />
      <PlanTracePanel
        v-if="showDiagnostics && (message.planTrace || message.workerTraces?.length)"
        :plan-trace="message.planTrace"
        :worker-traces="message.workerTraces"
      />

      <small v-if="showDiagnostics && message.stopReason" class="stop-reason">{{ message.stopReason }}</small>
    </div>
  </article>
</template>

<style scoped>
/* 思考区默认折叠，点开才看内容：思考往往比答案还长，展开显示会淹没正文 */
.thinking-block {
  margin-bottom: 0.6em;
  border: 1px dashed rgba(100, 116, 139, 0.45);
  border-radius: 6px;
  background: rgba(100, 116, 139, 0.06);
  font-size: 0.88em;
}

.thinking-block > summary {
  padding: 0.4em 0.7em;
  cursor: pointer;
  color: #475569;
  user-select: none;
}

.thinking-body {
  padding: 0 0.7em 0.6em;
  color: #475569;
  line-height: 1.7;
  /* 保留模型输出的换行，同时限高滚动，避免思考长文顶开整个窗口 */
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 14em;
  overflow-y: auto;
}
</style>
