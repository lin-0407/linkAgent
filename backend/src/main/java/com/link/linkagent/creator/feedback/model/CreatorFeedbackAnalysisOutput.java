package com.link.linkagent.creator.feedback.model;

import java.util.List;

import static com.link.linkagent.llm.StructuredOutputValidator.requireList;
import static com.link.linkagent.llm.StructuredOutputValidator.requireText;

/**
 * 评论弹幕分析的模型结构化输出。
 * 构造阶段检查字段完整性，是为了让 JSON 字段漂移在 LLMService 内触发重试，而不是落成空报告。
 */
public record CreatorFeedbackAnalysisOutput(
        String feedbackSummary,
        String creatorFeedbackDilemma,
        String audienceCoreConcern,
        List<HotTopic> hotTopics,
        String sentimentSummary,
        List<ControversyPoint> controversyPoints,
        List<MisunderstandingPoint> misunderstandingPoints,
        List<MisunderstandingSource> misunderstandingSourceAnalysis,
        List<NextContentSuggestion> nextContentSuggestions,
        List<InteractionSuggestion> interactionSuggestions,
        List<FeedbackAction> feedbackActionPlan
) {
    public CreatorFeedbackAnalysisOutput {
        feedbackSummary = requireText(feedbackSummary, "feedbackSummary");
        creatorFeedbackDilemma = requireText(creatorFeedbackDilemma, "creatorFeedbackDilemma");
        audienceCoreConcern = requireText(audienceCoreConcern, "audienceCoreConcern");
        hotTopics = requireList(hotTopics, "hotTopics");
        sentimentSummary = requireText(sentimentSummary, "sentimentSummary");
        controversyPoints = requireList(controversyPoints, "controversyPoints");
        misunderstandingPoints = requireList(misunderstandingPoints, "misunderstandingPoints");
        misunderstandingSourceAnalysis = requireList(
                misunderstandingSourceAnalysis,
                "misunderstandingSourceAnalysis"
        );
        nextContentSuggestions = requireList(nextContentSuggestions, "nextContentSuggestions");
        interactionSuggestions = requireList(interactionSuggestions, "interactionSuggestions");
        feedbackActionPlan = requireList(feedbackActionPlan, "feedbackActionPlan");
        if (hotTopics.isEmpty()) {
            throw new IllegalArgumentException("结构化输出缺少必要分析内容：hotTopics");
        }
        if (nextContentSuggestions.isEmpty()) {
            throw new IllegalArgumentException("结构化输出缺少必要分析内容：nextContentSuggestions");
        }
        if (feedbackActionPlan.isEmpty()) {
            throw new IllegalArgumentException("结构化输出缺少必要分析内容：feedbackActionPlan");
        }
    }

    public record HotTopic(
            String topic,
            String evidence,
            String creatorDecision,
            String suggestion
    ) {
        public HotTopic {
            topic = requireText(topic, "hotTopics[].topic");
            evidence = requireText(evidence, "hotTopics[].evidence");
            creatorDecision = requireText(creatorDecision, "hotTopics[].creatorDecision");
            suggestion = requireText(suggestion, "hotTopics[].suggestion");
        }
    }

    public record ControversyPoint(
            String point,
            String risk,
            String responseBoundary,
            String responseAdvice
    ) {
        public ControversyPoint {
            point = requireText(point, "controversyPoints[].point");
            risk = requireText(risk, "controversyPoints[].risk");
            responseBoundary = requireText(responseBoundary, "controversyPoints[].responseBoundary");
            responseAdvice = requireText(responseAdvice, "controversyPoints[].responseAdvice");
        }
    }

    public record MisunderstandingPoint(
            String point,
            String source,
            String clarificationAdvice
    ) {
        public MisunderstandingPoint {
            point = requireText(point, "misunderstandingPoints[].point");
            source = requireText(source, "misunderstandingPoints[].source");
            clarificationAdvice = requireText(clarificationAdvice, "misunderstandingPoints[].clarificationAdvice");
        }
    }

    public record MisunderstandingSource(
            String source,
            String reason,
            String repairAction
    ) {
        public MisunderstandingSource {
            source = requireText(source, "misunderstandingSourceAnalysis[].source");
            reason = requireText(reason, "misunderstandingSourceAnalysis[].reason");
            repairAction = requireText(repairAction, "misunderstandingSourceAnalysis[].repairAction");
        }
    }

    public record NextContentSuggestion(
            String topic,
            String sourceSignal,
            String executionHint,
            String risk
    ) {
        public NextContentSuggestion {
            topic = requireText(topic, "nextContentSuggestions[].topic");
            sourceSignal = requireText(sourceSignal, "nextContentSuggestions[].sourceSignal");
            executionHint = requireText(executionHint, "nextContentSuggestions[].executionHint");
            risk = requireText(risk, "nextContentSuggestions[].risk");
        }
    }

    public record InteractionSuggestion(
            String channel,
            String message,
            String purpose
    ) {
        public InteractionSuggestion {
            channel = requireText(channel, "interactionSuggestions[].channel");
            message = requireText(message, "interactionSuggestions[].message");
            purpose = requireText(purpose, "interactionSuggestions[].purpose");
        }
    }

    public record FeedbackAction(
            String priority,
            String action,
            String reason,
            String expectedResult
    ) {
        public FeedbackAction {
            priority = requireText(priority, "feedbackActionPlan[].priority");
            action = requireText(action, "feedbackActionPlan[].action");
            reason = requireText(reason, "feedbackActionPlan[].reason");
            expectedResult = requireText(expectedResult, "feedbackActionPlan[].expectedResult");
        }
    }
}
