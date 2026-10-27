package com.link.linkagent.creator.media.preflight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.preflight.model.PreflightIssueRecord;
import com.link.linkagent.creator.media.preflight.model.TimelineEvidenceRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightStepRecord;
import com.link.linkagent.creator.media.preflight.provider.VideoUnderstandingProvider;
import com.link.linkagent.creator.media.processing.mapper.MediaProcessingMapper;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    void shouldTreatPreviousPolicyAndIntentClaimsAsUnverifiedDuringSegmentReview() {
        PreflightSegmentReviewService service = new PreflightSegmentReviewService(
                new CreatorMediaProperties(), mock(PreflightReviewMapper.class),
                mock(MediaProcessingMapper.class), mock(ObjectStorageService.class),
                mock(VideoUnderstandingProvider.class), new ObjectMapper(), () -> "call-1", () -> "evidence-1"
        );
        PreflightIssueRecord issue = new PreflightIssueRecord(
                null, "issue-1", "review-1", "version-1", "RISK", "发布风险",
                "测试图违反平台规范", "非设计意图且有审核误判先例", 0L, 8000L,
                "HIGH", new BigDecimal("0.9"), "[\"model-1\"]", "删除测试图",
                false, "[\"QWEN3_VL_PLUS\"]", null, "PENDING", null, null, null
        );
        TimelineEvidenceRecord modelClaim = new TimelineEvidenceRecord(
                null, "model-1", "review-1", "version-1", "VIDEO_MODEL", 0L, 8000L,
                issue.description(), new BigDecimal("0.9"), null, false, "step-3", null
        );
        Object range = ReflectionTestUtils.invokeMethod(service, "segmentRange", issue, 8000L);

        String prompt = ReflectionTestUtils.invokeMethod(service, "buildPrompt", issue, range, List.of(modelClaim));

        assertThat(prompt).contains(issue.description(), "VIDEO_MODEL", "不是独立事实或官方依据",
                "不得沿用或编造 B 站规范名称、审核先例", "不得断言违规、限流、推荐机制或预测审核结果",
                "若原问题仅依赖上述无依据判断，应返回 confirmed=false", "非设计意图、损坏或制作失误",
                "原问题无依据时，说明无需据此修改", "无法100%排除",
                "不能自行认定由系统或播放器叠加", "不得仅凭 VOLUME 数值声称音量正常");
    }

    @Test
    void shouldRemoveRiskActionAndAssertionTitleWhenReviewDoesNotConfirmIssue() {
        verifyPersistedDecision(false, "原问题未被复核确认", "原问题未被复核确认，无需据此修改。");
    }

    @Test
    void shouldKeepOriginalTitleAndActionWhenReviewConfirmsIssue() {
        verifyPersistedDecision(true, "可见字幕过小", "放大可见字幕。");
    }

    private void verifyPersistedDecision(boolean confirmed, String expectedTitle, String expectedAction) {
        PreflightReviewMapper mapper = mock(PreflightReviewMapper.class);
        when(mapper.insertEvidence(any())).thenReturn(1);
        when(mapper.updateIssueAfterSegmentReview(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString())).thenReturn(1);
        PreflightSegmentReviewService service = new PreflightSegmentReviewService(
                new CreatorMediaProperties(), mapper, mock(MediaProcessingMapper.class),
                mock(ObjectStorageService.class), mock(VideoUnderstandingProvider.class), new ObjectMapper(),
                () -> "call-1", () -> "evidence-1"
        );
        String originalTitle = confirmed ? "可见字幕过小" : "测试图违反平台规范";
        PreflightIssueRecord issue = new PreflightIssueRecord(
                null, "issue-1", "review-1", "version-1", "VISUAL", "画面", originalTitle,
                "初次模型判断", 0L, 8000L, "HIGH", new BigDecimal("0.9"), "[]", "原建议",
                false, "[\"QWEN3_VL_PLUS\"]", null, "PENDING", null, null, null
        );
        String raw = "{\"confirmed\":" + confirmed + ",\"severity\":\"HIGH\",\"confidence\":0.9,"
                + "\"description\":\"复核观察\",\"suggestedAction\":\""
                + (confirmed ? "放大可见字幕。" : "若需规避潜在AI误判风险，可添加说明字幕。") + "\"}";
        Object parsed = ReflectionTestUtils.invokeMethod(service, "parse", raw);
        Object range = ReflectionTestUtils.invokeMethod(service, "segmentRange", issue, 8000L);

        ReflectionTestUtils.invokeMethod(service, "persistReview", review(), step(), issue, range, parsed);

        verify(mapper).updateIssueAfterSegmentReview(eq("review-1"), eq("issue-1"), eq(expectedTitle),
                eq("复核观察"), eq(confirmed ? "HIGH" : "LOW"), eq(new BigDecimal("0.9")),
                eq(expectedAction), anyString(), anyString());
        verify(mapper).insertEvidence(org.mockito.ArgumentMatchers.argThat(evidence ->
                evidence.metadataJson().contains("\"confirmed\":" + confirmed)
                        && evidence.metadataJson().contains(originalTitle)));
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
