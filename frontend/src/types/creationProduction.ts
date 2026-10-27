export type Money = number
export type CreationRun = { runId: string; kind: string; status: string; stage: string; errorCode: string | null; runRevision: number }
export type Runtime = {
  initialized: boolean; workspaceRevision: number; activeRun: CreationRun | null
  budget: { limitCny: Money; knownCny: Money; reservedCny: Money; unknownCny: Money; remainingCny: Money; paidCallsBlocked: boolean } | null
}
export type AudioBed = { assetId: string; sourceInMs: number; sourceOutMs: number; gainDb: number; fadeInMs: number; fadeOutMs: number; ducking?: boolean }
export type BriefDocument = {
  topic: string; coreMessage: string; audience: string; targetDurationMs: number
  dialogueMode: 'TTS' | 'USER_NARRATION' | 'SOURCE' | 'NO_DIALOGUE' | 'MIXED'; contentKind: 'USER_PROVIDED' | 'FICTION'
  assetIds: string[]; constraints: string; backgroundMusic?: AudioBed | null; ambience?: AudioBed | null
  subtitleFontSize?: number | null
}
export type Brief = { briefId: string; revision: number; document: BriefDocument }
export type Cue = { startMs: number; endMs: number; text: string }
export type Scene = {
  id: string; layout: 'TITLE_CARD' | 'IMAGE_CARD' | 'SPLIT_CARD' | 'COMPARE_CARD' | 'VIDEO_CLIP' | 'AI_VIDEO'
  title: string; body: string[]; narration: string; durationMs: number; visualAssetId: string | null
  sourceInMs: number | null; sourceOutMs: number | null; fit: 'CONTAIN' | 'COVER'
  audioMode: 'NO_DIALOGUE' | 'ORIGINAL' | 'NARRATION' | 'TTS'; narrationAssetId: string | null
  cues: Cue[]; sourceRefs: string[]; visualPrompt?: string | null
}
export type Storyboard = {
  storyboardId: string; revision: number; briefId: string; parentStoryboardId: string | null; runId: string | null; documentHash: string
  document: { candidate: { scenes: Scene[] }; subtitleTimingSource: string }
  check: { renderable: boolean; durationMs: number; renderContentHash: string | null; problems: { sceneId: string | null; code: string; message: string }[] }
}
export type Preparation = { run: CreationRun; storyboardId: string | null }
export type CreationAsset = {
  assetId: string; kind: 'IMAGE' | 'NARRATION' | 'MUSIC' | 'VIDEO_CLIP'; fileName: string; fileSize: number; sha256: string; status: string
  failedStage: string | null; failureCode: string | null; failureMessage: string | null; cleanupPending: boolean
  metadata: Record<string, unknown>; partSize: number; totalParts: number; parts: { partNumber: number; etag: string; partSize: number }[]
}
export type ExecutionEstimate = {
  storyboardId: string; storyboardHash: string; providerFingerprint: string; kind: 'SAMPLE' | 'DRAFT' | 'FULL' | 'REVISION'; workspaceRevision: number
  sceneIds: string[]; durationMs: number; voiceSceneIds: string[]; videoSceneIds: string[]
  preparationEstimateCny: Money; reviewEstimateCny: Money; estimatedMaxAdditionalCostCny: Money; blockers: string[]
}
export type Execution = {
  executionId: string; kind: 'SAMPLE' | 'DRAFT' | 'FULL' | 'REVISION'; status: string; stage: string; storyboardId: string; sceneIds: string[]
  maxAdditionalCostCny: Money; costs: { knownCny: Money; reservedCny: Money; unknownCny: Money; overrunCount: number }
  run: CreationRun | null; versionId: string | null; previewAvailable: boolean; packageAvailable: boolean
  errorCode: string | null; message: string | null; createTime: string
}
export type SignedRead = { url: string; expiresAt: string }
export type Timing = { proposals: { sceneId: string; code: string; message: string; proposedDurationMs: number | null }[]; requiresUserDecision: boolean }
export type RevisionProposal = {
  proposalId: string | null; storyboardId: string; workspaceRevision: number
  differences: { sceneId: string | null; field: string; before: unknown; after: unknown }[]
  plan: { voiceSceneIds: string[]; videoSceneIds: string[]; reusedAssetIds: string[]; rebuildTimeline: boolean; estimatedPreparationCny: number | null; notes: string[] } | null
  clarifications: string[]; appliedStoryboardId: string | null
}

export type VideoDraft = {
  draftId: string; version: number; baseDraftId: string | null; requestKey: string; instruction: string; status: string
  targetDurationMs: number
  message: string | null; storyboardId: string | null; creativeChoices: string[]; reviewNotes: string[]
  execution: Execution | null; createTime: string
}
export type VideoDraftWorkspace = { versions: VideoDraft[]; runtime: Runtime }
export type VideoDraftRequest = {
  expectedWorkspaceRevision: number; baseDraftId: string | null; instruction: string
  budgetLimitCny: number; targetDurationMs: number; assetIds: string[]
}
