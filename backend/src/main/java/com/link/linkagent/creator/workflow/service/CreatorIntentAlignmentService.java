package com.link.linkagent.creator.workflow.service;

import com.link.linkagent.creator.workflow.model.CreatorIntentAlignmentContext;
import com.link.linkagent.creator.workflow.model.CreatorIntentAlignmentOutput;
import com.link.linkagent.creator.workflow.model.CreatorIntentReviewIssue;
import com.link.linkagent.creator.workflow.model.CreatorIntentReviewResult;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.TextUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 创作想法对齐专用双 Agent 服务。
 * 主 Agent 只负责复述当前理解和提出关键疑问；审查 Agent 只检查是否偏离用户原话，
 * 不生成方案、不提供改写意见，也不把审查内容写入用户消息历史。
 */
@Service
public class CreatorIntentAlignmentService {

    private static final String MAIN_PROMPT_KEY = "creator_alignment.main.system";
    private static final String REVIEW_PROMPT_KEY = "creator_alignment.review.system";
    private static final int MAX_QUESTION_COUNT = 3;
    private static final int MAX_UNDERSTANDING_LENGTH = 800;
    private static final int MAX_QUESTION_LENGTH = 240;

    private final LLMService llmService;
    private final PromptService promptService;

    public CreatorIntentAlignmentService(LLMService llmService, PromptService promptService) {
        this.llmService = llmService;
        this.promptService = promptService;
    }

    /**
     * 生成本轮理解并审查；发现偏离时主 Agent 最多重答一次，重答后再做最终检查。
     */
    public CreatorIntentAlignmentOutput align(CreatorIntentAlignmentContext context) {
        return generateReviewed(context, false);
    }

    /**
     * 同一上下文连续生成三次方案仍未被采用时，只追问分歧，不再继续生成方案。
     */
    public CreatorIntentAlignmentOutput clarifyAfterPlanLimit(CreatorIntentAlignmentContext context) {
        return generateReviewed(context, true);
    }

    /**
     * 候选内容在重答后仍然偏离时，不再让模型继续猜，改为向用户明确追问分歧。
     */
    public CreatorIntentAlignmentOutput clarifyDeviation(CreatorIntentReviewResult reviewResult) {
        if (!hasDeviation(reviewResult)) {
            throw new IllegalArgumentException("没有偏离问题时不应生成澄清回复");
        }
        return buildClarificationFallback(reviewResult);
    }

