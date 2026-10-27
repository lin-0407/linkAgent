package com.link.linkagent.settings.service;

import com.link.linkagent.creator.creation.aivideo.service.CreationVideoProperties;
import com.link.linkagent.creator.creation.billing.BillingExchangeRate;
import com.link.linkagent.creator.creation.service.CreationBillingProperties;
import com.link.linkagent.creator.creation.service.CreationCapabilityService;
import com.link.linkagent.creator.creation.voice.service.CreationVoiceProperties;
import com.link.linkagent.creator.feedback.config.CreatorFeedbackRagProperties;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.knowledge.config.KnowledgeRagProperties;
import com.link.linkagent.llm.DeepSeekThinkingProperties;
import com.link.linkagent.llm.LlmCallGuardProperties;
import com.link.linkagent.memory.SummaryMemoryProperties;
import com.link.linkagent.settings.dto.BillingSettingsResponse;
import com.link.linkagent.settings.dto.PriceRangeResponse;
import com.link.linkagent.settings.dto.ReadonlySettingResponse;
import com.link.linkagent.settings.dto.RuntimeToggleResponse;
import com.link.linkagent.settings.dto.RuntimeValueResponse;
import com.link.linkagent.settings.dto.SettingsStatusResponse;
import com.link.linkagent.settings.mapper.RuntimeSettingMapper;
import com.link.linkagent.settings.model.RuntimeSettingKey;
import com.link.linkagent.settings.model.RuntimeSettingRecord;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 运行期设置服务。
 * <p>
 * 读取策略是“配置默认值 + DB 覆盖值”：首次未写 DB 时沿用 application.yml；设置页修改后 DB 值覆盖配置默认值。
 * 这样能兼顾现有配置体系和运行期热切换，同时避免直接改 Environment 造成“看似改了、实际 Bean 不重装配”的误导。
 */
