package com.link.linkagent.creator.feedback.service;

import com.link.linkagent.creator.feedback.config.CreatorFeedbackRagProperties;
import com.link.linkagent.creator.feedback.mapper.CreatorFeedbackMapper;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackEvidenceIndexRequest;
import com.link.linkagent.creator.media.workflow.CreatorMediaWorkflowGateService;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.settings.service.RuntimeSettingService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 反馈证据索引的媒体门禁回归测试。
 * <p>
 * 索引重建会调用 Embedding 并写回明细状态，因此必须和反馈导入、分析共享成片就绪要求。
 */
class CreatorFeedbackEvidenceIndexServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectIndexRebuildBeforeEmbeddingWhenMediaGateRejects() {
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorFeedbackMapper feedbackMapper = mock(CreatorFeedbackMapper.class);
        ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);
        RuntimeSettingService runtimeSettingService = mock(RuntimeSettingService.class);
        CreatorMediaWorkflowGateService mediaWorkflowGateService = mock(CreatorMediaWorkflowGateService.class);
        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(task("task-1")));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "成片尚未通过媒体探测"))
                .when(mediaWorkflowGateService)
                .ensureReadyForPostPublish("task-1", "default", "观众反馈");
        CreatorFeedbackEvidenceIndexService service = new CreatorFeedbackEvidenceIndexService(
                new CreatorFeedbackRagProperties(),
                taskMapper,
                feedbackMapper,
                vectorStoreProvider,
                runtimeSettingService,
                mediaWorkflowGateService
        );

        assertThatThrownBy(() -> service.rebuild(
                "task-1",
                new CreatorFeedbackEvidenceIndexRequest(null, null)
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(mediaWorkflowGateService).ensureMediaEnabled("观众反馈");
        verifyNoInteractions(feedbackMapper, vectorStoreProvider, runtimeSettingService);
    }

    private CreatorTaskRecord task(String taskId) {
        CreatorTaskRecord task = new CreatorTaskRecord();
        task.setTaskId(taskId);
        task.setUserId("default");
        return task;
    }
}
