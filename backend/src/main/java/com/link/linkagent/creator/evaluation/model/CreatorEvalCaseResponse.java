package com.link.linkagent.creator.evaluation.model;

import java.time.LocalDateTime;

/**
 * 评测用例返回对象。
 * 返回完整输入快照是为了让作者能直接把这组样例拿去做回放，不需要再去找原始文本。
 */
public record CreatorEvalCaseResponse(
        Long id,
        String caseId,
        String userId,
        String caseName,
        String targetStage,
        String taskId,
        String inputSnapshot,
        String expectedPoints,
        String scoringRubric,
        String status,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
