package com.link.linkagent.memory;

import com.link.linkagent.memory.model.ConversationMessageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 长期记忆提取协调器 —— 决定「这一轮要不要提取、提取哪一批、游标推到哪」，并负责消费队列任务
 * （设计文档 C2 / C4 / C6 / C7 / C8 / D7）。
 *
 * <h3>一轮对话结束后发生什么</h3>
 * <ol>
 *   <li>给这一轮打分（{@link TurnImportanceScorer}，1-100）；</li>
 *   <li>把分数从预算里扣掉（{@link MemoryExtractionAccumulator}）；</li>
 *   <li>预算没到零 → 结束，这一轮不调模型；</li>
 *   <li>预算扣到零 → 把「游标之后到本轮最后一条消息」这个区间入队后立即返回；</li>
 *   <li>消费者线程（{@link MemoryExtractionWorker}）取出任务：取增量消息 → 阶段一抽取 → 阶段二写入决策
 *       → 成功才推进游标并重置预算。</li>
 * </ol>
 *
 * <h3>为什么提取要搬出请求线程</h3>
 * 阶段一 + 阶段二是两次模型调用，放在请求线程里用户就要多等两次模型；搬进队列后，用户只承担一次「打分」调用
 * （作者在步骤 5 确认保留每轮同步打分）。队列放 Redis 而不是 JVM：后端重启后游标与任务都还在（D9）。
 *
 * <h3>游标只允许成功时推进</h3>
 * 这是 C4 的硬约束。反过来做（无论成败都推进）会在模型抖动时静默丢消息：那批消息被跳过、长期记忆里也没有。
 * 因此任务重试到上限仍失败时，只记 ERROR 日志，游标与预算都保持不动，下一轮触发时同一批会被重新处理。
 */
