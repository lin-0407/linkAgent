package com.link.linkagent.creator.competitor.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreatorCompetitorAnalysisOutputTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String COMPLETE = """
            {"competitorSummary":"竞品总结","competitorAdvantages":[{"advantage":"标题清楚","evidence":"标题原文","lesson":"直接说明内容"}],"ownAdvantages":[{"advantage":"未知","evidence":"缺少内容证据"}],"ownDisadvantages":[{"disadvantage":"未知","evidence":"缺少内容证据","risk":"不可推断"}],"gapAnalysis":[{"dimension":"标题","gap":"资料不足","priority":"LOW"}],"improvementSuggestions":[{"suggestion":"补齐证据","reason":"现有样本有限","action":"收集具体反馈"}],"differentiationStrategy":"补齐事实后再比较"}
            """;

    @Test
    void shouldRoundTripExistingFieldShape() throws Exception {
        CreatorCompetitorAnalysisOutput output = mapper.readValue(COMPLETE, CreatorCompetitorAnalysisOutput.class);
        assertThat(mapper.readTree(mapper.writeValueAsString(output))).isEqualTo(mapper.readTree(COMPLETE));
    }

    @Test
    void shouldRejectEmptyRequiredAnalysis() throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(COMPLETE);
        root.putArray("competitorAdvantages");
        assertThatThrownBy(() -> mapper.treeToValue(root, CreatorCompetitorAnalysisOutput.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("competitorAdvantages");
    }

    @Test
    void shouldRejectMissingNestedEvidence() throws Exception {
        JsonNode root = mapper.readTree(COMPLETE);
        ((ObjectNode) root.get("ownDisadvantages").get(0)).remove("evidence");
        assertThatThrownBy(() -> mapper.treeToValue(root, CreatorCompetitorAnalysisOutput.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownDisadvantages[].evidence");
    }

    @Test
    void shouldRejectNullListElement() throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(COMPLETE);
        root.putArray("improvementSuggestions").addNull();
        assertThatThrownBy(() -> mapper.treeToValue(root, CreatorCompetitorAnalysisOutput.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("improvementSuggestions");
    }
}
