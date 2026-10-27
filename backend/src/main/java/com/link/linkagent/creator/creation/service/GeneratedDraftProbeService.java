package com.link.linkagent.creator.creation.service;

import com.link.linkagent.creator.creation.model.GeneratedDraftLease;
import com.link.linkagent.creator.creation.render.RenderExecutionException;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.probe.service.FfprobeMediaProbeService;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.BooleanSupplier;

/** 私有原片网络读取在事务外执行，迟到结果必须重新经过制作租约与探测 token。 */
@Service
@ConditionalOnProperty(name = {"creator.media.enabled", "creator.creation.renderer.enabled"}, havingValue = "true")
public class GeneratedDraftProbeService {
    private final CreatorMediaProperties properties;
    private final ObjectStorageService storage;
    private final FfprobeMediaProbeService ffprobe;
    private final GeneratedDraftProbePersistenceService persistence;

    public GeneratedDraftProbeService(CreatorMediaProperties properties, ObjectStorageService storage,
            FfprobeMediaProbeService ffprobe, GeneratedDraftProbePersistenceService persistence) {
        this.properties = properties; this.storage = storage; this.ffprobe = ffprobe; this.persistence = persistence;
    }

    @Transactional(propagation = Propagation.NEVER)
    public DraftVideoRecord probe(GeneratedDraftLease lease, String versionId, BooleanSupplier cancelled) {
        check(cancelled);
        properties.validateEnabledConfiguration();
        var claim = persistence.begin(lease, versionId);
        if (claim.attemptId() == null) return claim.draft();
        try {
            check(cancelled);
            var read = storage.presignGetObject(claim.draft().bucketName(), claim.draft().objectKey(),
                    properties.getProcessing().getProviderReadTtl());
            check(cancelled);
            var result = ffprobe.probe(read.url(), cancelled);
            check(cancelled);
            if (result.durationMs() > properties.getMaxDurationMs())
                throw new RenderExecutionException("SOURCE_PROBE_MISMATCH", "原片实测时长超过部署支持范围");
            return persistence.complete(lease, versionId, claim.attemptId(), result);
        } catch (RuntimeException e) {
            check(cancelled);
            // 失败同样是受保护的结果；失租者不能把新执行者的探测改成失败。
            persistence.fail(lease, versionId, claim.attemptId());
            if (e instanceof RenderExecutionException render) throw render;
            throw new RenderExecutionException("SOURCE_PROBE_FAILED", "系统成片原片探测失败，请检查媒体服务后重试");
        }
    }

    private static void check(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
            throw new RenderExecutionException("CANCELLED", "本次原片探测已停止");
    }
}
