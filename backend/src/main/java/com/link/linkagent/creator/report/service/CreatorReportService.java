package com.link.linkagent.creator.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.competitor.mapper.CreatorCompetitorMapper;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorReportRecord;
import com.link.linkagent.creator.feedback.mapper.CreatorFeedbackMapper;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackReportRecord;
import com.link.linkagent.creator.preference.service.CreatorPreferenceService;
import com.link.linkagent.creator.report.mapper.CreatorReportMapper;
import com.link.linkagent.creator.report.model.CreatorReportAnalyzeRequest;
import com.link.linkagent.creator.report.model.CreatorReportRecord;
import com.link.linkagent.creator.report.model.CreatorReportResponse;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 创作复盘报告服务。
 * 该服务只汇总已落库的发布前建议和反馈分析，避免把复盘阶段变成新的原始数据入口。
 */
@Service
public class CreatorReportService {

    private static final int MATERIAL_MAX_LENGTH = 4000;
    private static final int SECTION_MAX_LENGTH = 8000;

    private final CreatorTaskMapper creatorTaskMapper;
    private final CreatorSuggestionMapper creatorSuggestionMapper;
    private final CreatorFeedbackMapper creatorFeedbackMapper;
    private final CreatorCompetitorMapper creatorCompetitorMapper;
    private final CreatorReportMapper creatorReportMapper;
    private final CreatorPreferenceService creatorPreferenceService;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;
    private final PromptService promptService;

    public CreatorReportService(CreatorTaskMapper creatorTaskMapper,
                                CreatorSuggestionMapper creatorSuggestionMapper,
                                CreatorFeedbackMapper creatorFeedbackMapper,
                                CreatorCompetitorMapper creatorCompetitorMapper,
                                CreatorReportMapper creatorReportMapper,
                                CreatorPreferenceService creatorPreferenceService,
                                LLMService llmService,
                                ObjectMapper objectMapper,
                                PromptService promptService) {
        this.creatorTaskMapper = creatorTaskMapper;
        this.creatorSuggestionMapper = creatorSuggestionMapper;
        this.creatorFeedbackMapper = creatorFeedbackMapper;
        this.creatorCompetitorMapper = creatorCompetitorMapper;
        this.creatorReportMapper = creatorReportMapper;
        this.creatorPreferenceService = creatorPreferenceService;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.promptService = promptService;
    }

    @Transactional
    public CreatorReportResponse analyze(String taskId, CreatorReportAnalyzeRequest request) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        CreatorSuggestionRecord suggestionRecord = creatorSuggestionMapper.findByTaskId(taskRecord.getTaskId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先生成发布前优化建议"));
        CreatorFeedbackReportRecord feedbackReportRecord = creatorFeedbackMapper.findReportByTaskId(taskRecord.getTaskId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先完成评论弹幕分析"));
        CreatorCompetitorReportRecord competitorReportRecord = creatorCompetitorMapper.findReportByTaskId(taskRecord.getTaskId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先完成同类型视频竞品分析"));
        List<CreatorMaterialRecord> materials = creatorTaskMapper.listMaterialsByTaskId(taskRecord.getTaskId());

        String rawOutput;
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.open(taskRecord.getTaskId(), "创作复盘报告")) {
            rawOutput = llmService.chat(
                    buildSystemPrompt(),
                    buildUserPrompt(taskRecord, materials, suggestionRecord, feedbackReportRecord, competitorReportRecord, request)
            );
        }
        CreatorReportRecord reportRecord = buildReportRecord(taskRecord.getTaskId(), rawOutput);
        creatorReportMapper.upsert(reportRecord);
        creatorPreferenceService.saveFromReport(taskRecord, reportRecord);
        creatorTaskMapper.updateTaskStatus(taskRecord.getTaskId(), CreatorTaskStatus.ANALYZED.name());
        return getReport(taskRecord.getTaskId());
    }

    public CreatorReportResponse getReport(String taskId) {
        getTaskRecord(taskId);
        return toResponse(getReportRecord(taskId));
    }

    public String exportMarkdown(String taskId) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        CreatorReportRecord reportRecord = getReportRecord(taskRecord.getTaskId());
        return buildMarkdownReport(taskRecord, reportRecord);
    }

    private CreatorTaskRecord getTaskRecord(String taskId) {
        return creatorTaskMapper.findTaskByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在"));
    }

    private CreatorReportRecord getReportRecord(String taskId) {
        return creatorReportMapper.findByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创作复盘报告不存在"));
    }

