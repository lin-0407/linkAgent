package com.link.linkagent.creator.task.model;

/**
 * 创作任务状态。
 * 阶段 4.x 先保留最小状态集合，避免一开始就设计复杂状态机。
 */
public enum CreatorTaskStatus {

    /**
     * 草稿态代表只完成了任务和材料输入，后续分析模块还没有产出。
     */
    DRAFT,

    /**
     * 发布前分析完成后先停在这个状态，便于前端区分是否可以进入反馈分析。
     */
    PRE_PUBLISH_ANALYZED,

    /**
     * 评论弹幕分析完成后先停在这个状态，便于复盘报告判断前置数据是否准备好。
     */
    FEEDBACK_ANALYZED,

    /**
     * 同类型视频竞品分析完成，说明已经有对照基准，复盘可以进入最终汇总。
     */
    COMPETITOR_ANALYZED,

    /**
     * 这里表示完整创作复盘已生成，不单指某一个子模块完成分析。
     */
    ANALYZED,

    /**
     * 归档态预留给后续历史任务管理，当前阶段不做复杂流转限制。
     */
    ARCHIVED
}
