package com.link.linkagent.creator.media.preflight.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/** 通过 DashScope OpenAI 兼容接口执行单次 Qwen3-VL-Flash 全片粗审。 */
@Component
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class DashScopeVideoUnderstandingProvider implements VideoUnderstandingProvider {

    private static final String CHAT_PATH = "/compatible-mode/v1/chat/completions";

    private final CreatorMediaProperties.Preflight properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public DashScopeVideoUnderstandingProvider(CreatorMediaProperties mediaProperties, ObjectMapper objectMapper) {
        this.properties = mediaProperties.getPreflight();
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(nonNull(properties.getConnectTimeout(), Duration.ofSeconds(10)));
        factory.setReadTimeout(nonNull(properties.getVideoReadTimeout(), Duration.ofMinutes(10)));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    DashScopeVideoUnderstandingProvider(CreatorMediaProperties.Preflight properties,
                                        RestClient restClient,
                                        ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public AnalysisResult analyze(String videoUrl, String prompt) {
        ensureConfigured();
        JsonNode response = restClient.post()
                .uri(endpoint(CHAT_PATH))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getDashScopeApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(buildRequest(videoUrl, prompt))
                .retrieve()
                .body(JsonNode.class);
        return parseResponse(response);
    }

    ObjectNode buildRequest(String videoUrl, String prompt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties == null ? "qwen3-vl-flash" : properties.getVideoModel());
        body.put("enable_thinking", false);
        body.putObject("response_format").put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        ArrayNode content = messages.addObject().put("role", "user").putArray("content");
        ObjectNode video = content.addObject();
        video.put("type", "video_url");
        ObjectNode videoUrlNode = video.putObject("video_url");
        videoUrlNode.put("url", videoUrl);
        video.put("fps", properties == null ? 0.2d : properties.getVideoFps());
        content.addObject().put("type", "text").put("text", prompt);
        return body;
    }

    AnalysisResult parseResponse(JsonNode response) {
        JsonNode content = response == null ? null : response.at("/choices/0/message/content");
        if (content == null || !content.isTextual() || content.asText().isBlank()) {
            throw new VideoUnderstandingException("视频理解未返回结构化体检结果");
        }
        return new AnalysisResult(
                content.asText(),
                longValue(response, "/usage/prompt_tokens"),
                longValue(response, "/usage/completion_tokens")
        );
    }

    private void ensureConfigured() {
        if (properties.getDashScopeApiKey() == null || properties.getDashScopeApiKey().isBlank()) {
            throw new VideoUnderstandingException("DASHSCOPE_API_KEY 未配置");
        }
        if (properties.getDashScopeBaseUrl() == null || properties.getDashScopeBaseUrl().isBlank()
                || properties.getVideoModel() == null || properties.getVideoModel().isBlank()) {
            throw new VideoUnderstandingException("DashScope 视频理解配置不完整");
        }
    }

    private String endpoint(String path) {
        String base = properties.getDashScopeBaseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path;
    }

    private Long longValue(JsonNode root, String pointer) {
        JsonNode node = root == null ? null : root.at(pointer);
        return node == null || node.isMissingNode() || node.isNull() || !node.canConvertToLong()
                ? null : node.asLong();
    }

    private Duration nonNull(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }

    public static class VideoUnderstandingException extends RuntimeException {
        public VideoUnderstandingException(String message) {
            super(message);
        }
    }
}
