package com.link.linkagent.knowledge.rag;

import com.link.linkagent.knowledge.config.KnowledgeRagProperties;
import com.link.linkagent.llm.usage.LlmApiUsageService;
import com.link.linkagent.settings.service.RuntimeSettingService;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 知识库重排序（Rerank）客户端（阶段 5.2e）：调用 DashScope `qwen3-rerank` 对检索候选做精排。
 * <p>
 * <b>为什么直接 HTTP 调而非用 SDK / Spring AI 抽象</b>：Spring AI 1.1 没有一等 reranker 抽象（只有
 * {@code DocumentPostProcessor}，那是 {@code RetrievalAugmentationAdvisor} 链上的钩子，本项目知识库检索是自研
 * {@code KnowledgeReferenceRetrievalService}、没走那条链）；引入 DashScope Java SDK 又是为单个接口拉一整个重依赖。
 * 故用 Spring 自带 {@link RestClient} 直连，最简、最可控。
 * <p>
 * <b>端点踩坑（已联网核实，2026-06）</b>：老的 `gte-rerank` 已于 2026-05-30 下线，替代是 `qwen3-rerank`；
 * 且 `qwen3-rerank` 走的是 <b>OpenAI 兼容端点</b> {@code /compatible-api/v1/reranks}（注意是 {@code compatible-api}，
 * 不是 chat 用的 {@code compatible-mode}），响应是顶层 {@code results} 数组（非老接口的 {@code output} 包裹）。
 * <p>
 * <b>优雅降级（贯穿全链路的容错哲学）</b>：未启用 / 未配 Key / 候选 ≤1 条 / 调用异常 → 一律返回空列表，
 * 调用方据此<b>保持原检索顺序</b>，绝不让 rerank 失败中断检索（同 5.2a「向量失败退 SQL」、5.2c「子失败退父-only」）。
 */
