<script setup lang="ts">
import MarkdownIt from 'markdown-it'
import markdownItKatex from 'markdown-it-katex'
import type { ChatMessage } from '@/types/agent'
import ReactTimeline from './ReactTimeline.vue'

defineProps<{
  message: ChatMessage
}>()

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
</script>

<template>
  <article class="message" :class="message.role">
    <div class="avatar">{{ message.role === 'user' ? 'U' : 'A' }}</div>
    <div class="bubble">
      <template v-if="message.role === 'assistant'">
        <div class="markdown-body" v-html="renderAssistantContent(message.content)"></div>
      </template>
      <p v-else>{{ message.content }}</p>

      <ReactTimeline v-if="message.steps?.length" :steps="message.steps" />

      <small v-if="message.stopReason" class="stop-reason">{{ message.stopReason }}</small>
    </div>
  </article>
</template>
