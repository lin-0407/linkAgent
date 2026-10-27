package com.link.linkagent.creator.media.preflight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightStepRecord;
import com.link.linkagent.creator.media.preflight.model.TimelineEvidenceRecord;
import com.link.linkagent.creator.media.processing.mapper.MediaProcessingMapper;
import com.link.linkagent.creator.media.processing.model.MediaProcessingJobRecord;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证原片有无音轨均进入模型可读证据，音量缺失不会被伪造成 0 dB。 */
class PreflightTimelineServiceTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldUseProbeFactsFromExactReviewVersion(boolean hasAudio) {
        List<TimelineEvidenceRecord> evidence = rebuild(hasAudio, null);

        assertThat(evidence).hasSize(1);
        TimelineEvidenceRecord track = evidence.getFirst();
        assertThat(track.sourceType()).isEqualTo("AUDIO_TRACK");
        assertThat(track.content()).contains(hasAudio ? "原片存在音轨" : "原片没有音轨",
                "分析代理视频和复核短片主动去掉音轨", "没有转写文本不代表原片无声");
        assertThat(track.startMs()).isZero();
        assertThat(track.endMs()).isEqualTo(8000L);
    }

    @Test
    void shouldExposeVolumeValuesAndPreserveUnknownValue() throws Exception {
        List<TimelineEvidenceRecord> evidence = rebuild(true,
                "{\"meanVolumeDb\":null,\"maxVolumeDb\":-17.8}");

        TimelineEvidenceRecord volume = evidence.stream().filter(item -> "VOLUME".equals(item.sourceType()))
                .findFirst().orElseThrow();
        assertThat(volume.content()).contains("平均音量 未测得", "峰值音量 -17.8 dB");
        assertThat(new ObjectMapper().readTree(volume.metadataJson()).path("meanVolumeDb").isNull()).isTrue();
        assertThat(volume.endMs()).isEqualTo(8000L);
    }

    @Test
    void shouldNotTreatUnrecordedTrackStateAsNoAudio() {
        List<TimelineEvidenceRecord> evidence = rebuild(null, null);

        assertThat(evidence.getFirst().content()).contains("未记录原片音轨状态", "不能判断原片有无音轨");
    }

    private List<TimelineEvidenceRecord> rebuild(Boolean hasAudio, String signals) {
        PreflightReviewMapper mapper = mock(PreflightReviewMapper.class);
        MediaProcessingMapper processing = mock(MediaProcessingMapper.class);
        MediaUploadMapper uploads = mock(MediaUploadMapper.class);
        PreflightReviewRecord review = mock(PreflightReviewRecord.class);
        PreflightStepRecord step = mock(PreflightStepRecord.class);
        DraftVideoRecord draft = mock(DraftVideoRecord.class);
        MediaProcessingJobRecord job = mock(MediaProcessingJobRecord.class);
        when(review.taskId()).thenReturn("task-1");
        when(review.ownerId()).thenReturn("default");
        when(review.versionId()).thenReturn("version-1");
        when(review.reviewId()).thenReturn("review-1");
        when(review.processingJobId()).thenReturn("job-1");
        when(step.stepId()).thenReturn("timeline-step");
        when(draft.hasAudio()).thenReturn(hasAudio);
        when(draft.audioCodec()).thenReturn(Boolean.TRUE.equals(hasAudio) ? "aac" : null);
        when(draft.durationMs()).thenReturn(8000L);
        when(job.signalSummaryJson()).thenReturn(signals);
        when(uploads.findDraftVideoByVersion("task-1", "default", "version-1")).thenReturn(Optional.of(draft));
        when(processing.findJob("task-1", "default", "version-1", "job-1")).thenReturn(Optional.of(job));
        when(processing.listAssets("job-1")).thenReturn(List.of());
        when(mapper.insertEvidence(any())).thenReturn(1);
        PreflightTimelineService service = new PreflightTimelineService(mapper, processing, new ObjectMapper(), uploads);

        int count = service.rebuild(review, step);

        verify(uploads).findDraftVideoByVersion("task-1", "default", "version-1");
        ArgumentCaptor<TimelineEvidenceRecord> evidence = ArgumentCaptor.forClass(TimelineEvidenceRecord.class);
        verify(mapper, times(count)).insertEvidence(evidence.capture());
        return evidence.getAllValues();
    }
}
