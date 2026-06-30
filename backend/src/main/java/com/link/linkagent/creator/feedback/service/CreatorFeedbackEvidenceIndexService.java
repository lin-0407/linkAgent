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
 * 反馈证据向量索引服务（Milvus 写入 + 状态管理）。
 * <p>
 * 负责把当前任务的评论弹幕明细写入 Milvus，并把索引状态（INDEXED / FAILED）回写到 MySQL 的
 * creator_feedback_item 表的 embedding_status 列。
 * <p>
 * 在整体架构中的位置：本服务是”向量检索链路”的写入端，对应的读取端是
 * {@link CreatorFeedbackEvidenceRetrievalService}。两者通过 creator_feedback_item 的
 * embedding_status 状态字段协同——索引服务写状态，检索服务读状态来决定走向量检索还是 SQL 兜底。
 * <p>
 * 设计要点（why）：
 * <ol>
 *   <li><b>MySQL 仍是事实来源，Milvus 只是候选副本</b>：这里只写向量、回写状态，
 *       不把任何业务判断结果存进向量库。因为向量库的数据一致性保障弱于关系型数据库，
 *       一旦出现脏数据或版本不同步，MySQL 中的 itemId + taskId 组合仍然是唯一可信的。</li>
 *   <li><b>用户显式触发索引，而非导入后自动索引</b>：不在导入评论弹幕后自动索引全部明细，
 *       默认上限 300 条，让演示环境的 Embedding 成本与耗时可控。
 *       Milvus Embedding 每次调用都有 API 开销（调用 OpenAI Embedding 接口），
 *       几百条评论的自动索引可能耗时数十秒，阻塞导入流程。</li>
 *   <li><b>方法不加 @Transactional</b>：Milvus 写入不受数据库事务管理，一旦回滚 MySQL
 *       状态会和 Milvus 实际写入分叉（状态显示 FAILED 但向量已写入，或反之）。
 *       按批提交、按批回写状态，才能让 embedding_status 真实反映向量库现状。</li>
 *   <li><b>用 ObjectProvider 而非直接注入 VectorStore</b>：默认 VECTOR_STORE_TYPE=none 时
 *       没有这个 Bean，直接 @Autowired 会导致应用启动失败；ObjectProvider.getIfAvailable()
 *       允许运行时判断向量库是否可用。</li>
 * </ol>
 */
@Service
public class CreatorFeedbackEvidenceIndexService {

    private static final Logger log = LoggerFactory.getLogger(CreatorFeedbackEvidenceIndexService.class);

    /**
     * 单批送 Embedding 的文档数。
     * 为什么是 25：Spring AI VectorStore.add(List) 一次调用会把整批文本一起 Embedding，
     * 比逐条写（25 次 API 调用）大幅减少 Embedding 调用次数和网络往返。
     * 不宜过大——Embedding API 对单次请求文本总量有限制，且大批中一条失败会整批重试。
     */
    private static final int EMBEDDING_BATCH_SIZE = 25;

    /**
     * 单次索引硬上限，和接口校验 @Max(1000) 对齐。
     * 这是二次防御：即使配置文件的 rag.max-index-items 被误改成超大值，
     * 这里也会兜底截断，防止一次性向 Embedding API 提交数千条文本导致巨额费用。
     */
    private static final int MAX_INDEX_HARD_LIMIT = 1000;

    /** 失败提示最多收集条数。避免大批失败时 warnings 列表过长撑满响应。 */
    private static final int MAX_WARNINGS = 10;

    /**
     * 失败原因摘要的最大长度。
     * embedding_error 列是 VARCHAR(512)，截断到 480 给省略号留出余量，
     * 也避免 Embedding API 返回的超长错误信息撑爆数据库列。
     */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 480;

