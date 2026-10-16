package com.link.linkagent.memory;

import java.util.List;

/**
 * 摘要复核结论。
 * <p>
 * 与模型输出 {@link SummaryReviewOutput} 分开，是因为这里还要承载规则校验的问题
 * （空摘要、超长、格式），两种来源最终都归到 issues 供重生成提示词使用。
 *
 * @param passed 是否通过复核
 * @param issues 需要返工的问题列表（空表示通过）
 */
public record SummaryReviewResult(boolean passed, List<String> issues) {

    public SummaryReviewResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /** 通过：没有需要返工的问题。 */
    public static SummaryReviewResult pass() {
        return new SummaryReviewResult(true, List.of());
    }

    /** 不通过：带上具体问题，供第二次生成时修正。 */
    public static SummaryReviewResult fail(List<String> issues) {
        return new SummaryReviewResult(false, issues);
    }
}
