package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.config.KnowledgeRagProperties;
import com.link.linkagent.knowledge.model.QueryEnhanceStrategy;
import com.link.linkagent.knowledge.rag.HydeQueryTransformer;
import com.link.linkagent.llm.DeepSeekThinkingOptionsFactory;
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
 * 检索查询增强器（阶段 5.2b）。
 * <p>
 * 在 RAG 检索架构中的位置：<b>预检索（Pre-retrieval）层</b>，位于用户输入和向量检索之间。
 * 不直接操作 Milvus，只负责把用户原始查询按策略扩展为 1-N 条用于向量语义检索的高质量文本。
 * 检索层（{@code KnowledgeReferenceRetrievalService}）只需「逐条检索 + 去重」，不感知具体增强策略。
 * <p>
 * 核心设计决策：
 * <ul>
 *   <li><b>三策略基于 Spring AI 原生 Modular RAG 查询组件</b>：REWRITE 用 {@link RewriteQueryTransformer}、
 *       MULTI_QUERY 用 {@link MultiQueryExpander}、HYDE 用自定义 {@link HydeQueryTransformer}。
 *       不自行实现 LLM 调用链，利用框架已有的异常处理、超时、重试能力。</li>
 *   <li><b>中文提示词显式注入</b>：Spring AI 内置组件的默认提示词是英文，对 B 站中文案例库会劣化甚至
 *       输出英文改写结果。因此 REWRITE 和 MULTI_QUERY 都显式注入中文 promptTemplate。
 *       占位符 {query}/{target}/{number} 与框架默认模板一致，不可改名——改名会导致框架渲染时报错。</li>
 *   <li><b>失败必降级、绝不中断检索</b>：查询增强是「锦上添花」而非「不可或缺」——任何策略执行异常
 *       或返回空结果时，自动退回原始 query 单路检索。宁可少召回一些案例，也不能因为增强器故障
 *       让用户的整个 RAG 检索流程中断。</li>
 * </ul>
 */
