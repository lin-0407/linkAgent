package com.link.linkagent.core.multi;

import com.link.linkagent.core.plan.AgentAnswerSynthesizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultiAgentOrchestratorTest {

    @Test
    void shouldExecuteIndependentWorkersAndKeepTraceOrder() {
        MultiAgentPlanner planner = mock(MultiAgentPlanner.class);
        AgentAnswerSynthesizer synthesizer = mock(AgentAnswerSynthesizer.class);
        RecordingWorker worker = new RecordingWorker("worker_a", WorkerStatus.SUCCESS);
        when(planner.plan(anyString(), anyString(), anyList())).thenReturn(new WorkerPlan(
                "目标",
                List.of(
                        new WorkerCall(2, "worker_a", "子任务2", "", List.of()),
                        new WorkerCall(1, "worker_a", "子任务1", "", List.of())
                ),
                "并行执行独立 Worker",
                "覆盖用户诉求"
        ));
        when(synthesizer.synthesizeMultiAgentResult(anyString(), anyString(), anyList())).thenReturn("ok");

        MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(planner, synthesizer, List.of(worker));

        var result = orchestrator.run("", "测试请求");

        assertThat(result.workerTraces())
                .extracting(AgentWorkerTrace::callId)
                .containsExactly(1, 2);
        assertThat(worker.executedCallIds()).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void shouldSkipWorkerWhenDependencyFailed() {
        MultiAgentPlanner planner = mock(MultiAgentPlanner.class);
        AgentAnswerSynthesizer synthesizer = mock(AgentAnswerSynthesizer.class);
        RecordingWorker worker = new RecordingWorker("worker_a", WorkerStatus.FAILED);
        when(planner.plan(anyString(), anyString(), anyList())).thenReturn(new WorkerPlan(
                "目标",
                List.of(
                        new WorkerCall(1, "worker_a", "失败子任务", "", List.of()),
                        new WorkerCall(2, "worker_a", "依赖失败子任务", "", List.of(1))
                ),
                "依赖失败时跳过后续 Worker",
                "覆盖用户诉求"
        ));
        when(synthesizer.synthesizeMultiAgentResult(anyString(), anyString(), anyList())).thenReturn("ok");

        MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(planner, synthesizer, List.of(worker));

        var result = orchestrator.run("", "测试请求");

        assertThat(result.workerTraces())
                .extracting(AgentWorkerTrace::status)
                .containsExactly(WorkerStatus.FAILED, WorkerStatus.SKIPPED);
        assertThat(worker.executedCallIds()).containsExactly(1);
    }

    private static class RecordingWorker implements WorkerAgent {

        private final String name;
        private final WorkerStatus status;
        private final List<Integer> executedCallIds = new CopyOnWriteArrayList<>();

        private RecordingWorker(String name, WorkerStatus status) {
            this.name = name;
            this.status = status;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String role() {
            return "测试 Worker";
        }

        @Override
        public String capability() {
            return "用于验证多 Agent 调度。";
        }

        @Override
        public AgentWorkerTrace execute(WorkerCall call, String conversationContext, String userMessage) {
            executedCallIds.add(call.id());
            return new AgentWorkerTrace(
                    call.id(),
                    name(),
                    role(),
                    capability(),
                    status,
                    call.subTask(),
                    call.sharedContext(),
                    status == WorkerStatus.SUCCESS ? "成功" : null,
                    WorkerBrief.fromSummary("测试摘要", List.of(), status),
                    List.of(),
                    status == WorkerStatus.SUCCESS ? null : "测试失败",
                    null,
                    List.of()
            );
        }

        private List<Integer> executedCallIds() {
            return executedCallIds;
        }
    }
}