@Component
public class KnowledgeRerankClient {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRerankClient.class);

    private final KnowledgeRagProperties properties;
    private final RuntimeSettingService runtimeSettingService;
    private final LlmApiUsageService llmApiUsageService;
    /** 预构建的 RestClient：只配超时、不设 baseUrl；构建本身不建连接，关 rerank 时也不会真正发请求。 */
    private final RestClient restClient;
    /**
     * 完整的 rerank 端点 URL（绝对地址）。
     * <b>不</b>用 {@code baseUrl(...)} + {@code uri("/reranks")} 拼接——按 RFC 3986，前导斜杠的相对路径会
     * 替换掉 baseUrl 的路径段（{@code .../compatible-api/v1} + {@code /reranks} → {@code host/reranks}，丢了路径前缀导致 404）。
     * 故这里直接拼绝对 URL 交给 {@code uri(...)}，最稳。
     */
    private final String endpoint;

    public KnowledgeRerankClient(KnowledgeRagProperties properties,
                                 RuntimeSettingService runtimeSettingService,
                                 LlmApiUsageService llmApiUsageService) {
        this.properties = properties;
        this.runtimeSettingService = runtimeSettingService;
        this.llmApiUsageService = llmApiUsageService;
        KnowledgeRagProperties.Rerank rerank = properties.getRerank();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 连接 / 读取都设超时：rerank 是检索链路里的同步外部调用，慢响应不能拖垮整个 /search。
        factory.setConnectTimeout(Duration.ofMillis(rerank.getTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(rerank.getTimeoutMs()));
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
        String base = rerank.getBaseUrl();
        this.endpoint = (base.endsWith("/") ? base + "reranks" : base + "/reranks");
    }

    /**
     * 用 qwen3-rerank 对候选文档按 query 精排，返回<b>重排后的原始下标顺序</b>（candidates 中的 index）。
     * <p>
     * 只依赖响应 {@code results[].index} 的返回次序（API 已按相关性降序），不依赖分数字段名，解析稳健。
     * 关闭 / 未配 Key / 候选 ≤1 / 任意异常 → 返回空列表，调用方保持原序。
     *
     * @param query     原始用户查询（用用户真实意图精排，不用改写/HyDE 扩展文本）
     * @param documents 与候选一一对应的待排文本
     * @return 重排后的下标列表（如 [2,0,1] 表示原第 3 条最相关）；空列表表示「未重排，按原序」
     */
    public List<Integer> rerank(String query, List<String> documents) {
        KnowledgeRagProperties.Rerank rerank = properties.getRerank();
        // 关、无 Key、或候选不足 2 条（无需精排）都直接短路，不发外部请求。
        if (!runtimeSettingService.isKnowledgeRerankEnabled() || !TextUtil.hasText(rerank.getApiKey())
                || documents == null || documents.size() <= 1) {
            llmApiUsageService.recordRerankSkipped(rerank.getModel(), "rerank 未启用、未配置 Key 或候选不足", documents == null ? 0 : documents.size());
            return List.of();
        }
        long startNanos = System.nanoTime();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", rerank.getModel());
            body.put("query", query);
            body.put("documents", documents);
            // 不要回显文档原文，省响应体积；只需要 index 次序。
            body.put("return_documents", false);

            RerankResponse response = restClient.post()
                    .uri(endpoint)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + rerank.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(RerankResponse.class);
            llmApiUsageService.recordRerankSuccess(
                    response == null ? rerank.getModel() : firstNonBlank(response.model(), rerank.getModel()),
                    extractPromptTokens(response),
                    extractTotalTokens(response),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos),
                    documents.size()
            );

            if (response == null || response.results() == null || response.results().isEmpty()) {
                return List.of();
            }
            List<Integer> order = new ArrayList<>();
            for (RerankResult result : response.results()) {
                // 越界保护：只接受落在候选范围内的下标，避免脏响应导致后续取错元素。
                if (result != null && result.index() != null
                        && result.index() >= 0 && result.index() < documents.size()) {
                    order.add(result.index());
                }
            }
            return order;
        } catch (Exception exception) {
            llmApiUsageService.recordRerankFailure(
                    rerank.getModel(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos),
                    exception,
                    documents.size()
            );
            log.warn("qwen3-rerank 重排调用失败，保持原检索顺序。model={}", rerank.getModel(), exception);
            return List.of();
        }
    }

    /**
     * qwen3-rerank 兼容端点响应：{@code { "results": [ { "index": i, "relevance_score": s } ] }}（已按相关性降序）。
     * 只声明需要的 index 字段；relevance_score / document 等其余字段由 Jackson 默认忽略（Spring Boot 默认不对未知字段报错）。
     */
    private Integer extractPromptTokens(RerankResponse response) {
        if (response == null || response.usage() == null) {
            return null;
        }
        return firstPositive(response.usage().promptTokens(), response.usage().inputTokens());
    }

    private Integer extractTotalTokens(RerankResponse response) {
        if (response == null || response.usage() == null) {
            return null;
        }
        return firstPositive(response.usage().totalTokens(), response.usage().promptTokens(), response.usage().inputTokens());
    }

    private Integer firstPositive(Integer... values) {
        for (Integer value : values) {
            if (value != null && value > 0) {
                return value;
            }
        }
        return null;
    }

    private String firstNonBlank(String left, String right) {
        if (TextUtil.hasText(left)) {
            return left.trim();
        }
        if (TextUtil.hasText(right)) {
            return right.trim();
        }
        return null;
    }

    private record RerankResponse(String model, List<RerankResult> results, RerankUsage usage) {
    }

    private record RerankResult(Integer index) {
    }

    /**
     * 不同 OpenAI 兼容网关可能用 prompt_tokens / input_tokens 表达 rerank 输入 token。
     * 两套字段都声明出来，是为了尽量接住供应商真实返回值；没有返回时统计层保持 null。
     */
    private record RerankUsage(
            @com.fasterxml.jackson.annotation.JsonProperty("prompt_tokens") Integer promptTokens,
            @com.fasterxml.jackson.annotation.JsonProperty("input_tokens") Integer inputTokens,
            @com.fasterxml.jackson.annotation.JsonProperty("total_tokens") Integer totalTokens
    ) {
    }
}
