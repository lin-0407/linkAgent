package com.link.linkagent.creator.competitor.model;

import java.util.List;

import static com.link.linkagent.llm.StructuredOutputValidator.requireList;
import static com.link.linkagent.llm.StructuredOutputValidator.requireText;

/** 竞品也走强类型结构化调用，避免模型只返回普通文字后整个分析在落库阶段失败。 */
public record CreatorCompetitorAnalysisOutput(
        String competitorSummary,
        List<CompetitorAdvantage> competitorAdvantages,
        List<OwnAdvantage> ownAdvantages,
        List<OwnDisadvantage> ownDisadvantages,
        List<GapAnalysis> gapAnalysis,
        List<ImprovementSuggestion> improvementSuggestions,
        String differentiationStrategy
) {
    public CreatorCompetitorAnalysisOutput {
        competitorSummary = requireText(competitorSummary, "competitorSummary");
        competitorAdvantages = requiredAnalysisList(competitorAdvantages, "competitorAdvantages");
        ownAdvantages = requiredAnalysisList(ownAdvantages, "ownAdvantages");
        ownDisadvantages = requiredAnalysisList(ownDisadvantages, "ownDisadvantages");
        gapAnalysis = requiredAnalysisList(gapAnalysis, "gapAnalysis");
        improvementSuggestions = requiredAnalysisList(improvementSuggestions, "improvementSuggestions");
        differentiationStrategy = requireText(differentiationStrategy, "differentiationStrategy");
    }

    private static <T> List<T> requiredAnalysisList(List<T> values, String field) {
        List<T> required = requireList(values, field);
        if (required.isEmpty()) {
            throw new IllegalArgumentException("结构化输出缺少必要分析内容：" + field);
        }
        return required;
    }

    public record CompetitorAdvantage(String advantage, String evidence, String lesson) {
        public CompetitorAdvantage {
            advantage = requireText(advantage, "competitorAdvantages[].advantage");
            evidence = requireText(evidence, "competitorAdvantages[].evidence");
            lesson = requireText(lesson, "competitorAdvantages[].lesson");
        }
    }

    public record OwnAdvantage(String advantage, String evidence) {
        public OwnAdvantage {
            advantage = requireText(advantage, "ownAdvantages[].advantage");
            evidence = requireText(evidence, "ownAdvantages[].evidence");
        }
    }

    public record OwnDisadvantage(String disadvantage, String evidence, String risk) {
        public OwnDisadvantage {
            disadvantage = requireText(disadvantage, "ownDisadvantages[].disadvantage");
            evidence = requireText(evidence, "ownDisadvantages[].evidence");
            risk = requireText(risk, "ownDisadvantages[].risk");
        }
    }

    public record GapAnalysis(String dimension, String gap, String priority) {
        public GapAnalysis {
            dimension = requireText(dimension, "gapAnalysis[].dimension");
            gap = requireText(gap, "gapAnalysis[].gap");
            priority = requireText(priority, "gapAnalysis[].priority");
        }
    }

    public record ImprovementSuggestion(String suggestion, String reason, String action) {
        public ImprovementSuggestion {
            suggestion = requireText(suggestion, "improvementSuggestions[].suggestion");
            reason = requireText(reason, "improvementSuggestions[].reason");
            action = requireText(action, "improvementSuggestions[].action");
        }
    }
}
