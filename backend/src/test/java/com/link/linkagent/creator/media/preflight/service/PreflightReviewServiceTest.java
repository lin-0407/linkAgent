package com.link.linkagent.creator.media.preflight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.preflight.model.CreatePreflightReviewRequest;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewRecord;
import com.link.linkagent.creator.media.processing.mapper.MediaProcessingMapper;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    @Test
    void shouldRejectRetryAfterMediaWasDeleted() {
        PreflightReviewMapper mapper = mock(PreflightReviewMapper.class);
        MediaUploadMapper uploadMapper = mock(MediaUploadMapper.class);
        PreflightReviewRecord review = failedReview();
        when(mapper.findReview("task-1", "default", "review-1")).thenReturn(Optional.of(review));
        when(mapper.findCurrentByVersion("task-1", "default", "version-1"))
                .thenReturn(Optional.of(review));
        when(mapper.lockDraftVersion("task-1", "default", "version-1"))
                .thenReturn(Optional.of("version-1"));
        when(uploadMapper.findDraftVideoByVersion("task-1", "default", "version-1"))
                .thenReturn(Optional.of(deletedDraft()));
        PreflightReviewService service = service(mapper, uploadMapper);

        ResponseStatusException exception = catchThrowableOfType(
                () -> service.retry("default", "task-1", "review-1"),
                ResponseStatusException.class
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getReason()).contains("媒体文件已删除");
        verify(mapper, never()).retryReview("task-1", "default", "review-1");
    }

    private PreflightReviewService service(PreflightReviewMapper mapper) {
        return service(mapper, mock(MediaUploadMapper.class));
    }

    private PreflightReviewService service(PreflightReviewMapper mapper, MediaUploadMapper uploadMapper) {
        return new PreflightReviewService(
                new CreatorMediaProperties(),
                mapper,
                uploadMapper,
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

    private PreflightReviewRecord failedReview() {
        return new PreflightReviewRecord(
                null, "review-1", "task-1", "version-1", "default", "job-1", "key-1", null,
                "FAILED", "ANALYZE_VIDEO", 50, 1L, false, 1, 3, null, null, null,
                "fingerprint", "{}", null, null, BigDecimal.ONE, null, null, "USD",
                "VIDEO_ANALYSIS_FAILED", "视频分析失败", null, null, null, null
        );
    }

    private DraftVideoRecord deletedDraft() {
        LocalDateTime now = LocalDateTime.now();
        return new DraftVideoRecord(
                1L, "version-1", "task-1", "default", 1, "V1 初剪", "source.mp4",
                "linkagent-private-media", "original/source.mp4", "video/mp4", 1024L,
                30_000L, 1920, 1080, null, "h264", "aac", true, null,
                "READY_FOR_REVIEW", now, now, now
        );
    }
}
