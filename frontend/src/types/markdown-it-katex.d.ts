declare module 'markdown-it-katex' {
  import type MarkdownIt from 'markdown-it'

  type MarkdownItPlugin = (md: MarkdownIt, options?: Record<string, unknown>) => void

  const markdownItKatex: MarkdownItPlugin
  export default markdownItKatex
}
