import { ref } from 'vue'
import type { Ref } from 'vue'
import { recordCreatorEvent } from '@/api/creator'
import type {
  CreatorEventType,
  CreatorFeedbackEventPayload,
  CreatorRejectReason,
} from '@/types/creator'
import { ApiError } from '@/api/http'

/**
 * 建议卡片反馈事件上报。
 *
 * 为什么单独抽一个 composable：每张建议卡片都有"采纳/复制/不太好"几个按钮，
 * 如果各自直接调 recordCreatorEvent，会有大量重复的 try-catch、loading 标记和错误展示。
 * 这里统一管理"正在上报哪一条"的状态和错误信息，卡片只关心"对哪条建议做了什么动作"。
 *
 * 事件最终写入 creator_event 表，并触发创作者画像的增量更新（后端 CreatorProfileService 负责），
 * 所以这里的反馈是"有记忆的"——会影响用户后续的建议生成。
 *
 * @param successRef 由调用方提供的成功消息 ref，复用 CreatorWorkspace 已有的 toast 通道
 * @param errorRef 由调用方提供的错误消息 ref
 */
export function useCreatorFeedbackEvent(
  successRef: Ref<string>,
  errorRef: Ref<string>,
) {
  /** 当前正在上报的建议内容（用于禁用对应卡片的按钮，防重复点击） */
  const reportingContent = ref<string>('')

  let successTimer: ReturnType<typeof setTimeout> | undefined

  /**
   * 上报一条反馈事件。
   *
   * @param eventType 事件类型，区分采纳/拒绝、标题/标签等
   * @param taskId 当前任务 ID（可空，仅用于追溯事件发生的创作上下文）
   * @param fields 事件附加字段：建议正文 content、原因 reason 等
   * @returns 是否上报成功，便于调用方决定后续 UI 动作（如关闭面板）
   */
  async function report(
    eventType: CreatorEventType,
    taskId: string | null | undefined,
    fields: {
      userId?: string
      content?: string
      scenario?: string
      reason?: CreatorRejectReason
      reasonText?: string
      rank?: number
    },
  ): Promise<boolean> {
    // 建议正文作为去重 key：同一条建议的上报串行化，避免连点产生重复事件
    const contentKey = fields.content ?? eventType
    if (reportingContent.value === contentKey) return false

    reportingContent.value = contentKey
    errorRef.value = ''
    try {
      // 组装载荷：userId 兜底为 'default'，与后端画像接口默认值保持一致
      const payload: CreatorFeedbackEventPayload = {
        userId: fields.userId ?? 'default',
        eventType,
        taskId: taskId || undefined,
        content: fields.content,
        scenario: fields.scenario,
        reason: fields.reason,
        reasonText: fields.reasonText,
        rank: fields.rank,
      }
      await recordCreatorEvent(payload)

      // 上报成功后给一句简短反馈，让用户知道"系统记住了"
      showSuccess(successMessageOf(eventType))
      return true
    } catch (error) {
      // 失败不阻塞主流程，只提示，建议本身仍可继续操作
      errorRef.value =
        error instanceof ApiError
          ? `反馈记录失败：${error.message}`
          : '反馈记录失败，请稍后重试'
      return false
    } finally {
      reportingContent.value = ''
    }
  }

  /** 采纳快捷方法：标题/标签/整组建议通用 */
  function reportAccept(
    eventType: Extract<
      CreatorEventType,
      'TITLE_ACCEPTED' | 'TAG_ACCEPTED' | 'SUGGESTION_ADOPTED'
    >,
    taskId: string | null | undefined,
    content: string,
    rank?: number,
  ) {
    return report(eventType, taskId, { content, rank })
  }

  /** 拒绝快捷方法：需要带上拒绝原因，否则画像无法聚类用户的排斥倾向 */
  function reportReject(
    eventType: Extract<
      CreatorEventType,
      'TITLE_REJECTED' | 'TAG_REJECTED' | 'SUGGESTION_REJECTED'
    >,
    taskId: string | null | undefined,
    content: string,
    reason: CreatorRejectReason,
    reasonText: string,
    rank?: number,
  ) {
    return report(eventType, taskId, { content, reason, reasonText, rank })
  }

  /** 判断某条建议是否正在上报，用于禁用按钮 */
  function isReporting(content: string, eventType: CreatorEventType) {
    return reportingContent.value === (content || eventType)
  }

  function showSuccess(message: string) {
    successRef.value = message
    if (successTimer) clearTimeout(successTimer)
    // 3 秒后自动清空，与 CreatorWorkspace 现有 toast 行为一致
    successTimer = setTimeout(() => {
      successRef.value = ''
    }, 3000)
  }

  return {
    reportingContent,
    report,
    reportAccept,
    reportReject,
    isReporting,
  }
}

/** 根据事件类型给出一句用户能看懂的反馈文案 */
function successMessageOf(eventType: CreatorEventType): string {
  switch (eventType) {
    case 'TITLE_ACCEPTED':
    case 'TAG_ACCEPTED':
    case 'SUGGESTION_ADOPTED':
      return '已记录你的采纳，后续建议会更贴合你的风格'
    case 'TITLE_REJECTED':
    case 'TAG_REJECTED':
    case 'SUGGESTION_REJECTED':
      return '已记录你的反馈，下次会避开这类方向'
    case 'FEEDBACK_INSIGHT_SAVED':
      return '已保存这条洞察'
    default:
      return '已记录'
  }
}
