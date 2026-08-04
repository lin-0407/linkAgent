package com.link.linkagent.creator.media.preflight.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope 中国区文件转写实现。
 * 协议字段来自 P0-2 真实联调，避免沿用旧版 file_urls/results 结构导致任务无法提交或读取。
 */
@Component
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class DashScopeAsrProvider implements SpeechRecognitionProvider {

    private static final String TASK_PATH = "/api/v1/services/audio/asr/transcription";

    private final CreatorMediaProperties.Preflight properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public DashScopeAsrProvider(CreatorMediaProperties mediaProperties, ObjectMapper objectMapper) {
        this.properties = mediaProperties.getPreflight();
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(nonNull(properties.getConnectTimeout(), Duration.ofSeconds(10)));
        factory.setReadTimeout(nonNull(properties.getReadTimeout(), Duration.ofSeconds(30)));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    DashScopeAsrProvider(CreatorMediaProperties.Preflight properties,
                         RestClient restClient,
                         ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String submit(String audioUrl) {
        ensureConfigured();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("file_url", audioUrl);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getAsrModel());
        body.put("input", input);
        body.put("parameters", Map.of());
        JsonNode response = restClient.post()
                .uri(endpoint(TASK_PATH))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getDashScopeApiKey())
                .header("X-DashScope-Async", "enable")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        String taskId = text(response, "/output/task_id");
        if (taskId == null) {
            throw new SpeechRecognitionException("ASR 未返回任务 ID");
        }
        return taskId;
    }

    @Override
    public QueryResult query(String providerTaskId) {
        ensureConfigured();
        JsonNode response = restClient.get()
                .uri(endpoint("/api/v1/tasks/" + providerTaskId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getDashScopeApiKey())
                .retrieve()
                .body(JsonNode.class);
        return parseQueryResult(response);
    }

    QueryResult parseQueryResult(JsonNode response) {
        String status = text(response, "/output/task_status");
        if (status == null) {
            throw new SpeechRecognitionException("ASR 查询未返回任务状态");
        }
        Long usageSeconds = firstLong(response, "/usage/seconds", "/usage/duration");
        String failureCode = firstText(response, "/output/code", "/code");
        return switch (status) {
            case "PENDING" -> new QueryResult(Status.PENDING, null, usageSeconds, null);
            case "RUNNING" -> new QueryResult(Status.RUNNING, null, usageSeconds, null);
            case "SUCCEEDED" -> new QueryResult(
                    Status.SUCCEEDED,
                    text(response, "/output/result/transcription_url"),
                    usageSeconds,
                    null
            );
            case "FAILED", "CANCELED", "CANCELLED", "UNKNOWN" -> {
                // DashScope 用 FAILED 承载“处理完成但无人声”，业务上应跳过字幕而不是终止整次试映。
                if ("SUCCESS_WITH_NO_VALID_FRAGMENT".equals(failureCode)) {
                    yield new QueryResult(Status.NO_SPEECH, null, usageSeconds, failureCode);
                }
                yield new QueryResult(
                        Status.FAILED,
                        null,
                        usageSeconds,
                        firstText(response, "/output/message", "/message", "/code")
                );
            }
            default -> throw new SpeechRecognitionException("ASR 返回未知任务状态");
        };
    }

    @Override
    public TranscriptionResult loadResult(String transcriptionUrl) {
        if (transcriptionUrl == null || transcriptionUrl.isBlank()) {
            throw new SpeechRecognitionException("ASR 未返回转写结果地址");
        }
        JsonNode root = restClient.get().uri(URI.create(transcriptionUrl)).retrieve().body(JsonNode.class);
        List<Segment> segments = parseSegments(root);
        if (segments.isEmpty()) {
            throw new SpeechRecognitionException("ASR 转写结果没有时间戳文本");
        }
        return new TranscriptionResult(segments);
    }

    List<Segment> parseSegments(JsonNode root) {
        List<Segment> segments = new ArrayList<>();
        JsonNode transcripts = root == null ? null : root.path("transcripts");
        if (transcripts == null || !transcripts.isArray()) {
            return segments;
        }
        for (JsonNode transcript : transcripts) {
            String language = nullableText(transcript.get("language"));
            JsonNode sentences = transcript.path("sentences");
            if (!sentences.isArray()) {
                continue;
            }
            for (JsonNode sentence : sentences) {
                String value = firstText(sentence, "/text", "/sentence");
                Long startMs = firstLong(sentence, "/begin_time", "/start_time", "/start_ms");
                Long endMs = firstLong(sentence, "/end_time", "/end_ms");
                if (value == null || startMs == null || endMs == null || endMs < startMs) {
                    continue;
                }
                segments.add(new Segment(
                        startMs,
                        endMs,
                        value,
                        doubleValue(sentence, "/confidence"),
                        firstText(sentence, "/speaker_id", "/speaker"),
                        language
                ));
            }
        }
        return segments;
    }

    private void ensureConfigured() {
        if (properties.getDashScopeApiKey() == null || properties.getDashScopeApiKey().isBlank()) {
            throw new SpeechRecognitionException("DASHSCOPE_API_KEY 未配置");
        }
        if (properties.getDashScopeBaseUrl() == null || properties.getDashScopeBaseUrl().isBlank()) {
            throw new SpeechRecognitionException("DashScope Base URL 未配置");
        }
        if (properties.getAsrModel() == null || properties.getAsrModel().isBlank()) {
            throw new SpeechRecognitionException("ASR 模型未配置");
        }
    }

    private String endpoint(String path) {
        String base = properties.getDashScopeBaseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path;
    }

    private String firstText(JsonNode root, String... pointers) {
        for (String pointer : pointers) {
            String value = text(root, pointer);
            if (value != null) return value;
        }
        return null;
    }

    private Long firstLong(JsonNode root, String... pointers) {
        for (String pointer : pointers) {
            Long value = longValue(root, pointer);
            if (value != null) return value;
        }
        return null;
    }

    private String text(JsonNode root, String pointer) {
        return nullableText(root == null ? null : root.at(pointer));
    }

    private String nullableText(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank()
                ? null : node.asText();
    }

    private Long longValue(JsonNode root, String pointer) {
        JsonNode node = root == null ? null : root.at(pointer);
        return node == null || node.isMissingNode() || node.isNull() || !node.canConvertToLong()
                ? null : node.asLong();
    }

    private Double doubleValue(JsonNode root, String pointer) {
        JsonNode node = root == null ? null : root.at(pointer);
        return node == null || node.isMissingNode() || node.isNull() || !node.isNumber()
                ? null : node.asDouble();
    }

    private Duration nonNull(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }

    public static class SpeechRecognitionException extends RuntimeException {
        public SpeechRecognitionException(String message) {
            super(message);
        }
    }
}
