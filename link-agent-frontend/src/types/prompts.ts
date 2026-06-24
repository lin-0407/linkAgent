/** 提示词模板，对应后端 PromptTemplate 模型 */
export interface PromptTemplate {
  id: number
  promptKey: string
  promptType: 'SYSTEM' | 'USER'
  scene: string
  content: string
  description: string | null
  createTime: string
  updateTime: string
}

/** 按场景分组后的提示词列表 */
export interface PromptGroup {
  scene: string
  items: PromptTemplate[]
}
