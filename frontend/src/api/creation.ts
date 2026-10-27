import { get, post } from './http'

export type CreationVersion = {
  versionId: string
  versionNo: number
  versionName: string
  status: string
  durationMs: number | null
  mediaAvailable: boolean
  contextAvailable: boolean
  reviewReady: boolean
  selected: boolean
  published: boolean
  createTime: string
}

export type CreationVersionList = {
  total: number
  page: number
  pageSize: number
  workspaceRevision: number
  replacementAllowed: boolean
  versions: CreationVersion[]
}

export type CreationWorkspaceSnapshot = {
  enabled: boolean
  workspaceRevision: number
  currentVideoVersionId: string | null
  publishedVideoVersionId: string | null
  versionContext: Record<string, unknown> | null
}

export function getCreationVersions(taskId: string, page = 1) {
  return get<CreationVersionList>(`/creator/tasks/${encodeURIComponent(taskId)}/creation/versions?page=${page}&pageSize=10`)
}

export function selectCreationVersion(taskId: string, versionId: string, expectedWorkspaceRevision: number) {
  return post<CreationWorkspaceSnapshot>(
    `/creator/tasks/${encodeURIComponent(taskId)}/creation/versions/${encodeURIComponent(versionId)}:select`,
    { expectedWorkspaceRevision },
  )
}
