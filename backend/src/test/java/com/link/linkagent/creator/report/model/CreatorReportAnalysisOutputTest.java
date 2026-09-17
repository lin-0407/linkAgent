package com.link.linkagent.creator.report.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreatorReportAnalysisOutputTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRejectValidJsonWhenRequiredFieldIsMissing() {
        String json = """
                {
                  "contentSummary": "内容总结",
                  "coreSellingPoints": [],
                  "audienceFeedbackSummary": "反馈总结",
                  "controversyAndMisunderstanding": [],
                  "nextActionSuggestions": [],
                  "creatorPreferenceInsight": [],
                  "overallConclusion": "总体判断"
                }
                """;

        assertThatThrownBy(() -> objectMapper.readValue(json, CreatorReportAnalysisOutput.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("titleDescriptionReview");
    }

    @Test
    void shouldRejectCompleteOutputWhenRequiredAnalysisListsAreEmpty() {
        // competitorComparison 是嵌套记录，Jackson 会先构造它、再执行顶层校验（嵌套校验异常先抛出）。
        // 这里只保留 coreSellingPoints 一个空列表，让断言不依赖「哪一层先校验」这个实现细节。
        String json = """
                {
                  "contentSummary": "内容总结",
                  "coreSellingPoints": [],
                  "titleDescriptionReview": {
                    "titleConclusion": "标题结论",
                    "descriptionConclusion": "简介结论",
                    "tagAndPartitionConclusion": "标签分区结论",
                    "riskReminder": "风险提醒"
                  },
                  "audienceFeedbackSummary": "反馈总结",
                  "competitorComparison": {
                    "benchmarkConclusion": "对标结论",
                    "ownAdvantages": ["优势一"],
                    "ownDisadvantages": ["短板一"],
                    "differentiationStrategy": "差异化策略"
                  },
                  "controversyAndMisunderstanding": [],
                  "nextActionSuggestions": [],
                  "creatorPreferenceInsight": [],
                  "overallConclusion": "总体判断"
                }
                """;

        assertThatThrownBy(() -> objectMapper.readValue(json, CreatorReportAnalysisOutput.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("coreSellingPoints");
    }

}
