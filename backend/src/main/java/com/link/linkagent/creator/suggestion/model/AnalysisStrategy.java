package com.link.linkagent.creator.suggestion.model;

/**
 * 发布前优化分析策略。
 * 不同视频类型需要不同的分析切入角度——教程视频侧重知识点覆盖，
 * Vlog 侧重情感节奏，测评侧重对比框架。
 * 策略选择由前端面板传入，后端负责将对应提示词注入 system prompt。
 */
public enum AnalysisStrategy {

    /** 通用分析：均衡覆盖所有维度，适用于未指定策略时 */
    GENERAL("通用分析", "均衡覆盖内容卖点、标题吸引力、标签覆盖度和风险点"),

    /** 教程分析：重信息密度和知识点覆盖 */
    TUTORIAL("教程分析", "侧重知识点完整度、学习曲线和技能关键词覆盖"),

    /** Vlog分析：重情感节奏和人物弧光 */
    VLOG("Vlog分析", "侧重叙事节奏、情感起伏线和人物弧光的完整性"),

    /** 测评分析：重对比框架和购买建议 */
    REVIEW("测评分析", "侧重对比框架的清晰度、购买决策引导和客观性"),

    /** 评论分析：重观点独特性和论据强度 */
    COMMENTARY("评论分析", "侧重观点独特性、论据强度和表达说服力");

    private final String label;
    private final String description;

    AnalysisStrategy(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 从字符串安全解析策略，无法匹配时返回 GENERAL 作为兜底。
     */
    public static AnalysisStrategy fromString(String value) {
        if (value == null || value.isBlank()) {
            return GENERAL;
        }
        for (AnalysisStrategy strategy : values()) {
            if (strategy.name().equalsIgnoreCase(value.trim())) {
                return strategy;
            }
        }
        return GENERAL;
    }
}
