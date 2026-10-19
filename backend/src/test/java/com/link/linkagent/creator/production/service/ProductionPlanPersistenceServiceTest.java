package com.link.linkagent.creator.production.service;

import com.link.linkagent.creator.production.mapper.ProductionPlanMapper;
import com.link.linkagent.creator.production.model.ProductionPlanRecord;
import com.link.linkagent.creator.production.model.ProductionPlanStatus;
import com.link.linkagent.creator.report.mapper.CreatorReviewInvalidationMapper;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.creation.model.CreationWorkspaceRecord;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Optional;

class ProductionPlanPersistenceServiceTest {

    @Test
    void shouldRetireCurrentContextBeforeStartingNewPlan() {
        ProductionPlanMapper mapper = mock(ProductionPlanMapper.class);
        CreatorReviewInvalidationMapper invalidationMapper = mock(CreatorReviewInvalidationMapper.class);
        MediaUploadMapper media = mock(MediaUploadMapper.class);
        when(media.lockMediaTask("task-1", "default")).thenReturn(Optional.of("task-1"));
        when(media.findMediaWorkspace("task-1", "default"))
                .thenReturn(Optional.of(new CreationWorkspaceRecord("task-1", "default", 0, "old-video", 2)));
        when(media.clearCurrentVideoForReposition("task-1", "default", 0)).thenReturn(1);
        ProductionPlanPersistenceService service = new ProductionPlanPersistenceService(mapper, invalidationMapper, media);
        ProductionPlanRecord plan = generatingPlan();
        when(mapper.insertPlan(plan)).thenReturn(1);

        service.startGenerating(plan);

        verify(mapper).deleteUnboundVideoBindingsForReposition("task-1", "default");
        verify(mapper).invalidateMediaUploads("task-1", "default");
        verify(mapper).invalidateMediaProcessingJobs("task-1", "default");
        verify(mapper).invalidatePreflightReviews("task-1", "default");
        verify(mapper).cancelUnfinishedDraftsForReposition("task-1", "default");
        verify(media).clearCurrentVideoForReposition("task-1", "default", 0);
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
