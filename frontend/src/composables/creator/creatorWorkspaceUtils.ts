import type {
  CreatorContextPolarity,
  CreatorContextTermType,
  CreatorPreferenceMode,
  CreatorWorkflowStage,
  CreatorWorkflowStep,
  CreatorWorkflowStatus,
  LlmApiCallRecord,
} from '@/types/creator'

export type UnknownRecord = Record<string, unknown>

export function retrievalModeLabel(mode: string | null | undefined) {
  switch (mode) {
    case 'MILVUS_VECTOR_AND_MYSQL_REPORT':
      return '向量检索'
    case 'MILVUS_VECTOR_WITH_SQL_FALLBACK':
      return '向量检索（含 SQL 兜底）'
    case 'MYSQL_REPORT_AND_CLASSIFIED_ITEMS':
      return 'SQL 证据检索'
    default:
      return 'SQL 证据检索'
  }
}

export function usageCategoryLabel(category: string | null | undefined) {
  switch (category) {
    case 'TEXT':
      return '文本 LLM'
    case 'EMBEDDING':
      return '向量化模型'
    case 'RERANK':
      return 'Rerank 模型'
    default:
      return category || '未知模型'
  }
}

export function usageStatusLabel(status: string | null | undefined) {
  switch (status) {
    case 'SUCCESS':
      return '成功'
    case 'FAILED':
      return '失败'
    case 'SKIPPED':
      return '跳过'
    default:
      return status || '未知'
  }
}

export function usageStatusClass(status: string | null | undefined) {
  if (status === 'FAILED') {
    return 'failed'
  }
  if (status === 'SKIPPED') {
    return 'skipped'
  }
  return 'success'
}

export function formatUsageToken(value: number | null | undefined) {
  if (value === null || value === undefined) {
    return '未返回'
  }
  return value.toLocaleString('zh-CN')
}

export function formatDuration(value: number | null | undefined) {
  if (value === null || value === undefined) {
    return '未返回'
  }
  if (value < 1000) {
    return `${value} ms`
  }
  if (value < 60_000) {
    return `${(value / 1000).toFixed(1)} s`
  }
  const minutes = Math.floor(value / 60_000)
  const seconds = Math.round((value % 60_000) / 1000)
  return `${minutes} min ${seconds} s`
}

export function formatInputCount(record: LlmApiCallRecord) {
  if (record.inputCount === null || record.inputCount === undefined) {
    return record.modelCategory === 'TEXT' ? '单次对话' : '未记录'
  }
  if (record.modelCategory === 'EMBEDDING') {
    return `${record.inputCount} 段文本`
  }
  if (record.modelCategory === 'RERANK') {
    return `${record.inputCount} 条候选`
  }
  return `${record.inputCount} 条输入`
}

export function contextTermTypeLabel(termType: CreatorContextTermType | string) {
  switch (termType) {
    case 'SLANG':
      return '圈内黑话'
    case 'MEME':
      return '梗表达'
    case 'TABOO':
      return '慎用表达'
    case 'TITLE_PATTERN':
      return '标题套路'
    case 'AUDIENCE_CONCERN':
      return '观众关注点'
    default:
      return '关键词'
  }
}

export function contextTermSourceLabel(sourceType: string) {
  switch (sourceType) {
    case 'AI_ACCEPTED':
      return '采纳建议'
    case 'COMMENT_EXTRACTED':
      return '评论弹幕'
    case 'USER_REJECTED':
      return '用户否定'
    case 'VIDEO_SUCCESS':
      return '高质量视频'
    default:
      return '手动保存'
  }
}

export function contextTermPolarity(termType: CreatorContextTermType): CreatorContextPolarity {
  return termType === 'TABOO' ? 'NEGATIVE' : 'POSITIVE'
}

export function normalizeContextTermText(value: string) {
  const text = value.trim().replace(/\s+/g, ' ')
  return text.length > 128 ? text.slice(0, 128) : text
}

export function contextSaveKey(term: string, termType: CreatorContextTermType) {
  return `${termType}-${normalizeContextTermText(term)}`
}

export function parseJsonArray(value: string | null | undefined) {
  if (!value) {
    return []
  }

  try {
    const parsed = JSON.parse(value) as unknown
    return Array.isArray(parsed) ? parsed : [parsed]
  } catch {
    return [value]
  }
}

export function formatValue(value: unknown) {
  if (value === null || value === undefined) {
    return ''
  }
  if (typeof value === 'string') {
    return value
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value)
  }
  return JSON.stringify(value, null, 2)
}

export function preferenceItemText(value: unknown) {
  if (typeof value === 'string') {
    return value.trim()
  }
  if (isRecord(value)) {
    const keys = ['preference', 'preferenceValue', 'content', 'insight', 'label', 'value', 'suggestion']
    for (const key of keys) {
      const text = value[key]
      if (typeof text === 'string' && text.trim()) {
        return text.trim()
      }
    }
  }
  return formatValue(value)
}

export function getRecordText(value: unknown, key: string) {
  if (isRecord(value)) {
    const text = value[key]
    return typeof text === 'string' ? text : ''
  }
  return ''
}

export function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function extractBvid(value: string) {
  const matched = value.match(/BV[0-9A-Za-z]{10}/)
  return matched?.[0] ?? ''
}

export function clampScriptNumber(value: number, min: number, max: number) {
  if (!Number.isFinite(value)) {
    return min
  }
  return Math.min(max, Math.max(min, Math.trunc(value)))
}

