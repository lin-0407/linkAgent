package com.link.linkagent.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.ReferenceIndexCleanupRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoImportRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

/** 日期和指数回填、关系库分批删除与外部索引重试，不访问 B 站或模型。 */
@Service
public class KnowledgeReferenceMaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeReferenceMaintenanceService.class);
    private final KnowledgeReferenceVideoMapper mapper;
    private final KnowledgeQualityScoringService scoring;
    private final KnowledgeReferenceIndexCleanupService indexCleanup;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;
    private final boolean scheduledEnabled;

    public KnowledgeReferenceMaintenanceService(KnowledgeReferenceVideoMapper mapper,
            KnowledgeQualityScoringService scoring, KnowledgeReferenceIndexCleanupService indexCleanup,
            TransactionTemplate transaction, ObjectMapper objectMapper,
            @Value("${knowledge.reference-maintenance.enabled:true}") boolean scheduledEnabled) {
        this.mapper = mapper;
        this.scoring = scoring;
        this.indexCleanup = indexCleanup;
        this.transaction = transaction;
        this.objectMapper = objectMapper;
        this.scheduledEnabled = scheduledEnabled;
    }

    public Map<String, Object> status(List<String> bvIds) {
        return Map.of("existingBvIds", mapper.findExistingBvIds(bvIds));
    }

    public Map<String, Object> backfill(long afterId, int limit) {
        var records = mapper.listMetadataBackfill(afterId, limit);
        int updated = 0;
        for (var record : records) {
            var published = ReferenceVideoCollectionPolicy.parseLegacyTime(record.getPublishTimeText());
            if (published != null && published.isAfter(LocalDateTime.now(ReferenceVideoCollectionPolicy.ZONE))) published = null;
            var stats = new ReferenceVideoImportRequest.VideoStats(record.getViewCount(), record.getLikeCount(),
                    record.getCoinCount(), record.getFavoriteCount(), record.getDanmakuCount(), record.getReplyCount());
            var score = ReferenceVideoCollectionPolicy.storedScore(ReferenceVideoCollectionPolicy.score(stats));
            updated += mapper.backfillCollectionMetadata(record.getVideoId(), published, score, ReferenceVideoCollectionPolicy.VERSION);
        }
        long nextId = records.isEmpty() ? afterId : records.getLast().getId();
        return Map.of("updatedCount", updated, "nextId", nextId, "hasMore", records.size() == limit);
    }

    public synchronized Map<String, Object> purge(int batchSize) {
        LocalDateTime cutoff = LocalDateTime.now(ReferenceVideoCollectionPolicy.ZONE).minusYears(1);
        int deleted = transaction.execute(status -> {
            var videos = mapper.lockExpiredVideos(cutoff, batchSize);
            Set<String> categories = new LinkedHashSet<>();
            for (var video : videos) {
                var items = mapper.listAllItemIds(video.getVideoId());
                var chunks = mapper.listAllChunkIds(video.getVideoId());
                // 事务先保存文档 ID，再删正文；数据库回滚时两者一起回滚，不留下不可重试的索引。
                mapper.enqueueIndexCleanup(video.getVideoId(), json(items), json(chunks));
                mapper.deleteReferenceItems(video.getVideoId());
                mapper.deleteReferenceChunks(video.getVideoId());
                mapper.deleteExpiredVideo(video.getVideoId(), cutoff);
                categories.add(video.getCategory());
            }
            scoring.recomputeCategories(categories);
            return videos.size();
        });
        int indexCleaned = 0;
        for (ReferenceIndexCleanupRecord job : mapper.listPendingIndexCleanup(Math.min(batchSize, 50))) {
            try {
                indexCleanup.delete(job);
                mapper.completeIndexCleanup(job.videoId());
                indexCleaned++;
            } catch (Exception error) {
                mapper.failIndexCleanup(job.videoId(), error.getClass().getSimpleName());
                // MySQL 已删的数据不会因为 Milvus 断线重新出现；只保留 ID，下一次继续删。
                log.warn("过期参考视频索引待重试：videoId={}, error={}", job.videoId(), error.getClass().getSimpleName());
                break;
            }
        }
        return Map.of("cutoff", cutoff.toString(), "deletedCount", deleted, "indexCleanedCount", indexCleaned,
                "pendingIndexCleanupCount", mapper.countPendingIndexCleanup(), "remainingExpiredCount", mapper.countExpiredVideos(cutoff));
    }

    @Scheduled(initialDelayString = "${knowledge.reference-maintenance.initial-delay-ms:60000}",
            fixedDelayString = "${knowledge.reference-maintenance.fixed-delay-ms:21600000}")
    public void scheduledPurge() {
        if (!scheduledEnabled) return;
        try {
            log.info("参考视频过期清理结果：{}", purge(100));
        } catch (RuntimeException error) {
            log.warn("参考视频清理失败，本批事务已回滚，下轮重试。", error);
        }
    }

    private String json(List<String> ids) {
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("清理文档 ID 序列化失败", error);
        }
    }
}
