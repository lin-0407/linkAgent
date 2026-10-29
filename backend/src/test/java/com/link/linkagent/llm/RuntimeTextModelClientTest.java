package com.link.linkagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.core.multi.MultiAgentExecutionContext;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardTextGenerator;
import com.link.linkagent.llm.config.mapper.UserLlmConfigMapper;
import com.link.linkagent.llm.config.model.UserLlmConfigRecord;
import com.link.linkagent.llm.config.model.UserLlmConfigSaveRequest;
import com.link.linkagent.llm.config.service.UserLlmConfigService;
import com.link.linkagent.llm.usage.LlmApiCallRecord;
import com.link.linkagent.llm.usage.LlmApiUsageMapper;
import com.link.linkagent.llm.usage.LlmApiUsageService;
import com.link.linkagent.settings.mapper.RuntimeSettingMapper;
import com.link.linkagent.settings.model.RuntimeSettingRecord;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 只模拟持久层，用随机本机端口核对实际发出的模型、密钥、端点和返回用量，不访问外部供应商。 */
class RuntimeTextModelClientTest {
    private static final String ENVIRONMENT_MODEL = "deepseek-v4-flash-environment";
    private static final String ENVIRONMENT_KEY = "local-environment-key-never-real";
    private static final String SAVED_KEY = "local-saved-key-never-real";
    private static final String EDITED_KEY = "local-edited-key-never-real";
    private static final String AES_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String CONTENT = "{\"answer\":\"本机响应\"}";

    @Test
    void explicitActivationAndEveryConfigEditAffectNextChatAndStoryboardCallAndCanRestoreEnvironment() throws Exception {
        try (Endpoint environment = new Endpoint(200, 11);
             Endpoint selected = new Endpoint(200, 21);
             Endpoint moved = new Endpoint(200, 31)) {
            Fixture fixture = new Fixture(environment);
            String id = fixture.save("OPENAI", selected, SAVED_KEY, "compatible-v1");

            assertThat(fixture.runtime.current().source()).isEqualTo("ENVIRONMENT");
            fixture.assertChatAndStoryboard(ENVIRONMENT_MODEL, 11);
            assertThat(selected.requests).isEmpty();
            assertPair(environment.requests, 0, "/deployment/chat", ENVIRONMENT_MODEL, ENVIRONMENT_KEY);

            fixture.configService.activateTextConfig("default", id);
            assertThat(fixture.runtime.current().configId()).isEqualTo(id);
            fixture.assertChatAndStoryboard("compatible-v1", 21);
            assertPair(selected.requests, 0, "/v1/chat/completions", "compatible-v1", SAVED_KEY);
            assertThat(selected.requests.getFirst().body().has("thinking")).isFalse();
            assertThat(selected.requests.getFirst().body().has("reasoning_effort")).isFalse();

            // 分开修改密钥、模型和地址，避免其它字段变化掩盖某个缓存失效条件遗漏。
            assertThat(fixture.save("OPENAI", selected, EDITED_KEY, "compatible-v1")).isEqualTo(id);
            fixture.assertChatAndStoryboard("compatible-v1", 21);
            assertPair(selected.requests, 2, "/v1/chat/completions", "compatible-v1", EDITED_KEY);
            fixture.save("OPENAI", selected, null, "compatible-v2");
            fixture.assertChatAndStoryboard("compatible-v2", 21);
            assertPair(selected.requests, 4, "/v1/chat/completions", "compatible-v2", EDITED_KEY);
            fixture.save("OPENAI", moved, null, "compatible-v2");
            fixture.assertChatAndStoryboard("compatible-v2", 31);
            assertPair(moved.requests, 0, "/v1/chat/completions", "compatible-v2", EDITED_KEY);

            fixture.configService.useEnvironment("default");
            assertThat(fixture.runtime.current().source()).isEqualTo("ENVIRONMENT");
            fixture.assertChatAndStoryboard(ENVIRONMENT_MODEL, 11);
            assertPair(environment.requests, 2, "/deployment/chat", ENVIRONMENT_MODEL, ENVIRONMENT_KEY);
            assertThat(environment.requests).hasSize(4);
            assertThat(selected.requests).hasSize(6);
            assertThat(moved.requests).hasSize(2);

            ArgumentCaptor<LlmApiCallRecord> usage = ArgumentCaptor.forClass(LlmApiCallRecord.class);
            verify(fixture.usageMapper, times(12)).insert(usage.capture());
            assertThat(usage.getAllValues()).extracting(LlmApiCallRecord::getModelName)
                    .containsExactly(ENVIRONMENT_MODEL, ENVIRONMENT_MODEL,
                            "compatible-v1", "compatible-v1", "compatible-v1", "compatible-v1",
                            "compatible-v2", "compatible-v2", "compatible-v2", "compatible-v2",
                            ENVIRONMENT_MODEL, ENVIRONMENT_MODEL);
            assertThat(usage.getAllValues()).extracting(LlmApiCallRecord::getPromptTokens)
                    .containsExactly(11, 11, 21, 21, 21, 21, 21, 21, 31, 31, 11, 11);
            assertThat(usage.getAllValues()).allSatisfy(record -> {
                assertThat(record.getStatus()).isEqualTo("SUCCESS");
                assertThat(record.getCompletionTokens()).isEqualTo(3);
                assertThat(record.getCachedPromptTokens()).isEqualTo(2);
            });
        }
    }

