package com.link.linkagent.controller;

import com.link.linkagent.dto.ChatRequest;
import com.link.linkagent.dto.ChatResponse;
import com.link.linkagent.llm.LLMService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 纯 LLM 聊天接口（无 Agent 编排）。
 * <p>
 * <b>架构定位：</b>
 * 这是最底层的对话入口 —— 用户输入直接发给 LLM，不走 ReAct 循环、不挂载工具、不维护会话记忆。
 * 与之对应的是 {@link AgentController}，后者走完整的 Agent 编排链路。
 * <p>
 * <b>适用场景：</b>
 * <ul>
 *   <li>快速验证 LLM 连接是否正常</li>
 *   <li>不需要工具的简单问答</li>
 *   <li>前端开发阶段的功能测试</li>
 * </ul>
 * <p>
 * <b>路由前缀：</b>{@code /api} —— 与 AgentController ({@code /api/agent}) 和 MemoryController ({@code /api/memory}) 并列。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final LLMService llmService;

    public ChatController(LLMService llmService) {
        this.llmService = llmService;
    }

    /**
     * 纯 LLM 对话：接收用户消息，返回模型回复。
     * <p>
     * <b>请求格式：</b>{@code POST /api/chat}
     * <pre>{@code
     * { "message": "你好，请介绍一下自己" }
     * }</pre>
     * <b>响应格式：</b>
     * <pre>{@code
     * { "reply": "你好！我是 DeepSeek..." }
     * }</pre>
     * <p>
     * <b>参数校验：</b>message 不能为空（由 {@code @NotBlank} 约束），
     * Spring 在进入方法前会自动返回 400。
     *
     * @param request 聊天请求，包含用户输入消息
     * @return 聊天响应，包含模型回复文本
     */
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        String reply = llmService.chat(request.message());
        return new ChatResponse(reply);
    }
}
