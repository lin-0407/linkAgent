package com.link.linkagent.memory;

import com.link.linkagent.util.TextUtil;

/**
 * 长期记忆抽取候选。
 * 保留了写入长期记忆所需的最小字段：一条记忆键与一条内容，外加写入时由模型给出的重要性分数。
 */
public record LongTermMemoryCandidate(
        boolean shouldRemember,
        String memoryKey,
        String content,
        Integer importance
) {

    /** 不带分数的构造：单测合成候选用，落库时按 0（未打分）处理。 */
    public LongTermMemoryCandidate(boolean shouldRemember, String memoryKey, String content) {
        this(shouldRemember, memoryKey, content, null);
    }

    public boolean isValid() {
        return shouldRemember
                && TextUtil.hasText(memoryKey)
                && TextUtil.hasText(content);
    }
}
