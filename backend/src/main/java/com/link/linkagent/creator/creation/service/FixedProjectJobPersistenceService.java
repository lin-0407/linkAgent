package com.link.linkagent.creator.creation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.mapper.CreationRuntimeMapper;
import com.link.linkagent.creator.creation.mapper.FixedProjectJobMapper;
import com.link.linkagent.creator.creation.model.FixedProjectJob;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.link.linkagent.creator.creation.service.CreationInputRules.*;

/** 工程冻结与排队身份在短事务中核对，文件读取和媒体处理都留在事务外。 */
@Service
public class FixedProjectJobPersistenceService {
    private final MediaUploadMapper media;
    private final CreationRuntimeMapper runtime;
    private final FixedProjectJobMapper jobs;
    private final ObjectMapper json;

    public FixedProjectJobPersistenceService(MediaUploadMapper media, CreationRuntimeMapper runtime,
            FixedProjectJobMapper jobs, ObjectMapper json) {
        this.media = media; this.runtime = runtime; this.jobs = jobs; this.json = json;
    }

    @Transactional
    public FixedProjectJob attach(FixedProjectJob job) {
        id(job.taskId()); id(job.runId()); id(job.sourceAttemptId());
        media.lockMediaTask(job.taskId(), job.ownerId()).orElseThrow(() -> conflict("创作任务不存在"));
        media.lockTaskDraftVersions(job.taskId(), job.ownerId());
        String document = renderDocument(json, job.projectJson());
        validateAutomaticDuration(job, document);
        var existing = jobs.find(job.taskId(), job.ownerId(), job.runId());
        if (existing.isPresent()) {
            var previous = existing.get();
            if (!job.sourceAttemptId().equals(previous.sourceAttemptId())
                    || !document.equals(renderDocument(json, previous.projectJson()))) throw conflict("本运行已冻结其他工程");
            return previous;
        }
        var workspace = runtime.workspace(job.taskId(), job.ownerId()).orElseThrow(() -> conflict("制作工作区不存在"));
        var run = runtime.run(job.taskId(), job.ownerId(), job.runId()).orElseThrow(() -> conflict("制作运行不存在"));
        if (!job.runId().equals(workspace.activeRunId()) || !"FULL".equals(run.kind())
                || !"QUEUED".equals(run.status()) || run.leaseGeneration() != 0) throw conflict("工程只能附加到尚未领取的整片运行");
        var approval = runtime.approval(job.taskId(), job.ownerId(), run.approvalId()).orElseThrow();
        if (!"ACTIVE".equals(approval.status()) || !"PRODUCTION".equals(approval.approvalKind())
                || !run.inputFingerprint().equals(approval.inputFingerprint())
                || !run.inputFingerprint().equals(hash(canonical(json, run.requestJson())))) throw conflict("整片授权已失效");
        var input = productionInput(json, run.requestJson());
        try {
            var project = json.readTree(document);
            if (!job.runId().equals(project.path("runId").asText())
                    || !job.sourceAttemptId().equals(project.path("attemptId").asText())
                    || !input.storyboardId().equals(project.path("storyboardId").asText())
                    || !project.path("inputHash").asText().equals(renderInputHash(json, (com.fasterxml.jackson.databind.node.ObjectNode) project))
                    || !input.renderContentHash().equals(renderContentHash(json, document))) throw conflict("工程与整片授权不一致");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw conflict("工程内容无效"); }
        if (media.countPublishingFacts(job.taskId(), job.ownerId()) > 0
                || media.findGeneratedProductionContext(job.taskId(), job.ownerId(), input.publishingSessionId(),
                    input.publishingResultId(), input.productionPlanId(), false, true).isEmpty()) throw conflict("制作依据已失效");
        var frozen = new FixedProjectJob(job.runId(), job.taskId(), job.ownerId(), job.sourceAttemptId(), document);
        if (jobs.insert(frozen) != 1) throw conflict("无法冻结制作工程");
        return frozen;
    }

    private void validateAutomaticDuration(FixedProjectJob job, String document) {
        var target = jobs.automaticTargetDuration(job.taskId(), job.ownerId(), job.runId());
        if (target.isEmpty()) return;
        try {
            var video = json.readTree(document).path("video");
            var fps = video.path("fps"); var frames = video.path("frameCount");
            if (!fps.isIntegralNumber() || !fps.canConvertToInt() || !java.util.Set.of(24, 25, 30).contains(fps.intValue())
                    || !frames.isIntegralNumber() || !frames.canConvertToInt()
                    || frames.intValue() < fps.intValue() || frames.intValue() > fps.intValue() * 180
                    || target.get() < 1000 || target.get() > 180000)
                throw conflict("自动制作工程时长参数无效");
            // 只容纳一帧量化误差，不能用另一段实际长度悄悄替代用户确认的目标。
            if (Math.abs(frames.longValue() * 1000 - target.get() * fps.longValue()) > 1000)
                throw conflict("工程实际时长与已确认目标不一致，请调整工程后重新确认");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw conflict("工程内容无效"); }
    }
}
