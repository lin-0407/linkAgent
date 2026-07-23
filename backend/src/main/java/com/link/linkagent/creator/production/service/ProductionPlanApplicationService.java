package com.link.linkagent.creator.production.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.production.mapper.ProductionPlanMapper;
import com.link.linkagent.creator.production.model.CreateProductionPlanRequest;
import com.link.linkagent.creator.production.model.ProductionBlueprintOutput;
import com.link.linkagent.creator.production.model.ProductionBlueprintStepOutput;
import com.link.linkagent.creator.production.model.ProductionPlanRecord;
import com.link.linkagent.creator.production.model.ProductionPlanResponse;
import com.link.linkagent.creator.production.model.ProductionPlanStatus;
import com.link.linkagent.creator.production.model.ProductionStepRecord;
import com.link.linkagent.creator.production.model.ProductionStepResponse;
import com.link.linkagent.creator.production.model.ProductionStepStatus;
import com.link.linkagent.creator.production.model.ProductionWorkspaceResponse;
import com.link.linkagent.creator.production.model.ToolResolutionResponse;
import com.link.linkagent.creator.production.model.ToolVerificationStatus;
import com.link.linkagent.creator.production.model.UpdateProductionStepRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** P0-1 制作蓝图应用服务，编排门禁、工具解析、模型生成和恢复查询。 */
@Service
public class ProductionPlanApplicationService {

    private static final String DEFAULT_OWNER_ID = "default";

    private final ProductionPlanMapper mapper;
    private final ProductionPlanGateService gateService;
    private final ToolRecommendationService toolRecommendationService;
    private final ProductionBlueprintGenerator blueprintGenerator;
    private final ProductionPlanPersistenceService persistenceService;
    private final ObjectMapper objectMapper;

    public ProductionPlanApplicationService(ProductionPlanMapper mapper,
                                            ProductionPlanGateService gateService,
                                            ToolRecommendationService toolRecommendationService,
                                            ProductionBlueprintGenerator blueprintGenerator,
                                            ProductionPlanPersistenceService persistenceService,
                                            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.gateService = gateService;
        this.toolRecommendationService = toolRecommendationService;
        this.blueprintGenerator = blueprintGenerator;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
    }

    public ProductionWorkspaceResponse getCurrent(String taskId) {
        String normalizedTaskId = normalizeTaskId(taskId);
        ensureTask(normalizedTaskId);
        return mapper.findCurrentPlan(normalizedTaskId, DEFAULT_OWNER_ID)
                .map(this::toWorkspace)
                .orElseGet(() -> new ProductionWorkspaceResponse(null, List.of(), List.of(), false));
    }

