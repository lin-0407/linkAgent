package com.link.linkagent.creator.media.preflight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightStepRecord;
import com.link.linkagent.creator.media.preflight.model.TimelineEvidenceRecord;
import com.link.linkagent.creator.media.preflight.provider.VideoUnderstandingProvider;
import com.link.linkagent.creator.media.processing.mapper.MediaProcessingMapper;
import com.link.linkagent.creator.media.processing.model.MediaProcessingAssetRecord;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.storage.PresignedObjectRead;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证模型问题只有绑定合法时间段和可追溯证据后才会进入发布前体检。 */
class PreflightVideoAnalysisServiceTest {

    @Test
    void shouldPersistValidatedIssueWithVideoEvidence() {
        Fixture fixture = fixture("""
                {
                  "executiveSummary": "开场目标明确，但中段演示存在等待。",
                  "issues": [{
                    "issueType": "PACING",
                    "dimension": "节奏",
                    "title": "安装等待削弱节奏",
                    "description": "安装过程连续停留，信息密度明显下降。",
                    "startMs": 12000,
                    "endMs": 18000,
                    "severity": "HIGH",
                    "confidence": 0.88,
                    "evidenceRefs": ["evidence-1", "stale-video"],
                    "suggestedAction": "将等待过程压缩到 2 秒，并保留完成结果。",
                    "needsHumanReview": false
                  }]
                }
                """);

        PreflightVideoAnalysisService.Result result = fixture.service.analyze(review(), step());

        assertThat(result.executiveSummary()).contains("中段演示");
        assertThat(result.issueCount()).isEqualTo(1);
        verify(fixture.mapper).insertEvidence(any(TimelineEvidenceRecord.class));
        verify(fixture.mapper).insertIssue(org.mockito.ArgumentMatchers.argThat(issue ->
                "HIGH".equals(issue.severity())
                        && issue.evidenceRefs().contains("evidence-1")
                        && issue.evidenceRefs().contains("video-model-evidence-1")
                        && !issue.evidenceRefs().contains("stale-video")
        ));
        verify(fixture.mapper).completeVideoCall(eq("call-1"), eq(1200L), eq(180L), any(), anyInt());
    }

    @Test
    void shouldRejectIssueOutsideVideoDuration() {
        Fixture fixture = fixture("""
                {
                  "executiveSummary": "发现一个问题。",
                  "issues": [{
                    "issueType": "STRUCTURE",
                    "dimension": "内容结构",
                    "title": "无效时间段",
                    "description": "模型给出的时间超过成片范围。",
                    "startMs": 59000,
                    "endMs": 70000,
                    "severity": "HIGH",
                    "confidence": 0.8,
                    "evidenceRefs": [],
                    "suggestedAction": "调整结构。",
                    "needsHumanReview": false
                  }]
                }
                """);

        assertThatThrownBy(() -> fixture.service.analyze(review(), step()))
                .isInstanceOf(PreflightVideoAnalysisService.VideoAnalysisException.class)
                .hasMessageContaining("时间段");
    }

    private Fixture fixture(String response) {
        CreatorMediaProperties properties = new CreatorMediaProperties();
        properties.getPreflight().setDashScopeApiKey("test-key");
        PreflightReviewMapper mapper = mock(PreflightReviewMapper.class);
        MediaProcessingMapper processingMapper = mock(MediaProcessingMapper.class);
        ObjectStorageService storage = mock(ObjectStorageService.class);
        VideoUnderstandingProvider provider = (videoUrl, prompt) ->
                new VideoUnderstandingProvider.AnalysisResult(response, 1200L, 180L);
        MediaProcessingAssetRecord preview = new MediaProcessingAssetRecord(
                null, "asset-1", "job-1", "version-1", "PREVIEW_VIDEO", "bucket",
                "preview.mp4", "video/mp4", 1024L, null, null, 854, 480, 60000L, null, null
        );
        TimelineEvidenceRecord evidence = new TimelineEvidenceRecord(
                null, "evidence-1", "review-1", "version-1", "TRANSCRIPT",
                12000L, 18000L, "正在安装依赖", null, null, false, "timeline-step", null
        );
        TimelineEvidenceRecord staleVideoEvidence = new TimelineEvidenceRecord(
                null, "stale-video", "review-1", "version-1", "VIDEO_MODEL",
                12000L, 18000L, "上一次未完成尝试的模型证据", null, null, false, "step-3", null
        );
        when(processingMapper.listAssets("job-1")).thenReturn(List.of(preview));
        when(mapper.listEvidence("review-1")).thenReturn(List.of(evidence, staleVideoEvidence));
        when(storage.presignGetObject(eq("bucket"), eq("preview.mp4"), any()))
                .thenReturn(new PresignedObjectRead("https://media.example/review.mp4", Instant.now().plusSeconds(300)));
        when(mapper.insertVideoCall(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(mapper.insertEvidence(any())).thenReturn(1);
        when(mapper.insertIssue(any())).thenReturn(1);
        when(mapper.completeVideoCall(any(), any(), any(), any(), anyInt())).thenReturn(1);
        PreflightVideoAnalysisService service = new PreflightVideoAnalysisService(
                properties, mapper, processingMapper, storage, provider, new ObjectMapper(), () -> "call-1",
                () -> "video-model-evidence-1", () -> "issue-1"
        );
        return new Fixture(service, mapper);
    }

    private PreflightReviewRecord review() {
        return new PreflightReviewRecord(
                null, "review-1", "task-1", "version-1", "default", "job-1", "key-1", "检查节奏",
                "RUNNING", "ANALYZE_VIDEO", 75, 1L, false, 0, 3, null, "worker", null,
                "fingerprint", "{}", null, null, BigDecimal.ONE, BigDecimal.ZERO, null, "USD",
                null, null, null, null, null, null
        );
    }

    private PreflightStepRecord step() {
        return new PreflightStepRecord(
                null, "step-3", "review-1", "ANALYZE_VIDEO", 3, "RUNNING", 1,
                "fingerprint", null, null, null, null, null, null, null, null
        );
    }

    private record Fixture(PreflightVideoAnalysisService service, PreflightReviewMapper mapper) {
    }
}
