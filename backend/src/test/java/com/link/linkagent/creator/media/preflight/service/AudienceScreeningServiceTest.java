package com.link.linkagent.creator.media.preflight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.preflight.model.PreflightIssueRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightStepRecord;
import com.link.linkagent.creator.media.preflight.model.TimelineEvidenceRecord;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.StructuredCallResult;
import com.link.linkagent.llm.usage.LlmUsageContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
                .thenAnswer(invocation -> {
                    assertThat(LlmUsageContext.current().taskId()).isEqualTo("task-1");
                    assertThat(LlmUsageContext.current().scene()).isEqualTo("AI观众试映");
                    return new StructuredCallResult<>(
                        new AudienceScreeningService.AudienceOutput(List.of(casual, target, coreFan)),
                        1200, 360, 1560, 80L
                    );
                });
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
        assertThat(LlmUsageContext.current()).isNull();
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(llmService, times(1)).chatStructuredWithUsage(system.capture(), user.capture(),
                eq(AudienceScreeningService.AudienceOutput.class));
        assertThat(system.getValue()).contains("不能代替独立事实", "不得沿用或编造 B 站规范名称、审核先例",
                "非设计意图、损坏或制作失误", "不得声称既有风格、内容重复或粉丝实际期待",
                "不得把缺少依据的审核、AI误判或限流说法改写为条件式修改理由", "无法100%排除",
                "不能自行认定由系统或播放器叠加", "不得仅凭 VOLUME 数值声称音量正常");
        assertThat(user.getValue()).contains("只有给定历史作品或粉丝反馈时才比较风格");
        verify(mapper, times(3)).insertAudienceScreening(org.mockito.ArgumentMatchers.argThat(record ->
                "audience_screening_v3_evidence_bounds".equals(record.promptVersion())));
        verify(mapper).updateIssueAffectedPersonas(eq("review-1"), eq("issue-1"),
                org.mockito.ArgumentMatchers.contains("CASUAL"));
    }

    @Test
    void unrelatedReferencesStayEmptyAndNoIssueIsInventedForAZeroIssueVideo() {
        var mapper=mock(PreflightReviewMapper.class);var llm=mock(LLMService.class);
        var personas=java.util.stream.Stream.of("CASUAL","TARGET","CORE_FAN").map(type->
                new AudienceScreeningService.PersonaOutput(type,"可能感兴趣",List.of(),List.of(),List.of(),
                        List.of("nonexistent-evidence"),List.of("nonexistent-issue"),new BigDecimal("0.5"))).toList();
        when(llm.chatStructuredWithUsage(anyString(),anyString(),eq(AudienceScreeningService.AudienceOutput.class)))
                .thenReturn(new StructuredCallResult<>(new AudienceScreeningService.AudienceOutput(personas),100,100,200,1L));
        when(mapper.listIssues("review-1")).thenReturn(List.of());when(mapper.listEvidence("review-1")).thenReturn(List.of(evidence()));
        when(mapper.insertTextScreeningCall(anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(1);
        when(mapper.insertAudienceScreening(any())).thenReturn(1);when(mapper.completeTextScreeningCall(anyString(),any(),any(),eq(3))).thenReturn(1);
        var result=new AudienceScreeningService(mapper,llm,new ObjectMapper()).screen(review(),step());
        assertThat(result.personaCount()).isEqualTo(3);
        verify(mapper,times(3)).insertAudienceScreening(org.mockito.ArgumentMatchers.argThat(row->"[]".equals(row.evidenceRefs())));
        verify(mapper,org.mockito.Mockito.never()).updateIssueAffectedPersonas(anyString(),anyString(),anyString());
    }

    private AudienceScreeningService.PersonaOutput persona(String type) {
        return new AudienceScreeningService.PersonaOutput(
                type, "试映假设：可能愿意继续观看", List.of("结果展示"),
                List.of("步骤略快"), List.of("等待过程"), List.of("evidence-1"),
                List.of("issue-1"), new BigDecimal("0.82")
        );
    }
    @Test void chargedMalformedResponseKeepsUsageAndNeverRequestsAStructuredRetry(){
        var mapper=mock(PreflightReviewMapper.class);var llm=mock(LLMService.class);var gate=mock(MediaCallChargeGate.class);
        var single=mock(com.link.linkagent.creator.creation.storyboard.service.StoryboardTextGenerator.class);
        when(mapper.listIssues("review-1")).thenReturn(List.of());when(mapper.listEvidence("review-1")).thenReturn(List.of());
        when(mapper.insertTextScreeningCall(anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(1);
        when(gate.reserve(anyString(),anyString(),anyString(),any(),anyString())).thenReturn(()->true);
        when(gate.upperBound(anyString())).thenReturn(new BigDecimal("0.05"));when(gate.estimateTextCost(120L,40L)).thenReturn(new BigDecimal("0.001"));
        when(single.generate(anyString(),anyString(),eq(4096))).thenReturn(new com.link.linkagent.creator.creation.storyboard.service.StoryboardTextGenerator.Raw("invalid-json",120,40,"test-model"));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->new AudienceScreeningService(mapper,llm,new ObjectMapper(),gate,single).screen(review(),step()))
                .isInstanceOf(AudienceScreeningService.AudienceScreeningException.class);
        verify(single,times(1)).generate(anyString(),anyString(),eq(4096));org.mockito.Mockito.verifyNoInteractions(llm);
        verify(gate).settle(eq("review-1"),anyString(),eq(new BigDecimal("0.001")),anyString());
        verify(mapper).recordCallUsage(anyString(),eq(120L),eq(40L),eq(new BigDecimal("0.001")));
        verify(gate,org.mockito.Mockito.never()).unknown(anyString(),anyString());
        verify(mapper,org.mockito.Mockito.never()).insertAudienceScreening(any());
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
