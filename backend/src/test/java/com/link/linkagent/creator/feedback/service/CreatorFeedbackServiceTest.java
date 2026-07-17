package com.link.linkagent.creator.feedback.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.feedback.mapper.CreatorFeedbackMapper;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackSaveRequest;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

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
        CreatorFeedbackService service = new CreatorFeedbackService(
                taskMapper,
                feedbackMapper,
                llmService,
                objectMapper,
                transactionTemplate,
                evidenceRetrievalService,
                promptService,
                mediaProperties
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
                promptService
        );
    }
}
