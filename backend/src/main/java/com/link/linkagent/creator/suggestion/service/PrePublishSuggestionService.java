package com.link.linkagent.creator.suggestion.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.core.AgentExecutor;
import com.link.linkagent.creator.context.service.CreatorContextService;
import com.link.linkagent.creator.preference.service.CreatorPreferenceService;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionResponse;
import com.link.linkagent.creator.suggestion.model.PrePublishAnalyzeRequest;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorMaterialType;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.model.CreatorTaskStatus;
import com.link.linkagent.dto.AgentChatResponse;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.LlmJsonUtil;
import com.link.linkagent.util.TextUtil;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * 发布前优化服务。
 * 先用单次 LLM 调用完成内容摘要、标题简介和标签建议，后续再演进为可观测 Agent 流程。
 */
@Service
public class PrePublishSuggestionService {

    private static final int MATERIAL_MAX_LENGTH = 12000;
    // 三明治截断参数：保留开头2000字（钩子）+ 结尾2000字（总结），中间抽样8000字
    private static final int SANDWICH_HEAD_CHARS = 2000;
    private static final int SANDWICH_TAIL_CHARS = 2000;
    private static final int SANDWICH_MIDDLE_CHARS = 8000;
    private static final String PREFERENCE_MODE_USE_HISTORY = "USE_HISTORY";
    private static final String PREFERENCE_MODE_IGNORE_HISTORY = "IGNORE_HISTORY";
    private static final String PREFERENCE_MODE_EXPERIMENT = "EXPERIMENT";

    private final CreatorTaskMapper creatorTaskMapper;
    private final CreatorSuggestionMapper creatorSuggestionMapper;
    private final CreatorPreferenceService creatorPreferenceService;
    private final CreatorContextService creatorContextService;
    private final LLMService llmService;
    private final AgentExecutor agentExecutor;
    private final ObjectMapper objectMapper;
    private final PromptService promptService;

    @Autowired
    public PrePublishSuggestionService(CreatorTaskMapper creatorTaskMapper,
                                       CreatorSuggestionMapper creatorSuggestionMapper,
                                       CreatorPreferenceService creatorPreferenceService,
                                       CreatorContextService creatorContextService,
                                       LLMService llmService,
                                       AgentExecutor agentExecutor,
                                       ObjectMapper objectMapper,
                                       PromptService promptService) {
        this.creatorTaskMapper = creatorTaskMapper;
        this.creatorSuggestionMapper = creatorSuggestionMapper;
        this.creatorPreferenceService = creatorPreferenceService;
        this.creatorContextService = creatorContextService;
        this.llmService = llmService;
        this.agentExecutor = agentExecutor;
        this.objectMapper = objectMapper;
        this.promptService = promptService;
    }

    PrePublishSuggestionService(CreatorTaskMapper creatorTaskMapper,
                                CreatorSuggestionMapper creatorSuggestionMapper,
                                CreatorPreferenceService creatorPreferenceService,
                                CreatorContextService creatorContextService,
                                LLMService llmService,
                                ObjectMapper objectMapper,
                                PromptService promptService) {
        this(creatorTaskMapper, creatorSuggestionMapper, creatorPreferenceService, creatorContextService,
                llmService, null, objectMapper, promptService);
    }

    @Transactional
    public CreatorSuggestionResponse analyze(String taskId, PrePublishAnalyzeRequest request) {
        CreatorSuggestionResponse response = generateSuggestion(taskId, request);
        creatorTaskMapper.updateTaskStatus(response.taskId(), CreatorTaskStatus.PRE_PUBLISH_ANALYZED.name());
        return response;
    }

