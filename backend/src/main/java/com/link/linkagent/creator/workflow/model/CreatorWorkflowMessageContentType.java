package com.link.linkagent.creator.workflow.model;

/**
 * 消息内容类型。
 * 第一版只做文本和材料摘要，保留 RESULT_CARD / ERROR 是为了给下一轮建议确认和失败展示留稳定值。
 */
public enum CreatorWorkflowMessageContentType {
    /**
     * 普通文本：没有额外结构的消息内容。
     */
    TEXT,

    /**
     * 材料摘要：消息只展示摘要，完整内容通过 detailRefId 引用创作材料。
     */
    MATERIAL_SUMMARY,

    /**
     * 结果卡片：后续用于展示可确认的标题、简介、标签等结构化结果。
     */
    RESULT_CARD,

    /**
     * 错误消息：用于前端突出展示失败原因。
     */
    ERROR
}
