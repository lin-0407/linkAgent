package com.link.linkagent.creator.workflow.service;

import com.link.linkagent.creator.workflow.model.CreatorIntentAlignmentContext;
import com.link.linkagent.creator.workflow.model.CreatorIntentAlignmentOutput;
import com.link.linkagent.creator.workflow.model.CreatorIntentReviewIssue;
import com.link.linkagent.creator.workflow.model.CreatorIntentReviewResult;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 想法对齐双 Agent 的边界测试，不启动 Spring，也不会发起真实模型调用。
 */
class CreatorIntentAlignmentServiceTest {

    @Test
    void shouldReturnFirstDraftWhenReviewerFindsNoDeviation() {
        LLMService llmService = mock(LLMService.class);
        PromptService promptService = mock(PromptService.class);
        when(promptService.get("creator_alignment.main.system")).thenReturn("main-system");
        when(promptService.get("creator_alignment.review.system")).thenReturn("review-system");
        when(llmService.chatStructured(
                eq("main-system"),
                anyString(),
                eq(CreatorIntentAlignmentOutput.class)
        )).thenReturn(new CreatorIntentAlignmentOutput(
                "用户要展示当前流程为什么复杂，不是做部署教程。",
                List.of("最终视频主要给第一次了解项目的人看吗？")
        ));
        when(llmService.chatStructured(
                eq("review-system"),
                anyString(),
                eq(CreatorIntentReviewResult.class)
        )).thenReturn(new CreatorIntentReviewResult(false, List.of()));

        CreatorIntentAlignmentService service = new CreatorIntentAlignmentService(llmService, promptService);
        CreatorIntentAlignmentOutput result = service.align(new CreatorIntentAlignmentContext(
                "用户原话：我想展示整个流程太复杂。",
                "用户原话：我想展示整个流程太复杂。"
        ));

        assertThat(result.understanding()).contains("不是做部署教程");
        assertThat(result.questions()).hasSize(1);
        verify(llmService, times(1)).chatStructured(
                eq("main-system"),
                anyString(),
                eq(CreatorIntentAlignmentOutput.class)
        );
    }

    @Test
    void shouldLetMainAgentRetryOnlyOnceWithQuoteAndReason() {
        LLMService llmService = mock(LLMService.class);
        PromptService promptService = mock(PromptService.class);
        when(promptService.get("creator_alignment.main.system")).thenReturn("main-system");
        when(promptService.get("creator_alignment.review.system")).thenReturn("review-system");
        when(llmService.chatStructured(
                eq("main-system"),
                anyString(),
                eq(CreatorIntentAlignmentOutput.class)
        )).thenReturn(
                new CreatorIntentAlignmentOutput("用户要教观众部署项目。", List.of()),
                new CreatorIntentAlignmentOutput("用户要展示流程体验和当前问题。", List.of())
        );
        when(llmService.chatStructured(
                eq("review-system"),
                anyString(),
                eq(CreatorIntentReviewResult.class)
        )).thenReturn(
                new CreatorIntentReviewResult(true, List.of(
                        new CreatorIntentReviewIssue(
                                "我不是要教别人部署",
                                "候选回复把展示体验改成了部署教程"
                        )
                )),
                new CreatorIntentReviewResult(false, List.of())
        );

        CreatorIntentAlignmentService service = new CreatorIntentAlignmentService(llmService, promptService);
        CreatorIntentAlignmentOutput result = service.align(new CreatorIntentAlignmentContext(
                "用户原话：我不是要教别人部署，我是想展示现在的流程为什么麻烦。",
                "用户原话：我不是要教别人部署，我是想展示现在的流程为什么麻烦。"
        ));

        assertThat(result.understanding()).contains("流程体验");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmService, times(2)).chatStructured(
                eq("main-system"),
                promptCaptor.capture(),
                eq(CreatorIntentAlignmentOutput.class)
        );
        assertThat(promptCaptor.getAllValues().get(1))
                .contains("我不是要教别人部署")
                .contains("候选回复把展示体验改成了部署教程")
                .contains("用户原始上下文");
        verify(llmService, times(2)).chatStructured(
                eq("review-system"),
                anyString(),
                eq(CreatorIntentReviewResult.class)
        );
    }

    @Test
    void shouldStopGuessingWhenRetryStillDeviates() {
        LLMService llmService = mock(LLMService.class);
        PromptService promptService = mock(PromptService.class);
        when(promptService.get("creator_alignment.main.system")).thenReturn("main-system");
        when(promptService.get("creator_alignment.review.system")).thenReturn("review-system");
        when(llmService.chatStructured(
                eq("main-system"),
                anyString(),
                eq(CreatorIntentAlignmentOutput.class)
        )).thenReturn(
                new CreatorIntentAlignmentOutput("用户要做部署教程。", List.of()),
                new CreatorIntentAlignmentOutput("用户要做产品宣传。", List.of())
        );
        CreatorIntentReviewResult deviation = new CreatorIntentReviewResult(true, List.of(
                new CreatorIntentReviewIssue("我想展示流程为什么麻烦", "候选回复擅自改成了其他视频目标")
        ));
        when(llmService.chatStructured(
                eq("review-system"),
                anyString(),
                eq(CreatorIntentReviewResult.class)
        )).thenReturn(deviation, deviation);

        CreatorIntentAlignmentOutput result = new CreatorIntentAlignmentService(llmService, promptService)
                .align(new CreatorIntentAlignmentContext(
                        "用户原话：我想展示流程为什么麻烦。",
                        "用户原话：我想展示流程为什么麻烦。"
        ));

        assertThat(result.understanding()).contains("先不继续往下生成");
        assertThat(result.questions()).hasSize(1);
        assertThat(result.questions().get(0)).contains("我想展示流程为什么麻烦");
    }
}