@Service
public class RuntimeSettingService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeSettingService.class);

    private final RuntimeSettingMapper runtimeSettingMapper;
    private final LlmCallGuardProperties llmCallGuardProperties;
    private final SummaryMemoryProperties summaryMemoryProperties;
    private final KnowledgeRagProperties knowledgeRagProperties;
    private final CreatorFeedbackRagProperties creatorFeedbackRagProperties;
    private final DeepSeekThinkingProperties deepSeekThinkingProperties;
    private final CreationCapabilityService capabilities;
    private final CreationVoiceProperties voiceProperties;
    private final CreationVideoProperties videoProperties;
    private final CreatorMediaProperties mediaProperties;
    private final CreationBillingProperties billingProperties;
    private final BillingExchangeRate exchangeRate;
    private final Environment environment;

    public RuntimeSettingService(RuntimeSettingMapper runtimeSettingMapper,
                                 LlmCallGuardProperties llmCallGuardProperties,
                                 SummaryMemoryProperties summaryMemoryProperties,
                                 KnowledgeRagProperties knowledgeRagProperties,
                                 CreatorFeedbackRagProperties creatorFeedbackRagProperties,
                                 DeepSeekThinkingProperties deepSeekThinkingProperties,
                                 CreationCapabilityService capabilities,
                                 CreationVoiceProperties voiceProperties,
                                 CreationVideoProperties videoProperties,
                                 CreatorMediaProperties mediaProperties,
                                 CreationBillingProperties billingProperties,
                                 BillingExchangeRate exchangeRate,
                                 Environment environment) {
        this.runtimeSettingMapper = runtimeSettingMapper;
        this.llmCallGuardProperties = llmCallGuardProperties;
        this.summaryMemoryProperties = summaryMemoryProperties;
        this.knowledgeRagProperties = knowledgeRagProperties;
        this.creatorFeedbackRagProperties = creatorFeedbackRagProperties;
        this.deepSeekThinkingProperties = deepSeekThinkingProperties;
        this.capabilities = capabilities;
        this.voiceProperties = voiceProperties;
        this.videoProperties = videoProperties;
        this.mediaProperties = mediaProperties;
        this.billingProperties = billingProperties;
        this.exchangeRate = exchangeRate;
        this.environment = environment;
    }

    public SettingsStatusResponse status() {
        return new SettingsStatusResponse(dynamicToggles(), dynamicValues(), readonlySettings(),
                productionToggles(), billing());
    }

    public BillingSettingsResponse refreshBilling() {
        exchangeRate.refresh();
        return billing();
    }

    public void updateToggle(String settingKey, boolean enabled) {
        RuntimeSettingKey key = RuntimeSettingKey.fromKey(settingKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持动态修改的设置项: " + settingKey));
        if (key == RuntimeSettingKey.DEEPSEEK_REASONING_EFFORT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该设置项需要使用枚举值接口修改: " + settingKey);
        }
        // 媒体对象存储、上传与渲染在启动期装配，不能在运行期凭空创建；只允许关闭或重新打开已装配的能力。
        if (key == RuntimeSettingKey.CREATION_MEDIA_ENABLED && enabled && !capabilities.mediaInfrastructureEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "媒体基础设施在启动时未启用；请配置对象存储并设置 creator.media.enabled=true 后重启一次");
        }
        if ((key == RuntimeSettingKey.CREATION_VOICE_ENABLED || key == RuntimeSettingKey.CREATION_VIDEO_ENABLED)
                && enabled && !capabilities.mediaEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    capabilities.mediaInfrastructureEnabled()
                            ? "媒体付费分析未启用，请先打开媒体开关"
                            : "媒体基础设施在启动时未启用；需要配置对象存储并重启一次后才能打开配音或 AI 视频");
        }
        runtimeSettingMapper.upsert(key.key(), Boolean.toString(enabled), key.description());
    }

    public void updateValue(String settingKey, String value) {
        RuntimeSettingKey key = RuntimeSettingKey.fromKey(settingKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持动态修改的设置项: " + settingKey));
        if (key != RuntimeSettingKey.DEEPSEEK_REASONING_EFFORT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该设置项不是可选值设置: " + settingKey);
        }
        String normalized = DeepSeekThinkingProperties.normalizeReasoningEffort(value);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DeepSeek 思考强度只支持 low、high、max");
        }
        runtimeSettingMapper.upsert(key.key(), normalized, key.description());
    }

    public boolean isLlmGuardEnabled() {
        return isEnabled(RuntimeSettingKey.LLM_GUARD_ENABLED, llmCallGuardProperties.isEnabled());
    }

    public boolean isSummaryMemoryEnabled() {
        return isEnabled(RuntimeSettingKey.SUMMARY_MEMORY_ENABLED, summaryMemoryProperties.enabled());
    }

    public boolean isStructuredKernelEnabled() {
        return isEnabled(RuntimeSettingKey.AGENT_STRUCTURED_KERNEL_ENABLED, agentStructuredKernelDefaultEnabled());
    }

    public boolean isPrePublishAgentEnabled() {
        return isEnabled(RuntimeSettingKey.PRE_PUBLISH_AGENT_ENABLED, prePublishAgentDefaultEnabled());
    }

    public boolean isKnowledgeRerankEnabled() {
        return isEnabled(RuntimeSettingKey.KNOWLEDGE_RAG_RERANK_ENABLED, knowledgeRagProperties.getRerank().isEnabled());
    }

    public boolean isCreatorFeedbackRagEnabled() {
        return isEnabled(RuntimeSettingKey.CREATOR_FEEDBACK_RAG_ENABLED, creatorFeedbackRagProperties.isEnabled());
    }

    public boolean isDeepSeekThinkingEnabled() {
        return isEnabled(RuntimeSettingKey.DEEPSEEK_THINKING_ENABLED, deepSeekThinkingProperties.isEnabled());
    }

    public String getDeepSeekReasoningEffort() {
        String defaultValue = deepSeekThinkingProperties.resolvedReasoningEffort();
        try {
            return runtimeSettingMapper.findByKey(RuntimeSettingKey.DEEPSEEK_REASONING_EFFORT.key())
                    .map(RuntimeSettingRecord::getSettingValue)
                    .map(DeepSeekThinkingProperties::normalizeReasoningEffort)
                    .filter(value -> value != null)
                    .orElse(defaultValue);
        } catch (DataAccessException exception) {
            log.warn("读取运行期设置失败，回退配置默认值。key={}", RuntimeSettingKey.DEEPSEEK_REASONING_EFFORT.key(), exception);
            return defaultValue;
        }
    }

    private List<RuntimeToggleResponse> dynamicToggles() {
        Map<RuntimeSettingKey, Boolean> defaults = dynamicDefaults();
        return List.of(
                toToggle(RuntimeSettingKey.LLM_GUARD_ENABLED, defaults),
                toToggle(RuntimeSettingKey.SUMMARY_MEMORY_ENABLED, defaults),
                toToggle(RuntimeSettingKey.AGENT_STRUCTURED_KERNEL_ENABLED, defaults),
                toToggle(RuntimeSettingKey.PRE_PUBLISH_AGENT_ENABLED, defaults),
                toToggle(RuntimeSettingKey.KNOWLEDGE_RAG_RERANK_ENABLED, defaults),
                toToggle(RuntimeSettingKey.CREATOR_FEEDBACK_RAG_ENABLED, defaults),
                toToggle(RuntimeSettingKey.DEEPSEEK_THINKING_ENABLED, defaults)
        );
    }

    private List<RuntimeValueResponse> dynamicValues() {
        return List.of(new RuntimeValueResponse(
                RuntimeSettingKey.DEEPSEEK_REASONING_EFFORT.key(),
                RuntimeSettingKey.DEEPSEEK_REASONING_EFFORT.displayName(),
                getDeepSeekReasoningEffort(),
                List.of("low", "high", "max"),
                RuntimeSettingKey.DEEPSEEK_REASONING_EFFORT.description()
        ));
    }

    private RuntimeToggleResponse toToggle(RuntimeSettingKey key, Map<RuntimeSettingKey, Boolean> defaults) {
        return new RuntimeToggleResponse(
                key.key(),
                key.displayName(),
                isEnabled(key, defaults.getOrDefault(key, false)),
                key.description(),
                false,
                ""
        );
    }

    private List<RuntimeToggleResponse> productionToggles() {
        boolean mediaStartup = capabilities.mediaInfrastructureEnabled();
        boolean media = capabilities.mediaEnabled();
        String mediaLock = mediaStartup ? ""
                : "媒体基础设施在启动时未启用；需要配置对象存储并设置 creator.media.enabled=true 后重启一次";
        String dependentLock = media ? "" : "媒体付费分析未启用，配音和 AI 视频依赖私有对象存储";
        return List.of(
                new RuntimeToggleResponse(RuntimeSettingKey.CREATION_VOICE_ENABLED.key(),
                        RuntimeSettingKey.CREATION_VOICE_ENABLED.displayName(),
                        capabilities.voiceEnabled(), RuntimeSettingKey.CREATION_VOICE_ENABLED.description(),
                        !media, dependentLock),
                new RuntimeToggleResponse(RuntimeSettingKey.CREATION_VIDEO_ENABLED.key(),
                        RuntimeSettingKey.CREATION_VIDEO_ENABLED.displayName(),
                        capabilities.videoEnabled(), RuntimeSettingKey.CREATION_VIDEO_ENABLED.description(),
                        !media, dependentLock),
                new RuntimeToggleResponse(RuntimeSettingKey.CREATION_MEDIA_ENABLED.key(),
                        RuntimeSettingKey.CREATION_MEDIA_ENABLED.displayName(),
                        media, RuntimeSettingKey.CREATION_MEDIA_ENABLED.description(),
                        !mediaStartup, mediaLock)
        );
    }

    private BillingSettingsResponse billing() {
        BillingExchangeRate.Rate rate = exchangeRate.current();
        BigDecimal fx = rate.usdToCny();
        String note = rate.source() == BillingExchangeRate.Source.CONFIG_FALLBACK
                ? "未能获取公开汇率，当前使用部署配置的折算率；可在设置中重新获取"
                : "公开参考汇率，通常每日更新；用于估算和价格快照，不代表银行成交价或供应商账单";
        return new BillingSettingsResponse(fx, rate.source().name(),
                rate.fetchedAt() == null ? "尚未获取" : DateTimeFormatter.ISO_INSTANT.format(rate.fetchedAt()),
                rate.source() != BillingExchangeRate.Source.LIVE, note,
                List.of(
                        range("voice", "配音 TTS", "每 1000 字符",
                                voiceProperties.getUsdPerMillionCharacters().divide(new BigDecimal("1000"),
                                        12, RoundingMode.HALF_UP), fx),
                        range("asr", "语音转写", "每分钟",
                                mediaProperties.getProcessing().getAsrUsdPerSecond().multiply(BigDecimal.valueOf(60)), fx),
                        range("video", "AI 视频生成", "每 " + minimumVideoSeconds() + " 秒",
                                videoProperties.getUsdPerSecond().multiply(BigDecimal.valueOf(minimumVideoSeconds())), fx),
                        range("video_understanding", "视频理解", "每次完整试映",
                                billingProperties.getVideoUsdUpperBound(), fx),
                        range("segment_review", "重点片段复核", "每个片段",
                                billingProperties.getSegmentReviewUsdUpperBound(), fx),
                        range("storyboard_text", "故事板文本", "每次（按 2 万输入 / 4 千输出估算）",
                                billingProperties.getTextInputUsdPerMillionTokens().multiply(BigDecimal.valueOf(20000))
                                        .add(billingProperties.getTextOutputUsdPerMillionTokens().multiply(BigDecimal.valueOf(4000)))
                                        .divide(new BigDecimal("1000000"), 12, RoundingMode.HALF_UP), fx)
                ));
    }

    private PriceRangeResponse range(String key, String name, String unit, BigDecimal usd, BigDecimal fx) {
        BigDecimal low = usd.multiply(fx).setScale(4, RoundingMode.HALF_UP);
        BigDecimal high = low.multiply(new BigDecimal("1.5")).setScale(4, RoundingMode.HALF_UP);
        return new PriceRangeResponse(key, name, unit, low, high, "配置单价 × 当前汇率；上限按 1.5 倍估算");
    }

    private int minimumVideoSeconds() {
        return videoProperties.durations().stream().min(Integer::compareTo).orElse(5);
    }

    private Map<RuntimeSettingKey, Boolean> dynamicDefaults() {
        Map<RuntimeSettingKey, Boolean> defaults = new EnumMap<>(RuntimeSettingKey.class);
        defaults.put(RuntimeSettingKey.LLM_GUARD_ENABLED, llmCallGuardProperties.isEnabled());
        defaults.put(RuntimeSettingKey.SUMMARY_MEMORY_ENABLED, summaryMemoryProperties.enabled());
        defaults.put(RuntimeSettingKey.AGENT_STRUCTURED_KERNEL_ENABLED, agentStructuredKernelDefaultEnabled());
        defaults.put(RuntimeSettingKey.PRE_PUBLISH_AGENT_ENABLED, prePublishAgentDefaultEnabled());
        defaults.put(RuntimeSettingKey.KNOWLEDGE_RAG_RERANK_ENABLED, knowledgeRagProperties.getRerank().isEnabled());
        defaults.put(RuntimeSettingKey.CREATOR_FEEDBACK_RAG_ENABLED, creatorFeedbackRagProperties.isEnabled());
        defaults.put(RuntimeSettingKey.DEEPSEEK_THINKING_ENABLED, deepSeekThinkingProperties.isEnabled());
        return defaults;
    }

    private List<ReadonlySettingResponse> readonlySettings() {
        return List.of(
                new ReadonlySettingResponse(
                        "knowledge.rag.enabled",
                        "知识库 RAG 主开关",
                        Boolean.toString(knowledgeRagProperties.isEnabled()),
                        "启动期决定知识库工具和向量库初始化，修改配置后需重启"),
                new ReadonlySettingResponse(
                        "knowledge.rag.hybrid.enabled",
                        "知识库 hybrid 主开关",
                        Boolean.toString(knowledgeRagProperties.getHybrid().isEnabled()),
                        "启动期决定 hybrid 客户端初始化，修改配置后需重启"),
                new ReadonlySettingResponse(
                        "agent.memory.short-term.store-type",
                        "短期记忆存储",
                        environment.getProperty("agent.memory.short-term.store-type", "memory"),
                        "memory/redis 是启动期 Bean 二选一，修改配置后需重启")
        );
    }

    private boolean agentStructuredKernelDefaultEnabled() {
        return Boolean.parseBoolean(environment.getProperty("agent.kernel.structured.enabled", "true"));
    }

    private boolean prePublishAgentDefaultEnabled() {
        return Boolean.parseBoolean(environment.getProperty("creator.pre-publish.agent.enabled", "true"));
    }

    private boolean isEnabled(RuntimeSettingKey key, boolean defaultValue) {
        try {
            return runtimeSettingMapper.findByKey(key.key())
                    .map(RuntimeSettingRecord::getSettingValue)
                    .map(value -> parseBoolean(key, value, defaultValue))
                    .orElse(defaultValue);
        } catch (DataAccessException exception) {
            // init.sql 未执行或数据库短暂异常时，业务调用不能因为设置表不可读而整体失败；写接口会显式暴露错误。
            log.warn("读取运行期设置失败，回退配置默认值。key={}", key.key(), exception);
            return defaultValue;
        }
    }

    private boolean parseBoolean(RuntimeSettingKey key, String value, boolean defaultValue) {
        String normalized = TextUtil.trimToDefault(value, "").toLowerCase();
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }
        log.warn("运行期设置值非法，回退配置默认值。key={}, value={}", key.key(), value);
        return defaultValue;
    }
}
