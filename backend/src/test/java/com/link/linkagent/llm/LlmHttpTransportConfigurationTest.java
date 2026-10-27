package com.link.linkagent.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmHttpTransportConfigurationTest {

    @Test
    void shouldReadCompleteChunkedResponseThroughBootConfiguredClient() throws IOException {
        try (ChunkedModelServer server = new ChunkedModelServer()) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
                    .withUserConfiguration(LlmHttpTransportConfiguration.class)
                    .run(context -> {
                        OpenAiApi api = OpenAiApi.builder()
                                .baseUrl(server.baseUrl()).apiKey("test-only")
                                .restClientBuilder(context.getBean(RestClient.Builder.class))
                                .build();
                        ChatClient client = ChatClient.create(OpenAiChatModel.builder().openAiApi(api)
                                .defaultOptions(OpenAiChatOptions.builder().model("test-model").build()).build());

                        var response = client.prompt().user("传输测试").call().chatResponse();

                        assertThat(response.getResult().getOutput().getText()).isEqualTo("{\"answer\":\"完整响应\"}");
                        assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(11);
                    });
        }
    }

    @Test
    void shouldReadCompleteChunkedResponseThroughIndependentStrictClient() throws IOException {
        try (ChunkedModelServer server = new ChunkedModelServer()) {
            StrictFunctionCallingProperties properties = new StrictFunctionCallingProperties();
            properties.setBaseUrl(server.baseUrl());
            properties.setApiKey("test-only");
            properties.setModel("test-model");
            DeepSeekThinkingOptionsFactory options = mock(DeepSeekThinkingOptionsFactory.class);
            when(options.optionsForModel("test-model"))
                    .thenReturn(OpenAiChatOptions.builder().model("test-model").build());

            var response = new StrictFunctionCallingClient(properties, options).call("传输测试",
                    List.of(new UserMessage("传输测试")), List.of(), null, true);

            assertThat(response.getResult().getOutput().getText()).isEqualTo("{\"answer\":\"完整响应\"}");
            assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(11);
        }
    }

    /** 首字符与余下 JSON 分开发送，验证真实 HTTP 读取不会把第一个分块当成完整正文。 */
    private static final class ChunkedModelServer implements AutoCloseable {
        private final HttpServer server;

        private ChunkedModelServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] response = """
                        {"id":"chunk-test","object":"chat.completion","created":1,"model":"test-model",
                        "choices":[{"index":0,"message":{"role":"assistant","content":"{\\"answer\\":\\"完整响应\\"}"},"finish_reason":"stop"}],
                        "usage":{"prompt_tokens":7,"completion_tokens":4,"total_tokens":11}}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                try (var output = exchange.getResponseBody()) {
                    output.write(response, 0, 1);
                    output.flush();
                    output.write(response, 1, response.length - 1);
                }
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
