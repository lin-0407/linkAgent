package com.link.linkagent.memory;

import com.link.linkagent.util.TextUtil;

/**
 * 短期对话记忆中的单条消息。
 *
 * <h3>为什么工具调用和工具结果要分开</h3>
 * 会话历史按 OpenAI/DeepSeek 原生协议存：assistant 的 tool_call 是一条消息，tool 的结果是紧随其后的另一条消息，
 * 两条用 {@code toolCallId} 配对。这样模型看到的是「调用 → 结果」两段，而不是把两者拼进同一条记录；
 * 裁剪时也要求两条一起保留/一起裁掉，不会出现只有结果、没有调用的断头记录。
 *
 * <h3>字段含义</h3>
 * <ul>
 *   <li>{@code role}/{@code content}：普通消息沿用原语义；工具调用消息的 content 为空串。</li>
 *   <li>{@code toolCallId}：assistant 调用和 tool 结果共用的配对 id；普通消息为 null。</li>
 *   <li>{@code toolName}：工具名，调用与结果都带，便于回看。</li>
 *   <li>{@code toolArguments}：工具调用参数摘要（≤300 字），只有调用消息有；结果为 null。
 *       用它区分「调用」和「结果」：{@code toolArguments != null} 就是调用消息。</li>
 * </ul>
 *
 * @param role          消息角色，如 "Human" / "AI" / "Tool"
 * @param content       消息正文；工具调用消息为空串
 * @param messageId     这条消息在 t_conversation_message 里的主键；null 表示没有落库（写库失败或旧数据）。
 *                      长期记忆游标记的是消息 id，短期记忆带上它才能判断「这条消息游标走过没有」（设计文档 A6 / C4）。
 * @param toolCallId    原生协议工具调用 id；普通消息为 null
 * @param toolName      工具名；普通消息为 null
 * @param toolArguments 工具调用参数摘要；工具结果消息和普通消息为 null
 */
public record MemoryMessage(
        String role,
        String content,
        Long messageId,
        String toolCallId,
        String toolName,
        String toolArguments
) {

    public MemoryMessage {
        content = content == null ? "" : content;
        toolCallId = TextUtil.trimToNull(toolCallId);
        toolName = TextUtil.trimToNull(toolName);
        // 参数摘要非空串有语义（空串参数也是有效调用），这里只做 null 透传，不 trim 成 null。
        if (toolArguments != null) {
            toolArguments = toolArguments.trim();
        }
    }

    /** 不带消息 id 的构造：摘要、复核这些只关心内容的链路用它，避免调用方为了编译补一堆 null。 */
    public MemoryMessage(String role, String content) {
        this(role, content, null, null, null, null);
    }

    /** 兼容旧调用点：只带消息 id 的普通消息。 */
    public MemoryMessage(String role, String content, Long messageId) {
        this(role, content, messageId, null, null, null);
    }

    /** assistant 工具调用消息：正文为空，参数放在 toolArguments，与后面的 Tool 结果用 toolCallId 配对。 */
    public static MemoryMessage toolCall(String role, Long messageId, String toolCallId, String toolName,
                                         String toolArguments) {
        return new MemoryMessage(role, "", messageId, toolCallId, toolName, toolArguments);
    }

    /** tool 结果消息：正文是工具结果；toolArguments 保持 null，和调用消息区分开。 */
    public static MemoryMessage toolResult(String role, Long messageId, String toolCallId, String toolName,
                                           String content) {
        return new MemoryMessage(role, content, messageId, toolCallId, toolName, null);
    }

    /** 是否工具调用消息：参数摘要非 null 是唯一判据（结果消息为 null）。 */
    public boolean isToolCall() {
        return toolArguments != null;
    }

    /** 是否工具结果消息：有配对 id 且没有调用参数。 */
    public boolean isToolResult() {
        return toolCallId != null && toolArguments == null;
    }

    /**
     * 渲染给模型看的正文。
     * <p>
     * 工具调用消息本身 content 为空，渲染成 {@code ToolCall: 工具名(参数摘要)}，下一行/下一条就是它的 Tool 结果；
     * 其余消息原样返回。摘要、复核、事实点抽取和上下文组装都用这一份渲染，避免多处各写一套格式。
     */
    public String promptText() {
        if (isToolCall()) {
            return "ToolCall: " + TextUtil.trimToDefault(toolName, "") + "(" + toolArguments + ")";
        }
        return content;
    }
}
