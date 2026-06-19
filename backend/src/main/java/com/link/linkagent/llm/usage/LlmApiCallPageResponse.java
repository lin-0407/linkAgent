package com.link.linkagent.llm.usage;

import java.util.List;

/**
 * 模型调用明细分页响应。
 * 当前项目不引入分页框架，直接返回 limit/offset 和总数，前端就能实现简单翻页。
 */
public record LlmApiCallPageResponse(
        String taskId,
        int page,
        int pageSize,
        long total,
        List<LlmApiCallRecord> items
) {
}
