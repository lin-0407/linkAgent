package com.link.linkagent.creator.production.model;

import java.time.LocalDateTime;

/** creator_tool_knowledge 的持久化记录。 */
public record ToolKnowledgeRecord(
        Long id,
        String knowledgeId,
        String toolId,
        String toolName,
        String toolVersion,
        String officialDomain,
        String sourceUrls,
        String sourceHash,
        String capabilitySnapshot,
        String operationSnapshot,
        String verificationStatus,
        LocalDateTime verifiedAt,
        LocalDateTime expiresAt,
        String rawSummary,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
