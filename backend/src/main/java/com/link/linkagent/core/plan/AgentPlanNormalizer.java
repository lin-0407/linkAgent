package com.link.linkagent.core.plan;

import com.link.linkagent.util.TextUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 计划规整器。
 * <p>
 * Planner 和 Replanner 都会产出 AgentPlan，集中规整能保证步骤排序、空值兜底和字段语义一致。
 */
final class AgentPlanNormalizer {

    private AgentPlanNormalizer() {
    }

    static AgentPlan normalize(AgentPlan rawPlan, String emptyRationale) {
        if (rawPlan == null) {
            return new AgentPlan("未生成计划", List.of(), emptyRationale, "未覆盖用户诉求");
        }
        List<AgentPlanStep> normalizedSteps = rawPlan.steps().stream()
                .filter(step -> step != null)
                // Planner 生成乱序时，执行器仍按 id 稳定执行，便于前端回放和问题复现。
                .sorted(Comparator.comparingInt(AgentPlanStep::id))
                .map(AgentPlanNormalizer::normalizeStep)
                .toList();
        return new AgentPlan(
                TextUtil.trimToDefault(rawPlan.objective(), "未说明目标"),
                normalizedSteps,
                TextUtil.trimToDefault(rawPlan.rationale(), "未说明规划依据"),
                TextUtil.trimToDefault(rawPlan.coverageCheck(), "未说明覆盖检查")
        );
    }

    static AgentPlan reindexRemainingSteps(AgentPlan plan, int nextStepId, Set<Integer> successStepIds) {
        AgentPlan normalizedPlan = normalize(plan, "Replanner 返回空对象");
        Map<Integer, Integer> idMapping = new HashMap<>();
        int cursor = Math.max(1, nextStepId);
        for (AgentPlanStep step : normalizedPlan.steps()) {
            idMapping.put(step.id(), cursor++);
        }
        List<AgentPlanStep> reindexedSteps = new ArrayList<>();
        Set<Integer> safeSuccessStepIds = successStepIds == null ? Set.of() : new HashSet<>(successStepIds);
        for (AgentPlanStep step : normalizedPlan.steps()) {
            List<Integer> remappedDependsOn = step.dependsOn().stream()
                    .map(dependencyId -> idMapping.getOrDefault(dependencyId, dependencyId))
                    // Replanner 只能依赖已经成功的旧步骤，或本轮新生成的步骤；失败旧步骤不能继续作为依赖。
                    .filter(dependencyId -> safeSuccessStepIds.contains(dependencyId)
                            || idMapping.containsValue(dependencyId))
                    .distinct()
                    .toList();
            reindexedSteps.add(new AgentPlanStep(
                    idMapping.get(step.id()),
                    step.description(),
                    step.action(),
                    step.actionInput(),
                    remappedDependsOn,
                    step.expectedObservation()
            ));
        }
        return new AgentPlan(
                normalizedPlan.objective(),
                reindexedSteps,
                normalizedPlan.rationale(),
                normalizedPlan.coverageCheck()
        );
    }
    private static AgentPlanStep normalizeStep(AgentPlanStep step) {
        return new AgentPlanStep(
                step.id(),
                TextUtil.trimToDefault(step.description(), "未说明步骤目标"),
                TextUtil.trimToDefault(step.action(), ""),
                TextUtil.trimToDefault(step.actionInput(), ""),
                step.dependsOn(),
                TextUtil.trimToDefault(step.expectedObservation(), "")
        );
    }
}
