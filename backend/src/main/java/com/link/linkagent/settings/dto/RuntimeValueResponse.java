package com.link.linkagent.settings.dto;

import java.util.List;

/**
 * 可动态修改的运行期枚举值展示项。
 */
public record RuntimeValueResponse(
        String key,
        String name,
        String value,
        List<String> options,
        String description
) {
}
