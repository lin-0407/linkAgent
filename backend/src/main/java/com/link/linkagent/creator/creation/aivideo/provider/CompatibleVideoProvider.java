package com.link.linkagent.creator.creation.aivideo.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.link.linkagent.creator.creation.aivideo.service.CreationVideoProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可配置的异步视频生成协议适配。
 * <p>
 * 协议约定（部署者按此填写服务地址，不支持任意 URL）：
 * <pre>
 * POST {base}/video/generations
 *   {"model","prompt","duration_seconds","size","seed"}
 *   → 2xx {"id"|"task_id", "request_id"}
 * GET  {base}/video/generations/{taskId}
 *   → {"status":"queued|running|succeeded|failed",
 *      "video_url","duration_seconds","width","height","usage_seconds",
 *      "error":{"code"}}
 * </pre>
 * 同时兼容把状态放在 {@code output.task_status}、把结果放在 {@code output.video_url} 的返回。
 * 这里只完成协议适配与本地替身验证，真实供应商仍须在实际部署中核对与验收。
 */
@Component
public class CompatibleVideoProvider implements VideoGenerationProvider {

    private final CreationVideoProperties settings;
    private final RestClient client;
    private final RestClient downloads;

    public CompatibleVideoProvider(CreationVideoProperties settings) {
        this(settings,
                RestClient.builder().requestFactory(factory(settings.getConnectTimeout(), settings.getReadTimeout())).build(),
                RestClient.builder().requestFactory(factory(settings.getConnectTimeout(), settings.getDownloadTimeout())).build());
    }

    CompatibleVideoProvider(CreationVideoProperties settings, RestClient client, RestClient downloads) {
        // 运行期开关可能后置打开；部署默认开启时才在这里强制校验完整配置。
        if (settings.isEnabled()) settings.validate();
        this.settings = settings;
        this.client = client;
        this.downloads = downloads;
    }

