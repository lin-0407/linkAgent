package com.link.linkagent.creator.suggestion.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.LlmJsonUtil;
import com.link.linkagent.util.TextUtil;
import java.util.Map;
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
    private static final String PREFERENCE_MODE_USE_HISTORY = "USE_HISTORY";
    private static final String PREFERENCE_MODE_IGNORE_HISTORY = "IGNORE_HISTORY";
    private static final String PREFERENCE_MODE_EXPERIMENT = "EXPERIMENT";

    private final CreatorTaskMapper creatorTaskMapper;
    private final CreatorSuggestionMapper creatorSuggestionMapper;
    private final CreatorPreferenceService creatorPreferenceService;
    private final CreatorContextService creatorContextService;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;
    private final PromptService promptService;

    public PrePublishSuggestionService(CreatorTaskMapper creatorTaskMapper,
                                       CreatorSuggestionMapper creatorSuggestionMapper,
                                       CreatorPreferenceService creatorPreferenceService,
                                       CreatorContextService creatorContextService,
                                       LLMService llmService,
                                       ObjectMapper objectMapper,
                                       PromptService promptService) {
        this.creatorTaskMapper = creatorTaskMapper;
        this.creatorSuggestionMapper = creatorSuggestionMapper;
        this.creatorPreferenceService = creatorPreferenceService;
        this.creatorContextService = creatorContextService;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.promptService = promptService;
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
                    .append("】\n")
                    .append(TextUtil.abbreviateWithSuffix(
                            material.getContent(),
                            MATERIAL_MAX_LENGTH,
                            "\n[内容过长，已截断用于本次分析]"
                    ))
                    .append("\n");
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
