package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.config.KnowledgeRagProperties;
import com.link.linkagent.knowledge.model.QueryEnhanceStrategy;
import com.link.linkagent.knowledge.rag.HydeQueryTransformer;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 检索查询增强器（5.2b）。
 * <p>
 * 把用户原始 query 按策略扩展为「1~N 条用于 dense 检索的文本」，统一收敛为 {@link #enhance} 一个出口，
 * 让检索层（{@code KnowledgeReferenceRetrievalService}）只需「逐条检索 + 去重」，不感知具体策略。
 * <p>
 * 三策略均基于 Spring AI 原生 Modular RAG 查询组件：
 * <ul>
 *   <li>{@code REWRITE} → {@link RewriteQueryTransformer}（1 条改写）；</li>
 *   <li>{@code MULTI_QUERY} → {@link MultiQueryExpander}（N 条变体，{@code includeOriginal} 由框架并入原 query）；</li>
 *   <li>{@code HYDE} → 自定义 {@link HydeQueryTransformer}（1 条假设文档）。</li>
 * </ul>
 * 内置组件默认提示词是英文，对中文案例库会劣化甚至输出英文，故 REWRITE / MULTI_QUERY 都显式注入<b>中文</b>
 * {@code promptTemplate}（占位符 {@code {query}/{target}}、{@code {query}/{number}} 与框架默认模板一致，不可改名）。
 * <p>
 * <b>失败必降级</b>：任何策略异常 / 空结果 → 退回 {@code [原始 query]} 单路检索，绝不中断检索（见设计 §11.1）。
 */
@Service
public class KnowledgeQueryEnhancer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQueryEnhancer.class);

    /** 中文 REWRITE 模板：必须含 {target} 与 {query}（与框架默认模板占位符一致，改名会渲染报错）。 */
    private static final String REWRITE_PROMPT = """
            你是 B 站创作知识库的检索查询优化助手。请把下面的用户查询改写为更利于在 {target} 中做语义检索的查询：
            保留核心检索意图，去掉口语和无关信息，可补充同义词或上位词。
            只输出改写后的查询本身，不要任何解释。

            原始查询：
            {query}

            改写后的查询：
            """;

    /** 中文 MULTI_QUERY 模板：必须含 {number} 与 {query}。 */
    private static final String MULTI_QUERY_PROMPT = """
            你是信息检索与搜索优化专家。请为下面的查询生成 {number} 个不同角度的变体查询，
            覆盖该问题的不同侧面，同时保持原始查询的核心意图，以扩大召回范围。
            不要解释，不要添加任何额外文字，每行输出一个变体查询。

            原始查询：{query}

            变体查询：
            """;

    private final RewriteQueryTransformer rewriteQueryTransformer;
    private final MultiQueryExpander multiQueryExpander;
    private final HydeQueryTransformer hydeQueryTransformer;

    public KnowledgeQueryEnhancer(ChatClient.Builder chatClientBuilder,
                                  KnowledgeRagProperties knowledgeRagProperties,
                                  PromptService promptService) {
        // numberOfQueries 至少为 1，二次防御配置被误设成 0 / 负数导致组件构造异常。
        int multiQueryCount = Math.max(1, knowledgeRagProperties.getQueryEnhancement().getMultiQueryCount());
        // 三个组件共用注入的 ChatClient.Builder：各自 build 出独立 ChatClient（框架文档推荐用法）。
        this.rewriteQueryTransformer = RewriteQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder)
                .targetSearchSystem("视频案例知识库")
                .promptTemplate(new PromptTemplate(REWRITE_PROMPT))
                .build();
        this.multiQueryExpander = MultiQueryExpander.builder()
                .chatClientBuilder(chatClientBuilder)
                .numberOfQueries(multiQueryCount)
                .includeOriginal(true)
                .promptTemplate(new PromptTemplate(MULTI_QUERY_PROMPT))
                .build();
        this.hydeQueryTransformer = new HydeQueryTransformer(chatClientBuilder, promptService);
    }

    /**
     * 按策略把原始 query 扩展为 1~N 条「用于向量检索的文本」。
     * 任何异常 / 空结果都退回 {@code [原始 query]}，绝不中断检索。
     */
    public List<String> enhance(String query, QueryEnhanceStrategy strategy) {
        String original = TextUtil.trimToNull(query);
        if (original == null) {
            return List.of();
        }
        if (strategy == null || strategy == QueryEnhanceStrategy.NONE) {
            return List.of(original);
        }
        List<String> texts;
        try {
            texts = switch (strategy) {
                case REWRITE -> singletonText(rewriteQueryTransformer.transform(new Query(original)));
                case HYDE -> singletonText(hydeQueryTransformer.transform(new Query(original)));
                case MULTI_QUERY -> multiQueryExpander.expand(new Query(original)).stream()
                        .filter(Objects::nonNull)
                        .map(Query::text)
                        .collect(Collectors.toCollection(ArrayList::new));
                default -> new ArrayList<>(List.of(original));
            };
        } catch (Exception exception) {
            log.warn("查询增强失败（strategy={}），退回原始查询。query={}",
                    strategy, TextUtil.preview(original, 60, ""), exception);
            return List.of(original);
        }
        // 去空 + 去重；全空则退回原始 query，保证至少一条检索文本。
        List<String> cleaned = texts.stream()
                .map(TextUtil::trimToNull)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        return cleaned.isEmpty() ? List.of(original) : cleaned;
    }

    /** 单条策略（REWRITE / HYDE）结果包装；用 singletonList 容忍 null 元素，交由上游 trimToNull 过滤。 */
    private List<String> singletonText(Query transformed) {
        return Collections.singletonList(transformed == null ? null : transformed.text());
    }
}
