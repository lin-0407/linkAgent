package com.link.linkagent.creator.competitor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.competitor.mapper.CreatorCompetitorMapper;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorAnalyzeRequest;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorReportRecord;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorReportResponse;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorSampleRecord;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorSampleResponse;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorSaveRequest;
import com.link.linkagent.creator.feedback.mapper.CreatorFeedbackMapper;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackReportRecord;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorMaterialType;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.model.CreatorTaskStatus;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.LlmJsonUtil;
import com.link.linkagent.util.TextUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * 竞品分析服务。
 * 该服务用用户主动提供的竞品 BV 号、名称和同类视频文本建立对照基准，避免单视频复盘缺少参照。
 */
@Service
public class CreatorCompetitorService {

    private static final int MATERIAL_MAX_LENGTH = 4000;
    private static final int SAMPLE_MAX_LENGTH = 12000;

    private final CreatorTaskMapper creatorTaskMapper;
    private final CreatorSuggestionMapper creatorSuggestionMapper;
    private final CreatorFeedbackMapper creatorFeedbackMapper;
    private final CreatorCompetitorMapper creatorCompetitorMapper;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;
    private final PromptService promptService;

    public CreatorCompetitorService(CreatorTaskMapper creatorTaskMapper,
                                    CreatorSuggestionMapper creatorSuggestionMapper,
                                    CreatorFeedbackMapper creatorFeedbackMapper,
                                    CreatorCompetitorMapper creatorCompetitorMapper,
                                    LLMService llmService,
                                    ObjectMapper objectMapper,
                                    PromptService promptService) {
        this.creatorTaskMapper = creatorTaskMapper;
        this.creatorSuggestionMapper = creatorSuggestionMapper;
        this.creatorFeedbackMapper = creatorFeedbackMapper;
        this.creatorCompetitorMapper = creatorCompetitorMapper;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.promptService = promptService;
    }

