export type ProductionVideoCategory = 'AI_GENERATED' | 'PROJECT_DEMO'
export type ProductionMethod =
  | 'HUMAN_SHOOTING'
  | 'SCREEN_RECORDING'
  | 'AI_GENERATION'
  | 'EXISTING_ASSET_EDITING'
  | 'MIXED'
export type ProductionPlanStatus = 'GENERATING' | 'READY' | 'STALE' | 'FAILED'
export type ProductionStepStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'SKIPPED'
export type ToolVerificationStatus = 'VERIFIED' | 'SOURCE_REQUIRED' | 'STALE' | 'FAILED'

export type PreferredTool = {
  name: string
  version?: string
  officialUrl?: string
}

export type CreateProductionPlanPayload = {
  videoCategory: ProductionVideoCategory
  productionMethod: ProductionMethod
  targetAudience: string
  corePromise: string
  targetDurationSeconds?: number
  availableAssets?: string[]
  constraints?: string
  preferredTools?: PreferredTool[]
}

export type ToolResolution = {
  toolId: string | null
  toolName: string
  version: string | null
  officialUrl: string | null
  verificationStatus: ToolVerificationStatus
  sourceUrls: string[]
  capabilities: string[]
  operations: string[]
  reason: string | null
}

export type ProductionPlan = {
  planId: string
  taskId: string
  planVersion: number
  videoCategory: ProductionVideoCategory
  productionMethod: ProductionMethod
  targetAudience: string
  corePromise: string
  targetDurationMs: number | null
  availableAssets: string[]
  constraints: string | null
  status: ProductionPlanStatus
  planTitle: string | null
  positioningSummary: string | null
  createTime: string
  updateTime: string
}

export type ProductionStep = {
  stepId: string
  sequenceNo: number
  phase: string
  stepName: string
  objective: string
  prerequisites: string[]
  operations: string[]
  toolRefs: ToolResolution[]
  expectedOutputs: string[]
  acceptanceCriteria: string[]
  difficulty: string | null
  required: boolean
  status: ProductionStepStatus
  rowVersion: number
  skipReason: string | null
}

export type ProductionWorkspace = {
  plan: ProductionPlan | null
  steps: ProductionStep[]
  toolResolution: ToolResolution[]
  readyForMedia: boolean
}

export type UpdateProductionStepPayload = {
  status: ProductionStepStatus
  skipReason?: string
  rowVersion: number
}
