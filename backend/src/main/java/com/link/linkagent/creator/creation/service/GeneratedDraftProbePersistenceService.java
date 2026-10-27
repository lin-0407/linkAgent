package com.link.linkagent.creator.creation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.mapper.CreationRuntimeMapper;
import com.link.linkagent.creator.creation.mapper.FixedProjectJobMapper;
import com.link.linkagent.creator.creation.mapper.GeneratedDraftMapper;
import com.link.linkagent.creator.creation.model.GeneratedDraftInput;
import com.link.linkagent.creator.creation.model.GeneratedDraftLease;
import com.link.linkagent.creator.creation.render.RenderExecutionException;
import com.link.linkagent.creator.media.probe.model.MediaProbeResult;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static com.link.linkagent.creator.creation.service.CreationInputRules.*;

/** 原片探测只采纳本运行精确登记版本；元信息与最后一次数据库租约校验一起提交。 */
@Service
@Transactional
public class GeneratedDraftProbePersistenceService {
    public record ProbeClaim(DraftVideoRecord draft, String attemptId) { }

    private final CreationRuntimeService execution;
    private final CreationRuntimeMapper runtime;
    private final GeneratedDraftMapper generated;
    private final FixedProjectJobMapper jobs;
    private final MediaUploadMapper media;
    private final ObjectMapper json;

    public GeneratedDraftProbePersistenceService(CreationRuntimeService execution, CreationRuntimeMapper runtime,
            GeneratedDraftMapper generated, FixedProjectJobMapper jobs, MediaUploadMapper media, ObjectMapper json) {
        this.execution = execution; this.runtime = runtime; this.generated = generated;
        this.jobs = jobs; this.media = media; this.json = json;
    }

    public ProbeClaim begin(GeneratedDraftLease lease, String versionId) {
        var input = execution.requireProductionLease(lease);
        var draft = registered(lease, versionId);
        if ("READY_FOR_REVIEW".equals(draft.status())) {
            if (draft.durationMs() == null || draft.width() == null || draft.height() == null
                    || draft.frameRate() == null || draft.hasAudio() == null) throw mismatch();
            validateResult(lease, input, new MediaProbeResult(draft.durationMs(), draft.width(), draft.height(),
                    draft.frameRate(), draft.videoCodec(), draft.audioCodec(), draft.hasAudio()));
            fence(lease, "SOURCE_PROBED");
            return new ProbeClaim(draft, null);
        }
        if (!Set.of("UPLOADED", "PROBE_FAILED", "PROBING").contains(draft.status()))
            throw conflict("当前系统成片状态不能探测");
        String prefix = "creation-" + lease.generation() + "-";
        if ("PROBING".equals(draft.status()) && draft.probeAttemptId() != null && draft.probeAttemptId().startsWith(prefix))
            throw conflict("本次执行已经领取原片探测");
        String token = prefix + UUID.randomUUID().toString().replace("-", "");
        if (generated.claimProbe(lease.taskId(), lease.ownerId(), versionId, draft.status(), draft.probeAttemptId(), token) != 1)
            throw conflict("原片探测状态已变化");
        fence(lease, "SOURCE_PROBING");
        return new ProbeClaim(draft, token);
    }

    public DraftVideoRecord complete(GeneratedDraftLease lease, String versionId, String token, MediaProbeResult result) {
        var input = execution.requireProductionLease(lease);
        registered(lease, versionId);
        ownedToken(lease, token);
        validateResult(lease, input, result);
        if (media.updateDraftVideoProbeResult(lease.taskId(), lease.ownerId(), versionId, "PROBING", token,
                "READY_FOR_REVIEW", result.durationMs(), result.width(), result.height(), result.frameRate(),
                result.videoCodec(), result.audioCodec(), result.hasAudio()) != 1) throw conflict("原片探测结果已失效");
        // 写元信息后再看数据库时钟，租约在事务中途到期也必须整体回滚。
        fence(lease, "SOURCE_PROBED");
        return media.findDraftVideoByVersion(lease.taskId(), lease.ownerId(), versionId).orElseThrow();
    }

    public void fail(GeneratedDraftLease lease, String versionId, String token) {
        execution.requireProductionLease(lease);
        registered(lease, versionId);
        ownedToken(lease, token);
        if (media.updateDraftVideoProbeResult(lease.taskId(), lease.ownerId(), versionId, "PROBING", token,
                "PROBE_FAILED", null, null, null, null, null, null, null) != 1) throw conflict("原片探测状态已变化");
        fence(lease, "SOURCE_PROBE_FAILED");
    }

    private DraftVideoRecord registered(GeneratedDraftLease lease, String versionId) {
        var attempt = generated.registered(lease.taskId(), lease.ownerId(), lease.runId())
                .orElseThrow(() -> conflict("本运行尚未登记成片"));
        if (!attempt.versionId().equals(versionId)) throw conflict("原片不属于本次制作运行");
        var draft = media.findDraftVideoByVersion(lease.taskId(), lease.ownerId(), versionId)
                .orElseThrow(() -> conflict("已登记成片不存在"));
        if (draft.mediaDeletedAt() != null) throw conflict("已登记媒体已删除");
        return draft;
    }

    private void ownedToken(GeneratedDraftLease lease, String token) {
        if (token == null || !token.startsWith("creation-" + lease.generation() + "-")) throw conflict("探测不属于当前执行代数");
    }

    private void validateResult(GeneratedDraftLease lease, GeneratedDraftInput input, MediaProbeResult result) {
        var job = jobs.find(lease.taskId(), lease.ownerId(), lease.runId()).orElseThrow(() -> conflict("缺少冻结工程"));
        if (!input.renderContentHash().equals(renderContentHash(json, job.projectJson()))) throw conflict("冻结工程已变化");
        try {
            var video = json.readTree(job.projectJson()).path("video");
            int fps = video.path("fps").asInt();
            long frames = video.path("frameCount").asLong();
            if (fps <= 0 || frames <= 0 || result == null) throw mismatch();
            long expectedDuration = BigDecimal.valueOf(frames).multiply(BigDecimal.valueOf(1000))
                    .divide(BigDecimal.valueOf(fps), 0, java.math.RoundingMode.HALF_UP).longValueExact();
            if (result.durationMs() < expectedDuration - 65 || result.durationMs() > expectedDuration + 65
                    || result.width() != video.path("width").asInt() || result.height() != video.path("height").asInt()
                    || result.frameRate() == null || result.frameRate().compareTo(BigDecimal.valueOf(fps)) != 0
                    || !"h264".equals(result.videoCodec()) || !"aac".equals(result.audioCodec()) || !result.hasAudio()) throw mismatch();
        } catch (java.io.IOException | ArithmeticException e) { throw mismatch(); }
    }

    private void fence(GeneratedDraftLease lease, String stage) {
        if (runtime.sourceProbeStage(lease, stage) != 1) throw conflict("探测期间制作已取消或执行租约已失效");
    }

    private static RenderExecutionException mismatch() {
        return new RenderExecutionException("SOURCE_PROBE_MISMATCH", "私有原片实测信息与已确认工程不符");
    }
}
