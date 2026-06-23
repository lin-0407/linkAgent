/**
 * 创作台编排器 —— 唯一的跨域业务流入口。
 *
 * 架构契约：
 *  - composable = 纯数据 + API 调用（不关心"然后做什么"）
 *  - orchestrator = 跨域流程 + store 写入（"创建任务后 → 加载偏好 → 启动工作流"）
 *  - 组件 = UI 渲染 + 事件 → orchestrator 调用（不写业务逻辑）
 *
 * 所有 activeStep 变更、跨模块数据加载顺序均在此收敛。
 *
 * 组件只允许通过此 orchestrator 驱动业务流，禁止直接操作 store.activeStep /
 * taskModule.selectedTask / workflowModule.workflowSession 等跨域状态。
 */
import type { Ref } from 'vue'

// ── 模块接口（最小契约）──

interface TaskModule {
  submitTask: () => Promise<{ taskId: string; userId?: string; videoType?: string } | null>
  submitUpdateTask: () => Promise<{ taskId: string; userId?: string; videoType?: string } | null>
  loadTask: (taskId: string) => Promise<{ taskId: string; userId?: string; videoType?: string } | null>
  refreshTasks: () => Promise<void>
  resetTaskForm: () => void
  fillTaskForm: (task: Record<string, unknown>) => void
  getMaterialContent: (task: Record<string, unknown>, materialType: string) => string
  hasTaskMaterialChanged: (task: Record<string, unknown>) => boolean
  selectedTaskId: Ref<string>
  hasSelectedTaskMaterials: Ref<boolean>
}

interface WorkflowModule {
  startWorkflow: (taskId: string) => Promise<void>
  runAnalyze: (taskId: string, sessionId: string, payload: Record<string, unknown>) => Promise<void>
  confirmSuggestion: (taskId: string, sessionId: string, payload: Record<string, unknown>) => Promise<void>
  sendSupplement: (taskId: string, sessionId: string, payload: Record<string, unknown>) => Promise<void>
  disconnect: () => void
  refreshWorkflowSteps: (taskId: string) => Promise<void>
  suggestion: Ref<unknown>
  hasConfirmedPrePublish: Ref<boolean>
  canRunPrePublishAnalyze: Ref<boolean>
  canConfirmPrePublish: Ref<boolean>
  canSendWorkflowMessage: Ref<boolean>
  workflowSession: Ref<{ status?: string; sessionId?: string; confirmedResultId?: string; errorMessage?: string; messages?: unknown[] } | null>
}

interface FeedbackModule {
  loadFeedbackData: (taskId: string) => Promise<void>
  submitFeedback: (taskId: string) => Promise<void>
  importFeedbackFile: (taskId: string) => Promise<void>
  fetchFeedbackByBv: (taskId: string) => Promise<void>
  runAnalyze: (taskId: string, payload: Record<string, unknown>) => Promise<void>
  askChat: (taskId: string) => Promise<unknown>
  downloadReportMarkdown: (taskId: string) => Promise<void>
  loadEvidenceIndexStatus: (taskId: string) => Promise<void>
  rebuildEvidenceIndex: (taskId: string) => Promise<void>
  clearFeedbackChatState: () => void
  canEnterFeedback: Ref<boolean>
  canRunFeedbackAnalyze: Ref<boolean>
  canAskFeedbackChat: Ref<boolean>
}

interface ContextModule {
  loadCreatorPreferences: (userId?: string) => Promise<void>
  loadCreatorContextTerms: (userId?: string, videoType?: string) => Promise<void>
}

interface UsageModule {
  refreshUsageStats: (page?: number, reportError?: boolean) => Promise<void>
}

interface CreatorStore {
  activeStep: Ref<string>
  setSelectedTaskId: (taskId: string | null) => void
  selectedTaskId: Ref<string | null>
}

export interface OrchestratorContext {
  taskModule: TaskModule
  workflowModule: WorkflowModule
  feedbackModule: FeedbackModule
  contextModule: ContextModule
  usageModule: UsageModule
  store: CreatorStore
  errorRef: Ref<string>
  successRef: Ref<string>
}

