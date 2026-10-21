package com.link.linkagent.creator.creation.storyboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.llm.usage.LlmApiCallRecord;
import com.link.linkagent.llm.usage.LlmApiUsageMapper;
import com.link.linkagent.llm.usage.LlmApiUsageService;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 使用本机 HTTP 假供应商核对真实请求次数和参数，不产生任何外部调用。 */
class StoryboardTextGeneratorTest {

    private static final String TEST_KEY = "local-storyboard-test-key-never-real";

    @Test
    void sendsOneBoundedRequestWithDisabledThinkingAndPreservesRawUsageAndTaskContext() throws Exception {
        try (Endpoint endpoint = new Endpoint(200, success("原样保留的非 JSON 文本", true))) {
            LlmApiUsageMapper mapper = mock(LlmApiUsageMapper.class);
            OpenAiChatOptions original = OpenAiChatOptions.builder().model("deepseek-v4-flash")
                    .maxCompletionTokens(12000).N(4)
                    .reasoningEffort("max").extraBody(Map.of("thinking", Map.of("type", "enabled")))
                    .build();
            OpenAiChatModel source = endpoint.model(original);
            StoryboardTextGenerator generator = endpoint.generator(source, mapper);
            StoryboardTextGenerator.Raw raw;
            try (var ignored = LlmUsageContext.open("task-storyboard", "脚本与故事板生成")) {
                raw = generator.generate("系统约束", "用户简报", 512);
            }
            assertThat(raw.content()).isEqualTo("原样保留的非 JSON 文本");
            assertThat(raw.inputTokens()).isEqualTo(10);
            assertThat(raw.outputTokens()).isEqualTo(3);
            assertThat(raw.modelName()).isEqualTo("provider-model-alias");
            assertThat(generator.modelName()).isEqualTo("deepseek-v4-flash");
            assertThat(endpoint.requests).hasSize(1);
            assertThat(endpoint.headers).singleElement().satisfies(headers -> {
                assertThat(headers.path("api").asText()).isEqualTo("retained-api-header");
                assertThat(headers.path("builder").asText()).isEqualTo("retained-builder-header");
                assertThat(headers.path("authorized").asBoolean()).isTrue();
            });
            JsonNode request = endpoint.requests.getFirst();
            assertThat(request.path("model").asText()).isEqualTo("deepseek-v4-flash");
            assertThat(request.path("max_tokens").asInt()).isEqualTo(512);
            assertThat(request.path("n").asInt()).isEqualTo(1);
            assertThat(request.path("thinking").path("type").asText()).isEqualTo("disabled");
            assertThat(request.has("reasoning_effort")).isFalse();
            assertThat(request.has("max_completion_tokens")).isFalse();
            assertThat(request.has("tools")).isFalse();
            assertThat(request.has("response_format")).isFalse();
            assertThat(request.path("messages").get(0).path("content").asText()).isEqualTo("系统约束");
            assertThat(request.path("messages").get(1).path("content").asText()).isEqualTo("用户简报");
            assertThat(request.toString()).doesNotContain(TEST_KEY);
            assertThat(original.getN()).isEqualTo(4);
            assertThat(original.getMaxCompletionTokens()).isEqualTo(12000);
            assertThat(original.getReasoningEffort()).isEqualTo("max");
            ArgumentCaptor<LlmApiCallRecord> call = ArgumentCaptor.forClass(LlmApiCallRecord.class);
            verify(mapper).insert(call.capture());
            assertThat(call.getValue().getTaskId()).isEqualTo("task-storyboard");
            assertThat(call.getValue().getScene()).isEqualTo("脚本与故事板生成");
            assertThat(call.getValue().getPromptTokens()).isEqualTo(10);
            assertThat(call.getValue().getCompletionTokens()).isEqualTo(3);
            assertThat(call.getValue().getCachedPromptTokens()).isEqualTo(2);
            assertThat(call.getValue().getStatus()).isEqualTo("SUCCESS");
            assertThat(LlmUsageContext.current()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void doesNotRetryProviderFailuresOrPersistProviderBody(int status) throws Exception {
        try (Endpoint endpoint = new Endpoint(status, "{\"error\":{\"message\":\"private-provider-response\"}}")) {
            LlmApiUsageMapper mapper = mock(LlmApiUsageMapper.class);
            StoryboardTextGenerator generator = endpoint.generator(
                    endpoint.model(OpenAiChatOptions.builder().model("deployed-compatible-model").build()), mapper);
            assertThatThrownBy(() -> generator.generate("系统", "简报", 256))
                    .isInstanceOf(IllegalStateException.class).hasMessage("故事板文本调用失败，未自动重试")
                    .hasNoCause().hasMessageNotContaining(TEST_KEY).hasMessageNotContaining("private-provider-response");
            assertThat(endpoint.requests).hasSize(1);
            ArgumentCaptor<LlmApiCallRecord> call = ArgumentCaptor.forClass(LlmApiCallRecord.class);
            verify(mapper).insert(call.capture());
            assertThat(call.getValue().getErrorMessage()).isEqualTo("故事板文本调用失败，未自动重试");
            assertThat(call.getValue().getStatus()).isEqualTo("FAILED");
            assertThat(call.getValue().getPromptTokens()).isNull();
        }
    }

    @Test
    void compatibleModelReceivesNoDeepSeekFieldAndMissingUsageRemainsUnknown() throws Exception {
        try (Endpoint endpoint = new Endpoint(200, success("{}", false))) {
            LlmApiUsageMapper mapper = mock(LlmApiUsageMapper.class);
            StoryboardTextGenerator generator = endpoint.generator(
                    endpoint.model(OpenAiChatOptions.builder().model("my-existing-model").build()), mapper);
            var raw = generator.generate("系统", "简报", 320);
            assertThat(raw.inputTokens()).isNull();
            assertThat(raw.outputTokens()).isNull();
            assertThat(endpoint.requests).hasSize(1);
            assertThat(endpoint.requests.getFirst().has("thinking")).isFalse();
            assertThat(endpoint.requests.getFirst().path("model").asText()).isEqualTo("my-existing-model");
        }
    }

    @Test
    void partialUsageDoesNotTurnUnreportedOutputTokensIntoZero() throws Exception {
        String response = success("{}", false).stripTrailing();
        response = response.substring(0, response.length() - 1) + ",\"usage\":{\"prompt_tokens\":10,\"total_tokens\":10}}";
        try (Endpoint endpoint = new Endpoint(200, response)) {
            LlmApiUsageMapper mapper = mock(LlmApiUsageMapper.class);
            StoryboardTextGenerator generator = endpoint.generator(
                    endpoint.model(OpenAiChatOptions.builder().model("existing-model").build()), mapper);
            var raw = generator.generate("系统", "简报", 320);
            assertThat(raw.inputTokens()).isEqualTo(10);
            assertThat(raw.outputTokens()).isNull();
            assertThat(endpoint.requests).hasSize(1);
        }
    }

    @Test
    void emptyInputsAndInvalidUpperBoundsNeverCallProvider() throws Exception {
        try (Endpoint endpoint = new Endpoint(200, success("{}", true))) {
            LlmApiUsageMapper mapper = mock(LlmApiUsageMapper.class);
            StoryboardTextGenerator generator = endpoint.generator(
                    endpoint.model(OpenAiChatOptions.builder().model("model").build()), mapper);
            assertThatIllegalArgumentException().isThrownBy(() -> generator.generate("", "input", 1));
            assertThatIllegalArgumentException().isThrownBy(() -> generator.generate("system", null, 1));
            assertThatIllegalArgumentException().isThrownBy(() -> generator.generate("system", "input", 0));
            assertThat(endpoint.requests).isEmpty();
            verifyNoInteractions(mapper);
        }
    }

    @Test
    void anUnexpectedToolResponseCannotTriggerASecondPhysicalCall() throws Exception {
        String toolResponse = """
                {"id":"local","object":"chat.completion","created":1,"model":"model",
                "choices":[{"index":0,"message":{"role":"assistant","content":null,
                "tool_calls":[{"id":"tool-1","type":"function","function":{"name":"some_tool","arguments":"{}"}}]},
                "finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}
                """;
        try (Endpoint endpoint = new Endpoint(200, toolResponse)) {
            LlmApiUsageMapper mapper = mock(LlmApiUsageMapper.class);
            OpenAiChatModel source = endpoint.model(OpenAiChatOptions.builder().model("model").build()).mutate()
                    .toolExecutionEligibilityPredicate((options, response) -> true).build();
            StoryboardTextGenerator generator = endpoint.generator(source, mapper);
            var raw = generator.generate("系统", "简报", 100);
            assertThat(raw.inputTokens()).isEqualTo(10);
            assertThat(raw.content()).isNullOrEmpty();
            assertThat(endpoint.requests).hasSize(1);
            verify(mapper, times(1)).insert(org.mockito.ArgumentMatchers.any());
        }
    }

    private static String success(String content, boolean withUsage) {
        return """
                {"id":"local","object":"chat.completion","created":1,"model":"provider-model-alias",
                "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}]%s}
                """.formatted(content, withUsage
                ? ",\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3,\"total_tokens\":13,\"prompt_tokens_details\":{\"cached_tokens\":2}}" : "");
    }

    private static final class Endpoint implements AutoCloseable {
        private final HttpServer server;
        private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        private final List<JsonNode> headers = new CopyOnWriteArrayList<>();
        private OpenAiApi api;

        private Endpoint(int status, String body) throws Exception {
            ObjectMapper json = new ObjectMapper();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/deployed/custom-chat", exchange -> {
                requests.add(json.readTree(exchange.getRequestBody()));
                var receivedHeaders = json.createObjectNode();
                receivedHeaders.put("api", exchange.getRequestHeaders().getFirst("X-Deployed-Api"));
                receivedHeaders.put("builder", exchange.getRequestHeaders().getFirst("X-Deployed-Builder"));
                receivedHeaders.put("authorized", ("Bearer " + TEST_KEY).equals(exchange.getRequestHeaders().getFirst("Authorization")));
                headers.add(receivedHeaders);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        private OpenAiChatModel model(OpenAiChatOptions options) {
            var headers = new LinkedMultiValueMap<String, String>();
            headers.add("X-Deployed-Api", "retained-api-header");
            api = OpenAiApi.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .completionsPath("/deployed/custom-chat").headers(headers).apiKey(TEST_KEY).build();
            return OpenAiChatModel.builder().openAiApi(api).defaultOptions(options)
                    .retryTemplate(RetryTemplate.builder().maxAttempts(3).noBackoff().build()).build();
        }

        private StoryboardTextGenerator generator(OpenAiChatModel source, LlmApiUsageMapper mapper) {
            return new StoryboardTextGenerator(source, api,
                    RestClient.builder().defaultHeader("X-Deployed-Builder", "retained-builder-header"),
                    new LlmApiUsageService(mapper));
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
