package com.link.linkagent.llm.usage;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * 给容器中的 EmbeddingModel 自动加统计代理。
 * 选择 BeanPostProcessor 是为了不侵入 Spring AI 的自动配置，也不要求业务侧修改所有注入点。
 */
@Component
public class MeteredEmbeddingModelBeanPostProcessor implements BeanPostProcessor {

    private final LlmApiUsageService llmApiUsageService;

    public MeteredEmbeddingModelBeanPostProcessor(LlmApiUsageService llmApiUsageService) {
        this.llmApiUsageService = llmApiUsageService;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof EmbeddingModel embeddingModel && !(bean instanceof MeteredEmbeddingModel)) {
            return new MeteredEmbeddingModel(embeddingModel, llmApiUsageService);
        }
        return bean;
    }
}
