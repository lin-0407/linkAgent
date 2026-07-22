package com.link.linkagent.creator.feedback.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreatorFeedbackAnalysisOutputTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRejectValidJsonWhenRequiredTopLevelFieldIsMissing() {
        String json = """
                {
                  "feedbackSummary": "整体反馈",
                  "creatorFeedbackDilemma": "表达落差",
                  "audienceCoreConcern": "核心关注",
                  "sentimentSummary": "整体中性"
                }
                """;

        assertThatThrownBy(() -> objectMapper.readValue(json, CreatorFeedbackAnalysisOutput.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("hotTopics");
    }

    @Test
    void shouldRejectNestedItemWhenRequiredFieldIsMissing() {
        String json = """
                {
                  "feedbackSummary": "整体反馈",
                  "creatorFeedbackDilemma": "表达落差",
                  "audienceCoreConcern": "核心关注",
                  "hotTopics": [{"topic": "高频观点"}],
                  "sentimentSummary": "整体中性",
                  "controversyPoints": [],
                  "misunderstandingPoints": [],
                  "misunderstandingSourceAnalysis": [],
                  "nextContentSuggestions": [],
                  "interactionSuggestions": [],
                  "feedbackActionPlan": []
                }
                """;

        assertThatThrownBy(() -> objectMapper.readValue(json, CreatorFeedbackAnalysisOutput.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("evidence");
    }

    @Test
    void shouldAcceptCompleteOutputWithEmptyOptionalFindingLists() throws Exception {
        String json = """
                {
                  "feedbackSummary": "整体反馈",
                  "creatorFeedbackDilemma": "表达落差",
                  "audienceCoreConcern": "核心关注",
                  "hotTopics": [],
                  "sentimentSummary": "整体中性",
                  "controversyPoints": [],
                  "misunderstandingPoints": [],
                  "misunderstandingSourceAnalysis": [],
                  "nextContentSuggestions": [],
                  "interactionSuggestions": [],
                  "feedbackActionPlan": []
                }
                """;

        CreatorFeedbackAnalysisOutput output = objectMapper.readValue(
                json,
                CreatorFeedbackAnalysisOutput.class
        );

        assertThat(output.feedbackSummary()).isEqualTo("整体反馈");
        assertThat(output.hotTopics()).isEmpty();
    }
}
