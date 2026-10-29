package com.link.linkagent.llm;

import com.link.linkagent.common.AesGcmUtil;
import com.link.linkagent.llm.config.mapper.UserLlmConfigMapper;
import com.link.linkagent.llm.config.model.UserLlmConfigRecord;
import com.link.linkagent.settings.mapper.RuntimeSettingMapper;
import com.link.linkagent.settings.model.RuntimeSettingRecord;
import com.link.linkagent.util.TextUtil;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 单工作台的文本模型选择和客户端入口；向量模型继续由原部署配置负责。 */
@Component
public class RuntimeTextModelClient {
    public static final String ACTIVE_CONFIG_KEY = "llm.text.active-config-id";
    private final UserLlmConfigMapper configs;
    private final RuntimeSettingMapper settings;
    private final String aesKey;
    private final String defaultBaseUrl;
    private final String defaultModel;
    private final OpenAiChatModel configuredModel;
    private final OpenAiApi configuredApi;
    private final RestClient.Builder rest;
    private final DeepSeekThinkingOptionsFactory thinking;
    private final long multiTimeoutSeconds;
    @Value("${spring.ai.openai.api-key:}")
    private String defaultApiKey = "";
    private String cachedRevision;
    private final Map<String, OpenAiChatModel> models = new HashMap<>();

    public RuntimeTextModelClient(UserLlmConfigMapper configs, RuntimeSettingMapper settings,
            @Value("${linkagent.secret.aes-key:}") String aesKey,
            @Value("${spring.ai.openai.base-url:}") String defaultBaseUrl,
            @Value("${spring.ai.openai.chat.options.model:}") String defaultModel,
            OpenAiChatModel configuredModel, OpenAiApi configuredApi, RestClient.Builder rest,
            DeepSeekThinkingOptionsFactory thinking,
            @Value("${agent.multi.worker-timeout-seconds:120}") long multiTimeoutSeconds) {
        this.configs = configs; this.settings = settings; this.aesKey = aesKey;
        this.defaultBaseUrl = defaultBaseUrl; this.defaultModel = defaultModel;
        this.configuredModel = configuredModel; this.configuredApi = configuredApi; this.rest = rest;
        this.thinking = thinking; this.multiTimeoutSeconds = multiTimeoutSeconds;
    }

    public String activeConfigId() {
        return settings.findByKey(ACTIVE_CONFIG_KEY).map(RuntimeSettingRecord::getSettingValue)
                .map(TextUtil::trimToNull).orElse(null);
    }

    public Optional<TextConfig> active() {
        String id = activeConfigId();
        if (id == null) return Optional.empty();
        return configs.findByConfigId(id).map(record -> {
            if (!"default".equals(record.getUserId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前文本模型不属于默认工作台，请重新选择");
            }
            return resolve(record);
        });
    }

    public TextConfig resolve(UserLlmConfigRecord record) {
        if (TextUtil.isBlank(record.getLlmApiKeyEnc())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "此配置没有文本模型密钥，不能设为当前文本模型");
        }
        String baseUrl = TextUtil.trimToDefault(record.getLlmBaseUrl(), defaultBaseUrl);
        String model = TextUtil.trimToDefault(record.getLlmModelName(), defaultModel);
        if (TextUtil.isBlank(baseUrl) || TextUtil.isBlank(model)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文本模型地址和模型名称必须配置或具有部署默认值");
        }
        try {
            var uri = java.net.URI.create(baseUrl);
            if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文本模型地址须为 HTTP 或 HTTPS 地址，不能包含密钥、查询参数或片段");
        }
        final String key;
        try {
            key = AesGcmUtil.decrypt(record.getLlmApiKeyEnc(), aesKey);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "文本模型密钥无法解密，请重新保存密钥");
        }
        if (TextUtil.isBlank(key)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文本模型密钥不能为空");
        return new TextConfig(record.getConfigId(), record.getProvider(), baseUrl, model, key,
                record.getLlmApiKeyEnc());
    }

    public void activate(String userId, String id) {
        checkDefaultWorkspace(userId);
        UserLlmConfigRecord record = configs.findByConfigId(id)
                .filter(config -> "default".equals(config.getUserId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "配置不存在"));
        resolve(record);
        settings.upsert(ACTIVE_CONFIG_KEY, id, "当前工作台显式选择的文本模型配置；空值使用部署配置");
    }

    public void useEnvironment(String userId) {
        checkDefaultWorkspace(userId);
        settings.upsert(ACTIVE_CONFIG_KEY, "", "当前工作台显式选择的文本模型配置；空值使用部署配置");
    }

    public void forgetDeletedConfig(String configId) {
        settings.clearIfMatches(ACTIVE_CONFIG_KEY, configId);
    }

