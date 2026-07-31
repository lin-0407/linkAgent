package com.link.linkagent.llm.config.service;

import com.link.linkagent.common.AesGcmUtil;
import com.link.linkagent.llm.DeepSeekThinkingOptionsFactory;
import com.link.linkagent.llm.config.mapper.UserLlmConfigMapper;
import com.link.linkagent.llm.config.model.UserLlmConfigRecord;
import com.link.linkagent.llm.config.model.UserLlmConfigResponse;
import com.link.linkagent.llm.config.model.UserLlmConfigSaveRequest;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用户 LLM/Embedding 配置服务（P1-4）。
 * <p>
 * 负责配置的 CRUD、API Key 加解密和连通性测试。
 * 所有 Key 在数据库中只存 AES-256-GCM 密文，明文仅在加解密瞬间存在于内存。
 * <p>
 * 为什么连通性测试不复用 LLMService？
 * LLMService 绑定的是系统全局 ChatClient（spring.ai.openai.* 配置），
 * 测试用户自定义 Key 需要用用户的 baseUrl + apiKey 创建临时连接，
 * 测试完成后立即丢弃，不缓存明文也不污染全局 ChatClient 状态。
 */
@Service
public class UserLlmConfigService {

    private static final Logger log = LoggerFactory.getLogger(UserLlmConfigService.class);

    /** 默认用户标识 */
    private static final String DEFAULT_USER_ID = "default";

    /** AES-256-GCM 密钥（Base64 编码的 32 字节），从配置文件读取 */
    private final String aesKey;

    private final UserLlmConfigMapper mapper;

    /**
     * 系统默认 LLM Base URL（来自 spring.ai.openai.base-url），
     * 用于用户未配置自定义 URL 时回退到系统默认值进行连通性测试。
     */
    private final String defaultLlmBaseUrl;

    /**
     * 系统默认 LLM 模型名（来自 spring.ai.openai.chat.options.model），
     * 用于用户未配置自定义模型名时回退，避免连通性测试误用 Spring AI 的内置 OpenAI 默认模型。
     */
    private final String defaultLlmModelName;

    /** 用户配置测试也复用全局 DeepSeek Flash 思考参数，避免测试请求与正式请求行为不一致。 */
    private final DeepSeekThinkingOptionsFactory deepSeekThinkingOptionsFactory;

    public UserLlmConfigService(
            UserLlmConfigMapper mapper,
            @Value("${linkagent.secret.aes-key:}") String aesKey,
            @Value("${spring.ai.openai.base-url:}") String defaultLlmBaseUrl,
            @Value("${spring.ai.openai.chat.options.model:}") String defaultLlmModelName,
            DeepSeekThinkingOptionsFactory deepSeekThinkingOptionsFactory) {
        this.mapper = mapper;
        this.aesKey = aesKey;
        this.defaultLlmBaseUrl = defaultLlmBaseUrl;
        this.defaultLlmModelName = defaultLlmModelName;
        this.deepSeekThinkingOptionsFactory = deepSeekThinkingOptionsFactory;
        if (aesKey == null || aesKey.isBlank()) {
            log.warn("linkagent.secret.aes-key 未配置——API Key 加密/解密功能不可用。" +
                    "生产环境必须通过 LINKAGENT_AES_KEY 环境变量注入 Base64 编码的 32 字节密钥。");
        }
    }

    /**
     * 列出用户的所有 LLM/Embedding 配置。
     * <p>
     * 返回的 API Key 字段为脱敏值（如 sk-****j8x2），Base URL 和模型名明文返回。
     *
     * @param userId 用户标识，为空时使用 "default"
     * @return 配置列表（按 provider 字母序）
     */
    public List<UserLlmConfigResponse> listConfigs(String userId) {
        String resolvedUserId = resolveUserId(userId);
        return mapper.listByUser(resolvedUserId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 保存或更新一条 LLM/Embedding 配置。
     * <p>
     * 按 (user_id, provider) 唯一约束 upsert：同用户同 provider 只有一条配置。
     * API Key 传入明文 → 加密后存 _enc 列；传入空串或 null → 不修改已有 Key。
     *
     * @param userId  用户标识
     * @param request 保存请求
     * @return 保存后的配置（Key 脱敏）
     */
    public UserLlmConfigResponse saveConfig(String userId, UserLlmConfigSaveRequest request) {
        String resolvedUserId = resolveUserId(userId);
        ensureAesKeyAvailable();

        UserLlmConfigRecord record = new UserLlmConfigRecord();
        record.setConfigId(UUID.randomUUID().toString());
        record.setUserId(resolvedUserId);
        record.setProvider(request.provider().trim().toUpperCase());
        record.setLlmBaseUrl(TextUtil.trimToNull(request.llmBaseUrl()));
        // 只有非空才加密写入，空串保留数据库已有值（upsert SQL 中用 IF(VALUES(...) IS NULL) 控制）
        record.setLlmApiKeyEnc(encryptIfNotEmpty(request.llmApiKey()));
        record.setLlmModelName(TextUtil.trimToNull(request.llmModelName()));
        record.setEmbeddingBaseUrl(TextUtil.trimToNull(request.embeddingBaseUrl()));
        record.setEmbeddingApiKeyEnc(encryptIfNotEmpty(request.embeddingApiKey()));
        record.setEmbeddingModelName(TextUtil.trimToNull(request.embeddingModelName()));
        LocalDateTime now = LocalDateTime.now();
        record.setCreateTime(now);
        record.setUpdateTime(now);

        mapper.upsert(record);

        // 回读确认（拿到 upsert 后的持久化数据，包括已有 Key 的密文）
        return mapper.findByConfigId(record.getConfigId())
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "配置保存后无法回读，请检查数据库连接"));
    }

