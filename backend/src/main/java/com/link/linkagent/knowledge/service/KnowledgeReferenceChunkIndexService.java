package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.config.KnowledgeRagProperties;
import com.link.linkagent.knowledge.config.KnowledgeVectorStore;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.ReferenceVideoChunkRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoChunkIndexRow;
import com.link.linkagent.knowledge.model.ReferenceVideoEmbeddingStatusCount;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexStatusResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoItemRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoRecord;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 案例库主题中块向量索引服务。
 * <p>
 * 把 {@code creator_reference_video_chunk} 写入独立中块集合。中块是三层分块里的中间层：
 * 父卡片负责全局上下文，子条目负责原始证据，中块负责标题包装 / 内容定位 / 反馈主题这类可解释主题。
 */
@Service
public class KnowledgeReferenceChunkIndexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReferenceChunkIndexService.class);

    private static final int MAX_INDEX_HARD_LIMIT = 1000;
    private static final int MAX_WARNINGS = 10;
    private static final int ERROR_MESSAGE_MAX_LENGTH = 480;
    private static final int CHUNK_DOC_TEXT_MAX_CHARS = 1800;
    private static final String MODE_VECTOR = "VECTOR";
    private static final String MODE_SQL = "SQL";

    private final KnowledgeRagProperties knowledgeRagProperties;
    private final KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper;
    private final KnowledgeVectorStore knowledgeVectorStore;
    private final KnowledgeReferenceChunkService knowledgeReferenceChunkService;

    public KnowledgeReferenceChunkIndexService(KnowledgeRagProperties knowledgeRagProperties,
                                               KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper,
                                               KnowledgeVectorStore knowledgeVectorStore,
                                               KnowledgeReferenceChunkService knowledgeReferenceChunkService) {
        this.knowledgeRagProperties = knowledgeRagProperties;
        this.knowledgeReferenceVideoMapper = knowledgeReferenceVideoMapper;
        this.knowledgeVectorStore = knowledgeVectorStore;
        this.knowledgeReferenceChunkService = knowledgeReferenceChunkService;
    }

    /**
     * 重建（增量）主题中块向量索引。
     * RAG 未启用 / 中块向量库未就绪 / 无待索引中块 → 400；批次失败不整体回滚，只回写失败状态与 warnings。
     */
    public ReferenceVideoIndexResponse rebuildChunks(ReferenceVideoIndexRequest request) {
        if (!knowledgeRagProperties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "知识库 RAG 业务开关未启用，请先设置 knowledge.rag.enabled=true");
        }
        if (!knowledgeVectorStore.isChunkReady()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "知识库主题中块向量库未就绪，请确认 Milvus 已连接、配置了 EmbeddingModel，且中块集合初始化成功");
        }
        VectorStore chunkStore = knowledgeVectorStore.getChunkVectorStore()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "知识库主题中块向量库未就绪"));

        int maxItems = resolveMaxItems(request);
        int batchSize = Math.max(1, knowledgeRagProperties.getIndexBatchSize());
        List<String> warnings = new ArrayList<>();
        backfillMissingChunks(maxItems, warnings);
        List<ReferenceVideoChunkIndexRow> rows = knowledgeReferenceVideoMapper.listIndexableChunks(maxItems);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "没有待索引的主题中块（PENDING / FAILED），请先导入案例，或确认是否已全部索引完成");
        }

        int indexed = 0;
        int failed = 0;
        for (int start = 0; start < rows.size(); start += batchSize) {
            int end = Math.min(start + batchSize, rows.size());
            List<ReferenceVideoChunkIndexRow> chunk = rows.subList(start, end);
            try {
                List<Document> documents = chunk.stream().map(this::toChunkDocument).toList();
                try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("知识库主题中块向量索引")) {
                    chunkStore.add(documents);
                }
                for (ReferenceVideoChunkIndexRow row : chunk) {
                    knowledgeReferenceVideoMapper.updateChunkEmbeddingIndexed(row.getChunkId(), row.getChunkId());
                    indexed++;
                }
            } catch (Exception exception) {
                String reason = normalizeError(exception);
                for (ReferenceVideoChunkIndexRow row : chunk) {
                    knowledgeReferenceVideoMapper.updateChunkEmbeddingFailed(row.getChunkId(), reason);
                    failed++;
                }
                if (warnings.size() < MAX_WARNINGS) {
                    warnings.add("第 " + (start + 1) + "-" + end + " 条主题中块索引失败：" + reason);
                }
                log.warn("主题中块向量索引失败。range={}-{}", start + 1, end, exception);
            }
        }

        return new ReferenceVideoIndexResponse(
                true, true, rows.size(), indexed, 0, failed, warnings, LocalDateTime.now());
    }

    /**
     * 为历史案例补齐主题中块。
     * 这一步放在中块索引服务里，是因为它只服务于三层分块索引升级，不改变原导入接口的响应契约。
     */
    private void backfillMissingChunks(int limit, List<String> warnings) {
        List<ReferenceVideoRecord> videos = knowledgeReferenceVideoMapper.listVideosMissingChunks(limit);
        if (videos.isEmpty()) {
            return;
        }
        int generated = 0;
        for (ReferenceVideoRecord video : videos) {
            try {
                List<ReferenceVideoItemRecord> items =
                        knowledgeReferenceVideoMapper.listItemsByVideoId(video.getVideoId(), 200);
                for (ReferenceVideoChunkRecord chunk : knowledgeReferenceChunkService.buildChunks(video, items)) {
                    knowledgeReferenceVideoMapper.insertReferenceVideoChunk(chunk);
                    generated++;
                }
            } catch (Exception exception) {
                String reason = normalizeError(exception);
                if (warnings.size() < MAX_WARNINGS) {
                    warnings.add("视频 " + video.getVideoId() + " 主题中块补齐失败：" + reason);
                }
                log.warn("历史视频主题中块补齐失败。videoId={}", video.getVideoId(), exception);
            }
        }
        if (generated > 0) {
            log.info("已为历史案例补齐主题中块：videos={}, chunks={}", videos.size(), generated);
        }
    }

    /**
     * 查询主题中块向量索引状态。RAG 关闭时照常返回，用于确认中块层是否处于降级状态。
     */
    public ReferenceVideoIndexStatusResponse chunkStatus() {
        boolean ragEnabled = knowledgeRagProperties.isEnabled();
        boolean chunkReady = ragEnabled && knowledgeVectorStore.isChunkReady();

        long total = 0;
        long indexed = 0;
        long pending = 0;
        long failed = 0;
        for (ReferenceVideoEmbeddingStatusCount row : knowledgeReferenceVideoMapper.countChunkEmbeddingStatus()) {
            long count = row.getCount();
            total += count;
            switch (TextUtil.trimToDefault(row.getStatus(), "")) {
                case "INDEXED" -> indexed += count;
                case "PENDING" -> pending += count;
                case "FAILED" -> failed += count;
                default -> {
                    // 其它状态仅计入 total，避免响应结构为少见状态扩张。
                }
            }
        }

        LocalDateTime lastIndexedAt = knowledgeReferenceVideoMapper.findLastChunkEmbeddingUpdateTime();
        String retrievalMode = (chunkReady && indexed > 0) ? MODE_VECTOR : MODE_SQL;
        return new ReferenceVideoIndexStatusResponse(
                ragEnabled, chunkReady, total, indexed, pending, failed, lastIndexedAt, retrievalMode);
    }

    private Document toChunkDocument(ReferenceVideoChunkIndexRow row) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chunkId", row.getChunkId());
        metadata.put("videoId", row.getVideoId());
        metadata.put("chunkType", row.getChunkType());
        putIfNotNull(metadata, "chunkTitle", row.getChunkTitle());
        putIfNotNull(metadata, "category", row.getCategory());
        putIfNotNull(metadata, "tier", row.getTier());
        return Document.builder()
                .id(row.getChunkId())
                .text(buildChunkDocumentText(row))
                .metadata(metadata)
                .build();
    }

    /**
     * 中块文档显式带上类型和标题，是为了让「标题怎么包装」「内容定位」「观众反馈」这类查询更容易命中正确主题层。
     */
    private String buildChunkDocumentText(ReferenceVideoChunkIndexRow row) {
        StringBuilder builder = new StringBuilder();
        builder.append("主题类型：").append(chunkTypeLabel(row.getChunkType())).append('\n');
        if (TextUtil.hasText(row.getChunkTitle())) {
            builder.append("主题标题：").append(row.getChunkTitle()).append('\n');
        }
        builder.append(TextUtil.trimToDefault(row.getChunkContent(), ""));
        return TextUtil.abbreviateWithSuffix(builder.toString().trim(), CHUNK_DOC_TEXT_MAX_CHARS, "...");
    }

    private String chunkTypeLabel(String chunkType) {
        if (chunkType == null) {
            return "未知主题";
        }
        return switch (chunkType) {
            case "TITLE_PACKAGE" -> "标题包装";
            case "CONTENT_POSITIONING" -> "内容定位";
            case "AUDIENCE_FEEDBACK_SUMMARY" -> "观众反馈";
            default -> chunkType;
        };
    }

    private int resolveMaxItems(ReferenceVideoIndexRequest request) {
        int requested = (request != null && request.maxItems() != null)
                ? request.maxItems()
                : knowledgeRagProperties.getMaxIndexItems();
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, MAX_INDEX_HARD_LIMIT);
    }

    private void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private String normalizeError(Exception exception) {
        String message = exception.getMessage();
        if (TextUtil.isBlank(message)) {
            message = exception.getClass().getSimpleName();
        }
        return TextUtil.abbreviateWithSuffix(message.replaceAll("\\s+", " ").trim(), ERROR_MESSAGE_MAX_LENGTH, "...");
    }
}
