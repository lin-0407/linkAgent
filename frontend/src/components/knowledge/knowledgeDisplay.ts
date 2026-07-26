import type { ReferenceVideo } from '@/types/knowledge'

// 案例层级会同时出现在导入、列表和检索结果中，集中维护可避免同一枚举显示成不同文案。
export const KNOWLEDGE_TIER_OPTIONS = [
  { value: 'BENCHMARK', label: '标杆案例' },
  { value: 'COMPETITOR', label: '竞品案例' },
  { value: 'OWN_HISTORY', label: '自己历史' },
] as const

export function knowledgeTierLabel(value: string) {
  return KNOWLEDGE_TIER_OPTIONS.find((option) => option.value === value)?.label ?? value
}

export function knowledgeEmbeddingLabel(status: string | null) {
  switch (status) {
    case 'INDEXED':
      return '已索引'
    case 'FAILED':
      return '索引失败'
    case 'PENDING':
      return '待索引'
    default:
      return status ?? '待索引'
  }
}

// 检索结果和 AI 案例上下文展示同一批证据枚举，集中转换可避免两处文案不一致。
export function chunkTypeLabel(chunkType: string) {
  switch (chunkType) {
    case 'TITLE_PACKAGE':
      return '标题包装'
    case 'CONTENT_POSITIONING':
      return '内容定位'
    case 'AUDIENCE_FEEDBACK_SUMMARY':
      return '观众反馈'
    default:
      return chunkType || '主题'
  }
}

export function sentimentLabel(sentiment: string) {
  switch (sentiment) {
    case 'POSITIVE':
      return '正向'
    case 'NEGATIVE':
      return '负向'
    default:
      return sentiment || '中性'
  }
}

export function sourceTypeLabel(sourceType: string) {
  return sourceType === 'DANMAKU' ? '弹幕' : '评论'
}

// 原始质量分只用于低样本排序，可靠性不足时不能把它伪装成可比较的相对质量分。
export function knowledgeQualityScoreLabel(video: ReferenceVideo) {
  if (video.qualityScoreReliable && video.qualityScore !== null) {
    return `质量分 ${video.qualityScore}`
  }
  if (video.rawQualityScore !== null) {
    return '质量样本不足'
  }
  return ''
}

export function knowledgeQualityScoreTitle(video: ReferenceVideo) {
  if (video.qualityScoreReliable && video.qualityScore !== null) {
    return `同分区有效样本 ${video.qualitySampleCount} 条`
  }
  if (video.rawQualityScore !== null) {
    return `同分区有效样本 ${video.qualitySampleCount} 条，暂不展示相对质量分`
  }
  return ''
}

export function formatKnowledgeCount(value: number | null) {
  if (value === null || value === undefined) {
    return '—'
  }
  if (value >= 10000) {
    return `${(value / 10000).toFixed(1)}万`
  }
  return String(value)
}
