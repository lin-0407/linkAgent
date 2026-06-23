export const CREATOR_WORKSPACE_STORAGE_KEY = 'link-agent-creator-workspace'

// 项目列表和创作台不做强耦合，统一通过本地状态记录“当前视频项目”。
export function persistCreatorWorkspaceTask(taskId: string | null) {
  const normalizedTaskId = taskId?.trim()
  localStorage.setItem(
    CREATOR_WORKSPACE_STORAGE_KEY,
    JSON.stringify(normalizedTaskId ? { taskId: normalizedTaskId } : {}),
  )
}
