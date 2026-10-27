package com.link.linkagent.creator.creation.aivideo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.aivideo.mapper.CreationVideoMapper;
import com.link.linkagent.creator.creation.aivideo.model.CreationVideo;
import com.link.linkagent.creator.creation.asset.model.AssetRequests.StoredFile;
import com.link.linkagent.creator.creation.asset.model.CreationAsset;
import com.link.linkagent.creator.creation.asset.service.CreationAssetService;
import com.link.linkagent.creator.creation.service.CreationRuntimeService;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.Asset;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.Board;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.Candidate;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardPersistence;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import static com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec.conflict;

/**
 * AI 视频受理、片段登记与新版本保存都在短事务内完成。
 * 供应商调用、下载和 FFmpeg 在事务外执行，这里只保存已经复核过的事实。
 */
@Service
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
@Transactional
public class CreationVideoPersistence {
    private final CreationVideoMapper mapper;
    private final CreationRuntimeService runtime;
    private final StoryboardPersistence storyboards;
    private final CreationAssetService assets;
    private final MediaUploadMapper media;
    private final ObjectMapper json;

    public CreationVideoPersistence(CreationVideoMapper mapper, CreationRuntimeService runtime,
                                    StoryboardPersistence storyboards, CreationAssetService assets,
                                    MediaUploadMapper media, ObjectMapper json) {
        this.mapper = mapper; this.runtime = runtime; this.storyboards = storyboards; this.assets = assets;
        this.media = media; this.json = json;
    }

    public record Start(String runId, CreationVideoMapper.Job job) { }

    public record GeneratedVideo(String bucket, String assetId, String fileName, long sourceBytes,
            String sourceSha256, String sourceKey, long normalizedBytes, String normalizedSha256,
            long durationMs, int width, int height, String metadataJson, String objectsJson) { }

    /** 受理与场景明细一起提交；同一幂等键重放返回原运行，不重复创建或扣费。 */
    public Start start(String task, String owner, String key, String scopedKey, String boardId, String briefId,
                       String scope, String requestHash, String requestJson, BigDecimal maxCost, long expectedRevision,
                       List<CreationVideo> rows) {
        // 受理先锁任务行：并发同键请求在锁外都能通过预检，否则第二个请求会多建一个没有任务行的运行。
        if (media.lockMediaTask(task, owner).isEmpty()) throw conflict("任务不存在");
        var existing = mapper.jobByKey(task, owner, key);
        if (existing.isPresent()) {
            if (!requestHash.equals(existing.get().requestHash())) throw conflict("同一幂等键对应不同视频生成请求");
            return new Start(existing.get().runId(), existing.get());
        }
        var run = runtime.startPreparation(task, owner, "PREPARE", scopedKey, expectedRevision, requestJson, maxCost);
        try {
            mapper.insertJob(run.runId(), task, owner, briefId, boardId, scope, key, requestHash);
            for (CreationVideo row : rows) {
                row.setRunId(run.runId());
                mapper.insert(row);
            }
        } catch (DuplicateKeyException race) {
            // 并发同键请求在受理锁外都能通过预检，唯一键保证只有一份任务行。
            var current = mapper.jobByKey(task, owner, key).orElseThrow(() -> race);
            if (!requestHash.equals(current.requestHash())) throw conflict("同一幂等键对应不同视频生成请求");
            return new Start(current.runId(), current);
        }
        return new Start(run.runId(), mapper.job(task, owner, run.runId()).orElseThrow());
    }

    public void submitted(CreationVideo row, String taskId, String requestId, String worker, long generation) {
        if (mapper.submitted(row.getRunId(), row.getSceneId(), taskId, requestId, worker, generation) != 1)
            throw conflict("视频任务状态已变化或执行权失效");
    }

