package com.link.linkagent.creator.evaluation.service;

import com.link.linkagent.creator.evaluation.mapper.CreatorEvaluationMapper;
import com.link.linkagent.creator.evaluation.model.CreatorEvalCaseCreateRequest;
import com.link.linkagent.creator.evaluation.model.CreatorEvalCaseRecord;
import com.link.linkagent.creator.evaluation.model.CreatorEvalCaseResponse;
import com.link.linkagent.creator.evaluation.model.CreatorEvalPromptVersionStatsResponse;
import com.link.linkagent.creator.evaluation.model.CreatorEvalResultCreateRequest;
import com.link.linkagent.creator.evaluation.model.CreatorEvalResultRecord;
import com.link.linkagent.creator.evaluation.model.CreatorEvalResultResponse;
import com.link.linkagent.util.NumberUtil;
import com.link.linkagent.util.TextUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 评测集服务。
 * 这一层只做样例和结果的落库、查询和基础校验，不把评测解释成新的业务分析，以免偏离创作者工作流主线。
 */
@Service
public class CreatorEvaluationService {

    private static final String DEFAULT_USER_ID = "default";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String RUN_STATUS_SUCCESS = "SUCCESS";
    private static final String RUN_STATUS_FAILED = "FAILED";
    private static final String PARSE_STATUS_PARSED = "PARSED";
    private static final String PARSE_STATUS_RAW_ONLY = "RAW_ONLY";

    private final CreatorEvaluationMapper creatorEvaluationMapper;

    public CreatorEvaluationService(CreatorEvaluationMapper creatorEvaluationMapper) {
        this.creatorEvaluationMapper = creatorEvaluationMapper;
    }

    @Transactional
    public CreatorEvalCaseResponse createCase(CreatorEvalCaseCreateRequest request) {
        CreatorEvalCaseRecord record = new CreatorEvalCaseRecord();
        record.setCaseId(UUID.randomUUID().toString());
        record.setUserId(TextUtil.trimToDefault(request.userId(), DEFAULT_USER_ID));
        record.setCaseName(request.caseName().trim());
        record.setTargetStage(normalizeStage(request.targetStage()));
        record.setTaskId(TextUtil.trimToNull(request.taskId()));
        record.setInputSnapshot(request.inputSnapshot().trim());
        record.setExpectedPoints(TextUtil.trimToNull(request.expectedPoints()));
        record.setScoringRubric(TextUtil.trimToNull(request.scoringRubric()));
        record.setStatus(STATUS_ACTIVE);
        creatorEvaluationMapper.insertCase(record);
        return getCase(record.getCaseId());
    }

    public List<CreatorEvalCaseResponse> listCases(String userId, String targetStage, Integer limit) {
        int safeLimit = NumberUtil.limitOrDefault(limit, DEFAULT_LIMIT, MAX_LIMIT);
        String safeUserId = TextUtil.trimToNull(userId);
        String safeStage = normalizeOptionalStage(targetStage);
        return creatorEvaluationMapper.listCases(safeUserId, safeStage, safeLimit)
                .stream()
                .map(this::toCaseResponse)
                .toList();
    }

    public CreatorEvalCaseResponse getCase(String caseId) {
        CreatorEvalCaseRecord record = getCaseRecord(caseId);
        return toCaseResponse(record);
    }

    @Transactional
    public CreatorEvalResultResponse recordResult(String caseId, CreatorEvalResultCreateRequest request) {
        CreatorEvalCaseRecord caseRecord = getCaseRecord(caseId);
        String targetStage = normalizeStage(request.targetStage());
        if (!caseRecord.getTargetStage().equals(targetStage)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评测结果阶段必须与评测用例阶段保持一致");
        }

        String rawOutput = TextUtil.trimToNull(request.rawOutput());
        String failureReason = TextUtil.trimToNull(request.failureReason());
        if (rawOutput == null && failureReason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评测结果必须提供模型输出或失败原因");
        }

        CreatorEvalResultRecord record = new CreatorEvalResultRecord();
        record.setResultId(UUID.randomUUID().toString());
        record.setCaseId(caseRecord.getCaseId());
        record.setTaskId(resolveTaskId(caseRecord.getTaskId(), request.taskId()));
        record.setWorkflowSessionId(TextUtil.trimToNull(request.workflowSessionId()));
        record.setTargetStage(targetStage);
        record.setModelName(TextUtil.trimToNull(request.modelName()));
        record.setPromptVersion(TextUtil.trimToNull(request.promptVersion()));
        record.setPromptSnapshot(TextUtil.trimToNull(request.promptSnapshot()));
        record.setPromptHash(resolvePromptHash(request.promptHash(), record.getPromptSnapshot()));
        record.setOutputSummary(TextUtil.trimToNull(request.outputSummary()));
        record.setRawOutput(rawOutput == null ? failureReason : rawOutput);
        record.setRunStatus(failureReason == null ? RUN_STATUS_SUCCESS : RUN_STATUS_FAILED);
        record.setParseStatus(resolveParseStatus(rawOutput));
        Integer promptTokens = normalizeTokenCount(request.promptTokens());
        Integer completionTokens = normalizeTokenCount(request.completionTokens());
        Integer totalTokens = normalizeTokenCount(request.totalTokens());
        record.setElapsedMs(request.elapsedMs());
        record.setPromptTokens(promptTokens);
        record.setCompletionTokens(completionTokens);
        record.setTotalTokens(resolveTotalTokens(totalTokens, promptTokens, completionTokens));
        record.setFailureReason(failureReason);
        record.setReadabilityScore(request.readabilityScore());
        record.setRelevanceScore(request.relevanceScore());
        record.setCompletenessScore(request.completenessScore());
        record.setAccuracyScore(request.accuracyScore());
        record.setStabilityScore(request.stabilityScore());
        record.setCostScore(request.costScore());
        record.setExplainabilityScore(request.explainabilityScore());
        record.setReviewerNote(TextUtil.trimToNull(request.reviewerNote()));
        creatorEvaluationMapper.insertResult(record);
        return getResult(record.getResultId());
    }