@Service
public class KnowledgeQueryEnhancer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQueryEnhancer.class);

    /**
     * 中文 REWRITE 策略的提示词模板。
     * 必须包含 {target}（检索目标系统描述）和 {query}（用户原始查询）两个占位符，
     * 占位符名与 RewriteQueryTransformer 框架源码中的硬编码名称一致，改名会导致模板渲染抛出异常。
     */
    private static final String REWRITE_PROMPT = """
            你是 B 站创作知识库的检索查询优化助手。请把下面的用户查询改写为更利于在 {target} 中做语义检索的查询：
            保留核心检索意图，去掉口语和无关信息，可补充同义词或上位词。
            只输出改写后的查询本身，不要任何解释。

            原始查询：
            {query}

            改写后的查询：
            """;

    /**
     * 中文 MULTI_QUERY 策略的提示词模板。
     * 必须包含 {number}（期望变体数量，框架注入）和 {query}（用户原始查询）两个占位符。
     * 提示词要求"每行一个变体"，因为 MultiQueryExpander 默认按行解析输出。
     */
    private static final String MULTI_QUERY_PROMPT = """
            你是信息检索与搜索优化专家。请为下面的查询生成 {number} 个不同角度的变体查询，
            覆盖该问题的不同侧面，同时保持原始查询的核心意图，以扩大召回范围。
            不要解释，不要添加任何额外文字，每行输出一个变体查询。

            原始查询：{query}

            变体查询：
            """;

    /** 原始 Builder，用于每次查询按运行期设置创建带思考参数的独立组件。 */
    private final ChatClient.Builder chatClientBuilder;

    /** Multi-query 变体数量，组件按请求创建但配置保持固定。 */
    private final int multiQueryCount;

    /** HyDE 组件需要的提示词服务。 */
    private final PromptService promptService;

    /** DeepSeek Flash 思考参数工厂，设置页修改后下一次查询即可生效。 */
    private final DeepSeekThinkingOptionsFactory deepSeekThinkingOptionsFactory;

    /**
     * 保存查询增强所需的 Builder 和固定参数；实际组件按查询创建，以便运行期思考设置无需重启即可生效。
     * <p>
     * 每次创建都会 clone 原始 Builder 并独立 build ChatClient，避免一个策略的请求选项污染另一个策略。
     *
     * @param chatClientBuilder Spring AI ChatClient 构造器（通过 Spring 自动注入）
     * @param knowledgeRagProperties RAG 运行期配置，取 multiQueryCount 等参数
     * @param promptService 提示词模板服务，供 HyDE 组件渲染中文提示词模板
     * @param deepSeekThinkingOptionsFactory DeepSeek Flash 思考参数工厂
     */
    public KnowledgeQueryEnhancer(ChatClient.Builder chatClientBuilder,
                                  KnowledgeRagProperties knowledgeRagProperties,
                                  PromptService promptService,
                                  DeepSeekThinkingOptionsFactory deepSeekThinkingOptionsFactory) {
        // numberOfQueries 至少为 1：二次防御配置被误设成 0 或负数导致框架组件构造异常
        this.chatClientBuilder = chatClientBuilder;
        this.multiQueryCount = Math.max(1, knowledgeRagProperties.getQueryEnhancement().getMultiQueryCount());
        this.promptService = promptService;
        this.deepSeekThinkingOptionsFactory = deepSeekThinkingOptionsFactory;
    }

    /**
     * 按策略把原始查询扩展为 1-N 条「用于向量检索的文本」。
     * <p>
     * 这是查询增强层的唯一对外出口——检索层调用此方法后拿到一个文本列表，
     * 对每条文本分别做向量检索然后去重合并。检索层不需要知道是哪个策略产生的这些文本。
     * <p>
     * 三种策略的输出语义：
     * <ul>
     *   <li>REWRITE：LLM 将用户口语查询改写为更精确的检索语句（1 条）</li>
     *   <li>HYDE：LLM 生成假设答案文档文本，用假设答案做向量检索（1 条）</li>
     *   <li>MULTI_QUERY：LLM 生成多条不同角度的变体查询，扩大召回范围（N 条，含原始 query）</li>
     * </ul>
     * <p>
     * 降级策略（三层防护，由内到外）：
     * <ol>
     *   <li>外层 catch：任何策略执行异常 → 捕捉后以 warn 级别记录日志，退回原始 query 单路检索</li>
     *   <li>空值过滤：去空 + 去重后若列表全空 → 退回原始 query，保证至少有一条检索文本可执行</li>
     *   <li>入参防御：原始 query 为空或策略为 NONE/null → 直接返回原始 query 列表（含空返回空列表）</li>
     * </ol>
     *
     * @param query 用户原始查询文本（可能为空或 null）
     * @param strategy 查询增强策略枚举（REWRITE / HYDE / MULTI_QUERY / NONE）
     * @return 1-N 条用于向量检索的文本，保证非 null（最坏情况返回空列表）
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
                case REWRITE -> singletonText(createRewriteTransformer().transform(new Query(original)));
                case HYDE -> singletonText(createHydeQueryTransformer().transform(new Query(original)));
                case MULTI_QUERY -> createMultiQueryExpander().expand(new Query(original)).stream()
                        .filter(Objects::nonNull)
                        .map(Query::text)
                        .collect(Collectors.toCollection(ArrayList::new));
                default -> new ArrayList<>(List.of(original));
            };
        } catch (Exception exception) {
            // 捕捉所有异常——查询增强是可选的锦上添花，任何故障都不应中断检索主流程
            log.warn("查询增强失败（strategy={}），退回原始查询。query={}",
                    strategy, TextUtil.preview(original, 60, ""), exception);
            return List.of(original);
        }
        // 去空（trimToNull 过滤空串和纯空格）+ 去重（distinct 避免同一条向量被多次检索后重复去重）。
        // 全空说明增强产出完全失败（如 LLM 返回空），退回原始 query 保障至少有一条检索文本。
        List<String> cleaned = texts.stream()
                .map(TextUtil::trimToNull)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        return cleaned.isEmpty() ? List.of(original) : cleaned;
    }

    /**
     * 将单条策略（REWRITE / HYDE）的 Query 结果包装为单元素列表。
     * <p>
     * 用 singletonList 而非 List.of 的原因：transformed 可能为 null，
     * 需要容忍 null 元素存在（后续由 trimToNull + filter 统一清理），
     * 此处不做 null 检查是为了让退化逻辑集中在一处。
     *
     * @param transformed 转换后的 Query 对象，可能为 null
     * @return 单元素列表（元素可能为 null）
     */
    private List<String> singletonText(Query transformed) {
        return Collections.singletonList(transformed == null ? null : transformed.text());
    }

    private RewriteQueryTransformer createRewriteTransformer() {
        return RewriteQueryTransformer.builder()
                .chatClientBuilder(configuredBuilder())
                .targetSearchSystem("视频案例知识库")
                .promptTemplate(new PromptTemplate(REWRITE_PROMPT))
                .build();
    }

    private MultiQueryExpander createMultiQueryExpander() {
        return MultiQueryExpander.builder()
                .chatClientBuilder(configuredBuilder())
                .numberOfQueries(multiQueryCount)
                .includeOriginal(true)
                .promptTemplate(new PromptTemplate(MULTI_QUERY_PROMPT))
                .build();
    }

    private HydeQueryTransformer createHydeQueryTransformer() {
        return new HydeQueryTransformer(configuredBuilder(), promptService);
    }

    private ChatClient.Builder configuredBuilder() {
        return deepSeekThinkingOptionsFactory.configureDefaultBuilder(chatClientBuilder);
    }
}
