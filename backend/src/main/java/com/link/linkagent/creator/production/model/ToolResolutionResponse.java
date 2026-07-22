package com.link.linkagent.creator.production.model;

import java.util.List;

/** 返回给前端的工具解析结果，明确展示来源状态而不是伪造菜单建议。 */
public record ToolResolutionResponse(
        String toolId,
        String toolName,
        String version,
        String officialUrl,
        String verificationStatus,
        List<String> sourceUrls,
        List<String> capabilities,
        List<String> operations,
        String reason
) {
}
