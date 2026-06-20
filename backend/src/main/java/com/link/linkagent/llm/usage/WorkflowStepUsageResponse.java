package com.link.linkagent.llm.usage;

import java.util.List;

/**
 * 工作流步骤下的模型 API 调用明细。
 * 前端过程弹窗按步骤展示开销，后端先完成分组，避免页面用时间或字符串猜测归属。
 */
public record WorkflowStepUsageResponse(
        String stepId,
        String stepName,
        String stage,
        List<LlmApiCallRecord> calls
) {
}
