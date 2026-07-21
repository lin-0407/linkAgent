package com.link.linkagent.llm.usage;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MeteredEmbeddingModelBeanPostProcessorTest {

    @Test
    void shouldResolveUsageServiceOnlyWhenWrappingEmbeddingModel() {
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmApiUsageService> usageServiceProvider = mock(ObjectProvider.class);
        LlmApiUsageService usageService = mock(LlmApiUsageService.class);
        MeteredEmbeddingModelBeanPostProcessor processor =
                new MeteredEmbeddingModelBeanPostProcessor(usageServiceProvider);

        Object regularBean = new Object();
        assertThat(processor.postProcessAfterInitialization(regularBean, "regularBean")).isSameAs(regularBean);
        verifyNoInteractions(usageServiceProvider);

        when(usageServiceProvider.getObject()).thenReturn(usageService);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        Object processed = processor.postProcessAfterInitialization(embeddingModel, "embeddingModel");

        assertThat(processed).isInstanceOf(MeteredEmbeddingModel.class);
        verify(usageServiceProvider).getObject();
    }
}
