package com.link.linkagent.core.multi;

import com.link.linkagent.util.TextUtil;

import java.util.List;

/**
 * Worker 给 Synthesizer 的摘要层。
 * <p>
 * 摘要层只保留核心结论和证据索引，完整步骤仍留在 trace 中，避免最终合成阶段上下文膨胀。
 */
public record WorkerBrief(
        String coreConclusion,
        List<String> keyPoints,
        Double confidence,
        List<String> evidenceIds,
        List<String> unresolvedQuestions
) {

    public WorkerBrief {
        coreConclusion = TextUtil.preview(coreConclusion, 600, "未形成明确结论");
        keyPoints = keyPoints == null ? List.of() : keyPoints.stream()
                .filter(TextUtil::hasText)
                .map(point -> TextUtil.preview(point, 180, ""))
                .toList();
        confidence = confidence == null ? 0.5D : Math.max(0D, Math.min(1D, confidence));
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        unresolvedQuestions = unresolvedQuestions == null ? List.of() : unresolvedQuestions.stream()
                .filter(TextUtil::hasText)
                .map(String::trim)
                .toList();
    }

    public static WorkerBrief fromSummary(String summary, List<String> evidenceIds, WorkerStatus status) {
        double confidence = status == WorkerStatus.SUCCESS ? 0.65D : 0.2D;
        return new WorkerBrief(
                TextUtil.trimToDefault(summary, "Worker 未返回有效摘要"),
                List.of(),
                confidence,
                evidenceIds,
                status == WorkerStatus.SUCCESS ? List.of() : List.of("Worker 未成功完成，结论可信度较低")
        );
    }
}