@Component
public class LongTermMemoryExtractionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryExtractionCoordinator.class);

    /**
     * 单批最多抽取多少条消息。
     * 首次使用时游标为空、或提取被卡住一段时间后，增量可能积累很多；
     * 这个上限保证单次抽取调用不会无限变大，剩下的消息留给下一次触发（游标只推到本批最后一条）。
     */
    static final int DEFAULT_BATCH_MESSAGE_LIMIT = 200;

    /** 失败原地重试次数上限（设计文档 C8）：不把失败任务丢回队尾，超过上限就丢弃并记 ERROR。 */
    static final int DEFAULT_RETRY_MAX = 2;

    private final LongTermMemoryExtractor extractor;
    private final LongTermMemory longTermMemory;
    private final LongTermMemoryDecisionMaker decisionMaker;
    private final ConversationSessionMapper conversationSessionMapper;
    private final MemoryExtractionAccumulator accumulator;
    private final TurnImportanceScorer scorer;
    private final MemoryExtractionQueue queue;
    private final int batchMessageLimit;
    private final int retryMax;

    @Autowired
    public LongTermMemoryExtractionCoordinator(LongTermMemoryExtractor extractor,
                                               LongTermMemory longTermMemory,
                                               LongTermMemoryDecisionMaker decisionMaker,
                                               ConversationSessionMapper conversationSessionMapper,
                                               MemoryExtractionAccumulator accumulator,
                                               TurnImportanceScorer scorer,
                                               MemoryExtractionQueue queue,
                                               @Value("${agent.memory.extract.batch-message-limit:200}") int batchMessageLimit,
                                               @Value("${agent.memory.extract.retry-max:2}") int retryMax) {
        this.extractor = extractor;
        this.longTermMemory = longTermMemory;
        this.decisionMaker = decisionMaker;
        this.conversationSessionMapper = conversationSessionMapper;
        this.accumulator = accumulator;
        this.scorer = scorer;
        this.queue = queue;
        this.batchMessageLimit = Math.max(1, batchMessageLimit);
        this.retryMax = Math.max(0, retryMax);
    }

    /**
     * 最小构造：不接 Redis、消息表与队列，只保留「每轮抽本轮这一条」的退化路径。
     * <p>
     * 单测构造 AgentExecutor 时走这里——单测不该为了跑一轮对话而准备 Redis 与数据库。
     */
    public LongTermMemoryExtractionCoordinator(LongTermMemoryExtractor extractor, LongTermMemory longTermMemory) {
        this(extractor, longTermMemory, null, null, null, null, null,
                DEFAULT_BATCH_MESSAGE_LIMIT, DEFAULT_RETRY_MAX);
    }

    /** 单测构造：接队列与依赖，重试次数取默认值。 */
    public LongTermMemoryExtractionCoordinator(LongTermMemoryExtractor extractor,
                                               LongTermMemory longTermMemory,
                                               LongTermMemoryDecisionMaker decisionMaker,
                                               ConversationSessionMapper conversationSessionMapper,
                                               MemoryExtractionAccumulator accumulator,
                                               TurnImportanceScorer scorer,
                                               MemoryExtractionQueue queue,
                                               int batchMessageLimit) {
        this(extractor, longTermMemory, decisionMaker, conversationSessionMapper, accumulator, scorer, queue,
                batchMessageLimit, DEFAULT_RETRY_MAX);
    }

    /** 向后兼容的单轮入口（调用方拿不到消息 id 时用）。 */
    public void afterTurn(String userId, String sessionId, String userMessage, String finalAnswer) {
        afterTurn(userId, sessionId, userMessage, finalAnswer, null);
    }

    /**
     * 一轮对话成功产出了回答之后的入口。
     *
     * @param userId        用户标识
     * @param sessionId     会话标识（来源会话标记用）
     * @param userMessage   本轮用户输入
     * @param finalAnswer   本轮最终回答
     * @param lastMessageId 本轮最后一条已落库消息的 id，用它组成任务的消息区间；为空表示没有可用的区间
     */
    public void afterTurn(String userId, String sessionId, String userMessage, String finalAnswer, Long lastMessageId) {
        if (extractor == null || longTermMemory == null) {
            // 最小构造没有抽取器和长期记忆，说明这条链路在这个运行形态里没有开启。
            return;
        }
        if (accumulator == null || scorer == null || queue == null) {
            extractCurrentTurn(userId, sessionId, userMessage, finalAnswer, "未接入预算累加器或队列");
            return;
        }
        int score = scorer.score(userMessage, finalAnswer);
        MemoryExtractionAccumulator.Decision decision = accumulator.consume(userId, score);
        if (decision == MemoryExtractionAccumulator.Decision.WAIT) {
            return;
        }
        if (decision == MemoryExtractionAccumulator.Decision.DEGRADED) {
            // Redis 不可用时不再退回「在请求线程里抽一轮」：游标、预算、队列三样都在 Redis，
            // 缺了它们既无法保证不重复抽取，也要让用户白等两次模型调用。这轮跳过，记日志等人发现。
            log.warn("预算累加器不可用（Redis），本轮不提取长期记忆，userId={}", userId);
            return;
        }
        enqueueRangeTask(userId, sessionId, lastMessageId);
    }

    /**
     * 压缩链路的高分事实点入队（设计文档 6.1 步骤 5.3）。
     * <p>
     * 预抽候选同样要过阶段二写入决策：跳过它会让同一批事实绕过去重与冲突判断，与其他路径的写入口径不一致。
     *
     * @return true=已入队；false=Redis 不可用，调用方需要决定是否直接落库兜底
     */
    public boolean enqueueFacts(String userId, String sessionId, List<SummaryFactPoint> facts) {
        if (queue == null || facts == null || facts.isEmpty()) {
            return false;
        }
        List<MemoryExtractionTask.MemoryExtractionTaskFact> taskFacts = new ArrayList<>();
        for (SummaryFactPoint fact : facts) {
            taskFacts.add(new MemoryExtractionTask.MemoryExtractionTaskFact(
                    fact.memoryKey(), fact.storageContent(), fact.importance()));
        }
        return queue.enqueue(MemoryExtractionTask.forFacts(userId, sessionId, taskFacts, OffsetDateTime.now().toString()));
    }

    /**
     * 消费者入口：处理一个任务，失败按配置原地重试（C8），不把失败任务丢回队尾。
     * <p>
     * 这个方法不抛异常：消费者是长期运行的线程，一个任务处理不了不应该把循环打断。
     */
    public void processTask(MemoryExtractionTask task) {
        processTaskWithResult(task);
    }

    /** 评测必须区分成功与失败；生产消费者仍保留原来的记录日志并继续消费的行为。 */
    public boolean processTaskWithResult(MemoryExtractionTask task) {
        if (task == null || task.userId() == null) {
            return false;
        }
        for (int attempt = 0; attempt <= retryMax; attempt++) {
            try {
                handleTask(task);
                return true;
            } catch (Exception exception) {
                log.warn("长期记忆任务处理失败（第 {} 次尝试），userId={}, 消息范围={}-{}, error={}",
                        attempt + 1, task.userId(), task.fromMessageId(), task.toMessageId(), exception.getMessage());
            }
        }
        // 重试到上限仍失败：游标与预算保持不动，下一轮预算扣减后会再次触发同一批消息。
        log.error("长期记忆任务重试 {} 次仍失败，已丢弃；游标保持不动，等下一次触发重试，userId={}, 消息范围={}-{}",
                retryMax, task.userId(), task.fromMessageId(), task.toMessageId());
        return false;
    }

    private void handleTask(MemoryExtractionTask task) {
        List<LongTermMemoryCandidate> candidates;
        Long lastMessageId = null;
        String sessionId = task.sessionId();
        if (task.hasFacts()) {
            candidates = task.facts().stream()
                    .map(fact -> new LongTermMemoryCandidate(true, fact.memoryKey(), fact.content(), fact.importance()))
                    .toList();
            // 预抽候选任务不改游标：压缩路径已经推进过游标（advanceAfterCompaction），这里只负责写入决策。
            lastMessageId = null;
        } else if (task.hasMessageRange()) {
            long cursor = accumulator.getCursor(task.userId());
            List<ConversationMessageRecord> records = listIncrementMessages(task.userId(), cursor);
            if (records.isEmpty()) {
                // 没有增量：这批预算对应的消息已经通过压缩路径处理过，重置预算等下一轮。
                resetBudgetQuietly(task.userId(), "无增量消息");
                log.info("长期记忆任务没有增量消息，预算已重置，userId={}, cursor={}", task.userId(), cursor);
                return;
            }
            LongTermMemoryExtractionResult result = extractor.extract(toMemoryMessages(records), MemoryObservationContext.observationDate());
            if (!result.succeeded()) {
                // 交给 processTask 的重试：这里抛出去，游标不推进。
                throw new IllegalStateException("阶段一抽取失败");
            }
            candidates = result.candidates();
            lastMessageId = records.get(records.size() - 1).getId();
            sessionId = sourceSessionId(records);
        } else {
            log.warn("长期记忆任务既没有消息区间也没有预抽候选，已跳过，userId={}", task.userId());
            return;
        }
        if (!candidates.isEmpty()) {
            if (decisionMaker == null) {
                saveCandidatesDirectly(task.userId(), sessionId, candidates);
            } else {
                decisionMaker.decideAndApply(task.userId(), sessionId, candidates);
            }
        }
        finishRangeTask(task.userId(), lastMessageId);
    }

    /**
     * 成功收尾：推进游标 + 重置预算。
     * <p>
     * 没有候选事实也要推进：模型只是说「这批没什么值得记的」，如果游标不动，同一批消息每轮都会被重抽。
     * 预抽候选任务（lastMessageId 为空）不动游标——它不代表任何消息区间已经被处理完。
     */
    private void finishRangeTask(String userId, Long lastMessageId) {
        if (lastMessageId == null || accumulator == null) {
            return;
        }
        try {
            accumulator.advanceCursor(userId, lastMessageId);
            accumulator.resetBudget(userId);
            log.info("长期记忆任务完成：游标推进到 {}，预算已重置，userId={}", lastMessageId, userId);
        } catch (Exception exception) {
            log.warn("推进游标或重置预算失败，下轮会重复抽取本批消息，userId={}, error={}",
                    userId, exception.getMessage());
        }
    }

    /** 预算扣到零：把「游标之后到本轮最后一条消息」这个区间入队，不在请求线程里跑模型。 */
    private void enqueueRangeTask(String userId, String sessionId, Long lastMessageId) {
        long to = lastMessageId == null ? 0L : lastMessageId;
        long from;
        try {
            from = accumulator.getCursor(userId);
        } catch (Exception exception) {
            // 读不到游标仍然可以入队：消费者会重新读一次游标，读不到就按失败重试。
            from = 0L;
            log.warn("读取长期记忆游标失败，任务按区间 0-{} 入队，userId={}, error={}",
                    to, userId, exception.getMessage());
        }
        if (to <= from) {
            log.warn("长期记忆触发时没有可提取的消息区间（游标 {} 已到 {}），本轮不入队，userId={}", from, to, userId);
            return;
        }
        MemoryExtractionTask task = MemoryExtractionTask.forMessageRange(userId, sessionId, from, to,
                OffsetDateTime.now().toString());
        if (!queue.enqueue(task)) {
            // 入队失败时预算已经被扣掉，下一轮扣减后仍然是负数，会自然重试。
            log.warn("长期记忆任务入队失败（Redis 不可用），本轮不提取，userId={}", userId);
        }
    }

    /**
     * 压缩路径已经把这批消息抽成事实点之后的回调：推进游标，并返回裁剪短期记忆时可以安全裁掉的边界。
     * <p>
     * 压缩会把原文从短期记忆里裁掉，如果游标不跟着走，下次触发会把同一批消息再抽一遍；
     * 但抽取失败时调用方不会走到这里——那时这批消息只被摘要覆盖，高分事实点还没落长期记忆
     * （见 {@link SummaryFactExtractionResult}）。
     *
     * <p>游标只向前不回退：增量提取可能已经抽到更靠后的消息，这时不能为了这批压缩消息把游标退回去。
     *
     * @param messages 本次压缩掉的消息（原文）
     * @return 短期记忆裁剪时的游标边界；null 表示没有可用的游标信息，裁剪退回「只按条数保留」
     */
    public Long advanceAfterCompaction(String userId, List<MemoryMessage> messages) {
        if (accumulator == null || messages == null || messages.isEmpty()) {
            return null;
        }
        Long lastMessageId = null;
        for (MemoryMessage message : messages) {
            if (message.messageId() != null) {
                lastMessageId = message.messageId();
            }
        }
        if (lastMessageId == null) {
            // 这批消息没有落库 id（消息表写入失败），无法用游标表示，裁剪只能退回按条数保留。
            return null;
        }
        try {
            long currentCursor = accumulator.getCursor(userId);
            if (lastMessageId <= currentCursor) {
                // 增量提取已经覆盖到这批消息之后，游标保持不动，但仍然可以按当前游标裁剪。
                return currentCursor;
            }
            accumulator.advanceCursor(userId, lastMessageId);
            log.info("压缩路径已抽取事实点，长期记忆游标推进到 {}，userId={}", lastMessageId, userId);
            return lastMessageId;
        } catch (Exception exception) {
            log.warn("推进长期记忆游标失败，压缩照常完成，本轮按条数裁剪，userId={}, error={}",
                    userId, exception.getMessage());
            return null;
        }
    }

    /**
     * 退化路径：只抽本轮这两条消息并直接落库（不经过写入决策），不碰游标与预算。
     * <p>
     * 只在「没接入队列与累加器」的最小构造下走到，用于单测与未开启记忆链路的运行形态；
     * 生产环境 Redis 不可用时不走这里（见 {@link #afterTurn}）。
     */
    private void extractCurrentTurn(String userId, String sessionId, String userMessage, String finalAnswer, String reason) {
        log.warn("长期记忆退回逐轮抽取（{}），userId={}", reason, userId);
        List<MemoryMessage> messages = List.of(
                new MemoryMessage("Human", userMessage),
                new MemoryMessage("AI", finalAnswer)
        );
        LongTermMemoryExtractionResult result = extractor.extract(messages);
        if (result.succeeded() && result.hasCandidates()) {
            saveCandidatesDirectly(userId, sessionId, result.candidates());
        }
    }

    /** 退化路径的写入：没有写入决策器时按新增语义直接落库。 */
    private void saveCandidatesDirectly(String userId, String sessionId, List<LongTermMemoryCandidate> candidates) {
        for (LongTermMemoryCandidate candidate : candidates) {
            int importance = candidate.importance() == null ? 0 : candidate.importance();
            try {
                longTermMemory.save(userId, candidate.memoryKey(), candidate.content(), sessionId, importance);
                log.info("长期记忆已保存（直写），userId={}, memoryKey={}, importance={}",
                        userId, candidate.memoryKey(), importance);
            } catch (Exception exception) {
                // 单条写入失败只打 warn：记忆写不进去不应该影响已经返回给用户的回答。
                log.warn("长期记忆保存失败，userId={}, memoryKey={}, error={}",
                        userId, candidate.memoryKey(), exception.getMessage());
            }
        }
    }

    /**
     * 读取游标之后的增量消息，并翻正成时间顺序。
     * <p>游标只推到本批最后一条：取到的消息数少于实际增量时（超过单批上限），剩下的下一轮继续。
     */
    private List<ConversationMessageRecord> listIncrementMessages(String userId, long cursor) {
        if (conversationSessionMapper == null) {
            return List.of();
        }
        try {
            List<ConversationMessageRecord> records = new ArrayList<>(
                    conversationSessionMapper.listLatestMessagesAfterIdForUser(userId, cursor, batchMessageLimit));
            Collections.reverse(records);
            return records;
        } catch (Exception exception) {
            // 查库失败按异常抛出，让 processTask 走重试：静默返回空列表会被当成「没有增量」而重置预算。
            throw new IllegalStateException("按游标查询增量消息失败：" + exception.getMessage(), exception);
        }
    }

    private List<MemoryMessage> toMemoryMessages(List<ConversationMessageRecord> records) {
        return records.stream()
                .map(record -> new MemoryMessage(record.getRole(), record.getContent(), record.getId()))
                .toList();
    }

    /**
     * 记忆的来源会话：取本批最新一条消息所在会话。
     * 同一用户可能同时有多个会话（多标签页），跨会话的增量会被合并成一批，这里只能标一个来源；
     * 单用户单会话是常态，标最新那个比留空更有排查价值。
     */
    private String sourceSessionId(List<ConversationMessageRecord> records) {
        return records.get(records.size() - 1).getSessionId();
    }

    private void resetBudgetQuietly(String userId, String reason) {
        try {
            accumulator.resetBudget(userId);
        } catch (Exception exception) {
            // 重置失败只影响下一次触发时机（预算已经是负数，下轮还会再触发一次），不影响正确性。
            log.warn("重置长期记忆预算失败（{}），userId={}, error={}", reason, userId, exception.getMessage());
        }
    }
}
