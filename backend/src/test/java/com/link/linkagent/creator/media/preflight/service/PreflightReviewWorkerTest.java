package com.link.linkagent.creator.media.preflight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightStepRecord;
import com.link.linkagent.creator.media.preflight.provider.SpeechRecognitionProvider;
import com.link.linkagent.creator.media.processing.mapper.MediaProcessingMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证无人声是字幕步骤的正常空结果，不会中断后续试映流程。 */
class PreflightReviewWorkerTest {

    @Test
    void shouldSkipTranscriptAndContinueWhenProviderFindsNoSpeech() {
        CreatorMediaProperties properties = new CreatorMediaProperties();
        PreflightReviewMapper mapper = mock(PreflightReviewMapper.class);
        MediaProcessingMapper processingMapper = mock(MediaProcessingMapper.class);
        SpeechRecognitionProvider asrProvider = mock(SpeechRecognitionProvider.class);
        PreflightAsrService asrService = mock(PreflightAsrService.class);
        PreflightTimelineService timelineService = mock(PreflightTimelineService.class);
        PreflightVideoAnalysisService videoAnalysisService = mock(PreflightVideoAnalysisService.class);
        PreflightSegmentReviewService segmentReviewService = mock(PreflightSegmentReviewService.class);
        AudienceScreeningService audienceScreeningService = mock(AudienceScreeningService.class);
        PreflightReviewService reviewService = mock(PreflightReviewService.class);
        PreflightReviewRecord review = review();
        PreflightStepRecord runningTranscribe = step(
                "step-1", "TRANSCRIBE", 1, "RUNNING", null, "provider-task-1"
        );
        PreflightStepRecord skippedTranscribe = step(
                "step-1", "TRANSCRIBE", 1, "SKIPPED", "{\"reason\":\"NO_SPEECH_DETECTED\"}",
                "provider-task-1"
        );

        when(mapper.findNextRunnableReview()).thenReturn(Optional.of(review));
        when(mapper.claimReview(eq("review-1"), anyString(), any(LocalDateTime.class))).thenReturn(1);
        when(mapper.findReviewForWorker(eq("review-1"), anyString())).thenReturn(Optional.of(review));
        when(mapper.findStep("review-1", "TRANSCRIBE"))
                .thenReturn(Optional.of(runningTranscribe), Optional.of(skippedTranscribe));
        when(mapper.findStep("review-1", "BUILD_TIMELINE"))
                .thenReturn(Optional.of(step("step-2", "BUILD_TIMELINE", 2, "SUCCEEDED", "{}", null)));
        when(mapper.findStep("review-1", "ANALYZE_VIDEO")).thenReturn(Optional.of(step(
                "step-3", "ANALYZE_VIDEO", 3, "SUCCEEDED",
                "{\"executiveSummary\":\"画面检查完成\",\"issueCount\":0,\"actualCostUsd\":0}", null
        )));
        when(mapper.findStep("review-1", "REVIEW_SEGMENTS"))
                .thenReturn(Optional.of(step("step-4", "REVIEW_SEGMENTS", 4, "SKIPPED", "{}", null)));
        when(mapper.findStep("review-1", "SCREEN_AUDIENCE"))
                .thenReturn(Optional.of(step("step-5", "SCREEN_AUDIENCE", 5, "SUCCEEDED", "{}", null)));
        when(asrProvider.query("provider-task-1")).thenReturn(new SpeechRecognitionProvider.QueryResult(
                SpeechRecognitionProvider.Status.NO_SPEECH,
                null,
                12L,
                "SUCCESS_WITH_NO_VALID_FRAGMENT"
        ));
        when(mapper.sumActualCost("review-1")).thenReturn(new BigDecimal("0.00042000"));
        when(mapper.advanceReview(eq("review-1"), anyString(), eq("BUILD_TIMELINE"), eq(65),
                eq(12L), eq(new BigDecimal("0.00042000")))).thenReturn(1);
        when(mapper.completeReview(eq("review-1"), anyString(), eq("画面检查完成"),
                eq(new BigDecimal("0.00042000")))).thenReturn(1);

        PreflightReviewWorker worker = new PreflightReviewWorker(
                properties,
                mapper,
                processingMapper,
                asrProvider,
                asrService,
                timelineService,
                videoAnalysisService,
                segmentReviewService,
                audienceScreeningService,
                reviewService,
                new ObjectMapper()
        );
        try {
            worker.poll();

            verify(reviewService, timeout(3000)).publish("review-1", "review_completed");
            verify(asrService).skipTranscript(
                    review,
                    runningTranscribe,
                    "SUCCESS_WITH_NO_VALID_FRAGMENT",
                    12L,
                    new BigDecimal("0.00042000")
            );
            verify(mapper).advanceReview(eq("review-1"), anyString(), eq("BUILD_TIMELINE"), eq(65),
                    eq(12L), eq(new BigDecimal("0.00042000")));
            verify(asrProvider, never()).loadResult(any());
            verify(mapper, never()).failAsrCall(anyString(), anyString(), anyString(), any());
            verify(mapper, never()).failReview(anyString(), anyString(), anyString(), anyString());
        } finally {
            worker.shutdown();
        }
    }

    private PreflightReviewRecord review() {
        return new PreflightReviewRecord(
                null, "review-1", "task-1", "version-1", "default", "job-1", "key-1", null,
                "RUNNING", "TRANSCRIBE", 60, 1L, false, 0, 3, null, null, null,
                "fingerprint", "{}", null, null, BigDecimal.ONE, BigDecimal.ZERO, null, "USD",
                null, null, null, null, null, null
        );
    }

    private PreflightStepRecord step(String stepId,
                                     String stepType,
                                     int sequenceNo,
                                     String status,
                                     String outputRef,
                                     String providerTaskId) {
        return new PreflightStepRecord(
                null, stepId, "review-1", stepType, sequenceNo, status, 1,
                "fingerprint", outputRef, providerTaskId, null, null, null, null, null, null
        );
    }
}
