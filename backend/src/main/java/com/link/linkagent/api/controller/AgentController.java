package com.link.linkagent.api.controller;

import com.link.linkagent.api.dto.AgentChatRequest;
import com.link.linkagent.api.dto.AgentChatResponse;
import com.link.linkagent.api.dto.SessionListItem;
import com.link.linkagent.api.dto.SessionMessageItem;
import com.link.linkagent.core.AgentExecutor;
import com.link.linkagent.memory.MemoryMessage;
import com.link.linkagent.memory.ShortTermMemory;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 对话接口，驱动 ReAct 循环。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentExecutor agentExecutor;
    private final ShortTermMemory shortTermMemory;

    public AgentController(AgentExecutor agentExecutor, ShortTermMemory shortTermMemory) {
        this.agentExecutor = agentExecutor;
        this.shortTermMemory = shortTermMemory;
    }

    @PostMapping("/chat")
    public AgentChatResponse chat(@Valid @RequestBody AgentChatRequest request) {
        return agentExecutor.run(request.sessionId(), request.userId(), request.message());
    }

    @GetMapping("/sessions")
    public List<SessionListItem> sessions() {
        return shortTermMemory.listSessions().stream()
                .map(item -> new SessionListItem(item.sessionId(), item.preview(), item.messageCount()))
                .toList();
    }

    @GetMapping("/sessions/{sessionId}")
    public List<SessionMessageItem> sessionMessages(@org.springframework.web.bind.annotation.PathVariable String sessionId) {
        return shortTermMemory.getMessages(sessionId).stream()
                .map(this::toMessageItem)
                .toList();
    }

    private SessionMessageItem toMessageItem(MemoryMessage message) {
        return new SessionMessageItem(message.role(), message.content());
    }
}