    /**
     * 只生成并保存建议，不推进任务状态。
     * 工作流模式需要先让用户确认建议，确认后才能进入下一阶段。
     */
    @Transactional
    public CreatorSuggestionResponse generateSuggestion(String taskId, PrePublishAnalyzeRequest request) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        List<CreatorMaterialRecord> materials = creatorTaskMapper.listMaterialsByTaskId(taskRecord.getTaskId());
        if (materials.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "创作任务缺少可分析材料");
        }

        PrePublishAnalyzeRequest safeRequest = normalizeRequest(request);
        String rawOutput;
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.open(taskRecord.getTaskId(), "发布前优化")) {
            rawOutput = llmService.chat(buildSystemPrompt(), buildUserPrompt(taskRecord, materials, safeRequest));
        }
        CreatorSuggestionRecord suggestionRecord = buildSuggestionRecord(taskRecord.getTaskId(), rawOutput);
        creatorSuggestionMapper.upsert(suggestionRecord);
        return getSuggestion(taskRecord.getTaskId());
    }

    @Transactional
    public CreatorSuggestionResponse generateSuggestionByAgent(String taskId, PrePublishAnalyzeRequest request) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        List<CreatorMaterialRecord> materials = creatorTaskMapper.listMaterialsByTaskId(taskRecord.getTaskId());
        if (materials.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "创作任务缺少可分析材料");
        }

        PrePublishAnalyzeRequest safeRequest = normalizeRequest(request);
        if (agentExecutor == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "发布前优化 Agent 未初始化");
        }
        AgentChatResponse agentResponse = agentExecutor.runTask(buildAgentTaskPrompt(taskRecord, materials, safeRequest));
        if (TextUtil.hasText(agentResponse.stopReason()) && !TextUtil.hasText(agentResponse.finalAnswer())) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "发布前优化 Agent 未能生成最终答案：" + agentResponse.stopReason());
        }
        String rawOutput = TextUtil.trimToNull(agentResponse.finalAnswer());
        if (rawOutput == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "发布前优化 Agent 返回为空");
        }

        CreatorSuggestionRecord suggestionRecord = buildSuggestionRecord(taskRecord.getTaskId(), rawOutput);
        if (!"PARSED".equals(suggestionRecord.getParseStatus())) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "发布前优化 Agent 输出无法解析为结构化 JSON");
        }
        creatorSuggestionMapper.upsert(suggestionRecord);
        return getSuggestion(taskRecord.getTaskId());
    }

    public CreatorSuggestionResponse getSuggestion(String taskId) {
        getTaskRecord(taskId);
        CreatorSuggestionRecord record = creatorSuggestionMapper.findByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "发布前优化建议不存在"));
        return toResponse(record);
    }

    private CreatorTaskRecord getTaskRecord(String taskId) {
        return creatorTaskMapper.findTaskByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在"));
    }

    private PrePublishAnalyzeRequest normalizeRequest(PrePublishAnalyzeRequest request) {
        if (request != null) {
            return request;
        }
        return new PrePublishAnalyzeRequest(null, null, null, null, null);
    }

    private CreatorSuggestionRecord buildSuggestionRecord(String taskId, String rawOutput) {
        CreatorSuggestionRecord record = new CreatorSuggestionRecord();
        record.setSuggestionId(UUID.randomUUID().toString());
        record.setTaskId(taskId);
        record.setRawOutput(rawOutput);
        fillParsedFields(record, rawOutput);
        return record;
    }

    private void fillParsedFields(CreatorSuggestionRecord record, String rawOutput) {
        try {
            JsonNode rootNode = objectMapper.readTree(LlmJsonUtil.extractJsonObject(rawOutput));
            record.setContentSummary(LlmJsonUtil.text(rootNode, "contentSummary"));
            record.setCreatorDilemma(LlmJsonUtil.text(rootNode, "creatorDilemma"));
            record.setAudienceProfile(LlmJsonUtil.text(rootNode, "audienceProfile"));
            record.setAudienceHook(LlmJsonUtil.text(rootNode, "audienceHook"));
            record.setContentPositioning(LlmJsonUtil.text(rootNode, "contentPositioning"));
            record.setSellingPoints(LlmJsonUtil.json(objectMapper, rootNode, "sellingPoints"));
            record.setRiskPoints(LlmJsonUtil.json(objectMapper, rootNode, "riskPoints"));
            record.setTitleSuggestions(LlmJsonUtil.json(objectMapper, rootNode, "titleSuggestions"));
            record.setDescriptionSuggestion(LlmJsonUtil.text(rootNode, "descriptionSuggestion"));
            record.setActionableRevisionPlan(LlmJsonUtil.json(objectMapper, rootNode, "actionableRevisionPlan"));
            record.setTagSuggestions(LlmJsonUtil.json(objectMapper, rootNode, "tagSuggestions"));
            record.setPartitionSuggestion(LlmJsonUtil.text(rootNode, "partitionSuggestion"));
            record.setParseStatus("PARSED");
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            record.setParseStatus("RAW_ONLY");
        }
    }

    private String buildSystemPrompt() {
        return promptService.get("pre_publish.system");
    }

    private String buildUserPrompt(CreatorTaskRecord taskRecord,
                                   List<CreatorMaterialRecord> materials,
                                   PrePublishAnalyzeRequest request) {
        return promptService.render("pre_publish.user", Map.of(
                "taskName", taskRecord.getTaskName(),
                "taskId", taskRecord.getTaskId(),
                "customGuidance", TextUtil.trimToDefault(request.customGuidance(), "未提供"),
                "preferenceMode", preferenceModeLabel(request.preferenceMode()),
                "preferenceContext", buildPreferencePromptContext(taskRecord, request),
                "creatorPreference", TextUtil.trimToDefault(request.creatorPreference(), "未提供"),
                "titleStyle", TextUtil.trimToDefault(request.titleStyle(), "未提供"),
                "extraRequirement", TextUtil.trimToDefault(request.extraRequirement(), "未提供"),
                "materials", buildMaterialPrompt(materials)
        ));
    }

    private String buildAgentTaskPrompt(CreatorTaskRecord taskRecord,
                                        List<CreatorMaterialRecord> materials,
                                        PrePublishAnalyzeRequest request) {
        return """
                你正在帮助 B 站 UP 主做发布前优化。

                任务信息：
                - taskId：%s
                - taskName：%s
                - videoType：%s
                - 用户补充创作指导：%s
                - 偏好使用方式：%s
                - 用户手动补充偏好：%s
                - 标题风格：%s
                - 额外要求：%s

                创作者历史偏好和当前视频类型语境：
                %s

                用户主动提供的任务材料：
                %s

                你的目标：
                1. 判断当前内容的核心卖点。
                2. 如果系统工具列表中存在 knowledge_search，且当前有明确视频类型、内容主题或标题方向，优先调用它检索同类型案例。
                3. 如果 knowledge_search 不在工具列表中、知识库为空或检索失败，不要报错，按无案例降级生成。
                4. 参考案例时必须说明借鉴点，而不是照搬标题或话术。
                5. 输出标题建议、简介建议、标签建议、风险点、修改计划。
                6. 最终回答必须是一个 JSON 对象，不要使用 Markdown 代码块，不要输出 JSON 之外的解释。
                7. 如果当前 Agent 内核要求通过 finalAnswer 字段结束，请把完整 JSON 对象作为 finalAnswer 的字符串内容，不要把 finalAnswer 写成嵌套对象。

                JSON 字段固定如下：
                {
                  "contentSummary": "100字以内的内容摘要",
                  "creatorDilemma": "本期创作者最可能纠结或最容易做错的表达问题，必须具体到当前材料",
                  "audienceProfile": "目标观众判断",
                  "audienceHook": "观众为什么愿意点进来、继续看或收藏评论的核心钩子",
                  "contentPositioning": "本期内容在同类 B 站内容中的表达定位和差异化方向，不得编造具体竞品数据",
                  "sellingPoints": ["核心卖点1", "核心卖点2", "核心卖点3"],
                  "riskPoints": ["可能的表达风险或内容短板"],
                  "titleSuggestions": [
                    {"title": "标题1", "viewerPsychology": "对应的观众心理", "clickReason": "为什么会点", "trustRisk": "可能损伤信任的点", "bestScenario": "最适合的使用场景", "reason": "推荐理由", "risk": "风险提醒"}
                  ],
                  "descriptionSuggestion": "简介建议",
                  "actionableRevisionPlan": [
                    {"priority": "HIGH/MEDIUM/LOW", "target": "标题/开头/简介/标签/结构", "problem": "当前具体问题", "action": "可以直接执行的修改动作", "expectedEffect": "这个动作解决的观众或创作者问题"}
                  ],
                  "tagSuggestions": ["标签1", "标签2", "标签3", "标签4", "标签5"],
                  "partitionSuggestion": "建议分区"
                }
                """.formatted(
                taskRecord.getTaskId(),
                taskRecord.getTaskName(),
                TextUtil.trimToDefault(taskRecord.getVideoType(), "未分类"),
                TextUtil.trimToDefault(request.customGuidance(), "未提供"),
                preferenceModeLabel(request.preferenceMode()),
                TextUtil.trimToDefault(request.creatorPreference(), "未提供"),
                TextUtil.trimToDefault(request.titleStyle(), "未提供"),
                TextUtil.trimToDefault(request.extraRequirement(), "未提供"),
                buildPreferencePromptContext(taskRecord, request),
                buildMaterialPrompt(materials)
        );
    }

    private String buildPreferencePromptContext(CreatorTaskRecord taskRecord, PrePublishAnalyzeRequest request) {
        String preferenceMode = normalizePreferenceMode(request.preferenceMode());
        if (PREFERENCE_MODE_IGNORE_HISTORY.equals(preferenceMode)) {
            return "本次选择不使用历史创作者偏好和视频类型语境库，请只参考本期用户输入和任务材料。";
        }

        String promptContext = creatorPreferenceService.buildPromptContext(taskRecord.getUserId());
        String typeContext = creatorContextService.buildPromptContext(
                taskRecord.getUserId(),
                taskRecord.getVideoType(),
                "PRE_PUBLISH"
        );
        String mergedContext = promptContext + "\n\n当前视频类型语境库：\n" + typeContext;
        if (PREFERENCE_MODE_EXPERIMENT.equals(preferenceMode)) {
            return mergedContext + "\n本次选择试验新方向：历史偏好和语境库只用于避开明显不适配点，本期用户手动要求优先。";
        }
        return mergedContext;
    }

    private String normalizePreferenceMode(String preferenceMode) {
        if (TextUtil.isBlank(preferenceMode)) {
            return PREFERENCE_MODE_USE_HISTORY;
        }
        return preferenceMode.trim();
    }

    private String preferenceModeLabel(String preferenceMode) {
        String normalized = normalizePreferenceMode(preferenceMode);
        if (PREFERENCE_MODE_IGNORE_HISTORY.equals(normalized)) {
            return "本期换风格，不使用历史偏好";
        }
        if (PREFERENCE_MODE_EXPERIMENT.equals(normalized)) {
            return "试验新方向，历史偏好仅作避坑参考";
        }
        return "沿用历史偏好";
    }

    private String buildMaterialPrompt(List<CreatorMaterialRecord> materials) {
        StringBuilder builder = new StringBuilder();
        for (CreatorMaterialRecord material : materials) {
            builder.append("\n【")
                    .append(toChineseMaterialName(material.getMaterialType()))
                    .append("】\n");

            // 文稿和字幕通常很长，用三明治截断保留开头钩子和结尾总结，避免 AI 丢失关键语境
            String materialType = material.getMaterialType();
            if (CreatorMaterialType.MANUSCRIPT.name().equals(materialType)
                    || CreatorMaterialType.SUBTITLE.name().equals(materialType)) {
                builder.append(TextUtil.abbreviateSandwich(
                        material.getContent(),
                        SANDWICH_HEAD_CHARS,
                        SANDWICH_TAIL_CHARS,
                        SANDWICH_MIDDLE_CHARS,
                        "中间段落已抽样保留关键信息"
                ));
            } else {
                // 标题草稿和简介草稿通常较短，直接用简单截断
                builder.append(TextUtil.abbreviateWithSuffix(
                        material.getContent(),
                        MATERIAL_MAX_LENGTH,
                        "\n[内容过长，已截断用于本次分析]"
                ));
            }
            builder.append("\n");
        }
        return builder.toString();
    }

    private String toChineseMaterialName(String materialType) {
        if (CreatorMaterialType.TITLE_DRAFT.name().equals(materialType)) {
            return "标题草稿";
        }
        if (CreatorMaterialType.DESCRIPTION_DRAFT.name().equals(materialType)) {
            return "简介草稿";
        }
        if (CreatorMaterialType.MANUSCRIPT.name().equals(materialType)) {
            return "文稿";
        }
        if (CreatorMaterialType.SUBTITLE.name().equals(materialType)) {
            return "字幕";
        }
        return materialType;
    }

    private CreatorSuggestionResponse toResponse(CreatorSuggestionRecord record) {
        return new CreatorSuggestionResponse(
                record.getId(),
                record.getSuggestionId(),
                record.getTaskId(),
                record.getContentSummary(),
                record.getCreatorDilemma(),
                record.getAudienceProfile(),
                record.getAudienceHook(),
                record.getContentPositioning(),
                record.getSellingPoints(),
                record.getRiskPoints(),
                record.getTitleSuggestions(),
                record.getDescriptionSuggestion(),
                record.getActionableRevisionPlan(),
                record.getTagSuggestions(),
                record.getPartitionSuggestion(),
                record.getRawOutput(),
                record.getParseStatus(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
