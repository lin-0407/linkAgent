package com.link.linkagent.knowledge.controller;

import com.link.linkagent.knowledge.model.ReferenceVideoFetchImportRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoAnalysisContextResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoImportRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoImportResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexStatusResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoPageResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoQualityRecomputeResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoSearchRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoSearchResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoTopicSearchRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoTopicSearchResponse;
import com.link.linkagent.knowledge.service.KnowledgeReferenceFetchService;
import com.link.linkagent.knowledge.service.KnowledgeReferenceChunkIndexService;
import com.link.linkagent.knowledge.service.KnowledgeReferenceHybridIndexService;
import com.link.linkagent.knowledge.service.KnowledgeReferenceIndexService;
import com.link.linkagent.knowledge.service.KnowledgeReferenceItemHybridIndexService;
import com.link.linkagent.knowledge.service.KnowledgeReferenceItemIndexService;
import com.link.linkagent.knowledge.service.KnowledgeReferenceRetrievalService;
import com.link.linkagent.knowledge.service.KnowledgeReferenceTopicSearchService;
import com.link.linkagent.knowledge.service.KnowledgeReferenceVideoService;
import com.link.linkagent.knowledge.service.KnowledgeQualityScoringService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final KnowledgeReferenceChunkIndexService knowledgeReferenceChunkIndexService;
    private final KnowledgeReferenceItemIndexService knowledgeReferenceItemIndexService;
    private final KnowledgeReferenceHybridIndexService knowledgeReferenceHybridIndexService;
    private final KnowledgeReferenceItemHybridIndexService knowledgeReferenceItemHybridIndexService;
    private final KnowledgeReferenceRetrievalService knowledgeReferenceRetrievalService;
    private final KnowledgeReferenceTopicSearchService knowledgeReferenceTopicSearchService;
    private final KnowledgeQualityScoringService knowledgeQualityScoringService;

    public KnowledgeReferenceVideoController(KnowledgeReferenceVideoService knowledgeReferenceVideoService,
                                             KnowledgeReferenceFetchService knowledgeReferenceFetchService,
                                             KnowledgeReferenceIndexService knowledgeReferenceIndexService,
                                             KnowledgeReferenceChunkIndexService knowledgeReferenceChunkIndexService,
                                             KnowledgeReferenceItemIndexService knowledgeReferenceItemIndexService,
                                             KnowledgeReferenceHybridIndexService knowledgeReferenceHybridIndexService,
                                             KnowledgeReferenceItemHybridIndexService knowledgeReferenceItemHybridIndexService,
                                             KnowledgeReferenceRetrievalService knowledgeReferenceRetrievalService,
                                             KnowledgeReferenceTopicSearchService knowledgeReferenceTopicSearchService,
                                             KnowledgeQualityScoringService knowledgeQualityScoringService) {
        this.knowledgeReferenceVideoService = knowledgeReferenceVideoService;
        this.knowledgeReferenceFetchService = knowledgeReferenceFetchService;
        this.knowledgeReferenceIndexService = knowledgeReferenceIndexService;
        this.knowledgeReferenceChunkIndexService = knowledgeReferenceChunkIndexService;
        this.knowledgeReferenceItemIndexService = knowledgeReferenceItemIndexService;
        this.knowledgeReferenceHybridIndexService = knowledgeReferenceHybridIndexService;
        this.knowledgeReferenceItemHybridIndexService = knowledgeReferenceItemHybridIndexService;
        this.knowledgeReferenceRetrievalService = knowledgeReferenceRetrievalService;
        this.knowledgeReferenceTopicSearchService = knowledgeReferenceTopicSearchService;
        this.knowledgeQualityScoringService = knowledgeQualityScoringService;
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
     * 重算全部分区质量分。
     * 表结构或公式口径调整后，用这个维护入口刷新历史数据，避免旧 quality_score 继续影响排序。
     */
    @PostMapping("/quality/recompute")
    public ReferenceVideoQualityRecomputeResponse recomputeReferenceVideoQuality() {
        return knowledgeQualityScoringService.recomputeAllCategories();
    }

    /**
     * 案例库检索（5.2a）：dense 语义检索父表案例卡片 + SQL 关键词兜底，响应回显本次实际检索模式。
     * RAG 关闭或向量库未就绪时不报错，正常返回 mode=SQL（优雅降级，非错误）；非法 tier / 空 query / 超长 → 400。
     */
    @PostMapping("/search")
    public ReferenceVideoSearchResponse searchReferenceVideos(
            @Valid @RequestBody ReferenceVideoSearchRequest request) {
        return knowledgeReferenceRetrievalService.search(request);
    }

    /**
     * 主题优先检索：先用 RAG 命中主题中块，再按质量信号展示 top5 视频卡片。
     * 刷新时传 page=2/3/4，分别展示 top6-10、top11-15、top16-20。
     */
    @PostMapping("/topic-search")
    public ReferenceVideoTopicSearchResponse topicSearchReferenceVideos(
            @Valid @RequestBody ReferenceVideoTopicSearchRequest request) {
        return knowledgeReferenceTopicSearchService.topicSearch(request);
    }

    /**
     * 点击某张视频卡片后，加载该视频的主题中块和评论弹幕上下文，供 AI 交互台自动进入该视频分析。
     */
    @GetMapping("/{videoId}/analysis-context")
    public ReferenceVideoAnalysisContextResponse referenceVideoAnalysisContext(
            @PathVariable
            @NotBlank(message = "videoId 不能为空")
            @Size(max = 64, message = "videoId 长度不能超过64个字符")
            String videoId) {
        return knowledgeReferenceTopicSearchService.analysisContext(videoId);
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

    /**
     * 重建（增量）主题中块向量索引：把标题包装 / 内容定位 / 观众反馈主题块写入中块集合并回写状态。
     * 与父表、子条目索引平行，三层分块各自独立，任一层不可用时都能单独降级。
     */
    @PostMapping("/index/chunks/rebuild")
    public ReferenceVideoIndexResponse rebuildReferenceVideoChunkIndex(
            @Valid @RequestBody(required = false) ReferenceVideoIndexRequest request) {
        return knowledgeReferenceChunkIndexService.rebuildChunks(request);
    }

    /**
     * 查询主题中块向量索引状态：各状态计数、最近成功索引时间、检索模式预测。
     * RAG 关闭时也正常返回（ragEnabled=false），用于确认中块层降级是否符合预期。
     */
    @GetMapping("/index/chunks/status")
    public ReferenceVideoIndexStatusResponse referenceVideoChunkIndexStatus() {
        return knowledgeReferenceChunkIndexService.chunkStatus();
    }

    /**
     * 重建（增量）子条目向量索引（5.2c-1）：把尚未成功索引的优质评论 / 弹幕原文写入子集合并回写状态。
     * 请求体可空（不带 maxItems 时用配置默认值）；RAG 未启用或子向量库未就绪时返回 400。
     * 与父表 /index/rebuild 平行、互不影响：子集合是 small-to-big 召回的 small 端，单独成索引动作。
     */
    @PostMapping("/index/items/rebuild")
    public ReferenceVideoIndexResponse rebuildReferenceVideoItemIndex(
            @Valid @RequestBody(required = false) ReferenceVideoIndexRequest request) {
        return knowledgeReferenceItemIndexService.rebuildItems(request);
    }

    /**
     * 查询子条目向量索引状态（5.2c-1）：各状态计数、最近成功索引时间、检索模式预测。
     * RAG 关闭时也正常返回（ragEnabled=false），用于确认子向量索引的优雅降级是否生效。
     */
    @GetMapping("/index/items/status")
    public ReferenceVideoIndexStatusResponse referenceVideoItemIndexStatus() {
        return knowledgeReferenceItemIndexService.itemStatus();
    }

    /**
     * 重建（整库重灌）父表原生 hybrid 索引（5.2d-1）：drop 旧 hybrid 集合 → 自建 schema 建集合 → 从 MySQL 全量重灌。
     * 需 knowledge.rag.enabled + knowledge.rag.hybrid.enabled + Milvus v2 就绪（服务端 ≥2.5），否则 400。
     */
    @PostMapping("/index/hybrid/rebuild")
    public ReferenceVideoIndexResponse rebuildReferenceVideoHybridIndex(
            @Valid @RequestBody(required = false) ReferenceVideoIndexRequest request) {
        return knowledgeReferenceHybridIndexService.rebuildHybrid(request);
    }

    /**
     * 查询父表原生 hybrid 索引状态（5.2d-1）：RAG/hybrid 是否就绪、可重灌的父卡片总数、检索模式预测（HYBRID/SQL）。
     * RAG/hybrid 关闭时也正常返回，用于确认降级。
     */
    @GetMapping("/index/hybrid/status")
    public ReferenceVideoIndexStatusResponse referenceVideoHybridIndexStatus() {
        return knowledgeReferenceHybridIndexService.hybridStatus();
    }

    /**
     * 重建（整库重灌）子条目原生 hybrid 索引（5.2d-3）：drop 旧子 hybrid 集合 → 自建 schema → 从 MySQL 全量重灌未删子条目。
     * 需 knowledge.rag.enabled + knowledge.rag.hybrid.enabled + Milvus v2 就绪，否则 400。子集合是 hybrid 开启时 small-to-big 的 small 端。
     */
    @PostMapping("/index/hybrid/items/rebuild")
    public ReferenceVideoIndexResponse rebuildReferenceVideoItemHybridIndex(
            @Valid @RequestBody(required = false) ReferenceVideoIndexRequest request) {
        return knowledgeReferenceItemHybridIndexService.rebuildChildHybrid(request);
    }

    /**
     * 查询子条目原生 hybrid 索引状态（5.2d-3）：RAG/hybrid 是否就绪、可重灌的子条目总数、检索模式预测（HYBRID/SQL）。
     * RAG/hybrid 关闭时也正常返回，用于确认降级。
     */
    @GetMapping("/index/hybrid/items/status")
    public ReferenceVideoIndexStatusResponse referenceVideoItemHybridIndexStatus() {
        return knowledgeReferenceItemHybridIndexService.childHybridStatus();
    }
}
