package com.link.linkagent.creator.production.model;

import java.time.LocalDateTime;

/** creator_tool_catalog 的持久化记录。 */
public record ToolCatalogRecord(
        Long id,
        String toolId,
        String toolName,
        String normalizedName,
        String officialDomain,
        String officialUrl,
        String capabilityTypes,
        String supportedCategories,
        String pricingType,
        String regionNote,
        Integer defaultRank,
        Boolean enabled,
        LocalDateTime sourceUpdatedAt,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