    public List<CreatorEvalResultResponse> listResults(String caseId, Integer limit) {
        getCaseRecord(caseId);
        int safeLimit = NumberUtil.limitOrDefault(limit, DEFAULT_LIMIT, MAX_LIMIT);
        return creatorEvaluationMapper.listResultsByCaseId(caseId.trim(), safeLimit)
                .stream()
                .map(this::toResultResponse)
                .toList();
    }

    public CreatorEvalResultResponse getResult(String resultId) {
        CreatorEvalResultRecord record = creatorEvaluationMapper.findResultByResultId(normalizeId(resultId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "评测结果不存在"));
        return toResultResponse(record);
    }

    public List<CreatorEvalPromptVersionStatsResponse> comparePromptVersions(String caseId) {
        getCaseRecord(caseId);
        List<CreatorEvalResultRecord> records = creatorEvaluationMapper.listAllResultsByCaseIdForStats(caseId.trim());
        Map<String, List<CreatorEvalResultRecord>> groupedRecords = new LinkedHashMap<>();
        for (CreatorEvalResultRecord record : records) {
            String promptVersion = TextUtil.trimToDefault(record.getPromptVersion(), "UNVERSIONED");
            groupedRecords.computeIfAbsent(promptVersion, key -> new ArrayList<>()).add(record);
        }
        return groupedRecords.entrySet()
                .stream()
                .map(entry -> toPromptVersionStats(caseId.trim(), entry.getKey(), entry.getValue()))
                .toList();
    }

    private CreatorEvalCaseRecord getCaseRecord(String caseId) {
        return creatorEvaluationMapper.findCaseByCaseId(normalizeId(caseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "评测用例不存在"));
    }

