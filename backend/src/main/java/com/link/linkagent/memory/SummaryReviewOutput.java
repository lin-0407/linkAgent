package com.link.linkagent.memory;

import java.util.List;
import java.util.Objects;

/**
 * 摘要复核的模型输出契约（设计文档 B4）。
 *
 * @param passed     是否通过复核
 * @param missing    摘要漏掉的原文信息（漏抽）
 * @param fabricated 摘要里没有原文依据的内容（编造）
 */
public record SummaryReviewOutput(
        boolean passed,
        List<String> missing,
        List<String> fabricated
) {

    public SummaryReviewOutput {
        // 模型可能省略数组字段或混入 null 元素，统一收敛为纯列表，避免调用方空指针。
        missing = missing == null ? List.of() : missing.stream().filter(Objects::nonNull).toList();
        fabricated = fabricated == null ? List.of() : fabricated.stream().filter(Objects::nonNull).toList();
    }
}
