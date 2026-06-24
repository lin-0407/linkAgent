import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

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

    return {
      selectedTaskId,
      activeStep,
      restoredTaskId,
      hasSelectedTask,
      setSelectedTaskId,
      setActiveStep,
      setRestoredTaskId,
    }
  },
  {
    persist: {
      key: 'link-agent-creator',
      // 持久化选中的任务和导航步骤，恢复时自动回到上次工作状态
      pick: ['selectedTaskId', 'activeStep'],
    },
  },
)
