package com.link.linkagent.creator.production.service;

import com.link.linkagent.creator.production.mapper.ProductionPlanMapper;
import com.link.linkagent.creator.production.model.ProductionPlanRecord;
import com.link.linkagent.creator.production.model.ProductionPlanStatus;
import com.link.linkagent.creator.report.mapper.CreatorReviewInvalidationMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProductionPlanPersistenceServiceTest {

    @Test
    void shouldClearDownstreamStateBeforeStartingNewPlan() {
        ProductionPlanMapper mapper = mock(ProductionPlanMapper.class);
        CreatorReviewInvalidationMapper invalidationMapper = mock(CreatorReviewInvalidationMapper.class);
        ProductionPlanPersistenceService service = new ProductionPlanPersistenceService(mapper, invalidationMapper);
        ProductionPlanRecord plan = generatingPlan();

        service.startGenerating(plan);

        verify(mapper).deleteUnboundVideoBindingsForReposition("task-1", "default");
        verify(mapper).invalidateMediaUploads("task-1", "default");
        verify(mapper).invalidateMediaProcessingJobs("task-1", "default");
        verify(mapper).invalidatePreflightReviews("task-1", "default");
        verify(mapper).resetDraftVideosForReposition("task-1", "default");
        verify(invalidationMapper).invalidateFeedbackReport("task-1");
        verify(invalidationMapper).invalidateCompetitorReport("task-1");
        verify(invalidationMapper).invalidateCreatorReport("task-1");
        verify(invalidationMapper).invalidateGeneratedPreference("task-1");
        verify(mapper).resetTaskStatusForReposition("task-1", "default");
        verify(mapper).markCurrentPlansStale("task-1", "default");
        verify(mapper).insertPlan(plan);
    }

    private ProductionPlanRecord generatingPlan() {
        return new ProductionPlanRecord(
                null, "plan-2", "task-1", "default", 2,
                "PROJECT_DEMO", "SCREEN_RECORDING", "开发者", "看懂项目价值", 90000L,
                "[]", null, "[]", null, null, null,
                ProductionPlanStatus.GENERATING.name(), null, null,
                "key-2", null, null
        );
    }
}