    private void checkDefaultWorkspace(String userId) {
        if (userId != null && !userId.isBlank() && !"default".equals(userId.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前部署为单工作台，请使用默认工作台选择文本模型");
        }
    }

    public CurrentTextModel current() {
        return active().map(config -> new CurrentTextModel(config.configId(), "SAVED_CONFIG", config.provider(),
                        config.modelName(), config.baseUrl()))
                .orElseGet(() -> new CurrentTextModel(null, "ENVIRONMENT", null, defaultModel, defaultBaseUrl));
    }

    /** 保留输出上限、工具等请求选项，替换当前模型及其供应商专用思考参数。 */
    public OpenAiChatOptions adaptOptions(OpenAiChatOptions options) {
        Optional<TextConfig> selected = active();
        if (selected.isEmpty()) return options;
        OpenAiChatOptions adapted = options == null ? OpenAiChatOptions.builder().build() : options.copy();
        OpenAiChatOptions selectedOptions = thinking.optionsForModel(selected.get().modelName());
        adapted.setModel(selected.get().modelName());
        adapted.setExtraBody(selectedOptions.getExtraBody());
        adapted.setReasoningEffort(selectedOptions.getReasoningEffort());
        return adapted;
    }

    public Optional<ChatClient> client(boolean singleAttempt, boolean multiAgent, boolean strict) {
        return model(singleAttempt, multiAgent, strict).map(ChatClient::create);
    }

    public Optional<OpenAiChatModel> model(boolean singleAttempt, boolean multiAgent, boolean strict) {
        ModelSnapshot snapshot = snapshot(singleAttempt, multiAgent, strict);
        return snapshot.environment() ? Optional.empty() : Optional.of(snapshot.model());
    }

    /** 一次解析固定客户端和身份，费用授权校验后不能再读取另一份活动配置。 */
    public ModelSnapshot snapshot(boolean singleAttempt, boolean multiAgent, boolean strict) {
        Optional<TextConfig> selected = active();
        if (selected.isEmpty()) {
            return new ModelSnapshot(configuredModel,
                    digest("ENVIRONMENT\n" + defaultBaseUrl + "\n" + defaultModel + "\n" + defaultApiKey), true);
        }
        TextConfig config = selected.get();
        return new ModelSnapshot(model(config, singleAttempt, multiAgent, strict), fingerprint(config), false);
    }

    public String configurationFingerprint() {
        Optional<TextConfig> selected = active();
        return selected.map(this::fingerprint).orElseGet(() ->
                digest("ENVIRONMENT\n" + defaultBaseUrl + "\n" + defaultModel + "\n" + defaultApiKey));
    }

    private String fingerprint(TextConfig config) {
        // 只持久化摘要；供应商、端点和密钥的变化都需要重新确认原有费用估算。
        return digest("SAVED_CONFIG\n" + config.configId() + "\n" + config.provider() + "\n"
                + config.baseUrl() + "\n" + config.modelName() + "\n" + config.encryptedKey());
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持配置摘要", exception);
        }
    }

    private synchronized OpenAiChatModel model(TextConfig config, boolean singleAttempt, boolean multiAgent, boolean strict) {
        // 密文参与版本判断，编辑密钥后下一次请求立即重建；只缓存当前选择，避免累积失效客户端。
        String revision = config.configId() + "\n" + config.baseUrl() + "\n" + config.modelName() + "\n" + config.encryptedKey();
        if (!Objects.equals(cachedRevision, revision)) { models.clear(); cachedRevision = revision; }
        String mode = singleAttempt + ":" + multiAgent + ":" + strict;
        return models.computeIfAbsent(mode, ignored -> {
            var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            var requestFactory = new JdkClientHttpRequestFactory(http);
            if (multiAgent) requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(1, multiTimeoutSeconds)));
            OpenAiApi api = configuredApi.mutate().baseUrl(config.baseUrl()).apiKey(config.apiKey())
                    .completionsPath(strict ? "/beta/chat/completions" : "/v1/chat/completions")
                    .restClientBuilder(rest.clone().requestFactory(requestFactory))
                    .responseErrorHandler(new DefaultResponseErrorHandler()).build();
            OpenAiChatOptions defaults = singleAttempt && !strict
                    ? OpenAiChatOptions.builder().model(config.modelName())
                        .temperature(configuredModel.getDefaultOptions().getTemperature())
                        .internalToolExecutionEnabled(false).build()
                    : thinking.optionsForModel(config.modelName());
            var builder = configuredModel.mutate().openAiApi(api).defaultOptions(defaults);
            if (singleAttempt || multiAgent) {
                builder.retryTemplate(RetryTemplate.builder().maxAttempts(1).noBackoff().build());
                builder.toolExecutionEligibilityPredicate((options, response) -> false);
            }
            return builder.build();
        });
    }

    public record CurrentTextModel(String configId, String source, String provider, String modelName, String baseUrl) {}

    public record ModelSnapshot(OpenAiChatModel model, String configurationFingerprint, boolean environment) {}

    public record TextConfig(String configId, String provider, String baseUrl, String modelName,
                             String apiKey, String encryptedKey) {
        public boolean supportsStrict() { return "DEEPSEEK".equalsIgnoreCase(provider); }
        @Override public String toString() { return "TextConfig[configId=" + configId + ", modelName=" + modelName + "]"; }
    }
}
