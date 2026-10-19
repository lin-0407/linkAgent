package com.link.linkagent.memory;

import com.link.linkagent.llm.ChatRequestEnvelope;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * 复核 JSON 的输出上限。
     * <p>
     * 复核只返回 passed + 两组短句，正常几百 token；给 4k 是为了防止思考模式下跑飞写出几万 token 的思考，
     * 又反过来增加压缩失败后的重试成本。
     */
    private static final int REVIEW_MAX_OUTPUT_TOKENS = 4096;

    /**
     * 摘要正文允许的最大字数；0 表示不限制。
     * 作者 2026-10-18 取消 5000 字上限：压缩后原文不再保留，摘要是唯一载体，
     * 长度应由「保留多少信息」决定，不该在规则层写死。需要限制时用配置显式打开。
     */
    private final int maxSummaryChars;

    private final LLMService llmService;
    private final PromptService promptService;

    /**
     * 生产构造器：字数上限从配置读取，默认 0（不限制）。
     * 单测与最小构造路径走下面两参构造器，同样是不限制。
     */
    @Autowired
    public SummaryReviewer(LLMService llmService, PromptService promptService,
                           @Value("${agent.memory.summary.max-chars:0}") int maxSummaryChars) {
        this.llmService = llmService;
        this.promptService = promptService;
        this.maxSummaryChars = Math.max(0, maxSummaryChars);
    }

    public SummaryReviewer(LLMService llmService, PromptService promptService) {
        this(llmService, promptService, 0);
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
     * 复用摘要生成前缀的复核（2026-10，DSH 式缓存优化）。
     *
     * <p>为什么不重新拼一遍「系统提示词 + 原文」：生成调用刚把那段 25 万 token 的 prompt 写进
     * 供应商前缀缓存，复核如果换一套 system 自己重发，等于同一批原文再冷启动一次。这里直接在
     * 生成消息列表末尾追加一条复核要求，system 与前面所有消息逐字节一致，缓存直接命中。
     *
     * @param generation      摘要生成这次的完整调用信封（摘要正文 + system + 消息列表）
     * @param existingSummary 上一版通过复核的摘要
     * @param messages        本次被压缩的原始消息（规则校验与兜底用，模型侧已在上文）
     */
    public SummaryReviewResult review(SummaryGeneration generation, String existingSummary,
                                      List<MemoryMessage> messages) {
        if (generation == null) {
            return SummaryReviewResult.fail(List.of("摘要生成结果缺失"));
        }
        List<String> ruleIssues = checkRules(generation.summary());
        if (!ruleIssues.isEmpty()) {
            return SummaryReviewResult.fail(ruleIssues);
        }
        return reviewByModel(generation, existingSummary);
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
        // 只有显式配置了正数上限才拦截；默认 0 表示不限制（作者 2026-10-18 决定）。
        if (maxSummaryChars > 0 && normalized.length() > maxSummaryChars) {
            issues.add("摘要超过 " + maxSummaryChars + " 字上限（实际 " + normalized.length() + " 字）");
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

    /** 复核输入里原文与被压缩消息用同一格式，便于模型逐条比对；工具调用渲染成 ToolCall: 工具(参数)。 */
    private String formatMessages(List<MemoryMessage> messages) {
        return messages.stream()
                .map(message -> MemoryObservationContext.prefix(message) + message.role() + ": " + message.promptText())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    /**
     * 前缀复用版模型复核：消息 = 生成调用的消息列表 + 一条复核要求。
     * <p>
     * 复核规则仍然来自 summary_review 提示词，但作为「最后一条用户消息」追加，而不是 system：
     * system 必须和生成调用保持一致，否则整段前缀缓存失效。
     */
    private SummaryReviewResult reviewByModel(SummaryGeneration generation, String existingSummary) {
        String systemPrompt;
        String reviewInstruction;
        try {
            systemPrompt = generation.systemPrompt();
            reviewInstruction = promptService.get("summary_review.system") + "\n\n"
                    + promptService.render("summary_review.user", Map.of(
                    "existingSummary", TextUtil.trimToDefault(existingSummary, "（无）"),
                    "summary", "见上文最后一条生成的摘要",
                    "messages", "见上文对话原文（本次被压缩的消息就在其中，不需要重复粘贴）"))
                    // 生成信封里可能带 tools（真实请求形状）；复核必须只输出 JSON，不能把 token 花在工具调用上。
                    + "\n\n只输出要求的 JSON，不要调用任何工具。";
        } catch (RuntimeException promptException) {
            // 提示词种子缺失是部署错误（必须重新执行 init.sql），和模型侧偶发失败不是一类问题：
            // 它不会自愈，还会让压缩持续不通过，所以单独打 ERROR（作者 2026-09-19 确认）。
            log.error("摘要复核提示词缺失或渲染失败，压缩会持续不通过，请确认已重新执行 init.sql 灌入 summary_review 种子，error={}",
                    promptException.getMessage());
            return SummaryReviewResult.fail(List.of("复核提示词不可用：" + promptException.getMessage()));
        }
        List<Message> reviewMessages = new ArrayList<>();
        if (generation.envelope() != null) {
            // 生产路径：生成调用通过 LLMService 重放，消息列表里没有 SystemMessage（system 在信封里），
            // 原样接在后面追加即可保证前缀逐字节一致。
            reviewMessages.addAll(generation.messages());
        } else {
            // 最小构造路径：生成调用是 Prompt[system + user]，LLMService 会通过 .system(systemPrompt)
            // 注入一次 system，消息列表里再带一份会变成两条 system，前缀从第二条消息起整段错位。
            for (Message message : generation.messages()) {
                if (message instanceof SystemMessage) {
                    continue;
                }
                reviewMessages.add(message);
            }
        }
        // 待复核的摘要必须以 assistant 消息出现：只写「见上一条」的文字占位，模型看不到摘要正文，
        // 会把摘要判成「空/缺失」。追加在生成调用消息之后，前缀仍与生成调用一致，缓存照样命中。
        reviewMessages.add(new AssistantMessage(generation.summary()));
        reviewMessages.add(new UserMessage(reviewInstruction));
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("摘要复核")) {
            // 复核必须用生成调用的同一个信封重放：换成 strict 结果函数或 json_object 会改写请求形状，
            // 供应商端前缀从工具/格式声明处就分叉，那 25 万 token 的原文一次都命中不了缓存。
            ChatRequestEnvelope envelope = generation.envelope();
            SummaryReviewOutput output = envelope != null
                    ? MemoryCallTimeout.call("摘要复核", () -> llmService.replayStructured(envelope, reviewMessages,
                            SummaryReviewOutput.class, REVIEW_MAX_OUTPUT_TOKENS)).entity()
                    : MemoryCallTimeout.call("摘要复核", () -> llmService.chatStructuredWithPrefixReuse(systemPrompt,
                            reviewMessages, SummaryReviewOutput.class));
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
}
