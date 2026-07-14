import { computed, reactive, ref } from 'vue'
import type { Ref } from 'vue'
import {
  createCreatorTask,
  deleteCreatorTask,
  getCreatorTask,
  listCreatorTasks,
  updateCreatorTask,
} from '@/api/creator'
import type {
  CreatorTask,
  CreatorTaskCreatePayload,
  CreatorTaskSummary,
  CreatorTaskUpdatePayload,
} from '@/types/creator'
import { useCreatorStore } from '@/stores/creatorStore'

type TaskManageMode = 'create' | 'edit'

export function useCreatorTask(errorRef: Ref<string>) {
  const creatorStore = useCreatorStore()

  // ── 状态 ──
  const tasks = ref<CreatorTaskSummary[]>([])
  const selectedTask = ref<CreatorTask | null>(null)
  const taskManageMode = ref<TaskManageMode>('create')
  const taskSearchQuery = ref('')
  const taskStatusFilter = ref<'ALL' | CreatorTaskSummary['status']>('ALL')
  const pendingDeleteTask = ref<CreatorTaskSummary | null>(null)

  const taskForm = reactive({
    taskName: '',
    videoType: '未分类',
    titleDraft: '',
    descriptionDraft: '',
    manuscript: '',
    subtitle: '',
  })

  // loading
  const isLoadingTasks = ref(false)
  const isCreatingTask = ref(false)
  const isUpdatingTask = ref(false)
  const isDeletingTask = ref(false)

  // ── 计算属性 ──
  const selectedTaskId = computed(() => selectedTask.value?.taskId ?? '')
  const hasSelectedTask = computed(() => selectedTaskId.value.length > 0)
  const hasSelectedTaskMaterials = computed(() => (selectedTask.value?.materials.length ?? 0) > 0)

  const hasTaskMaterialInput = computed(
    () =>
      hasText(taskForm.titleDraft) ||
      hasText(taskForm.descriptionDraft) ||
      hasText(taskForm.manuscript) ||
      hasText(taskForm.subtitle),
  )

  const filteredTasks = computed(() => {
    const keyword = taskSearchQuery.value.trim().toLowerCase()
    return tasks.value.filter((task) => {
      const matchStatus = taskStatusFilter.value === 'ALL' || task.status === taskStatusFilter.value
      if (!matchStatus) return false
      if (!keyword) return true
      return [task.taskName, task.videoType, task.taskId, statusLabel(task.status)]
        .join(' ')
        .toLowerCase()
        .includes(keyword)
    })
  })

  const taskSummaryStats = computed(() => {
    const stats = { total: tasks.value.length, draft: 0, inProgress: 0, done: 0 }
    for (const task of tasks.value) {
      if (task.status === 'DRAFT') stats.draft++
      else if (task.status === 'ANALYZED') stats.done++
      else stats.inProgress++
    }
    return stats
  })

  const taskSubmitLabel = computed(() => {
    if (taskManageMode.value === 'edit') return isUpdatingTask.value ? '保存中...' : '保存视频资料'
    return isCreatingTask.value ? '保存中...' : '保存视频资料'
  })

  const taskFormTitle = computed(() =>
    taskManageMode.value === 'edit' ? '编辑视频资料' : '填写视频资料',
  )

  const taskFormHint = computed(() =>
    taskManageMode.value === 'edit'
      ? '编辑当前任务后，旧材料会被覆盖，后续分析请重新生成。'
      : '先放入这期视频已有的标题、简介和文稿，后续会基于这些资料生成发布方案。',
  )

  const pendingDeleteTaskName = computed(() => pendingDeleteTask.value?.taskName ?? '')

  const materialPreview = computed(() => {
    if (!selectedTask.value) return []
    return selectedTask.value.materials.map((item) => ({
      ...item,
      label: materialLabel(item.materialType),
    }))
  })

  const currentVideoType = computed(() => {
    if (selectedTask.value?.videoType) return selectedTask.value.videoType
    return taskForm.videoType || '未分类'
  })

  // ── 工具函数 ──
  function showError(error: unknown) {
    errorRef.value = error instanceof Error ? error.message : String(error)
  }

  // ── 方法 ──

  async function refreshTasks() {
    isLoadingTasks.value = true
    errorRef.value = ''
    try {
      tasks.value = await listCreatorTasks(50)
      // 如果当前选中的任务已被删除，则清空选中
      if (selectedTask.value) {
        const stillExists = tasks.value.some((t) => t.taskId === selectedTask.value?.taskId)
        if (!stillExists) {
          resetSelectedWorkspace()
        }
      }
    } catch (error) {
      showError(error)
    } finally {
      isLoadingTasks.value = false
    }
  }

  function resetSelectedWorkspace() {
    selectedTask.value = null
    creatorStore.selectedTaskId = null
  }

  function resetTaskForm() {
    taskForm.taskName = ''
    taskForm.videoType = '未分类'
    taskForm.titleDraft = ''
    taskForm.descriptionDraft = ''
    taskForm.manuscript = ''
    taskForm.subtitle = ''
  }

  function fillTaskForm(task: CreatorTask) {
    taskForm.taskName = task.taskName ?? ''
    taskForm.videoType = task.videoType ?? '未分类'
    taskForm.titleDraft = getMaterialContent(task, 'TITLE_DRAFT')
    taskForm.descriptionDraft = getMaterialContent(task, 'DESCRIPTION_DRAFT')
    taskForm.manuscript = getMaterialContent(task, 'MANUSCRIPT')
    taskForm.subtitle = getMaterialContent(task, 'SUBTITLE')
  }

  function getMaterialContent(task: CreatorTask, materialType: string) {
    const found = task.materials.find((item) => item.materialType === materialType)
    return (found as Record<string, unknown>)?.content as string || (found as Record<string, unknown>)?.fileName as string || ''
  }

  /**
   * 判断本次编辑是否改变了发布前分析的输入。
   *
   * 后端生成建议时会同时读取四类材料和视频类型语境，因此前端不能只比较材料。
   * 只要其中任一项变化，就必须清空旧建议并创建新工作流会话，避免旧类型的方案继续进入反馈阶段。
   */
  function hasTaskAnalysisInputChanged(task: CreatorTask) {
    return normalizeVideoType(task.videoType) !== normalizeVideoType(taskForm.videoType) ||
      getMaterialContent(task, 'TITLE_DRAFT') !== taskForm.titleDraft.trim() ||
      getMaterialContent(task, 'DESCRIPTION_DRAFT') !== taskForm.descriptionDraft.trim() ||
      getMaterialContent(task, 'MANUSCRIPT') !== taskForm.manuscript.trim() ||
      getMaterialContent(task, 'SUBTITLE') !== taskForm.subtitle.trim()
  }

  function resetGeneratedTaskResults() {
    // 外部 composable/component 需在调用此方法后清空 suggestion/feedback 等
    // 此方法仅清空任务相关的 UI 状态
  }

  /** 创建新任务，返回完整任务对象供编排层加载关联数据 */
  async function submitTask(): Promise<CreatorTask | null> {
    if (!hasText(taskForm.taskName)) return null
    isCreatingTask.value = true
    errorRef.value = ''
    try {
      const task = await createCreatorTask({
        taskName: taskForm.taskName.trim(),
        videoType: taskForm.videoType || undefined,
        titleDraft: taskForm.titleDraft.trim() || undefined,
        descriptionDraft: taskForm.descriptionDraft.trim() || undefined,
        manuscript: taskForm.manuscript.trim() || undefined,
        subtitle: taskForm.subtitle.trim() || undefined,
        status: 'DRAFT',
      } as CreatorTaskCreatePayload)
      selectedTask.value = task
      creatorStore.selectedTaskId = task.taskId
      return task
    } catch (error) {
      showError(error)
      return null
    } finally {
      isCreatingTask.value = false
    }
  }

  /** 更新已有任务，返回完整任务对象 */
  async function submitUpdateTask(): Promise<CreatorTask | null> {
    if (!selectedTask.value || !hasText(taskForm.taskName)) return null
    isUpdatingTask.value = true
    errorRef.value = ''
    try {
      const payload: CreatorTaskUpdatePayload = {
        taskName: taskForm.taskName,
        videoType: taskForm.videoType || undefined,
        titleDraft: taskForm.titleDraft.trim() || undefined,
        descriptionDraft: taskForm.descriptionDraft.trim() || undefined,
        manuscript: taskForm.manuscript.trim() || undefined,
        subtitle: taskForm.subtitle.trim() || undefined,
      }
      const task = await updateCreatorTask(selectedTask.value.taskId, payload)
      selectedTask.value = task
      creatorStore.selectedTaskId = task.taskId
      return task
    } catch (error) {
      showError(error)
      return null
    } finally {
      isUpdatingTask.value = false
    }
  }

  /** 选中已有任务并加载完整数据，返回任务对象供编排层加载关联数据 */
  async function loadTask(taskId: string): Promise<CreatorTask | null> {
    try {
      const task = await getCreatorTask(taskId)
      selectedTask.value = task
      creatorStore.selectedTaskId = task.taskId
      return task
    } catch (error) {
      showError(error)
      return null
    }
  }

  async function confirmDeleteTask() {
    if (!pendingDeleteTask.value) return
    isDeletingTask.value = true
    errorRef.value = ''
    try {
      await deleteCreatorTask(pendingDeleteTask.value.taskId)
      if (selectedTask.value?.taskId === pendingDeleteTask.value.taskId) {
        selectedTask.value = null
        creatorStore.selectedTaskId = null
      }
      pendingDeleteTask.value = null
      await refreshTasks()
    } catch (error) {
      showError(error)
    } finally {
      isDeletingTask.value = false
    }
  }

  function askDeleteTask(task: CreatorTaskSummary) {
    pendingDeleteTask.value = task
  }

  function cancelDeleteTask() {
    pendingDeleteTask.value = null
  }

  function startCreateTask() {
    taskManageMode.value = 'create'
    resetTaskForm()
  }

  function startEditTask() {
    if (!selectedTask.value) return
    taskManageMode.value = 'edit'
    fillTaskForm(selectedTask.value)
  }

  function cancelEditTask() {
    if (!selectedTask.value) {
      taskManageMode.value = 'create'
      return
    }
    fillTaskForm(selectedTask.value)
    taskManageMode.value = 'create'
  }

  // ── 选项列表 ──

  const taskStatusOptions: Array<{
    value: 'ALL' | CreatorTaskSummary['status']
    label: string
  }> = [
    { value: 'ALL', label: '全部状态' },
    { value: 'DRAFT', label: '草稿' },
    { value: 'PRE_PUBLISH_ANALYZED', label: '发布前完成' },
    { value: 'FEEDBACK_ANALYZED', label: '反馈分析完成' },
    { value: 'COMPETITOR_ANALYZED', label: '竞品分析完成' },
    { value: 'ANALYZED', label: '复盘完成' },
  ]

  const videoTypeOptions = [
    '未分类', 'GLOBAL', '知识科普', '游戏实况', '游戏攻略',
    '数码测评', '影视杂谈', '生活记录', '鬼畜娱乐',
  ]

  return {
    // 状态
    tasks, selectedTask, taskManageMode, taskSearchQuery, taskStatusFilter,
    taskForm, pendingDeleteTask,
    isLoadingTasks, isCreatingTask, isUpdatingTask, isDeletingTask,
    // 计算
    selectedTaskId, hasSelectedTask, hasSelectedTaskMaterials,
    hasTaskMaterialInput, filteredTasks, taskSummaryStats,
    taskSubmitLabel, taskFormTitle, taskFormHint, pendingDeleteTaskName,
    materialPreview, currentVideoType,
    // 选项
    taskStatusOptions, videoTypeOptions,
    // 方法
    refreshTasks, resetTaskForm, fillTaskForm, getMaterialContent,
    hasTaskAnalysisInputChanged, resetGeneratedTaskResults,
    submitTask, submitUpdateTask, loadTask, confirmDeleteTask,
    askDeleteTask, cancelDeleteTask,
    startCreateTask, startEditTask, cancelEditTask,
  }
}

// ── 内部工具 ──
const hasText = (v: string) => v.trim().length > 0

/**
 * 与后端默认值保持一致，避免空值和“未分类”在前端被误判为两次不同编辑。
 */
function normalizeVideoType(value: string | null | undefined) {
  const normalizedValue = value?.trim()
  return normalizedValue || '未分类'
}

function statusLabel(status: string) {
  const labels: Record<string, string> = {
    DRAFT: '草稿',
    PRE_PUBLISH_ANALYZED: '已发布前优化',
    FEEDBACK_ANALYZED: '已反馈分析',
    COMPETITOR_ANALYZED: '已竞品分析',
    ANALYZED: '已分析',
    ARCHIVED: '已归档',
  }
  return labels[status] ?? status
}

function materialLabel(type: string) {
  const labels: Record<string, string> = {
    TITLE_DRAFT: '标题草稿',
    DESCRIPTION_DRAFT: '简介草稿',
    MANUSCRIPT: '文稿',
    SUBTITLE: '字幕',
  }
  return labels[type] ?? type
}
