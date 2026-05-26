package com.link.linkagent.creator.suggestion.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final CreatorTaskMapper creatorTaskMapper;
    private final CreatorSuggestionMapper creatorSuggestionMapper;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;

    public PrePublishSuggestionService(CreatorTaskMapper creatorTaskMapper,
                                       CreatorSuggestionMapper creatorSuggestionMapper,
                                       LLMService llmService,
                                       ObjectMapper objectMapper) {
        this.creatorTaskMapper = creatorTaskMapper;
        this.creatorSuggestionMapper = creatorSuggestionMapper;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CreatorSuggestionResponse analyze(String taskId, PrePublishAnalyzeRequest request) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        List<CreatorMaterialRecord> materials = creatorTaskMapper.listMaterialsByTaskId(taskRecord.getTaskId());
        if (materials.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "创作任务缺少可分析材料");
        }

        String rawOutput = llmService.chat(buildSystemPrompt(), buildUserPrompt(taskRecord, materials, request));
        CreatorSuggestionRecord suggestionRecord = buildSuggestionRecord(taskRecord.getTaskId(), rawOutput);
        creatorSuggestionMapper.upsert(suggestionRecord);
        creatorTaskMapper.updateTaskStatus(taskRecord.getTaskId(), CreatorTaskStatus.PRE_PUBLISH_ANALYZED.name());
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
            JsonNode rootNode = objectMapper.readTree(extractJson(rawOutput));
            record.setContentSummary(text(rootNode, "contentSummary"));
            record.setAudienceProfile(text(rootNode, "audienceProfile"));
            record.setSellingPoints(json(rootNode, "sellingPoints"));
            record.setRiskPoints(json(rootNode, "riskPoints"));
            record.setTitleSuggestions(json(rootNode, "titleSuggestions"));
            record.setDescriptionSuggestion(text(rootNode, "descriptionSuggestion"));
            record.setTagSuggestions(json(rootNode, "tagSuggestions"));
            record.setPartitionSuggestion(text(rootNode, "partitionSuggestion"));
            record.setParseStatus("PARSED");
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            record.setParseStatus("RAW_ONLY");
        }
    }

    private String extractJson(String rawOutput) {
        String normalized = rawOutput.trim();
        int startIndex = normalized.indexOf('{');
        int endIndex = normalized.lastIndexOf('}');
        if (startIndex < 0 || endIndex <= startIndex) {
            throw new IllegalArgumentException("LLM 输出中没有 JSON 对象");
        }
        return normalized.substring(startIndex, endIndex + 1);
    }

    private String text(JsonNode rootNode, String fieldName) {
        JsonNode valueNode = rootNode.get(fieldName);
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }
        if (valueNode.isTextual()) {
            return valueNode.asText();
        }
        return valueNode.toString();
    }

    private String json(JsonNode rootNode, String fieldName) throws JsonProcessingException {
        JsonNode valueNode = rootNode.get(fieldName);
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }
        return objectMapper.writeValueAsString(valueNode);
    }

    private String buildSystemPrompt() {
        return """
                你是 LinkAgent Creator Copilot 的发布前优化 Agent，服务对象是 B 站内容创作者。
                你的任务是基于用户主动提供的标题草稿、简介草稿、文稿或字幕，生成发布前优化建议。
                你不能声称自己知道 B 站内部推荐算法，也不能编造真实平台数据。
                输出必须是一个 JSON 对象，不要使用 Markdown 代码块，不要输出 JSON 之外的解释。
                JSON 字段固定如下：
                {
                  "contentSummary": "100字以内的内容摘要",
                  "audienceProfile": "目标观众判断",
                  "sellingPoints": ["核心卖点1", "核心卖点2", "核心卖点3"],
                  "riskPoints": ["可能的表达风险或内容短板"],
                  "titleSuggestions": [
                    {"title": "标题1", "reason": "推荐理由", "risk": "风险提醒"},
                    {"title": "标题2", "reason": "推荐理由", "risk": "风险提醒"},
                    {"title": "标题3", "reason": "推荐理由", "risk": "风险提醒"}
                  ],
                  "descriptionSuggestion": "简介建议",
                  "tagSuggestions": ["标签1", "标签2", "标签3", "标签4", "标签5"],
                  "partitionSuggestion": "建议分区"
                }
                """;
    }

    private String buildUserPrompt(CreatorTaskRecord taskRecord,
                                   List<CreatorMaterialRecord> materials,
                                   PrePublishAnalyzeRequest request) {
        return """
                请为下面这个 B 站创作任务生成发布前优化建议。

                任务名称：%s
                任务ID：%s

                创作者偏好：%s
                标题风格：%s
                额外要求：%s

                用户主动提供的创作材料：
                %s
                """.formatted(
                taskRecord.getTaskName(),
                taskRecord.getTaskId(),
                normalizeOptional(request.creatorPreference(), "未提供"),
                normalizeOptional(request.titleStyle(), "未提供"),
                normalizeOptional(request.extraRequirement(), "未提供"),
                buildMaterialPrompt(materials)
        );
    }

    private String buildMaterialPrompt(List<CreatorMaterialRecord> materials) {
        StringBuilder builder = new StringBuilder();
        for (CreatorMaterialRecord material : materials) {
            builder.append("\n【")
                    .append(toChineseMaterialName(material.getMaterialType()))
                    .append("】\n")
                    .append(abbreviate(material.getContent(), MATERIAL_MAX_LENGTH))
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

    private String normalizeOptional(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n[内容过长，已截断用于本次分析]";
    }

    private CreatorSuggestionResponse toResponse(CreatorSuggestionRecord record) {
        return new CreatorSuggestionResponse(
                record.getId(),
                record.getSuggestionId(),
                record.getTaskId(),
                record.getContentSummary(),
                record.getAudienceProfile(),
                record.getSellingPoints(),
                record.getRiskPoints(),
                record.getTitleSuggestions(),
                record.getDescriptionSuggestion(),
                record.getTagSuggestions(),
                record.getPartitionSuggestion(),
                record.getRawOutput(),
                record.getParseStatus(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