export function createCreatorOrchestrator(ctx: OrchestratorContext) {
  const { taskModule, workflowModule, feedbackModule, contextModule, usageModule, store, errorRef, successRef } = ctx

  // ═══════════════════════════════ TASK ═══════════════════════════════

  async function submitTask() {
    errorRef.value = ''
    const task = await taskModule.submitTask()
    if (!task) return
    store.setSelectedTaskId(task.taskId)
    await Promise.all([
      contextModule.loadCreatorPreferences(task.userId),
      contextModule.loadCreatorContextTerms(task.userId, task.videoType),
    ])
    store.activeStep.value = 'prePublish'
    await workflowModule.startWorkflow(task.taskId)
  }

  async function updateTask() {
    errorRef.value = ''
    const task = await taskModule.submitUpdateTask()
    if (!task) return
    store.setSelectedTaskId(task.taskId)
    await Promise.all([
      contextModule.loadCreatorPreferences(task.userId),
      contextModule.loadCreatorContextTerms(task.userId, task.videoType),
    ])
    await taskModule.refreshTasks()
  }

  async function selectTask(taskId: string) {
    errorRef.value = ''
    const task = await taskModule.loadTask(taskId)
    if (!task) return
    store.setSelectedTaskId(task.taskId)
    store.activeStep.value = 'prePublish'
    workflowModule.disconnect()
    await Promise.allSettled([
      contextModule.loadCreatorPreferences(task.userId),
      contextModule.loadCreatorContextTerms(task.userId, task.videoType),
      feedbackModule.loadFeedbackData(task.taskId),
      usageModule.refreshUsageStats(1, false),
    ])
    await workflowModule.startWorkflow(task.taskId)
  }

  function startCreateTask() {
    store.activeStep.value = 'task'
    taskModule.resetTaskForm()
  }

  async function startEditTask(taskId: string) {
    store.activeStep.value = 'task'
    return selectTask(taskId)
  }

  async function confirmDeleteTask() {
    await taskModule.refreshTasks()
  }

  // ═══════════════════════════════ WORKFLOW ═══════════════════════════════

  /** 确保工作流已启动（幂等），然后运行发布前分析 */
  async function runPrePublishAnalyze(taskId: string, sessionId: string, payload: Record<string, unknown>) {
    await workflowModule.runAnalyze(taskId, sessionId, payload)
    await usageModule.refreshUsageStats(1, false)
  }

  async function confirmPrePublish(taskId: string, sessionId: string, payload: Record<string, unknown>) {
    await workflowModule.confirmSuggestion(taskId, sessionId, payload)
    store.activeStep.value = 'feedback'
    await taskModule.refreshTasks()
  }

  async function sendWorkflowSupplement(taskId: string, sessionId: string, payload: Record<string, unknown>) {
    await workflowModule.sendSupplement(taskId, sessionId, payload)
  }

  // ═══════════════════════════════ FEEDBACK ═══════════════════════════════

  async function submitFeedback(taskId: string) {
    await feedbackModule.submitFeedback(taskId)
    store.activeStep.value = 'feedback'
  }

  async function importFeedback(taskId: string) {
    await feedbackModule.importFeedbackFile(taskId)
  }

  async function fetchFeedback(taskId: string) {
    await feedbackModule.fetchFeedbackByBv(taskId)
  }

  async function runFeedbackAnalyze(taskId: string, payload: Record<string, unknown>) {
    await feedbackModule.runAnalyze(taskId, payload)
    store.activeStep.value = 'report'
    await taskModule.refreshTasks()
    await usageModule.refreshUsageStats(1, false)
  }

  async function askFeedbackChat(taskId: string) {
    return feedbackModule.askChat(taskId)
  }

  async function downloadReport(taskId: string) {
    await feedbackModule.downloadReportMarkdown(taskId)
  }

  async function loadFeedbackEvidence(taskId: string) {
    await feedbackModule.loadEvidenceIndexStatus(taskId)
  }

  async function rebuildEvidence(taskId: string) {
    await feedbackModule.rebuildEvidenceIndex(taskId)
  }

  function clearFeedbackChat() {
    feedbackModule.clearFeedbackChatState()
  }

  return {
    submitTask, updateTask, selectTask,
    startCreateTask, startEditTask, confirmDeleteTask,
    runPrePublishAnalyze, confirmPrePublish, sendWorkflowSupplement,
    submitFeedback, importFeedback, fetchFeedback,
    runFeedbackAnalyze, askFeedbackChat,
    downloadReport, loadFeedbackEvidence, rebuildEvidence,
    clearFeedbackChat,
  }
}
