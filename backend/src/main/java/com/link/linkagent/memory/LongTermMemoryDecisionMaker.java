package com.link.linkagent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.LlmJsonUtil;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 长期记忆写入决策器（设计文档 D8 / C12 阶段二 / D14）。
 *
 * <h3>它解决什么问题</h3>
 * 阶段一只负责「哪些事实值得记」，不判断库里已经有什么。若直接把候选写进去，同一件事会被反复记成多条；
 * 而反过来用数据库唯一键覆盖，又会把「同 key 的不同事实」误杀（D13 的旧问题）。
 * 因此单独做一次调用：候选事实 + 已有记忆一起给模型，由它决定每条候选该 {@code ADD / UPDATE / DELETE / NONE}。
 *
 * <h3>为什么校验在代码里做</h3>
 * 模型经常编 id、编 {@code oldMemory}，或者输出空内容。这些都能用字符串判断拦住，
 * 不需要再加一次模型调用去复核（设计文档 D14 明确「不新增 LLM 调用」）。
 * 被拦下的动作只记日志、不写库——宁可少写一条，也不能让模型编造的内容污染记忆。
 *
 * <h3>为什么解析失败要抛异常</h3>
 * 决策输出不可解析时如果按「没有动作」处理，调用方会推进游标，那批候选就永久丢了。
 * 抛异常让消费者走重试，最终失败也不推游标，下一轮触及时同一批会被重新抽取。
 */
