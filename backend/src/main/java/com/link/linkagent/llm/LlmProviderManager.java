package com.link.linkagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 多 Provider 回退链管理器（方案四）。
 * 当主 ChatClient（spring.ai.openai.*）调用失败时，
 * 按配置顺序依次尝试备用 Provider，遇限流自动冷却、遇异常自动跳过。
 * 全挂时才抛出异常，单个 Provider 失败不影响后续备用 Provider。
 */
@Component
public class LlmProviderManager {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderManager.class);

    private final LlmFallbackProperties fallbackProperties;
    private final ObjectMapper objectMapper;
    /** Provider 冷却结束时间记录：key 为 provider name，value 为冷却结束的 Instant */
    private final Map<String, Instant> cooldownMap = new ConcurrentHashMap<>();

    public LlmProviderManager(LlmFallbackProperties fallbackProperties, ObjectMapper objectMapper) {
        this.fallbackProperties = fallbackProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 是否有可用的备用 Provider。
     * 主流程在调用前先检查，避免进入回退链后发现全部不可用。
     */
    public boolean hasAvailableProvider() {
        if (!fallbackProperties.isEnabled() || fallbackProperties.getProviders().isEmpty()) {
            return false;
        }
        return fallbackProperties.getProviders().stream()
                .anyMatch(p -> !isOnCooldown(p.getName()));
    }

    /**
     * 尝试调用备用 Provider 链，返回模型输出文本。
     * 遍历所有已配置的 Provider，跳过冷却中的，尝试调用第一个可用的。
     * 全部失败时抛出 AllProvidersFailedException。
     */
    public LlmCallResult tryFallback(String systemPrompt, String userMessage) {
        List<LlmFallbackProperties.ProviderConfig> providers = fallbackProperties.getProviders();
        Exception lastException = null;

        for (LlmFallbackProperties.ProviderConfig provider : providers) {
            if (isOnCooldown(provider.getName())) {
                log.debug("Provider {} 冷却中，跳过", provider.getName());
                continue;
            }
            try {
                long startNanos = System.nanoTime();
                String responseBody = callProviderApi(provider, systemPrompt, userMessage);
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

                // 解析 OpenAI 兼容响应格式
                JsonNode root = objectMapper.readTree(responseBody);
                String content = extractContent(root);
                String modelName = extractModelName(root, provider.getModel());

                log.info("备用 Provider {} 调用成功，耗时 {}ms", provider.getName(), elapsedMs);
                return new LlmCallResult(content, modelName, null, null, null, elapsedMs);
            } catch (RestClientException e) {
                // HTTP 层异常（网络不通、超时等）
                log.warn("备用 Provider {} 调用失败（网络/HTTP异常）：{}", provider.getName(), e.getMessage());
                lastException = e;
                // 不冷却——网络异常通常是临时的，下次重试可能恢复
            } catch (RateLimitException e) {
                // 限流异常：标记冷却，跳过该 Provider
                log.warn("备用 Provider {} 被限流，冷却 {} 秒", provider.getName(),
                        fallbackProperties.getCooldownSeconds());
                markCooldown(provider.getName());
                lastException = e;
            } catch (Exception e) {
                log.warn("备用 Provider {} 调用失败：{}", provider.getName(), e.getMessage());
                lastException = e;
            }
        }

        String message = "所有 LLM Provider（主+备用）均不可用";
        if (lastException != null) {
            message += "，最后一次异常：" + lastException.getMessage();
        }
        throw new AllProvidersFailedException(message, lastException);
    }

    /**
     * 调用单个 Provider 的 OpenAI 兼容聊天 API。
     * 使用 RestClient 直接发送 HTTP 请求，不依赖 Spring AI ChatClient，
     * 这样每个备用 Provider 只需要配置 base-url、api-key 和 model 即可。
     */
    private String callProviderApi(LlmFallbackProperties.ProviderConfig provider,
                                   String systemPrompt, String userMessage) throws Exception {
        // 构建 OpenAI 兼容的请求体
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", provider.getModel());
        ArrayNode messages = objectMapper.createArrayNode();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sysMsg = objectMapper.createObjectNode();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.add(sysMsg);
        }

        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage != null ? userMessage : "");
        messages.add(userMsg);

        requestBody.set("messages", messages);

        // 发送 HTTP 请求
        RestClient restClient = RestClient.builder()
                .baseUrl(provider.getBaseUrl())
                .build();

        String responseBody = restClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + provider.getApiKey())
                .body(requestBody.toString())
                .retrieve()
                .onStatus(status -> status.value() == 429, (req, resp) -> {
                    throw new RateLimitException("Provider " + provider.getName() + " 返回 429 限流");
                })
                .body(String.class);

        return responseBody;
    }

    /**
     * 从 OpenAI 兼容响应中提取文本内容。
     * 格式：choices[0].message.content
     */
    private String extractContent(JsonNode root) {
        JsonNode choices = root.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode message = choices.get(0).get("message");
            if (message != null) {
                JsonNode content = message.get("content");
                if (content != null) {
                    return content.asText();
                }
            }
        }
        return "";
    }

    /**
     * 从响应中提取实际使用的模型名称，未返回时用配置的模型名兜底。
     */
    private String extractModelName(JsonNode root, String configuredModel) {
        JsonNode modelNode = root.get("model");
        if (modelNode != null && !modelNode.isNull()) {
            return modelNode.asText();
        }
        return configuredModel;
    }

    private boolean isOnCooldown(String providerName) {
        Instant cooldownEnd = cooldownMap.get(providerName);
        return cooldownEnd != null && Instant.now().isBefore(cooldownEnd);
    }

    private void markCooldown(String providerName) {
        cooldownMap.put(providerName, Instant.now().plusSeconds(fallbackProperties.getCooldownSeconds()));
    }

    /**
     * 所有 Provider（含主 Provider 和备用）均不可用时抛出。
     */
    public static class AllProvidersFailedException extends RuntimeException {
        public AllProvidersFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * HTTP 429 限流异常，用于触发 Provider 冷却。
     */
    public static class RateLimitException extends RuntimeException {
        public RateLimitException(String message) {
            super(message);
        }
    }
}
