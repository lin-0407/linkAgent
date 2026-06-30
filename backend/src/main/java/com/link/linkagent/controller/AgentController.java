package com.link.linkagent.controller;

import com.link.linkagent.dto.AgentChatRequest;
import com.link.linkagent.dto.AgentChatResponse;
import com.link.linkagent.dto.SessionListItem;
import com.link.linkagent.dto.SessionMessageItem;
import com.link.linkagent.core.AgentExecutor;
import com.link.linkagent.memory.MemoryMessage;
import com.link.linkagent.memory.ShortTermMemory;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 对话接口，驱动 ReAct 循环并管理会话记忆。
 * <p>
 * <b>架构定位：</b>
 * 这是本项目的核心控制器 —— 所有带工具调用、多步推理的能力都通过此控制器暴露。
 * 与 {@link ChatController}（纯 LLM 对话）相比，本控制器走完整的 Agent 编排链路：
 * Thought → Action → Observation 迭代，并自动维护短期记忆。
 * <p>
 * <b>路由前缀：</b>{@code /api/agent}
 * <p>
 * <b>端点一览：</b>
 * <ul>
 *   <li>{@code POST /api/agent/chat} — 发起 Agent 对话</li>
 *   <li>{@code GET  /api/agent/sessions} — 列出所有会话</li>
 *   <li>{@code GET  /api/agent/sessions/{sessionId}} — 查看会话消息历史</li>
 * </ul>
 *
 * @see AgentExecutor ReAct 主循环实现
 * @see ShortTermMemory Redis 短期记忆存储
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentExecutor agentExecutor;
    /** 短期记忆服务，用于会话列表和消息历史查询。 */
    private final ShortTermMemory shortTermMemory;

    public AgentController(AgentExecutor agentExecutor, ShortTermMemory shortTermMemory) {
        this.agentExecutor = agentExecutor;
        this.shortTermMemory = shortTermMemory;
    }

    /**
     * 发起 Agent 对话，走完整 ReAct 循环。
     * <p>
     * <b>端点：</b>{@code POST /api/agent/chat}
     * <p>
     * <b>请求格式：</b>
     * <pre>{@code
     * {
     *   "sessionId": "uuid-string",       // 可选，不传则自动创建新会话
     *   "userId": "user-123",             // 可选
     *   "message": "分析这段视频文稿...",   // 必填
     *   "executionMode": "REACT"          // 可选，默认 REACT
     * }
     * }</pre>
     * <p>
     * <b>响应格式：</b>见 {@link AgentChatResponse}，包含：
     * <ul>
     *   <li>{@code finalAnswer} — 最终答案</li>
     *   <li>{@code steps} — 完整 ReAct 步骤追踪（Thought/Action/Observation）</li>
     *   <li>{@code totalSteps} — 步骤总数</li>
     *   <li>{@code stopReason} — 停止原因（max_iterations / final_answer）</li>
     *   <li>{@code executionMode} — 实际使用的执行模式</li>
     * </ul>
     * <p>
     * <b>核心流程：</b>
     * <ol>
     *   <li>参数校验（@Valid 触发，message 必填由 DTO 保证）</li>
     *   <li>委托 {@link AgentExecutor#run} 启动 ReAct 主循环</li>
     *   <li>循环内 LLM 输出 → 解析 Thought/Action → 执行工具 → 回灌 Observation</li>
     *   <li>达到最终答案或最大迭代次数时退出，返回完整追踪信息</li>
     * </ol>
     *
     * @param request Agent 聊天请求
     * @return Agent 聊天响应，包含最终答案和步骤追踪
     */
    @PostMapping("/chat")
    public AgentChatResponse chat(@Valid @RequestBody AgentChatRequest request) {
        return agentExecutor.run(request.sessionId(), request.userId(), request.message(), request.executionMode());
    }

    /**
     * 发起流式 Agent 对话（SSE），走完整 ReAct 循环并以事件流形式实时推送。
     * <p>
     * <b>端点：</b>{@code POST /api/agent/chat/stream}
     * <p>
     * 与 {@link #chat(AgentChatRequest)} 的最大区别：本端点返回 SSE（Server-Sent Events）流，
     * 在每个 ReAct 步骤完成时实时推送 {@code step} 事件，最终答案以 {@code token} 事件逐字符流式发送，
     * 最后以 {@code done} 事件结束。
     * <p>
     * <b>SSE 事件类型：</b>
     * <ul>
     *   <li>{@code session} — 连接建立后立即发送，携带 sessionId</li>
     *   <li>{@code step} — 每个 ReAct 步骤完成后推送，包含 thought/action/actionInput/observation</li>
     *   <li>{@code token} — 最终答案的单个字符或小片段，用于前端打字机效果</li>
     *   <li>{@code error} — 错误发生时推送，携带 message 字段</li>
     *   <li>{@code done} — 流结束信号，携带 sessionId</li>
     * </ul>
     * <p>
     * <b>超时设置：</b>SSE 连接最长保持 5 分钟（300000ms），超过此时间未完成则自动断开。
     * 这是为了在 Agent 陷入长循环或 LLM 响应极慢时，保护前端不无限等待。
     * <p>
     * <b>异步执行：</b>Agent 推理在独立线程中异步执行，Controller 立即返回 SseEmitter，
     * 让 HTTP 连接升级为 SSE 长连接。
     *
     * @param request Agent 聊天请求
     * @return SseEmitter 实例，由 Spring 管理其生命周期
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody AgentChatRequest request) {
        // 5 分钟超时：覆盖大多数 ReAct 推理场景（通常 30s-2min），也保护前端不无限等待
        SseEmitter emitter = new SseEmitter(300_000L);
        // 异步执行 Agent 推理：让 Controller 立即返回 emitter 建立 SSE 连接，
        // Agent 推理在独立线程中逐步推送事件；不阻塞 HTTP 响应发送
        CompletableFuture.runAsync(() -> {
            agentExecutor.runStreaming(
                    request.sessionId(), request.userId(), request.message(),
                    request.executionMode(), emitter);
        });
        return emitter;
    }

    /**
     * 列出所有活跃会话的摘要信息。
     * <p>
     * <b>端点：</b>{@code GET /api/agent/sessions}
     * <p>
     * <b>响应格式：</b>JSON 数组，每个元素包含：
     * <ul>
     *   <li>{@code sessionId} — 会话唯一标识</li>
     *   <li>{@code preview} — 最后一条消息的预览文本</li>
     *   <li>{@code messageCount} — 会话消息总数</li>
     * </ul>
     * <p>
     * <b>用途：</b>前端会话列表页面，用户可查看和切换历史会话。
     *
     * @return 会话摘要列表
     */
    @GetMapping("/sessions")
    public List<SessionListItem> sessions() {
        return shortTermMemory.listSessions().stream()
                .map(item -> new SessionListItem(item.sessionId(), item.preview(), item.messageCount()))
                .toList();
    }

    /**
     * 查看指定会话的完整消息历史。
     * <p>
     * <b>端点：</b>{@code GET /api/agent/sessions/{sessionId}}
     * <p>
     * <b>路径参数：</b>
     * <ul>
     *   <li>{@code sessionId} — 会话唯一标识</li>
     * </ul>
     * <p>
     * <b>响应格式：</b>JSON 数组，每个元素包含：
     * <ul>
     *   <li>{@code role} — 消息角色（user / assistant / tool）</li>
     *   <li>{@code content} — 消息内容</li>
     * </ul>
     * <p>
     * <b>用途：</b>前端点击会话后展开消息历史，或用于调试 ReAct 步骤历史。
     *
     * @param sessionId 会话唯一标识（路径参数）
     * @return 会话消息历史列表
     */
    @GetMapping("/sessions/{sessionId}")
    public List<SessionMessageItem> sessionMessages(@org.springframework.web.bind.annotation.PathVariable String sessionId) {
        return shortTermMemory.getMessages(sessionId).stream()
                .map(this::toMessageItem)
                .toList();
    }

    /**
     * 将内存层的 {@link MemoryMessage} 转换为 API 响应层的 {@link SessionMessageItem}。
     * 只暴露 role 和 content，隐藏内部元数据（如 messageType、timestamp 等）。
     */
    private SessionMessageItem toMessageItem(MemoryMessage message) {
        return new SessionMessageItem(message.role(), message.content());
    }
}