    /** 片段短于场景时仍登记真实素材并给出可执行提案，缩短场景后可免费复用。 */
    public void saveReview(CreationVideo row, String key, GeneratedVideo video, String code, String proposalJson,
                           String worker, long generation) {
        var existing = mapper.assetByKey(row.getTaskId(), row.getOwnerId(), key);
        String assetId = existing.map(CreationAsset::getAssetId).orElse(video.assetId());
        if (existing.isEmpty())
            mapper.insertGeneratedAsset(assetId, row.getTaskId(), row.getOwnerId(), key, video.fileName(),
                    video.sourceBytes(), video.sourceSha256(), video.bucket(), video.sourceKey(),
                    video.metadataJson(), video.objectsJson());
        if (mapper.review(row.getRunId(), row.getSceneId(), assetId, video.normalizedSha256(),
                video.normalizedBytes(), video.durationMs(), video.width(), video.height(),
                proposalJson, code, worker, generation) != 1)
            throw conflict("视频结果已变化或执行权失效");
    }

    public void fail(CreationVideo row, String code, String worker, long generation) {
        mapper.fail(row.getRunId(), row.getSceneId(), code, worker, generation);
    }

    public void closeRows(String runId, String status, String code) { mapper.closeByRun(runId, status, code); }

    /** 素材登记与场景完成在同一短事务内提交；围栏失败时不会留下没有场景引用的片段。 */
    public void saveVideo(CreationVideo row, String key, GeneratedVideo video, String worker, long generation) {
        var existing = mapper.assetByKey(row.getTaskId(), row.getOwnerId(), key);
        String assetId = existing.map(CreationAsset::getAssetId).orElse(video.assetId());
        if (existing.isEmpty())
            mapper.insertGeneratedAsset(assetId, row.getTaskId(), row.getOwnerId(), key, video.fileName(),
                    video.sourceBytes(), video.sourceSha256(), video.bucket(), video.sourceKey(),
                    video.metadataJson(), video.objectsJson());
        if (mapper.ready(row.getRunId(), row.getSceneId(), assetId, video.normalizedSha256(),
                video.normalizedBytes(), video.durationMs(), video.width(), video.height(), worker, generation) != 1)
            throw conflict("视频结果已变化或执行权失效");
    }

    /** 生成素材转成故事板冻结快照；只使用 NORMALIZED 工作文件，不把短签或原始上传路径带入工程。 */
    public Asset asset(CreationAsset row) {
        try {
            StoredFile file = json.readValue(row.getObjectsJson(), new TypeReference<List<StoredFile>>() { }).stream()
                    .filter(item -> "NORMALIZED".equals(item.role())).findFirst()
                    .orElseThrow(() -> conflict("视频素材缺少规范化工作文件"));
            return new Asset(row.getAssetId(), row.getKind(), row.getBucketName(), file.objectKey(), file.sha256(),
                    file.bytes(), file.contentType(), json.readTree(row.getMetadataJson()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw conflict("视频素材记录无法读取");
        }
    }

    public Asset asset(String task, String owner, String assetId) {
        return asset(assets.require(task, owner, assetId));
    }

    public Board saveBoard(CreationVideoMapper.Job job, Candidate candidate, List<Asset> extra, Set<String> extraIds,
                           String worker, long generation, String key) {
        var board = storyboards.saveVideo(job.taskId(), job.ownerId(), key, job.runId(), worker, generation,
                job.briefId(), job.storyboardId(), candidate, extra, extraIds);
        mapper.result(job.runId(), board.storyboardId());
        return board;
    }

    public CreationVideoMapper.Job job(String task, String owner, String runId) {
        return mapper.job(task, owner, runId).orElseThrow(() -> conflict("视频生成运行不存在"));
    }

    public List<CreationVideo> scenes(String runId) { return mapper.scenes(runId); }

    public com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.Brief brief(String task, String owner, String briefId) {
        return storyboards.brief(task, owner, briefId);
    }

    public List<Asset> frozen(Board board) { return storyboards.frozen(board); }

    public java.util.Optional<CreationVideo> reusable(String task, String owner, String promptSha256, long minDurationMs) {
        return mapper.reusable(task, owner, promptSha256, minDurationMs);
    }

    public CreationAsset assetByKey(String task, String owner, String runId, String sceneId) {
        return mapper.assetByKey(task, owner, "video-" + runId + "-" + sceneId).orElse(null);
    }
}
