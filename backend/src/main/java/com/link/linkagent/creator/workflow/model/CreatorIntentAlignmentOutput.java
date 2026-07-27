package com.link.linkagent.creator.workflow.model;

import java.util.List;

/**
 * 主 Agent 的结构化输出。
 * 理解和疑问分开保存，是为了在后端硬性限制最多三个问题，而不是只依赖提示词自觉。
 */
public record CreatorIntentAlignmentOutput(
        String understanding,
        List<String> questions
) {
}
