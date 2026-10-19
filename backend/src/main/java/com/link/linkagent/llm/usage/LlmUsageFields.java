package com.link.linkagent.llm.usage;

import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * 从 Spring AI 的 {@link Usage} 里取各家供应商都有的统计字段。
 *
 * <p>单独抽出来的原因：摘要生成/复核走的是 ChatModel 或结构化调用，不在
 * {@link com.link.linkagent.llm.LLMService} 的文本链路上，但缓存命中率必须用同一套口径统计，
 * 否则同一张 llm_api_call_log 表里会出现两种算法。
 */
public final class LlmUsageFields {

    private LlmUsageFields() {
    }

    /** Usage 是否缺失：null 或 Spring AI 的 EmptyUsage（三项 token 全为 0/null）。 */
    public static boolean isMissing(Usage usage) {
        if (usage == null || usage instanceof EmptyUsage) {
            return true;
        }
        return isZeroOrNull(usage.getPromptTokens())
                && isZeroOrNull(usage.getCompletionTokens())
                && isZeroOrNull(usage.getTotalTokens());
    }

    public static Integer promptTokens(Usage usage) {
        return isMissing(usage) ? null : usage.getPromptTokens();
    }

    public static Integer completionTokens(Usage usage) {
        return isMissing(usage) ? null : usage.getCompletionTokens();
    }

    public static Integer totalTokens(Usage usage) {
        return isMissing(usage) ? null : usage.getTotalTokens();
    }

    /**
     * 命中前缀缓存的输入 token 数，读不到时返回 null。
     * <p>
     * DeepSeek 的 usage 里带 {@code prompt_tokens_details.cached_tokens}，Spring AI 1.1.4 的
     * {@code OpenAiApi.Usage.PromptTokensDetails} 已经解析了这个字段；供应商不返回时保持 null，
     * 不能把「没有缓存信息」误当成「命中 0」。
     */
    public static Integer cachedPromptTokens(Usage usage) {
        if (isMissing(usage)) {
            return null;
        }
        Object nativeUsage = usage.getNativeUsage();
        if (!(nativeUsage instanceof OpenAiApi.Usage openAiUsage)) {
            return null;
        }
        OpenAiApi.Usage.PromptTokensDetails details = openAiUsage.promptTokensDetails();
        return details == null ? null : details.cachedTokens();
    }

    private static boolean isZeroOrNull(Integer value) {
        return value == null || value == 0;
    }
}
