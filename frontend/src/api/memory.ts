/**
 * 长期记忆 API 层，对应后端 MemoryController (/api/memory)。
 * 提供长期记忆管理页当前使用的列表与软删除能力。
 */
import type { LongTermMemoryRecord } from '@/types/memory'
import { del, get } from './http'

/** 列出某用户的全部长期记忆，管理页需要完整数据用于本地搜索和删除 */
export function listLongTermMemories(userId: string) {
  return get<LongTermMemoryRecord[]>(`/memory/long-term/users/${encodeURIComponent(userId)}`)
}

/** 软删除一条长期记忆 */
export function deleteLongTermMemory(userId: string, memoryKey: string) {
  return del(`/memory/long-term/users/${encodeURIComponent(userId)}/keys/${encodeURIComponent(memoryKey)}`)
}
