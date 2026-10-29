package com.link.linkagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmProviderManagerUsageTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void preservesUsageAndFinishReasonReturnedByHttpProvider() throws Exception {
        LlmCallResult result = fallback("""
                {"model":"actual-model","choices":[{"message":{"content":"OK"},"finish_reason":"length"}],
                 "usage":{"prompt_tokens":40,"completion_tokens":120,"total_tokens":160,
                          "prompt_tokens_details":{"cached_tokens":12}}}
                """);

        assertThat(result.content()).isEqualTo("OK");
        assertThat(result.modelName()).isEqualTo("actual-model");
        assertThat(result.promptTokens()).isEqualTo(40);
        assertThat(result.completionTokens()).isEqualTo(120);
        assertThat(result.totalTokens()).isEqualTo(160);
        assertThat(result.cachedPromptTokens()).isEqualTo(12);
        assertThat(result.truncatedByOutputLimit()).isTrue();
    }

    @Test
    void missingUsageRemainsUnknown() throws Exception {
        LlmCallResult result = fallback("""
                {"choices":[{"message":{"content":"OK"}}]}
                """);
        assertThat(result.promptTokens()).isNull();
        assertThat(result.completionTokens()).isNull();
        assertThat(result.totalTokens()).isNull();
        assertThat(result.cachedPromptTokens()).isNull();
        assertThat(result.modelName()).isEqualTo("configured-model");
    }

    @Test
    void invalidUsageDoesNotBecomeZeroOrOverflow() throws Exception {
        LlmCallResult result = fallback("""
                {"choices":[{"message":{"content":"OK"}}],
                 "usage":{"prompt_tokens":-1,"completion_tokens":3.5,"total_tokens":99999999999,
                          "prompt_cache_hit_tokens":0}}
                """);
        assertThat(result.promptTokens()).isNull();
        assertThat(result.completionTokens()).isNull();
        assertThat(result.totalTokens()).isNull();
        assertThat(result.cachedPromptTokens()).isZero();
    }

    private LlmCallResult fallback(String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        LlmFallbackProperties properties = new LlmFallbackProperties();
        LlmFallbackProperties.ProviderConfig provider = new LlmFallbackProperties.ProviderConfig();
        provider.setName("test-provider");
        provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        provider.setApiKey("test-only");
        provider.setModel("configured-model");
        properties.setEnabled(true);
        properties.setProviders(List.of(provider));
        return new LlmProviderManager(properties, new ObjectMapper()).tryFallback("测试", "你好");
    }
}
