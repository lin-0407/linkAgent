package com.link.linkagent.core;

import com.link.linkagent.core.plan.PlanAndExecuteAgent;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.StubPromptService;
import com.link.linkagent.settings.service.RuntimeSettingService;
import com.link.linkagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentExecutorAutoPlanFallbackTest {

    @Test
    void shouldCompleteSimplePreferenceRequestWhenAutoPlanHasNoSteps() {
        LLMService llm = mock(LLMService.class);
        PlanAndExecuteAgent planner = emptyPlanner();
        when(llm.chat(anyString(), anyString())).thenReturn("Final Answer: 已记住，以后按这个风格写。");

        var response = executor(llm, planner).runTask("记住我的偏好：先展示结果，再讲操作步骤。", AgentExecutionMode.AUTO);

        assertThat(response.finalAnswer()).isEqualTo("已记住，以后按这个风格写。");
        assertThat(response.executionMode()).isEqualTo(AgentExecutionMode.REACT);
        verify(llm).chat(anyString(), anyString());
    }

    @Test
    void shouldKeepExplicitPlanResultVisible() {
        LLMService llm = mock(LLMService.class);

        var response = executor(llm, emptyPlanner()).runTask("生成计划", AgentExecutionMode.PLAN_EXECUTE);

        assertThat(response.executionMode()).isEqualTo(AgentExecutionMode.PLAN_EXECUTE);
        assertThat(response.finalAnswer()).isEqualTo("没有可引用证据，无法可靠回答。");
        verify(llm, never()).chat(anyString(), anyString());
    }

    private PlanAndExecuteAgent emptyPlanner() {
        PlanAndExecuteAgent planner = mock(PlanAndExecuteAgent.class);
        when(planner.run(anyString(), anyString())).thenReturn(AgentRunResult.planExecute(
                "没有可引用证据，无法可靠回答。", null, List.of(), null));
        return planner;
    }

    private AgentExecutor executor(LLMService llm, PlanAndExecuteAgent planner) {
        // 通过公开任务入口验证回退行为，不引入记忆和网络依赖。
        return new AgentExecutor(llm, new ToolRegistry(List.of()), null, null, null, null, null,
                new StubPromptService(), mock(RuntimeSettingService.class), new AgentExecutionModeRouter(),
                planner, null, null, null, null, null, null, null);
    }
}
