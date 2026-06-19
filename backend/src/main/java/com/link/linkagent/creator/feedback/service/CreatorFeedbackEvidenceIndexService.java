package com.link.linkagent.creator.feedback.service;

import com.link.linkagent.creator.feedback.config.CreatorFeedbackRagProperties;
import com.link.linkagent.creator.feedback.mapper.CreatorFeedbackMapper;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackEvidenceIndexRequest;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackEvidenceIndexResponse;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackEvidenceIndexStatusResponse;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackEvidenceRetrievalResult;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackItemRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackStatRecord;
import com.link.linkagent.creator.feedback.util.CreatorFeedbackLabelUtil;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.settings.service.RuntimeSettingService;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 反馈证据向量索引服务。
 * <p>
 * 负责把当前任务的评论弹幕明细写入 Milvus，并把索引状态回写到 creator_feedback_item。
 * <p>
 * 设计要点：
 * <ol>
 *   <li>MySQL 仍是事实来源，Milvus 只是“可被语义检索的候选副本”。所以这里只写向量、回写状态，
 *       不把任何业务判断结果存进向量库。</li>
 *   <li>不在导入评论弹幕后自动索引全部明细，而是由用户显式触发、且默认上限 300 条，
 *       是为了让演示环境的 Embedding 成本与耗时可控。</li>
 *   <li>方法不加 {@code @Transactional}：Milvus 写入不受数据库事务管理，一旦回滚 MySQL 状态会和
 *       Milvus 实际写入分叉。按批提交、按批回写状态，才能让 embedding_status 真实反映向量库现状。</li>
 * </ol>
 */
@Service
public class CreatorFeedbackEvidenceIndexService {

    private static final Logger log = LoggerFactory.getLogger(CreatorFeedbackEvidenceIndexService.class);

    /** 单批送 Embedding 的文档数。一次 add 会把整批文本一起向量化，比逐条写大幅减少 Embedding 调用次数。 */
    private static final int EMBEDDING_BATCH_SIZE = 25;

    /** 单次索引硬上限，和接口校验 @Max(1000) 对齐，二次防御配置被误改成超大值。 */
    private static final int MAX_INDEX_HARD_LIMIT = 1000;

    /** 失败提示最多收集条数，避免大批失败时 warnings 过长。 */
    private static final int MAX_WARNINGS = 10;

    /** embedding_error 列是 VARCHAR(512)，失败原因摘要截断到 480，留出省略号余量。 */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 480;

    private final CreatorFeedbackRagProperties ragProperties;
    private final CreatorTaskMapper creatorTaskMapper;
    private final CreatorFeedbackMapper creatorFeedbackMapper;
    // 可选 VectorStore：默认 VECTOR_STORE_TYPE=none 时不存在该 Bean，用 ObjectProvider 避免启动强依赖。
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final RuntimeSettingService runtimeSettingService;

    public CreatorFeedbackEvidenceIndexService(CreatorFeedbackRagProperties ragProperties,
                                               CreatorTaskMapper creatorTaskMapper,
                                               CreatorFeedbackMapper creatorFeedbackMapper,
                                               ObjectProvider<VectorStore> vectorStoreProvider,
                                               RuntimeSettingService runtimeSettingService) {
        this.ragProperties = ragProperties;
        this.creatorTaskMapper = creatorTaskMapper;
        this.creatorFeedbackMapper = creatorFeedbackMapper;
        this.vectorStoreProvider = vectorStoreProvider;
        this.runtimeSettingService = runtimeSettingService;
    }

