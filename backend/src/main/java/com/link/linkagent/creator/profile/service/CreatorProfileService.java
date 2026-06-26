package com.link.linkagent.creator.profile.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.preference.mapper.CreatorPreferenceMapper;
import com.link.linkagent.creator.preference.model.CreatorPreferenceRecord;
import com.link.linkagent.creator.profile.mapper.CreatorEventMapper;
import com.link.linkagent.creator.profile.mapper.CreatorProfileMapper;
import com.link.linkagent.creator.profile.model.CreatorEventRecord;
import com.link.linkagent.creator.profile.model.CreatorProfileRecord;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 创作者画像服务。
 * 负责画像的初始化、增量更新和提示词注入。
 * 事件写入由本服务统一管理，保证事件→画像的飞轮闭环在一个服务内完成。
 */
@Service
public class CreatorProfileService {

    private static final Logger log = LoggerFactory.getLogger(CreatorProfileService.class);

    private static final String DEFAULT_USER_ID = "default";
    /** 累积新事件数达到此阈值时触发画像增量更新 */
    private static final int EVENT_TRIGGER_THRESHOLD = 10;
    /** 最近事件查询上限，避免 LLM 上下文过长 */
    private static final int RECENT_EVENTS_LIMIT = 50;
    /** 构建提示词上下文时取最近几期偏好 */
    private static final int PREFERENCE_HISTORY_LIMIT = 5;

    private final CreatorEventMapper eventMapper;
    private final CreatorProfileMapper profileMapper;
    private final CreatorPreferenceMapper preferenceMapper;
    private final LLMService llmService;
    private final PromptService promptService;
    private final ObjectMapper objectMapper;

