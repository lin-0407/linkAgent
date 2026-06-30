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
 * LLM 多 Provider 回退链管理器（方案四）—— 管理多个 LLM 模型实例的负载均衡和故障转移。
 * <p>
 * 在架构中的位置：位于 {@link LLMService} 的失败处理路径中，不介入正常情况下由 Spring AI
 * ChatClient 驱动的主 Provider 调用；只有当主 Provider 抛出异常时才被激活，按配置顺序遍历
 * 备用 Provider 链，找到第一个可用的并透传结果。
 * <p>
 * <b>核心设计决策</b>
 * <ul>
 *   <li><b>独立于 Spring AI</b>：备用 Provider 不通过 Spring AI ChatClient 调用，而是直接用
 *       RestClient 发送 OpenAI 兼容的 HTTP 请求。这样每个备用 Provider 的 base-url、api-key、
 *       model 完全独立配置，不与主 Provider 共享任何连接或状态，实现真正的故障隔离。</li>
 *   <li><b>冷却机制（Cooldown）</b>：被限流（HTTP 429）的 Provider 进入冷却期，冷却期内不参与
 *       回退链选择，避免连续请求堆到已被限流的 Provider 上加速限流处罚。冷却到期后自动恢复可用，
 *       不需要手动干预或重启服务。</li>
 *   <li><b>网络异常不冷却</b>：RestClientException（网络不通、超时、DNS 解析失败等）不触发冷却，
 *       因为网络异常通常是临时性的——下次重试大概率恢复。冷却只适用于服务器端主动返回的限流信号。</li>
 *   <li><b>全挂才抛</b>：只有主 Provider + 所有备用 Provider 全部失败时才抛出异常；单个备用
 *       Provider 的失败只打 warn 日志并继续尝试下一个，不中断回退链。</li>
 *   <li><b>配置驱动</b>：Provider 列表、冷却时间全部通过 {@link LlmFallbackProperties} 配置，
 *       新增/移除备用 Provider 只需修改 YAML 配置，无需改代码。</li>
 * </ul>
 */
