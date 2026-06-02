package com.link.linkagent.creator.feedback.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 重建当前任务反馈证据索引请求。
 * <p>
 * 两个字段都允许为空：为空时分别回落到 {@code creator.feedback.rag.max-index-items} 和
 * {@code include-noise-default} 配置默认值。不允许一次索引超过 1000 条，是为了防止用户导入大文件后
 * 误触发高成本 Embedding。
 */
public record CreatorFeedbackEvidenceIndexRequest(
        @Min(value = 1, message = "单次索引条数至少为1")
        @Max(value = 1000, message = "单次索引条数不能超过1000")
        Integer maxItems,

        Boolean includeNoise
) {
}
