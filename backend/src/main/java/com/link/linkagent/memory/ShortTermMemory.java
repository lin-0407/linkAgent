package com.link.linkagent.memory;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 短期记忆 —— 管理单次会话的最近 N 条消息，为 Agent 提供最直接的对话连续性上下文。
 *
 * <h3>在记忆架构中的位置</h3>
 * 记忆拼接顺序为「长期记忆 → 摘要 → 短期记忆 → 用户输入」。
 * 短期记忆位于摘要之后、用户输入之前，是 LLM 做本轮推理时最直接的参考上下文。
 *
 * <h3>核心设计</h3>
 * <ul>
 *   <li><b>消息窗口管理</b>：通过 {@link ShortTermMemoryStore} 维护每个会话的消息列表，
 *       append 时自动按 {@code MAX_MESSAGES_PER_SESSION} 上限裁剪，防止单次会话消息无限膨胀</li>
 *   <li><b>与摘要记忆的协作</b>：当摘要触发后，
 *       会调用 {@link #keepRecentMessages}，按 {@code retainedMessageCount}（由 SummaryMemory 提供）裁剪消息列表。
 *       形成「早期对话靠摘要、最近对话靠原文」的分层上下文策略——摘要在前面给 LLM 看历史要点，
 *       短期记忆给 LLM 看最近几轮的完整原文。</li>
 *   <li><b>存储抽象</b>：通过 {@code ShortTermMemoryStore} 接口解耦存储实现——
 *       当前为内存实现，未来可替换为 Redis 实现以支持分布式部署和多副本共享会话状态</li>
 * </ul>
 *
 * <h3>消息数量设计</h3>
 * {@code MAX_MESSAGES_PER_SESSION = 10}：每条 Human 或 AI 消息各算一条，
 * 即最多保留 5 轮完整对话。这个值平衡了上下文丰富度和 Token 成本——
 * 5 轮对话足以覆盖大多数单次会话的上下文需求，超过 5 轮的内容由摘要记忆承载。
 */
@Component
public class ShortTermMemory {

    /**
     * 每个会话默认保留的最大消息数。
     * 10 条 ≈ 5 轮对话（每轮 1 条 Human + 1 条 AI），足以覆盖大多数单次会话的上下文需求。
     * 超过此上限的消息在 append 时被自动丢弃，防止内存膨胀。
     */
    private static final int MAX_MESSAGES_PER_SESSION = 10;

    /**
     * 短期记忆的存储实现，当前为内存版，未来可替换为 Redis 版以支持水平扩展。
     * 通过接口注入实现存储层与业务层解耦。
     */
    private final ShortTermMemoryStore memoryStore;

    public ShortTermMemory(ShortTermMemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    /**
     * 获取指定会话的最近消息列表，用于拼接 Agent 对话上下文。
     *
     * @param sessionId 会话标识
     * @return 按时间顺序排列的消息列表（旧的在前，新的在后）
     */
    public List<MemoryMessage> getRecentMessages(String sessionId) {
        return memoryStore.getRecentMessages(sessionId);
    }

    /**
     * 向指定会话追加一条消息，自动按 {@code MAX_MESSAGES_PER_SESSION} 上限裁剪旧消息。
     *
     * <h3>裁剪策略</h3>
     * 裁剪逻辑下沉到 {@link ShortTermMemoryStore#append}，而非在此层做判断——
     * 因为不同存储实现（内存 / Redis）的裁剪方式不同：
     * 内存版可用 subList，Redis 版需用 LTRIM 命令。
     *
     * @param sessionId 会话标识
     * @param role      消息角色，如 "Human" 或 "AI"
     * @param content   消息正文
     */
    public void append(String sessionId, String role, String content) {
        memoryStore.append(sessionId, new MemoryMessage(role, content), MAX_MESSAGES_PER_SESSION);
    }

    /**
     * 摘要触发后裁剪短期消息，只保留最近的 {@code retainedMessageCount} 条。
     *
     * <h3>防御性处理</h3>
     * <ul>
     *   <li>{@code Math.max(0, retainedMessageCount)}：防止配置文件中误配负数，
     *       导致 {@code fromIndex} 计算出负值引发 {@code subList} 异常</li>
     *   <li>消息数不足时直接返回：如果当前消息数不超过保留数，无需裁剪，
     *       避免不必要的 {@code subList} + {@code replaceMessages} 开销</li>
     *   <li>{@code Math.max(0, messages.size() - safeRetainedMessageCount)}：
     *       双重保险，即使前面的检查有遗漏也不会算出负索引</li>
     * </ul>
     *
     * <h3>与摘要记忆的协作</h3>
     * 此方法由  在摘要触发后调用。
     * 调用顺序为「摘要先生成 → 短期消息再裁剪」——先压缩后再丢弃原文，
     * 保证「摘要一定覆盖了被裁剪掉的消息」的语义正确性。
     *
     * @param sessionId            会话标识
     * @param retainedMessageCount 摘要触发后应保留的消息数量，由 {@link SummaryMemory#getRetainedMessageCount} 提供
     */
    public void keepRecentMessages(String sessionId, int retainedMessageCount) {
        List<MemoryMessage> messages = memoryStore.getRecentMessages(sessionId);
        // 防止负值配置：若 retainedMessageCount 为负，取 0 语义为「清空所有消息」
        int safeRetainedMessageCount = Math.max(0, retainedMessageCount);
        if (messages.size() <= safeRetainedMessageCount) {
            // 当前消息数不超过保留数，无需裁剪
            return;
        }
        // 计算裁剪起点：从末尾往前保留 safeRetainedMessageCount 条
        int fromIndex = Math.max(0, messages.size() - safeRetainedMessageCount);
        // subList(fromIndex, size()) 取尾部：保证丢弃的是最旧的消息，保留最新的
        memoryStore.replaceMessages(sessionId, messages.subList(fromIndex, messages.size()));
    }

    /**
     * 列出所有活跃会话信息（如会话 ID、最后活跃时间等），用于管理端查看会话状态。
     *
     * @return 活跃会话列表
     */
    public List<SessionInfo> listSessions() {
        return memoryStore.listSessions();
    }

    /**
     * 获取指定会话的完整消息列表（不受窗口大小限制），用于调试和管理用途。
     * 与 {@link #getRecentMessages} 的区别：getRecentMessages 可能只返回窗口内的消息，
     * getMessages 返回存储中的所有消息。
     *
     * @param sessionId 会话标识
     * @return 完整消息列表
     */
    public List<MemoryMessage> getMessages(String sessionId) {
        return memoryStore.getMessages(sessionId);
    }
}