    private CreatorReportRecord buildReportRecord(String taskId, String rawOutput) {
        CreatorReportRecord record = new CreatorReportRecord();
        record.setReportId(UUID.randomUUID().toString());
        record.setTaskId(taskId);
        record.setRawOutput(rawOutput);
        fillParsedFields(record, rawOutput);
        return record;
    }

    private void fillParsedFields(CreatorReportRecord record, String rawOutput) {
        try {
            JsonNode rootNode = objectMapper.readTree(LlmJsonUtil.extractJsonObject(rawOutput));
            record.setContentSummary(LlmJsonUtil.text(rootNode, "contentSummary"));
            record.setCoreSellingPoints(LlmJsonUtil.json(objectMapper, rootNode, "coreSellingPoints"));
            record.setTitleDescriptionReview(LlmJsonUtil.json(objectMapper, rootNode, "titleDescriptionReview"));
            record.setAudienceFeedbackSummary(LlmJsonUtil.text(rootNode, "audienceFeedbackSummary"));
            record.setCompetitorComparison(LlmJsonUtil.json(objectMapper, rootNode, "competitorComparison"));
            record.setControversyAndMisunderstanding(LlmJsonUtil.json(objectMapper, rootNode, "controversyAndMisunderstanding"));
            record.setNextActionSuggestions(LlmJsonUtil.json(objectMapper, rootNode, "nextActionSuggestions"));
            record.setCreatorPreferenceInsight(LlmJsonUtil.json(objectMapper, rootNode, "creatorPreferenceInsight"));
            record.setOverallConclusion(LlmJsonUtil.text(rootNode, "overallConclusion"));
            record.setParseStatus("PARSED");
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            record.setParseStatus("RAW_ONLY");
        }
    }

    private String buildSystemPrompt() {
        return promptService.get("report.system");
    }

    private String buildUserPrompt(CreatorTaskRecord taskRecord,
                                   List<CreatorMaterialRecord> materials,
                                   CreatorSuggestionRecord suggestionRecord,
                                   CreatorFeedbackReportRecord feedbackReportRecord,
                                   CreatorCompetitorReportRecord competitorReportRecord,
                                   CreatorReportAnalyzeRequest request) {
        return promptService.render("report.user", Map.of(
                "taskName", taskRecord.getTaskName(),
                "taskId", taskRecord.getTaskId(),
                "customGuidance", TextUtil.trimToDefault(request.customGuidance(), "未提供"),
                "reviewFocus", TextUtil.trimToDefault(request.reviewFocus(), "未提供"),
                "extraRequirement", TextUtil.trimToDefault(request.extraRequirement(), "未提供"),
                "materials", buildMaterialPrompt(materials),
                "suggestionResult", buildSuggestionPrompt(suggestionRecord),
                "feedbackResult", buildFeedbackReportPrompt(feedbackReportRecord),
                "competitorResult", buildCompetitorReportPrompt(competitorReportRecord)
        ));
    }

    private String buildMaterialPrompt(List<CreatorMaterialRecord> materials) {
        if (materials.isEmpty()) {
            return "未提供";
        }
        StringBuilder builder = new StringBuilder();
        for (CreatorMaterialRecord material : materials) {
            builder.append("\n【")
                    .append(toChineseMaterialName(material.getMaterialType()))
                    .append("】\n")
                    .append(limitSection(material.getContent(), MATERIAL_MAX_LENGTH))
                    .append("\n");
        }
        return builder.toString();
    }

    private String buildSuggestionPrompt(CreatorSuggestionRecord record) {
        return """
                内容摘要：%s
                创作者困境：%s
                目标受众：%s
                观众钩子：%s
                内容定位：%s
                核心卖点：%s
                风险点：%s
                标题建议：%s
                简介建议：%s
                可执行修改计划：%s
                标签建议：%s
                分区建议：%s
                解析状态：%s
                """.formatted(
                normalizeSection(record.getContentSummary()),
                normalizeSection(record.getCreatorDilemma()),
                normalizeSection(record.getAudienceProfile()),
                normalizeSection(record.getAudienceHook()),
                normalizeSection(record.getContentPositioning()),
                normalizeSection(record.getSellingPoints()),
                normalizeSection(record.getRiskPoints()),
                normalizeSection(record.getTitleSuggestions()),
                normalizeSection(record.getDescriptionSuggestion()),
                normalizeSection(record.getActionableRevisionPlan()),
                normalizeSection(record.getTagSuggestions()),
                normalizeSection(record.getPartitionSuggestion()),
                normalizeSection(record.getParseStatus())
        );
    }

