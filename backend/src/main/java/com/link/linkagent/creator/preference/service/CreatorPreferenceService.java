package com.link.linkagent.creator.preference.service;

import com.link.linkagent.creator.preference.mapper.CreatorPreferenceMapper;
import com.link.linkagent.creator.preference.model.CreatorPreferenceRecord;
import com.link.linkagent.creator.preference.model.CreatorPreferenceResponse;
import com.link.linkagent.creator.report.model.CreatorReportRecord;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.util.NumberUtil;
import com.link.linkagent.util.TextUtil;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 创作者长期偏好服务。
 * 业务偏好与通用对话记忆分开保存，是为了让发布前优化只读取和创作决策相关的历史经验。
 */
@Service
public class CreatorPreferenceService {

    private static final String DEFAULT_USER_ID = "default";
    private static final int DEFAULT_LIST_LIMIT = 10;
    private static final int MAX_LIST_LIMIT = 20;
    private static final int PROMPT_HISTORY_LIMIT = 5;
    private static final int PROMPT_CONTEXT_MAX_LENGTH = 6000;

    private final CreatorPreferenceMapper creatorPreferenceMapper;

    public CreatorPreferenceService(CreatorPreferenceMapper creatorPreferenceMapper) {
        this.creatorPreferenceMapper = creatorPreferenceMapper;
    }

    /**
     * 只沉淀可解析报告中的有效洞察，避免把 LLM 解析失败的原始文本误当成长期偏好带入后续任务。
     */
    public void saveFromReport(CreatorTaskRecord taskRecord, CreatorReportRecord reportRecord) {
        if (taskRecord == null
                || reportRecord == null
                || !"PARSED".equals(reportRecord.getParseStatus())
                || !hasPreferenceContent(reportRecord.getCreatorPreferenceInsight())) {
            return;
        }

        CreatorPreferenceRecord preferenceRecord = new CreatorPreferenceRecord();
        preferenceRecord.setPreferenceId(UUID.randomUUID().toString());
        preferenceRecord.setUserId(normalizeUserId(taskRecord.getUserId()));
        preferenceRecord.setSourceTaskId(taskRecord.getTaskId());
        preferenceRecord.setSourceReportId(reportRecord.getReportId());
        preferenceRecord.setPreferenceContent(reportRecord.getCreatorPreferenceInsight().trim());
        creatorPreferenceMapper.upsert(preferenceRecord);
    }

    public List<CreatorPreferenceResponse> listPreferences(String userId, Integer limit) {
        int safeLimit = NumberUtil.limitOrDefault(limit, DEFAULT_LIST_LIMIT, MAX_LIST_LIMIT);
        return creatorPreferenceMapper.listByUserId(normalizeUserId(userId), safeLimit)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 发布前优化只读取最近几期偏好，限制长度是为了避免历史内容无限增长挤占本期文稿上下文。
     * 同时汇总用户对历史建议的"采用/拒绝"反馈，让 AI 知道用户实际偏好什么风格。
     */
    public String buildPromptContext(String userId) {
        List<CreatorPreferenceRecord> records = creatorPreferenceMapper.listByUserId(
                normalizeUserId(userId),
                PROMPT_HISTORY_LIMIT
        );
        if (records.isEmpty()) {
            return "暂无历史创作者偏好";
        }

        StringBuilder builder = new StringBuilder();

        // 汇总历史偏好内容
        for (int index = 0; index < records.size(); index++) {
            CreatorPreferenceRecord record = records.get(index);
            builder.append(index + 1)
                    .append(". 来源任务 ")
                    .append(record.getSourceTaskId())
                    .append("：")
                    .append(record.getPreferenceContent())
                    .append("\n");
        }

        // 追加采用/拒绝反馈记录，让 AI 了解用户实际偏好什么风格
        List<CreatorPreferenceRecord> feedbackRecords = creatorPreferenceMapper.listAdoptionFeedbackByUserId(
                normalizeUserId(userId),
                PROMPT_HISTORY_LIMIT
        );
        if (!feedbackRecords.isEmpty()) {
            builder.append("\n你的标题风格偏好（基于历史采用/拒绝记录）：\n");
            for (int index = 0; index < feedbackRecords.size(); index++) {
                CreatorPreferenceRecord record = feedbackRecords.get(index);
                builder.append(index + 1)
                        .append(". ")
                        .append(record.getPreferenceContent())
                        .append("\n");
            }
        }

        return TextUtil.abbreviateWithSuffix(
                builder.toString().trim(),
                PROMPT_CONTEXT_MAX_LENGTH,
                "\n[历史偏好过长，已截断用于本次分析]"
        );
    }

    /**
     * 记录用户对 AI 建议的采用/拒绝反馈。
     * 当用户在发布前优化中确认采用某个标题风格或手动修改后采用时，
     * 将用户的实际选择写入偏好表，供后续生成时参考。
     *
     * @param userId         用户ID
     * @param taskId         当前任务ID
     * @param preferenceType 偏好类型：ADOPTED（采用）、MODIFIED（修改后采用）、REJECTED（拒绝）
     * @param description    偏好描述，例如"采用短句+数字开头的标题风格，拒绝长句偏严肃风格"
     */
    public void recordAdoptionFeedback(String userId, String taskId, String preferenceType, String description) {
        if (TextUtil.isBlank(description)) {
            return;
        }
        CreatorPreferenceRecord record = new CreatorPreferenceRecord();
        record.setPreferenceId(UUID.randomUUID().toString());
        record.setUserId(normalizeUserId(userId));
        record.setSourceTaskId(taskId);
        // 采用/拒绝反馈没有 sourceReportId，使用特殊标记区分
        record.setSourceReportId("ADOPTION_FEEDBACK_" + preferenceType);
        record.setPreferenceContent("[" + preferenceType + "] " + description.trim());
        creatorPreferenceMapper.upsertAdoptionFeedback(record);
    }

    private boolean hasPreferenceContent(String preferenceContent) {
        if (TextUtil.isBlank(preferenceContent)) {
            return false;
        }
        String normalized = preferenceContent.trim();
        return !"[]".equals(normalized) && !"null".equalsIgnoreCase(normalized);
    }

    private String normalizeUserId(String userId) {
        return TextUtil.trimToDefault(userId, DEFAULT_USER_ID);
    }

    private CreatorPreferenceResponse toResponse(CreatorPreferenceRecord record) {
        return new CreatorPreferenceResponse(
                record.getId(),
                record.getPreferenceId(),
                record.getUserId(),
                record.getSourceTaskId(),
                record.getSourceReportId(),
                record.getPreferenceContent(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
