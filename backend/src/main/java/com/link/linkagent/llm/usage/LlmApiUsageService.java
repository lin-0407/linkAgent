package com.link.linkagent.llm.usage;

import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 模型 API 开销统计服务。
 * 统计链路必须“尽力而为”：写统计失败只能记录日志，不能让创作分析、向量检索或 rerank 因统计表异常而失败。
 */
@Service
public class LlmApiUsageService {

    private static final Logger log = LoggerFactory.getLogger(LlmApiUsageService.class);

    private static final int ERROR_MESSAGE_MAX_LENGTH = 480;

    private final LlmApiUsageMapper llmApiUsageMapper;

    public LlmApiUsageService(LlmApiUsageMapper llmApiUsageMapper) {
        this.llmApiUsageMapper = llmApiUsageMapper;
    }

    public void recordTextSuccess(String modelName,
                                  Integer promptTokens,
                                  Integer completionTokens,
                                  Integer totalTokens,
                                  Long elapsedMs) {
        recordWithErrorMessage(LlmApiModelCategory.TEXT, modelName, promptTokens, completionTokens, totalTokens,
                elapsedMs, LlmApiCallStatus.SUCCESS, null, null);
    }

    public void recordTextFailure(Long elapsedMs, Exception exception) {
        recordWithException(LlmApiModelCategory.TEXT, null, null, null, null,
                elapsedMs, LlmApiCallStatus.FAILED, exception, null);
    }

    public void recordEmbeddingSuccess(String modelName,
                                       Integer promptTokens,
                                       Integer totalTokens,
                                       Long elapsedMs,
                                       Integer inputCount) {
        recordWithErrorMessage(LlmApiModelCategory.EMBEDDING, modelName, promptTokens, null, totalTokens,
                elapsedMs, LlmApiCallStatus.SUCCESS, null, inputCount);
    }

    public void recordEmbeddingFailure(String modelName,
                                       Long elapsedMs,
                                       Exception exception,
                                       Integer inputCount) {
        recordWithException(LlmApiModelCategory.EMBEDDING, modelName, null, null, null,
                elapsedMs, LlmApiCallStatus.FAILED, exception, inputCount);
    }

    public void recordRerankSuccess(String modelName,
                                    Integer promptTokens,
                                    Integer totalTokens,
                                    Long elapsedMs,
                                    Integer inputCount) {
        recordWithErrorMessage(LlmApiModelCategory.RERANK, modelName, promptTokens, null, totalTokens,
                elapsedMs, LlmApiCallStatus.SUCCESS, null, inputCount);
    }

    public void recordRerankSkipped(String modelName, String reason, Integer inputCount) {
        recordWithErrorMessage(LlmApiModelCategory.RERANK, modelName, null, null, null,
                0L, LlmApiCallStatus.SKIPPED, reason, inputCount);
    }

    public void recordRerankFailure(String modelName,
                                    Long elapsedMs,
                                    Exception exception,
                                    Integer inputCount) {
        recordWithException(LlmApiModelCategory.RERANK, modelName, null, null, null,
                elapsedMs, LlmApiCallStatus.FAILED, exception, inputCount);
    }

    public LlmApiUsageSummaryResponse summarizeTask(String taskId) {
        List<LlmApiUsageCategorySummary> categories = llmApiUsageMapper.summarizeByTaskId(taskId);
        long callCount = 0;
        long successCount = 0;
        long failedCount = 0;
        long skippedCount = 0;
        Long totalTokens = null;
        Long totalElapsedMs = null;
        for (LlmApiUsageCategorySummary category : categories) {
            callCount += category.getCallCount();
            successCount += category.getSuccessCount();
            failedCount += category.getFailedCount();
            skippedCount += category.getSkippedCount();
            totalTokens = addNullable(totalTokens, category.getTotalTokens());
            totalElapsedMs = addNullable(totalElapsedMs, category.getTotalElapsedMs());
        }
        Long averageElapsedMs = callCount == 0 || totalElapsedMs == null ? null : Math.round((double) totalElapsedMs / callCount);
        return new LlmApiUsageSummaryResponse(
                taskId,
                callCount,
                successCount,
                failedCount,
                skippedCount,
                totalTokens,
                totalElapsedMs,
                averageElapsedMs,
                categories
        );
    }

