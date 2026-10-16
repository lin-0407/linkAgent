package com.link.linkagent.memory;

import java.util.List;

/**
 * 一次长期记忆抽取的结果（设计文档 C4 / C12）。
 *
 * <h3>为什么不能是 Optional</h3>
 * 游标只允许在「抽取成功」后推进（设计文档 C4）。而 {@code Optional.empty()} 同时表示
 * 「模型说这段对话没有值得记的信息」和「模型调用失败/输出不符合契约」两种情况——
 * 前者必须推进游标（否则这段消息每轮都会被重抽），后者必须原地等待下次重试。
 * 用一个显式的成功标记把两者分开。
 *
 * <h3>为什么是列表</h3>
 * 步骤 7 起阶段一一次可以产出多条候选（作者上手测试发现：单条契约会漏掉同一批里的其它事实，
 * 而游标把这批一次性推过后就再也不会回看）。同一分类键保留最高分，避免一条候选占满整批。
 *
 * @param succeeded 本次调用是否成功拿到符合契约的模型输出
 * @param candidates 抽取到的候选事实；{@code succeeded == true} 且列表为空表示「确实没有值得记的内容」
 */
public record LongTermMemoryExtractionResult(boolean succeeded, List<LongTermMemoryCandidate> candidates) {

    public LongTermMemoryExtractionResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /** 抽取成功且拿到多条候选。 */
    public static LongTermMemoryExtractionResult remembered(List<LongTermMemoryCandidate> candidates) {
        return new LongTermMemoryExtractionResult(true, candidates);
    }

    /** 抽取成功且只有一条候选（单候选场景与单测用）。 */
    public static LongTermMemoryExtractionResult remembered(LongTermMemoryCandidate candidate) {
        return remembered(candidate == null ? List.of() : List.of(candidate));
    }

    /** 调用成功，但模型判定这段对话没有值得长期保留的信息。 */
    public static LongTermMemoryExtractionResult noMemory() {
        return new LongTermMemoryExtractionResult(true, List.of());
    }

    /** 调用失败或输出不符合契约：游标不推进，预算不重置。 */
    public static LongTermMemoryExtractionResult failed() {
        return new LongTermMemoryExtractionResult(false, List.of());
    }

    public boolean hasCandidates() {
        return succeeded && !candidates.isEmpty();
    }
}
