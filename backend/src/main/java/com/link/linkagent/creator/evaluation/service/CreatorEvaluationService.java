package com.link.linkagent.creator.evaluation.service;

import com.link.linkagent.creator.evaluation.mapper.CreatorEvaluationMapper;
import com.link.linkagent.creator.evaluation.model.CreatorEvalCaseCreateRequest;
import com.link.linkagent.creator.evaluation.model.CreatorEvalCaseRecord;
import com.link.linkagent.creator.evaluation.model.CreatorEvalCaseResponse;
import com.link.linkagent.creator.evaluation.model.CreatorEvalResultCreateRequest;
import com.link.linkagent.creator.evaluation.model.CreatorEvalResultRecord;
import com.link.linkagent.creator.evaluation.model.CreatorEvalResultResponse;
import com.link.linkagent.util.NumberUtil;
import com.link.linkagent.util.TextUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
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
        record.setOutputSummary(TextUtil.trimToNull(request.outputSummary()));
        record.setRawOutput(rawOutput == null ? failureReason : rawOutput);
        record.setRunStatus(failureReason == null ? RUN_STATUS_SUCCESS : RUN_STATUS_FAILED);
        record.setParseStatus(resolveParseStatus(rawOutput));
        record.setElapsedMs(request.elapsedMs());
        record.setPromptTokens(request.promptTokens());
        record.setCompletionTokens(request.completionTokens());
        record.setTotalTokens(resolveTotalTokens(request.totalTokens(), request.promptTokens(), request.completionTokens()));
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
        if (promptTokens == null && completionTokens == null) {
            return null;
        }
        int safePromptTokens = promptTokens == null ? 0 : promptTokens;
        int safeCompletionTokens = completionTokens == null ? 0 : completionTokens;
        return safePromptTokens + safeCompletionTokens;
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