export function isWorkflowStatus(value: string | null): value is CreatorWorkflowStatus {
  return Boolean(
    value &&
      [
        'CREATED',
        'CONTEXT_LOADING',
        'WAITING_USER_INPUT',
        'RUNNING',
        'WAITING_CONFIRMATION',
        'CONFIRMED',
        'FAILED',
        'CANCELLED',
      ].includes(value),
  )
}

// 发布方案的父子组件都只关心最近一次执行结果，集中判断可避免两处排序规则逐渐分叉。
export function getLatestWorkflowFailedStep(steps: readonly CreatorWorkflowStep[]) {
  const latestStep = [...steps].sort((left, right) => {
    const leftTime = left.startTime || left.createTime
    const rightTime = right.startTime || right.createTime
    return rightTime.localeCompare(leftTime)
  })[0]
  return latestStep?.status === 'FAILED' ? latestStep : null
}

export function hasText(value: string) {
  return value.trim().length > 0
}

export function previewWorkflowMessage(value: string) {
  const normalized = value.replace(/\s+/g, ' ').trim()
  if (!normalized) {
    return '空消息'
  }
  return normalized.length > 64 ? `${normalized.slice(0, 64)}...` : normalized
}

export function workflowRoleLabel(role: string) {
  const labels: Record<string, string> = {
    SYSTEM: '系统',
    USER: '用户',
    AGENT: 'Agent',
    TOOL: '工具',
    RESULT: '结果',
  }
  return labels[role] ?? role
}

export function workflowContentTypeLabel(contentType: string) {
  const labels: Record<string, string> = {
    TEXT: '文本',
    MATERIAL_SUMMARY: '材料摘要',
    RESULT_CARD: '结果卡片',
    ERROR: '错误',
  }
  return labels[contentType] ?? contentType
}

export function workflowSessionLabel(status: string) {
  const labels: Record<string, string> = {
    CREATED: '已创建',
    CONTEXT_LOADING: '装载中',
    WAITING_USER_INPUT: '等待补充',
    RUNNING: '运行中',
    WAITING_CONFIRMATION: '等待确认',
    CONFIRMED: '已确认',
    FAILED: '失败',
    CANCELLED: '已取消',
  }
  return labels[status] ?? status
}

export function evalStageLabel(stage: CreatorWorkflowStage) {
  const labels: Record<CreatorWorkflowStage, string> = {
    PRE_PUBLISH: '发布前优化',
    FEEDBACK: '评论弹幕',
    REPORT: '总体复盘',
  }
  return labels[stage]
}

export function evalResultStatusLabel(status: string) {
  const labels: Record<string, string> = {
    SUCCESS: '成功',
    FAILED: '失败',
  }
  return labels[status] ?? status
}

export function isPrePublishSuggestionVisible(status: string) {
  // 重新生成中、失败后或用户继续补充时仍应恢复上一版成功方案，避免刷新后结果消失。
  return ['WAITING_USER_INPUT', 'RUNNING', 'WAITING_CONFIRMATION', 'CONFIRMED', 'FAILED'].includes(status)
}

export function hasPrePublishResult(status: string) {
  return [
    'PRE_PUBLISH_ANALYZED',
    'FEEDBACK_COLLECTING',
    'FEEDBACK_ANALYZED',
    'COMPETITOR_ANALYZED',
    'ANALYZED',
    'ARCHIVED',
  ].includes(status)
}

export function hasFeedbackResult(status: string) {
  return ['FEEDBACK_ANALYZED', 'COMPETITOR_ANALYZED', 'ANALYZED', 'ARCHIVED'].includes(status)
}

export function materialLabel(type: string) {
  const labels: Record<string, string> = {
    TITLE_DRAFT: '标题草稿',
    DESCRIPTION_DRAFT: '简介草稿',
    MANUSCRIPT: '文稿',
    SUBTITLE: '字幕',
  }
  return labels[type] ?? type
}

export function statusLabel(status: string) {
  const labels: Record<string, string> = {
    DRAFT: '草稿',
    PRE_PUBLISH_ANALYZED: '已发布前优化',
    FEEDBACK_COLLECTING: '反馈待分析',
    FEEDBACK_ANALYZED: '已反馈分析',
    COMPETITOR_ANALYZED: '已竞品分析',
    ANALYZED: '总体复盘完成',
    ARCHIVED: '已归档',
  }
  return labels[status] ?? status
}

export function shortId(value: string | null | undefined) {
  if (!value) {
    return '-'
  }
  return value.length <= 14 ? value : `${value.slice(0, 8)}...${value.slice(-4)}`
}

export function formatDate(value: string) {
  if (!value) {
    return '-'
  }
  return value.replace('T', ' ').slice(0, 16)
}

export function formatMetric(value: number | null | undefined) {
  if (value === null || value === undefined) {
    return '-'
  }
  return value.toLocaleString('zh-CN')
}

export function formatPercent(value: number | null | undefined) {
  if (value === null || value === undefined) {
    return '-'
  }
  return `${formatMetric(value)}%`
}

export function statBarWidth(count: number, total: number) {
  if (total <= 0 || count <= 0) {
    return '0%'
  }
  return `${Math.max(8, Math.round((count / total) * 100))}%`
}

export function preferenceModeNoteByMode(mode: CreatorPreferenceMode, historicalPreferenceCount: number) {
  if (mode === 'IGNORE_HISTORY') {
    return '历史偏好不参与本次生成'
  }
  if (mode === 'EXPERIMENT') {
    return '本期覆盖要求优先'
  }
  if (historicalPreferenceCount === 0) {
    return '暂无可用历史偏好'
  }
  return `参考 ${historicalPreferenceCount} 条偏好`
}