    private String normalizeStage(String stage) {
        String normalized = TextUtil.trimToNull(stage);
        if (!isValidStage(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评测阶段只能是 PRE_PUBLISH、FEEDBACK 或 REPORT");
        }
        return normalized;
    }

    private String normalizeOptionalStage(String stage) {
        String normalized = TextUtil.trimToNull(stage);
        if (normalized == null) {
            return null;
        }
        if (!isValidStage(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评测阶段只能是 PRE_PUBLISH、FEEDBACK 或 REPORT");
        }
        return normalized;
    }

    private boolean isValidStage(String stage) {
        return "PRE_PUBLISH".equals(stage) || "FEEDBACK".equals(stage) || "REPORT".equals(stage);
    }

    private String resolveTaskId(String caseTaskId, String requestTaskId) {
        if (TextUtil.hasText(requestTaskId)) {
            return requestTaskId.trim();
        }
        return TextUtil.trimToNull(caseTaskId);
    }

    private Integer resolveTotalTokens(Integer totalTokens, Integer promptTokens, Integer completionTokens) {
        if (totalTokens != null) {
            return totalTokens;
        }
        if (promptTokens == null || completionTokens == null) {
            return null;
        }
        // 只有输入和输出 token 都明确时才推导总量，避免把缺失的一侧当成 0 影响成本统计。
        return promptTokens + completionTokens;
    }

    private Integer normalizeTokenCount(Integer tokenCount) {
        // token 为 0 时更可能代表“未采集到 usage”，统一转成 null，避免统计层把未知用量当成零成本。
        return tokenCount == null || tokenCount <= 0 ? null : tokenCount;
    }

    private String resolveParseStatus(String rawOutput) {
        if (TextUtil.isBlank(rawOutput)) {
            return PARSE_STATUS_RAW_ONLY;
        }
        String normalized = rawOutput.trim();
        if (normalized.startsWith("{") && normalized.endsWith("}")) {
            return PARSE_STATUS_PARSED;
        }
        return PARSE_STATUS_RAW_ONLY;
    }

    private CreatorEvalPromptVersionStatsResponse toPromptVersionStats(String caseId,
                                                                       String promptVersion,
                                                                       List<CreatorEvalResultRecord> records) {
        int successCount = 0;
        String latestPromptHash = null;
        LocalDateTime latestUpdateTime = null;
        int scoreSampleCount = 0;
        int fullScoreCount = 0;
        double scoreTotal = 0;
        List<Double> scoreSamples = new ArrayList<>();
        double readabilityTotal = 0;
        int readabilityCount = 0;
        double relevanceTotal = 0;
        int relevanceCount = 0;
        double completenessTotal = 0;
        int completenessCount = 0;
        double accuracyTotal = 0;
        int accuracyCount = 0;
        double stabilityTotal = 0;
        int stabilityCount = 0;
        double costTotal = 0;
        int costCount = 0;
        double explainabilityTotal = 0;
        int explainabilityCount = 0;
        long promptTokenTotal = 0;
        int promptTokenCount = 0;
        long completionTokenTotal = 0;
        int completionTokenCount = 0;
        long totalTokenTotal = 0;
        int totalTokenCount = 0;
        double elapsedTotal = 0;
        int elapsedCount = 0;
        for (CreatorEvalResultRecord record : records) {
            if (RUN_STATUS_SUCCESS.equals(record.getRunStatus())) {
                successCount++;
            }
            if (latestUpdateTime == null || isAfter(record.getUpdateTime(), latestUpdateTime)) {
                latestUpdateTime = record.getUpdateTime();
                latestPromptHash = record.getPromptHash();
            }
            Double recordScore = recordAverageScore(record);
            if (recordScore != null) {
                scoreSamples.add(recordScore);
                scoreTotal += recordScore;
                scoreSampleCount++;
            }
            if (hasAllScoreDimensions(record)) {
                fullScoreCount++;
            }
            promptTokenTotal = addInteger(promptTokenTotal, record.getPromptTokens());
            promptTokenCount += record.getPromptTokens() == null ? 0 : 1;
            completionTokenTotal = addInteger(completionTokenTotal, record.getCompletionTokens());
            completionTokenCount += record.getCompletionTokens() == null ? 0 : 1;
            totalTokenTotal = addInteger(totalTokenTotal, record.getTotalTokens());
            totalTokenCount += record.getTotalTokens() == null ? 0 : 1;
            elapsedTotal = addLong(elapsedTotal, record.getElapsedMs());
            elapsedCount += record.getElapsedMs() == null ? 0 : 1;
            readabilityTotal = addInteger(readabilityTotal, record.getReadabilityScore());
            readabilityCount += record.getReadabilityScore() == null ? 0 : 1;
            relevanceTotal = addInteger(relevanceTotal, record.getRelevanceScore());
            relevanceCount += record.getRelevanceScore() == null ? 0 : 1;
            completenessTotal = addInteger(completenessTotal, record.getCompletenessScore());
            completenessCount += record.getCompletenessScore() == null ? 0 : 1;
            accuracyTotal = addInteger(accuracyTotal, record.getAccuracyScore());
            accuracyCount += record.getAccuracyScore() == null ? 0 : 1;
            stabilityTotal = addInteger(stabilityTotal, record.getStabilityScore());
            stabilityCount += record.getStabilityScore() == null ? 0 : 1;
            costTotal = addInteger(costTotal, record.getCostScore());
            costCount += record.getCostScore() == null ? 0 : 1;
            explainabilityTotal = addInteger(explainabilityTotal, record.getExplainabilityScore());
            explainabilityCount += record.getExplainabilityScore() == null ? 0 : 1;
        }
        return new CreatorEvalPromptVersionStatsResponse(
                caseId,
                promptVersion,
                latestPromptHash,
                records.size(),
                successCount,
                percent(successCount, records.size()),
                scoreSampleCount,
                scoreSampleCount == 0 ? null : roundOneDecimal(scoreTotal / scoreSampleCount),
                scoreStandardDeviation(scoreSamples),
                averageValue(readabilityTotal, readabilityCount),
                averageValue(relevanceTotal, relevanceCount),
                averageValue(completenessTotal, completenessCount),
                averageValue(accuracyTotal, accuracyCount),
                averageValue(stabilityTotal, stabilityCount),
                averageValue(costTotal, costCount),
                averageValue(explainabilityTotal, explainabilityCount),
                promptTokenCount == 0 ? null : promptTokenTotal,
                completionTokenCount == 0 ? null : completionTokenTotal,
                totalTokenCount == 0 ? null : totalTokenTotal,
                averageValue(promptTokenTotal, promptTokenCount),
                averageValue(completionTokenTotal, completionTokenCount),
                averageValue(totalTokenTotal, totalTokenCount),
                averageValue(elapsedTotal, elapsedCount),
                percent(fullScoreCount, records.size()),
                latestUpdateTime
        );
    }

    private boolean isAfter(LocalDateTime candidate, LocalDateTime current) {
        return candidate != null && (current == null || candidate.isAfter(current));
    }

    private Double recordAverageScore(CreatorEvalResultRecord record) {
        Integer[] scores = {
                record.getReadabilityScore(),
                record.getRelevanceScore(),
                record.getCompletenessScore(),
                record.getAccuracyScore(),
                record.getStabilityScore(),
                record.getCostScore(),
                record.getExplainabilityScore()
        };
        int scoreSum = 0;
        int scoreCount = 0;
        for (Integer score : scores) {
            if (score != null) {
                scoreSum += score;
                scoreCount++;
            }
        }
        return scoreCount == 0 ? null : (double) scoreSum / scoreCount;
    }

    private boolean hasAllScoreDimensions(CreatorEvalResultRecord record) {
        return record.getReadabilityScore() != null
                && record.getRelevanceScore() != null
                && record.getCompletenessScore() != null
                && record.getAccuracyScore() != null
                && record.getStabilityScore() != null
                && record.getCostScore() != null
                && record.getExplainabilityScore() != null;
    }

    private double addInteger(double total, Integer value) {
        return value == null ? total : total + value;
    }

    private long addInteger(long total, Integer value) {
        return value == null ? total : total + value;
    }

    private double addLong(double total, Long value) {
        return value == null ? total : total + value;
    }

    private Double averageValue(double total, int count) {
        return count == 0 ? null : roundOneDecimal(total / count);
    }

    private Double percent(int numerator, int denominator) {
        if (denominator <= 0) {
            return null;
        }
        return roundOneDecimal((double) numerator * 100.0 / denominator);
    }

    private Double scoreStandardDeviation(List<Double> scores) {
        if (scores.isEmpty()) {
            return null;
        }
        double average = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = scores.stream()
                .mapToDouble(score -> Math.pow(score - average, 2))
                .average()
                .orElse(0);
        return roundOneDecimal(Math.sqrt(variance));
    }

    private Double roundOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private String resolvePromptHash(String requestPromptHash, String promptSnapshot) {
        String safeRequestHash = TextUtil.trimToNull(requestPromptHash);
        if (safeRequestHash != null) {
            return safeRequestHash.toLowerCase();
        }
        if (promptSnapshot == null) {
            return null;
        }
        // 记录快照哈希，是为了后续即使隐藏长 Prompt，也能判断两次评测是否真的用了同一份提示词。
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(promptSnapshot.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashBytes.length * 2);
            for (byte hashByte : hashBytes) {
                builder.append(String.format("%02x", hashByte));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "当前 JDK 不支持 SHA-256 哈希计算");
        }
    }

    private String normalizeId(String value) {
        if (TextUtil.isBlank(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID不能为空");
        }
        return value.trim();
    }

    private CreatorEvalCaseResponse toCaseResponse(CreatorEvalCaseRecord record) {
        return new CreatorEvalCaseResponse(
                record.getId(),
                record.getCaseId(),
                record.getUserId(),
                record.getCaseName(),
                record.getTargetStage(),
                record.getTaskId(),
                record.getInputSnapshot(),
                record.getExpectedPoints(),
                record.getScoringRubric(),
                record.getStatus(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }

    private CreatorEvalResultResponse toResultResponse(CreatorEvalResultRecord record) {
        return new CreatorEvalResultResponse(
                record.getId(),
                record.getResultId(),
                record.getCaseId(),
                record.getTaskId(),
                record.getWorkflowSessionId(),
                record.getTargetStage(),
                record.getModelName(),
                record.getPromptVersion(),
                record.getPromptHash(),
                record.getPromptSnapshot(),
                record.getOutputSummary(),
                record.getRawOutput(),
                record.getRunStatus(),
                record.getParseStatus(),
                record.getElapsedMs(),
                record.getPromptTokens(),
                record.getCompletionTokens(),
                record.getTotalTokens(),
                record.getFailureReason(),
                record.getReadabilityScore(),
                record.getRelevanceScore(),
                record.getCompletenessScore(),
                record.getAccuracyScore(),
                record.getStabilityScore(),
                record.getCostScore(),
                record.getExplainabilityScore(),
                record.getReviewerNote(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
