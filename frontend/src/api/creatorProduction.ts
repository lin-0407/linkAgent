import { get, patch, post } from './http'
import type {
  CreateProductionPlanPayload,
  ProductionWorkspace,
  UpdateProductionStepPayload,
} from '@/types/creatorProduction'

function taskUrl(taskId: string) {
  return `/creator/tasks/${encodeURIComponent(taskId)}`
}

export function getCurrentProductionPlan(taskId: string) {
  return get<ProductionWorkspace>(`${taskUrl(taskId)}/production-plan/current`)
}

export function createProductionPlan(
  taskId: string,
  payload: CreateProductionPlanPayload,
  idempotencyKey: string,
) {
  return post<ProductionWorkspace>(`${taskUrl(taskId)}/production-plans`, payload, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function updateProductionStep(
  taskId: string,
  planId: string,
  stepId: string,
  payload: UpdateProductionStepPayload,
) {
  return patch<ProductionWorkspace>(
    `${taskUrl(taskId)}/production-plans/${encodeURIComponent(planId)}/steps/${encodeURIComponent(stepId)}`,
    payload,
  )
}
