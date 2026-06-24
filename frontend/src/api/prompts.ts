/**
 * 提示词模板 API
 * 对应后端 PromptTemplateController (/api/prompt-templates)
 */
import { get, put } from './http'
import type { PromptTemplate } from '@/types/prompts'

/** 列出全部提示词模板 */
export function listPromptTemplates(): Promise<PromptTemplate[]> {
  return get<PromptTemplate[]>('/prompt-templates')
}

/** 按 key 获取单条提示词正文 */
export function getPromptContent(key: string): Promise<string> {
  return get<string>(`/prompt-templates/${key}`)
}

/** 更新提示词正文（热更新，即时生效） */
export function updatePromptContent(key: string, content: string): Promise<void> {
  return put<void>(`/prompt-templates/${key}`, { content })
}
