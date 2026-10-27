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
 *   <li><b>角色包含 Tool</b>：每轮执行过的工具步骤按原生协议写成两条消息——assistant 工具调用一条
 *       （渲染为 {@code ToolCall: 工具名(参数)}），Tool 结果一条；两条用 toolCallId 配对，
 *       让下一轮模型能看到 agent 自己的执行证据，和用户贴材料区分开。摘要提示词明确工具调用/结果不是用户本人陈述。</li>
 *   <li><b>压缩后裁剪，且只裁游标走过的</b>：摘要成功生成后由调用方调用裁剪；
 *       长期记忆提取是攒批异步推进的，压缩那一刻可能还有消息没被提取，
 *       这些消息留在短期记忆里，避免出现「原文已裁、长期记忆还没写」的空档（设计文档 A6 / D6）。</li>
 *   <li><b>存储抽象</b>：通过 {@link ShortTermMemoryStore} 解耦存储实现，
 *       可在进程内 Map（本地开发）与 Redis（多实例共享）之间切换。</li>
 * </ul>
 */
@Component
public class ShortTermMemory {

    /**
     * 工具结果消息的短期记忆角色。
     * <p>
     * 会话历史按原生协议拆成两条：assistant 的工具调用消息（渲染为 {@code ToolCall: 工具名(参数)}）在前，
     * 这条 Tool 消息紧跟其后保存工具结果，两条用 toolCallId 配对。裁剪时必须成对保留，
     * 不能只留下调用或只留下结果。
     */
    public static final String ROLE_TOOL = "Tool";

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
     * @param role      消息角色，如 "Human"、"AI" 或 {@link #ROLE_TOOL}
     * @param content   消息正文
     */
    public void append(String sessionId, String role, String content) {
        append(sessionId, role, content, null);
    }

    /**
     * 追加一条完整消息对象（含工具调用配对字段）。
     * <p>
     * 工具调用和工具结果要携带 toolCallId/toolArguments，不能退化成只有 role/content；
     * 渲染、裁剪边界判断和摘要提示词都依赖这些字段。
     */
    public void append(String sessionId, MemoryMessage message) {
        memoryStore.append(sessionId, message);
    }

    /**
     * 追加一条带消息 id 的消息。
     * <p>
     * id 来自消息表主键，是后续判断「长期记忆游标走过这条没有」的唯一依据（A6 / C4），
     * 所以能在写入时拿到就一定要带上；拿不到（消息表写入失败）时为 null，按「已走过」处理。
     *
     * @param messageId 消息在其落库表里的主键，可为 null
     */
    public void append(String sessionId, String role, String content, Long messageId) {
        memoryStore.append(sessionId, new MemoryMessage(role, content, messageId));
    }

    /**
     * 摘要压缩后裁剪短期消息，只保留最近的 {@code retainedMessageCount} 条。
     *
     * <h3>调用时机</h3>
     * 只有摘要生成成功（摘要已经覆盖被裁掉的消息）之后才允许调用，
     * 调用顺序保证了「先压缩后丢弃原文」的语义正确性。
     *
     * @param sessionId            会话标识
     * @param retainedMessageCount 压缩后应保留的消息数量，由 {@link SummaryMemory#getRetainedMessageCount} 提供
     */
    public void keepRecentMessages(String sessionId, int retainedMessageCount) {
        keepRecentMessages(sessionId, retainedMessageCount, null);
    }

    /**
     * 摘要压缩后裁剪短期消息：保留最近 {@code retainedMessageCount} 条，同时保留长期记忆游标还没走到的消息。
     *
     * <h3>为什么按游标留一部分</h3>
     * 摘要压缩发生在请求链路上，长期记忆提取由预算累加器攒批触发，两者不同步。
     * 如果压缩时把还没提取的消息一起裁掉，这些内容就只剩摘要一个载体（设计文档 D4 的已知代价）；
     * 只裁游标已覆盖的消息，能把这段空档补上（设计文档 A6 / D6）。
     * 代价是压缩后短期记忆不会立刻变短，要等提取跑完才真正瘦下来。
     *
     * <h3>防御性处理</h3>
     * <ul>
     *   <li>{@code Math.max(0, retainedMessageCount)}：防止配置误配负数导致 {@code subList} 索引异常</li>
     *   <li>消息数不足时直接返回：无需裁剪时不产生 {@code subList} + {@code replaceMessages} 开销</li>
     *   <li>{@code cursorMessageId} 为 null：没有游标信息（如单测构造路径），退化为「只按条数保留」</li>
     * </ul>
     *
     * @param sessionId            会话标识
     * @param retainedMessageCount 压缩后应保留的消息数量
     * @param cursorMessageId      长期记忆游标（已抽取到的最大消息 id），为 null 表示不做游标判断
     */
    public void keepRecentMessages(String sessionId, int retainedMessageCount, Long cursorMessageId) {
        List<MemoryMessage> messages = memoryStore.getRecentMessages(sessionId);
        // 防止负值配置：若 retainedMessageCount 为负，取 0 语义为「清空所有消息」
        int safeRetainedMessageCount = Math.max(0, retainedMessageCount);
        if (messages.isEmpty()) {
            return;
        }
        // 只按条数保留的裁剪起点：从末尾往前保留 safeRetainedMessageCount 条
        int byCountFromIndex = Math.max(0, messages.size() - safeRetainedMessageCount);
        // 只按游标保留的裁剪起点：第一条「游标还没走到」的消息，它之前的所有消息才允许裁掉
        int byCursorFromIndex = firstMessageNotCoveredByCursor(messages, cursorMessageId);
        // 两个条件取更靠前的位置：既满足保留条数，也满足游标没走到的消息一条不删
        int fromIndex = Math.min(byCountFromIndex, byCursorFromIndex);
        if (fromIndex >= messages.size()) {
            // 全部消息都不该保留，清空
            memoryStore.replaceMessages(sessionId, List.of());
            return;
        }
        // subList(fromIndex, size()) 取尾部：保证丢弃的是最旧的、已被摘要覆盖且已被提取的消息
        memoryStore.replaceMessages(sessionId, messages.subList(fromIndex, messages.size()));
    }

    /**
     * 找出第一条「长期记忆游标还没走到」的消息下标。
     * <p>
     * 消息 id 单调递增，因此第一个不满足 {@code id <= cursor} 的位置就是分界点；
     * id 为 null 的消息（没落库或旧数据）在消息表里查不到，再也不会被提取，按已覆盖处理。
     *
     * @return 分界下标；游标为 null、没有未覆盖消息时返回消息总数（表示「都可以裁」）
     */
    private int firstMessageNotCoveredByCursor(List<MemoryMessage> messages, Long cursorMessageId) {
        if (cursorMessageId == null) {
            return messages.size();
        }
        for (int index = 0; index < messages.size(); index++) {
            Long messageId = messages.get(index).messageId();
            if (messageId != null && messageId > cursorMessageId) {
                return index;
            }
        }
        return messages.size();
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
     * @return 完整消息列表
     */
    public List<MemoryMessage> getMessages(String sessionId) {
        return memoryStore.getMessages(sessionId);
    }

    /** 清理范围由评测任务持有的会话清单限制，这里只复用原子替换能力。 */
    public void clearSession(String sessionId) {
        memoryStore.replaceMessages(sessionId, List.of());
    }
}
