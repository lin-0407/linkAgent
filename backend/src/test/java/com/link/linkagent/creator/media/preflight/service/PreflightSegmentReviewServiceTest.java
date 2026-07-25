package com.link.linkagent.creator.media.preflight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightStepRecord;
import com.link.linkagent.creator.media.preflight.provider.VideoUnderstandingProvider;
import com.link.linkagent.creator.media.processing.mapper.MediaProcessingMapper;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

/** 用户没有选择 Plus 抽样复核时不应切片，也不能产生额外调用。 */
class PreflightSegmentReviewServiceTest {

    @Test
    void shouldSkipPlusWhenModelPlanDoesNotEnableIt() {
        PreflightReviewMapper mapper = mock(PreflightReviewMapper.class);
        MediaProcessingMapper processingMapper = mock(MediaProcessingMapper.class);
        ObjectStorageService storage = mock(ObjectStorageService.class);
        VideoUnderstandingProvider provider = mock(VideoUnderstandingProvider.class);
        when(processingMapper.findJob("task-1", "default", "version-1", "job-1"))
                .thenReturn(Optional.empty());
        PreflightSegmentReviewService service = new PreflightSegmentReviewService(
                new CreatorMediaProperties(), mapper, processingMapper, storage, provider,
                new ObjectMapper(), () -> "call-1", () -> "evidence-1"
        );

        PreflightSegmentReviewService.Result result = service.review(review(), step());

        assertThat(result.selectedCount()).isZero();
        verifyNoInteractions(storage, provider);
    }

    private PreflightReviewRecord review() {
        return new PreflightReviewRecord(
                null, "review-1", "task-1", "version-1", "default", "job-1", "key-1", null,
                "RUNNING", "REVIEW_SEGMENTS", 82, 1L, false, 0, 3, null, "worker", null,
                "fingerprint", "{}", null, "摘要", BigDecimal.ONE, BigDecimal.ZERO,
                null, "USD", null, null, null, null, null, null
        );
    }

    private PreflightStepRecord step() {
        return new PreflightStepRecord(
                null, "step-4", "review-1", "REVIEW_SEGMENTS", 4, "RUNNING", 1,
                "fingerprint", null, null, null, null, null, null, null, null
        );
    }
}
