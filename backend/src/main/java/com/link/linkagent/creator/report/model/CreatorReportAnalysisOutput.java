package com.link.linkagent.creator.report.model;

import java.util.List;

import static com.link.linkagent.llm.StructuredOutputValidator.requireList;
import static com.link.linkagent.llm.StructuredOutputValidator.requireText;
import static com.link.linkagent.llm.StructuredOutputValidator.requireTextList;

/**
 * 最终创作复盘的模型结构化输出。
 * 必填字段在构造阶段校验，避免合法 JSON 因字段漂移继续沉淀空报告和错误偏好。
 */
public record CreatorReportAnalysisOutput(
        String contentSummary,
        List<String> coreSellingPoints,
        TitleDescriptionReview titleDescriptionReview,
        String audienceFeedbackSummary,
        CompetitorComparison competitorComparison,
        List<ControversyAndMisunderstanding> controversyAndMisunderstanding,
        List<NextActionSuggestion> nextActionSuggestions,
        List<String> creatorPreferenceInsight,
        String overallConclusion
) {
    public CreatorReportAnalysisOutput {
        contentSummary = requireText(contentSummary, "contentSummary");
        coreSellingPoints = requireTextList(coreSellingPoints, "coreSellingPoints");
        if (titleDescriptionReview == null) {
            throw new IllegalArgumentException("结构化输出缺少必填字段：titleDescriptionReview");
        }
        audienceFeedbackSummary = requireText(audienceFeedbackSummary, "audienceFeedbackSummary");
        if (competitorComparison == null) {
            throw new IllegalArgumentException("结构化输出缺少必填字段：competitorComparison");
        }
        controversyAndMisunderstanding = requireList(
                controversyAndMisunderstanding,
                "controversyAndMisunderstanding"
        );
        nextActionSuggestions = requireList(nextActionSuggestions, "nextActionSuggestions");
        creatorPreferenceInsight = requireTextList(creatorPreferenceInsight, "creatorPreferenceInsight");
        overallConclusion = requireText(overallConclusion, "overallConclusion");
        if (coreSellingPoints.isEmpty()) {
            throw new IllegalArgumentException("结构化输出缺少必要分析内容：coreSellingPoints");
        }
        if (nextActionSuggestions.isEmpty()) {
            throw new IllegalArgumentException("结构化输出缺少必要分析内容：nextActionSuggestions");
        }
        if (creatorPreferenceInsight.isEmpty()) {
            throw new IllegalArgumentException("结构化输出缺少必要分析内容：creatorPreferenceInsight");
        }
    }

    public record TitleDescriptionReview(
            String titleConclusion,
            String descriptionConclusion,
            String tagAndPartitionConclusion,
            String riskReminder
    ) {
        public TitleDescriptionReview {
            titleConclusion = requireText(titleConclusion, "titleDescriptionReview.titleConclusion");
            descriptionConclusion = requireText(descriptionConclusion, "titleDescriptionReview.descriptionConclusion");
            tagAndPartitionConclusion = requireText(
                    tagAndPartitionConclusion,
                    "titleDescriptionReview.tagAndPartitionConclusion"
            );
            riskReminder = requireText(riskReminder, "titleDescriptionReview.riskReminder");
        }
    }

    public record CompetitorComparison(
            String benchmarkConclusion,
            List<String> ownAdvantages,
            List<String> ownDisadvantages,
            String differentiationStrategy
    ) {
        public CompetitorComparison {
            benchmarkConclusion = requireText(benchmarkConclusion, "competitorComparison.benchmarkConclusion");
            ownAdvantages = requireTextList(ownAdvantages, "competitorComparison.ownAdvantages");
            ownDisadvantages = requireTextList(ownDisadvantages, "competitorComparison.ownDisadvantages");
            differentiationStrategy = requireText(
                    differentiationStrategy,
                    "competitorComparison.differentiationStrategy"
            );
            if (ownAdvantages.isEmpty()) {
                throw new IllegalArgumentException(
                        "结构化输出缺少必要分析内容：competitorComparison.ownAdvantages"
                );
            }
            if (ownDisadvantages.isEmpty()) {
                throw new IllegalArgumentException(
                        "结构化输出缺少必要分析内容：competitorComparison.ownDisadvantages"
                );
            }
        }
    }

    public record ControversyAndMisunderstanding(
            String point,
            String impact,
            String action
    ) {
        public ControversyAndMisunderstanding {
            point = requireText(point, "controversyAndMisunderstanding[].point");
            impact = requireText(impact, "controversyAndMisunderstanding[].impact");
            action = requireText(action, "controversyAndMisunderstanding[].action");
        }
    }

    public record NextActionSuggestion(
            String suggestion,
            String reason,
            String priority
    ) {
        public NextActionSuggestion {
            suggestion = requireText(suggestion, "nextActionSuggestions[].suggestion");
            reason = requireText(reason, "nextActionSuggestions[].reason");
            priority = requireText(priority, "nextActionSuggestions[].priority");
        }
    }
}