    @Test
    void strictUsesSelectedDeepSeekBetaWhileCompatibleModelsUseOrdinaryStructuredEndpoint() throws Exception {
        try (Endpoint environment = new Endpoint(200, 11);
             Endpoint selected = new Endpoint(200, 21)) {
            Fixture fixture = new Fixture(environment);
            String deepSeek = fixture.save("DEEPSEEK", selected, SAVED_KEY, "deepseek-chat-selected");
            fixture.configService.activateTextConfig("default", deepSeek);
            assertThat(fixture.strict.isEnabled()).isTrue();
            var strictResult = fixture.llm.chatStructuredWithUsage("系统", "返回 JSON", Answer.class);
            assertThat(strictResult.entity().answer()).isEqualTo("本机响应");
            assertThat(strictResult.promptTokens()).isEqualTo(21);
            assertThat(selected.requests).hasSize(1);
            assertRequest(selected.requests.getFirst(), "/beta/chat/completions", "deepseek-chat-selected", SAVED_KEY);
            assertThat(selected.requests.getFirst().body().path("tools").get(0)
                    .path("function").path("strict").asBoolean()).isTrue();

            String compatible = fixture.save("OPENAI", selected, EDITED_KEY, "compatible-model");
            fixture.configService.activateTextConfig("default", compatible);
            assertThat(fixture.strict.isEnabled()).isFalse();
            assertThat(fixture.llm.chatStructured("系统", "返回 JSON", Answer.class).answer()).isEqualTo("本机响应");
            assertThat(selected.requests).hasSize(2);
            Request ordinary = selected.requests.get(1);
            assertRequest(ordinary, "/v1/chat/completions", "compatible-model", EDITED_KEY);
            assertThat(ordinary.body().path("response_format").path("type").asText()).isEqualTo("json_object");
            assertThat(ordinary.body().has("tools")).isFalse();
            assertThat(ordinary.body().has("thinking")).isFalse();
            assertThat(environment.requests).isEmpty();

            fixture.configService.useEnvironment("default");
            assertThat(fixture.strict.isEnabled()).isTrue();
            assertThat(fixture.llm.chatStructured("系统", "返回 JSON", Answer.class).answer()).isEqualTo("本机响应");
            assertThat(environment.requests).hasSize(1);
            assertRequest(environment.requests.getFirst(), "/beta/chat/completions", ENVIRONMENT_MODEL, ENVIRONMENT_KEY);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void selectedStoryboardAndMultiAgentClientsNeverRetryProviderFailure(int status) throws Exception {
        try (Endpoint environment = new Endpoint(200, 11);
             Endpoint selected = new Endpoint(status, 21)) {
            Fixture fixture = new Fixture(environment);
            String id = fixture.save("DEEPSEEK", selected, SAVED_KEY, "deepseek-chat-selected");
            fixture.configService.activateTextConfig("default", id);
            assertThatThrownBy(() -> fixture.storyboard.generate("系统", "简报", 256))
                    .isInstanceOf(IllegalStateException.class).hasMessage("故事板文本调用失败，未自动重试");
            assertThat(selected.requests).hasSize(1);
            try (var ignored = MultiAgentExecutionContext.open(new AtomicBoolean(false))) {
                assertThatThrownBy(() -> fixture.llm.chatWithUsage("系统", "问题")).isInstanceOf(RuntimeException.class);
                assertThat(selected.requests).hasSize(2);
                assertThatThrownBy(() -> fixture.llm.chatStructured("系统", "返回 JSON", Answer.class))
                        .isInstanceOf(RuntimeException.class);
                assertThat(selected.requests).hasSize(3);
            }
            assertThat(MultiAgentExecutionContext.active()).isFalse();
            assertThat(selected.requests).extracting(Request::path)
                    .containsExactly("/v1/chat/completions", "/v1/chat/completions", "/beta/chat/completions");
            assertThat(environment.requests).isEmpty();
            ArgumentCaptor<LlmApiCallRecord> usage = ArgumentCaptor.forClass(LlmApiCallRecord.class);
            verify(fixture.usageMapper, times(3)).insert(usage.capture());
            assertThat(usage.getAllValues()).allSatisfy(record -> {
                assertThat(record.getStatus()).isEqualTo("FAILED");
                assertThat(record.getModelName()).isEqualTo("deepseek-chat-selected");
            });
        }
    }

    @Test
    void lightweightModelOverrideRecordsThinkingForTheSelectedModel() throws Exception {
        try (Endpoint environment = new Endpoint(200, 11); Endpoint selected = new Endpoint(200, 21)) {
            Fixture fixture = new Fixture(environment);
            String id = fixture.save("OPENAI", selected, SAVED_KEY, "compatible-selected");
            fixture.configService.activateTextConfig("default", id);
            fixture.llm.chatWithModel("deepseek-v4-flash", "系统", "轻量分析");
            assertThat(selected.requests).hasSize(1);
            assertThat(selected.requests.getFirst().body().has("thinking")).isFalse();
            ArgumentCaptor<LlmApiCallRecord> usage = ArgumentCaptor.forClass(LlmApiCallRecord.class);
            verify(fixture.usageMapper).insert(usage.capture());
            assertThat(usage.getValue().getModelName()).isEqualTo("compatible-selected");
            assertThat(usage.getValue().getReasoningEffort()).isNull();
        }
    }

    @Test
    void streamingCallsAlsoFollowActivationAndEnvironmentRestore() throws Exception {
        try (Endpoint environment = new Endpoint(200, 11); Endpoint selected = new Endpoint(200, 21)) {
            Fixture fixture = new Fixture(environment);
            String id = fixture.save("OPENAI", selected, SAVED_KEY, "stream-selected");
            for (int stage=0; stage<3; stage++) {
                if (stage==1) fixture.configService.activateTextConfig("default", id);
                if (stage==2) fixture.configService.useEnvironment("default");
                StringBuilder output = new StringBuilder();
                var result=fixture.llm.streamWithToolsWithUsage("系统",
                        List.of(new org.springframework.ai.chat.messages.UserMessage("流式验证")), List.of(),
                        output::append, ignored -> {}, false);
                assertThat(output.toString()).isEqualTo(CONTENT);
                assertThat(result.content()).isEqualTo(CONTENT);
            }
            assertThat(environment.requests).hasSize(2);
            assertRequest(environment.requests.get(0), "/deployment/chat", ENVIRONMENT_MODEL, ENVIRONMENT_KEY);
            assertRequest(environment.requests.get(1), "/deployment/chat", ENVIRONMENT_MODEL, ENVIRONMENT_KEY);
            assertRequest(selected.requests.getFirst(), "/v1/chat/completions", "stream-selected", SAVED_KEY);
            assertThat(selected.requests.getFirst().body().path("stream").asBoolean()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///private/model", "http://user:password@localhost", "http://localhost?key=private",
            "http://localhost#private", "http:///missing-host", "not a URL"})
    void rejectsInvalidOrCredentialBearingEndpointBeforeActivationOrAnyProviderRequest(String baseUrl) throws Exception {
        try (Endpoint environment = new Endpoint(200, 11)) {
            Fixture fixture = new Fixture(environment);
            String id = fixture.configService.saveConfig("default", new UserLlmConfigSaveRequest(
                    "OPENAI", baseUrl, SAVED_KEY, "compatible-model", null, null, null)).configId();
            assertThatThrownBy(() -> fixture.configService.activateTextConfig("default", id))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
            assertThat(fixture.runtime.current().source()).isEqualTo("ENVIRONMENT");
            assertThat(environment.requests).isEmpty();
        }
    }

    @Test
    void deletingOldSelectionCannotClearAConcurrentNewSelectionAndDeletingCurrentRestoresEnvironment() throws Exception {
        try (Endpoint environment = new Endpoint(200, 11);
             Endpoint selected = new Endpoint(200, 21)) {
            Fixture fixture = new Fixture(environment);
            String oldId = fixture.save("OPENAI", selected, SAVED_KEY, "compatible-old");
            String newId = fixture.save("DEEPSEEK", selected, EDITED_KEY, "deepseek-chat-new");
            fixture.configService.activateTextConfig("default", oldId);
            // 将切换精确插在软删除和条件清除之间，稳定重现并发选择被旧删除覆盖的时序。
            fixture.afterSoftDelete.set(() -> fixture.runtime.activate("default", newId));
            fixture.configService.deleteConfig("default", oldId);
            assertThat(fixture.runtime.current().configId()).isEqualTo(newId);
            fixture.assertChatAndStoryboard("deepseek-chat-new", 21);
            assertPair(selected.requests, 0, "/v1/chat/completions", "deepseek-chat-new", EDITED_KEY);
            verify(fixture.settings).clearIfMatches(RuntimeTextModelClient.ACTIVE_CONFIG_KEY, oldId);

            fixture.configService.deleteConfig("default", newId);
            assertThat(fixture.runtime.current().source()).isEqualTo("ENVIRONMENT");
            fixture.assertChatAndStoryboard(ENVIRONMENT_MODEL, 11);
            assertPair(environment.requests, 0, "/deployment/chat", ENVIRONMENT_MODEL, ENVIRONMENT_KEY);
            verify(fixture.settings).clearIfMatches(RuntimeTextModelClient.ACTIVE_CONFIG_KEY, newId);
        }
    }

    @Test
    void fingerprintChangesForEveryBillableConfigurationEditWhileCapturedSnapshotKeepsItsOriginalProvider() throws Exception {
        try (Endpoint environment = new Endpoint(200, 11);
             Endpoint selected = new Endpoint(200, 21);
             Endpoint moved = new Endpoint(200, 31)) {
            Fixture fixture = new Fixture(environment);
            String environmentFingerprint = fixture.runtime.configurationFingerprint();
            String id = fixture.save("OPENAI", selected, SAVED_KEY, "compatible-v1");
            fixture.configService.activateTextConfig("default", id);
            var captured = fixture.runtime.snapshot(true, false, false);
            String originalFingerprint = captured.configurationFingerprint();
            assertThat(originalFingerprint).matches("[0-9a-f]{64}").isNotEqualTo(environmentFingerprint);
            assertThat(captured.environment()).isFalse();
            assertThat(fixture.storyboard.configurationFingerprint()).isEqualTo(originalFingerprint);

            fixture.save("OPENAI", selected, EDITED_KEY, "compatible-v1");
            String editedKeyFingerprint = fixture.runtime.configurationFingerprint();
            assertThat(editedKeyFingerprint).isNotEqualTo(originalFingerprint);
            fixture.save("OPENAI", selected, null, "compatible-v2");
            String editedModelFingerprint = fixture.runtime.configurationFingerprint();
            assertThat(editedModelFingerprint).isNotEqualTo(editedKeyFingerprint);
            fixture.save("OPENAI", moved, null, "compatible-v2");
            assertThat(fixture.runtime.configurationFingerprint()).isNotEqualTo(editedModelFingerprint);
            assertThat(environment.requests).isEmpty();
            assertThat(selected.requests).isEmpty();
            assertThat(moved.requests).isEmpty();

            fixture.configService.useEnvironment("default");
            assertThat(fixture.runtime.configurationFingerprint()).isEqualTo(environmentFingerprint);
            assertThat(fixture.runtime.snapshot(true, false, false).environment()).isTrue();
            // 已批准调用只使用当时捕获的客户端，后来编辑配置或切回部署配置都不能改变其收费供应商。
            var response = captured.model().call(new Prompt("本次调用保持批准时的供应商"));
            assertThat(response.getMetadata().getModel()).isEqualTo("compatible-v1");
            assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(21);
            assertThat(captured.configurationFingerprint()).isEqualTo(originalFingerprint);
            assertThat(selected.requests).hasSize(1);
            assertRequest(selected.requests.getFirst(), "/v1/chat/completions", "compatible-v1", SAVED_KEY);
            assertThat(environment.requests).isEmpty();
            assertThat(moved.requests).isEmpty();
        }
    }

    private static void assertPair(List<Request> requests, int first, String path, String model, String key) {
        assertRequest(requests.get(first), path, model, key);
        assertRequest(requests.get(first + 1), path, model, key);
        assertThat(requests.get(first + 1).body().path("max_tokens").asInt()).isEqualTo(256);
        assertThat(requests.get(first + 1).body().path("n").asInt()).isEqualTo(1);
        assertThat(requests.get(first + 1).body().has("tools")).isFalse();
    }

    private static void assertRequest(Request request, String path, String model, String key) {
        assertThat(request.path()).isEqualTo(path);
        assertThat(request.body().path("model").asText()).isEqualTo(model);
        assertThat(request.authorization()).isEqualTo("Bearer " + key);
        assertThat(request.body().toString()).doesNotContain(key);
    }

    public record Answer(String answer) { }

    private record Request(String path, String authorization, JsonNode body) { }

    private static final class Fixture {
        private final UserLlmConfigMapper configs = mock(UserLlmConfigMapper.class);
        private final RuntimeSettingMapper settings = mock(RuntimeSettingMapper.class);
        private final LlmApiUsageMapper usageMapper = mock(LlmApiUsageMapper.class);
        private final Map<String, UserLlmConfigRecord> saved = new ConcurrentHashMap<>();
        private final AtomicReference<RuntimeSettingRecord> selection = new AtomicReference<>();
        private final AtomicReference<Runnable> afterSoftDelete = new AtomicReference<>();
        private final RuntimeTextModelClient runtime;
        private final UserLlmConfigService configService;
        private final StrictFunctionCallingClient strict;
        private final LLMService llm;
        private final StoryboardTextGenerator storyboard;

        private Fixture(Endpoint environment) {
            when(configs.findByConfigId(anyString())).thenAnswer(call -> Optional.ofNullable(saved.get(call.getArgument(0))));
            when(configs.findByUserAndProvider(anyString(), anyString())).thenAnswer(call -> saved.values().stream()
                    .filter(record -> record.getUserId().equals(call.getArgument(0))
                            && record.getProvider().equals(call.getArgument(1))).findFirst());
            when(configs.upsert(any())).thenAnswer(call -> {
                UserLlmConfigRecord record = call.getArgument(0);
                // 保留数据库唯一键 upsert 的稳定配置 ID，空密钥也按现有 SQL 保留旧值。
                saved.values().stream().filter(old -> old.getUserId().equals(record.getUserId())
                        && old.getProvider().equals(record.getProvider())).findFirst().ifPresent(old -> {
                    record.setConfigId(old.getConfigId());
                    if (record.getLlmApiKeyEnc() == null) record.setLlmApiKeyEnc(old.getLlmApiKeyEnc());
                });
                saved.put(record.getConfigId(), record);
                return 1;
            });
            when(configs.softDelete(anyString(), anyString())).thenAnswer(call -> {
                UserLlmConfigRecord record = saved.get(call.getArgument(0));
                if (record == null || !record.getUserId().equals(call.getArgument(1))) return 0;
                saved.remove(record.getConfigId());
                Runnable concurrentSelection = afterSoftDelete.getAndSet(null);
                if (concurrentSelection != null) concurrentSelection.run();
                return 1;
            });
            when(settings.findByKey(RuntimeTextModelClient.ACTIVE_CONFIG_KEY))
                    .thenAnswer(call -> Optional.ofNullable(selection.get()));
            when(settings.upsert(eq(RuntimeTextModelClient.ACTIVE_CONFIG_KEY), anyString(), anyString()))
                    .thenAnswer(call -> {
                        RuntimeSettingRecord record = new RuntimeSettingRecord();
                        record.setSettingValue(call.getArgument(1));
                        selection.set(record);
                        return 1;
                    });
            when(settings.clearIfMatches(eq(RuntimeTextModelClient.ACTIVE_CONFIG_KEY), anyString()))
                    .thenAnswer(call -> {
                        RuntimeSettingRecord previous = selection.get();
                        if (previous == null || !previous.getSettingValue().equals(call.getArgument(1))) return 0;
                        RuntimeSettingRecord cleared = new RuntimeSettingRecord();
                        cleared.setSettingValue("");
                        return selection.compareAndSet(previous, cleared) ? 1 : 0;
                    });
            DeepSeekThinkingProperties thinkingProperties = new DeepSeekThinkingProperties();
            thinkingProperties.setDefaultModel(ENVIRONMENT_MODEL);
            DeepSeekThinkingOptionsFactory thinking = new DeepSeekThinkingOptionsFactory(thinkingProperties, null);
            RestClient.Builder rest = RestClient.builder().requestFactory(new JdkClientHttpRequestFactory());
            OpenAiApi api = OpenAiApi.builder().baseUrl(environment.baseUrl()).apiKey(ENVIRONMENT_KEY)
                    .completionsPath("/deployment/chat").restClientBuilder(rest.clone()).build();
            OpenAiChatModel model = OpenAiChatModel.builder().openAiApi(api)
                    .defaultOptions(thinking.optionsForModel(ENVIRONMENT_MODEL))
                    .retryTemplate(RetryTemplate.builder().maxAttempts(3).noBackoff().build()).build();
            runtime = new RuntimeTextModelClient(configs, settings, AES_KEY, environment.baseUrl(),
                    ENVIRONMENT_MODEL, model, api, rest, thinking, 5);
            configService = new UserLlmConfigService(configs, AES_KEY, environment.baseUrl(), ENVIRONMENT_MODEL, thinking);
            configService.configureRuntimeTextModel(runtime);
            StrictFunctionCallingProperties strictProperties = new StrictFunctionCallingProperties();
            strictProperties.setBaseUrl(environment.baseUrl());
            strictProperties.setApiKey(ENVIRONMENT_KEY);
            strictProperties.setModel(ENVIRONMENT_MODEL);
            strict = new StrictFunctionCallingClient(strictProperties, thinking);
            strict.configureRuntimeTextModel(runtime);
            LlmApiUsageService usage = new LlmApiUsageService(usageMapper);
            LlmCallGuardProperties guard = new LlmCallGuardProperties();
            guard.setEnabled(false);
            llm = new LLMService(ChatClient.builder(model), guard, null, null, usage,
                    new ObjectMapper(), null, thinking, strict);
            llm.configureRuntimeTextModel(runtime);
            storyboard = new StoryboardTextGenerator(model, api, rest, usage);
            storyboard.configureRuntimeTextModel(runtime);
        }

        private String save(String provider, Endpoint endpoint, String key, String model) {
            return configService.saveConfig("default", new UserLlmConfigSaveRequest(provider,
                    endpoint.baseUrl(), key, model, null, null, null)).configId();
        }

        private void assertChatAndStoryboard(String model, int promptTokens) {
            var chat = llm.chatWithUsage("系统", "问题");
            assertThat(chat.content()).isEqualTo(CONTENT);
            assertThat(chat.modelName()).isEqualTo(model);
            assertThat(chat.promptTokens()).isEqualTo(promptTokens);
            assertThat(chat.totalTokens()).isEqualTo(promptTokens + 3);
            assertThat(storyboard.modelName()).isEqualTo(model);
            var raw = storyboard.generate("系统", "简报", 256);
            assertThat(raw.content()).isEqualTo(CONTENT);
            assertThat(raw.modelName()).isEqualTo(model);
            assertThat(raw.inputTokens()).isEqualTo(promptTokens);
            assertThat(raw.outputTokens()).isEqualTo(3);
        }
    }

    private static final class Endpoint implements AutoCloseable {
        private final HttpServer server;
        private final List<Request> requests = new CopyOnWriteArrayList<>();

        private Endpoint(int status, int promptTokens) throws Exception {
            ObjectMapper json = new ObjectMapper();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                JsonNode request = json.readTree(exchange.getRequestBody());
                String path = exchange.getRequestURI().getPath();
                requests.add(new Request(path, exchange.getRequestHeaders().getFirst("Authorization"), request));
                var response = json.createObjectNode();
                if (status >= 400) {
                    response.putObject("error").put("message", "local-test-provider-failure");
                } else {
                    response.put("id", "local").put("object", "chat.completion").put("created", 1)
                            .put("model", request.path("model").asText());
                    var choice = response.putArray("choices").addObject().put("index", 0);
                    var message = choice.putObject("message").put("role", "assistant");
                    if (path.equals("/beta/chat/completions")) {
                        choice.put("finish_reason", "tool_calls");
                        message.putNull("content").putArray("tool_calls").addObject()
                                .put("id", "result-1").put("type", "function").putObject("function")
                                .put("name", "return_structured_result").put("arguments", CONTENT);
                    } else {
                        choice.put("finish_reason", "stop");
                        message.put("content", CONTENT);
                    }
                    response.putObject("usage").put("prompt_tokens", promptTokens)
                            .put("completion_tokens", 3).put("total_tokens", promptTokens + 3)
                            .putObject("prompt_tokens_details").put("cached_tokens", 2);
                }
                boolean stream=status<400 && request.path("stream").asBoolean();
                String payload=response.toString();
                if (stream) {
                    response.put("object", "chat.completion.chunk");
                    var choice=(com.fasterxml.jackson.databind.node.ObjectNode)response.path("choices").get(0);
                    choice.set("delta",choice.remove("message"));
                    payload="data: "+response+"\n\ndata: [DONE]\n\n";
                }
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", stream?"text/event-stream":"application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
