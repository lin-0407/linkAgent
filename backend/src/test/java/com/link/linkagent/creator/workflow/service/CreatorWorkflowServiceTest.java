package com.link.linkagent.creator.workflow.service;

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
                suggestionService,
                eventPublisher,
                usageService,
                llmService,
                runtimeSettingService,
                preferenceService,
                profileService
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
        when(workflowMapper.claimPrePublishAnalysis("session-1", CreatorWorkflowStatus.RUNNING.name()))
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
}