    @Override public String provider() { return settings.getProvider(); }
    @Override public String model() { return settings.getModel(); }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(settings.durations(), settings.getWidth(), settings.getHeight(), settings.getUsdPerSecond());
    }

    @Override
    public TaskRef submit(Submit request) {
        ensureConfigured();
        if (request == null || request.prompt() == null || request.prompt().isBlank())
            throw new VideoRejectedException("视频提示词不能为空");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", settings.getModel());
        body.put("prompt", request.prompt());
        body.put("duration_seconds", request.seconds());
        body.put("size", request.width() + "x" + request.height());
        body.put("seed", request.seed());
        JsonNode response;
        try {
            response = client.post().uri(endpoint("/video/generations"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + settings.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            // 明确的 4xx 拒绝没有创建任务；超时、5xx 和断连都可能已经受理，必须保留未知费用。
            if (exception.getStatusCode().is4xxClientError() && status != 408)
                throw new VideoRejectedException("视频供应商拒绝了本次提交，HTTP " + status);
            throw new VideoUncertainException("视频提交结果不明，已停止自动重试");
        } catch (RuntimeException exception) {
            throw new VideoUncertainException("视频提交结果不明，已停止自动重试");
        }
        String taskId = text(response, "/id", "/task_id", "/output/task_id", "/data/id");
        if (taskId == null || taskId.isBlank()) {
            // 没有任务标识就无法续查，提交事实仍然存在，不能当免费。
            throw new VideoUncertainException("视频提交响应缺少任务标识");
        }
        return new TaskRef(taskId, text(response, "/request_id", "/output/request_id", "/data/request_id"));
    }

    @Override
    public QueryResult query(String taskId) {
        ensureConfigured();
        if (taskId == null || taskId.isBlank()) throw new VideoProviderException("视频任务标识不能为空");
        JsonNode response;
        try {
            response = client.get().uri(endpoint("/video/generations/" + encode(taskId)))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + settings.getApiKey())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404)
                return new QueryResult(Status.FAILED, null, null, null, null, null, "TASK_NOT_FOUND", null);
            throw new VideoProviderException("视频任务状态暂时无法查询");
        } catch (RuntimeException exception) {
            throw new VideoProviderException("视频任务状态暂时无法查询");
        }
        String status = text(response, "/status", "/output/task_status", "/data/status");
        Status parsed = parseStatus(status);
        String url = text(response, "/video_url", "/output/video_url", "/data/video_url",
                "/output/results/0/url", "/data/0/url");
        Long usage = longValue(response, "/usage_seconds", "/usage/duration", "/output/usage_seconds");
        Long durationMs = durationMs(response);
        Integer width = intValue(response, "/width", "/output/width");
        Integer height = intValue(response, "/height", "/output/height");
        String failure = text(response, "/error/code", "/output/code", "/failure_code");
        String requestId = text(response, "/request_id", "/data/request_id");
        if (parsed == null) throw new VideoProviderException("视频任务状态不是受支持的值");
        if (parsed == Status.SUCCEEDED && url == null)
            return new QueryResult(Status.FAILED, null, usage, durationMs, width, height, "RESULT_MISSING", requestId);
        return new QueryResult(parsed, url, usage, durationMs, width, height, failure, requestId);
    }

    @Override
    public byte[] download(String url, long maxBytes) {
        if (url == null || url.isBlank()) throw new VideoProviderException("生成结果缺少下载地址");
        URI uri;
        try { uri = URI.create(url); }
        catch (IllegalArgumentException exception) { throw new VideoProviderException("生成结果下载地址无效"); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
            throw new VideoProviderException("生成结果下载地址协议不受支持");
        try {
            // 下载请求不带供应商密钥，避免结果地址指向第三方时泄露凭据。
            byte[] bytes = downloads.get().uri(uri).retrieve().body(byte[].class);
            if (bytes == null || bytes.length == 0) throw new VideoProviderException("生成结果为空");
            if (bytes.length > maxBytes) throw new VideoProviderException("生成结果超过允许的大小");
            return bytes;
        } catch (RestClientResponseException exception) {
            throw new VideoProviderException("生成结果下载失败");
        } catch (RuntimeException exception) {
            throw new VideoProviderException("生成结果下载失败");
        }
    }

    private void ensureConfigured() {
        if (!settings.providerConfigured()) throw new VideoNotConfiguredException("视频生成供应商尚未配置");
    }

    private String endpoint(String path) {
        String base = settings.getBaseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Status parseStatus(String value) {
        if (value == null) return null;
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "queued", "pending", "submitted" -> Status.QUEUED;
            case "running", "processing", "in_progress" -> Status.RUNNING;
            case "succeeded", "success", "completed", "done" -> Status.SUCCEEDED;
            case "failed", "error", "canceled", "cancelled" -> Status.FAILED;
            default -> null;
        };
    }

    private Long durationMs(JsonNode root) {
        JsonNode seconds = first(root, "/duration_seconds", "/output/duration_seconds");
        if (seconds != null && seconds.isNumber() && Double.isFinite(seconds.asDouble()) && seconds.asDouble() > 0)
            return Math.round(seconds.asDouble() * 1000);
        JsonNode millis = first(root, "/duration_ms", "/output/duration_ms");
        return millis != null && millis.isNumber() && millis.asLong() > 0 ? millis.asLong() : null;
    }

    private String text(JsonNode root, String... pointers) {
        JsonNode node = first(root, pointers);
        return node == null || node.isNull() || node.asText().isBlank() ? null : node.asText();
    }

    private Long longValue(JsonNode root, String... pointers) {
        JsonNode node = first(root, pointers);
        if (node == null || node.isNull()) return null;
        if (node.isNumber() && node.canConvertToLong()) return node.asLong();
        try { return node.canConvertToLong() ? node.asLong() : null; }
        catch (RuntimeException exception) { return null; }
    }

    private Integer intValue(JsonNode root, String... pointers) {
        JsonNode node = first(root, pointers);
        return node == null || !node.canConvertToInt() || node.asInt() <= 0 ? null : node.asInt();
    }

    private JsonNode first(JsonNode root, String... pointers) {
        if (root == null) return null;
        for (String pointer : pointers) {
            JsonNode node = root.at(pointer);
            if (node != null && !node.isMissingNode() && !node.isNull()) return node;
        }
        return null;
    }

    private static SimpleClientHttpRequestFactory factory(Duration connect, Duration read) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connect == null ? Duration.ofSeconds(10) : connect);
        factory.setReadTimeout(read == null ? Duration.ofSeconds(60) : read);
        return factory;
    }
}
