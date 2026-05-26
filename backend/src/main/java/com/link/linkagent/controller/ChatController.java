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
 * 最简 Agent 调用链路：用户输入 → LLM → 返回结果。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final LLMService llmService;

    public ChatController(LLMService llmService) {
        this.llmService = llmService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        String reply = llmService.chat(request.message());
        return new ChatResponse(reply);
    }
}
