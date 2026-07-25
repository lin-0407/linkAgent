package com.link.linkagent.creator.media.preflight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.preflight.model.CreatePreflightReviewRequest;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewRecord;
import com.link.linkagent.creator.media.processing.mapper.MediaProcessingMapper;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证涉及重复计费风险的失败不能由普通重试接口重新提交。 */
class PreflightReviewServiceTest {

    @Test
    void shouldRejectRetryWhenAsrSubmissionResultIsAmbiguous() {
        PreflightReviewMapper mapper = mock(PreflightReviewMapper.class);
        PreflightReviewRecord review = new PreflightReviewRecord(
                null, "review-1", "task-1", "version-1", "default", "job-1", "key-1", null,
                "FAILED", "TRANSCRIBE", 0, 1L, false, 0, 3, null, null, null,
                "fingerprint", "{}", null, null, BigDecimal.ONE, null, null, "USD",
                "ASR_SUBMISSION_AMBIGUOUS", "ASR 提交结果不确定", null, null, null, null
        );
        when(mapper.findReview("task-1", "default", "review-1")).thenReturn(Optional.of(review));
        when(mapper.findCurrentByVersion("task-1", "default", "version-1"))
                .thenReturn(Optional.of(review));
        PreflightReviewService service = new PreflightReviewService(
                new CreatorMediaProperties(),
                mapper,
                mock(MediaUploadMapper.class),
                mock(MediaProcessingMapper.class),
                mock(PreflightEventPublisher.class),
                new ObjectMapper()
        );

        ResponseStatusException exception = catchThrowableOfType(
                () -> service.retry("default", "task-1", "review-1"),
                ResponseStatusException.class
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getReason()).contains("避免重复计费");
        verify(mapper, never()).retryReview("task-1", "default", "review-1");
    }

    @Test
    void shouldRejectNewReviewWhenPreviousAsrSubmissionIsAmbiguous() {
        PreflightReviewMapper mapper = mock(PreflightReviewMapper.class);
        PreflightReviewRecord review = ambiguousReview();
        when(mapper.lockDraftVersion("task-1", "default", "version-1"))
                .thenReturn(Optional.of("version-1"));
        when(mapper.findByIdempotency("task-1", "default", "new-key")).thenReturn(Optional.empty());
        when(mapper.findActiveByVersion("task-1", "default", "version-1")).thenReturn(Optional.empty());
        when(mapper.findCurrentByVersion("task-1", "default", "version-1"))
                .thenReturn(Optional.of(review));
        PreflightReviewService service = service(mapper);

        ResponseStatusException exception = catchThrowableOfType(
                () -> service.create(
                        "default",
                        "task-1",
                        "new-key",
                        new CreatePreflightReviewRequest("version-1", true, null)
                ),
                ResponseStatusException.class
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getReason()).contains("避免重复计费");
        verify(mapper, never()).insertReview(org.mockito.ArgumentMatchers.any());
    }

    private PreflightReviewService service(PreflightReviewMapper mapper) {
        return new PreflightReviewService(
                new CreatorMediaProperties(),
                mapper,
                mock(MediaUploadMapper.class),
                mock(MediaProcessingMapper.class),
                mock(PreflightEventPublisher.class),
                new ObjectMapper()
        );
    }

    private PreflightReviewRecord ambiguousReview() {
        return new PreflightReviewRecord(
                null, "review-1", "task-1", "version-1", "default", "job-1", "key-1", null,
                "FAILED", "TRANSCRIBE", 0, 1L, false, 0, 3, null, null, null,
                "fingerprint", "{}", null, null, BigDecimal.ONE, null, null, "USD",
                "ASR_SUBMISSION_AMBIGUOUS", "ASR 提交结果不确定", null, null, null, null
        );
    }
}
