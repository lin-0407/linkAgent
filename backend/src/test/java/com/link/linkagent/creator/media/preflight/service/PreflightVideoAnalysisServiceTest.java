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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;

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
                        && issue.sourceTypes().contains("QWEN3_VL_PLUS")
        ));
        verify(fixture.mapper).completeVideoCall(eq("call-1"), eq(1200L), eq(180L), eq(new BigDecimal("0.00043092")), anyInt());
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

    @Test
    void shouldRestrictAudioClaimsForPreviewWithoutAudioTrack() {
        Fixture fixture = fixture("""
                {
                  "executiveSummary": "画面结构完整。",
                  "issues": []
                }
                """);

        fixture.service.analyze(review(), step());

        assertThat(fixture.prompt.get()).contains(
                "输入的代理视频不含音轨",
                "不得评价音乐、背景音、音质或音画同步",
                "没有 TRANSCRIPT 时不得推断口播内容"
        );
    }

    @Test
    void shouldKeepOriginalAudioFactsWhenManyKeyFramesExceedPromptLimit() {
        Fixture fixture = fixture("{\"executiveSummary\":\"原片含音轨，画面结构完整。\",\"issues\":[]}");
        List<TimelineEvidenceRecord> evidence = new java.util.ArrayList<>();
        for (int index = 0; index < 300; index++) {
            evidence.add(new TimelineEvidenceRecord(null, "frame-" + index, "review-1", "version-1", "KEY_FRAME",
                    0L, 0L, "关键画面", null, null, false, "timeline-step", null));
        }
        evidence.add(new TimelineEvidenceRecord(null, "audio-1", "review-1", "version-1", "AUDIO_TRACK",
                0L, 60000L, "FFprobe 已确认原片存在音轨（编码：aac）", null, null, false, "timeline-step", null));
        evidence.add(new TimelineEvidenceRecord(null, "volume-1", "review-1", "version-1", "VOLUME",
                0L, 60000L, "原片音量检测：平均音量 -21.1 dB，峰值音量 -17.8 dB", null, null, false, "timeline-step", null));
        when(fixture.mapper.listEvidence("review-1")).thenReturn(evidence);

        fixture.service.analyze(review(), step());

        assertThat(fixture.prompt.get()).contains("FFprobe 已确认原片存在音轨", "平均音量 -21.1 dB",
                "不是原片缺少音轨或原片无声的证据", "不能据此声称原片没有音轨、声音或音乐");
    }

    @Test
    void shouldSendEvidenceBoundariesWithActualVideoRequest() {
        Fixture fixture = fixture("{\"executiveSummary\":\"仅观察到测试图，发布用途未知。\",\"issues\":[]}");

        fixture.service.analyze(review(), step());

        assertThat(fixture.prompt.get()).contains(
                "没有提供官方规则原文、审核记录或流量数据",
                "不得编造 B 站规范名称、审核先例",
                "不得断言违规、限流、推荐机制或预测审核结果",
                "不得断言测试图、抽象画面、重复、拖影或像素效果属于非设计意图",
                "用户试映重点是待检查的问题",
                "证据不足时省略问题",
                "不能自行认定由系统或播放器叠加", "不得仅凭 VOLUME 数值声称音量正常",
                "不得因像素化、抽象、看不清或“无法100%排除”而生成敏感或违规风险条目",
                "不得把缺少依据的审核、AI误判或限流说法改写为条件式修改理由"
        );
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(value={"NULL,180", "1200,NULL", "NULL,NULL", "0,0"},nullValues="NULL")
    void videoUsageMustPreserveUnknownCostsWithoutConfusingAnExplicitZero(Long input,Long output) {
        var gate=mock(MediaCallChargeGate.class);
        when(gate.upperBound("MEDIA_VIDEO")).thenReturn(new BigDecimal("0.30"));
        when(gate.reserve(anyString(),anyString(),anyString(),any(),anyString())).thenReturn(()->true);
        var fixture=fixture("{\"executiveSummary\":\"可见画面已完成观察。\",\"issues\":[]}",input,output,gate);
        var result=fixture.service.analyze(review(),step());
        if(input==null || output==null) {
            assertThat(result.actualCostUsd()).isNull();
            verify(gate).unknown("review-1","video-review-1-call-1");
            verify(gate,never()).settle(anyString(),anyString(),any(),anyString());
        } else {
            assertThat(result.actualCostUsd()).isZero();
            verify(gate).settle(eq("review-1"),eq("video-review-1-call-1"),eq(result.actualCostUsd()),anyString());
            verify(gate,never()).unknown(anyString(),anyString());
        }
    }

    private Fixture fixture(String response) {
        return fixture(response,1200L,180L,MediaCallChargeGate.NOOP);
    }

    private Fixture fixture(String response,Long input,Long output,MediaCallChargeGate gate) {
        CreatorMediaProperties properties = new CreatorMediaProperties();
        properties.getPreflight().setDashScopeApiKey("test-key");
        PreflightReviewMapper mapper = mock(PreflightReviewMapper.class);
        MediaProcessingMapper processingMapper = mock(MediaProcessingMapper.class);
        ObjectStorageService storage = mock(ObjectStorageService.class);
        AtomicReference<String> prompt = new AtomicReference<>();
        VideoUnderstandingProvider provider = (videoUrl, requestPrompt) -> {
            prompt.set(requestPrompt);
            return new VideoUnderstandingProvider.AnalysisResult(response, input, output);
        };
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
                () -> "video-model-evidence-1", () -> "issue-1", gate
        );
        return new Fixture(service, mapper, prompt);
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

    private record Fixture(PreflightVideoAnalysisService service,
                           PreflightReviewMapper mapper,
                           AtomicReference<String> prompt) {
    }
}
