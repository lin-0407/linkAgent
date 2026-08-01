import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export type InteractiveCreationPanel = 'idea' | 'context' | 'alignment'

type InteractiveCreationDraft = {
  activePanel: InteractiveCreationPanel
  feedbackDraft: string
}

/**
 * 创作台核心状态：跨组件被选中的任务 ID、当前导航步骤、恢复标记。
 * 替代原来的 ProjectListWorkspace → localStorage → CreatorWorkspace 间接通道。
 */
export const useCreatorStore = defineStore(
  'creator',
  () => {
    const selectedTaskId = ref<string | null>(null)
    const activeStep = ref<string>('task')
    /** 从持久化恢复的任务 ID，用于区分"恢复上次任务"和"新建任务" */
    const restoredTaskId = ref<string>('')
    /** 交互式创作尚未进入正式工作台时，用该任务 ID 恢复想法对齐页面。 */
    const activeInteractiveTaskId = ref<string | null>(null)
    /** 新任务尚未提交时没有 taskId，因此单独保存创作输入草稿。 */
    const newInteractiveTaskDraft = ref({
      idea: '',
      videoType: '未分类',
    })
    /** 已创建任务的子页和未发送补充按 taskId 隔离，切换任务时互不覆盖。 */
    const interactiveTaskDrafts = ref<Record<string, InteractiveCreationDraft>>({})
    /** 发布前协作输入按 taskId 保存，刷新后仍可继续编辑而不会自动发送。 */
    const workflowMessageDrafts = ref<Record<string, string>>({})

    const hasSelectedTask = computed(() => !!selectedTaskId.value)

    function setSelectedTaskId(taskId: string | null) {
      selectedTaskId.value = taskId
    }

    function setActiveStep(step: string) {
      activeStep.value = step
    }

    function setRestoredTaskId(taskId: string) {
      restoredTaskId.value = taskId
    }

    function setActiveInteractiveTaskId(taskId: string | null) {
      activeInteractiveTaskId.value = taskId
    }

    function setNewInteractiveTaskDraft(idea: string, videoType: string) {
      newInteractiveTaskDraft.value = { idea, videoType }
    }

    function clearNewInteractiveTaskDraft() {
      newInteractiveTaskDraft.value = { idea: '', videoType: '未分类' }
    }

    function setInteractiveTaskDraft(
      taskId: string,
      patch: Partial<InteractiveCreationDraft>,
    ) {
      if (!taskId) return
      const current = interactiveTaskDrafts.value[taskId] ?? {
        activePanel: 'context' as InteractiveCreationPanel,
        feedbackDraft: '',
      }
      interactiveTaskDrafts.value = {
        ...interactiveTaskDrafts.value,
        [taskId]: { ...current, ...patch },
      }
    }

    function clearInteractiveTaskDraft(taskId: string) {
      if (!taskId || !interactiveTaskDrafts.value[taskId]) return
      const nextDrafts = { ...interactiveTaskDrafts.value }
      delete nextDrafts[taskId]
      interactiveTaskDrafts.value = nextDrafts
    }

    function setWorkflowMessageDraft(taskId: string, draft: string) {
      if (!taskId) return
      workflowMessageDrafts.value = {
        ...workflowMessageDrafts.value,
        [taskId]: draft,
      }
    }

    function clearWorkflowMessageDraft(taskId: string) {
      if (!taskId || !(taskId in workflowMessageDrafts.value)) return
      const nextDrafts = { ...workflowMessageDrafts.value }
      delete nextDrafts[taskId]
      workflowMessageDrafts.value = nextDrafts
    }

    return {
      selectedTaskId,
      activeStep,
      restoredTaskId,
      activeInteractiveTaskId,
      newInteractiveTaskDraft,
      interactiveTaskDrafts,
      workflowMessageDrafts,
      hasSelectedTask,
      setSelectedTaskId,
      setActiveStep,
      setRestoredTaskId,
      setActiveInteractiveTaskId,
      setNewInteractiveTaskDraft,
      clearNewInteractiveTaskDraft,
      setInteractiveTaskDraft,
      clearInteractiveTaskDraft,
      setWorkflowMessageDraft,
      clearWorkflowMessageDraft,
    }
  },
  {
    persist: {
      key: 'link-agent-creator',
      // 只持久化恢复创作现场所需的小体积状态，服务端消息和文件正文仍以接口结果为准。
      pick: [
        'selectedTaskId',
        'activeStep',
        'activeInteractiveTaskId',
        'newInteractiveTaskDraft',
        'interactiveTaskDrafts',
        'workflowMessageDrafts',
      ],
    },
  },
)
