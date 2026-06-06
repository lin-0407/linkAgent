package com.link.linkagent.knowledge.controller;

import com.link.linkagent.knowledge.model.ReferenceVideoFetchImportRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoImportRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoImportResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexStatusResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoPageResponse;
import com.link.linkagent.knowledge.service.KnowledgeReferenceFetchService;
import com.link.linkagent.knowledge.service.KnowledgeReferenceIndexService;
import com.link.linkagent.knowledge.service.KnowledgeReferenceVideoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跨分区视频案例库接口。
 * 暴露「导入 / 单 BV 一键采集导入 / 列表 / 向量索引重建 / 索引状态」五个接口；
 * 案例库跨创作任务共享，故路径不挂在 /tasks/{taskId} 下，而是独立的 /api/knowledge 命名空间。
 */
@Validated
@RestController
@RequestMapping("/api/knowledge/reference-videos")
public class KnowledgeReferenceVideoController {

    private final KnowledgeReferenceVideoService knowledgeReferenceVideoService;
    private final KnowledgeReferenceFetchService knowledgeReferenceFetchService;
    private final KnowledgeReferenceIndexService knowledgeReferenceIndexService;

    public KnowledgeReferenceVideoController(KnowledgeReferenceVideoService knowledgeReferenceVideoService,
                                             KnowledgeReferenceFetchService knowledgeReferenceFetchService,
                                             KnowledgeReferenceIndexService knowledgeReferenceIndexService) {
        this.knowledgeReferenceVideoService = knowledgeReferenceVideoService;
        this.knowledgeReferenceFetchService = knowledgeReferenceFetchService;
        this.knowledgeReferenceIndexService = knowledgeReferenceIndexService;
    }

    /**
     * 导入离线脚本 / seed 产出的案例 JSON，落入父表（清洗与向量化不在本阶段）。
     */
    @PostMapping("/import")
    public ReferenceVideoImportResponse importReferenceVideos(
            @Valid @RequestBody ReferenceVideoImportRequest request) {
        return knowledgeReferenceVideoService.importReferenceVideos(request);
    }

    /**
     * 输入 BV → 后端显式调用采集脚本（限量）→ 自动清洗导入案例库，一键完成。
     * 单 BV 显式触发属于项目允许的采集方式；榜单批量仍只走离线脚本，不在此接口暴露。
     */
    @PostMapping("/fetch-import")
    public ReferenceVideoImportResponse fetchAndImportReferenceVideo(
            @Valid @RequestBody ReferenceVideoFetchImportRequest request) {
        return knowledgeReferenceFetchService.fetchAndImport(request);
    }

    /**
     * 分页列出案例，支持按分区、层级过滤，便于导入后核对数据是否正确落库。
     */
    @GetMapping
    public ReferenceVideoPageResponse listReferenceVideos(
            @RequestParam(required = false)
            @Size(max = 64, message = "分区过滤长度不能超过64个字符")
            String category,

            @RequestParam(required = false)
            @Size(max = 16, message = "层级过滤长度不能超过16个字符")
            String tier,

            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码最小为1")
            int page,

            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页条数最小为1")
            @Max(value = 100, message = "每页条数最大为100")
            int size) {
        return knowledgeReferenceVideoService.listReferenceVideos(category, tier, page, size);
    }

    /**
     * 重建（增量）案例库向量索引：把尚未成功索引的案例卡片写入 Milvus 并回写状态。
     * 请求体可空（不带 maxItems 时用配置默认值）；RAG 未启用或向量库未就绪时返回 400。
     */
    @PostMapping("/index/rebuild")
    public ReferenceVideoIndexResponse rebuildReferenceVideoIndex(
            @Valid @RequestBody(required = false) ReferenceVideoIndexRequest request) {
        return knowledgeReferenceIndexService.rebuild(request);
    }

    /**
     * 查询案例库向量索引状态：各状态计数、最近成功索引时间、检索模式预测。
     * RAG 关闭时也正常返回（ragEnabled=false），用于确认优雅降级是否生效。
     */
    @GetMapping("/index/status")
    public ReferenceVideoIndexStatusResponse referenceVideoIndexStatus() {
        return knowledgeReferenceIndexService.status();
    }
}
