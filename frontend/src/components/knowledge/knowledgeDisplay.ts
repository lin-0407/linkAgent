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

// 页面只解释实际评分与比较范围，案例数量少不会影响脚本基础分的展示。
export function knowledgeQualityScoreLabel(video: ReferenceVideo) {
  if (typeof video.qualityScore === 'number' && Number.isFinite(video.qualityScore)) {
    return `分区互动分 ${video.qualityScore.toFixed(2)}`
  }
  return '统计待更新'
}

export function knowledgeQualityScoreTitle(video: ReferenceVideo) {
  const base = video.videoIndexScore ?? video.rawQualityScore
  if (typeof video.qualityScore === 'number' && typeof base === 'number') {
    const correction = video.qualityScore - base
    return `脚本分 ${base.toFixed(2)}；分区互动修正 ${correction >= 0 ? '+' : ''}${correction.toFixed(2)}。仅用于同分区比较。`
  }
  return '六项公开统计不完整，请在详情中刷新视频数据。'
}

export function knowledgeCategoryRankLabel(video: ReferenceVideo) {
  if (video.categoryRank == null || video.qualitySampleCount <= 0) return ''
  return `区内第 ${video.categoryRank}/${video.qualitySampleCount} 名`
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
