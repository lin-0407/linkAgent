package com.link.linkagent.memory;

import java.util.List;

/**
 * 一次摘要事实点抽取的结果。
 *
 * <h3>为什么要区分「没抽到」和「没成功」</h3>
 * 压缩会把被压缩消息的原文从短期记忆里裁掉，同时把长期记忆游标推进到这批消息（设计文档 A6 / C4）。
 * 如果抽取调用失败却按「没有值得记的事实」处理，游标照样推进，这批消息就只剩下摘要一个载体，
 * 高分事实点永久丢失。因此调用方需要知道异常情况，失败时保持游标不动，等下轮增量提取重新覆盖这批消息。
 *
 * @param succeeded 本次抽取调用是否成功拿到符合契约的模型输出
 * @param facts     抽取到的事实点；调用失败时为空列表
 */
public record SummaryFactExtractionResult(boolean succeeded, List<SummaryFactPoint> facts) {

    public SummaryFactExtractionResult {
        facts = facts == null ? List.of() : facts;
    }

    /** 调用成功（可能一条事实点都没抽到）。跨包调用（AgentExecutor）也要能构造，因此是 public。 */
    public static SummaryFactExtractionResult ok(List<SummaryFactPoint> facts) {
        return new SummaryFactExtractionResult(true, facts);
    }

    /** 调用失败或输出不可用：游标不推进。 */
    public static SummaryFactExtractionResult failed() {
        return new SummaryFactExtractionResult(false, List.of());
    }
}
