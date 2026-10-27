package com.link.linkagent.creator.creation.voice.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.voice.service.CreationVoiceProperties;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DashScope 语音合成适配。
 * <p>
 * 协议字段按官方异步/同步多模态生成接口实现：提交文本与音色后返回可下载的音频地址，
 * 用量取响应中的字符数。没有真实密钥时只完成协议适配与本地替身验证，
 * 不能据此宣称真实供应商已验收。
 */
@Component
public class DashScopeCosyVoiceProvider implements SpeechSynthesisProvider {

    private static final String GENERATION_PATH = "/api/v1/services/aigc/multimodal-generation/generation";
    private static final long MAX_AUDIO_BYTES = 32L * 1024 * 1024;

    private final CreationVoiceProperties voiceProperties;
    private final CreatorMediaProperties.Preflight preflight;
    private final RestClient client;
    private final ObjectMapper json;

    // 保留协议测试使用的构造器，同时明确 Spring 的实际装配入口。
    @Autowired
    public DashScopeCosyVoiceProvider(CreationVoiceProperties voiceProperties,
                                      CreatorMediaProperties mediaProperties,
                                      ObjectMapper json) {
        this(voiceProperties, mediaProperties.getPreflight(), json,
                RestClient.builder().requestFactory(factory(mediaProperties.getPreflight())).build());
    }

    DashScopeCosyVoiceProvider(CreationVoiceProperties voiceProperties,
                               CreatorMediaProperties.Preflight preflight,
                               ObjectMapper json,
                               RestClient client) {
        voiceProperties.validate();
        this.voiceProperties = voiceProperties;
        this.preflight = preflight;
        this.json = json;
        this.client = client;
    }

    @Override public String provider() { return "dashscope"; }
    @Override public String model() { return voiceProperties.getModel(); }
    @Override public String voice() { return voiceProperties.getVoice(); }

    @Override
    public Audio synthesize(String text) {
        ensureConfigured();
        if (text == null || text.isBlank()) throw new SpeechSynthesisException("配音文稿不能为空");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("text", text);
        input.put("voice", voiceProperties.getVoice());
        input.put("format", voiceProperties.getFormat().toLowerCase());
        input.put("sample_rate", voiceProperties.getSampleRate());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", voiceProperties.getModel());
        body.put("input", input);

        JsonNode response;
        try {
            response = client.post().uri(endpoint(GENERATION_PATH))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + preflight.getDashScopeApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException exception) {
            // 供应商错误正文可能回显请求内容，只保留状态码用于排查。
            int status = exception.getStatusCode().value();
            if (exception.getStatusCode().is4xxClientError() && status != 408)
                throw new SynthesisRejectedException("配音供应商拒绝了本次请求，HTTP " + status);
            throw new SpeechSynthesisException("配音请求发送结果不明，已停止自动重试");
        } catch (RuntimeException exception) {
            throw new SpeechSynthesisException("配音请求发送结果不明，已停止自动重试");
        }
        String requestId = text(response, "/request_id");
        String url = text(response, "/output/audio/url");
        String base64 = text(response, "/output/audio/data");
        Long characters = longValue(response, "/usage/characters");
        byte[] bytes;
        String contentType;
        if (url != null) {
            Download download = download(url);
            bytes = download.bytes();
            contentType = download.contentType();
        } else if (base64 != null) {
            try { bytes = Base64.getDecoder().decode(base64); }
            catch (IllegalArgumentException exception) { throw new SpeechSynthesisException("配音响应音频不是有效的 Base64"); }
            contentType = "audio/wav";
        } else {
            throw new SpeechSynthesisException("配音响应没有音频地址或音频内容");
        }
        if (bytes.length == 0 || bytes.length > MAX_AUDIO_BYTES) throw new SpeechSynthesisException("配音音频大小超出允许范围");
        return new Audio(bytes, contentType, requestId, characters);
    }

    private Download download(String url) {
        try {
            var response = client.get().uri(URI.create(url)).retrieve().toEntity(byte[].class);
            byte[] bytes = response.getBody();
            return new Download(bytes == null ? new byte[0] : bytes,
                    response.getHeaders().getContentType() == null ? "audio/wav" : response.getHeaders().getContentType().toString());
        } catch (RuntimeException exception) {
            throw new SpeechSynthesisException("配音音频下载失败，本次调用结果不确定");
        }
    }

    private void ensureConfigured() {
        // 缺少部署凭据时必须在发送前识别，不能先占额度再记成结果不明。
        if (preflight.getDashScopeApiKey() == null || preflight.getDashScopeApiKey().isBlank())
            throw new SynthesisNotConfiguredException("DASHSCOPE_API_KEY 未配置");
        if (preflight.getDashScopeBaseUrl() == null || preflight.getDashScopeBaseUrl().isBlank())
            throw new SynthesisNotConfiguredException("DashScope Base URL 未配置");
    }

    private String endpoint(String path) {
        String base = preflight.getDashScopeBaseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path;
    }

    private String text(JsonNode root, String pointer) {
        JsonNode node = root == null ? null : root.at(pointer);
        return node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank() ? null : node.asText();
    }

    private Long longValue(JsonNode root, String pointer) {
        JsonNode node = root == null ? null : root.at(pointer);
        return node == null || node.isMissingNode() || node.isNull() || !node.canConvertToLong() ? null : node.asLong();
    }

    private static SimpleClientHttpRequestFactory factory(CreatorMediaProperties.Preflight properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout() == null ? Duration.ofSeconds(10) : properties.getConnectTimeout());
        factory.setReadTimeout(properties.getReadTimeout() == null ? Duration.ofSeconds(30) : properties.getReadTimeout());
        return factory;
    }

    private record Download(byte[] bytes, String contentType) { }

    public static class SpeechSynthesisException extends RuntimeException {
        public SpeechSynthesisException(String message) { super(message); }
    }

    /** 部署凭据缺失：请求没有发出，也不能计入未知费用。 */
    public static class SynthesisNotConfiguredException extends SpeechSynthesisException {
        public SynthesisNotConfiguredException(String message) { super(message); }
    }

    /** 明确的 4xx 拒绝：可以确认没有生成音频，不能把额度长期挂成未知。 */
    public static class SynthesisRejectedException extends SpeechSynthesisException {
        public SynthesisRejectedException(String message) { super(message); }
    }
}
