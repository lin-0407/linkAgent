package com.link.linkagent.settings.service;

import com.link.linkagent.creator.feedback.config.CreatorFeedbackRagProperties;
import com.link.linkagent.knowledge.config.KnowledgeRagProperties;
import com.link.linkagent.llm.LlmCallGuardProperties;
import com.link.linkagent.memory.SummaryMemoryProperties;
import com.link.linkagent.settings.dto.ReadonlySettingResponse;
import com.link.linkagent.settings.dto.RuntimeToggleResponse;
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
    private final Environment environment;

    public RuntimeSettingService(RuntimeSettingMapper runtimeSettingMapper,
                                 LlmCallGuardProperties llmCallGuardProperties,
                                 SummaryMemoryProperties summaryMemoryProperties,
                                 KnowledgeRagProperties knowledgeRagProperties,
                                 CreatorFeedbackRagProperties creatorFeedbackRagProperties,
                                 Environment environment) {
        this.runtimeSettingMapper = runtimeSettingMapper;
        this.llmCallGuardProperties = llmCallGuardProperties;
        this.summaryMemoryProperties = summaryMemoryProperties;
        this.knowledgeRagProperties = knowledgeRagProperties;
        this.creatorFeedbackRagProperties = creatorFeedbackRagProperties;
        this.environment = environment;
    }

    public SettingsStatusResponse status() {
        return new SettingsStatusResponse(dynamicToggles(), readonlySettings());
    }

    public void updateToggle(String settingKey, boolean enabled) {
        RuntimeSettingKey key = RuntimeSettingKey.fromKey(settingKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持动态修改的设置项: " + settingKey));
        runtimeSettingMapper.upsert(key.key(), Boolean.toString(enabled), key.description());
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

    public boolean isKnowledgeRerankEnabled() {
        return isEnabled(RuntimeSettingKey.KNOWLEDGE_RAG_RERANK_ENABLED, knowledgeRagProperties.getRerank().isEnabled());
    }

    public boolean isCreatorFeedbackRagEnabled() {
        return isEnabled(RuntimeSettingKey.CREATOR_FEEDBACK_RAG_ENABLED, creatorFeedbackRagProperties.isEnabled());
    }

    private List<RuntimeToggleResponse> dynamicToggles() {
        Map<RuntimeSettingKey, Boolean> defaults = dynamicDefaults();
        return List.of(
                toToggle(RuntimeSettingKey.LLM_GUARD_ENABLED, defaults),
                toToggle(RuntimeSettingKey.SUMMARY_MEMORY_ENABLED, defaults),
                toToggle(RuntimeSettingKey.AGENT_STRUCTURED_KERNEL_ENABLED, defaults),
                toToggle(RuntimeSettingKey.KNOWLEDGE_RAG_RERANK_ENABLED, defaults),
                toToggle(RuntimeSettingKey.CREATOR_FEEDBACK_RAG_ENABLED, defaults)
        );
    }

    private RuntimeToggleResponse toToggle(RuntimeSettingKey key, Map<RuntimeSettingKey, Boolean> defaults) {
        return new RuntimeToggleResponse(
                key.key(),
                key.displayName(),
                isEnabled(key, defaults.getOrDefault(key, false)),
                key.description()
        );
    }

    private Map<RuntimeSettingKey, Boolean> dynamicDefaults() {
        Map<RuntimeSettingKey, Boolean> defaults = new EnumMap<>(RuntimeSettingKey.class);
        defaults.put(RuntimeSettingKey.LLM_GUARD_ENABLED, llmCallGuardProperties.isEnabled());
        defaults.put(RuntimeSettingKey.SUMMARY_MEMORY_ENABLED, summaryMemoryProperties.enabled());
        defaults.put(RuntimeSettingKey.AGENT_STRUCTURED_KERNEL_ENABLED, agentStructuredKernelDefaultEnabled());
        defaults.put(RuntimeSettingKey.KNOWLEDGE_RAG_RERANK_ENABLED, knowledgeRagProperties.getRerank().isEnabled());
        defaults.put(RuntimeSettingKey.CREATOR_FEEDBACK_RAG_ENABLED, creatorFeedbackRagProperties.isEnabled());
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
