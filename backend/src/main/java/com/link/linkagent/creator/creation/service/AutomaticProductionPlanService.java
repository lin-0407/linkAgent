package com.link.linkagent.creator.creation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.mapper.*;
import com.link.linkagent.creator.creation.model.*;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.Run;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.production.mapper.ProductionPlanMapper;
import com.link.linkagent.creator.production.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import static com.link.linkagent.creator.creation.service.CreationInputRules.*;

/** 专属自动蓝图与整片受理原子提交，制作步骤只由实际完成证据推进。 */
@Service
@Transactional
public class AutomaticProductionPlanService {
    private record Definition(String code, String phase, String title) { }
    private static final List<Definition> DEFINITIONS = List.of(
            new Definition("APPROVED", "SCRIPT", "内容与工程已确认"),
            new Definition("ASSETS", "ASSET", "素材已准备"),
            new Definition("TIMELINE", "AUDIO", "配音和时间线就绪"),
            new Definition("RENDERED", "RENDER", "成片和字幕生成"),
            new Definition("LOCAL_QA", "QA", "本地质量检查通过"),
            new Definition("SAVED", "SAVE", "私有成片与初版素材已保存"));
    private static final Set<String> LOCAL_STEPS = Set.of("ASSETS", "TIMELINE", "RENDERED", "LOCAL_QA");
    private final MediaUploadMapper media;
    private final ProductionPlanMapper plans;
    private final CreationRuntimeMapper runtimeMapper;
    private final CreationRuntimeService runtime;
    private final AutomaticProductionMapper automatic;
    private final GeneratedDraftMapper attempts;
    private final FixedProjectJobMapper jobs;
    private final ObjectMapper json;

    public AutomaticProductionPlanService(MediaUploadMapper media, ProductionPlanMapper plans,
            CreationRuntimeMapper runtimeMapper, CreationRuntimeService runtime, AutomaticProductionMapper automatic,
            GeneratedDraftMapper attempts, FixedProjectJobMapper jobs, ObjectMapper json) {
        this.media = media; this.plans = plans; this.runtimeMapper = runtimeMapper; this.runtime = runtime;
        this.automatic = automatic; this.attempts = attempts; this.jobs = jobs; this.json = json;
    }

    /** 内部入口，不调用模型、不伪造发布确认，也不重置旧成片及其报告。 */
    public Run start(String taskId, String ownerId, String key, long expectedRevision,
                     AutomaticProductionRequest request, BigDecimal maxCost) {
        id(taskId); key(key); validate(request); maxCost = money(maxCost, true);
        media.lockMediaTask(taskId, ownerId).orElseThrow(() -> conflict("创作任务不存在"));
        media.lockTaskDraftVersions(taskId, ownerId);
        String requestHash = hash(canonical(json, write(request)) + ":" + expectedRevision + ":" + maxCost.toPlainString());
        var previous = runtimeMapper.runByKey(taskId, ownerId, key);
        if (previous.isPresent()) {
            var rows = owned(previous.get().taskId(), ownerId, previous.get().runId());
            if (rows.isEmpty()) throw conflict("此幂等键属于其他制作操作");
            var plan = plans.findPlan(taskId, ownerId, rows.getFirst().productionPlanId()).orElseThrow();
            if (!requestHash.equals(read(plan.sourceSnapshot()).path("automaticRequestHash").asText())) throw conflict("相同幂等键对应的自动制作内容不同");
            return runtime.startProduction(taskId, ownerId, key, expectedRevision,
                    write(input(request, plan.planId())), maxCost);
        }
        String confirmed = automatic.confirmedPublishing(taskId, ownerId, request.publishingSessionId(), request.publishingResultId())
                .orElseThrow(() -> conflict("自动制作需要当前真实发布方案确认，不能使用已有成片跳过入口"));
        ObjectNode snapshot = read(confirmed);
        snapshot.put("automaticRequestHash", requestHash).put("storyboardId", request.storyboardId())
                .put("renderContentHash", request.renderContentHash()).put("executionProfile", "FIXED_PROJECT");
        int previousVersion = plans.findMaxPlanVersion(taskId, ownerId);
        if (previousVersion == Integer.MAX_VALUE) throw conflict("制作蓝图版本序号已达到上限");
        String planId = UUID.randomUUID().toString();
        var plan = new ProductionPlanRecord(null, planId, taskId, ownerId, previousVersion + 1,
                "AI_GENERATED", "AI_GENERATION", textOrReference(snapshot, "audience"), textOrReference(snapshot, "coreMessage"),
                request.targetDurationMs(), "[]", null, "[]", write(snapshot), "自动制作蓝图",
                "按已确认工程生成初稿，并由真实产物完成制作步骤", "READY", null, "system.fixed-project.v1", key, null, null);
        if (plans.insertPlan(plan) != 1) throw conflict("无法建立自动制作蓝图");
        var stepIds = new LinkedHashMap<String, String>(); int sequence = 0;
        for (var definition : DEFINITIONS) {
            String stepId = UUID.randomUUID().toString(); stepIds.put(definition.code(), stepId);
            var step = new ProductionStepRecord(null, stepId, planId, taskId, ++sequence, definition.phase(), definition.title(),
                    "由系统根据本次运行的真实完成证据更新", "[]", write(List.of("系统执行；无需用户手工勾选")), "[]",
                    write(List.of(definition.title())), write(List.of("证据必须属于本次运行和实际尝试")), null, true, "PENDING", 0L, null, null, null);
            if (plans.insertStep(step) != 1) throw conflict("无法建立自动制作步骤");
        }
        var run = runtime.startProduction(taskId, ownerId, key, expectedRevision, write(input(request, planId)), maxCost);
        for (var definition : DEFINITIONS) {
            if (automatic.insert(new AutomaticStepEvidence(run.runId(), taskId, ownerId, planId, definition.code(),
                    stepIds.get(definition.code()), null, null, null, "PENDING")) != 1) throw conflict("无法绑定自动制作步骤");
        }
        ObjectNode proof = json.createObjectNode().put("kind", "PRODUCTION_APPROVAL").put("approvalId", run.approvalId())
                .put("publishingSessionId", request.publishingSessionId()).put("publishingResultId", request.publishingResultId())
                .put("storyboardId", request.storyboardId()).put("renderContentHash", request.renderContentHash());
        record(owned(taskId, ownerId, run.runId()).getFirst(), proof, null, null);
        return run;
    }

