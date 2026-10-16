package com.link.linkagent.knowledge.controller;

import com.link.linkagent.knowledge.model.ReferenceAutoCollectionRequest;
import com.link.linkagent.knowledge.model.ReferenceAutoCollectionResponse;
import com.link.linkagent.knowledge.service.KnowledgeReferenceAutoCollectionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge/reference-videos/auto-collection")
public class KnowledgeReferenceAutoCollectionController {
    private final KnowledgeReferenceAutoCollectionService service;

    public KnowledgeReferenceAutoCollectionController(KnowledgeReferenceAutoCollectionService service) {
        this.service = service;
    }

    @GetMapping
    public ReferenceAutoCollectionResponse status() {
        return service.status();
    }

    @PostMapping
    public ReferenceAutoCollectionResponse start(@Valid @RequestBody ReferenceAutoCollectionRequest request,
                                                HttpServletRequest servletRequest) {
        // 使用实际本机监听端口，不让页面提供脚本路径、命令或任意后端地址。
        return service.start(request, servletRequest.getLocalPort(), servletRequest.getContextPath());
    }

    @PostMapping("/stop")
    public ReferenceAutoCollectionResponse stop() {
        return service.stop();
    }
}
