package com.link.linkagent.creator.workflow.model;

/**
 * 创作者工作流阶段。
 * 先把业务阶段显式枚举出来，是为了避免前端随意传字符串导致消息混到错误场景里。
 */
public enum CreatorWorkflowStage {
    /**
     * 发布前优化阶段：围绕标题、简介、标签和分区生成建议。
     */
    PRE_PUBLISH,

    /**
     * 评论弹幕分析阶段：围绕用户主动提供的评论和弹幕样例生成反馈分析中间结果。
     */
    FEEDBACK,

    /**
     * 创作复盘报告阶段：汇总发布前建议、反馈分析和竞品分析生成最终报告。
     */
    REPORT
}