    public void recordRendered(GeneratedDraftLease lease, String attemptId, String projectJson) {
        var input = runtime.requireProductionLease(lease);
        var rows = owned(lease.taskId(), lease.ownerId(), lease.runId());
        if (rows.isEmpty()) return;
        var attempt = attempt(lease, attemptId, input, rows);
        ObjectNode project = read(renderDocument(json, projectJson));
        if (!lease.runId().equals(project.path("runId").asText()) || !attemptId.equals(project.path("attemptId").asText())
                || !input.storyboardId().equals(project.path("storyboardId").asText())
                || !attempt.inputHash().equals(renderInputHash(json, project))
                || !attempt.inputHash().equals(project.path("inputHash").asText())
                || !input.renderContentHash().equals(renderContentHash(json, projectJson))) throw conflict("制作证据与已验收工程不一致");
        var job = jobs.find(lease.taskId(), lease.ownerId(), lease.runId()).orElseThrow(() -> conflict("缺少冻结工程"));
        if (!input.renderContentHash().equals(renderContentHash(json, job.projectJson()))) throw conflict("冻结工程已变化");
        if (!"COMPLETED".equals(rows.getFirst().stepStatus()) || rows.getFirst().evidenceJson() == null)
            throw conflict("缺少本次制作确认依据");
        for (var row : rows) if (LOCAL_STEPS.contains(row.stepCode())) {
            ObjectNode proof = proof(attempt, "VALIDATED_RENDER").put("stepCode", row.stepCode());
            if ("ASSETS".equals(row.stepCode())) proof.set("assets", project.path("assets"));
            if ("TIMELINE".equals(row.stepCode())) {
                proof.set("video", project.path("video")); proof.put("sceneCount", project.path("scenes").size())
                        .put("subtitleCount", project.path("subtitles").size());
            }
            record(row, proof, lease.generation(), attemptId);
        }
    }

    /** 只能由实际对象 PUT/HEAD/GET 校验后的保存链路调用。 */
    public void recordStored(GeneratedDraftLease lease, String attemptId) {
        var input = runtime.requireProductionLease(lease); var rows = owned(lease.taskId(), lease.ownerId(), lease.runId());
        if (rows.isEmpty()) return;
        var attempt = attempt(lease, attemptId, input, rows);
        for (var row : rows) if (LOCAL_STEPS.contains(row.stepCode()))
            if (!"COMPLETED".equals(row.stepStatus()) || !Objects.equals(row.leaseGeneration(), lease.generation())
                    || !attemptId.equals(row.attemptId()) || row.evidenceJson() == null) throw conflict("本次尝试尚无完整本地制作证据");
        var saved = rows.getLast();
        record(saved, proof(attempt, "PRIVATE_OBJECTS_VERIFIED"), lease.generation(), attemptId);
    }

