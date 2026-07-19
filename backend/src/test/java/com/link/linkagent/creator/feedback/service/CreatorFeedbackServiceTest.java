package com.link.linkagent.creator.feedback.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.feedback.mapper.CreatorFeedbackMapper;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackSaveRequest;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.probe.service.DraftVideoProbeRecoveryService;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import com.link.linkagent.creator.media.workflow.CreatorMediaWorkflowGateService;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.workflow.mapper.CreatorWorkflowMapper;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowSessionRecord;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStage;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStatus;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 评论弹幕反馈阶段的流程门禁回归测试。
 *
 * 使用 Mock 隔离任务、反馈、模型和脚本相关依赖，验证媒体能力关闭时请求会在第一步被拒绝，
 * 避免测试本身触发外部 I/O 或因为下游依赖而掩盖状态机问题。
 */
class CreatorFeedbackServiceTest {

    @Test
    void shouldRejectAllFeedbackWritesBeforeAnyStateChangeWhenMediaFeatureIsDisabled() {
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorFeedbackMapper feedbackMapper = mock(CreatorFeedbackMapper.class);
        LLMService llmService = mock(LLMService.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        CreatorFeedbackEvidenceRetrievalService evidenceRetrievalService =
                mock(CreatorFeedbackEvidenceRetrievalService.class);
        PromptService promptService = mock(PromptService.class);
        CreatorMediaProperties mediaProperties = new CreatorMediaProperties();
        mediaProperties.setEnabled(false);
        MediaUploadMapper mediaUploadMapper = mock(MediaUploadMapper.class);
        CreatorWorkflowMapper workflowMapper = mock(CreatorWorkflowMapper.class);
        CreatorMediaWorkflowGateService mediaWorkflowGateService = new CreatorMediaWorkflowGateService(
                mediaProperties,
                mediaUploadMapper,
                workflowMapper,
                mock(CreatorSuggestionMapper.class),
                new DraftVideoProbeRecoveryService(mediaProperties, mediaUploadMapper)
        );
        CreatorFeedbackService service = new CreatorFeedbackService(
                taskMapper,
                feedbackMapper,
                llmService,
                objectMapper,
                transactionTemplate,
                evidenceRetrievalService,
                promptService,
                mediaWorkflowGateService
        );

        // 四个入口都会让任务进入或准备进入反馈阶段，关闭试映能力时必须统一返回冲突错误。
        assertThatThrownBy(() -> service.saveFeedback(
                "task-1",
                new CreatorFeedbackSaveRequest("评论样例", null, null)
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.analyze("task-1", null))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.importFeedback("task-1", null))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.fetchFeedback("task-1", null))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // 拒绝必须发生在查任务、写反馈、执行脚本和调用模型之前，才能保证状态不发生任何变化。
        verifyNoInteractions(
                taskMapper,
                feedbackMapper,
                llmService,
                objectMapper,
                transactionTemplate,
                evidenceRetrievalService,
                promptService,
                mediaUploadMapper,
                workflowMapper
        );
    }

    @Test
    void shouldRejectFeedbackWriteWhenCurrentDraftHasNotPassedProbe() {
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorFeedbackMapper feedbackMapper = mock(CreatorFeedbackMapper.class);
        LLMService llmService = mock(LLMService.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        CreatorFeedbackEvidenceRetrievalService evidenceRetrievalService =
                mock(CreatorFeedbackEvidenceRetrievalService.class);
        PromptService promptService = mock(PromptService.class);
        CreatorMediaProperties mediaProperties = new CreatorMediaProperties();
        mediaProperties.setEnabled(true);
        MediaUploadMapper mediaUploadMapper = mock(MediaUploadMapper.class);
        CreatorWorkflowMapper workflowMapper = mock(CreatorWorkflowMapper.class);
        CreatorSuggestionMapper suggestionMapper = mock(CreatorSuggestionMapper.class);
        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(task("task-1")));
        when(workflowMapper.findLatestSession("task-1", CreatorWorkflowStage.PRE_PUBLISH.name()))
                .thenReturn(Optional.of(confirmedSession()));
        when(suggestionMapper.findByTaskId("task-1")).thenReturn(Optional.of(suggestion()));
        when(mediaUploadMapper.findDraftVideo("task-1", "default"))
                .thenReturn(Optional.of(draft("UPLOADED")));
        CreatorFeedbackService service = new CreatorFeedbackService(
                taskMapper,
                feedbackMapper,
                llmService,
                objectMapper,
                transactionTemplate,
                evidenceRetrievalService,
                promptService,
                new CreatorMediaWorkflowGateService(
                        mediaProperties,
                        mediaUploadMapper,
                        workflowMapper,
                        suggestionMapper,
                        new DraftVideoProbeRecoveryService(mediaProperties, mediaUploadMapper)
                )
        );

        assertThatThrownBy(() -> service.saveFeedback(
                "task-1",
                new CreatorFeedbackSaveRequest("评论样例", null, null)
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // 成片未探测通过时，不能写评论样例、调用模型或进入事务。
        verifyNoInteractions(
                feedbackMapper,
                llmService,
                objectMapper,
                transactionTemplate,
                evidenceRetrievalService,
                promptService
        );
    }

    private CreatorTaskRecord task(String taskId) {
        CreatorTaskRecord task = new CreatorTaskRecord();
        task.setTaskId(taskId);
        task.setUserId("default");
        return task;
    }

    private DraftVideoRecord draft(String status) {
        return new DraftVideoRecord(
                1L,
                "version-1",
                "task-1",
                "default",
                1,
                "V1 初剪",
                "source.mp4",
                "linkagent-private-media",
                "users/default/tasks/task-1/versions/version-1/original/source.mp4",
                "video/mp4",
                1024L,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                status,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private CreatorWorkflowSessionRecord confirmedSession() {
        CreatorWorkflowSessionRecord session = new CreatorWorkflowSessionRecord();
        session.setTaskId("task-1");
        session.setUserId("default");
        session.setStage(CreatorWorkflowStage.PRE_PUBLISH.name());
        session.setStatus(CreatorWorkflowStatus.CONFIRMED.name());
        session.setConfirmedResultId("suggestion-1");
        return session;
    }

    private CreatorSuggestionRecord suggestion() {
        CreatorSuggestionRecord suggestion = new CreatorSuggestionRecord();
        suggestion.setTaskId("task-1");
        suggestion.setSuggestionId("suggestion-1");
        return suggestion;
    }
}
