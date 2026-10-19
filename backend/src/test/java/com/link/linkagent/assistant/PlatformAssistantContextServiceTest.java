package com.link.linkagent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.feedback.mapper.CreatorFeedbackMapper;
import com.link.linkagent.creator.report.mapper.CreatorReportMapper;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.workflow.mapper.CreatorWorkflowMapper;
import com.link.linkagent.creator.media.workflow.CreatorMediaWorkflowGateService;
import com.link.linkagent.creator.competitor.mapper.CreatorCompetitorMapper;
import com.link.linkagent.creator.bilibili.service.CreatorPublishedVideoContextService;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlatformAssistantContextServiceTest {
    private final CreatorTaskMapper tasks=mock(CreatorTaskMapper.class);
    private final CreatorMediaWorkflowGateService gate=mock(CreatorMediaWorkflowGateService.class);
    private final PlatformAssistantContextService service=new PlatformAssistantContextService(tasks,
            mock(CreatorFeedbackMapper.class),mock(CreatorReportMapper.class),mock(CreatorSuggestionMapper.class),
            mock(CreatorWorkflowMapper.class),gate,new ObjectMapper().findAndRegisterModules(),
            mock(CreatorCompetitorMapper.class),mock(CreatorPublishedVideoContextService.class),
            mock(com.link.linkagent.creator.production.mapper.ProductionPlanMapper.class));

    @Test void noTaskMustExplainPlatformWithoutReadingUnrelatedTasks() {
        var result=service.inspect("/knowledge",null);
        assertThat(result.taskId()).isNull();
        assertThat(result.availableActions()).isEmpty();
        assertThat(service.guide()).contains("成片试映","用户实际发布","创建新任务","platform_operations");
        verifyNoInteractions(tasks,gate);
    }

    @Test void taskSnapshotMustUseReadOnlyGateAndMustNotRecoverOrMutateMedia() {
        var task=new CreatorTaskRecord(); task.setTaskId("task-1"); task.setTaskName("真实任务"); task.setUserId("default");
        task.setStatus("DRAFT"); task.setPlanningSkipped(true);
        when(tasks.findTaskByTaskId("task-1")).thenReturn(Optional.of(task));
        when(gate.inspectPostPublishReadiness("task-1","default","观众反馈"))
                .thenReturn(new CreatorMediaWorkflowGateService.PostPublishReadiness(false,"尚未完成成片试映"));
        var result=service.inspect("/creator","task-1");
        assertThat(result.taskName()).isEqualTo("真实任务");
        assertThat(result.blockingReason()).isEqualTo("尚未完成成片试映");
        assertThat(result.facts()).containsEntry("planningSkipped",true);
        assertThat(result.availableActions()).isEmpty();
        verify(gate,never()).ensureReadyForPostPublish(anyString(),anyString(),anyString());
    }

    @Test void sessionMustBeIsolatedByTaskIncludingRestoredHistory() {
        String session=service.sessionId(null,"task-1");
        assertThat(session.length()).isLessThanOrEqualTo(64);
        assertThat(service.sessionId(session,"task-1")).isEqualTo(session);
        assertThatThrownBy(() -> service.sessionId(session,"task-2")).hasMessageContaining("其他任务");
        assertThat(service.sessionPrefix(null)).isNotEqualTo(service.sessionPrefix("task-1"));
    }

    @Test void missingTaskMustNotFallBackToAnotherTask() {
        assertThatThrownBy(() -> service.inspect("/creator","missing")).hasMessageContaining("创作任务不存在");
        verifyNoInteractions(gate);
    }
}