    public ProductionWorkspaceResponse create(String taskId,
                                              CreateProductionPlanRequest request,
                                              String idempotencyKey) {
        String normalizedTaskId = normalizeTaskId(taskId);
        ensureTask(normalizedTaskId);
        gateService.ensurePrePublishConfirmed(normalizedTaskId, DEFAULT_OWNER_ID, "制作蓝图");
        String normalizedKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? UUID.randomUUID().toString()
                : idempotencyKey.trim();
        ProductionPlanRecord current = mapper.findCurrentPlan(normalizedTaskId, DEFAULT_OWNER_ID).orElse(null);
        if (current != null && normalizedKey.equals(current.idempotencyKey())
                && !ProductionPlanStatus.STALE.name().equals(current.status())) {
            return toWorkspace(current);
        }
        int nextVersion = mapper.findMaxPlanVersion(normalizedTaskId, DEFAULT_OWNER_ID) + 1;
        String planId = UUID.randomUUID().toString();
        ProductionPlanRecord generating = new ProductionPlanRecord(
                null,
                planId,
                normalizedTaskId,
                DEFAULT_OWNER_ID,
                nextVersion,
                request.videoCategory().name(),
                request.productionMethod().name(),
                request.targetAudience().trim(),
                request.corePromise().trim(),
                request.targetDurationSeconds() == null ? null : request.targetDurationSeconds() * 1000L,
                writeJson(request.availableAssets() == null ? List.of() : request.availableAssets()),
                request.constraints(),
                writeJson(request.preferredTools() == null ? List.of() : request.preferredTools()),
                null,
                null,
                null,
                ProductionPlanStatus.GENERATING.name(),
                null,
                null,
                normalizedKey,
                null,
                null
        );
        persistenceService.startGenerating(generating);
        try {
            List<ToolResolutionResponse> tools = toolRecommendationService.resolve(
                    request.videoCategory(), request.preferredTools());
            ProductionBlueprintGenerator.GenerationResult generated = blueprintGenerator.generate(
                    normalizedTaskId, request, tools);
            List<ProductionStepRecord> steps = toStepRecords(planId, normalizedTaskId, generated.output(), tools);
            persistenceService.markReady(
                    planId,
                    generated.output().planTitle(),
                    generated.output().positioningSummary(),
                    writeJson(tools),
                    generated.sourceSnapshot(),
                    writeJson(generated.output()),
                    generated.promptKey(),
                    steps
            );
        } catch (RuntimeException exception) {
            persistenceService.markFailed(planId, abbreviate(exception.getMessage()));
            if (exception instanceof ResponseStatusException responseStatusException) {
                throw responseStatusException;
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "制作蓝图生成失败，请稍后重试", exception);
        }
        return mapper.findPlan(normalizedTaskId, DEFAULT_OWNER_ID, planId)
                .map(this::toWorkspace)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "制作蓝图生成后读取失败"));
    }

    public ProductionWorkspaceResponse updateStep(String taskId,
                                                  String planId,
                                                  String stepId,
                                                  UpdateProductionStepRequest request) {
        String normalizedTaskId = normalizeTaskId(taskId);
        ProductionPlanRecord plan = mapper.findCurrentPlan(normalizedTaskId, DEFAULT_OWNER_ID)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "制作蓝图不存在"));
        if (!plan.planId().equals(planId.trim())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "制作蓝图不存在或已经更新");
        }
        if (!ProductionPlanStatus.READY.name().equals(plan.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "制作蓝图尚未生成完成");
        }
        if (request.status() == ProductionStepStatus.SKIPPED && (request.skipReason() == null || request.skipReason().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "跳过步骤必须填写原因");
        }
        String skipReason = request.status() == ProductionStepStatus.SKIPPED ? request.skipReason().trim() : null;
        try {
            persistenceService.updateStep(normalizedTaskId, plan.planId(), stepId.trim(),
                    request.status().name(), skipReason, request.rowVersion());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
        return mapper.findPlan(normalizedTaskId, DEFAULT_OWNER_ID, plan.planId())
                .map(this::toWorkspace)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "制作蓝图不存在"));
    }

    private List<ProductionStepRecord> toStepRecords(String planId,
                                                     String taskId,
                                                     ProductionBlueprintOutput output,
                                                     List<ToolResolutionResponse> tools) {
        Map<String, ToolResolutionResponse> toolMap = new HashMap<>();
        for (ToolResolutionResponse tool : tools) {
            toolMap.put(ToolRecommendationService.normalizeName(tool.toolName()), tool);
        }
        List<ProductionStepRecord> records = new ArrayList<>();
        int sequence = 1;
        for (ProductionBlueprintStepOutput step : output.steps()) {
            List<ToolResolutionResponse> refs = step.toolNames() == null ? List.of() : step.toolNames().stream()
                    .map(name -> toolMap.getOrDefault(
                            ToolRecommendationService.normalizeName(name),
                            sourceRequiredTool(name)))
                    .toList();
            records.add(new ProductionStepRecord(
                    null,
                    UUID.randomUUID().toString(),
                    planId,
                    taskId,
                    sequence++,
                    step.phase(),
                    step.stepName(),
                    step.objective(),
                    writeJson(step.prerequisites()),
                    writeJson(step.operations()),
                    writeJson(refs),
                    writeJson(step.expectedOutputs()),
                    writeJson(step.acceptanceCriteria()),
                    step.difficulty(),
                    step.required() == null || step.required(),
                    ProductionStepStatus.PENDING.name(),
                    0L,
                    null,
                    null,
                    null
            ));
        }
        return records;
    }

    private ToolResolutionResponse sourceRequiredTool(String toolName) {
        return new ToolResolutionResponse(
                null,
                toolName == null || toolName.isBlank() ? "未命名工具" : toolName.trim(),
                null,
                null,
                ToolVerificationStatus.SOURCE_REQUIRED.name(),
                List.of(),
                List.of(),
                List.of(),
                "蓝图引用了未进入本次可信工具解析结果的工具，请补充官方资料"
        );
    }

    private ProductionWorkspaceResponse toWorkspace(ProductionPlanRecord plan) {
        List<ProductionStepRecord> records = mapper.listSteps(plan.taskId(), plan.planId());
        List<ToolResolutionResponse> tools = readList(plan.toolPreferences(), new TypeReference<>() { });
        boolean readyForMedia = ProductionPlanStatus.READY.name().equals(plan.status())
                && mapper.countIncompleteSteps(plan.taskId(), plan.planId()) == 0;
        return new ProductionWorkspaceResponse(
                new ProductionPlanResponse(
                        plan.planId(), plan.taskId(), plan.planVersion(), plan.videoCategory(), plan.productionMethod(),
                        plan.targetAudience(), plan.corePromise(), plan.targetDurationMs(),
                        readList(plan.availableAssets(), new TypeReference<>() { }), plan.constraintsJson(), plan.status(),
                        plan.planTitle(), plan.positioningSummary(), plan.createTime(), plan.updateTime()),
                records.stream().map(this::toStepResponse).toList(),
                tools,
                readyForMedia
        );
    }

    private ProductionStepResponse toStepResponse(ProductionStepRecord record) {
        return new ProductionStepResponse(
                record.stepId(), record.sequenceNo(), record.phase(), record.stepName(), record.objective(),
                readList(record.prerequisites(), new TypeReference<>() { }),
                readList(record.operationsJson(), new TypeReference<>() { }),
                readList(record.toolRefs(), new TypeReference<>() { }),
                readList(record.expectedOutputs(), new TypeReference<>() { }),
                readList(record.acceptanceCriteria(), new TypeReference<>() { }),
                record.difficulty(), record.requiredFlag(), record.status(), record.rowVersion(), record.skipReason()
        );
    }

    private void ensureTask(String taskId) {
        if (mapper.countTaskByOwner(taskId, DEFAULT_OWNER_ID) != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在");
        }
    }

    private String normalizeTaskId(String taskId) {
        if (taskId == null || taskId.isBlank() || taskId.trim().length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务ID不能为空或过长");
        }
        return taskId.trim();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("制作蓝图数据序列化失败", exception);
        }
    }

    private <T> List<T> readList(String value, TypeReference<List<T>> type) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "模型或官方资料服务返回未知错误";
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }
}
