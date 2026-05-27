package com.link.linkagent.memory;

import com.link.linkagent.util.TextUtil;

/**
 * 长期记忆抽取候选。
 * 只保留最小字段，避免第一版自动记忆引入复杂的置信度和合并策略。
 */
public record LongTermMemoryCandidate(
        boolean shouldRemember,
        String memoryKey,
        String content
) {

    public boolean isValid() {
        return shouldRemember
                && TextUtil.hasText(memoryKey)
                && TextUtil.hasText(content);
    }
}
