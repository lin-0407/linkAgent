package com.link.linkagent.api.controller;

import com.link.linkagent.api.dto.AgentChatRequest;
import com.link.linkagent.api.dto.AgentChatResponse;
import com.link.linkagent.core.AgentExecutor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 对话接口，驱动 ReAct 循环。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentExecutor agentExecutor;

    public AgentController(AgentExecutor agentExecutor) {
        this.agentExecutor = agentExecutor;
    }

    @PostMapping("/chat")
    public AgentChatResponse chat(@Valid @RequestBody AgentChatRequest request) {
        return agentExecutor.run(request.message());
    }
}
