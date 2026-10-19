package com.link.linkagent.assistant;

import com.link.linkagent.assistant.AssistantModels.*;
import com.link.linkagent.creator.feedback.service.CreatorFeedbackService;
import com.link.linkagent.creator.report.service.CreatorReportService;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AssistantActionServiceTest {
    private final AssistantMapper mapper=mock(AssistantMapper.class);
    private final PlatformAssistantContextService context=mock(PlatformAssistantContextService.class);
    private final CreatorTaskMapper tasks=mock(CreatorTaskMapper.class);
    private final CreatorFeedbackService feedback=mock(CreatorFeedbackService.class);
    private final CreatorReportService reports=mock(CreatorReportService.class);
    private final AtomicReference<ActionProposal> stored=new AtomicReference<>();
    private AssistantActionService service;

    @BeforeEach void setup() {
        var task=new CreatorTaskRecord(); task.setTaskId("task-1"); task.setTaskName("Java 项目介绍");
        when(tasks.findTaskByTaskId("task-1")).thenReturn(Optional.of(task));
        when(context.inspect("/creator","task-1")).thenReturn(new TaskContext("/creator","task-1",task.getTaskName(),
                "FEEDBACK_ANALYZED",null,Map.of(),List.of("ANALYZE_FEEDBACK","GENERATE_REVIEW")));
        when(context.actionSource("task-1")).thenReturn("原始评论和发布后资料-v1");
        when(mapper.insertAction(any())).thenAnswer(call -> { stored.set(call.getArgument(0)); return 1; });
        when(mapper.findAction(anyString())).thenAnswer(call -> Optional.ofNullable(stored.get()));
        when(mapper.claimAction(anyString(),any())).thenAnswer(call -> {
            var value=stored.get();
            if (!"PENDING".equals(value.status())) return 0;
            stored.set(withStatus(value,"RUNNING",null)); return 1;
        });
        when(mapper.finishAction(anyString(),anyString(),anyString())).thenAnswer(call -> {
            stored.set(withStatus(stored.get(),call.getArgument(1),call.getArgument(2))); return 1;
        });
        service=new AssistantActionService(mapper,context,tasks,feedback,reports);
    }

    @Test void preparingMustNotExecuteAndRepeatedConfirmationMustNotRepeatModelCall() {
        var proposal=service.prepare(new PrepareAction("task-1",ActionType.ANALYZE_FEEDBACK,"重点看误解"));
        assertThat(proposal.status()).isEqualTo("PENDING");
        assertThat(proposal.description()).contains("费用","失效");
        verifyNoInteractions(feedback,reports);
        assertThat(service.confirm(proposal.proposalId(),new ConfirmAction("task-1",true)).status()).isEqualTo("SUCCEEDED");
        service.confirm(proposal.proposalId(),new ConfirmAction("task-1",true));
        verify(feedback,times(1)).analyze(eq("task-1"),argThat(value -> "重点看误解".equals(value.customGuidance())));
    }

    @Test void refusingConfirmationOrWrongTaskMustNotExecute() {
        var proposal=service.prepare(new PrepareAction("task-1",ActionType.GENERATE_REVIEW,null));
        assertThatThrownBy(() -> service.confirm(proposal.proposalId(),new ConfirmAction("task-1",false)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("确认");
        assertThatThrownBy(() -> service.confirm(proposal.proposalId(),new ConfirmAction("task-2",true)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("不一致");
        verifyNoInteractions(feedback,reports);
    }

    @Test void changedSourceIncludingSameCountEditedCommentsMustRequireNewProposal() {
        var proposal=service.prepare(new PrepareAction("task-1",ActionType.ANALYZE_FEEDBACK,null));
        when(context.actionSource("task-1")).thenReturn("同样条数但是已修改的评论-v2");
        assertThatThrownBy(() -> service.confirm(proposal.proposalId(),new ConfirmAction("task-1",true)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("数据已经变化");
        verify(mapper,never()).claimAction(anyString(),any());
        verifyNoInteractions(feedback,reports);
    }

    @Test void expiredProposalMustNotExecute() {
        var proposal=service.prepare(new PrepareAction("task-1",ActionType.GENERATE_REVIEW,null));
        stored.set(new ActionProposal(proposal.proposalId(),proposal.taskId(),proposal.taskName(),proposal.actionType(),
                proposal.guidance(),proposal.description(),proposal.sourceFingerprint(),"PENDING",null,
                LocalDateTime.now().minusMinutes(30),LocalDateTime.now().minusMinutes(15)));
        assertThatThrownBy(() -> service.confirm(proposal.proposalId(),new ConfirmAction("task-1",true)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("过期");
        verifyNoInteractions(feedback,reports);
    }

    @Test void businessFailureMustBeVisibleAndMustNotAutomaticallyRetry() {
        var proposal=service.prepare(new PrepareAction("task-1",ActionType.GENERATE_REVIEW,null));
        when(reports.analyze(anyString(),any())).thenThrow(new IllegalStateException("模型不可用"));
        assertThatThrownBy(() -> service.confirm(proposal.proposalId(),new ConfirmAction("task-1",true)))
                .hasMessage("模型不可用");
        assertThat(stored.get().status()).isEqualTo("FAILED");
        service.confirm(proposal.proposalId(),new ConfirmAction("task-1",true));
        verify(reports,times(1)).analyze(anyString(),any());
    }

    @Test void currentGateMustBeRecheckedBeforeConfirmation() {
        var proposal=service.prepare(new PrepareAction("task-1",ActionType.ANALYZE_FEEDBACK,null));
        when(context.inspect("/creator","task-1")).thenReturn(new TaskContext("/creator","task-1","任务", "DRAFT",
                "成片试映尚未完成",Map.of(),List.of()));
        assertThatThrownBy(() -> service.confirm(proposal.proposalId(),new ConfirmAction("task-1",true)))
                .hasMessageContaining("成片试映尚未完成");
        verifyNoInteractions(feedback,reports);
    }

    private ActionProposal withStatus(ActionProposal value,String status,String message) {
        return new ActionProposal(value.proposalId(),value.taskId(),value.taskName(),value.actionType(),value.guidance(),
                value.description(),value.sourceFingerprint(),status,message,value.createTime(),value.expiresAt());
    }
}
