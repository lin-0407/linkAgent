package com.link.linkagent.creator.production.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.production.model.CreateProductionPlanRequest;
import com.link.linkagent.creator.production.model.ProductionBlueprintOutput;
import com.link.linkagent.creator.production.model.ProductionBlueprintStepOutput;
import com.link.linkagent.creator.production.model.ProductionVideoCategory;
import com.link.linkagent.creator.production.model.ToolResolutionResponse;
import com.link.linkagent.creator.preference.mapper.CreatorPreferenceMapper;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.prompt.service.PromptService;
import org.springframework.stereotype.Service;

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
    private final CreatorSuggestionMapper suggestionMapper;
    private final CreatorPreferenceMapper preferenceMapper;
    private final ObjectMapper objectMapper;

    public ProductionBlueprintGenerator(LLMService llmService,
                                        PromptService promptService,
                                        CreatorTaskMapper taskMapper,
                                        CreatorSuggestionMapper suggestionMapper,
                                        CreatorPreferenceMapper preferenceMapper,
                                        ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.promptService = promptService;
        this.taskMapper = taskMapper;
        this.suggestionMapper = suggestionMapper;
        this.preferenceMapper = preferenceMapper;
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
        context.put("prePublishSuggestion", suggestionMapper.findByTaskId(taskId)
                .map(this::suggestionSnapshot)
                .orElseGet(Map::of));
        context.put("creatorPreferences", preferenceMapper.listByUserId("default", 20).stream()
                .map(record -> record.getPreferenceContent())
                .filter(value -> value != null && !value.isBlank())
                .toList());
        String userMessage = writeJson(context);
        String systemPrompt = promptService.get(promptKey) + """

                【当前工作台流程边界】
                制作蓝图的终点是“真实成片、字幕、封面和发布文案已经制作并完成本地自查，可以上传成片试映”。
                全部蓝图步骤完成后，页面才会开放成片上传和 AI 试映。因此不得把运行本平台试映、
                根据本平台试映报告修改、实际发布、作者发布、BV 绑定、评论采集或发布后复盘列为蓝图步骤，
                否则会产生“先完成试映才能进入试映”的循环前置条件。
                可以安排本地播放自查和整理交付文件；平台试映和实际发布由后续独立页面执行。
                availableAssets 是本轮现有产物，以它为准，不把原始想法里“没有素材”的旧状态当成最新状态。
                """;
        ProductionBlueprintOutput output;
        // 蓝图生成与内部重试都应归属本期任务，避免单任务费用统计漏掉这部分调用。
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.open(taskId, "制作蓝图")) {
            output = llmService.chatStructured(systemPrompt, userMessage, ProductionBlueprintOutput.class);
        }
        validate(output);
        return new GenerationResult(output, userMessage, promptKey);
    }

    private void validate(ProductionBlueprintOutput output) {
        // 不设步数上限：上限会逼模型把真实制作过程压成泛泛的几步，只保证至少有一步
        if (output == null || output.steps() == null || output.steps().isEmpty()) {
            throw new IllegalStateException("AI 制作蓝图至少需要一个步骤");
        }
        for (ProductionBlueprintStepOutput step : output.steps()) {
            if (step == null || blank(step.stepName()) || blank(step.objective()) || blank(step.phase())) {
                throw new IllegalStateException("AI 制作蓝图缺少步骤名称、阶段或目标");
            }
            // 这些动作必须在蓝图门禁之后执行，不能持久化为门禁本身的前置步骤。
            String name = step.stepName().replaceAll("\\s+", "");
            if (List.of("本平台AI观众试映", "平台AI试映", "运行试映", "开始试映", "实际发布",
                    "作者发布", "BV绑定", "绑定BV", "评论采集", "发布后复盘").stream().anyMatch(name::contains)) {
                throw new IllegalStateException("制作蓝图包含后续阶段动作，需改为成片上传前可完成的制作步骤：" + step.stepName());
            }
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private Map<String, String> suggestionSnapshot(CreatorSuggestionRecord suggestion) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("contentSummary", suggestion.getContentSummary());
        snapshot.put("audienceProfile", suggestion.getAudienceProfile());
        snapshot.put("contentPositioning", suggestion.getContentPositioning());
        snapshot.put("actionableRevisionPlan", suggestion.getActionableRevisionPlan());
        snapshot.put("riskPoints", suggestion.getRiskPoints());
        return snapshot;
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
