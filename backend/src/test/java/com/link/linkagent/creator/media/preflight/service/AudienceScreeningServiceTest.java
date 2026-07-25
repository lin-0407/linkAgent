package com.link.linkagent.creator.media.preflight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.preflight.model.PreflightIssueRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightStepRecord;
import com.link.linkagent.creator.media.preflight.model.TimelineEvidenceRecord;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.StructuredCallResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证三类角色一次生成并把受影响观众回写到问题。 */
class AudienceScreeningServiceTest {

    @Test
    void shouldPersistThreePersonasFromOneStructuredCall() {
        PreflightReviewMapper mapper = mock(PreflightReviewMapper.class);
        LLMService llmService = mock(LLMService.class);
        AudienceScreeningService.PersonaOutput casual = persona("CASUAL");
        AudienceScreeningService.PersonaOutput target = persona("TARGET");
        AudienceScreeningService.PersonaOutput coreFan = persona("CORE_FAN");
        when(llmService.chatStructuredWithUsage(anyString(), anyString(),
                eq(AudienceScreeningService.AudienceOutput.class)))
                .thenReturn(new StructuredCallResult<>(
                        new AudienceScreeningService.AudienceOutput(List.of(casual, target, coreFan)),
                        1200, 360, 1560, 80L
                ));
        when(mapper.listIssues("review-1")).thenReturn(List.of(issue()));
        when(mapper.listEvidence("review-1")).thenReturn(List.of(evidence()));
        when(mapper.insertTextScreeningCall(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        when(mapper.insertAudienceScreening(any())).thenReturn(1);
        when(mapper.updateIssueAffectedPersonas(anyString(), anyString(), anyString())).thenReturn(1);
        when(mapper.completeTextScreeningCall(anyString(), any(), any(), eq(3))).thenReturn(1);
        AudienceScreeningService service = new AudienceScreeningService(mapper, llmService, new ObjectMapper());

        AudienceScreeningService.Result result = service.screen(review(), step());

        assertThat(result.personaCount()).isEqualTo(3);
        verify(llmService, times(1)).chatStructuredWithUsage(anyString(), anyString(),
                eq(AudienceScreeningService.AudienceOutput.class));
        verify(mapper, times(3)).insertAudienceScreening(any());
        verify(mapper).updateIssueAffectedPersonas(eq("review-1"), eq("issue-1"),
                org.mockito.ArgumentMatchers.contains("CASUAL"));
    }

    private AudienceScreeningService.PersonaOutput persona(String type) {
        return new AudienceScreeningService.PersonaOutput(
                type, "试映假设：可能愿意继续观看", List.of("结果展示"),
                List.of("步骤略快"), List.of("等待过程"), List.of("evidence-1"),
                List.of("issue-1"), new BigDecimal("0.82")
        );
    }

    private PreflightIssueRecord issue() {
        return new PreflightIssueRecord(
                null, "issue-1", "review-1", "version-1", "PACING", "节奏",
                "等待过程偏长", "中段等待降低信息密度", 10_000L, 18_000L,
                "HIGH", new BigDecimal("0.88"), "[\"evidence-1\"]", "压缩等待过程",
                false, "[\"QWEN3_VL_FLASH\"]", null, "PENDING", null, null, null
        );
    }

    private TimelineEvidenceRecord evidence() {
        return new TimelineEvidenceRecord(
                null, "evidence-1", "review-1", "version-1", "TRANSCRIPT",
                10_000L, 18_000L, "正在等待安装完成", null, null, false, "step-2", null
        );
    }

    private PreflightReviewRecord review() {
        return new PreflightReviewRecord(
                null, "review-1", "task-1", "version-1", "default", "job-1", "key-1", null,
                "RUNNING", "SCREEN_AUDIENCE", 90, 1L, false, 0, 3, null, "worker", null,
                "fingerprint", "{}", null, "开场清楚，中段等待偏长", BigDecimal.ONE,
                BigDecimal.ZERO, null, "USD", null, null, null, null, null, null
        );
    }

    private PreflightStepRecord step() {
        return new PreflightStepRecord(
                null, "step-5", "review-1", "SCREEN_AUDIENCE", 5, "RUNNING", 1,
                "fingerprint", null, null, null, null, null, null, null, null
        );
    }
}
