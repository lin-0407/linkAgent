package com.link.linkagent.memory;

import com.link.linkagent.util.TextUtil;

/**
 * 摘要压缩时抽取出的单个事实点（设计文档 B2 / D5）。
 *
 * <h3>为什么 fact 与 original 并存</h3>
 * D5 要求高分事实点「连同原文一并送入长期记忆」：fact 是模型提炼的一句话结论，original 是支撑它的用户原话。
 * 压缩后原文不再进入上下文，只留 fact 会丢掉原话里的细节，只留 original 又丢掉了模型判断出的结论，
 * 因此两者都随记录一起保存。
 *
 * @param memoryKey  事实分类键，取值与 {@link LongTermMemory#normalizeMemoryKey} 的 5 类映射一致
 * @param fact       事实点的一句话描述
 * @param original   支撑该事实的用户原话（逐字摘录，可能为空）
 * @param importance 重要性分数（1-100，口径见设计文档 D17.2 的 1-100 调整）；抽取器会保证非空
 */
public record SummaryFactPoint(
        String memoryKey,
        String fact,
        String original,
        Integer importance
) {

    /**
     * 写入长期记忆的正文。
     * <p>
     * 事实点与原文同时落库：读取时原样拼回上下文，既能看到结论，也能回溯用户原话，不经过摘要改写（D5）。
     */
    public String storageContent() {
        String normalizedFact = TextUtil.trimToDefault(fact, "");
        String normalizedOriginal = TextUtil.trimToDefault(original, "");
        if (!TextUtil.hasText(normalizedOriginal)) {
            return normalizedFact;
        }
        if (!TextUtil.hasText(normalizedFact)) {
            return normalizedOriginal;
        }
        return normalizedFact + "\n原文：" + normalizedOriginal;
    }
}