@Component
public class LlmProviderManager {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderManager.class);

    /** 备用 Provider 配置：包括 Provider 列表、每个 Provider 的 base-url/api-key/model、冷却时间等。 */
    private final LlmFallbackProperties fallbackProperties;

    /** Jackson ObjectMapper：用于构建 OpenAI 兼容的 JSON 请求体，以及解析响应中的 content 和 model 字段。 */
    private final ObjectMapper objectMapper;

    /**
     * Provider 冷却结束时间记录。
     * <p>
     * key 为 provider name（来自配置中的唯一标识），value 为该 Provider 冷却结束的 Instant。
     * 使用 ConcurrentHashMap 而非 synchronized HashMap，因为：
     * ①回退链可能在多个并发请求中同时被触发（主 Provider 宕机时流量全部涌入回退链）；
     * ②ConcurrentHashMap 的读（isOnCooldown）几乎无锁，写（markCooldown）是局部锁，
     *    不会在高并发冷却查询时造成全局锁竞争。
     */
    private final Map<String, Instant> cooldownMap = new ConcurrentHashMap<>();

    /**
     * 构造器：注入回退链配置和 JSON 工具。
     *
     * @param fallbackProperties 备用 Provider 配置（Provider 列表、冷却时间等）
     * @param objectMapper Jackson ObjectMapper（由 Spring Boot 自动配置的共享实例）
     */
    public LlmProviderManager(LlmFallbackProperties fallbackProperties, ObjectMapper objectMapper) {
        this.fallbackProperties = fallbackProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 判断是否有可用的备用 Provider（未被冷却且配置了 provider 列表）。
     * <p>
     * 主流程在调用回退链前先通过此方法做"预检查"——若回退链未启用（fallbackProperties.isEnabled() == false）
     * 或所有 Provider 都处于冷却中，则直接跳过回退尝试，避免进入 {@link #tryFallback} 后
     * 遍历所有 Provider 却发现全部不可用的无效开销。
     * <p>
     * 为什么不在 tryFallback 内部静默处理"全部冷却"而是提供这个方法：
     * 调用方可以根据 hasAvailableProvider() 的返回做差异化日志记录——"无备用 Provider 可用"
     * 和 "虽无备用 Provider 但回退链未配置"在运营视角是不同的信号。
     *
     * @return true 表示至少有一个备用 Provider 可用（未被冷却且配置列表非空）
     */
    public boolean hasAvailableProvider() {
        if (!fallbackProperties.isEnabled() || fallbackProperties.getProviders().isEmpty()) {
            return false;
        }
        return fallbackProperties.getProviders().stream()
                .anyMatch(p -> !isOnCooldown(p.getName()));
    }

    /**
     * 尝试调用备用 Provider 链，按配置顺序选取第一个可用的 Provider 并返回 LLM 输出文本。
     * <p>
     * <b>遍历策略</b>
     * 按配置列表的顺序依次尝试，不随机挑选——原因有三：
     * <ol>
     *   <li>配置顺序代表优先级意图（越靠前的 Provider 通常越可靠或越便宜）；</li>
     *   <li>确定性行为便于排查问题（每次回退都尝试相同的 Provider 顺序，日志可对比不同请求的行为）；</li>
     *   <li>避免随机选择导致某个冷却中的 Provider 被反复尝试（随机算法在冷却期间会浪费 CPU）。</li>
     * </ol>
     * <p>
     * <b>异常分类处理</b>
     * <ul>
     *   <li>{@link RestClientException}（网络/HTTP 异常）：不冷却，打 warn 日志后继续下一个 Provider。
     *       网络问题通常是临时的——DNS 抖动、路由波动、对方机房的短暂不可达，下次重试大概率恢复。</li>
     *   <li>{@link RateLimitException}（HTTP 429）：立即标记冷却，跳过该 Provider 并继续尝试下一个。
     *       限流是服务器端主动拒绝，说明配额已耗尽或 QPS 超限——继续请求只会延长限流处罚。</li>
     *   <li>其他 Exception：打 warn 日志后继续尝试下一个，不做特殊分类（如 JSON 解析异常、
     *       响应格式异常等——这些是 Provider 的 bug，冷却没有意义）。</li>
     * </ul>
     * <p>
     * <b>Token 用量缺失说明</b>
     * 当前直接通过 RestClient 调用 OpenAI 兼容 API，解析响应中的 content 和 model 字段，
     * 但没有解析响应中的 usage 部分（token 用量）。返回的 LlmCallResult 的 token 字段均为 null。
     * 这是因为回退链是"备用路径"，不需要成本统计的精确度与主路径一致——
     * 先保证可用性，后续再补充 usage 解析。
     *
     * @param systemPrompt 系统提示词
     * @param userMessage 用户输入文本
     * @return 包含回复内容、模型名称（从 API 响应中提取或回退到配置值）和耗时的调用结果
     * @throws AllProvidersFailedException 当所有备用 Provider 都失败时抛出（含最后一次异常的详情）
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
     * 调用单个 Provider 的 OpenAI 兼容聊天 API，返回原始 JSON 响应字符串。
     * <p>
     * <b>为什么不通过 Spring AI ChatClient</b>
     * Spring AI 的 ChatClient 在构建时就绑定了特定的 base-url 和 api-key（来自
     * spring.ai.openai.* 配置），运行时无法动态切换 Provider。如果为每个备用 Provider 都创建
     * 一个 ChatClient 实例，会引入多个连接池、多个 HTTP 客户端，增加内存开销和配置复杂度。
     * 因此直接用 RestClient 发送 HTTP 请求——轻量、可动态指定 base-url/api-key、不引入额外连接池。
     * <p>
     * <b>请求体格式</b>
     * 构建标准的 OpenAI Chat Completions API 请求体：
     * <pre>{@code
     * {
     *   "model": "...",
     *   "messages": [
     *     {"role": "system", "content": "..."},
     *     {"role": "user", "content": "..."}
     *   ]
     * }
     * }</pre>
     * system prompt 为空时不发送 system 消息——部分兼容层（如某些代理/网关）不允许空的 system 消息。
     * <p>
     * <b>HTTP 429 处理</b>
     * 使用 RestClient 的 onStatus 拦截器：遇到 429 时不在本方法内做冷却标记（冷却属于回退链的全局策略，
     * 由 {@link #tryFallback} 统一管理），而是转换为专用的 {@link RateLimitException} 向上抛出，
     * 由 tryFallback 捕获后统一执行冷却逻辑。这样冷却策略集中在回退链层面，单个 Provider 调用不关心
     * 自己的冷却状态。
     *
     * @param provider 备用 Provider 的配置（base-url、api-key、model）
     * @param systemPrompt 系统提示词
     * @param userMessage 用户输入文本
     * @return API 返回的原始 JSON 响应字符串
     * @throws RateLimitException 当 API 返回 HTTP 429 限流响应时
     * @throws RestClientException 当网络不通、超时、DNS 解析失败等 HTTP 层异常时
     * @throws Exception 当 JSON 构建失败等其他异常时
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
     * <p>
     * 目标路径：{@code choices[0].message.content}，即取第一个 choice 的 message 对象的 content 字段。
     * 为什么只取第一个 choice：当前场景下我们只请求一条回答（n 参数未设置或为 1），
     * OpenAI 兼容 API 的 choices 数组长度通常为 1。如果有多个 choices（如 n > 1），
     * 取第一个是安全的默认行为。
     * <p>
     * 逐级判空：JSON 响应可能因 Provider 后端 bug 而缺少 choices、message 或 content 字段
     * （如模型内部错误返回了空响应、或者 Provider 使用了非标准的响应格式），
     * 逐级判空比直接链式调用 choices.get(0).get("message").get("content") 更安全，
     * 避免了 NullPointerException 穿透到 tryFallback 层。
     *
     * @param root 已解析的 JSON 响应根节点
     * @return 提取的文本内容；解析路径上任何节点缺失时返回空字符串 ""
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
     * 从 API 响应中提取实际使用的模型名称。
     * <p>
     * 为什么需要同时检查配置值和 API 返回值：
     * 部分 OpenAI 兼容 API（如某些代理、网关）会在响应的 model 字段中返回实际调用的模型版本
     * （如请求 "deepseek-chat" 但响应返回 "deepseek-chat-20250601" 的具体版本号），
     * 此时用 API 返回值更精确。若响应中未返回 model 字段，则用配置值作为兜底——
     * 保证日志和成本统计中至少有一个可识别的模型名称。
     *
     * @param root 已解析的 JSON 响应根节点
     * @param configuredModel 配置文件中指定的模型名称（兜底值）
     * @return 实际使用的模型名称
     */
    private String extractModelName(JsonNode root, String configuredModel) {
        JsonNode modelNode = root.get("model");
        if (modelNode != null && !modelNode.isNull()) {
            return modelNode.asText();
        }
        return configuredModel;
    }

    /**
     * 判断指定 Provider 是否处于冷却期。
     * <p>
     * 冷却判断：从 cooldownMap 读取冷却结束时间，若当前时间早于该时间则 Provider 处于冷却中。
     * 若 cooldownMap 中没有该 Provider 的记录，说明从未被限流或冷却已过期但未被清理——
     * 后者无害（下次限流时会覆盖写，不累积垃圾记录）。
     * <p>
     * 为什么不在 isOnCooldown 中清理过期记录：ConcurrentHashMap 的 remove 与 containsKey/put
     * 形成两阶段操作，需要额外的原子性保障；且冷却记录的存留量级很小（等于 Provider 数量），
     * 内存占用可忽略，不值得引入清理逻辑的复杂度。
     *
     * @param providerName Provider 的唯一名称
     * @return true 表示该 Provider 当前处于冷却期，不可用于回退链
     */
    private boolean isOnCooldown(String providerName) {
        Instant cooldownEnd = cooldownMap.get(providerName);
        return cooldownEnd != null && Instant.now().isBefore(cooldownEnd);
    }

    /**
     * 将 Provider 标记为冷却状态：从当前时间开始，冷却指定的秒数。
     * <p>
     * 冷却计时起点是 Instant.now() 而非原始请求失败那一刻——因为在回退链中，
     * 一个 Provider 的限流异常发生后可能还有其他备用 Provider 需要尝试，等所有尝试结束后
     * Instant.now() 已经偏移了几百毫秒。这个偏移远小于冷却时间（通常 30-300 秒），
     * 对正确性无实质影响。
     * <p>
     * 冷却时长来自全局配置的 fallbackProperties.getCooldownSeconds()——所有 Provider 共用
     * 同一冷却时长。未来如需按 Provider 粒度设置不同的冷却时间，将配置改为 Map<String, Integer>
     * 即可。
     *
     * @param providerName Provider 的唯一名称
     */
    private void markCooldown(String providerName) {
        cooldownMap.put(providerName, Instant.now().plusSeconds(fallbackProperties.getCooldownSeconds()));
    }

    /**
     * 所有 Provider（含主 Provider 和所有备用 Provider）均不可用时抛出的异常。
     * <p>
     * 调用方（LLMService.chatWithUsage）捕获此异常后不应再尝试重试——此异常意味着
     * 所有可用的 LLM 通道已耗尽，继续重试只会延长用户等待时间。策略应是快速失败并通知用户。
     * <p>
     * 作为内部类而非独立文件：此异常是 LlmProviderManager 的专属概念，外部不应主动抛此异常；
     * 只有 LlmProviderManager 自己在本回退链全部走完仍失败时抛。放为内部类表达了这种归属关系。
     */
    public static class AllProvidersFailedException extends RuntimeException {
        public AllProvidersFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * HTTP 429 限流异常——从 RestClient 的 onStatus 拦截器转换为 Java 异常，
     * 以便在 tryFallback 的 catch 块中与 RestClientException 做差异化处理（冷却 vs 跳过）。
     * <p>
     * 为什么不用 Spring 的 HttpStatusCodeException：
     * RestClient 的 onStatus 回调本身不自动抛异常，需要手动 throw。
     * 自定义 RateLimitException 比通用的 HttpStatusCodeException 语义更明确——
     * catch 块一看即知此异常用于触发冷却逻辑，不需要再用 instanceof 检查 HTTP 状态码。
     */
    public static class RateLimitException extends RuntimeException {
        public RateLimitException(String message) {
            super(message);
        }
    }
}
