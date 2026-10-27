package com.link.linkagent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.core.*;
import com.link.linkagent.core.multi.*;
import com.link.linkagent.core.plan.AgentAnswerSynthesizer;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.prompt.StubPromptService;
import com.link.linkagent.tool.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AssistantToolsAndBusinessTest {
    @Test void platformScopeMustHideUnreviewedToolsWithoutChangingGeneralAgentRegistry() {
        Tool unreviewed=new Tool() {
            public String getName() { return "unreviewed_mcp_write"; }
            public String getDescription() { return "未审核的外部写工具"; }
            public String execute(String input) { throw new AssertionError("不应执行"); }
        };
        var registry=new ToolRegistry(List.of(unreviewed)); registry.init();
        assertThat(registry.getTool(unreviewed.getName())).isNotNull();
        try (var scope=AssistantInvocationContext.open(new AssistantInvocationContext.State("task-1",true))) {
            assertThat(registry.getTool(unreviewed.getName())).isNull();
            assertThat(registry.getAllTools()).isEmpty();
        }
        assertThat(registry.getTool(unreviewed.getName())).isNotNull();
    }
    @Test void scopedTaskMustReachAsyncToolAndMustNotLeakAfterReturn() {
        var context=mock(PlatformAssistantContextService.class);
        when(context.details("task-1","summary")).thenReturn(Map.of("taskId","task-1"));
        when(context.write(any())).thenReturn("当前任务真实数据");
        try (var validation=Validation.buildDefaultValidatorFactory()) {
            var tool=new CreatorTaskContextTool(context,new ObjectMapper(),validation.getValidator());
            var registry=new ToolRegistry(List.of(tool)); registry.init();
            var executor=new ToolExecutor(registry,new ToolExecutionProperties(5,0));
            try (var scope=AssistantInvocationContext.open(new AssistantInvocationContext.State("task-1",true))) {
                assertThat(executor.execute(new ToolCall(tool.getName(),"{\"taskId\":\"task-1\",\"section\":\"summary\"}")).result())
                        .isEqualTo("当前任务真实数据");
                assertThat(executor.execute(new ToolCall(tool.getName(),"{\"taskId\":\"task-2\",\"section\":\"summary\"}")).result())
                        .startsWith("Error:").contains("当前任务");
                assertThat(executor.execute(new ToolCall(tool.getName(),"{\"taskId\":\"task-1\",\"section\":\"arbitrary_sql\"}")).result())
                        .startsWith("Error:");
            }
            assertThat(AssistantInvocationContext.current()).isNull();
            assertThat(executor.execute(new ToolCall(tool.getName(),"{\"taskId\":\"task-1\",\"section\":\"summary\"}")).result())
                    .startsWith("Error:");
            verify(context,times(1)).details("task-1","summary");
        }
    }

    @Test void businessMustRunThreeIndependentWorkersConcurrentlyWithTaskAndUsageContext() throws Exception {
        var llm=mock(LLMService.class);
        var entered=new CountDownLatch(3);
        var count=new AtomicInteger();
        when(llm.chat(anyString(),anyString())).thenAnswer(call -> {
            count.incrementAndGet(); entered.countDown();
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(AssistantInvocationContext.current().taskId()).isEqualTo("task-1");
            assertThat(AssistantInvocationContext.current().allowProposals()).isFalse();
            assertThat(LlmUsageContext.current().taskId()).isEqualTo("task-1");
            assertThat((String)call.getArgument(1)).contains("正式发布资料：真实标题","评论：哪里下载");
            return "分析意见：资料不足时明确限制";
        });
        var planner=mock(MultiAgentPlanner.class);
        var synth=mock(AgentAnswerSynthesizer.class);
        var multi=new MultiAgentOrchestrator(planner,synth,List.of(new DirectReasoningWorkerAgent(llm,new StubPromptService())));
        var mapper=mock(AssistantMapper.class);
        var service=new CreatorBusinessAgentService(multi,new CreatorAgentTraceService(mapper,new ObjectMapper().findAndRegisterModules()));
        try (var usage=LlmUsageContext.open("task-1","总体复盘")) {
            assertThat(service.review("task-1","正式发布资料：真实标题\n评论：哪里下载"))
                    .contains("分析意见","原始资料");
        }
        assertThat(count.get()).isEqualTo(3);
        verifyNoInteractions(planner,synth);
        verify(mapper).finishTrace(anyString(),eq("SUCCEEDED"),contains("workerTraces"),isNull());
        assertThat(AssistantInvocationContext.current()).isNull();
        assertThat(LlmUsageContext.current()).isNull();
    }

    @Test void failedWorkerMustFailBusinessAnalysisAndKeepTrace() {
        var llm=mock(LLMService.class);
        when(llm.chat(anyString(),anyString())).thenThrow(new IllegalStateException("供应商不可用"));
        var multi=new MultiAgentOrchestrator(mock(MultiAgentPlanner.class),mock(AgentAnswerSynthesizer.class),
                List.of(new DirectReasoningWorkerAgent(llm,new StubPromptService())));
        var mapper=mock(AssistantMapper.class);
        var service=new CreatorBusinessAgentService(multi,new CreatorAgentTraceService(mapper,new ObjectMapper()));
        assertThatThrownBy(() -> service.review("task-1","发布后资料")).hasMessageContaining("未完成");
        verify(mapper).finishTrace(anyString(),eq("FAILED"),contains("workerTraces"),anyString());
    }
}
