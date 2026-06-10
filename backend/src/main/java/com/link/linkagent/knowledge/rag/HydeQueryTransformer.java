package com.link.linkagent.knowledge.rag;

import com.link.linkagent.prompt.service.PromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.util.Assert;

/**
 * HyDE（Hypothetical Document Embeddings）查询变换器（5.2b）。
 * <p>
 * Spring AI 1.1.4 内置 {@code RewriteQueryTransformer} / {@code MultiQueryExpander}，但<b>没有 HyDE</b>。
 * 这里实现框架的 {@link QueryTransformer} 接口，把 HyDE 融入同一 Modular RAG 抽象：先用 LLM 生成一段
 * 「假设的优质视频亮点摘要」，再以这段假设文档的语义去向量检索——用户的短查询与父表「案例卡片」长文本的
 * 语义分布差异大，用「假设答案」检索往往比用原始短查询更贴近案例卡片，从而提升召回。
 * <p>
 * <b>失败哲学</b>：生成异常 / 为空 → 原样返回输入 query（绝不抛出中断检索）；外层 {@code KnowledgeQueryEnhancer} 再兜一层。
 */
public class HydeQueryTransformer implements QueryTransformer {

    private static final Logger log = LoggerFactory.getLogger(HydeQueryTransformer.class);

    private final ChatClient chatClient;
    private final PromptService promptService;

    public HydeQueryTransformer(ChatClient.Builder chatClientBuilder, PromptService promptService) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder 不能为空");
        Assert.notNull(promptService, "promptService 不能为空");
        this.chatClient = chatClientBuilder.build();
        this.promptService = promptService;
    }

    @Override
    public Query transform(Query query) {
        Assert.notNull(query, "query 不能为空");
        String original = query.text();
        try {
            String hypothetical = chatClient.prompt()
                    .system(promptService.get("hyde.system"))
                    .user(original)
                    .call()
                    .content();
            if (hypothetical == null || hypothetical.isBlank()) {
                log.warn("HyDE 生成为空，返回原始查询。");
                return query;
            }
            // 用假设文档作为新的检索文本；本检索场景的 query 不带 history/context，故直接 new Query 即可。
            return new Query(hypothetical.trim());
        } catch (Exception exception) {
            log.warn("HyDE 生成失败，返回原始查询。", exception);
            return query;
        }
    }
}
