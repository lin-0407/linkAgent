package com.link.linkagent.creator.production.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 用户临时指定的工具；官方链接可选，缺少可信来源时会降级为 SOURCE_REQUIRED。 */
public record PreferredToolRequest(
        @NotBlank
        @Size(max = 128)
        String name,
        @Size(max = 64)
        String version,
        @Size(max = 1000)
        String officialUrl
) {
}