    private String buildFeedbackReportPrompt(CreatorFeedbackReportRecord record) {
        return """
                反馈摘要：%s
                创作者反馈困境：%s
                观众核心关注：%s
                高频观点：%s
                情绪倾向：%s
                争议点：%s
                误解点：%s
                误解来源分析：%s
                下一期内容建议：%s
                互动回应建议：%s
                反馈行动计划：%s
                解析状态：%s
                """.formatted(
                normalizeSection(record.getFeedbackSummary()),
                normalizeSection(record.getCreatorFeedbackDilemma()),
                normalizeSection(record.getAudienceCoreConcern()),
                normalizeSection(record.getHotTopics()),
                normalizeSection(record.getSentimentSummary()),
                normalizeSection(record.getControversyPoints()),
                normalizeSection(record.getMisunderstandingPoints()),
                normalizeSection(record.getMisunderstandingSourceAnalysis()),
                normalizeSection(record.getNextContentSuggestions()),
                normalizeSection(record.getInteractionSuggestions()),
                normalizeSection(record.getFeedbackActionPlan()),
                normalizeSection(record.getParseStatus())
        );
    }

    private String buildCompetitorReportPrompt(CreatorCompetitorReportRecord record) {
        return """
                竞品整体打法：%s
                竞品优势：%s
                本视频优势：%s
                本视频短板：%s
                差距分析：%s
                改进建议：%s
                差异化策略：%s
                解析状态：%s
                """.formatted(
                normalizeSection(record.getCompetitorSummary()),
                normalizeSection(record.getCompetitorAdvantages()),
                normalizeSection(record.getOwnAdvantages()),
                normalizeSection(record.getOwnDisadvantages()),
                normalizeSection(record.getGapAnalysis()),
                normalizeSection(record.getImprovementSuggestions()),
                normalizeSection(record.getDifferentiationStrategy()),
                normalizeSection(record.getParseStatus())
        );
    }

    private String normalizeSection(String value) {
        return limitSection(TextUtil.trimToDefault(value, "未提供"), SECTION_MAX_LENGTH);
    }

    private String limitSection(String value, int maxLength) {
        String normalized = TextUtil.trimToDefault(value, "未提供");
        return TextUtil.abbreviateWithSuffix(
                normalized,
                maxLength,
                "\n[内容过长，已截断用于本次复盘]"
        );
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

    private String buildMarkdownReport(CreatorTaskRecord taskRecord, CreatorReportRecord reportRecord) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ")
                .append(TextUtil.trimToDefault(TextUtil.collapseWhitespace(taskRecord.getTaskName()), "未命名任务"))
                .append(" 创作复盘报告\n\n");
        builder.append("- 任务ID：").append(taskRecord.getTaskId()).append("\n");
        builder.append("- 报告ID：").append(reportRecord.getReportId()).append("\n");
        builder.append("- 解析状态：").append(TextUtil.trimToDefault(reportRecord.getParseStatus(), "未提供")).append("\n");
        builder.append("- 生成时间：").append(reportRecord.getCreateTime() == null ? "未提供" : reportRecord.getCreateTime()).append("\n");
        builder.append("- 更新时间：").append(reportRecord.getUpdateTime() == null ? "未提供" : reportRecord.getUpdateTime()).append("\n\n");

        appendMarkdownSection(builder, "内容摘要", reportRecord.getContentSummary());
        appendMarkdownSection(builder, "核心卖点", reportRecord.getCoreSellingPoints());
        appendMarkdownSection(builder, "标题简介复盘", reportRecord.getTitleDescriptionReview());
        appendMarkdownSection(builder, "观众反馈摘要", reportRecord.getAudienceFeedbackSummary());
        appendMarkdownSection(builder, "竞品对照结论", reportRecord.getCompetitorComparison());
        appendMarkdownSection(builder, "争议与误解", reportRecord.getControversyAndMisunderstanding());
        appendMarkdownSection(builder, "下一步动作建议", reportRecord.getNextActionSuggestions());
        appendMarkdownSection(builder, "创作者偏好洞察", reportRecord.getCreatorPreferenceInsight());
        appendMarkdownSection(builder, "复盘总判断", reportRecord.getOverallConclusion());

        if (!"PARSED".equals(reportRecord.getParseStatus())) {
            // 解析失败时必须保留原始输出，否则导出的报告会丢失排查 LLM 输出格式问题的关键证据。
            appendMarkdownSection(builder, "原始输出", reportRecord.getRawOutput());
        }
        return builder.toString();
    }

    private void appendMarkdownSection(StringBuilder builder, String title, String value) {
        builder.append("## ").append(title).append("\n\n");
        builder.append(formatMarkdownValue(value)).append("\n\n");
    }

    private String formatMarkdownValue(String value) {
        String normalized = TextUtil.trimToDefault(value, "未提供");
        try {
            JsonNode rootNode = objectMapper.readTree(normalized);
            StringBuilder builder = new StringBuilder();
            appendJsonNodeMarkdown(builder, rootNode, 0);
            return TextUtil.trimToDefault(builder.toString(), "未提供");
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return normalized;
        }
    }

