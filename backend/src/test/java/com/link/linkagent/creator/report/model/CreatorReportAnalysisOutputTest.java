package com.link.linkagent.creator.report.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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
    void shouldAcceptCompleteOutputWithEmptyFindingLists() throws Exception {
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
                    "ownAdvantages": [],
                    "ownDisadvantages": [],
                    "differentiationStrategy": "差异化策略"
                  },
                  "controversyAndMisunderstanding": [],
                  "nextActionSuggestions": [],
                  "creatorPreferenceInsight": [],
                  "overallConclusion": "总体判断"
                }
                """;

        CreatorReportAnalysisOutput output = objectMapper.readValue(json, CreatorReportAnalysisOutput.class);

        assertThat(output.overallConclusion()).isEqualTo("总体判断");
        assertThat(output.nextActionSuggestions()).isEmpty();
    }
}
