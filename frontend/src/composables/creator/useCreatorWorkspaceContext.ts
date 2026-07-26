import { inject, provide, type ComputedRef, type InjectionKey, type Ref } from 'vue'
import type {
  CreatorContextTermType,
  CreatorFeedback,
  CreatorFeedbackDashboard,
  CreatorFeedbackFetchResult,
  CreatorFeedbackReport,
  CreatorPreferenceMode,
  CreatorSuggestion,
  CreatorTask,
  CreatorWorkflowSession,
  CreatorWorkflowStep,
  LlmApiCallPage,
  LlmApiCallRecord,
  LlmApiModelCategory,
  LlmApiUsageCategorySummary,
  LlmApiUsageSummary,
  ResultModalTarget,
} from '@/types/creator'
import type { DraftVideo, MediaProcessingJob, PreflightReviewStatus } from '@/types/media'
import type { useCreatorFeedbackEvent } from './useCreatorFeedbackEvent'
import type { useCreatorGuidance } from './useCreatorGuidance'
import type { useCreatorTask } from './useCreatorTask'
import type { useCreatorUsage } from './useCreatorUsage'

type WorkspaceRef<T> = Ref<T> | ComputedRef<T>
type TaskModule = ReturnType<typeof useCreatorTask>
type GuidanceModule = ReturnType<typeof useCreatorGuidance>

export type CreatorWorkspacePreferenceOption = {
  value: CreatorPreferenceMode
  label: string
  description: string
}

export type CreatorWorkspacePreferenceChip = {
  text: string
  sourceTaskId: string
}

export type CreatorWorkspaceContextTermChip = {
  id: string
  text: string
  label: string
  title: string
}

/**
 * 创作台子页面通过该契约读取主壳状态。
 * 字段必须逐项声明，避免 Record<string, any> 把遗漏字段和嵌套 Ref 误用留到运行时才暴露。
 */
