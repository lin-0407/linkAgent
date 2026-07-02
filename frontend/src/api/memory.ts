/**
 * 长期记忆 API 层，对应后端 MemoryController (/api/memory)。
 * 提供长期记忆的写入和查询，用于在 Agent 对话中持久化重要上下文片段。
 */
import type { LongTermMemoryRecord, LongTermMemorySavePayload } from '@/types/memory'
import { del, get, post } from './http'

/** 保存一条长期记忆，返回保存后的完整记录 */
export function saveLongTermMemory(payload: LongTermMemorySavePayload) {
  return post<LongTermMemoryRecord>('/memory/long-term', payload)
}

/** 列出某用户的全部长期记忆，管理页需要完整数据用于本地搜索和删除 */
export function listLongTermMemories(userId: string) {
  return get<LongTermMemoryRecord[]>(`/memory/long-term/users/${encodeURIComponent(userId)}`)
}

/** 按用户 + 记忆键获取单条长期记忆 */
export function getLongTermMemory(userId: string, memoryKey: string) {
  return get<LongTermMemoryRecord>(
    `/memory/long-term/users/${encodeURIComponent(userId)}/keys/${encodeURIComponent(memoryKey)}`,
  )
}

/** 软删除一条长期记忆 */
export function deleteLongTermMemory(userId: string, memoryKey: string) {
  return del(`/memory/long-term/users/${encodeURIComponent(userId)}/keys/${encodeURIComponent(memoryKey)}`)
}