    /**
     * 检查任意候选回复或发布方案是否偏离用户原始意图。
     */
    public CreatorIntentReviewResult review(CreatorIntentAlignmentContext context, String candidate) {
        String normalizedCandidate = TextUtil.trimToNull(candidate);
        if (normalizedCandidate == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "待审查的 AI 回复为空");
        }
        CreatorIntentReviewResult result = llmService.chatStructured(
                promptService.get(REVIEW_PROMPT_KEY),
                buildReviewUserPrompt(context, normalizedCandidate),
                CreatorIntentReviewResult.class
        );
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "审查 Agent 未返回结果");
        }
        List<CreatorIntentReviewIssue> issues = result.issues() == null ? List.of() : result.issues().stream()
                .filter(issue -> issue != null
                        && TextUtil.hasText(issue.userQuote())
                        && TextUtil.hasText(issue.reason()))
                .limit(3)
                .toList();
        return new CreatorIntentReviewResult(result.deviated() && !issues.isEmpty(), issues);
    }

    /**
     * 把结构化理解渲染成用户直接能读的短消息。
     */
    public String render(CreatorIntentAlignmentOutput output) {
        CreatorIntentAlignmentOutput normalized = normalize(output);
        StringBuilder builder = new StringBuilder("我理解的是：")
                .append(normalized.understanding());
        if (!normalized.questions().isEmpty()) {
            builder.append("\n\n我还没确定的是：\n");
            for (int index = 0; index < normalized.questions().size(); index++) {
                builder.append(index + 1)
                        .append(". ")
                        .append(normalized.questions().get(index));
                if (index + 1 < normalized.questions().size()) {
                    builder.append("\n");
                }
            }
        }
        return builder.toString();
    }

    /**
     * 把审查发现转换为主 Agent 可读取的偏离提醒；内容只包含用户原话和原因。
     */
    public String buildReviewReminder(CreatorIntentReviewResult reviewResult) {
        if (!hasDeviation(reviewResult)) {
            return "无";
        }
        StringBuilder builder = new StringBuilder();
        for (CreatorIntentReviewIssue issue : reviewResult.issues()) {
            builder.append("- 用户原话：")
                    .append(issue.userQuote().trim())
                    .append("\n  偏离原因：")
                    .append(issue.reason().trim())
                    .append("\n");
        }
        return builder.toString().trim();
    }

    private CreatorIntentAlignmentOutput generate(CreatorIntentAlignmentContext context,
                                                  CreatorIntentReviewResult reviewResult,
                                                  boolean clarificationOnly) {
        CreatorIntentAlignmentOutput output = llmService.chatStructured(
                promptService.get(MAIN_PROMPT_KEY),
                buildMainUserPrompt(context, reviewResult, clarificationOnly),
                CreatorIntentAlignmentOutput.class
        );
        return normalize(output);
    }

    private CreatorIntentAlignmentOutput generateReviewed(CreatorIntentAlignmentContext context,
                                                           boolean clarificationOnly) {
        CreatorIntentAlignmentOutput firstDraft = generate(context, null, clarificationOnly);
        CreatorIntentReviewResult firstReview = review(context, render(firstDraft));
        if (!hasDeviation(firstReview)) {
            return firstDraft;
        }

        // 重答时仍把用户原始上下文作为唯一事实来源，审查结果只负责指出哪里偏了。
        CreatorIntentAlignmentOutput retryDraft = generate(context, firstReview, clarificationOnly);
        CreatorIntentReviewResult retryReview = review(context, render(retryDraft));
        if (!hasDeviation(retryReview)) {
            return retryDraft;
        }
        return buildClarificationFallback(retryReview);
    }

    /**
     * 主 Agent 重答后仍然偏离时停止猜测，直接把分歧交还给用户说明。
     */
    private CreatorIntentAlignmentOutput buildClarificationFallback(CreatorIntentReviewResult reviewResult) {
        List<String> questions = reviewResult.issues().stream()
                .map(issue -> "你说“%s”，但我现在仍可能理解偏了：%s。你希望我按什么意思理解？"
                        .formatted(issue.userQuote().trim(), issue.reason().trim()))
                .limit(MAX_QUESTION_COUNT)
                .toList();
        return normalize(new CreatorIntentAlignmentOutput(
                "我还没有把你的意思理解准，这次先不继续往下生成。",
                questions
        ));
    }

    private CreatorIntentAlignmentOutput normalize(CreatorIntentAlignmentOutput output) {
        if (output == null || !TextUtil.hasText(output.understanding())) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "主 Agent 未返回有效理解");
        }
        String understanding = TextUtil.abbreviateWithSuffix(
                output.understanding().trim(),
                MAX_UNDERSTANDING_LENGTH,
                "……"
        );
        LinkedHashSet<String> questions = new LinkedHashSet<>();
        if (output.questions() != null) {
            for (String question : output.questions()) {
                if (!TextUtil.hasText(question)) {
                    continue;
                }
                questions.add(TextUtil.abbreviateWithSuffix(
                        question.trim(),
                        MAX_QUESTION_LENGTH,
                        "……"
                ));
                if (questions.size() >= MAX_QUESTION_COUNT) {
                    break;
                }
            }
        }
        return new CreatorIntentAlignmentOutput(understanding, List.copyOf(questions));
    }

    private boolean hasDeviation(CreatorIntentReviewResult reviewResult) {
        return reviewResult != null
                && reviewResult.deviated()
                && reviewResult.issues() != null
                && !reviewResult.issues().isEmpty();
    }

    private String buildMainUserPrompt(CreatorIntentAlignmentContext context,
                                       CreatorIntentReviewResult reviewResult,
                                       boolean clarificationOnly) {
        String modeInstruction;
        if (clarificationOnly) {
            modeInstruction = """
                    用户已经在同一上下文下连续查看了三版发布方案，但都没有采用。
                    不要再生成或概括发布方案。请直接指出你认为最可能没有对齐的地方，
                    并提出一到三个足够具体、用户回答后就能改变下一版方案的问题。
                    """;
        } else if (hasDeviation(reviewResult)) {
            modeInstruction = """
                    独立审查 Agent 发现上一版理解存在偏离。审查内容不是改写意见，
                    你必须重新阅读用户原始上下文后自行调整，只能把下面内容当作偏离提醒：
                    %s
                    """.formatted(buildReviewReminder(reviewResult));
        } else {
            modeInstruction = """
                    这是本轮第一次对齐。请说明你现在真正理解到的用户意图；
                    只有会影响后续方案的缺口才提问，已经明确的信息不要重复问。
                    """;
        }
        return """
                用户原始上下文：
                %s

                本轮要求：
                %s
                """.formatted(context.sourceContext(), modeInstruction.trim());
    }

    private String buildReviewUserPrompt(CreatorIntentAlignmentContext context, String candidate) {
        return """
                用户原始上下文：
                %s

                待审查的候选回复：
                %s
                """.formatted(context.sourceContext(), candidate);
    }
}
