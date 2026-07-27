package com.link.linkagent.creator.workflow.service;

import com.link.linkagent.creator.interactive.mapper.CreatorInteractiveMapper;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.preference.service.CreatorPreferenceService;
import com.link.linkagent.creator.profile.service.CreatorProfileService;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.PrePublishAnalyzeRequest;
import com.link.linkagent.creator.suggestion.service.PrePublishSuggestionService;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.workflow.event.CreatorWorkflowEventPublisher;
import com.link.linkagent.creator.workflow.mapper.CreatorWorkflowMapper;
import com.link.linkagent.creator.workflow.model.CreatorIntentAlignmentContext;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowSessionRecord;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStage;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStatus;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.usage.LlmApiUsageService;
import com.link.linkagent.settings.service.RuntimeSettingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 发布前工作流的状态机回归测试。
 *
 * 这里不启动 Spring 上下文，而是用 Mock 隔离数据库和模型依赖，
 * 这样可以只验证“抢占失败后不得继续调用模型”的并发边界，避免真实 LLM 调用让测试变慢或产生费用。
 */
class CreatorWorkflowServiceTest {
    @Test
    void shouldCountOnlySuccessfulPlansGeneratedFromTheSameContext() {
        PrePublishAnalyzeRequest request = new PrePublishAnalyzeRequest(
                "用户原始上下文",
                null,
                null,
                null,
                null,
                null
        );
        String contextHash = CreatorWorkflowService.buildPlanContextHash(
                new CreatorIntentAlignmentContext("用户想展示当前流程为什么麻烦", "用户想展示当前流程为什么麻烦"),
                request
        );
        CreatorWorkflowSessionRecord sessionRecord = new CreatorWorkflowSessionRecord();
        sessionRecord.setPlanContextHash(contextHash);
        sessionRecord.setPlanGenerationCount(3);

        assertThat(CreatorWorkflowService.resolveSuccessfulGenerationCount(sessionRecord, contextHash))
                .isEqualTo(3);

        String changedHash = CreatorWorkflowService.buildPlanContextHash(
                new CreatorIntentAlignmentContext("用户补充：重点不是部署，而是交互成本", "用户补充：重点不是部署，而是交互成本"),
                request
        );
        assertThat(CreatorWorkflowService.resolveSuccessfulGenerationCount(sessionRecord, changedHash))
                .isZero();
    }

