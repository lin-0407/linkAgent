import type { ReferenceVideoAnalysisContext } from '@/types/knowledge'

// 用全局事件连接案例库和 AI 交互台，是为了避免把两个顶层组件强行改成父子传参。
// 这里的事件只传已经由后端回查过的事实上下文，不在前端再次做检索。
export const KNOWLEDGE_VIDEO_CONTEXT_EVENT = 'link-agent:knowledge-video-context'

export type KnowledgeVideoContextEventDetail = {
  query: string
  context: ReferenceVideoAnalysisContext
}