    /**
     * 重建当前任务反馈证据索引。
     * <p>
     * 异常约定：任务不存在 404；RAG 业务开关未启用 / Milvus 未就绪 / 没有可索引明细 400；
     * Embedding 或 Milvus 部分失败不报错，而是写入 failedCount 与 warnings，让用户看到具体哪几批没成功。
     */
    public CreatorFeedbackEvidenceIndexResponse rebuild(String taskId, CreatorFeedbackEvidenceIndexRequest request) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        if (!runtimeSettingService.isCreatorFeedbackRagEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "反馈追问 RAG 业务开关未启用，请先在设置面板打开 creator.feedback.rag.enabled");
        }
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Milvus 向量库未就绪，请确认 VECTOR_STORE_TYPE=milvus 且 EMBEDDING_MODEL_TYPE=openai");
        }

        int maxItems = resolveMaxItems(request);
        boolean includeNoise = request.includeNoise() != null
                ? request.includeNoise()
                : ragProperties.isIncludeNoiseDefault();

        List<CreatorFeedbackItemRecord> items = creatorFeedbackMapper.listIndexableItemsByTaskId(
                taskRecord.getTaskId(), maxItems, includeNoise);
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "当前任务没有可索引的评论弹幕明细，请先导入评论弹幕样例");
        }

        List<String> warnings = new ArrayList<>();
        int indexed = 0;
        int failed = 0;
        for (int start = 0; start < items.size(); start += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + EMBEDDING_BATCH_SIZE, items.size());
            List<CreatorFeedbackItemRecord> chunk = items.subList(start, end);
            try {
                List<Document> documents = chunk.stream()
                        .map(item -> toDocument(taskRecord.getTaskId(), item))
                        .toList();
                try (LlmUsageContext.UsageScope ignored = LlmUsageContext.open(taskRecord.getTaskId(), "反馈证据向量索引")) {
                    vectorStore.add(documents);
                }
                for (CreatorFeedbackItemRecord item : chunk) {
                    // embedding_id 复用 item_id，让向量文档与 MySQL 明细天然一一对应，回查时无需额外映射。
                    creatorFeedbackMapper.updateItemEmbeddingIndexed(
                            taskRecord.getTaskId(), item.getItemId(), item.getItemId());
                    indexed++;
                }
            } catch (Exception exception) {
                String reason = normalizeError(exception);
                for (CreatorFeedbackItemRecord item : chunk) {
                    creatorFeedbackMapper.updateItemEmbeddingFailed(taskRecord.getTaskId(), item.getItemId(), reason);
                    failed++;
                }
                if (warnings.size() < MAX_WARNINGS) {
                    warnings.add("第 " + (start + 1) + "-" + end + " 条索引失败：" + reason);
                }
                log.warn("反馈证据批量索引失败。taskId={}, range={}-{}", taskRecord.getTaskId(), start + 1, end, exception);
            }
        }

        // skippedCount 恒为 0：可索引明细已在 SQL 层按 is_deleted/is_noise 过滤，没有“查出来又跳过”的情况；
        // 字段保留，是为了后续接入“已索引则跳过”增量索引时不改响应结构。
        return new CreatorFeedbackEvidenceIndexResponse(
                taskRecord.getTaskId(),
                true,
                true,
                items.size(),
                indexed,
                0,
                failed,
                warnings,
                LocalDateTime.now()
        );
    }

    /**
     * 查询当前任务证据索引状态。
     * <p>
     * retrievalMode 是“如果现在追问预计走哪种检索”的预测值：RAG 启用、Milvus 就绪且已有 INDEXED 明细时
     * 预测走向量检索，否则按 SQL 检索展示，让前端在用户提问前就能提示当前检索方式。
     */
    public CreatorFeedbackEvidenceIndexStatusResponse status(String taskId) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        boolean ragEnabled = runtimeSettingService.isCreatorFeedbackRagEnabled();
        boolean vectorStoreReady = ragEnabled && vectorStoreProvider.getIfAvailable() != null;

        long total = 0;
        long indexed = 0;
        long pending = 0;
        long failed = 0;
        for (CreatorFeedbackStatRecord statRecord : creatorFeedbackMapper.countEmbeddingStatusByTaskId(taskRecord.getTaskId())) {
            long count = statRecord.getCount() == null ? 0 : statRecord.getCount();
            total += count;
            switch (TextUtil.trimToDefault(statRecord.getName(), "")) {
                case "INDEXED" -> indexed += count;
                case "PENDING" -> pending += count;
                case "FAILED" -> failed += count;
                default -> {
                    // SKIPPED 等状态只计入 total，不单列，避免响应字段无限扩张。
                }
            }
        }

        LocalDateTime lastIndexedAt = creatorFeedbackMapper.findLastEmbeddingUpdateTime(taskRecord.getTaskId());
        String retrievalMode = (vectorStoreReady && indexed > 0)
                ? CreatorFeedbackEvidenceRetrievalResult.MODE_VECTOR
                : CreatorFeedbackEvidenceRetrievalResult.MODE_SQL;

        return new CreatorFeedbackEvidenceIndexStatusResponse(
                taskRecord.getTaskId(),
                ragEnabled,
                vectorStoreReady,
                total,
                indexed,
                pending,
                failed,
                lastIndexedAt,
                retrievalMode
        );
    }

    private int resolveMaxItems(CreatorFeedbackEvidenceIndexRequest request) {
        // maxItems 为空用配置默认值；即使配置被误改成 0 或超大值，也用 [1, 1000] 兜底，二次防御高成本 Embedding。
        int requested = request.maxItems() != null ? request.maxItems() : ragProperties.getMaxIndexItems();
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, MAX_INDEX_HARD_LIMIT);
    }

    private Document toDocument(String taskId, CreatorFeedbackItemRecord item) {
        // metadata 存业务字段，taskId 用于向量过滤，其余字段用于排查；只放非空值，避免 Milvus 元数据出现 null。
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("taskId", taskId);
        metadata.put("itemId", item.getItemId());
        putIfNotNull(metadata, "sourceType", item.getSourceType());
        putIfNotNull(metadata, "category", item.getCategory());
        putIfNotNull(metadata, "sentiment", item.getSentiment());
        metadata.put("noise", Boolean.TRUE.equals(item.getNoise()));
        putIfNotNull(metadata, "likeCount", item.getLikeCount());
        putIfNotNull(metadata, "replyCount", item.getReplyCount());
        putIfNotNull(metadata, "occurTimeText", item.getOccurTimeText());
        return Document.builder()
                .id(item.getItemId())
                .text(buildDocumentText(item))
                .metadata(metadata)
                .build();
    }

    private String buildDocumentText(CreatorFeedbackItemRecord item) {
        // 文本里带中文分类/情绪标签，是为了让语义检索能匹配“哪些是提问/质疑/负面”这类问法，而不只是匹配原文。
        StringBuilder builder = new StringBuilder();
        builder.append("来源：").append(CreatorFeedbackLabelUtil.labelFor(item.getSourceType())).append('\n');
        builder.append("内容：").append(TextUtil.trimToDefault(item.getContent(), "")).append('\n');
        builder.append("分类：").append(CreatorFeedbackLabelUtil.labelFor(item.getCategory())).append('\n');
        builder.append("情绪：").append(CreatorFeedbackLabelUtil.labelFor(item.getSentiment()));
        if (TextUtil.hasText(item.getReason())) {
            builder.append('\n').append("分类原因：").append(item.getReason());
        }
        List<String> meta = new ArrayList<>();
        if (TextUtil.hasText(item.getOccurTimeText())) {
            meta.add("时间 " + item.getOccurTimeText());
        }
        if (item.getLikeCount() != null) {
            meta.add("点赞 " + item.getLikeCount());
        }
        if (item.getReplyCount() != null) {
            meta.add("回复 " + item.getReplyCount());
        }
        if (!meta.isEmpty()) {
            builder.append('\n').append(String.join("，", meta));
        }
        return builder.toString();
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

    private CreatorTaskRecord getTaskRecord(String taskId) {
        return creatorTaskMapper.findTaskByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在"));
    }
}
