package com.link.linkagent.knowledge.model;

/**
 * 案例库导入结果。
 * 返回收到条数、实际落库条数与按 BV 去重跳过的条数：receivedCount = importedCount + skippedCount。
 * 重复导入同一批带 BV 的案例时，第二次起 importedCount=0、skippedCount 等于重复数，即可确认幂等生效。
 */
public record ReferenceVideoImportResponse(
        int receivedCount,
        int importedCount,
        int skippedCount,
        String source,
        String tier
) {
}
