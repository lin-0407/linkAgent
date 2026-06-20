package com.link.linkagent.core.plan;

import java.util.List;

/**
 * Plan-and-Execute 的单个计划步骤。
 *
 * @param id                  步骤 ID，Planner 必须用从 1 开始的稳定编号
 * @param description         本步要完成的目标，给执行回放和最终合成使用
 * @param action              要调用的工具名，必须来自工具清单
 * @param actionInput         工具输入，本项目自研 Tool 契约统一使用字符串入参
 * @param dependsOn           依赖的步骤 ID；第一版顺序执行，但保留依赖字段为后续 DAG 并发打底
 * @param expectedObservation 预期观察结果，用于减少 Planner 漏步和执行后合成信息不足
 */
public record AgentPlanStep(
        int id,
        String description,
        String action,
        String actionInput,
        List<Integer> dependsOn,
        String expectedObservation
) {

    public AgentPlanStep {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