    public LlmApiCallPageResponse listTaskCalls(String taskId, String modelCategory, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(Math.max(1, pageSize), 100);
        int offset = (safePage - 1) * safePageSize;
        String normalizedCategory = normalizeModelCategory(modelCategory);
        long total = llmApiUsageMapper.countCallsByTaskId(taskId, normalizedCategory);
        List<LlmApiCallRecord> items = llmApiUsageMapper.listCallsByTaskId(taskId, normalizedCategory, safePageSize, offset);
        return new LlmApiCallPageResponse(taskId, safePage, safePageSize, total, items);
    }

    public LlmApiCallLogPageResponse listCalls(LocalDateTime startTime,
                                               LocalDateTime endTime,
                                               String modelName,
                                               String scene,
                                               String modelCategory,
                                               String status,
                                               int page,
                                               int pageSize) {
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "开始时间不能晚于结束时间");
        }
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(Math.max(1, pageSize), 100);
        int offset = (safePage - 1) * safePageSize;
        String normalizedModelName = TextUtil.trimToNull(modelName);
        String normalizedScene = TextUtil.trimToNull(scene);
        String normalizedCategory = normalizeModelCategory(modelCategory);
        String normalizedStatus = normalizeStatus(status);
        LlmApiCallLogSummary summary = llmApiUsageMapper.summarizeCalls(
                startTime,
                endTime,
                normalizedModelName,
                normalizedScene,
                normalizedCategory,
                normalizedStatus
        );
        List<LlmApiCallRecord> items = llmApiUsageMapper.listCalls(
                startTime,
                endTime,
                normalizedModelName,
                normalizedScene,
                normalizedCategory,
                normalizedStatus,
                safePageSize,
                offset
        );
        Long averageElapsedMs = summary.getCallCount() == 0 || summary.getTotalElapsedMs() == null
                ? null
                : Math.round((double) summary.getTotalElapsedMs() / summary.getCallCount());
        LlmApiCallLogSummaryResponse summaryResponse = new LlmApiCallLogSummaryResponse(
                summary.getCallCount(),
                summary.getSuccessCount(),
                summary.getFailedCount(),
                summary.getSkippedCount(),
                summary.getTotalTokens(),
                summary.getPromptTokens(),
                summary.getCompletionTokens(),
                summary.getTotalElapsedMs(),
                averageElapsedMs
        );
        return new LlmApiCallLogPageResponse(
                safePage,
                safePageSize,
                summary.getCallCount(),
                summaryResponse,
                items
        );
    }

    public WorkflowUsageResponse summarizeWorkflowSession(String taskId, String sessionId) {
        List<LlmApiCallRecord> calls = llmApiUsageMapper.listCallsByWorkflowSession(taskId, sessionId);
        long successCalls = 0;
        long failedCalls = 0;
        long skippedCalls = 0;
        Long totalTokens = null;
        Long totalElapsedMs = null;
        Map<String, WorkflowStepAccumulator> stepMap = new LinkedHashMap<>();
        for (LlmApiCallRecord call : calls) {
            if (LlmApiCallStatus.SUCCESS.name().equals(call.getStatus())) {
                successCalls++;
            } else if (LlmApiCallStatus.FAILED.name().equals(call.getStatus())) {
                failedCalls++;
            } else if (LlmApiCallStatus.SKIPPED.name().equals(call.getStatus())) {
                skippedCalls++;
            }
            totalTokens = addNullable(totalTokens, longValue(call.getTotalTokens()));
            totalElapsedMs = addNullable(totalElapsedMs, call.getElapsedMs());

            String stepKey = TextUtil.trimToDefault(call.getWorkflowStepId(), "UNKNOWN_STEP");
            WorkflowStepAccumulator accumulator = stepMap.computeIfAbsent(stepKey, ignored -> new WorkflowStepAccumulator(
                    stepKey,
                    TextUtil.trimToDefault(call.getWorkflowStepName(), "未归属步骤"),
                    TextUtil.trimToDefault(call.getWorkflowStage(), null)
            ));
            accumulator.calls().add(call);
        }

        List<WorkflowStepUsageResponse> steps = stepMap.values()
                .stream()
                .map(item -> new WorkflowStepUsageResponse(item.stepId(), item.stepName(), item.stage(), item.calls()))
                .toList();
        return new WorkflowUsageResponse(
                taskId,
                sessionId,
                calls.size(),
                successCalls,
                failedCalls,
                skippedCalls,
                totalTokens,
                totalElapsedMs,
                steps
        );
    }

    private void recordWithException(LlmApiModelCategory modelCategory,
                                     String modelName,
                                     Integer promptTokens,
                                     Integer completionTokens,
                                     Integer totalTokens,
                                     Long elapsedMs,
                                     LlmApiCallStatus status,
                                     Exception exception,
                                     Integer inputCount) {
        recordWithErrorMessage(modelCategory, modelName, promptTokens, completionTokens, totalTokens,
                elapsedMs, status, TextUtil.normalizeExceptionMessage(exception), inputCount);
    }

    private void recordWithErrorMessage(LlmApiModelCategory modelCategory,
                                        String modelName,
                                        Integer promptTokens,
                                        Integer completionTokens,
                                        Integer totalTokens,
                                        Long elapsedMs,
                                        LlmApiCallStatus status,
                                        String errorMessage,
                                        Integer inputCount) {
        try {
            LlmApiCallRecord record = new LlmApiCallRecord();
            LlmUsageContext context = LlmUsageContext.current();
            record.setCallId(UUID.randomUUID().toString());
            record.setTaskId(context == null ? null : context.taskId());
            record.setTraceId(context == null ? null : context.traceId());
            record.setRequestId(context == null ? null : context.requestId());
            record.setWorkflowSessionId(context == null ? null : context.workflowSessionId());
            record.setWorkflowStepId(context == null ? null : context.workflowStepId());
            record.setWorkflowStepName(context == null ? null : context.workflowStepName());
            record.setWorkflowStage(context == null ? null : context.workflowStage());
            record.setScene(context == null ? null : context.scene());
            record.setModelCategory(modelCategory.name());
            record.setModelName(TextUtil.trimToNull(modelName));
            record.setPromptTokens(normalizeTokenCount(promptTokens));
            record.setCompletionTokens(normalizeTokenCount(completionTokens));
            record.setTotalTokens(normalizeTokenCount(totalTokens));
            record.setElapsedMs(normalizeElapsedMs(elapsedMs));
            record.setStatus(status.name());
            record.setErrorMessage(trimToMax(errorMessage, ERROR_MESSAGE_MAX_LENGTH));
            record.setInputCount(inputCount == null || inputCount < 0 ? null : inputCount);
            llmApiUsageMapper.insert(record);
        } catch (Exception exception) {
            // 统计表属于可观测辅助能力，不能因为写统计失败影响主链路。
            log.warn("模型 API 开销统计写入失败，已忽略。category={}, status={}", modelCategory, status, exception);
        }
    }

    private String normalizeModelCategory(String modelCategory) {
        if (TextUtil.isBlank(modelCategory)) {
            return null;
        }
        String normalized = modelCategory.trim().toUpperCase();
        for (LlmApiModelCategory category : LlmApiModelCategory.values()) {
            if (category.name().equals(normalized)) {
                return normalized;
            }
        }
        return null;
    }

    private String normalizeStatus(String status) {
        if (TextUtil.isBlank(status)) {
            return null;
        }
        String normalized = status.trim().toUpperCase();
        for (LlmApiCallStatus callStatus : LlmApiCallStatus.values()) {
            if (callStatus.name().equals(normalized)) {
                return normalized;
            }
        }
        return null;
    }

    private Integer normalizeTokenCount(Integer tokenCount) {
        return tokenCount == null || tokenCount <= 0 ? null : tokenCount;
    }

    private Long normalizeElapsedMs(Long elapsedMs) {
        return elapsedMs == null || elapsedMs < 0 ? null : elapsedMs;
    }

    private String trimToMax(String value, int maxLength) {
        String trimmed = TextUtil.trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        return TextUtil.abbreviateWithSuffix(trimmed, maxLength, "...");
    }

    private Long addNullable(Long left, Long right) {
        if (right == null) {
            return left;
        }
        return left == null ? right : left + right;
    }

    private Long longValue(Integer value) {
        return value == null ? null : value.longValue();
    }

    private record WorkflowStepAccumulator(
            String stepId,
            String stepName,
            String stage,
            List<LlmApiCallRecord> calls
    ) {

        private WorkflowStepAccumulator(String stepId, String stepName, String stage) {
            this(stepId, stepName, stage, new ArrayList<>());
        }
    }
}
