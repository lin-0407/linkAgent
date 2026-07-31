package com.link.linkagent.creator.production.service;

import com.link.linkagent.creator.production.mapper.ProductionPlanMapper;
import com.link.linkagent.creator.production.model.ProductionPlanRecord;
import com.link.linkagent.creator.production.model.ProductionPlanStatus;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.workflow.mapper.CreatorWorkflowMapper;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowSessionRecord;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStage;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** P0-1 门禁测试：发布方案确认后进入蓝图，蓝图完成后才能进入 OSS 上传。 */
class ProductionPlanGateServiceTest {

    @Test
    void shouldRejectBlueprintWhenPrePublishIsMissing() {
        ProductionPlanMapper planMapper = mock(ProductionPlanMapper.class);
        CreatorWorkflowMapper workflowMapper = mock(CreatorWorkflowMapper.class);
        CreatorSuggestionMapper suggestionMapper = mock(CreatorSuggestionMapper.class);
        when(workflowMapper.findLatestSession("task-1", CreatorWorkflowStage.PRE_PUBLISH.name()))
                .thenReturn(Optional.empty());
        ProductionPlanGateService service = new ProductionPlanGateService(
                planMapper, workflowMapper, suggestionMapper);

        ResponseStatusException exception = catchThrowableOfType(
                () -> service.ensurePrePublishConfirmed("task-1", "default", "制作蓝图"),
                ResponseStatusException.class);

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verifyNoInteractions(planMapper, suggestionMapper);
    }

    @Test
    void shouldRejectMediaWhenBlueprintHasIncompleteSteps() {
        ProductionPlanMapper planMapper = mock(ProductionPlanMapper.class);
        when(planMapper.findCurrentPlan("task-1", "default")).thenReturn(Optional.of(readyPlan()));
        when(planMapper.countIncompleteSteps("task-1", "plan-1")).thenReturn(1);
        ProductionPlanGateService service = new ProductionPlanGateService(
                planMapper, mock(CreatorWorkflowMapper.class), mock(CreatorSuggestionMapper.class));

        ResponseStatusException exception = catchThrowableOfType(
                () -> service.requireReady("task-1", "default"),
                ResponseStatusException.class);

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getReason()).contains("全部步骤");
    }

    @Test
    void shouldAllowMediaWhenBlueprintStepsAreFinished() {
        ProductionPlanMapper planMapper = mock(ProductionPlanMapper.class);
        when(planMapper.findCurrentPlan("task-1", "default")).thenReturn(Optional.of(readyPlan()));
        when(planMapper.countIncompleteSteps("task-1", "plan-1")).thenReturn(0);
        ProductionPlanGateService service = new ProductionPlanGateService(
                planMapper, mock(CreatorWorkflowMapper.class), mock(CreatorSuggestionMapper.class));

        ProductionPlanRecord plan = service.requireReady("task-1", "default");

        assertThat(plan.planId()).isEqualTo("plan-1");
    }

    @Test
    void shouldAllowPreflightWhenPlanningWasExplicitlySkipped() {
        ProductionPlanMapper planMapper = mock(ProductionPlanMapper.class);
        CreatorWorkflowMapper workflowMapper = mock(CreatorWorkflowMapper.class);
        CreatorSuggestionMapper suggestionMapper = mock(CreatorSuggestionMapper.class);
        when(planMapper.countPlanningSkippedTask("task-1", "default")).thenReturn(1);
        ProductionPlanGateService service = new ProductionPlanGateService(
                planMapper, workflowMapper, suggestionMapper);

        service.ensureReadyForPreflight("task-1", "default");

        verifyNoInteractions(workflowMapper, suggestionMapper);
    }

    @Test
    void shouldRejectRepositionWhenVideoIsPublishedOrBvIsBound() {
        ProductionPlanMapper planMapper = mock(ProductionPlanMapper.class);
        when(planMapper.countFinalizedPublishingFacts("task-1", "default")).thenReturn(1);
        ProductionPlanGateService service = new ProductionPlanGateService(
                planMapper, mock(CreatorWorkflowMapper.class), mock(CreatorSuggestionMapper.class));

        ResponseStatusException exception = catchThrowableOfType(
                () -> service.ensureRepositionAllowed("task-1", "default"),
                ResponseStatusException.class);

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getReason()).contains("BV 绑定").contains("修订任务");
    }

    private ProductionPlanRecord readyPlan() {
        return new ProductionPlanRecord(
                1L, "plan-1", "task-1", "default", 1,
                "PROJECT_DEMO", "SCREEN_RECORDING", "开发者", "看懂项目价值", 90000L,
                "[]", null, "[]", "{}", "项目演示制作蓝图", "先录制再剪辑",
                ProductionPlanStatus.READY.name(), "{}", "production_blueprint_project_demo_v1",
                "key-1", LocalDateTime.now(), LocalDateTime.now());
    }
}
