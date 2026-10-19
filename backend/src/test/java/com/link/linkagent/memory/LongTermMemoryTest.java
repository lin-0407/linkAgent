package com.link.linkagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class LongTermMemoryTest {

    @Test
    void shouldTrimAndSaveMemory() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        LongTermMemory memory = new LongTermMemory(mapper);

        memory.save(" user-1 ", " user.preference.language ", " Java ", " session-1 ");

        assertThat(mapper.savedRecord.getUserId()).isEqualTo("user-1");
        assertThat(mapper.savedRecord.getMemoryKey()).isEqualTo("user.preference.example_language");
        assertThat(mapper.savedRecord.getContent()).isEqualTo("Java");
        assertThat(mapper.savedRecord.getSourceSessionId()).isEqualTo("session-1");
    }

    @Test
    void shouldNormalizeSimilarPreferenceKeysToSameMemoryKey() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        LongTermMemory memory = new LongTermMemory(mapper);

        memory.save("user-1", "user.preference.programming_language", "C++", null);

        assertThat(mapper.savedRecord.getMemoryKey()).isEqualTo("user.preference.example_language");

        memory.save("user-1", "user.preference.example", "Java", null);

        assertThat(mapper.savedRecord.getMemoryKey()).isEqualTo("user.preference.example_language");
    }

    @Test
    void shouldInsertNewRecordInsteadOfOverwritingSameKey() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        LongTermMemory memory = new LongTermMemory(mapper);

        memory.save("user-1", "user.preference.explanation_style", "用户喜欢简洁解释", null);
        memory.save("user-1", "user.preference.explanation_style", "用户要求详细展开", null);

        // 步骤 6 的核心：同 key 的不同事实各自成条，不再由唯一键覆盖（设计文档 D13）
        assertThat(mapper.insertedRecords).hasSize(2);
        assertThat(mapper.insertedRecords).extracting(LongTermMemoryRecord::getContent)
                .containsExactly("用户喜欢简洁解释", "用户要求详细展开");
    }

    @Test
    void shouldStoreAndRetrievePersonalEventWithoutChangingItsCategory() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        LongTermMemory memory = new LongTermMemory(mapper);

        memory.save("user-1", "user.event", "首次保养汽车后 GPS 出现故障", "session-1", 88);
        mapper.retrievalRecords = new ArrayList<>(mapper.insertedRecords);

        // 事件键会原样入库，读路径也不会按画像分类过滤，下一会话仍可取到事件正文。
        assertThat(mapper.savedRecord.getMemoryKey()).isEqualTo("user.event");
        assertThat(memory.retrieveForContext("user-1", "首次保养后发生了什么"))
                .extracting(LongTermMemoryRecord::getContent)
                .containsExactly("首次保养汽车后 GPS 出现故障");
    }

    @Test
    void shouldReturnSavedRecordReadBackByGeneratedId() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        LongTermMemory memory = new LongTermMemory(mapper);

        Optional<LongTermMemoryRecord> saved = memory.save("user-1", "key", "content", null);

        // 主键由数据库回填，返回值必须按这个主键回读，管理页才能拿到新记录的 id
        assertThat(mapper.foundId).isEqualTo(mapper.savedRecord.getId());
        assertThat(saved).containsSame(mapper.savedRecord);
    }

    @Test
    void shouldReturnEmptyWhenSavedRecordCannotBeReadBack() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        mapper.readBackOverride = Optional.empty();
        LongTermMemory memory = new LongTermMemory(mapper);

        assertThat(memory.save("user-1", "key", "content", null)).isEmpty();
    }

    @Test
    void shouldFindMemoryByIdWithUserIdFilter() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        LongTermMemory memory = new LongTermMemory(mapper);

        memory.findById(" user-1 ", 42L);

        assertThat(mapper.foundUserId).isEqualTo("user-1");
        assertThat(mapper.foundId).isEqualTo(42L);
    }

    @Test
    void shouldDeleteMemoryById() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        LongTermMemory memory = new LongTermMemory(mapper);

        memory.delete(" user-1 ", 42L);

        assertThat(mapper.deletedUserId).isEqualTo("user-1");
        assertThat(mapper.deletedId).isEqualTo(42L);
    }

    @Test
    void shouldRestoreSoftDeletedMemoryWithoutOverwritingItsContent() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        LongTermMemoryRecord restoredRecord = new LongTermMemoryRecord();
        restoredRecord.setId(42L);
        restoredRecord.setContent("保留的原始内容");
        mapper.readBackOverride = Optional.of(restoredRecord);
        LongTermMemory memory = new LongTermMemory(mapper);

        Optional<LongTermMemoryRecord> result = memory.restore(" user-1 ", 42L);

        assertThat(mapper.restoredUserId).isEqualTo("user-1");
        assertThat(mapper.restoredId).isEqualTo(42L);
        assertThat(mapper.foundId).isEqualTo(42L);
        assertThat(result).containsSame(restoredRecord);
        // 恢复不能走写入路径，否则会用当前入参覆盖原内容、来源会话和向量标识
        assertThat(mapper.insertedRecords).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenRestoreDoesNotMatchUserDeletedRow() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        // 被新事实取代的记忆恢复不到（D15）：UPDATE 影响 0 行，回读也是空
        mapper.readBackOverride = Optional.empty();
        LongTermMemory memory = new LongTermMemory(mapper);

        assertThat(memory.restore("user-1", 42L)).isEmpty();
    }

    @Test
    void shouldUpdateExistingMemoryByDecision() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        LongTermMemory memory = new LongTermMemory(mapper);

        memory.update(" user-1 ", 27L, " 用户偏好简洁解释并配例子 ", 85);

        assertThat(mapper.updatedUserId).isEqualTo("user-1");
        assertThat(mapper.updatedId).isEqualTo(27L);
        assertThat(mapper.updatedContent).isEqualTo("用户偏好简洁解释并配例子");
        assertThat(mapper.updatedImportance).isEqualTo(85);
    }

    @Test
    void shouldMarkMemoryDeletedByDecisionWithSupersededBy() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        LongTermMemory memory = new LongTermMemory(mapper);

        memory.markDeleted(" user-1 ", 30L, 101L);

        // 被取代的失效必须带上 superseded_by：这是它与管理页「用户删除」的区别（D15）
        assertThat(mapper.supersededUserId).isEqualTo("user-1");
        assertThat(mapper.supersededId).isEqualTo(30L);
        assertThat(mapper.supersededBy).isEqualTo(101L);
    }

    @Test
    void shouldClampImportanceWhenUpdating() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        LongTermMemory memory = new LongTermMemory(mapper);

        memory.update("user-1", 27L, "内容", 150);

        assertThat(mapper.updatedImportance).isEqualTo(100);
    }

    @Test
    void shouldNormalizeEmptySourceSessionId() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        LongTermMemory memory = new LongTermMemory(mapper);

        memory.save("user-1", "key", "content", " ");

        assertThat(mapper.savedRecord.getSourceSessionId()).isNull();
    }

    @Test
    void shouldSaveImportanceScoreWithMemory() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        LongTermMemory memory = new LongTermMemory(mapper);

        memory.save("user-1", "user.profile.summary", "用户是 Java 后端学习者", "session-1", 85);

        // 压缩抽取的高分事实点必须带上分数：步骤 8 的三维打分要用它作重要性维度
        assertThat(mapper.savedRecord.getImportance()).isEqualTo(85);
    }

    @Test
    void shouldClampImportanceScoreToValidRange() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        LongTermMemory memory = new LongTermMemory(mapper);

        memory.save("user-1", "key", "content", null, 150);
        assertThat(mapper.savedRecord.getImportance()).isEqualTo(100);

        memory.save("user-1", "key", "content", null, -3);
        assertThat(mapper.savedRecord.getImportance()).isZero();
    }

    @Test
    void shouldMarkImportanceAsUnscoredWhenSaveWithoutScore() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        LongTermMemory memory = new LongTermMemory(mapper);

        memory.save("user-1", "key", "content", null);

        // 逐轮抽取的旧写入路径还没有分数，用 0 表示未打分，不能被误当成低分事实
        assertThat(mapper.savedRecord.getImportance()).isZero();
    }

    @Test
    void shouldListAllMemoriesByUser() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        LongTermMemory memory = new LongTermMemory(mapper);

        memory.listByUser(" user-1 ");

        assertThat(mapper.listUserId).isEqualTo("user-1");
    }

    @Test
    void shouldRetrieveTopKFromAllCandidatesByScore() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        // 三条候选，两条高分一条低分：设计文档 D1 要求「先在全部记忆上打分再取 top-K」，
        // 所以 top-K 是 Java 侧截断，而不是让数据库先按更新时间砍成 10 条（那会预先吃掉时效性维度）。
        mapper.retrievalRecords = List.of(
                record(1L, "低分记忆", 10, null),
                record(2L, "高分记忆", 90, null),
                record(3L, "中分记忆", 60, null));
        LongTermMemory memory = new LongTermMemory(mapper, MemoryEmbeddingService.disabled(),
                new MemoryRetrievalProperties(2, 0.99, 0.5, 3.0, 2.0, 5, 10, 50));

        List<LongTermMemoryRecord> memories = memory.retrieveForContext(" user-1 ", "任意问题");

        assertThat(mapper.retrievalUserId).isEqualTo("user-1");
        assertThat(memories).extracting(LongTermMemoryRecord::getId).containsExactly(2L, 3L);
    }

    @Test
    void shouldUseRelevanceToWakeUpOldLowImportanceMemory() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        // A 是「和本轮问题相关但重要性低」的旧记忆，B 是「不相关但重要性高」的记忆：
        // D11 第 1 条要求唤醒老记忆靠相关性而不是重要性，权重也要能体现出这一点。
        LongTermMemoryRecord relevant = record(11L, "用户用 SM58 录音", 5, "1.0,0.0");
        LongTermMemoryRecord irrelevant = record(12L, "用户住在上海", 100, "0.0,1.0");
        mapper.retrievalRecords = List.of(irrelevant, relevant);
        Map<String, float[]> vectors = new HashMap<>();
        vectors.put("录音设备怎么选", new float[]{1.0f, 0.0f});
        StubEmbeddingService embeddingService = new StubEmbeddingService(true,
                text -> vectors.getOrDefault(text, new float[]{0.0f, 1.0f}));
        LongTermMemory memory = new LongTermMemory(mapper, embeddingService,
                new MemoryRetrievalProperties(1, 0.99, 0.5, 3.0, 2.0, 5, 10, 50));

        List<LongTermMemoryRecord> memories = memory.retrieveForContext("user-1", "录音设备怎么选");

        assertThat(memories).extracting(LongTermMemoryRecord::getId).containsExactly(11L);
    }

    @Test
    void shouldEmitContextMemoriesInStableIdOrder() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        // id=2 的重要性更高，分数序会把它排在前面；输出顺序必须按 id 升序（D12）
        mapper.retrievalRecords = List.of(
                record(1L, "用户偏好使用 Java", 10, null),
                record(2L, "项目是个人工作台", 90, null));
        LongTermMemory memory = new LongTermMemory(mapper, MemoryEmbeddingService.disabled(),
                new MemoryRetrievalProperties(5, 0.99, 0.5, 3.0, 2.0, 5, 10, 50));

        List<LongTermMemoryRecord> memories = memory.retrieveForContext("user-1", "随便问一句");

        // 同一批记忆换个问题也必须拼出同样的文本：这段在最前面，顺序一变前缀缓存整段作废
        assertThat(memories).extracting(LongTermMemoryRecord::getId).containsExactly(1L, 2L);
    }

    @Test
    void shouldKeepDecisionRecallInScoreOrder() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        mapper.retrievalRecords = List.of(
                record(1L, "用户偏好使用 Java", 10, null),
                record(2L, "项目是个人工作台", 90, null));
        LongTermMemory memory = new LongTermMemory(mapper, MemoryEmbeddingService.disabled(),
                new MemoryRetrievalProperties(5, 0.99, 0.5, 3.0, 2.0, 5, 10, 50));

        List<LongTermMemoryRecord> memories = memory.retrieveForDecision("user-1", List.of("候选事实"), 5);

        // 决策召回不进每轮上下文，保持「最相关的排前面」；稳定重排只作用于上下文注入那条路径
        assertThat(memories).extracting(LongTermMemoryRecord::getId).containsExactly(2L, 1L);
    }

    @Test
    void shouldBackfillMissingEmbeddingAndWriteBackAccessTime() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        mapper.retrievalRecords = List.of(record(21L, "用户偏好简洁解释", 70, null));
        StubEmbeddingService embeddingService = new StubEmbeddingService(true, text -> new float[]{1.0f, 0.0f});
        LongTermMemory memory = new LongTermMemory(mapper, embeddingService,
                new MemoryRetrievalProperties(5, 0.99, 0.5, 3.0, 2.0, 5, 10, 50));

        memory.retrieveForContext("user-1", "解释风格");

        // 存量记忆（或写入时向量化失败的记忆）在读取路径上补算并落库，本次检索立刻能用上（D4）
        assertThat(mapper.updatedEmbeddingId).isEqualTo(21L);
        assertThat(mapper.updatedEmbedding).isEqualTo("1.0,0.0");
        // 入选记忆写回访问时间（D6）：不写回就会退化成「按创建时间排序」
        assertThat(mapper.accessedIds).containsExactly(21L);
        assertThat(mapper.accessedTime).isNotNull();
    }

    @Test
    void shouldSkipBackfillWhenEmbeddingUnavailable() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        mapper.retrievalRecords = List.of(record(31L, "内容", 70, null));
        StubEmbeddingService embeddingService = new StubEmbeddingService(false, text -> new float[]{1.0f, 0.0f});
        LongTermMemory memory = new LongTermMemory(mapper, embeddingService,
                new MemoryRetrievalProperties(5, 0.99, 0.5, 3.0, 2.0, 5, 10, 50));

        List<LongTermMemoryRecord> memories = memory.retrieveForContext("user-1", "问题");

        // 没有向量能力时按「时效 + 重要」两维降级：不补算、不写向量，但记忆照常返回
        assertThat(memories).hasSize(1);
        assertThat(mapper.updatedEmbeddingId).isNull();
        assertThat(embeddingService.embeddedTexts).isEmpty();
    }

    @Test
    void shouldNotWriteBackAccessTimeForDecisionRecall() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        mapper.retrievalRecords = List.of(record(41L, "用户偏好简洁解释", 70, "1.0,0.0"));
        StubEmbeddingService embeddingService = new StubEmbeddingService(true, text -> new float[]{1.0f, 0.0f});
        LongTermMemory memory = new LongTermMemory(mapper, embeddingService,
                new MemoryRetrievalProperties(5, 0.99, 0.5, 3.0, 2.0, 5, 10, 50));

        memory.retrieveForDecision("user-1", List.of("用户喜欢详细解释"), 10);

        // 决策召回只是「看了一眼」，不算被取用：写回会把时效性维度污染成「最近被决策看过的排前面」
        assertThat(mapper.accessedIds).isEmpty();
    }

    @Test
    void shouldRecomputeEmbeddingWhenContentUpdated() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        StubEmbeddingService embeddingService = new StubEmbeddingService(true, text -> new float[]{0.5f, 0.5f});
        LongTermMemory memory = new LongTermMemory(mapper, embeddingService,
                new MemoryRetrievalProperties());

        memory.update("user-1", 27L, " 新的内容 ", 85);

        // 内容变了向量必须跟着重算，否则相关性维度会一直拿旧内容的向量算相似度
        assertThat(mapper.updatedEmbeddingId).isEqualTo(27L);
        assertThat(mapper.updatedEmbedding).isEqualTo("0.5,0.5");
    }

    @Test
    void shouldEmbedContentOnSaveWhenVectorEnabled() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        StubEmbeddingService embeddingService = new StubEmbeddingService(true, text -> new float[]{0.9f, 0.1f});
        LongTermMemory memory = new LongTermMemory(mapper, embeddingService, new MemoryRetrievalProperties());

        memory.save("user-1", "key", "用户偏好简洁解释", null, 70);

        // D4：写入时算一次向量并随插入落库
        assertThat(mapper.savedRecord.getEmbedding()).isEqualTo("0.9,0.1");
    }

    @Test
    void shouldKeepRetrievalWorkingWhenAccessTimeWriteFails() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        mapper.retrievalRecords = List.of(record(51L, "用户用 SM58 录音", 70, "1.0,0.0"));
        mapper.failAccessWrite = true;
        StubEmbeddingService embeddingService = new StubEmbeddingService(true, text -> new float[]{1.0f, 0.0f});
        LongTermMemory memory = new LongTermMemory(mapper, embeddingService,
                new MemoryRetrievalProperties(5, 0.99, 0.5, 3.0, 2.0, 5, 10, 50));

        // 访问时间是派生数据：写不进去也要把记忆交回上下文，不能让这一轮对话失败
        assertThat(memory.retrieveForContext("user-1", "录音设备")).hasSize(1);
    }

    @Test
    void shouldKeepRelevanceWhenEmbeddingWriteFails() {
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        // 61 需要补算向量（写入会失败），62 已有向量：相关性维度不能因为一次写入失败就丢掉
        mapper.retrievalRecords = List.of(
                record(61L, "用户用 SM58 录音", 5, null),
                record(62L, "用户住在上海", 100, "0.0,1.0"));
        mapper.failEmbeddingWrite = true;
        StubEmbeddingService embeddingService = new StubEmbeddingService(true,
                text -> text.contains("SM58") ? new float[]{1.0f, 0.0f} : new float[]{0.0f, 1.0f});
        LongTermMemory memory = new LongTermMemory(mapper, embeddingService,
                new MemoryRetrievalProperties(1, 0.99, 0.5, 3.0, 2.0, 5, 10, 50));

        assertThat(memory.retrieveForContext("user-1", "SM58"))
                .extracting(LongTermMemoryRecord::getId).containsExactly(61L);
    }

    private static LongTermMemoryRecord record(Long id, String content, int importance, String embedding) {
        LongTermMemoryRecord item = new LongTermMemoryRecord();
        item.setId(id);
        item.setUserId("user-1");
        item.setMemoryKey("user.profile.summary");
        item.setContent(content);
        item.setImportance(importance);
        item.setEmbedding(embedding);
        return item;
    }

    /**
     * 假向量服务：向量由调用方给的函数决定，序列化格式固定成 "x,y"（两维足够验证余弦与补算）。
     * 继承而不是 mock，是因为 {@link MemoryEmbeddingService} 的可用性判断与分批逻辑都要走真实分支。
     */
    private static class StubEmbeddingService extends MemoryEmbeddingService {

        private final boolean available;
        private final Function<String, float[]> embedder;
        private final List<String> embeddedTexts = new ArrayList<>();

        StubEmbeddingService(boolean available, Function<String, float[]> embedder) {
            super(null, new ObjectMapper(), 1, 10);
            this.available = available;
            this.embedder = embedder;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public float[] embedOne(String text) {
            embeddedTexts.add(text);
            return embedder.apply(text);
        }

        @Override
        public List<float[]> embedAll(List<String> texts) {
            List<float[]> results = new ArrayList<>(texts.size());
            for (String text : texts) {
                results.add(embedOne(text));
            }
            return results;
        }

        @Override
        public String toJson(float[] vector) {
            return vector == null ? null : vector[0] + "," + vector[1];
        }

        @Override
        public float[] parse(String json) {
            if (json == null || json.isBlank()) {
                return null;
            }
            String[] parts = json.split(",");
            return new float[]{Float.parseFloat(parts[0]), Float.parseFloat(parts[1])};
        }
    }

    private static class FakeLongTermMemoryMapper implements LongTermMemoryMapper {

        /** 模拟数据库自增主键：每次插入返回一个新 id，用于验证「同 key 两条记录」这条语义。 */
        private final AtomicLong idSequence = new AtomicLong(100);

        private final List<LongTermMemoryRecord> insertedRecords = new ArrayList<>();
        private LongTermMemoryRecord savedRecord;
        private Optional<LongTermMemoryRecord> readBackOverride;
        private String listUserId;
        private List<LongTermMemoryRecord> retrievalRecords = new ArrayList<>();
        private String retrievalUserId;
        private Long updatedEmbeddingId;
        private String updatedEmbedding;
        private List<Long> accessedIds = new ArrayList<>();
        private java.time.LocalDateTime accessedTime;
        private boolean failEmbeddingWrite;
        private boolean failAccessWrite;
        private String foundUserId;
        private Long foundId;
        private String deletedUserId;
        private Long deletedId;
        private String restoredUserId;
        private Long restoredId;
        private String updatedUserId;
        private Long updatedId;
        private String updatedContent;
        private int updatedImportance;
        private String supersededUserId;
        private Long supersededId;
        private Long supersededBy;

        @Override
        public int insert(LongTermMemoryRecord record) {
            this.savedRecord = record;
            record.setId(idSequence.incrementAndGet());
            this.insertedRecords.add(record);
            return 1;
        }

        @Override
        public Optional<LongTermMemoryRecord> findActiveById(String userId, Long id) {
            this.foundUserId = userId;
            this.foundId = id;
            if (readBackOverride != null) {
                return readBackOverride;
            }
            return insertedRecords.stream().filter(record -> id.equals(record.getId())).findFirst();
        }

        @Override
        public List<LongTermMemoryRecord> listByUser(String userId) {
            this.listUserId = userId;
            return new ArrayList<>();
        }

        @Override
        public List<LongTermMemoryRecord> listActiveForRetrieval(String userId) {
            this.retrievalUserId = userId;
            return new ArrayList<>(retrievalRecords);
        }

        @Override
        public int updateEmbedding(String userId, Long id, String embedding) {
            if (failEmbeddingWrite) {
                throw new IllegalStateException("模拟向量列写入失败");
            }
            this.updatedEmbeddingId = id;
            this.updatedEmbedding = embedding;
            return 1;
        }

        @Override
        public int markAccessed(String userId, List<Long> ids, java.time.LocalDateTime accessedTime) {
            if (failAccessWrite) {
                throw new IllegalStateException("模拟访问时间写入失败");
            }
            this.accessedIds = new ArrayList<>(ids);
            this.accessedTime = accessedTime;
            return ids.size();
        }

        @Override
        public int updateContent(String userId, Long id, String content, int importance) {
            this.updatedUserId = userId;
            this.updatedId = id;
            this.updatedContent = content;
            this.updatedImportance = importance;
            return 1;
        }

        @Override
        public int markDeletedByDecision(String userId, Long id, Long supersededBy) {
            this.supersededUserId = userId;
            this.supersededId = id;
            this.supersededBy = supersededBy;
            return 1;
        }

        @Override
        public int softDelete(String userId, Long id) {
            this.deletedUserId = userId;
            this.deletedId = id;
            return 1;
        }

        @Override
        public int restore(String userId, Long id) {
            this.restoredUserId = userId;
            this.restoredId = id;
            return 1;
        }
    }
}