    private void appendJsonNodeMarkdown(StringBuilder builder, JsonNode node, int indent) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            builder.append(indent(indent)).append("未提供\n");
            return;
        }
        if (node.isValueNode()) {
            builder.append(indent(indent)).append(toMarkdownScalar(node)).append("\n");
            return;
        }
        if (node.isArray()) {
            appendJsonArrayMarkdown(builder, node, indent);
            return;
        }
        appendJsonObjectMarkdown(builder, node, indent);
    }

    private void appendJsonArrayMarkdown(StringBuilder builder, JsonNode arrayNode, int indent) {
        if (arrayNode.size() == 0) {
            builder.append(indent(indent)).append("- 未提供\n");
            return;
        }
        for (JsonNode item : arrayNode) {
            if (item.isObject()) {
                builder.append(indent(indent)).append("- ").append(resolveObjectSummary(item)).append("\n");
                appendJsonObjectMarkdown(builder, item, indent + 2);
            } else if (item.isArray()) {
                builder.append(indent(indent)).append("-\n");
                appendJsonArrayMarkdown(builder, item, indent + 2);
            } else {
                builder.append(indent(indent)).append("- ").append(toMarkdownScalar(item)).append("\n");
            }
        }
    }

    private void appendJsonObjectMarkdown(StringBuilder builder, JsonNode objectNode, int indent) {
        Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
        if (!fields.hasNext()) {
            builder.append(indent(indent)).append("- 未提供\n");
            return;
        }
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode childNode = field.getValue();
            String label = labelForReportKey(field.getKey());
            if (childNode == null || childNode.isNull() || childNode.isMissingNode()) {
                builder.append(indent(indent)).append("- **").append(label).append("**：未提供\n");
                continue;
            }
            if (childNode.isValueNode()) {
                builder.append(indent(indent))
                        .append("- **")
                        .append(label)
                        .append("**：")
                        .append(toMarkdownScalar(childNode))
                        .append("\n");
                continue;
            }
            builder.append(indent(indent)).append("- **").append(label).append("**：\n");
            appendJsonNodeMarkdown(builder, childNode, indent + 2);
        }
    }

    private String resolveObjectSummary(JsonNode objectNode) {
        List<String> summaryKeys = List.of(
                "suggestion",
                "point",
                "title",
                "topic",
                "target",
                "benchmarkConclusion",
                "titleConclusion"
        );
        for (String key : summaryKeys) {
            JsonNode valueNode = objectNode.get(key);
            if (valueNode != null && valueNode.isValueNode()) {
                String value = TextUtil.trimToDefault(valueNode.asText(), "");
                if (TextUtil.hasText(value)) {
                    return value;
                }
            }
        }
        return "条目";
    }

    private String toMarkdownScalar(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "未提供";
        }
        if (node.isBoolean()) {
            return node.asBoolean() ? "是" : "否";
        }
        return TextUtil.trimToDefault(node.asText(), "未提供");
    }

    private String labelForReportKey(String key) {
        return switch (key) {
            case "titleConclusion" -> "标题结论";
            case "descriptionConclusion" -> "简介结论";
            case "tagAndPartitionConclusion" -> "标签与分区结论";
            case "riskReminder" -> "风险提醒";
            case "benchmarkConclusion" -> "对标结论";
            case "ownAdvantages" -> "本视频优势";
            case "ownDisadvantages" -> "本视频短板";
            case "differentiationStrategy" -> "差异化策略";
            case "point" -> "问题点";
            case "impact" -> "影响";
            case "action" -> "处理建议";
            case "suggestion" -> "动作建议";
            case "reason" -> "依据";
            case "priority" -> "优先级";
            case "title" -> "标题";
            case "topic" -> "选题";
            case "target" -> "目标";
            default -> key;
        };
    }

    private String indent(int count) {
        return " ".repeat(Math.max(0, count));
    }

    private CreatorReportResponse toResponse(CreatorReportRecord record) {
        return new CreatorReportResponse(
                record.getId(),
                record.getReportId(),
                record.getTaskId(),
                record.getContentSummary(),
                record.getCoreSellingPoints(),
                record.getTitleDescriptionReview(),
                record.getAudienceFeedbackSummary(),
                record.getCompetitorComparison(),
                record.getControversyAndMisunderstanding(),
                record.getNextActionSuggestions(),
                record.getCreatorPreferenceInsight(),
                record.getOverallConclusion(),
                record.getRawOutput(),
                record.getParseStatus(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
