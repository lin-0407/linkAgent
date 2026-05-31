package com.link.linkagent.creator.evaluation.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 评测结果创建请求。
 * 评测阶段保留模型输出、失败原因、耗时和人工评分，是为了后续能做失败回放和样例复盘，而不是只看最终结论。
 */
public record CreatorEvalResultCreateRequest(
        @Size(max = 64, message = "任务ID长度不能超过64个字符")
        String taskId,

        @Size(max = 64, message = "工作流会话ID长度不能超过64个字符")
        String workflowSessionId,

        @NotBlank(message = "评测阶段不能为空")
        @Pattern(regexp = "PRE_PUBLISH|FEEDBACK|REPORT", message = "评测阶段只能是 PRE_PUBLISH、FEEDBACK 或 REPORT")
        String targetStage,

        @Size(max = 128, message = "模型名称长度不能超过128个字符")
        String modelName,

        @Size(max = 4000, message = "输出摘要长度不能超过4000个字符")
        String outputSummary,

        @Size(max = 20000, message = "原始输出长度不能超过20000个字符")
        String rawOutput,

        @PositiveOrZero(message = "耗时毫秒不能小于0")
        Long elapsedMs,

        @PositiveOrZero(message = "提示词 token 不能小于0")
        Integer promptTokens,

        @PositiveOrZero(message = "输出 token 不能小于0")
        Integer completionTokens,

        @PositiveOrZero(message = "总 token 不能小于0")
        Integer totalTokens,

        @Size(max = 500, message = "失败原因长度不能超过500个字符")
        String failureReason,

        @Min(value = 1, message = "可读性评分不能小于1")
        @Max(value = 5, message = "可读性评分不能大于5")
        Integer readabilityScore,

        @Min(value = 1, message = "贴合度评分不能小于1")
        @Max(value = 5, message = "贴合度评分不能大于5")
        Integer relevanceScore,

        @Min(value = 1, message = "完整性评分不能小于1")
        @Max(value = 5, message = "完整性评分不能大于5")
        Integer completenessScore,

        @Min(value = 1, message = "准确性评分不能小于1")
        @Max(value = 5, message = "准确性评分不能大于5")
        Integer accuracyScore,

        @Min(value = 1, message = "稳定性评分不能小于1")
        @Max(value = 5, message = "稳定性评分不能大于5")
        Integer stabilityScore,

        @Min(value = 1, message = "成本评分不能小于1")
        @Max(value = 5, message = "成本评分不能大于5")
        Integer costScore,

        @Min(value = 1, message = "可解释性评分不能小于1")
        @Max(value = 5, message = "可解释性评分不能大于5")
        Integer explainabilityScore,

        @Size(max = 1000, message = "评测备注长度不能超过1000个字符")
        String reviewerNote
) {
}
