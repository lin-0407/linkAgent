import { inject, provide, type ComputedRef, type InjectionKey, type Ref } from 'vue'
import type { ResultModalTarget } from '@/types/creator'
import type { useCreatorContext } from './useCreatorContext'
import type { useCreatorEvaluation } from './useCreatorEvaluation'
import type { useCreatorFeedback } from './useCreatorFeedback'
import type { useCreatorFeedbackEvent } from './useCreatorFeedbackEvent'
import type { useCreatorGuidance } from './useCreatorGuidance'
import type { useCreatorTask } from './useCreatorTask'
import type { useCreatorUsage } from './useCreatorUsage'
import type { useCreatorWorkflow } from './useCreatorWorkflow'

type LooseWorkspaceShell = Record<string, any>

export interface CreatorWorkspaceContext {
  taskModule: ReturnType<typeof useCreatorTask>
  workflowModule: ReturnType<typeof useCreatorWorkflow>
  feedbackModule: ReturnType<typeof useCreatorFeedback>
  guidance: ReturnType<typeof useCreatorGuidance>
  contextModule: ReturnType<typeof useCreatorContext>
  usageModule: ReturnType<typeof useCreatorUsage>
  evaluationModule: ReturnType<typeof useCreatorEvaluation>
  feedbackEvent: ReturnType<typeof useCreatorFeedbackEvent>
  showDeveloperTools: ComputedRef<boolean>
  successMessage: Ref<string>
  errorMessage: Ref<string>
  openResultModal: (target: ResultModalTarget) => void
  closeResultModal: () => void
  openWorkflowMessageModal: () => void
  openWorkflowProcessModal: () => void
  openFeedbackChatDrawer: () => void
  closeFeedbackChatDrawer: () => void
  toggleFeedbackChatDrawer: () => void
  openDeveloperTest: () => void
  openUsageStats: () => void
  openTaskManager: () => void
  /**
   * 阶段二拆分发生在旧模板仍处于桥接模式时。
   * 这里集中承载主壳编排状态，避免子组件重新实现 SSE、任务恢复和弹窗副作用。
   */
  shell: LooseWorkspaceShell
}

export const CreatorWorkspaceKey: InjectionKey<CreatorWorkspaceContext> = Symbol('CreatorWorkspace')

export function provideCreatorWorkspace(ctx: CreatorWorkspaceContext) {
  provide(CreatorWorkspaceKey, ctx)
}

export function useCreatorWorkspaceContext(): CreatorWorkspaceContext {
  const ctx = inject(CreatorWorkspaceKey)
  if (!ctx) {
    throw new Error('useCreatorWorkspaceContext 必须在 CreatorWorkspace 主壳内使用')
  }
  return ctx
}

export function useCreatorWorkspaceShell<T extends LooseWorkspaceShell = LooseWorkspaceShell>(): T {
  return useCreatorWorkspaceContext().shell as T
}