    /** 反馈 RAG 配置属性（索引上限、TopK、最小命中数等） */
    private final CreatorFeedbackRagProperties ragProperties;
    /** 创作任务数据访问层 */
    private final CreatorTaskMapper creatorTaskMapper;
    /** 反馈明细数据访问层（读写 embedding_status） */
    private final CreatorFeedbackMapper creatorFeedbackMapper;
    /**
     * 可选 VectorStore Bean。
     * 默认 VECTOR_STORE_TYPE=none 时不存在该 Bean，用 ObjectProvider 避免启动强依赖——
     * 这样演示环境不需要 Milvus 也能启动应用，只是在索引和检索时返回”向量库未就绪”的提示。
     */
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    /** 运行期设置服务，控制 RAG 业务开关（creator.feedback.rag.enabled） */
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
     * 重建当前任务反馈证据索引（全量覆盖）。
     * <p>
     * 索引流程：
     * <ol>
     *   <li>校验 RAG 业务开关已启用 + Milvus 已就绪</li>
     *   <li>查询当前任务的可索引明细（按 maxItems 和 includeNoise 过滤）</li>
     *   <li>按 EMBEDDING_BATCH_SIZE 分批向量化并写入 Milvus</li>
     *   <li>每批成功后逐个回写 embedding_status = INDEXED / FAILED</li>
     * </ol>
     * <p>
     * 为什么不是增量索引而是全量覆盖：当前版本不维护"哪些已索引、哪些待索引"的差异列表；
     * 全量重索引用 Milvus 的 add（幂等——同 id 覆盖）实现，简单可靠。
     * skippedCount 字段保留，是为了后续接入增量索引时不改响应结构。
     * <p>
     * 异常约定：
     * <ul>
     *   <li>任务不存在：404</li>
     *   <li>RAG 业务开关未启用 / Milvus 未就绪 / 无明细：400</li>
     *   <li>Embedding 或 Milvus 部分失败：不报错，写入 failedCount + warnings</li>
     * </ul>
     * <p>
     * Embedding 调用包裹在 LlmUsageContext 中，和 LLM 调用共享同一套 Langfuse 追踪体系。
     *
     * @param taskId 创作任务 ID
     * @param request 索引请求，含 maxItems 和 includeNoise 选项
     * @return 索引结果响应（含成功数、失败数、警告列表）
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
     * 查询当前任务证据索引状态（前端轮询展示用）。
     * <p>
     * retrievalMode 是”如果现在追问预计走哪种检索”的预测值：RAG 启用、Milvus 就绪且已有 INDEXED 明细时
     * 预测走向量检索，否则按 SQL 检索展示。让前端在用户提问前就能提示当前检索方式（”AI 正在阅读你的全部评论弹幕”
     * vs “AI 正在关键词匹配你的提问”），提升用户信任感。
     * <p>
     * 各状态统计通过 SQL 聚合查询一次获取，不做逐条遍历，避免索引状态查询拖慢页面加载。
     * SKIPPED 等状态只计入 total，不单列——避免响应字段随业务状态增加而无限扩张。
     *
     * @param taskId 创作任务 ID
     * @return 索引状态响应（含总数、各类状态计数、最后索引时间、预测检索模式）
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

    /**
     * 解析索引条数上限，多层防御。
     * <p>
     * 优先级：请求参数 > 配置文件默认值，再兜底 [1, MAX_INDEX_HARD_LIMIT]。
     * 即使配置文件的 rag.max-index-items 被误改成 0 或 100000，这里也强制收敛到安全范围。
     *
     * @param request 索引请求
     * @return 最终条数上限（1 ~ 1000）
     */
    private int resolveMaxItems(CreatorFeedbackEvidenceIndexRequest request) {
        // maxItems 为空用配置默认值；即使配置被误改成 0 或超大值，也用 [1, 1000] 兜底，二次防御高成本 Embedding。
        int requested = request.maxItems() != null ? request.maxItems() : ragProperties.getMaxIndexItems();
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, MAX_INDEX_HARD_LIMIT);
    }

    /**
     * 将 MySQL 明细记录转为 Milvus Document。
     * <p>
     * metadata 存业务字段——taskId 用于向量过滤（检索时只搜当前任务），
     * itemId 用于回查 MySQL（embedding_id 复用 item_id，向量文档与明细天然一一对应），
     * sourceType / category / sentiment 等用于排查。
     * 只放非空值——避免 Milvus 元数据出现 null，null 在某些 Milvus 版本中可能引发查询异常。
     * <p>
     * 文本内容（buildDocumentText）携带中文标签，让语义检索能匹配"哪些是提问/质疑/负面"这类问法，
     * 而不只是匹配原文——这利用了 Embedding 模型对中文语义的理解能力。
     *
     * @param taskId 创作任务 ID
     * @param item 评论弹幕明细记录
     * @return Spring AI Document 对象
     */
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

    /**
     * 构建供 Embedding 向量化的文档文本。
     * <p>
     * 文本格式中包含中文分类和情绪标签——这是关键设计：单纯将评论原文 Embedding 只能做"近似句子匹配"，
     * 加上"来源：评论"、"分类：提问"、"情绪：负面"后，Embedding 模型能在语义空间中将
     * "哪些评论在质疑我的方法"这样的抽象问法定位到带有 DOUBT / COMPLAINT / NEGATIVE 标签的条目。
     * <p>
     * 附加元数据（时间、点赞、回复）以"时间 XX:XX，点赞 N，回复 N"的形式拼在末尾，
     * 即使不用于语义匹配，也能在排查索引质量时直接从 Milvus 文本中看到对应信息。
     *
     * @param item 评论弹幕明细记录
     * @return 格式化后的文档文本
     */
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

    /**
     * 条件写入元数据：只写入非 null 值。
     * 避免 Milvus 元数据中出现 null，某些版本可能因此引发查询异常。
     *
     * @param metadata 元数据 Map
     * @param key 键名
     * @param value 值（null 时跳过）
     */
    private void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    /**
     * 标准化错误消息用于写入 embedding_error 列。
     * <p>
     * 压缩空白字符并截断至 ERROR_MESSAGE_MAX_LENGTH——
     * Embedding API 的异常消息可能包含很长的 JSON 响应或堆栈跟踪，
     * 直接存会导致 embedding_error VARCHAR(512) 列溢出或截断出乱码。
     *
     * @param exception 异常对象
     * @return 截断后的错误消息
     */
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
