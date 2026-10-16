/**
 * 长期记忆前端类型，对应后端 MemoryController 接口和 LongTermMemory DTO。
 * 单独成文件是为了让记忆域的类型与 agent/creator 等业务域保持解耦。
 */

/** 长期记忆记录，对应后端 LongTermMemoryResponse */
export type LongTermMemoryRecord = {
  /** 记忆身份（数据库主键）：删除与撤销都按它调用；memoryKey 只是分类标签，同一 key 可能有多条 */
  id: number
  userId: string
  memoryKey: string
  content: string
  /** 重要性分数（1-100，0 表示未打分）：压缩时抽取的高分事实点才有值 */
  importance: number | null
  sourceSessionId: string | null
  embeddingId: string | null
  createTime: string
  updateTime: string
}
