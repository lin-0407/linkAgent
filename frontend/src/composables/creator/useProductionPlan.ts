import { ref } from 'vue'
import { ApiError } from '@/api/http'
import {
  createProductionPlan,
  getCurrentProductionPlan,
  updateProductionStep,
} from '@/api/creatorProduction'
import type {
  CreateProductionPlanPayload,
  ProductionStepStatus,
  ProductionWorkspace,
} from '@/types/creatorProduction'

export function useProductionPlan() {
  const workspace = ref<ProductionWorkspace | null>(null)
  const isLoading = ref(false)
  const isGenerating = ref(false)
  const isUpdatingStep = ref(false)
  const errorMessage = ref('')

  async function load(taskId: string) {
    if (!taskId) return
    isLoading.value = true
    errorMessage.value = ''
    try {
      workspace.value = await getCurrentProductionPlan(taskId)
    } catch (error) {
      errorMessage.value = error instanceof ApiError ? error.message : '制作蓝图读取失败'
    } finally {
      isLoading.value = false
    }
  }

  async function generate(taskId: string, payload: CreateProductionPlanPayload) {
    if (!taskId || isGenerating.value) return false
    isGenerating.value = true
    errorMessage.value = ''
    try {
      workspace.value = await createProductionPlan(taskId, payload, `${taskId}:${crypto.randomUUID()}`)
      return true
    } catch (error) {
      errorMessage.value = error instanceof ApiError ? error.message : '制作蓝图生成失败'
      return false
    } finally {
      isGenerating.value = false
    }
  }

  async function updateStep(taskId: string, planId: string, stepId: string, status: ProductionStepStatus, rowVersion: number, skipReason?: string) {
    if (isUpdatingStep.value) return false
    isUpdatingStep.value = true
    errorMessage.value = ''
    try {
      workspace.value = await updateProductionStep(taskId, planId, stepId, {
        status,
        rowVersion,
        skipReason,
      })
      return true
    } catch (error) {
      errorMessage.value = error instanceof ApiError ? error.message : '制作步骤更新失败'
      return false
    } finally {
      isUpdatingStep.value = false
    }
  }

  return {
    workspace,
    isLoading,
    isGenerating,
    isUpdatingStep,
    errorMessage,
    load,
    generate,
    updateStep,
  }
}
