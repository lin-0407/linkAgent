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
 * 摘要事实点抽取器 —— 压缩长对话时，从被压缩的消息里抽出值得长期保留的用户事实（设计文档 B2 / D5）。
 *
 * <h3>为什么单独一次模型调用</h3>
 * 事实点抽取是「判断什么值得长期记住」的独立认知任务，与摘要的「忠实压缩」目标不同。
 * 放在同一次调用里会让提示词同时承载两个目标，输出约束变松、也更容易漏抽；作者 2026-09 确认单独调用。
 *
 * <h3>失败处理</h3>
 * 抽取是增强能力：调用失败或输出不合法时返回失败标记（不抛异常），既不写长期记忆，也不影响本轮压缩与回答。
 * 失败必须能被调用方识别：压缩会裁掉原文并推进长期记忆游标，抽取失败时不能按「没有事实」处理。
 *
 * <h3>与后续步骤的关系</h3>
 * 单轮写入条数上限（C14）与打分口径（D17.2）在这里先落地一个保守值，
 * 步骤 5 的累加器会决定「什么时候触发提取」，本类只负责「抽取并打分」。
 */
@Component
public class SummaryFactExtractor {

    private static final Logger log = LoggerFactory.getLogger(SummaryFactExtractor.class);

    /**
     * 单次压缩最多保留的事实点数。
     * 250k token 的对话可能抽出上百条事实，全部写入会让记忆列表被一批噪音淹没；
     * 这个上限是步骤 7 C14「单轮写入条数设上限」的先行值，口径调整时一起改。
     */
    static final int MAX_FACTS_PER_COMPRESSION = 20;

    /** 模型没给出可用分数时的兜底值：沿用 D17.2 的兜底语义，按 1-100 口径取 40。 */
    static final int DEFAULT_IMPORTANCE = 40;

    private static final int MIN_IMPORTANCE = 1;
    private static final int MAX_IMPORTANCE = 100;

    private final LLMService llmService;
    private final PromptService promptService;

    public SummaryFactExtractor(LLMService llmService, PromptService promptService) {
        this.llmService = llmService;
        this.promptService = promptService;
    }

    /**
     * 从本次要压缩的消息里抽取事实点。
     *
     * @param messages 本次被压缩掉的对话消息（原文）
     * @return 抽取结果；没有可抽取内容时成功且为空列表，调用失败时 {@code succeeded()} 为 false
     *         （调用方据此决定长期记忆游标是否随压缩一起推进）
     */
    public SummaryFactExtractionResult extract(List<MemoryMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return SummaryFactExtractionResult.ok(List.of());
        }
        String systemPrompt;
        String userPrompt;
        try {
            systemPrompt = promptService.get("summary_fact.system");
            userPrompt = promptService.render("summary_fact.user", Map.of("messages", formatMessages(messages)));
        } catch (RuntimeException promptException) {
            // 提示词种子缺失是部署错误（必须重新执行 init.sql），不会自愈，单独打 ERROR 便于当场发现。
            log.error("事实点抽取提示词缺失或渲染失败，请确认已重新执行 init.sql 灌入 summary_fact 种子，error={}",
                    promptException.getMessage());
            return SummaryFactExtractionResult.failed();
        }
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("摘要事实点抽取")) {
            SummaryFactExtraction output = MemoryCallTimeout.call("摘要事实点抽取",
                    () -> llmService.chatStructured(systemPrompt, userPrompt, SummaryFactExtraction.class));
            return SummaryFactExtractionResult.ok(normalize(output));
        } catch (Exception exception) {
            log.warn("摘要事实点抽取失败，本轮不写长期记忆、游标不推进，error={}", exception.getMessage());
            return SummaryFactExtractionResult.failed();
        }
    }

    /**
     * 归一化模型输出：丢掉空事实、限制条数、把重要性分数收敛回 1-100。
     * 模型输出不能直接入库——空内容会写出一条无意义记忆，越界分数会污染后续检索排序。
     */
    List<SummaryFactPoint> normalize(SummaryFactExtraction output) {
        if (output == null || output.facts().isEmpty()) {
            return List.of();
        }
        List<SummaryFactPoint> normalized = new ArrayList<>();
        int dropped = 0;
        for (SummaryFactPoint point : output.facts()) {
            String fact = TextUtil.trimToDefault(point.fact(), "");
            String original = TextUtil.trimToDefault(point.original(), "");
            if (!TextUtil.hasText(fact) && !TextUtil.hasText(original)) {
                dropped++;
                continue;
            }
            if (normalized.size() >= MAX_FACTS_PER_COMPRESSION) {
                dropped++;
                continue;
            }
            normalized.add(new SummaryFactPoint(
                    TextUtil.trimToDefault(point.memoryKey(), ""),
                    fact,
                    original,
                    normalizeImportance(point.importance())));
        }
        if (dropped > 0) {
            log.info("事实点抽取结果已裁剪：丢弃 {} 条（空内容或超过单轮上限 {} 条）",
                    dropped, MAX_FACTS_PER_COMPRESSION);
        }
        return normalized;
    }

    /**
     * 分数收敛：1-100 之外的值按边界截断；没给分数或给了非正数时用兜底 40。
     * 不直接信任模型返回的数字，避免 0 分或 150 分这类值进入后续的三维打分。
     */
    int normalizeImportance(Integer importance) {
        if (importance == null || importance <= 0) {
            return DEFAULT_IMPORTANCE;
        }
        return Math.min(MAX_IMPORTANCE, Math.max(MIN_IMPORTANCE, importance));
    }

    /** 与被压缩消息保持同一文本格式，便于模型对照原文摘录；工具调用渲染成 ToolCall: 工具(参数)。 */
    private String formatMessages(List<MemoryMessage> messages) {
        return messages.stream()
                .map(message -> MemoryObservationContext.prefix(message) + message.role() + ": " + message.promptText())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }
}
