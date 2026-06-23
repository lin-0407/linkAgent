package com.link.linkagent.core.citation;

import com.link.linkagent.util.TextUtil;

import java.util.List;

/**
 * 带证据引用的结构化最终回答。
 * <p>
 * 先让模型输出结构化对象，再渲染成 Markdown，是为了让审查器能逐句检查引用是否存在。
 */
public record CitedAnswer(
        List<CitedStatement> statements,
        List<String> limitations
) {

    public CitedAnswer {
        statements = statements == null ? List.of() : statements.stream()
                .filter(statement -> statement != null && TextUtil.hasText(statement.text()))
                .toList();
        limitations = limitations == null ? List.of() : limitations.stream()
                .filter(TextUtil::hasText)
                .map(String::trim)
                .toList();
    }

    public static CitedAnswer empty(String limitation) {
        return new CitedAnswer(List.of(), List.of(TextUtil.trimToDefault(limitation, "没有找到足够依据。")));
    }
}
