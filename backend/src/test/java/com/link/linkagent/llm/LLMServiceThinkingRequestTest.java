package com.link.linkagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 本机假供应商只记录真实 HTTP 请求体，避免把选项对象断言冒充为已发送参数。 */
class LLMServiceThinkingRequestTest {
    @Test
    void shouldSendMaxOnlyForAnswerAndLowForOtherEvaluationCalls() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<JsonNode> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            byte[] response = """
                    {"id":"local","object":"chat.completion","created":1,"model":"deepseek-flash",
                    "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                    "usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            DeepSeekThinkingProperties properties = new DeepSeekThinkingProperties();
            properties.setDefaultModel("deepseek-v4-flash");
            OpenAiApi api = OpenAiApi.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .apiKey("local-test-only").build();
            OpenAiChatModel model = OpenAiChatModel.builder().openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder().model("deepseek-v4-flash").build()).build();
            LlmCallGuardProperties guard = new LlmCallGuardProperties();
            guard.setEnabled(false);
            LLMService llm = new LLMService(ChatClient.builder(model), guard, null, null, null, json, null,
                    new DeepSeekThinkingOptionsFactory(properties, null), null);
            List<String> scenes = List.of("评测回答", "评测裁判", "长期记忆抽取", "长期记忆写入决策",
                    "长期记忆重要性打分", "摘要复核", "摘要事实点抽取");
            for (String scene : scenes) {
                try (var ignored = LlmUsageContext.scene(scene)) {
                    assertThat(llm.chatWithUsage("测试", "测试").content()).isEqualTo("ok");
                }
            }
            assertThat(requests).hasSize(scenes.size());
            for (int index = 0; index < requests.size(); index++) {
                assertThat(requests.get(index).path("thinking").path("type").asText()).isEqualTo("enabled");
                assertThat(requests.get(index).path("reasoning_effort").asText())
                        .as(scenes.get(index)).isEqualTo(index == 0 ? "max" : "low");
            }
        } finally {
            server.stop(0);
        }
    }
}