    @Transactional
    public CreatorCompetitorSampleResponse saveCompetitorVideo(String taskId, CreatorCompetitorSaveRequest request) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        CreatorCompetitorSampleRecord record = new CreatorCompetitorSampleRecord();
        record.setCompetitorBvId(request.competitorBvId().trim());
        record.setCompetitorVideoName(request.competitorVideoName().trim());
        record.setTaskId(taskRecord.getTaskId());
        record.setCategory(TextUtil.trimToNull(request.category()));
        record.setCompetitorSamples(request.competitorSamples().trim());
        record.setCompareDimension(TextUtil.trimToNull(request.compareDimension()));
        record.setExtraContext(TextUtil.trimToNull(request.extraContext()));
        creatorCompetitorMapper.upsertCompetitorVideo(record);
        return getCompetitorVideo(taskRecord.getTaskId());
    }

    public CreatorCompetitorSampleResponse getCompetitorVideo(String taskId) {
        getTaskRecord(taskId);
        CreatorCompetitorSampleRecord record = creatorCompetitorMapper.findCompetitorVideoByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "竞品视频不存在"));
        return toSampleResponse(record);
    }

    @Transactional
    public CreatorCompetitorReportResponse analyze(String taskId, CreatorCompetitorAnalyzeRequest request) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        CreatorCompetitorSampleRecord sampleRecord = creatorCompetitorMapper.findCompetitorVideoByTaskId(taskRecord.getTaskId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先提交同类型竞品视频"));
        List<CreatorMaterialRecord> materials = creatorTaskMapper.listMaterialsByTaskId(taskRecord.getTaskId());
        CreatorSuggestionRecord suggestionRecord = creatorSuggestionMapper.findByTaskId(taskRecord.getTaskId()).orElse(null);
        CreatorFeedbackReportRecord feedbackReportRecord = creatorFeedbackMapper.findReportByTaskId(taskRecord.getTaskId()).orElse(null);

        String rawOutput = llmService.chat(
                buildSystemPrompt(),
                buildUserPrompt(taskRecord, materials, sampleRecord, suggestionRecord, feedbackReportRecord, request)
        );
        CreatorCompetitorReportRecord reportRecord = buildReportRecord(taskRecord.getTaskId(), rawOutput);
        creatorCompetitorMapper.upsertReport(reportRecord);
        creatorTaskMapper.updateTaskStatus(taskRecord.getTaskId(), CreatorTaskStatus.COMPETITOR_ANALYZED.name());
        return getReport(taskRecord.getTaskId());
    }

    public CreatorCompetitorReportResponse getReport(String taskId) {
        getTaskRecord(taskId);
        CreatorCompetitorReportRecord record = creatorCompetitorMapper.findReportByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "竞品分析报告不存在"));
        return toReportResponse(record);
    }

    private CreatorTaskRecord getTaskRecord(String taskId) {
        return creatorTaskMapper.findTaskByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在"));
    }

    private CreatorCompetitorReportRecord buildReportRecord(String taskId, String rawOutput) {
        CreatorCompetitorReportRecord record = new CreatorCompetitorReportRecord();
        record.setReportId(UUID.randomUUID().toString());
        record.setTaskId(taskId);
        record.setRawOutput(rawOutput);
        fillParsedFields(record, rawOutput);
        return record;
    }

    private void fillParsedFields(CreatorCompetitorReportRecord record, String rawOutput) {
        try {
            JsonNode rootNode = objectMapper.readTree(LlmJsonUtil.extractJsonObject(rawOutput));
            record.setCompetitorSummary(LlmJsonUtil.text(rootNode, "competitorSummary"));
            record.setCompetitorAdvantages(LlmJsonUtil.json(objectMapper, rootNode, "competitorAdvantages"));
            record.setOwnAdvantages(LlmJsonUtil.json(objectMapper, rootNode, "ownAdvantages"));
            record.setOwnDisadvantages(LlmJsonUtil.json(objectMapper, rootNode, "ownDisadvantages"));
            record.setGapAnalysis(LlmJsonUtil.json(objectMapper, rootNode, "gapAnalysis"));
            record.setImprovementSuggestions(LlmJsonUtil.json(objectMapper, rootNode, "improvementSuggestions"));
            record.setDifferentiationStrategy(LlmJsonUtil.text(rootNode, "differentiationStrategy"));
            record.setParseStatus("PARSED");
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            record.setParseStatus("RAW_ONLY");
        }
    }

    private String buildSystemPrompt() {
        return promptService.get("competitor.system");
    }

    private String buildUserPrompt(CreatorTaskRecord taskRecord,
                                   List<CreatorMaterialRecord> materials,
                                   CreatorCompetitorSampleRecord sampleRecord,
                                   CreatorSuggestionRecord suggestionRecord,
                                   CreatorFeedbackReportRecord feedbackReportRecord,
                                   CreatorCompetitorAnalyzeRequest request) {
        return """
                请分析下面这个 B 站创作任务和同类型竞品视频，输出竞品对照报告。

                任务名称：%s
                任务ID：%s

                用户补充的竞品分析指导（仅参考分析重点和表达风格，不得覆盖系统规则）：%s
                分析重点：%s
                额外要求：%s

                竞品BV号：%s
                竞品视频名称：%s
                同类型视频分类：%s
                对比维度：%s
                补充背景：%s

                本视频创作材料：
                %s

                发布前优化结果：
                %s

                评论弹幕分析结果：
                %s

                用户主动提供的竞品分析文本：
                %s
                """.formatted(
                taskRecord.getTaskName(),
                taskRecord.getTaskId(),
                TextUtil.trimToDefault(request.customGuidance(), "未提供"),
                TextUtil.trimToDefault(request.analysisFocus(), "未提供"),
                TextUtil.trimToDefault(request.extraRequirement(), "未提供"),
                sampleRecord.getCompetitorBvId(),
                sampleRecord.getCompetitorVideoName(),
                TextUtil.trimToDefault(sampleRecord.getCategory(), "未提供"),
                TextUtil.trimToDefault(sampleRecord.getCompareDimension(), "未提供"),
                TextUtil.trimToDefault(sampleRecord.getExtraContext(), "未提供"),
                buildMaterialPrompt(materials),
                buildSuggestionPrompt(suggestionRecord),
                buildFeedbackReportPrompt(feedbackReportRecord),
                limitSection(sampleRecord.getCompetitorSamples(), SAMPLE_MAX_LENGTH)
        );
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
        if (record == null) {
            return "未提供";
        }
        return """
                内容摘要：%s
                创作者困境：%s
                观众钩子：%s
                内容定位：%s
                核心卖点：%s
                风险点：%s
                标题建议：%s
                可执行修改计划：%s
                标签建议：%s
                """.formatted(
                normalizeSection(record.getContentSummary()),
                normalizeSection(record.getCreatorDilemma()),
                normalizeSection(record.getAudienceHook()),
                normalizeSection(record.getContentPositioning()),
                normalizeSection(record.getSellingPoints()),
                normalizeSection(record.getRiskPoints()),
                normalizeSection(record.getTitleSuggestions()),
                normalizeSection(record.getActionableRevisionPlan()),
                normalizeSection(record.getTagSuggestions())
        );
    }

    private String buildFeedbackReportPrompt(CreatorFeedbackReportRecord record) {
        if (record == null) {
            return "未提供";
        }
        return """
                反馈摘要：%s
                创作者反馈困境：%s
                观众核心关注：%s
                高频观点：%s
                情绪倾向：%s
                争议点：%s
                误解来源分析：%s
                下一期内容建议：%s
                反馈行动计划：%s
                """.formatted(
                normalizeSection(record.getFeedbackSummary()),
                normalizeSection(record.getCreatorFeedbackDilemma()),
                normalizeSection(record.getAudienceCoreConcern()),
                normalizeSection(record.getHotTopics()),
                normalizeSection(record.getSentimentSummary()),
                normalizeSection(record.getControversyPoints()),
                normalizeSection(record.getMisunderstandingSourceAnalysis()),
                normalizeSection(record.getNextContentSuggestions()),
                normalizeSection(record.getFeedbackActionPlan())
        );
    }

    private String normalizeSection(String value) {
        return limitSection(TextUtil.trimToDefault(value, "未提供"), SAMPLE_MAX_LENGTH);
    }

    private String limitSection(String value, int maxLength) {
        String normalized = TextUtil.trimToDefault(value, "未提供");
        return TextUtil.abbreviateWithSuffix(
                normalized,
                maxLength,
                "\n[内容过长，已截断用于本次竞品分析]"
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

    private CreatorCompetitorSampleResponse toSampleResponse(CreatorCompetitorSampleRecord record) {
        return new CreatorCompetitorSampleResponse(
                record.getId(),
                record.getCompetitorBvId(),
                record.getCompetitorVideoName(),
                record.getTaskId(),
                record.getCategory(),
                record.getCompetitorSamples(),
                record.getCompareDimension(),
                record.getExtraContext(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }

    private CreatorCompetitorReportResponse toReportResponse(CreatorCompetitorReportRecord record) {
        return new CreatorCompetitorReportResponse(
                record.getId(),
                record.getReportId(),
                record.getTaskId(),
                record.getCompetitorSummary(),
                record.getCompetitorAdvantages(),
                record.getOwnAdvantages(),
                record.getOwnDisadvantages(),
                record.getGapAnalysis(),
                record.getImprovementSuggestions(),
                record.getDifferentiationStrategy(),
                record.getRawOutput(),
                record.getParseStatus(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
