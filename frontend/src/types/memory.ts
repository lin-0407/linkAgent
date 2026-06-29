/**
 * 长期记忆前端类型，对应后端 MemoryController 接口和 LongTermMemory DTO。
 * 单独成文件是为了让记忆域的类型与 agent/creator 等业务域保持解耦。
 */

/** 长期记忆保存请求，对应后端 LongTermMemorySaveRequest */
export type LongTermMemorySavePayload = {
  userId: string
  memoryKey: string
  content: string
  sourceSessionId?: string
}

/** 长期记忆记录，对应后端 LongTermMemoryResponse */
export type LongTermMemoryRecord = {
  id: number
  userId: string
  memoryKey: string
  content: string
  sourceSessionId: string | null
  embeddingId: string | null
  createTime: string
  updateTime: string
}
