package com.link.linkagent.memory;

import java.util.List;
import java.util.Objects;

/**
 * 事实点抽取的模型输出契约（设计文档 B2）。
 * <p>
 * 包一层对象而不是让模型直接返回裸数组：旧链路（json_object 回退）里对象比裸数组更容易约束，
 * 以后新增字段也不需要改动调用方。
 *
 * @param facts 模型抽取的事实点列表
 */
public record SummaryFactExtraction(List<SummaryFactPoint> facts) {

    public SummaryFactExtraction {
        // 模型可能返回 null 数组，或在数组里混入 null 元素，这里统一收敛为纯列表，调用方不必再判空。
        facts = facts == null ? List.of() : facts.stream().filter(Objects::nonNull).toList();
    }
}