export interface CreatorWorkspaceShell {
  askDeleteSelectedTask: () => void
  cancelEditTask: () => void
  canConfirmPrePublish: WorkspaceRef<boolean>
  canEnterFeedback: WorkspaceRef<boolean>
  canGeneratePrePublishDraft: WorkspaceRef<boolean>
  canRunFeedbackAnalyze: WorkspaceRef<boolean>
  canRunPrePublishAnalyze: WorkspaceRef<boolean>
  canSendWorkflowMessage: WorkspaceRef<boolean>
  changeUsageCategoryFilter: (category: 'ALL' | LlmApiModelCategory) => Promise<void>
  changeUsagePage: (delta: number) => Promise<void>
  confirmPrePublishResult: () => Promise<void>
  contextTermChips: WorkspaceRef<CreatorWorkspaceContextTermChip[]>
  currentDraftVideo: Ref<DraftVideo | null>
  currentMediaProcessingStatus: Ref<MediaProcessingJob['status'] | null>
  currentPreflightReviewStatus: Ref<PreflightReviewStatus | null>
  currentVideoType: WorkspaceRef<string>
  downloadReportMarkdown: () => Promise<void>
  feedback: WorkspaceRef<CreatorFeedback | null>
  feedbackAnalyzeForm: GuidanceModule['feedbackAnalyzeForm']
  feedbackDashboard: WorkspaceRef<CreatorFeedbackDashboard | null>
  feedbackFetchResult: WorkspaceRef<CreatorFeedbackFetchResult | null>
  feedbackForm: {
    commentSamples: string
    danmakuSamples: string
    extraContext: string
  }
  feedbackImportFile: WorkspaceRef<File | null>
  feedbackReport: WorkspaceRef<CreatorFeedbackReport | null>
  feedbackScriptBv: WorkspaceRef<string>
  feedbackScriptForm: {
    bvInput: string
    maxComments: number
    maxRepliesPerComment: number
    maxDanmaku: number
    format: 'json' | 'both'
  }
  fetchFeedbackByBv: () => Promise<void>
  formatDate: (value: string) => string
  formatDuration: (value: number | null | undefined) => string
  formatInputCount: (record: LlmApiCallRecord) => string
  formatUsageToken: (value: number | null | undefined) => string
  hasConfirmedPrePublish: WorkspaceRef<boolean>
  hasFeedbackSampleInput: WorkspaceRef<boolean>
  hasPrePublishPreferenceModeSnapshot: WorkspaceRef<boolean>
  hasPrePublishScriptMaterial: WorkspaceRef<boolean>
  hasSelectedTask: WorkspaceRef<boolean>
  hasTaskMaterialInput: WorkspaceRef<boolean>
  historicalPreferenceChips: WorkspaceRef<CreatorWorkspacePreferenceChip[]>
  generatePrePublishManuscriptDraftForCurrentTask: (extraRequirement?: string) => Promise<boolean>
  handleFeedbackFileChange: (event: Event) => void
  importFeedbackFile: () => Promise<void>
  isActiveStepReadOnly: WorkspaceRef<boolean>
  isAnalyzingFeedback: WorkspaceRef<boolean>
  isAnalyzingPrePublish: WorkspaceRef<boolean>
  isConfirmingPrePublish: WorkspaceRef<boolean>
  isCreatingTask: WorkspaceRef<boolean>
  isExportingReportMarkdown: WorkspaceRef<boolean>
  isFetchingFeedback: WorkspaceRef<boolean>
  isGeneratingPrePublishDraft: WorkspaceRef<boolean>
  isImportingFeedback: WorkspaceRef<boolean>
  isLoadingCreatorContextTerms: WorkspaceRef<boolean>
  isLoadingCreatorPreferences: WorkspaceRef<boolean>
  isLoadingUsageStats: WorkspaceRef<boolean>
  isSavingCreatorContextTerm: WorkspaceRef<boolean>
  isSavingFeedback: WorkspaceRef<boolean>
  isSendingWorkflowMessage: WorkspaceRef<boolean>
  isUpdatingTask: WorkspaceRef<boolean>
  lastPreferenceModeLabel: WorkspaceRef<string>
  lastPreferenceModeNote: WorkspaceRef<string>
  openContextLibrary: () => void
  openGuidanceEditor: (target: 'prePublish' | 'feedback') => void
  openResultModal: (target: ResultModalTarget) => void
  openTaskManager: () => void
  openWorkflowMessageModal: () => void
  preferenceModeNote: WorkspaceRef<string>
  preferenceModeOptions: CreatorWorkspacePreferenceOption[]
  prePublishForm: GuidanceModule['prePublishForm']
  refreshCurrentDraftVideo: (taskId?: string) => Promise<void>
  refreshUsageStats: (page?: number, reportError?: boolean) => Promise<void>
  runFeedbackAnalyze: () => Promise<void>
  runPrePublishAnalyze: () => Promise<void>
  saveContextTermFromSuggestion: (
    term: string,
    termType: CreatorContextTermType,
    evidenceText?: string,
  ) => Promise<void>
  savingContextTermKey: WorkspaceRef<string>
  selectedPreferenceModeLabel: WorkspaceRef<string>
  selectedTask: WorkspaceRef<CreatorTask | null>
  selectedTaskId: WorkspaceRef<string>
  showDeveloperTools: WorkspaceRef<boolean>
  shortId: (value: string | null | undefined) => string
  startEditTask: (taskId: string) => Promise<void>
  statusLabel: (status: string) => string
  submitFeedback: () => Promise<void>
  submitTask: () => Promise<void>
  suggestion: WorkspaceRef<CreatorSuggestion | null>
  taskForm: TaskModule['taskForm']
  taskFormHint: WorkspaceRef<string>
  taskFormTitle: WorkspaceRef<string>
  taskManageMode: WorkspaceRef<'create' | 'edit'>
  taskSubmitLabel: WorkspaceRef<string>
  usageCallPage: WorkspaceRef<LlmApiCallPage | null>
  usageCategoryFilter: WorkspaceRef<'ALL' | LlmApiModelCategory>
  usageCategoryLabel: (category: string | null | undefined) => string
  usageCategoryOptions: Array<{ value: 'ALL' | LlmApiModelCategory; label: string }>
  usageCategorySummaries: WorkspaceRef<LlmApiUsageCategorySummary[]>
  usageCurrentPage: WorkspaceRef<number>
  usageStatusClass: (status: string | null | undefined) => string
  usageStatusLabel: (status: string | null | undefined) => string
  usageSummary: WorkspaceRef<LlmApiUsageSummary | null>
  usageTotalPages: WorkspaceRef<number>
  videoTypeOptions: string[]
  workflowRunningStep: WorkspaceRef<CreatorWorkflowStep | null>
  workflowSession: WorkspaceRef<CreatorWorkflowSession | null>
  workflowSseText: WorkspaceRef<string>
  workflowStatusText: WorkspaceRef<string>
  workflowSteps: WorkspaceRef<CreatorWorkflowStep[]>
}

export interface CreatorWorkspaceContext {
  feedbackEvent: ReturnType<typeof useCreatorFeedbackEvent>
  shell: CreatorWorkspaceShell
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

export function useCreatorWorkspaceShell(): CreatorWorkspaceShell {
  return useCreatorWorkspaceContext().shell
}