@Component
public class LongTermMemoryDecisionMaker {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryDecisionMaker.class);

    /**
     * 单轮最多写几条（设计文档 C14）。超过上限的动作被拒绝。
     * 注意：这里的默认值与下面 {@code @Value} 注解里的默认值必须一致（配置缺失时用同一个数）。
     */
    static final int DEFAULT_MAX_WRITES = 5;

    /**
     * 决策时最多携带多少条已有记忆。
     * 步骤 8 起这个条数是「向量召回多少条」的上限，不再表示「取最近的 N 条」（设计文档 C11）：
     * 按更新时间取最近 N 条，会在记忆变多以后把「和候选冲突、但很久没动过」的那条挡在外面。
     */
    static final int DEFAULT_EXISTING_MEMORY_LIMIT = 50;

    /** 单条记忆内容长度上限，与记忆管理页新建接口的限制保持一致。 */
    static final int MAX_CONTENT_LENGTH = 2000;

    /** 决策里没给分数时 ADD 用的兜底值，与抽取器同一口径（1-100，D17.2）。 */
    private static final int DEFAULT_IMPORTANCE = 40;

    private final LLMService llmService;
    private final PromptService promptService;
    private final LongTermMemory longTermMemory;
    private final ObjectMapper objectMapper;
    private final int maxWrites;
    private final int existingMemoryLimit;

    /** 单测构造器：写入上限与已有记忆条数取默认值。 */
    public LongTermMemoryDecisionMaker(LLMService llmService, PromptService promptService,
                                       LongTermMemory longTermMemory, ObjectMapper objectMapper) {
        this(llmService, promptService, longTermMemory, objectMapper,
                DEFAULT_MAX_WRITES, DEFAULT_EXISTING_MEMORY_LIMIT);
    }

    /** 生产构造器：上限来自配置，便于不重新发版就调整。 */
    @Autowired
    public LongTermMemoryDecisionMaker(LLMService llmService, PromptService promptService,
                                       LongTermMemory longTermMemory, ObjectMapper objectMapper,
                                       @Value("${agent.memory.extract.decision-max-writes:5}") int maxWrites,
                                       @Value("${agent.memory.extract.existing-memory-limit:50}") int existingMemoryLimit) {
        this.llmService = llmService;
        this.promptService = promptService;
        this.longTermMemory = longTermMemory;
        this.objectMapper = objectMapper;
        this.maxWrites = Math.max(1, maxWrites);
        this.existingMemoryLimit = Math.max(1, existingMemoryLimit);
    }

    /**
     * 对一批候选事实做写入决策并落库。
     *
     * @param userId     用户标识
     * @param sessionId  来源会话，新增记忆时写入 source_session_id
     * @param candidates 阶段一抽出的候选事实
     * @return 本次实际执行的写入统计，供调用方打日志
     * @throws IllegalStateException 模型输出不可解析时抛出，让调用方重试而不是推进游标
     */
    public WriteOutcome decideAndApply(String userId, String sessionId, List<LongTermMemoryCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return WriteOutcome.empty();
        }
        // 用三维打分的向量召回代替「按更新时间取最近 N 条」（设计文档 C11）：冲突判断要的是「与候选相关的记忆」，
        // 排序口径与上下文注入一致，只是不写回访问时间——这些记忆只是被决策看了一眼，不算被取用。
        List<LongTermMemoryRecord> existingMemories = longTermMemory.retrieveForDecision(
                userId, candidateQueryTexts(candidates), existingMemoryLimit);
        if (existingMemories.isEmpty()) {
            // 库里一条都没有时没有「冲突判断」可做，直接全部新增：省掉一次模型调用，结论也不会不同。
            return applyAdds(userId, sessionId, candidates, WriteOutcome.builder());
        }
        Map<Long, LongTermMemoryRecord> existingById = new LinkedHashMap<>();
        for (LongTermMemoryRecord record : existingMemories) {
            if (record.getId() != null) {
                existingById.put(record.getId(), record);
            }
        }
        String response = callModel(candidates, existingMemories);
        List<LongTermMemoryDecision> decisions = parseDecisions(response);
        return applyDecisions(userId, sessionId, candidates, existingById, decisions);
    }

    // ────────────────────────── 模型调用与解析 ──────────────────────────

    private String callModel(List<LongTermMemoryCandidate> candidates, List<LongTermMemoryRecord> existingMemories) {
        String systemPrompt;
        String userPrompt;
        try {
            systemPrompt = promptService.get("long_term_memory_decision.system");
            userPrompt = promptService.render("long_term_memory_decision.user", Map.of(
                    "candidates", renderCandidates(candidates),
                    "existingMemories", renderExistingMemories(existingMemories)));
        } catch (RuntimeException promptException) {
            // 提示词种子缺失是部署错误：消费者会重试，最终游标不推进，事实不会丢，但必须让人看到。
            throw new IllegalStateException("长期记忆写入决策提示词缺失或渲染失败，请重新执行 init.sql：" + promptException.getMessage(),
                    promptException);
        }
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("长期记忆写入决策")) {
            return MemoryCallTimeout.call("长期记忆写入决策", () -> llmService.chat(systemPrompt, userPrompt));
        } catch (Exception exception) {
            // 调用失败与「输出不可解析」同处理：抛给消费者重试，游标不推进，候选事实下一轮会被重新抽出。
            throw new IllegalStateException("长期记忆写入决策调用失败：" + exception.getMessage(), exception);
        }
    }

    /**
     * 候选事实的内容列表，用于召回已有记忆。
     * 每条候选各召回一次再取并集，而不是把它们拼成一段文本：拼起来的查询向量是若干主题的平均值，
     * 「只和其中一条候选冲突」的既有记忆反而召不回来，而漏掉它就会把 UPDATE 判成 ADD、留下两条重复记忆。
     */
    private List<String> candidateQueryTexts(List<LongTermMemoryCandidate> candidates) {
        List<String> texts = new ArrayList<>();
        for (LongTermMemoryCandidate candidate : candidates) {
            String content = TextUtil.trimToDefault(candidate.content(), "");
            if (TextUtil.hasText(content)) {
                texts.add(content);
            }
        }
        return texts;
    }

    /** 候选事实渲染成模型输入：只给判断需要的字段，避免把内部结构透出去。 */
    private String renderCandidates(List<LongTermMemoryCandidate> candidates) {
        ArrayNode array = objectMapper.createArrayNode();
        for (LongTermMemoryCandidate candidate : candidates) {
            ObjectNode node = array.addObject();
            node.put("memoryKey", TextUtil.trimToDefault(candidate.memoryKey(), ""));
            node.put("content", TextUtil.trimToDefault(candidate.content(), ""));
            node.put("importance", candidate.importance() == null ? DEFAULT_IMPORTANCE : candidate.importance());
        }
        return array.toString();
    }

    /** 已有记忆渲染成模型输入：id 是模型唯一能引用的身份，必须带上。 */
    private String renderExistingMemories(List<LongTermMemoryRecord> memories) {
        ArrayNode array = objectMapper.createArrayNode();
        for (LongTermMemoryRecord record : memories) {
            ObjectNode node = array.addObject();
            node.put("id", record.getId() == null ? 0L : record.getId());
            node.put("memoryKey", TextUtil.trimToDefault(record.getMemoryKey(), ""));
            node.put("content", TextUtil.trimToDefault(record.getContent(), ""));
            node.put("importance", record.getImportance() == null ? 0 : record.getImportance());
        }
        return array.toString();
    }

    List<LongTermMemoryDecision> parseDecisions(String response) {
        JsonNode root;
        try {
            root = objectMapper.readTree(LlmJsonUtil.extractJsonObject(response));
        } catch (Exception exception) {
            throw new IllegalStateException("长期记忆写入决策输出不是合法 JSON：" + exception.getMessage(), exception);
        }
        JsonNode decisionsNode = root == null ? null : root.get("decisions");
        if (decisionsNode == null || !decisionsNode.isArray()) {
            throw new IllegalStateException("长期记忆写入决策输出缺少 decisions 数组");
        }
        List<LongTermMemoryDecision> decisions = new ArrayList<>();
        for (JsonNode node : decisionsNode) {
            if (node == null || !node.isObject()) {
                continue;
            }
            decisions.add(new LongTermMemoryDecision(
                    LlmJsonUtil.text(node, "action"),
                    node.hasNonNull("id") ? node.get("id").asLong() : null,
                    LlmJsonUtil.text(node, "memory"),
                    LlmJsonUtil.text(node, "oldMemory"),
                    node.hasNonNull("importance") ? node.get("importance").asInt() : null,
                    LlmJsonUtil.text(node, "reason")));
        }
        return decisions;
    }

    // ────────────────────────── 校验与执行 ──────────────────────────

    private WriteOutcome applyDecisions(String userId, String sessionId,
                                        List<LongTermMemoryCandidate> candidates,
                                        Map<Long, LongTermMemoryRecord> existingById,
                                        List<LongTermMemoryDecision> decisions) {
        Map<String, Integer> candidateImportance = new LinkedHashMap<>();
        for (LongTermMemoryCandidate candidate : candidates) {
            candidateImportance.put(TextUtil.trimToDefault(candidate.content(), ""),
                    candidate.importance() == null ? DEFAULT_IMPORTANCE : candidate.importance());
        }
        List<LongTermMemoryDecision> adds = new ArrayList<>();
        // 同一个 id 在一轮里被改多次时只取最后一次（设计文档 D14），所以按 id 覆盖而不是追加。
        Map<Long, LongTermMemoryDecision> lastWriteById = new LinkedHashMap<>();
        int rejected = 0;
        int skipped = 0;
        int writes = 0;
        for (LongTermMemoryDecision decision : decisions) {
            if (decision.isNoop()) {
                skipped++;
                continue;
            }
            if (decision.isAdd()) {
                adds.add(decision);
                continue;
            }
            String rejection = validateExistingTarget(decision, existingById);
            if (rejection != null) {
                rejected++;
                log.warn("长期记忆写入决策被拒绝：{}，action={}, id={}", rejection, decision.action(), decision.id());
                continue;
            }
            lastWriteById.put(decision.id(), decision);
        }
        WriteOutcome.Builder outcome = WriteOutcome.builder();
        outcome.rejected(rejected).skipped(skipped);
        // 先新增，后更新与删除：删除的 superseded_by 需要指向本轮新写入的替代记录，新增必须先在库里拿到 id。
        Map<String, Long> addedIdByKey = new LinkedHashMap<>();
        for (LongTermMemoryDecision decision : adds) {
            if (writes >= maxWrites) {
                rejected = countRejection(outcome, rejected);
                log.warn("长期记忆写入决策被拒绝：超过单轮写入上限 {}，action=ADD", maxWrites);
                continue;
            }
            String content = TextUtil.trimToDefault(decision.memory(), "");
            if (!isAcceptableContent(content)) {
                rejected = countRejection(outcome, rejected);
                continue;
            }
            int importance = decision.importance() == null
                    ? candidateImportance.getOrDefault(content, DEFAULT_IMPORTANCE)
                    : normalizeImportance(decision.importance());
            Optional<LongTermMemoryRecord> saved = longTermMemory.save(userId, decisionCandidateKey(candidates, content),
                    content, sessionId, importance);
            if (saved.isEmpty()) {
                // 写入成功但回读不到：不影响本轮其它动作，下一轮决策会把它当已有记忆看。
                log.warn("长期记忆新增后回读失败，userId={}, content={}", userId, TextUtil.preview(content, 40, ""));
                continue;
            }
            writes++;
            outcome.added(outcome.added() + 1);
            addedIdByKey.put(saved.get().getMemoryKey(), saved.get().getId());
        }
        for (LongTermMemoryDecision decision : lastWriteById.values()) {
            if (writes >= maxWrites) {
                rejected = countRejection(outcome, rejected);
                log.warn("长期记忆写入决策被拒绝：超过单轮写入上限 {}，action={}, id={}", maxWrites, decision.action(), decision.id());
                continue;
            }
            LongTermMemoryRecord target = existingById.get(decision.id());
            if (decision.isUpdate()) {
                String content = TextUtil.trimToDefault(decision.memory(), "");
                if (!isAcceptableContent(content)) {
                    rejected = countRejection(outcome, rejected);
                    continue;
                }
                int importance = decision.importance() == null
                        ? (target.getImportance() == null ? DEFAULT_IMPORTANCE : target.getImportance())
                        : normalizeImportance(decision.importance());
                longTermMemory.update(userId, decision.id(), content, importance);
                writes++;
                outcome.updated(outcome.updated() + 1);
                continue;
            }
            if (decision.isDelete()) {
                // 被删除的记忆若在本轮有同 key 的新增记录，把 superseded_by 指向它：
                // 这就是 E2 要区分的「被新事实取代」，与管理页的用户删除不是一回事。
                Long supersededBy = addedIdByKey.get(target.getMemoryKey());
                longTermMemory.markDeleted(userId, decision.id(), supersededBy);
                writes++;
                outcome.deleted(outcome.deleted() + 1);
            }
        }
        log.info("长期记忆写入决策完成，userId={}, 候选 {} 条, 新增 {}, 更新 {}, 删除 {}, 跳过 {}, 拒绝 {}",
                userId, candidates.size(), outcome.added(), outcome.updated(), outcome.deleted(),
                outcome.skipped(), outcome.rejected());
        return outcome.build();
    }

    /** 库里没有任何记忆时的直通路径：所有候选都是新增。 */
    private WriteOutcome applyAdds(String userId, String sessionId, List<LongTermMemoryCandidate> candidates,
                                   WriteOutcome.Builder outcome) {
        Map<String, LongTermMemoryCandidate> deduped = new LinkedHashMap<>();
        for (LongTermMemoryCandidate candidate : candidates) {
            String key = TextUtil.trimToDefault(candidate.memoryKey(), "").toLowerCase();
            LongTermMemoryCandidate existing = deduped.get(key);
            if (existing == null || importanceOf(candidate) > importanceOf(existing)) {
                deduped.put(key, candidate);
            }
        }
        int writes = 0;
        for (LongTermMemoryCandidate candidate : deduped.values()) {
            if (writes >= maxWrites) {
                outcome.rejected(outcome.rejected() + 1);
                continue;
            }
            String content = TextUtil.trimToDefault(candidate.content(), "");
            if (!isAcceptableContent(content)) {
                outcome.rejected(outcome.rejected() + 1);
                continue;
            }
            Optional<LongTermMemoryRecord> saved = longTermMemory.save(userId, candidate.memoryKey(), content, sessionId,
                    candidate.importance() == null ? DEFAULT_IMPORTANCE : candidate.importance());
            if (saved.isPresent()) {
                writes++;
                outcome.added(outcome.added() + 1);
            }
        }
        log.info("长期记忆写入决策完成（库里暂无已有记忆，直接新增），userId={}, 候选 {} 条, 新增 {}",
                userId, candidates.size(), outcome.added());
        return outcome.build();
    }

    private int importanceOf(LongTermMemoryCandidate candidate) {
        return candidate.importance() == null ? 0 : candidate.importance();
    }

    /** 拒绝计数：拒绝分支不止一处，集中在一个 helper 里更新，避免「写统计」和「改局部变量」两处状态不同步。 */
    private int countRejection(WriteOutcome.Builder outcome, int rejectedSoFar) {
        int next = rejectedSoFar + 1;
        outcome.rejected(next);
        return next;
    }

    private String validateExistingTarget(LongTermMemoryDecision decision, Map<Long, LongTermMemoryRecord> existingById) {
        if (decision.id() == null) {
            return "缺少 id";
        }
        LongTermMemoryRecord target = existingById.get(decision.id());
        if (target == null) {
            // 两种情况都会命中这里：模型编造 id，或这条记忆超出了本次携带的条数上限（existing-memory-limit）。
            // 提示里把两种可能都写上，避免排查时一律当成模型编造。
            return "id 不在本次携带的已有记忆里（模型编造，或超出 existing-memory-limit=" + existingMemoryLimit + " 的携带范围）";
        }
        String oldMemory = TextUtil.trimToDefault(decision.oldMemory(), "");
        if (!TextUtil.hasText(oldMemory)) {
            return "缺少 oldMemory";
        }
        String existingContent = TextUtil.trimToDefault(target.getContent(), "");
        if (!existingContent.contains(oldMemory)) {
            // 字符串包含判断而不是相等：模型回带的原文常带省略号或换行差异，但对不上就说明它在编。
            return "oldMemory 与库中内容对不上";
        }
        return null;
    }

    private boolean isAcceptableContent(String content) {
        if (!TextUtil.hasText(content)) {
            return false;
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            log.warn("长期记忆写入决策被拒绝：内容长度 {} 超过上限 {}", content.length(), MAX_CONTENT_LENGTH);
            return false;
        }
        return true;
    }

    /**
     * 新增时把候选的分类键还原出来：决策只给内容，分类键仍在候选里，按内容反查。
     * <p>
     * 模型若把 ADD 的内容改写过（不是逐字抄候选），这里匹配不上，会落到兜底分类。
     * 记忆本身不会丢（内容照写），但分类标签可能不准，所以打 warn 留痕，便于之后按日志核对。
     */
    private String decisionCandidateKey(List<LongTermMemoryCandidate> candidates, String content) {
        for (LongTermMemoryCandidate candidate : candidates) {
            if (TextUtil.trimToDefault(candidate.content(), "").equals(content)) {
                return TextUtil.trimToDefault(candidate.memoryKey(), "");
            }
        }
        log.warn("新增记忆的内容与候选不完全一致，分类键回退到 user.profile.summary，content={}",
                TextUtil.preview(content, 40, ""));
        return "user.profile.summary";
    }

    private int normalizeImportance(int importance) {
        return Math.min(100, Math.max(1, importance));
    }

    /**
     * 一次写入决策的执行统计。
     * 用可变 Builder 累加：多个动作分支都要计数，record 本身不适合在循环里反复重建。
     */
    public record WriteOutcome(int added, int updated, int deleted, int skipped, int rejected) {

        static Builder builder() {
            return new Builder();
        }

        static WriteOutcome empty() {
            return builder().build();
        }

        static final class Builder {
            private int added;
            private int updated;
            private int deleted;
            private int skipped;
            private int rejected;

            // 既当 setter 也当 getter（同名重载）：调用点要写成「累加」形式，
            // 分成 setAdded/getAdded 两套名字会让分支里的计数代码更啰嗦。
            int added() {
                return added;
            }

            Builder added(int value) {
                this.added = value;
                return this;
            }

            int updated() {
                return updated;
            }

            int deleted() {
                return deleted;
            }

            int skipped() {
                return skipped;
            }

            int rejected() {
                return rejected;
            }

            Builder updated(int value) {
                this.updated = value;
                return this;
            }

            Builder deleted(int value) {
                this.deleted = value;
                return this;
            }

            Builder skipped(int value) {
                this.skipped = value;
                return this;
            }

            Builder rejected(int value) {
                this.rejected = value;
                return this;
            }

            WriteOutcome build() {
                return new WriteOutcome(added, updated, deleted, skipped, rejected);
            }
        }
    }
}
