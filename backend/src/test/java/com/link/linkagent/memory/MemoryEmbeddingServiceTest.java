package com.link.linkagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 向量化服务的边界测试：没有模型、批量上限、空白文本、模型报错、JSON 往返、余弦的退化输入。
 * 超时分支由 {@link MemoryCallTimeoutTest} 覆盖（本类只验证分批与降级）。
 */
class MemoryEmbeddingServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldReportUnavailableAndReturnNullsWhenModelMissing() {
        MemoryEmbeddingService service = MemoryEmbeddingService.disabled();

        assertThat(service.available()).isFalse();
        assertThat(service.embedOne("任意文本")).isNull();
        assertThat(service.embedAll(List.of("a", "b"))).hasSize(2).containsOnlyNulls();
    }

    @Test
    void shouldSplitBatchesAtConfiguredSize() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        // 批次大小 2、5 条文本 → 3 次调用；Qwen 兼容模式单批上限 10，超了整批 400
        MemoryEmbeddingService service = new MemoryEmbeddingService(model, OBJECT_MAPPER, 5, 2);

        List<float[]> vectors = service.embedAll(List.of("a", "bb", "ccc", "dddd", "eeeee"));

        assertThat(vectors).hasSize(5);
        assertThat(model.batchSizes).containsExactly(2, 2, 1);
    }

    @Test
    void shouldSkipBlankTextsWithoutCallingModel() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        MemoryEmbeddingService service = new MemoryEmbeddingService(model, OBJECT_MAPPER, 5, 10);

        List<float[]> vectors = service.embedAll(java.util.Arrays.asList("a", "  ", null, "b"));

        // 空串混进批次会让整批报错：空白条目直接跳过并返回 null，有效条目照常向量化
        assertThat(vectors).hasSize(4);
        assertThat(vectors.get(1)).isNull();
        assertThat(vectors.get(2)).isNull();
        assertThat(model.batchSizes).containsExactly(2);
    }

    @Test
    void shouldDegradeToNullsWhenModelThrows() {
        EmbeddingModel failing = new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                throw new IllegalStateException("模型不可用");
            }

            @Override
            public float[] embed(Document document) {
                throw new IllegalStateException("模型不可用");
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                throw new IllegalStateException("模型不可用");
            }
        };
        MemoryEmbeddingService service = new MemoryEmbeddingService(failing, OBJECT_MAPPER, 5, 10);

        // 向量化失败只丢相关性维度：不抛异常，让检索照常返回记忆
        assertThat(service.embedAll(List.of("a", "b"))).hasSize(2).containsOnlyNulls();
    }

    @Test
    void shouldRoundTripVectorThroughJson() {
        MemoryEmbeddingService service = MemoryEmbeddingService.disabled();
        float[] vector = new float[]{0.125f, -0.5f, 1.0f};

        String json = service.toJson(vector);
        float[] parsed = service.parse(json);

        assertThat(parsed).containsExactly(vector);
        // 空向量存 NULL 而不是字符串 "null"：读取侧用同一个判断识别「没有向量」
        assertThat(service.toJson(null)).isNull();
        assertThat(service.toJson(new float[0])).isNull();
        assertThat(service.parse(null)).isNull();
        assertThat(service.parse("不是 JSON")).isNull();
    }

    @Test
    void shouldComputeCosineAndReturnZeroForIncomparableVectors() {
        assertThat(MemoryEmbeddingService.cosine(new float[]{1, 0}, new float[]{1, 0})).isCloseTo(1.0, within(1e-9));
        assertThat(MemoryEmbeddingService.cosine(new float[]{1, 0}, new float[]{0, 1})).isCloseTo(0.0, within(1e-9));
        assertThat(MemoryEmbeddingService.cosine(new float[]{1, 0}, new float[]{-1, 0})).isCloseTo(-1.0, within(1e-9));
        // 缺失、长度不一致、零向量都返回 0：无法比较时按「不相关」处理
        assertThat(MemoryEmbeddingService.cosine(null, new float[]{1, 0})).isZero();
        assertThat(MemoryEmbeddingService.cosine(new float[]{1, 0}, new float[]{1, 0, 0})).isZero();
        assertThat(MemoryEmbeddingService.cosine(new float[]{0, 0}, new float[]{1, 0})).isZero();
    }

    /** 记录每次调用的批次大小，并向量的第一维设成文本长度，便于断言「哪个文本被算过」。 */
    private static class RecordingEmbeddingModel implements EmbeddingModel {

        private final List<Integer> batchSizes = new ArrayList<>();

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException("单测只走 embed(List<String>) 路径");
        }

        @Override
        public float[] embed(Document document) {
            throw new UnsupportedOperationException("单测只走 embed(List<String>) 路径");
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            batchSizes.add(texts.size());
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (String text : texts) {
                vectors.add(new float[]{text.length(), 1.0f});
            }
            return vectors;
        }

        @Override
        public int dimensions() {
            return 2;
        }
    }
}
