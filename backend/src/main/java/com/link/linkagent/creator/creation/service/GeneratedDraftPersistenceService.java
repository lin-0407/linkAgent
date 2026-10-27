package com.link.linkagent.creator.creation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.mapper.CreationRuntimeMapper;
import com.link.linkagent.creator.creation.mapper.GeneratedDraftMapper;
import com.link.linkagent.creator.creation.model.*;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.link.linkagent.creator.creation.service.CreationInputRules.*;

/** 网络在事务外进行；上传前后均复核运行身份，不将已失效产物采纳为当前版本。 */
@Service
@Transactional
public class GeneratedDraftPersistenceService {
    private final MediaUploadMapper media;
    private final CreationRuntimeMapper runtime;
    private final GeneratedDraftMapper mapper;
    private final ObjectMapper json;
    private final CreationRuntimeService execution;
    private final AutomaticProductionPlanService automatic;
    private static final Set<String> FILES = Set.of("video.mp4", "subtitles.srt", "cover.png", "frame-start.png",
            "frame-middle.png", "frame-end.png", "render.log", "manifest.json");

    public GeneratedDraftPersistenceService(MediaUploadMapper media, CreationRuntimeMapper runtime,
            GeneratedDraftMapper mapper, ObjectMapper json, CreationRuntimeService execution, AutomaticProductionPlanService automatic) {
        this.media = media; this.runtime = runtime; this.mapper = mapper; this.json = json;
        this.execution = execution; this.automatic = automatic;
    }

    public GeneratedDraftAttempt prepare(GeneratedDraftLease lease, String attemptId, String inputHash,
            String contentHash, String storyboardId, String bucket, List<GeneratedDraftFile> files) {
        var input = requireLive(lease);
        id(attemptId);
        if (inputHash == null || !inputHash.matches("[0-9a-f]{64}") || !input.renderContentHash().equals(contentHash)
                || !input.storyboardId().equals(storyboardId)) throw conflict("渲染工程与整片授权不一致");
        if (bucket == null || bucket.isBlank() || bucket.length() > 255) throw conflict("私有存储桶未配置");
        validateFiles(files);
        String bundleHash = hash(write(files.stream().sorted(Comparator.comparing(GeneratedDraftFile::name)).toList()));
        var existing = mapper.attempt(lease.taskId(), lease.ownerId(), lease.runId(), attemptId);
        if (existing.isPresent()) {
            var old = existing.get();
            if (old.leaseGeneration() != lease.generation() || !old.inputHash().equals(inputHash)
                    || !old.bundleHash().equals(bundleHash) || !old.bucketName().equals(bucket))
                throw conflict("同一尝试已用于其他工程或执行代数，请创建新尝试");
            return old;
        }
        if (mapper.registered(lease.taskId(), lease.ownerId(), lease.runId()).isPresent())
            throw conflict("本运行已经登记成片，请恢复已有版本");
        requireBase(lease, input);
        String version = UUID.randomUUID().toString();
        String prefix = "users/" + id(lease.ownerId()) + "/tasks/" + id(lease.taskId()) + "/versions/" + version
                + "/attempts/" + attemptId + "/rendered/";
        var objects = files.stream().sorted(Comparator.comparing(GeneratedDraftFile::name))
                .map(file -> new GeneratedDraftObject(file.name(), prefix + file.name(), file.contentType(), file.bytes(), file.sha256())).toList();
        var value = new GeneratedDraftAttempt(lease.runId(), attemptId, lease.taskId(), lease.ownerId(), lease.generation(),
                version, input.productionPlanId(), inputHash, bundleHash, bucket, write(objects), "UPLOADING");
        if (mapper.insert(value) != 1) throw conflict("无法保存本次产物清单");
        return value;
    }

