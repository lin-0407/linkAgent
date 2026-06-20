package com.link.linkagent.core;

import com.link.linkagent.util.TextUtil;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 执行模式路由器。
 * <p>
 * 默认 AUTO 不额外调用模型，而是用轻量规则判断任务复杂度。这样不会为了选择模式先产生一次 LLM 成本，
 * 也避免路由模型失误导致简单问题被过度编排。
 */
@Component
public class AgentExecutionModeRouter {

    private static final int PLAN_LENGTH_THRESHOLD = 120;

    private static final List<String> MULTI_AGENT_KEYWORDS = List.of(
            "多agent", "多 agent", "multi agent", "多智能体", "多视角", "分别从", "综合评估", "竞品",
            "受众", "评论", "弹幕", "复盘"
    );

    private static final List<String> PLAN_KEYWORDS = List.of(
            "计划", "步骤", "拆解", "实现", "排查", "优化", "方案", "工作流", "先做", "再做", "同时",
            "多个", "复杂", "对比", "分析"
    );

    public AgentExecutionMode route(AgentExecutionMode requestedMode, String userMessage) {
        AgentExecutionMode normalized = AgentExecutionMode.normalize(requestedMode);
        if (normalized != AgentExecutionMode.AUTO) {
            return normalized;
        }
        String normalizedMessage = TextUtil.trimToDefault(userMessage, "").toLowerCase();
        if (containsAny(normalizedMessage, MULTI_AGENT_KEYWORDS)) {
            return AgentExecutionMode.MULTI_AGENT;
        }
        if (normalizedMessage.length() >= PLAN_LENGTH_THRESHOLD || containsAny(normalizedMessage, PLAN_KEYWORDS)) {
            return AgentExecutionMode.PLAN_EXECUTE;
        }
        return AgentExecutionMode.REACT;
    }

    private boolean containsAny(String value, List<String> keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