    /**
     * 软删除一条用户配置。
     * <p>
     * 校验 configId 归属当前用户，防止横向越权删除他人配置。
     *
     * @param userId   用户标识
     * @param configId 配置唯一标识
     */
    public void deleteConfig(String userId, String configId) {
        String resolvedUserId = resolveUserId(userId);
        int affected = mapper.softDelete(configId, resolvedUserId);
        if (affected == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "配置不存在或已删除（configId=" + configId + "）");
        }
    }

    /**
     * 测试 LLM 连通性：用用户配置的 Base URL + API Key 发起一次简单的 LLM 调用。
     * <p>
     * 测试完成后立即丢弃临时连接，不缓存明文 Key 也不影响系统全局 ChatClient。
     *
     * @param userId   用户标识
     * @param configId 配置唯一标识
     * @return { success: bool, elapsedMs: long, response?: string, error?: string }
     */
    public Map<String, Object> testConnectivity(String userId, String configId) {
        String resolvedUserId = resolveUserId(userId);
        ensureAesKeyAvailable();

        UserLlmConfigRecord config = mapper.findByConfigId(configId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "配置不存在"));
        if (!resolvedUserId.equals(config.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作此配置");
        }

        // 解密 LLM API Key
        String llmKey = AesGcmUtil.decrypt(config.getLlmApiKeyEnc(), aesKey);
        if (llmKey == null || llmKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "此配置未设置 LLM API Key，无法测试连接。请先保存 LLM API Key 后再测试。");
        }
        String baseUrl = config.getLlmBaseUrl() != null ? config.getLlmBaseUrl() : TextUtil.trimToNull(defaultLlmBaseUrl);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "LLM Base URL 未配置（既无用户自定义也无系统默认值），无法测试连接。");
        }
        String modelName = config.getLlmModelName() != null ? config.getLlmModelName() : TextUtil.trimToNull(defaultLlmModelName);
        if (modelName == null || modelName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "LLM 模型名称未配置（既无用户自定义也无系统默认值），无法测试连接。");
        }

        long startMs = System.currentTimeMillis();
        try {
            // 创建临时 API 客户端和 ChatClient——测试完成后立即释放
            // Spring AI 1.1.4 移除了旧的短构造函数，必须通过 builder 补齐默认重试、观测和工具调用配置。
            OpenAiApi tempApi = OpenAiApi.builder()
                    .baseUrl(baseUrl)
                    .apiKey(llmKey)
                    .build();
            OpenAiChatOptions tempOptions = deepSeekThinkingOptionsFactory.optionsForModel(modelName);
            OpenAiChatModel tempModel = OpenAiChatModel.builder()
                    .openAiApi(tempApi)
                    .defaultOptions(tempOptions)
                    .build();
            ChatClient tempClient = ChatClient.builder(tempModel).build();

            String response = tempClient.prompt()
                    .user("回复 OK")
                    .call()
                    .content();

            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("LLM 连通性测试成功，configId={}, provider={}, elapsedMs={}",
                    configId, config.getProvider(), elapsedMs);
            return Map.of("success", true, "elapsedMs", elapsedMs, "response",
                    response != null ? response : "OK");
        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.warn("LLM 连通性测试失败，configId={}, provider={}, elapsedMs={}, error={}",
                    configId, config.getProvider(), elapsedMs, e.getMessage());
            return Map.of("success", false, "elapsedMs", elapsedMs, "error",
                    e.getMessage() != null ? e.getMessage() : "未知错误");
        }
    }

    // ── 内部方法 ──

    /** 解析用户 ID：空白时回退到 "default" */
    private String resolveUserId(String userId) {
        return userId == null || userId.isBlank() ? DEFAULT_USER_ID : userId.trim();
    }

    /** 确认 AES 密钥已配置，未配置时抛出明确异常 */
    private void ensureAesKeyAvailable() {
        if (aesKey == null || aesKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "服务端 AES 加密密钥未配置（linkagent.secret.aes-key），无法执行此操作。" +
                    "请联系管理员配置 LINKAGENT_AES_KEY 环境变量。");
        }
    }

    /** 明文非空时才加密，空串返回 null（upsert SQL 中 null 表示不修改已有 Key） */
    private String encryptIfNotEmpty(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return null;
        }
        return AesGcmUtil.encrypt(plainText.trim(), aesKey);
    }

    /** 将 Record 转换为响应 DTO：解密 Key → 脱敏 */
    private UserLlmConfigResponse toResponse(UserLlmConfigRecord record) {
        return new UserLlmConfigResponse(
                record.getConfigId(),
                record.getUserId(),
                record.getProvider(),
                record.getLlmBaseUrl(),
                maskKey(record.getLlmApiKeyEnc()),
                record.getLlmModelName(),
                record.getEmbeddingBaseUrl(),
                maskKey(record.getEmbeddingApiKeyEnc()),
                record.getEmbeddingModelName(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }

    /** 解密后脱敏；解密失败时返回 "***" 表示数据异常 */
    private String maskKey(String encryptedKey) {
        if (encryptedKey == null || encryptedKey.isEmpty()) {
            return null;
        }
        try {
            return AesGcmUtil.maskKey(AesGcmUtil.decrypt(encryptedKey, aesKey));
        } catch (Exception e) {
            log.warn("API Key 解密失败，返回占位脱敏值。可能是密钥已轮换。error={}", e.getMessage());
            return "***";
        }
    }

}