    public void requireStoredProof(GeneratedDraftLease lease, String attemptId) {
        var rows = owned(lease.taskId(), lease.ownerId(), lease.runId());
        if (rows.isEmpty()) return;
        var saved = rows.getLast();
        if (!"COMPLETED".equals(saved.stepStatus()) || saved.evidenceJson() == null
                || !Objects.equals(saved.leaseGeneration(), lease.generation()) || !attemptId.equals(saved.attemptId()))
            throw conflict("缺少本次尝试的真实存储完成证据");
    }

    private GeneratedDraftAttempt attempt(GeneratedDraftLease lease, String attemptId, GeneratedDraftInput input, List<AutomaticStepEvidence> rows) {
        var attempt = attempts.attempt(lease.taskId(), lease.ownerId(), lease.runId(), id(attemptId))
                .orElseThrow(() -> conflict("缺少本次产物清单"));
        if (attempt.leaseGeneration() != lease.generation() || !input.productionPlanId().equals(attempt.productionPlanId())
                || rows.stream().anyMatch(row -> !input.productionPlanId().equals(row.productionPlanId()))) throw conflict("制作证据不属于本次执行");
        return attempt;
    }

    private List<AutomaticStepEvidence> owned(String taskId, String ownerId, String runId) {
        var rows = automatic.steps(taskId, ownerId, runId);
        if (!rows.isEmpty() && (rows.size() != DEFINITIONS.size()
                || !rows.stream().map(AutomaticStepEvidence::stepCode).toList().equals(DEFINITIONS.stream().map(Definition::code).toList())))
            throw conflict("自动制作步骤绑定不完整");
        return rows;
    }

    private ObjectNode proof(GeneratedDraftAttempt attempt, String kind) {
        ObjectNode proof = json.createObjectNode().put("kind", kind).put("runId", attempt.runId()).put("attemptId", attempt.attemptId())
                .put("inputHash", attempt.inputHash()).put("bundleHash", attempt.bundleHash());
        var objects = readArray(attempt.objectsJson());
        if ("PRIVATE_OBJECTS_VERIFIED".equals(kind)) {
            proof.put("bucketName", attempt.bucketName()); proof.set("objects", objects);
        } else {
            // 预分配的私有对象键还不代表上传成功，本地证据只记录实际文件事实。
            var files = json.createArrayNode();
            for (var object : objects) files.add(json.createObjectNode().put("name", object.path("name").asText())
                    .put("bytes", object.path("bytes").asLong()).put("sha256", object.path("sha256").asText()));
            proof.set("files", files);
        }
        return proof;
    }

    private void record(AutomaticStepEvidence row, ObjectNode proof, Long generation, String attemptId) {
        String document = canonical(json, write(proof));
        if (row.evidenceJson() != null && document.equals(canonical(json, row.evidenceJson()))
                && Objects.equals(row.leaseGeneration(), generation) && Objects.equals(row.attemptId(), attemptId)
                && "COMPLETED".equals(row.stepStatus())) return;
        var value = new AutomaticStepEvidence(row.runId(), row.taskId(), row.ownerId(), row.productionPlanId(), row.stepCode(),
                row.stepId(), document, generation, attemptId, "COMPLETED");
        if (automatic.evidence(value) != 1 || automatic.complete(row.taskId(), row.ownerId(), row.runId(), row.stepCode()) != 1)
            throw conflict("自动制作步骤已变化，不能采纳完成证据");
    }

    private GeneratedDraftInput input(AutomaticProductionRequest request, String planId) {
        return new GeneratedDraftInput(request.publishingSessionId(), request.publishingResultId(), planId,
                request.baseVersionId(), request.storyboardId(), request.renderContentHash());
    }
    private void validate(AutomaticProductionRequest request) {
        if (request == null) throw conflict("缺少自动制作依据");
        id(request.publishingSessionId()); id(request.publishingResultId()); id(request.storyboardId());
        if (request.baseVersionId() != null) id(request.baseVersionId());
        if (request.renderContentHash() == null || !request.renderContentHash().matches("[0-9a-f]{64}")
                || request.targetDurationMs() < 1000 || request.targetDurationMs() > 180000) throw conflict("固定工程摘要或目标时长不受支持");
    }
    private String textOrReference(ObjectNode node, String field) {
        String value = node.path(field).asText(""); return value.isBlank() ? "见已确认发布方案" : value;
    }
    private ObjectNode read(String value) {
        try { return (ObjectNode) json.readTree(value); }
        catch (java.io.IOException | ClassCastException e) { throw conflict("制作依据不是有效对象"); }
    }
    private com.fasterxml.jackson.databind.JsonNode readArray(String value) {
        try { return json.readTree(value); }
        catch (java.io.IOException e) { throw conflict("产物清单无效"); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
