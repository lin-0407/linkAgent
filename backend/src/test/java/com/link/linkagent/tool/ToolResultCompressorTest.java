package com.link.linkagent.tool;

import com.link.linkagent.llm.LLMService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ToolResultCompressor 的回归测试。
 *
 * <h3>这个类在防什么</h3>
 * 工具结果原文是单条能顶穿上下文窗口的东西（实测单轮最大 24 万 token，设计文档 2.7），
 * 所以「进上下文的那一份」必须限体积；同时原文要完整留给 t_agent_step。
 * 用例覆盖三条边界：什么情况不压（短结果、自己压过的工具）、压完长什么样（截断上限、摘要前缀）、
 * 压不成怎么办（返回空、抛异常、开关关闭都必须原样返回，不能让工具结果凭空消失）。
 */
class ToolResultCompressorTest {

    private static final String MODEL = "deepseek-v4-flash";

    /** 与实现里的 COMPRESSION_THRESHOLD 一致：刚好等于阈值的文本不压缩。 */
    private static final int COMPRESSION_THRESHOLD = 2000;

    /** 与实现里的 COMPRESSION_INPUT_MAX_LENGTH 一致。 */
    private static final int COMPRESSION_INPUT_MAX_LENGTH = 24000;

    /** 与实现里的 COMPRESSION_OUTPUT_MAX_LENGTH 一致。 */
    private static final int COMPRESSION_OUTPUT_MAX_LENGTH = 6000;

    private final LLMService llmService = mock(LLMService.class);
    private final ToolResultCompressor compressor = new ToolResultCompressor(true, MODEL, llmService);

    @Test
    void shouldReturnShortResultWithoutCallingModel() {
        String result = "a".repeat(COMPRESSION_THRESHOLD);

        assertThat(compressor.compressIfNeeded("calculator", result)).isEqualTo(result);
        verifyNoInteractions(llmService);
    }

    @Test
    void shouldReturnNullResultAsIs() {
        assertThat(compressor.compressIfNeeded("calculator", null)).isNull();
        verifyNoInteractions(llmService);
    }

    @Test
    void shouldSkipWebSearchBecauseItCompressesItsOwnResult() {
        // web_search 自己已经把长网页压到 6000 字符以内，再压一次只会多花一次调用并丢细节
        String result = "b".repeat(COMPRESSION_THRESHOLD + 1);

        assertThat(compressor.compressIfNeeded("web_search", result)).isEqualTo(result);
        verifyNoInteractions(llmService);
    }

    @Test
    void shouldCompressLongResultAndMarkItAsSummary() {
        String result = "c".repeat(COMPRESSION_THRESHOLD + 1);
        when(llmService.chatWithModel(eq(MODEL), anyString(), anyString())).thenReturn("压缩后的正文");

        String compressed = compressor.compressIfNeeded("web_fetch", result);

        // 带前缀是为了让模型和人都知道这是摘要、原文在步骤记录里可回查
        assertThat(compressed).startsWith("[工具结果摘要：");
        assertThat(compressed).endsWith("压缩后的正文");
        verify(llmService).chatWithModel(eq(MODEL), anyString(), anyString());
    }

    @Test
    void shouldTruncateCompressorInputBeforeCallingModel() {
        String result = "d".repeat(30000);
        when(llmService.chatWithModel(eq(MODEL), anyString(), anyString())).thenReturn("ok");

        compressor.compressIfNeeded("calculator", result);

        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmService).chatWithModel(eq(MODEL), anyString(), inputCaptor.capture());
        // 压缩调用本身也不能超长，否则压缩器自己就会顶穿窗口
        assertThat(inputCaptor.getValue()).isEqualTo("d".repeat(COMPRESSION_INPUT_MAX_LENGTH));
    }

    @Test
    void shouldCapCompressedOutputAtSixThousandChars() {
        String result = "e".repeat(COMPRESSION_THRESHOLD + 1);
        when(llmService.chatWithModel(eq(MODEL), anyString(), anyString())).thenReturn("f".repeat(7000));

        String compressed = compressor.compressIfNeeded("calculator", result);

        assertThat(compressed).endsWith("f".repeat(COMPRESSION_OUTPUT_MAX_LENGTH));
        assertThat(compressed).doesNotContain("f".repeat(COMPRESSION_OUTPUT_MAX_LENGTH + 1));
    }

    @Test
    void shouldKeepOriginalWhenModelReturnsBlank() {
        String result = "g".repeat(COMPRESSION_THRESHOLD + 1);
        when(llmService.chatWithModel(eq(MODEL), anyString(), anyString())).thenReturn("   ");

        // 压出空内容就等于把工具结果弄丢了，这种情况必须原样返回
        assertThat(compressor.compressIfNeeded("calculator", result)).isEqualTo(result);
    }

    @Test
    void shouldKeepOriginalWhenModelCallFails() {
        String result = "h".repeat(COMPRESSION_THRESHOLD + 1);
        when(llmService.chatWithModel(eq(MODEL), anyString(), anyString()))
                .thenThrow(new IllegalStateException("模型不可用"));

        // 压缩失败时模型看到全文，比看不到强
        assertThat(compressor.compressIfNeeded("calculator", result)).isEqualTo(result);
    }

    @Test
    void shouldReturnOriginalWhenCompressionDisabled() {
        // 测试用构造器等价于关闭压缩（enabled=false、无模型）：用于单测注入与降级场景
        ToolResultCompressor disabled = new ToolResultCompressor();
        String result = "i".repeat(COMPRESSION_THRESHOLD + 1);

        assertThat(disabled.compressIfNeeded("calculator", result)).isEqualTo(result);
    }
}
