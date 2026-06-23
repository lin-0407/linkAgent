package com.link.linkagent.core.citation;

import com.link.linkagent.util.TextUtil;

import java.util.List;

/**
 * 最终回答中的一个可引用陈述。
 */
public record CitedStatement(
        String text,
        List<String> evidenceIds
) {

    public CitedStatement {
        text = TextUtil.trimToDefault(text, "");
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