    public DraftVideoRecord register(GeneratedDraftLease lease, String attemptId) {
        var input = requireLive(lease);
        var attempt = mapper.attempt(lease.taskId(), lease.ownerId(), lease.runId(), id(attemptId))
                .orElseThrow(() -> conflict("缺少本次产物清单"));
        if (attempt.leaseGeneration() != lease.generation()) throw conflict("执行代数已变化，不能登记旧尝试");
        if ("REGISTERED".equals(attempt.status()))
            return media.findDraftVideoByVersion(lease.taskId(), lease.ownerId(), attempt.versionId()).orElseThrow();
        requireBase(lease, input);
        automatic.requireStoredProof(lease, attemptId);
        String snapshot = media.findGeneratedProductionContext(lease.taskId(), lease.ownerId(), input.publishingSessionId(),
                input.publishingResultId(), input.productionPlanId(), true, false)
                .orElseThrow(() -> conflict("真实发布确认或制作蓝图尚未完成，不能登记系统成片"));
        var workspace = media.findMediaWorkspace(lease.taskId(), lease.ownerId()).orElseThrow();
        if (workspace.nextVersionNo() == Integer.MAX_VALUE) throw conflict("成片版本序号已达到上限");
        var video = objects(attempt).stream().filter(object -> object.name().equals("video.mp4")).findFirst().orElseThrow();
        var draft = new DraftVideoRecord(null, attempt.versionId(), lease.taskId(), lease.ownerId(), workspace.nextVersionNo(),
                "自动制作初稿", "generated-" + attempt.versionId() + ".mp4", attempt.bucketName(), video.objectKey(),
                "video/mp4", video.bytes(), null, null, null, null, null, null, null, null, "UPLOADED", null, null, null);
        if (media.advanceMediaVersion(lease.taskId(), lease.ownerId(), workspace.nextVersionNo()) != 1
                || media.insertDraftVideo(draft) != 1) throw conflict("无法分配系统成片版本");
        try {
            ObjectNode context = (ObjectNode) json.readTree(snapshot);
            context.put("runId", lease.runId()).put("attemptId", attemptId).put("storyboardId", input.storyboardId())
                    .put("inputHash", attempt.inputHash());
            mapper.insertContext(new VideoVersionContextRecord(draft.versionId(), lease.taskId(), lease.ownerId(), false,
                    input.publishingSessionId(), input.publishingResultId(), input.productionPlanId(), json.writeValueAsString(context)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("制作上下文无法保存", e); }
        if (mapper.markRegistered(attempt) != 1 || media.activateUploadedVersion(lease.taskId(), lease.ownerId(), draft.versionId(),
                workspace.workspaceRevision()) != 1) throw conflict("成片已变化，请恢复已登记结果");
        if (runtime.artifactReady(lease) != 1) throw conflict("登记期间执行租约已失效");
        return media.findDraftVideoByVersion(lease.taskId(), lease.ownerId(), draft.versionId()).orElseThrow();
    }

    /** 每次存储调用前检查；这里只做短事务，不把网络包在行锁中。 */
    public void checkLive(GeneratedDraftLease lease) { requireLive(lease); }

    /** 调用方已完成本地文件与清单验收，才允许采纳自动步骤证据。 */
    public void recordValidatedRender(GeneratedDraftLease lease, String attemptId, String projectJson) {
        automatic.recordRendered(lease, attemptId, projectJson);
    }

    public void confirmStored(GeneratedDraftLease lease, String attemptId) {
        automatic.recordStored(lease, attemptId);
    }

    /** 开始耗时渲染前拒绝失效的制作依据，登记时仍会再次复核。 */
    public void validateBeforeRender(GeneratedDraftLease lease) {
        var input = requireLive(lease);
        requireBase(lease, input);
        if (media.findGeneratedProductionContext(lease.taskId(), lease.ownerId(), input.publishingSessionId(),
                input.publishingResultId(), input.productionPlanId(), false, true).isEmpty()) throw conflict("制作依据已失效");
    }

    /** 接管已登记的运行时直接读取版本事实，不要求原工作目录仍然存在。 */
    public Optional<DraftVideoRecord> registeredResult(GeneratedDraftLease lease) {
        requireLive(lease);
        return mapper.registered(lease.taskId(), lease.ownerId(), lease.runId()).map(attempt -> {
            var draft = media.findDraftVideoByVersion(lease.taskId(), lease.ownerId(), attempt.versionId())
                    .orElseThrow(() -> conflict("已登记版本不存在"));
            if (draft.mediaDeletedAt() != null) throw conflict("已登记媒体已删除，不能恢复制作");
            return draft;
        });
    }

    /** 已登记结果可跨租约恢复；这是读取事实，不再次切换指针或依赖本地临时目录。 */
    public Optional<DraftVideoRecord> restoreRegistered(GeneratedDraftLease lease, String attemptId, String inputHash) {
        requireLive(lease);
        var registered = mapper.registered(lease.taskId(), lease.ownerId(), lease.runId());
        if (registered.isEmpty()) return Optional.empty();
        var value = registered.get();
        if (!value.attemptId().equals(attemptId) || !value.inputHash().equals(inputHash)) throw conflict("本运行已登记其他尝试，请恢复原有结果");
        return media.findDraftVideoByVersion(lease.taskId(), lease.ownerId(), value.versionId());
    }

    public List<GeneratedDraftObject> objects(GeneratedDraftAttempt attempt) {
        try { return json.readerForListOf(GeneratedDraftObject.class).readValue(attempt.objectsJson()); }
        catch (java.io.IOException e) { throw new IllegalStateException("已保存的产物清单无效", e); }
    }

    private GeneratedDraftInput requireLive(GeneratedDraftLease lease) {
        return execution.requireProductionLease(lease);
    }

    private void requireBase(GeneratedDraftLease lease, GeneratedDraftInput input) {
        if (!Objects.equals(input.baseVersionId(), media.findMediaWorkspace(lease.taskId(), lease.ownerId()).orElseThrow().currentVideoVersionId()))
            throw conflict("基准成片已经变化，不能覆盖当前版本");
        if (media.findCurrentUpload(lease.taskId(), lease.ownerId()).isPresent()) throw conflict("仍有成片上传未结束");
    }

    private void validateFiles(List<GeneratedDraftFile> files) {
        if (files == null || files.size() != 8 || !new HashSet<>(files.stream().map(GeneratedDraftFile::name).toList()).equals(FILES))
            throw conflict("固定渲染产物不完整");
        for (var file : files) {
            String expected = file.name().endsWith(".mp4") ? "video/mp4" : file.name().endsWith(".png") ? "image/png"
                    : file.name().endsWith(".json") ? "application/json" : file.name().endsWith(".srt") ? "application/x-subrip" : "text/plain";
            if (!expected.equals(file.contentType()) || file.bytes() <= 0 || file.bytes() > 512L * 1024 * 1024
                    || file.sha256() == null || !file.sha256().matches("[0-9a-f]{64}")) throw conflict("固定渲染文件事实无效");
        }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
