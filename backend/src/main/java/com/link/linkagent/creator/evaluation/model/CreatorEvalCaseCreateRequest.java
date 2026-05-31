package com.link.linkagent.creator.evaluation.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 评测用例创建请求。
 * 这里保存的是可回放的样例输入，而不是自动化测试脚本，目的是让创作者能复用真实工作流材料做人工评测。
 */
public record CreatorEvalCaseCreateRequest(
        @Size(max = 64, message = "用户ID长度不能超过64个字符")
        String userId,

        @NotBlank(message = "评测用例名称不能为空")
        @Size(max = 128, message = "评测用例名称长度不能超过128个字符")
        String caseName,

        @NotBlank(message = "评测阶段不能为空")
        @Pattern(regexp = "PRE_PUBLISH|FEEDBACK|REPORT", message = "评测阶段只能是 PRE_PUBLISH、FEEDBACK 或 REPORT")
        String targetStage,

        @Size(max = 64, message = "任务ID长度不能超过64个字符")
        String taskId,

        @NotBlank(message = "评测输入快照不能为空")
        @Size(max = 20000, message = "评测输入快照长度不能超过20000个字符")
        String inputSnapshot,

        @Size(max = 4000, message = "期望要点长度不能超过4000个字符")
        String expectedPoints,

        @Size(max = 4000, message = "评分说明长度不能超过4000个字符")
        String scoringRubric
) {
}
