/**
 * 长期记忆 API 层，对应后端 MemoryController (/api/memory)。
 * 提供长期记忆管理页当前使用的列表、删除与撤销删除能力。
 * 记忆身份是数据库主键 id（后端设计文档 D13 / E1）：memoryKey 只作分类展示，
 * 同一 key 下可能有多条记忆，因此删除与恢复一律按 id 调用。
 */
import type { LongTermMemoryRecord } from '@/types/memory'
import { del, get, post } from './http'

/** 列出某用户的全部长期记忆，管理页需要完整数据用于本地搜索和删除 */
export function listLongTermMemories(userId: string) {
  return get<LongTermMemoryRecord[]>(`/memory/long-term/users/${encodeURIComponent(userId)}`)
}

/** 用户主动删除一条长期记忆（软删除，可撤销） */
export function deleteLongTermMemory(userId: string, memoryId: number) {
  return del(`/memory/long-term/users/${encodeURIComponent(userId)}/memories/${memoryId}`)
}

/** 撤销删除，并返回数据库中的当前记忆，避免用旧快照覆盖并发更新 */
export function restoreLongTermMemory(userId: string, memoryId: number) {
  return post<LongTermMemoryRecord>(
    `/memory/long-term/users/${encodeURIComponent(userId)}/memories/${memoryId}/restore`,
  )
}
