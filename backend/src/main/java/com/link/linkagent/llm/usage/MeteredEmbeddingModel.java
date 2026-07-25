package com.link.linkagent.llm.usage;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 带开销统计的 EmbeddingModel 代理。
 * 代理真实 EmbeddingModel，是为了覆盖 VectorStore 内部自动触发的向量化调用，而不是要求每个业务服务手动打点。
 */
public class MeteredEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final LlmApiUsageService llmApiUsageService;

    public MeteredEmbeddingModel(EmbeddingModel delegate, LlmApiUsageService llmApiUsageService) {
        this.delegate = delegate;
        this.llmApiUsageService = llmApiUsageService;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        int inputCount = request == null || request.getInstructions() == null ? 0 : request.getInstructions().size();
        long startNanos = System.nanoTime();
        String requestModel = extractModelName(request == null ? null : request.getOptions());
        try {
            EmbeddingResponse response = delegate.call(request);
            long elapsedMs = elapsedMs(startNanos);
            recordSuccess(response, requestModel, elapsedMs, inputCount);
            return response;
        } catch (RuntimeException exception) {
            llmApiUsageService.recordEmbeddingFailure(requestModel, elapsedMs(startNanos), exception, inputCount);
            throw exception;
        }
    }

    @Override
    public float[] embed(Document document) {
        long startNanos = System.nanoTime();
        try {
            float[] result = delegate.embed(document);
            llmApiUsageService.recordEmbeddingSuccess(null, null, null, elapsedMs(startNanos), 1);
            return result;
        } catch (RuntimeException exception) {
            llmApiUsageService.recordEmbeddingFailure(null, elapsedMs(startNanos), exception, 1);
            throw exception;
        }
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        EmbeddingResponse response = embedForResponse(texts);
        return response.getResults().stream()
                .map(embedding -> embedding.getOutput())
                .toList();
    }

    @Override
    public EmbeddingResponse embedForResponse(List<String> texts) {
        int inputCount = texts == null ? 0 : texts.size();
        long startNanos = System.nanoTime();
        try {
            EmbeddingResponse response = delegate.embedForResponse(texts);
            recordSuccess(response, null, elapsedMs(startNanos), inputCount);
            return response;
        } catch (RuntimeException exception) {
            llmApiUsageService.recordEmbeddingFailure(null, elapsedMs(startNanos), exception, inputCount);
            throw exception;
        }
    }

    @Override
    public List<float[]> embed(List<Document> documents, EmbeddingOptions options, BatchingStrategy batchingStrategy) {
        int inputCount = documents == null ? 0 : documents.size();
        long startNanos = System.nanoTime();
        String requestModel = extractModelName(options);
        try {
            List<float[]> result = delegate.embed(documents, options, batchingStrategy);
            llmApiUsageService.recordEmbeddingSuccess(requestModel, null, null, elapsedMs(startNanos), inputCount);
            return result;
        } catch (RuntimeException exception) {
            llmApiUsageService.recordEmbeddingFailure(requestModel, elapsedMs(startNanos), exception, inputCount);
            throw exception;
        }
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    @Override
    public String getEmbeddingContent(Document document) {
        return delegate.getEmbeddingContent(document);
    }

    private void recordSuccess(EmbeddingResponse response, String requestModel, long elapsedMs, int inputCount) {
        EmbeddingResponseMetadata metadata = response == null ? null : response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        llmApiUsageService.recordEmbeddingSuccess(
                firstNonBlank(metadata == null ? null : metadata.getModel(), requestModel),
                extractPromptTokens(usage),
                extractTotalTokens(usage),
                elapsedMs,
                inputCount
        );
    }

    private Integer extractPromptTokens(Usage usage) {
        return isMissingUsage(usage) ? null : usage.getPromptTokens();
    }

    private Integer extractTotalTokens(Usage usage) {
        return isMissingUsage(usage) ? null : usage.getTotalTokens();
    }

    private boolean isMissingUsage(Usage usage) {
        if (usage == null) {
            return true;
        }
        return isZeroOrNull(usage.getPromptTokens())
                && isZeroOrNull(usage.getCompletionTokens())
                && isZeroOrNull(usage.getTotalTokens());
    }

    private boolean isZeroOrNull(Integer value) {
        return value == null || value == 0;
    }

    private String extractModelName(EmbeddingOptions options) {
        if (options == null || options.getModel() == null || options.getModel().isBlank()) {
            return null;
        }
        return options.getModel().trim();
    }

    private String firstNonBlank(String left, String right) {
        if (left != null && !left.isBlank()) {
            return left.trim();
        }
        if (right != null && !right.isBlank()) {
            return right.trim();
        }
        return null;
    }

    private long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
