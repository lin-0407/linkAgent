package com.link.linkagent.core.plan;

import java.util.List;

/**
 * Plan-and-Execute 的结构化计划。
 * <p>
 * 这是 Planner 与 Executor 之间的契约，模型只负责产计划，真正执行仍由后端代码按步骤调工具。
 *
 * @param objective     用户本轮真实目标
 * @param steps         可执行步骤列表
 * @param rationale     为什么这样拆分
 * @param coverageCheck 对用户诉求的覆盖说明，避免 Planner 漏掉原问题中的某一部分
 */
public record AgentPlan(
        String objective,
        List<AgentPlanStep> steps,
        String rationale,
        String coverageCheck
) {

    public AgentPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
