package com.link.linkagent.core.plan;

import com.link.linkagent.tool.Tool;
import com.link.linkagent.tool.ToolExecutionProperties;
import com.link.linkagent.tool.ToolExecutor;
import com.link.linkagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanAndExecuteAgentTest {

    @Test
    void shouldReplanAndExecuteReplacementStepWhenOriginalStepFails() {
        AgentPlanner planner = mock(AgentPlanner.class);
        AgentReplanner replanner = mock(AgentReplanner.class);
        AgentAnswerSynthesizer synthesizer = mock(AgentAnswerSynthesizer.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getTool("valid_tool")).thenReturn(new FixedTool("valid_tool", "新证据"));
        PlanAndExecuteAgent agent = new PlanAndExecuteAgent(
                planner,
                replanner,
                new ToolExecutor(registry, new ToolExecutionProperties(10, 0)),
                registry,
                synthesizer
        );
        when(planner.plan(anyString(), anyString())).thenReturn(new AgentPlan(
                "目标",
                List.of(new AgentPlanStep(1, "失败步骤", "missing_tool", "input", List.of(), "拿到证据")),
                "初始计划",
                "覆盖用户诉求"
        ));
        when(replanner.replan(anyString(), anyString(), any(PlanExecutionState.class))).thenReturn(new AgentPlan(
                "目标",
                List.of(new AgentPlanStep(1, "替代步骤", "valid_tool", "input", List.of(), "拿到新证据")),
                "改用可用工具",
                "已覆盖剩余诉求"
        ));
        when(synthesizer.synthesizePlanResult(anyString(), anyString(), any(AgentPlan.class), anyList()))
                .thenReturn("最终回答");

        var result = agent.run("", "用户请求");

        assertThat(result.stopReason()).isNull();
        assertThat(result.planTrace().executions())
                .extracting(PlanStepExecution::status)
                .containsExactly(PlanStepStatus.FAILED, PlanStepStatus.SUCCESS);
        assertThat(result.planTrace().executions())
                .extracting(PlanStepExecution::stepId)
                .containsExactly(1, 2);
    }

    @Test
    void shouldKeepFailureWhenReplannerReturnsEmptySteps() {
        AgentPlanner planner = mock(AgentPlanner.class);
        AgentReplanner replanner = mock(AgentReplanner.class);
        AgentAnswerSynthesizer synthesizer = mock(AgentAnswerSynthesizer.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        PlanAndExecuteAgent agent = new PlanAndExecuteAgent(
                planner,
                replanner,
                new ToolExecutor(registry, new ToolExecutionProperties(10, 0)),
                registry,
                synthesizer
        );
        when(planner.plan(anyString(), anyString())).thenReturn(new AgentPlan(
                "目标",
                List.of(new AgentPlanStep(1, "失败步骤", "missing_tool", "input", List.of(), "拿到证据")),
                "初始计划",
                "覆盖用户诉求"
        ));
        when(replanner.replan(anyString(), anyString(), any(PlanExecutionState.class))).thenReturn(new AgentPlan(
                "目标",
                List.of(),
                "没有替代路线",
                "剩余诉求无法满足"
        ));
        when(synthesizer.synthesizePlanResult(anyString(), anyString(), any(AgentPlan.class), anyList()))
                .thenReturn("最终回答");

        var result = agent.run("", "用户请求");

        assertThat(result.stopReason()).contains("失败 1 步");
        assertThat(result.planTrace().executions())
                .extracting(PlanStepExecution::status)
                .containsExactly(PlanStepStatus.FAILED);
    }

    private record FixedTool(String name, String result) implements Tool {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "测试工具";
        }

        @Override
        public String execute(String input) {
            return result;
        }
    }
}
