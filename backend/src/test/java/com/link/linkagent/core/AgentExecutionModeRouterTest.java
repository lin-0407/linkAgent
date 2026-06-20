package com.link.linkagent.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutionModeRouterTest {

    private final AgentExecutionModeRouter router = new AgentExecutionModeRouter();

    @Test
    void shouldRespectExplicitMode() {
        AgentExecutionMode mode = router.route(AgentExecutionMode.MULTI_AGENT, "简单问题");

        assertThat(mode).isEqualTo(AgentExecutionMode.MULTI_AGENT);
    }

    @Test
    void shouldKeepSimplePreferenceInReact() {
        AgentExecutionMode mode = router.route(AgentExecutionMode.AUTO, "以后请优先用 Java 举例");

        assertThat(mode).isEqualTo(AgentExecutionMode.REACT);
    }

    @Test
    void shouldRouteComplexPlanningTaskToPlanExecute() {
        AgentExecutionMode mode = router.route(AgentExecutionMode.AUTO, "帮我拆解这个功能的实现步骤和排查方案");

        assertThat(mode).isEqualTo(AgentExecutionMode.PLAN_EXECUTE);
    }

    @Test
    void shouldRouteMultiPerspectiveTaskToMultiAgent() {
        AgentExecutionMode mode = router.route(AgentExecutionMode.AUTO, "从标题、受众和评论反馈三个角度做复盘");

        assertThat(mode).isEqualTo(AgentExecutionMode.MULTI_AGENT);
    }
}