    public CreatorProfileService(CreatorEventMapper eventMapper,
                                 CreatorProfileMapper profileMapper,
                                 CreatorPreferenceMapper preferenceMapper,
                                 LLMService llmService,
                                 PromptService promptService,
                                 ObjectMapper objectMapper) {
        this.eventMapper = eventMapper;
        this.profileMapper = profileMapper;
        this.preferenceMapper = preferenceMapper;
        this.llmService = llmService;
        this.promptService = promptService;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取用户画像，不存在时返回 null。
     * 与 ensureProfile 的区别：本方法不做初始化，用于只需要读画像但不强制创建的场景。
     */
    public CreatorProfileRecord getProfile(String userId) {
        return profileMapper.findByCreatorId(normalizeUserId(userId));
    }

    /**
     * 确保用户画像存在——不存在时从历史偏好中 LLM 汇总生成初始画像。
     * 这是"首次使用时自动初始化"的入口，避免需要人工手动创建画像。
     */
    public CreatorProfileRecord ensureProfile(String userId) {
        String normalizedUserId = normalizeUserId(userId);
        CreatorProfileRecord existing = profileMapper.findByCreatorId(normalizedUserId);
        if (existing != null) {
            return existing;
        }
        return createInitialProfile(normalizedUserId);
    }

    /**
     * 记录一条创作者事件。
     * 事件写入后自动检查是否达到画像更新阈值，达到则触发增量更新。
     * 事件记录失败不影响主流程（仅记日志），避免反馈链路因事件表异常而阻塞。
     */
    public void recordEvent(String userId, String eventType, String taskId, Map<String, Object> payloadMap) {
        try {
            CreatorEventRecord event = new CreatorEventRecord();
            event.setEventId(UUID.randomUUID().toString());
            event.setCreatorId(normalizeUserId(userId));
            event.setEventType(eventType);
            event.setTaskId(taskId);
            event.setPayload(payloadMap != null && !payloadMap.isEmpty()
                    ? objectMapper.writeValueAsString(payloadMap)
                    : null);
            eventMapper.insert(event);
        } catch (Exception e) {
            log.warn("记录创作者事件失败：userId={}, eventType={}, taskId={}", userId, eventType, taskId, e);
        }
    }

    /**
     * 检查新事件数是否达到阈值，达到则触发画像增量更新。
     * 放在事件记录之后调用，让画像更新在事件流水驱动下自然发生。
     * 更新失败不抛异常——本次跳过，下次事件累积后再试。
     */
    public void tryTriggerProfileUpdate(String userId) {
        String normalizedUserId = normalizeUserId(userId);
        CreatorProfileRecord profile = profileMapper.findByCreatorId(normalizedUserId);
        if (profile == null) {
            return;
        }
        // 以画像上次更新时间为起点，统计这之后的新事件数
        LocalDateTime sinceTime = profile.getUpdateTime();
        int newEventCount = eventMapper.countNewEvents(normalizedUserId, sinceTime);
        if (newEventCount < EVENT_TRIGGER_THRESHOLD) {
            return;
        }
        try {
            updateProfileFromEvents(normalizedUserId, profile);
        } catch (Exception e) {
            log.warn("画像增量更新失败，本次跳过：userId={}", normalizedUserId, e);
        }
    }

    /**
     * 构建注入到系统提示词中的画像上下文。
     * 返回格式为人类可读的中文摘要，直接拼接进 system prompt 末尾。
     * 画像不存在时返回空字符串，不影响正常分析流程。
     */
    public String buildProfilePromptContext(String userId) {
        CreatorProfileRecord profile = getProfile(userId);
        if (profile == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("\n\n【创作者画像】\n");

        String styleTags = profile.getStyleTags();
        if (TextUtil.hasText(styleTags)) {
            builder.append("风格标签：").append(styleTags).append("\n");
        }
        String toneGuide = profile.getToneGuide();
        if (TextUtil.hasText(toneGuide)) {
            builder.append("语气偏好：").append(toneGuide).append("\n");
        }
        String audienceView = profile.getAudienceView();
        if (TextUtil.hasText(audienceView)) {
            builder.append("受众认知：").append(audienceView).append("\n");
        }

        // 如果画像三个字段都为空，返回空（首次初始化尚未完成的情况）
        String result = builder.toString().trim();
        if ("【创作者画像】".equals(result)) {
            return "";
        }
        return result;
    }

    /**
     * 手动触发画像刷新（供管理接口调用）。
     * 与 tryTriggerProfileUpdate 的区别：本方法不检查阈值，直接执行更新。
     */
    public CreatorProfileRecord refreshProfile(String userId) {
        String normalizedUserId = normalizeUserId(userId);
        CreatorProfileRecord profile = ensureProfile(normalizedUserId);
        updateProfileFromEvents(normalizedUserId, profile);
        return profileMapper.findByCreatorId(normalizedUserId);
    }

    /**
     * 从历史偏好中 LLM 汇总生成初始画像。
     * 如果用户没有任何历史偏好，则创建一个只有 creator_id 的空画像占位。
     */
    private CreatorProfileRecord createInitialProfile(String userId) {
        List<CreatorPreferenceRecord> preferences = preferenceMapper.listByUserId(userId, PREFERENCE_HISTORY_LIMIT);
        CreatorProfileRecord profile = new CreatorProfileRecord();
        profile.setCreatorId(userId);

        if (preferences.isEmpty()) {
            // 无历史偏好：创建空画像占位，后续有事件后再增量更新填充
            profile.setStyleTags("[]");
            profile.setToneGuide("");
            profile.setAudienceView("");
            profileMapper.insert(profile);
            return profileMapper.findByCreatorId(userId);
        }

        // 有历史偏好：调用 LLM 汇总生成初始画像
        String preferenceSummary = preferences.stream()
                .map(p -> "[" + p.getSourceTaskId() + "] " + p.getPreferenceContent())
                .collect(Collectors.joining("\n"));
        String userPrompt = promptService.render("creator_profile.init.user", Map.of(
                "preferenceSummary", preferenceSummary
        ));
        try {
            String rawOutput = llmService.chat(
                    promptService.get("creator_profile.init.system"),
                    userPrompt
            );
            // LLM 返回 JSON，解析出三个字段
            parseProfileOutput(profile, rawOutput);
        } catch (Exception e) {
            log.warn("LLM 初始画像生成失败，创建空画像占位：userId={}", userId, e);
            profile.setStyleTags("[]");
            profile.setToneGuide("");
            profile.setAudienceView("");
        }
        profileMapper.insert(profile);
        return profileMapper.findByCreatorId(userId);
    }

    /**
     * 从最近事件中 LLM 增量更新画像。
     * 只传入当前画像 + 最近事件，LLM 判断是否需要调整画像内容。
     */
    private void updateProfileFromEvents(String userId, CreatorProfileRecord currentProfile) {
        List<CreatorEventRecord> recentEvents = eventMapper.listRecentByCreator(userId, RECENT_EVENTS_LIMIT);
        if (recentEvents.isEmpty()) {
            return;
        }
        String eventsSummary = recentEvents.stream()
                .map(e -> "[" + e.getEventType() + "] "
                        + (TextUtil.hasText(e.getPayload()) ? e.getPayload() : "无详情")
                        + " (taskId=" + e.getTaskId() + ")")
                .collect(Collectors.joining("\n"));

        String currentProfileText = buildCurrentProfileText(currentProfile);
        String userPrompt = promptService.render("creator_profile.update.user", Map.of(
                "currentProfile", currentProfileText,
                "recentEvents", eventsSummary
        ));
        try {
            String rawOutput = llmService.chat(
                    promptService.get("creator_profile.update.system"),
                    userPrompt
            );
            parseProfileOutput(currentProfile, rawOutput);
            profileMapper.update(currentProfile);
        } catch (Exception e) {
            log.warn("LLM 画像增量更新失败：userId={}", userId, e);
        }
    }

    /**
     * 解析 LLM 返回的 JSON 输出，填充画像的三个字段。
     * LLM 输出格式：{ "styleTags": [...], "toneGuide": "...", "audienceView": "..." }
     */
    private void parseProfileOutput(CreatorProfileRecord profile, String rawOutput) {
        try {
            String jsonText = rawOutput;
            // 兼容 LLM 输出可能包裹在 markdown 代码块中的情况
            if (jsonText.contains("```")) {
                jsonText = jsonText.replaceAll("```json\\s*", "")
                        .replaceAll("```\\s*", "")
                        .trim();
            }
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(jsonText);
            if (root.has("styleTags")) {
                profile.setStyleTags(objectMapper.writeValueAsString(root.get("styleTags")));
            }
            if (root.has("toneGuide") && !root.get("toneGuide").isNull()) {
                profile.setToneGuide(root.get("toneGuide").asText());
            }
            if (root.has("audienceView") && !root.get("audienceView").isNull()) {
                profile.setAudienceView(root.get("audienceView").asText());
            }
        } catch (Exception e) {
            log.warn("画像 JSON 解析失败，保留旧值：{}", e.getMessage());
        }
    }

    private String buildCurrentProfileText(CreatorProfileRecord profile) {
        return "风格标签：" + TextUtil.trimToDefault(profile.getStyleTags(), "暂无")
                + "\n语气偏好：" + TextUtil.trimToDefault(profile.getToneGuide(), "暂无")
                + "\n受众认知：" + TextUtil.trimToDefault(profile.getAudienceView(), "暂无");
    }

    private String normalizeUserId(String userId) {
        return TextUtil.trimToDefault(userId, DEFAULT_USER_ID);
    }
}
