package com.link.linkagent.memory;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 短期记忆 —— 保存单个会话的完整消息列表，为 Agent 提供最直接的对话连续性上下文。
 *
 * <h3>在记忆架构中的位置</h3>
 * 记忆拼接顺序为「长期记忆 → 摘要 → 短期记忆 → 用户输入」。
 * 短期记忆位于摘要之后、用户输入之前，是 LLM 做本轮推理时最直接的参考上下文。
 *
 * <h3>核心设计</h3>
 * <ul>
 *   <li><b>不按条数裁剪</b>：2026-09 记忆重构删除了 10 条滑动窗口（设计文档 A1）。
 *       原来的窗口会让早期对话在既没进摘要、也没进长期记忆的情况下直接消失；
 *       现在消息一直保留，是否压缩由「本次请求的 token 数」决定。</li>
 *   <li><b>压缩后裁剪</b>：只有摘要成功生成后，才由调用方调用 {@link #keepRecentMessages}
 *       裁掉被摘要覆盖的旧消息，形成「早期对话靠摘要、最近对话靠原文」的分层上下文。</li>
 *   <li><b>存储抽象</b>：通过 {@link ShortTermMemoryStore} 解耦存储实现，
 *       可在进程内 Map（本地开发）与 Redis（多实例共享）之间切换。</li>
 * </ul>
 */
@Component
public class ShortTermMemory {

    /**
     * 短期记忆的存储实现，由 {@code agent.memory.short-term.store-type} 决定注入内存版还是 Redis 版。
     */
    private final ShortTermMemoryStore memoryStore;

    public ShortTermMemory(ShortTermMemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    /**
     * 获取指定会话的全部消息，用于拼接 Agent 对话上下文。
     *
     * @param sessionId 会话标识
     * @return 按时间顺序排列的消息列表（旧的在前，新的在后）
     */
    public List<MemoryMessage> getRecentMessages(String sessionId) {
        return memoryStore.getRecentMessages(sessionId);
    }

    /**
     * 向指定会话追加一条消息。
     * <p>
     * 这里不再做任何裁剪：条数上限已删除，防膨胀由请求级 token 计量与摘要压缩负责（设计文档 A1 / A2）。
     *
     * @param sessionId 会话标识
     * @param role      消息角色，如 "Human" 或 "AI"
     * @param content   消息正文
     */
    public void append(String sessionId, String role, String content) {
        memoryStore.append(sessionId, new MemoryMessage(role, content));
    }

    /**
     * 摘要压缩后裁剪短期消息，只保留最近的 {@code retainedMessageCount} 条。
     *
     * <h3>调用时机</h3>
     * 只有摘要生成成功（摘要已经覆盖被裁掉的消息）之后才允许调用，
     * 调用顺序保证了「先压缩后丢弃原文」的语义正确性。
     *
     * <h3>防御性处理</h3>
     * <ul>
     *   <li>{@code Math.max(0, retainedMessageCount)}：防止配置误配负数导致 {@code subList} 索引异常</li>
     *   <li>消息数不足时直接返回：无需裁剪时不产生 {@code subList} + {@code replaceMessages} 开销</li>
     * </ul>
     *
     * @param sessionId            会话标识
     * @param retainedMessageCount 压缩后应保留的消息数量，由 {@link SummaryMemory#getRetainedMessageCount} 提供
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
        // subList(fromIndex, size()) 取尾部：保证丢弃的是最旧的、已被摘要覆盖的消息
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
     * 获取指定会话的完整消息列表，用于调试和管理用途。
     *
     * @param sessionId 会话标识
     * @return 完整消息列表
     */
    public List<MemoryMessage> getMessages(String sessionId) {
        return memoryStore.getMessages(sessionId);
    }
}
