package com.link.linkagent.creator.production.model;

import java.util.List;

/** 官方工具资料结构化提取结果；仅允许来自已验证官方资料的内容进入蓝图。 */
public record ToolDocumentationOutput(
        String toolName,
        String toolVersion,
        List<String> capabilities,
        List<String> operations,
        List<String> limitations
) {
}
