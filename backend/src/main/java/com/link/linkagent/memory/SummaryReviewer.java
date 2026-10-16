package com.link.linkagent.memory;

import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 摘要复核器 —— 压缩前核对摘要是否忠实反映原文（设计文档 B4）。
 *
 * <h3>为什么先规则后模型</h3>
 * 空摘要、超长、格式违规这三类问题不需要模型判断（作者 2026-09 确认「规则校验 + 再调一次模型」）。
 * 规则能判定的问题直接判不通过，省掉一次模型调用；规则过了才交给模型查「漏抽」和「编造」，
 * 这两件事只能靠语义比对，规则做不到。
 *
 * <h3>失败时为什么按不通过处理</h3>
 * 压缩后摘要是被压缩消息的唯一载体（D4），复核模型不可用时无法证明摘要忠实，
 * 宁可不裁剪、下一轮再试，也不接受一份没核对过的摘要（fail-closed）。
 */
@Component
public class SummaryReviewer {

    private static final Logger log = LoggerFactory.getLogger(SummaryReviewer.class);

    /** 摘要提示词要求「控制在 5000 字以内」，超出会直接影响压缩效果，属于规则层可判定的硬问题。 */
    static final int MAX_SUMMARY_CHARS = 5000;

    private final LLMService llmService;
    private final PromptService promptService;

    public SummaryReviewer(LLMService llmService, PromptService promptService) {
        this.llmService = llmService;
        this.promptService = promptService;
    }

    /**
     * 复核摘要。
     *
     * <h3>为什么必须带上「上一版摘要」</h3>
     * 摘要是累积合并的：新摘要会把上一版摘要的内容一起带过来，而上一版摘要对应的原文已经被裁掉。
     * 2026-09-19 本地联调实测：只拿「本次被压缩的消息」做比对时，复核器把从上一版继承的真实信息判成编造，
     * 重生成提示词要求模型「修正」这些问题，结果模型把真实事实改写成了「已剔除」——压缩后唯一载体开始丢信息。
     * 因此复核的比对基准必须是「上一版摘要 + 本次被压缩的消息」两部分，缺一不可。
     *
     * @param summary         待复核的摘要正文
     * @param existingSummary 上一版已通过复核的摘要；为空表示这是第一次压缩
     * @param messages        本次被压缩的原始消息
     * @return 复核结论；不通过时 issues 里是可直接喂回重生成提示词的问题描述
     */
    public SummaryReviewResult review(String summary, String existingSummary, List<MemoryMessage> messages) {
        List<String> ruleIssues = checkRules(summary);
        if (!ruleIssues.isEmpty()) {
            return SummaryReviewResult.fail(ruleIssues);
        }
        return reviewByModel(summary, existingSummary, messages);
    }

    /**
     * 规则校验：空、超长、Markdown 代码块。
     * 只做确定性判断，不做「像不像漏抽」这类需要语义的猜测。
     */
    List<String> checkRules(String summary) {
        List<String> issues = new ArrayList<>();
        if (TextUtil.isBlank(summary)) {
            issues.add("摘要为空");
            return issues;
        }
        String normalized = summary.trim();
        if (normalized.length() > MAX_SUMMARY_CHARS) {
            issues.add("摘要超过 " + MAX_SUMMARY_CHARS + " 字上限（实际 " + normalized.length() + " 字）");
        }
        if (normalized.contains("```")) {
            issues.add("摘要包含 Markdown 代码块，要求输出纯文本");
        }
        return issues;
    }

    /**
     * 模型复核：拿摘要对照原始消息，检查漏抽与编造。
     * 模型输出本身也不能全信——它可能给出 passed=true 却同时列了问题，这种自相矛盾按不通过处理。
     */
    private SummaryReviewResult reviewByModel(String summary, String existingSummary, List<MemoryMessage> messages) {
        String systemPrompt;
        String userPrompt;
        try {
            systemPrompt = promptService.get("summary_review.system");
            userPrompt = promptService.render("summary_review.user", Map.of(
                    "existingSummary", TextUtil.trimToDefault(existingSummary, "（无）"),
                    "summary", summary,
                    "messages", formatMessages(messages)));
        } catch (RuntimeException promptException) {
            // 提示词种子缺失是部署错误（必须重新执行 init.sql），和模型侧偶发失败不是一类问题：
            // 它不会自愈，还会让压缩持续不通过，所以单独打 ERROR（作者 2026-09-19 确认）。
            log.error("摘要复核提示词缺失或渲染失败，压缩会持续不通过，请确认已重新执行 init.sql 灌入 summary_review 种子，error={}",
                    promptException.getMessage());
            return SummaryReviewResult.fail(List.of("复核提示词不可用：" + promptException.getMessage()));
        }
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("摘要复核")) {
            SummaryReviewOutput output = MemoryCallTimeout.call("摘要复核",
                    () -> llmService.chatStructured(systemPrompt, userPrompt, SummaryReviewOutput.class));
            if (output == null) {
                return SummaryReviewResult.fail(List.of("复核模型未返回结果"));
            }
            List<String> issues = new ArrayList<>();
            output.missing().forEach(item -> issues.add("漏抽：" + item));
            output.fabricated().forEach(item -> issues.add("编造：" + item));
            if (!output.passed() && issues.isEmpty()) {
                issues.add("复核判定摘要不合格");
            }
            return issues.isEmpty() ? SummaryReviewResult.pass() : SummaryReviewResult.fail(issues);
        } catch (Exception exception) {
            log.warn("摘要复核调用失败，本轮按不通过处理，error={}", exception.getMessage());
            return SummaryReviewResult.fail(List.of("复核调用失败：" + exception.getMessage()));
        }
    }

    /** 复核输入里原文与被压缩消息用同一格式，便于模型逐条比对。 */
    private String formatMessages(List<MemoryMessage> messages) {
        return messages.stream()
                .map(message -> MemoryObservationContext.prefix(message) + message.role() + ": " + message.content())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }
}
