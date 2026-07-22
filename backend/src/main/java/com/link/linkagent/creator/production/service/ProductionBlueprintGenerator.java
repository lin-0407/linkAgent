package com.link.linkagent.creator.production.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.production.model.CreateProductionPlanRequest;
import com.link.linkagent.creator.production.model.ProductionBlueprintOutput;
import com.link.linkagent.creator.production.model.ProductionBlueprintStepOutput;
import com.link.linkagent.creator.production.model.ProductionVideoCategory;
import com.link.linkagent.creator.production.model.ToolResolutionResponse;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 制作蓝图模型编排器。蓝图输出使用结构化 schema，工具来源状态会原样传入模型，
 * 这样未核验工具只能得到通用动作，不能被模型扩写成不存在的菜单路径。
 */
@Service
public class ProductionBlueprintGenerator {

    private final LLMService llmService;
    private final PromptService promptService;
    private final CreatorTaskMapper taskMapper;
    private final ObjectMapper objectMapper;

    public ProductionBlueprintGenerator(LLMService llmService,
                                        PromptService promptService,
                                        CreatorTaskMapper taskMapper,
                                        ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.promptService = promptService;
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
    }

    public GenerationResult generate(String taskId,
                                    CreateProductionPlanRequest request,
                                    List<ToolResolutionResponse> tools) {
        String promptKey = request.videoCategory() == ProductionVideoCategory.PROJECT_DEMO
                ? "production_blueprint_project_demo_v1"
                : "production_blueprint_ai_video_v1";
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("taskId", taskId);
        context.put("videoCategory", request.videoCategory().name());
        context.put("productionMethod", request.productionMethod().name());
        context.put("targetAudience", request.targetAudience().trim());
        context.put("corePromise", request.corePromise().trim());
        context.put("targetDurationSeconds", request.targetDurationSeconds());
        context.put("availableAssets", request.availableAssets() == null ? List.of() : request.availableAssets());
        context.put("constraints", request.constraints() == null ? "" : request.constraints().trim());
        context.put("toolResolution", tools);
        context.put("taskMaterials", taskMapper.listMaterialsByTaskId(taskId).stream()
                .map(CreatorMaterialRecord::getContent)
                .filter(value -> value != null && !value.isBlank())
                .limit(8)
                .toList());
        String userMessage = writeJson(context);
        ProductionBlueprintOutput output = llmService.chatStructured(
                promptService.get(promptKey), userMessage, ProductionBlueprintOutput.class);
        validate(output);
        return new GenerationResult(output, userMessage, promptKey);
    }

    private void validate(ProductionBlueprintOutput output) {
        if (output == null || output.steps() == null || output.steps().isEmpty() || output.steps().size() > 12) {
            throw new IllegalStateException("AI 制作蓝图步骤数量必须在1到12步之间");
        }
        for (ProductionBlueprintStepOutput step : output.steps()) {
            if (step == null || blank(step.stepName()) || blank(step.objective()) || blank(step.phase())) {
                throw new IllegalStateException("AI 制作蓝图缺少步骤名称、阶段或目标");
            }
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("制作蓝图上下文序列化失败", exception);
        }
    }

    public record GenerationResult(ProductionBlueprintOutput output,
                                   String sourceSnapshot,
                                   String promptKey) {
    }
}
