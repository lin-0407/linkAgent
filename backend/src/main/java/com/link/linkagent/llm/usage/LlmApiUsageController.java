package com.link.linkagent.llm.usage;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型 API 开销统计接口。
 * 这组接口只读统计数据，不触发任何模型调用，避免“看开销”本身又产生新的开销。
 */
@Validated
@RestController
@RequestMapping("/api/llm-usage/tasks")
public class LlmApiUsageController {

    private final LlmApiUsageService llmApiUsageService;

    public LlmApiUsageController(LlmApiUsageService llmApiUsageService) {
        this.llmApiUsageService = llmApiUsageService;
    }

    @GetMapping("/{taskId}/summary")
    public LlmApiUsageSummaryResponse summarizeTask(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId) {
        return llmApiUsageService.summarizeTask(taskId.trim());
    }

    @GetMapping("/{taskId}/calls")
    public LlmApiCallPageResponse listTaskCalls(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @RequestParam(required = false)
            @Pattern(regexp = "TEXT|EMBEDDING|RERANK", message = "模型分类只能是 TEXT、EMBEDDING 或 RERANK")
            String modelCategory,

            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码不能小于1")
            Integer page,

            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页数量不能小于1")
            @Max(value = 100, message = "每页数量不能超过100")
            Integer pageSize) {
        return llmApiUsageService.listTaskCalls(taskId.trim(), modelCategory, page, pageSize);
    }
}
