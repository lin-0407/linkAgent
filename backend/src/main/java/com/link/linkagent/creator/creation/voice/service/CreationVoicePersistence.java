package com.link.linkagent.creator.creation.voice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.asset.model.AssetRequests.StoredFile;
import com.link.linkagent.creator.creation.asset.model.CreationAsset;
import com.link.linkagent.creator.creation.service.CreationRuntimeService;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.Asset;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.Board;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.Candidate;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardPersistence;
import com.link.linkagent.creator.creation.voice.mapper.CreationVoiceMapper;
import com.link.linkagent.creator.creation.voice.model.CreationVoice;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import static com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec.conflict;

/**
 * 配音受理、音频登记与新版本保存都在短事务内完成。
 * 供应商调用、下载和 FFmpeg 在事务外执行，这里只保存已经复核过的事实。
 */
@Service
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
@Transactional
public class CreationVoicePersistence {
    private final CreationVoiceMapper mapper;
    private final CreationRuntimeService runtime;
    private final StoryboardPersistence storyboards;
    private final ObjectMapper json;

    public CreationVoicePersistence(CreationVoiceMapper mapper, CreationRuntimeService runtime,
                                    StoryboardPersistence storyboards, ObjectMapper json) {
        this.mapper = mapper; this.runtime = runtime; this.storyboards = storyboards; this.json = json;
    }

    public record Start(String runId, CreationVoiceMapper.Job job) { }

    public record GeneratedAudio(String bucket, String assetId, String fileName, long originalBytes,
            String originalSha256, String sourceKey, long normalizedBytes, String normalizedSha256,
            long durationMs, String metadataJson, String objectsJson) { }

    /** 受理与场景明细一起提交；同一幂等键重放返回原运行，不重复创建或扣费。 */
    public Start start(String task, String owner, String key, String scopedKey, String boardId, String briefId,
                       String requestHash, String requestJson, BigDecimal maxCost, long expectedRevision,
                       List<CreationVoice> rows) {
        var existing = mapper.jobByKey(task, owner, key);
        if (existing.isPresent()) {
            if (!requestHash.equals(existing.get().requestHash())) throw conflict("同一幂等键对应不同配音请求");
            return new Start(existing.get().runId(), existing.get());
        }
        var run = runtime.startPreparation(task, owner, "PREPARE", scopedKey, expectedRevision, requestJson, maxCost);
        try {
            mapper.insertJob(run.runId(), task, owner, briefId, boardId, key, requestHash);
            for (CreationVoice row : rows) {
                row.setRunId(run.runId());
                mapper.insert(row);
            }
        } catch (DuplicateKeyException race) {
            // 并发同键请求在受理锁外都能通过预检，唯一键保证只有一份任务行。
            var current = mapper.jobByKey(task, owner, key).orElseThrow(() -> race);
            if (!requestHash.equals(current.requestHash())) throw conflict("同一幂等键对应不同配音请求");
            return new Start(current.runId(), current);
        }
        return new Start(run.runId(), mapper.job(task, owner, run.runId()).orElseThrow());
    }

    /**
     * 登记生成音频与场景结果。素材行只在音频真实复核后写入，场景更新必须仍持有当前租约，
     * 失败时整笔回滚，不会留下没有场景引用的半成品。
     */
    public void saveAudio(CreationVoice row, String worker, long generation, String requestId, GeneratedAudio audio) {
        String key = "voice-" + row.getRunId() + "-" + row.getSceneId();
        var existing = mapper.assetByKey(row.getTaskId(), row.getOwnerId(), key);
        String assetId = existing.map(CreationAsset::getAssetId).orElse(audio.assetId());
        if (existing.isEmpty())
            mapper.insertGeneratedAsset(assetId, row.getTaskId(), row.getOwnerId(), key, audio.fileName(),
                    audio.originalBytes(), audio.originalSha256(), audio.bucket(), audio.sourceKey(),
                    audio.metadataJson(), audio.objectsJson());
        if (mapper.synthesized(row.getRunId(), row.getSceneId(), assetId, audio.normalizedSha256(),
                audio.normalizedBytes(), audio.durationMs(), requestId, worker, generation) != 1)
            throw conflict("配音运行已取消、执行权失效或场景状态已变化");
    }

    public void transcribing(CreationVoice row, String providerTaskId, String worker, long generation) {
        if (mapper.transcribing(row.getRunId(), row.getSceneId(), providerTaskId, worker, generation) != 1)
            throw conflict("配音转写状态已变化或执行权失效");
    }

    public void aligned(CreationVoice row, String status, String transcriptJson, BigDecimal ratio,
                        String proposalJson, String errorCode, String worker, long generation) {
        if (mapper.aligned(row.getRunId(), row.getSceneId(), status, transcriptJson, ratio, proposalJson,
                errorCode, worker, generation) != 1)
            throw conflict("配音场景结果已变化或执行权失效");
    }

    public void fail(CreationVoice row, String code, String worker, long generation) {
        mapper.fail(row.getRunId(), row.getSceneId(), code, worker, generation);
    }

    public void closeRows(String runId, String status, String code) { mapper.closeByRun(runId, status, code); }

    /** 生成素材转成故事板冻结快照；只使用 NORMALIZED 工作文件，不把短签或原始上传路径带入工程。 */
    public Asset asset(CreationAsset row) {
        try {
            StoredFile file = json.readValue(row.getObjectsJson(), new TypeReference<List<StoredFile>>() { }).stream()
                    .filter(item -> "NORMALIZED".equals(item.role())).findFirst()
                    .orElseThrow(() -> conflict("配音素材缺少规范化工作文件"));
            return new Asset(row.getAssetId(), row.getKind(), row.getBucketName(), file.objectKey(), file.sha256(),
                    file.bytes(), file.contentType(), json.readTree(row.getMetadataJson()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw conflict("配音素材记录无法读取");
        }
    }

    public Board saveBoard(CreationVoiceMapper.Job job, Candidate candidate, List<Asset> extra, Set<String> extraIds,
                           String worker, long generation, String key) {
        var board = storyboards.saveVoice(job.taskId(), job.ownerId(), key, job.runId(), worker, generation,
                job.briefId(), job.storyboardId(), candidate, extra, extraIds);
        mapper.result(job.runId(), board.storyboardId());
        return board;
    }

    public CreationVoiceMapper.Job job(String task, String owner, String runId) {
        return mapper.job(task, owner, runId).orElseThrow(() -> conflict("配音准备运行不存在"));
    }

    public List<CreationVoice> scenes(String runId) { return mapper.scenes(runId); }

    public CreationAsset assetByKey(String task, String owner, String runId, String sceneId) {
        return mapper.assetByKey(task, owner, "voice-" + runId + "-" + sceneId).orElse(null);
    }
}