    @Test
    void shouldRejectConfirmationBeforeAnyStateChangeWhenMediaFeatureIsDisabled() {
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorSuggestionMapper suggestionMapper = mock(CreatorSuggestionMapper.class);
        CreatorWorkflowMapper workflowMapper = mock(CreatorWorkflowMapper.class);
        CreatorInteractiveMapper interactiveMapper = mock(CreatorInteractiveMapper.class);
        CreatorIntentAlignmentService alignmentService = mock(CreatorIntentAlignmentService.class);
        PrePublishSuggestionService suggestionService = mock(PrePublishSuggestionService.class);
        CreatorWorkflowEventPublisher eventPublisher = mock(CreatorWorkflowEventPublisher.class);
        LlmApiUsageService usageService = mock(LlmApiUsageService.class);
        LLMService llmService = mock(LLMService.class);
        RuntimeSettingService runtimeSettingService = mock(RuntimeSettingService.class);
        CreatorPreferenceService preferenceService = mock(CreatorPreferenceService.class);
        CreatorProfileService profileService = mock(CreatorProfileService.class);
        CreatorMediaProperties mediaProperties = new CreatorMediaProperties();
        mediaProperties.setEnabled(false);
        CreatorWorkflowService service = new CreatorWorkflowService(
                taskMapper,
                suggestionMapper,
                workflowMapper,
                interactiveMapper,
                alignmentService,
                suggestionService,
                eventPublisher,
                usageService,
                llmService,
                runtimeSettingService,
                preferenceService,
                profileService,
                mediaProperties
        );

        // 媒体能力关闭时，确认动作不能把任务推进到旧的反馈链路。
        assertThatThrownBy(() -> service.confirmPrePublishSuggestion(
                "task-1",
                "session-1",
                new com.link.linkagent.creator.workflow.model.CreatorWorkflowConfirmRequest("suggestion-1")
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // 门禁必须在读取会话、建议和写入任务状态前执行，才能保证任务仍停留在发布方案阶段。
        verifyNoInteractions(
                taskMapper,
                suggestionMapper,
                workflowMapper,
                interactiveMapper,
                alignmentService,
                suggestionService,
                eventPublisher,
                usageService,
                llmService,
                runtimeSettingService,
                preferenceService,
                profileService
        );
    }


    @Test
    void shouldRejectAnalysisBeforeCallingModelWhenAnotherRequestHasClaimedSession() {
        // 工作流服务依赖较多；本用例只关心会话状态抢占，因此其余依赖均使用 Mock 隔离。
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorSuggestionMapper suggestionMapper = mock(CreatorSuggestionMapper.class);
        CreatorWorkflowMapper workflowMapper = mock(CreatorWorkflowMapper.class);
        PrePublishSuggestionService suggestionService = mock(PrePublishSuggestionService.class);
        CreatorWorkflowEventPublisher eventPublisher = mock(CreatorWorkflowEventPublisher.class);
        LlmApiUsageService usageService = mock(LlmApiUsageService.class);
        LLMService llmService = mock(LLMService.class);
        RuntimeSettingService runtimeSettingService = mock(RuntimeSettingService.class);
        CreatorPreferenceService preferenceService = mock(CreatorPreferenceService.class);
        CreatorProfileService profileService = mock(CreatorProfileService.class);
        CreatorWorkflowService service = new CreatorWorkflowService(
                taskMapper,
                suggestionMapper,
                workflowMapper,
                mock(CreatorInteractiveMapper.class),
                mock(CreatorIntentAlignmentService.class),
                suggestionService,
                eventPublisher,
                usageService,
                llmService,
                runtimeSettingService,
                preferenceService,
                profileService,
                enabledMediaProperties()
        );

        // 构造一个已具备材料、处于等待用户输入状态的正常发布前会话，
        // 确保本用例失败的唯一原因是并发抢占，而不是任务或材料前置校验。
        CreatorTaskRecord taskRecord = new CreatorTaskRecord();
        taskRecord.setTaskId("task-1");
        taskRecord.setUserId("default");
        CreatorMaterialRecord materialRecord = new CreatorMaterialRecord();
        materialRecord.setTaskId("task-1");
        materialRecord.setMaterialType("MANUSCRIPT");
        materialRecord.setContent("用于验证并发抢占的文稿材料");
        CreatorWorkflowSessionRecord sessionRecord = new CreatorWorkflowSessionRecord();
        sessionRecord.setSessionId("session-1");
        sessionRecord.setTaskId("task-1");
        sessionRecord.setUserId("default");
        sessionRecord.setStage(CreatorWorkflowStage.PRE_PUBLISH.name());
        sessionRecord.setStatus(CreatorWorkflowStatus.WAITING_USER_INPUT.name());

        // 模拟另一条请求已先把会话切换为 RUNNING：条件更新影响行数为 0。
        // 这比只模拟内存状态更接近真实并发场景，因为最终裁决必须以数据库条件更新结果为准。
        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(taskRecord));
        when(workflowMapper.findSession("task-1", "session-1")).thenReturn(Optional.of(sessionRecord));
        when(taskMapper.listMaterialsByTaskId("task-1")).thenReturn(List.of(materialRecord));
        when(workflowMapper.claimPrePublishExecution("session-1", CreatorWorkflowStatus.RUNNING.name()))
                .thenReturn(0);

        // 抢占失败应明确返回 409，而不是继续执行后在消息或建议覆盖时才暴露不一致。
        assertThatThrownBy(() -> service.analyzePrePublishWorkflow(
                "task-1",
                "session-1",
                new PrePublishAnalyzeRequest(null, null, null, null, null, null)
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // 409 必须发生在画像初始化和建议生成之前，
        // 否则虽然最终拒绝了请求，仍会产生不必要的 LLM 调用成本。
        verifyNoInteractions(profileService, suggestionService, runtimeSettingService);
    }

    @Test
    void shouldRejectDraftGenerationBeforeCallingModelWhenAnotherRequestHasClaimedSession() {
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorSuggestionMapper suggestionMapper = mock(CreatorSuggestionMapper.class);
        CreatorWorkflowMapper workflowMapper = mock(CreatorWorkflowMapper.class);
        PrePublishSuggestionService suggestionService = mock(PrePublishSuggestionService.class);
        CreatorWorkflowEventPublisher eventPublisher = mock(CreatorWorkflowEventPublisher.class);
        LlmApiUsageService usageService = mock(LlmApiUsageService.class);
        LLMService llmService = mock(LLMService.class);
        RuntimeSettingService runtimeSettingService = mock(RuntimeSettingService.class);
        CreatorPreferenceService preferenceService = mock(CreatorPreferenceService.class);
        CreatorProfileService profileService = mock(CreatorProfileService.class);
        CreatorWorkflowService service = new CreatorWorkflowService(
                taskMapper,
                suggestionMapper,
                workflowMapper,
                mock(CreatorInteractiveMapper.class),
                mock(CreatorIntentAlignmentService.class),
                suggestionService,
                eventPublisher,
                usageService,
                llmService,
                runtimeSettingService,
                preferenceService,
                profileService,
                enabledMediaProperties()
        );

        CreatorTaskRecord taskRecord = new CreatorTaskRecord();
        taskRecord.setTaskId("task-1");
        CreatorMaterialRecord materialRecord = new CreatorMaterialRecord();
        materialRecord.setTaskId("task-1");
        materialRecord.setMaterialType("TITLE_DRAFT");
        materialRecord.setContent("用于生成文稿的短大纲");
        CreatorWorkflowSessionRecord sessionRecord = new CreatorWorkflowSessionRecord();
        sessionRecord.setSessionId("session-1");
        sessionRecord.setTaskId("task-1");
        sessionRecord.setStage(CreatorWorkflowStage.PRE_PUBLISH.name());
        sessionRecord.setStatus(CreatorWorkflowStatus.WAITING_USER_INPUT.name());
        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(taskRecord));
        when(workflowMapper.findSession("task-1", "session-1")).thenReturn(Optional.of(sessionRecord));
        when(taskMapper.listMaterialsByTaskId("task-1")).thenReturn(List.of(materialRecord));
        when(workflowMapper.claimPrePublishExecution("session-1", CreatorWorkflowStatus.RUNNING.name()))
                .thenReturn(0);

        // 文稿草稿与发布方案都会调用模型，必须共享同一份会话抢占机制。
        assertThatThrownBy(() -> service.generatePrePublishManuscriptDraft(
                "task-1",
                "session-1",
                new com.link.linkagent.creator.workflow.model.PrePublishDraftRequest(null)
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verifyNoInteractions(llmService);
    }

    @Test
    void shouldRejectRepeatedDraftGenerationWhenShortAiDraftIsAlreadySaved() {
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorSuggestionMapper suggestionMapper = mock(CreatorSuggestionMapper.class);
        CreatorWorkflowMapper workflowMapper = mock(CreatorWorkflowMapper.class);
        PrePublishSuggestionService suggestionService = mock(PrePublishSuggestionService.class);
        CreatorWorkflowEventPublisher eventPublisher = mock(CreatorWorkflowEventPublisher.class);
        LlmApiUsageService usageService = mock(LlmApiUsageService.class);
        LLMService llmService = mock(LLMService.class);
        RuntimeSettingService runtimeSettingService = mock(RuntimeSettingService.class);
        CreatorPreferenceService preferenceService = mock(CreatorPreferenceService.class);
        CreatorProfileService profileService = mock(CreatorProfileService.class);
        CreatorWorkflowService service = new CreatorWorkflowService(
                taskMapper,
                suggestionMapper,
                workflowMapper,
                mock(CreatorInteractiveMapper.class),
                mock(CreatorIntentAlignmentService.class),
                suggestionService,
                eventPublisher,
                usageService,
                llmService,
                runtimeSettingService,
                preferenceService,
                profileService,
                enabledMediaProperties()
        );

        CreatorTaskRecord taskRecord = new CreatorTaskRecord();
        taskRecord.setTaskId("task-1");
        CreatorWorkflowSessionRecord sessionRecord = new CreatorWorkflowSessionRecord();
        sessionRecord.setSessionId("session-1");
        sessionRecord.setTaskId("task-1");
        sessionRecord.setStage(CreatorWorkflowStage.PRE_PUBLISH.name());
        sessionRecord.setStatus(CreatorWorkflowStatus.WAITING_USER_INPUT.name());
        CreatorMaterialRecord draftMaterial = new CreatorMaterialRecord();
        draftMaterial.setTaskId("task-1");
        draftMaterial.setMaterialType("MANUSCRIPT");
        // 故意短于 800 字，验证已保存的 AI 草稿不会再被误判为缺少完整文稿。
        draftMaterial.setContent("【AI 可编辑文稿草稿】\n这是可继续生成发布方案的短草稿。");

        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(taskRecord));
        when(workflowMapper.findSession("task-1", "session-1")).thenReturn(Optional.of(sessionRecord));
        when(taskMapper.listMaterialsByTaskId("task-1")).thenReturn(List.of(draftMaterial));

        assertThatThrownBy(() -> service.generatePrePublishManuscriptDraft(
                "task-1",
                "session-1",
                new com.link.linkagent.creator.workflow.model.PrePublishDraftRequest(null)
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        // 拒绝应发生在会话抢占和模型调用之前，避免重复补稿覆盖现有草稿并产生额外成本。
        verify(workflowMapper, never()).claimPrePublishExecution(
                "session-1",
                CreatorWorkflowStatus.RUNNING.name()
        );
        verifyNoInteractions(llmService);
    }

    @Test
    void shouldRejectSupplementAfterExecutionClaimHasChangedSessionToRunning() {
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorSuggestionMapper suggestionMapper = mock(CreatorSuggestionMapper.class);
        CreatorWorkflowMapper workflowMapper = mock(CreatorWorkflowMapper.class);
        PrePublishSuggestionService suggestionService = mock(PrePublishSuggestionService.class);
        CreatorWorkflowEventPublisher eventPublisher = mock(CreatorWorkflowEventPublisher.class);
        LlmApiUsageService usageService = mock(LlmApiUsageService.class);
        LLMService llmService = mock(LLMService.class);
        RuntimeSettingService runtimeSettingService = mock(RuntimeSettingService.class);
        CreatorPreferenceService preferenceService = mock(CreatorPreferenceService.class);
        CreatorProfileService profileService = mock(CreatorProfileService.class);
        CreatorWorkflowService service = new CreatorWorkflowService(
                taskMapper,
                suggestionMapper,
                workflowMapper,
                mock(CreatorInteractiveMapper.class),
                mock(CreatorIntentAlignmentService.class),
                suggestionService,
                eventPublisher,
                usageService,
                llmService,
                runtimeSettingService,
                preferenceService,
                profileService,
                enabledMediaProperties()
        );

        CreatorTaskRecord taskRecord = new CreatorTaskRecord();
        taskRecord.setTaskId("task-1");
        CreatorWorkflowSessionRecord runningSession = new CreatorWorkflowSessionRecord();
        runningSession.setSessionId("session-1");
        runningSession.setTaskId("task-1");
        runningSession.setStage(CreatorWorkflowStage.PRE_PUBLISH.name());
        runningSession.setStatus(CreatorWorkflowStatus.RUNNING.name());
        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(taskRecord));
        when(workflowMapper.findSessionForUpdate("task-1", "session-1"))
                .thenReturn(Optional.of(runningSession));

        // 模拟补充请求在等待数据库锁期间，另一条分析请求已成功抢占会话。
        // 锁释放后必须以最新的 RUNNING 状态拒绝补充，而不是把状态写回 WAITING_USER_INPUT。
        assertThatThrownBy(() -> service.sendMessage(
                "task-1",
                "session-1",
                new com.link.linkagent.creator.workflow.model.CreatorWorkflowMessageCreateRequest("补充要求")
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不可继续发送消息");

        verify(workflowMapper, never()).insertMessage(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldResetPlanCountAndMarkIntentPendingWhenUserAddsNewFeedback() {
        CreatorTaskMapper taskMapper = mock(CreatorTaskMapper.class);
        CreatorSuggestionMapper suggestionMapper = mock(CreatorSuggestionMapper.class);
        CreatorWorkflowMapper workflowMapper = mock(CreatorWorkflowMapper.class);
        CreatorInteractiveMapper interactiveMapper = mock(CreatorInteractiveMapper.class);
        CreatorWorkflowEventPublisher eventPublisher = mock(CreatorWorkflowEventPublisher.class);
        CreatorWorkflowService service = new CreatorWorkflowService(
                taskMapper,
                suggestionMapper,
                workflowMapper,
                interactiveMapper,
                mock(CreatorIntentAlignmentService.class),
                mock(PrePublishSuggestionService.class),
                eventPublisher,
                mock(LlmApiUsageService.class),
                mock(LLMService.class),
                mock(RuntimeSettingService.class),
                mock(CreatorPreferenceService.class),
                mock(CreatorProfileService.class),
                enabledMediaProperties()
        );

        CreatorTaskRecord taskRecord = new CreatorTaskRecord();
        taskRecord.setTaskId("task-1");
        CreatorWorkflowSessionRecord sessionRecord = new CreatorWorkflowSessionRecord();
        sessionRecord.setSessionId("session-1");
        sessionRecord.setTaskId("task-1");
        sessionRecord.setStage(CreatorWorkflowStage.PRE_PUBLISH.name());
        sessionRecord.setStatus(CreatorWorkflowStatus.WAITING_CONFIRMATION.name());
        sessionRecord.setPlanGenerationCount(3);

        when(taskMapper.findTaskByTaskId("task-1")).thenReturn(Optional.of(taskRecord));
        when(workflowMapper.findSessionForUpdate("task-1", "session-1"))
                .thenReturn(Optional.of(sessionRecord));
        when(workflowMapper.findSession("task-1", "session-1"))
                .thenReturn(Optional.of(sessionRecord));
        when(workflowMapper.nextMessageSequence("session-1")).thenReturn(1);
        when(workflowMapper.findMessageByMessageId(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());

        service.sendMessage(
                "task-1",
                "session-1",
                new com.link.linkagent.creator.workflow.model.CreatorWorkflowMessageCreateRequest(
                        "我不是要做教程，而是要展示流程为什么麻烦。"
                )
        );

        verify(workflowMapper).resetPlanGenerationState("session-1");
        verify(interactiveMapper).markIntentAlignmentPending("task-1");
        verify(workflowMapper).updateSessionStatus(
                "session-1",
                CreatorWorkflowStatus.WAITING_USER_INPUT.name(),
                null
        );
    }

    private static CreatorMediaProperties enabledMediaProperties() {
        CreatorMediaProperties mediaProperties = new CreatorMediaProperties();
        mediaProperties.setEnabled(true);
        return mediaProperties;
    }
}
