package com.link.linkagent.knowledge.controller;

import com.link.linkagent.knowledge.model.ReferenceVideoMaintenanceRequest;
import com.link.linkagent.knowledge.service.KnowledgeReferenceMaintenanceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/** 自动脚本复用现有个人部署 API，不在后端批量采集 B 站。 */
@RestController
@RequestMapping("/api/knowledge/reference-videos")
public class KnowledgeReferenceMaintenanceController {
    private final KnowledgeReferenceMaintenanceService service;
    public KnowledgeReferenceMaintenanceController(KnowledgeReferenceMaintenanceService service) { this.service = service; }

    @PostMapping("/collection/status")
    public Map<String, Object> status(@Valid @RequestBody ReferenceVideoMaintenanceRequest.Status request) {
        return service.status(request.bvIds());
    }
    @PostMapping("/maintenance/backfill")
    public Map<String, Object> backfill(@Valid @RequestBody ReferenceVideoMaintenanceRequest.Backfill request) {
        return service.backfill(request.afterId(), request.limit());
    }
    @PostMapping("/maintenance/purge")
    public Map<String, Object> purge(@Valid @RequestBody ReferenceVideoMaintenanceRequest.Purge request) {
        return service.purge(request.batchSize());
    }
}
