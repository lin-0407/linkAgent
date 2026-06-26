package com.link.linkagent.creator.autofill.service;

import com.link.linkagent.creator.context.service.CreatorContextService;
import com.link.linkagent.creator.preference.service.CreatorPreferenceService;
import com.link.linkagent.creator.profile.service.CreatorProfileService;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorMaterialType;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 任务字段自动补全服务。
 * 前端输入框旁的 AI 按钮触发，后端拼装任务全局上下文（材料+画像+偏好+语境+已有建议），
 * 调用轻量模型生成补全建议。不建新表存储上下文——每次请求从已有数据中动态拼装。
 */
@Service
public class TaskAutofillService {

    private static final Logger log = LoggerFactory.getLogger(TaskAutofillService.class);

    private static final int CONTEXT_MAX_LENGTH = 4000;

    private final CreatorTaskMapper taskMapper;
    private final CreatorSuggestionMapper suggestionMapper;
    private final CreatorProfileService profileService;
    private final CreatorPreferenceService preferenceService;
    private final CreatorContextService contextService;
    private final LLMService llmService;
    private final PromptService promptService;
    private final String autofillModel;

    public TaskAutofillService(CreatorTaskMapper taskMapper,
                                CreatorSuggestionMapper suggestionMapper,
                                CreatorProfileService profileService,
                                CreatorPreferenceService preferenceService,
                                CreatorContextService contextService,
                                LLMService llmService,
                                PromptService promptService,
                                @Value("${spring.ai.openai.autofill-model}") String autofillModel) {
        this.taskMapper = taskMapper;
        this.suggestionMapper = suggestionMapper;
        this.profileService = profileService;
        this.preferenceService = preferenceService;
        this.contextService = contextService;
        this.llmService = llmService;
        this.promptService = promptService;
        this.autofillModel = autofillModel;
    }

    /**
     * 根据任务全局上下文，为指定字段生成补全建议。
     * 上下文从材料、画像、偏好、语境词条、历史建议中动态拼装，
     * 每次请求构建最新上下文，保证不用过期数据。
     */
    public String suggestField(String taskId, String fieldType) {
        CreatorTaskRecord task = taskMapper.findTaskByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在"));

        String globalContext = buildGlobalContext(task);
        String systemPrompt = promptService.get("field_autofill.system");
        String userPrompt = promptService.render("field_autofill.user", Map.of(
                "fieldType", toChineseFieldName(fieldType),
                "taskName", task.getTaskName(),
                "videoType", TextUtil.trimToDefault(task.getVideoType(), "未分类"),
                "globalContext", globalContext
        ));

        try {
            // 用独立的轻量模型（dpv4flash），与主分析模型区分，降低补全成本
            String result = llmService.chatWithModel(autofillModel, systemPrompt, userPrompt);
            return cleanResult(result);
        } catch (Exception e) {
            log.warn("字段自动补全失败：taskId={}, fieldType={}", taskId, fieldType, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "AI 补全失败，请稍后重试");
        }
    }

    /**
     * 拼装任务全局上下文。
     * 来源：已填材料 → 已有分析 → 创作者画像 → 历史偏好 → 类型语境词条。
     */
    private String buildGlobalContext(CreatorTaskRecord task) {
        StringBuilder ctx = new StringBuilder();

        // 1. 已填材料——直接上下文
        List<CreatorMaterialRecord> materials = taskMapper.listMaterialsByTaskId(task.getTaskId());
        if (!materials.isEmpty()) {
            ctx.append("【已填材料】\n");
            for (CreatorMaterialRecord m : materials) {
                ctx.append(toChineseMaterialName(m.getMaterialType()))
                        .append("：")
                        .append(TextUtil.preview(m.getContent(), 500, "暂无"))
                        .append("\n");
            }
            ctx.append("\n");
        }

        // 2. 已有分析建议——做过分析就有，含内容摘要/受众/定位
        Optional<CreatorSuggestionRecord> suggestionOpt = suggestionMapper.findByTaskId(task.getTaskId());
        if (suggestionOpt.isPresent() && "PARSED".equals(suggestionOpt.get().getParseStatus())) {
            CreatorSuggestionRecord s = suggestionOpt.get();
            ctx.append("【分析结果】\n");
            appendIfPresent(ctx, "内容摘要", s.getContentSummary());
            appendIfPresent(ctx, "目标受众", s.getAudienceProfile());
            appendIfPresent(ctx, "内容定位", s.getContentPositioning());
            if (TextUtil.hasText(s.getTitleSuggestions())) {
                ctx.append("标题建议：").append(TextUtil.preview(s.getTitleSuggestions(), 300, "")).append("\n");
            }
            ctx.append("\n");
        }

        // 3. 创作者画像
        String profileCtx = profileService.buildProfilePromptContext(task.getUserId());
        if (!profileCtx.isEmpty()) {
            ctx.append(profileCtx).append("\n\n");
        }

        // 4. 历史偏好
        String prefCtx = preferenceService.buildPromptContext(task.getUserId());
        if (!prefCtx.isEmpty() && !"暂无历史创作者偏好".equals(prefCtx)) {
            ctx.append("【历史偏好】\n").append(prefCtx).append("\n\n");
        }

        // 5. 语境词条
        String typeCtx = contextService.buildPromptContext(task.getUserId(), task.getVideoType(), "PRE_PUBLISH");
        if (!typeCtx.isEmpty()) {
            ctx.append("【类型语境】\n").append(typeCtx).append("\n");
        }

        return TextUtil.abbreviateWithSuffix(ctx.toString().trim(), CONTEXT_MAX_LENGTH, "\n[上下文过长，已截断]");
    }

    /** 去掉 LLM 常见包裹——引号、Markdown 代码块、前缀标签 */
    private String cleanResult(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("```[a-z]*\\s*", "").replaceAll("```\\s*", "").trim();
        }
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("「") && cleaned.endsWith("」"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        cleaned = cleaned.replaceFirst("^(建议[：:]\\s*|标题建议[：:]\\s*|简介建议[：:]\\s*)", "");
        return cleaned;
    }

    private void appendIfPresent(StringBuilder sb, String label, String value) {
        if (TextUtil.hasText(value)) {
            sb.append(label).append("：").append(value).append("\n");
        }
    }

    private String toChineseFieldName(String fieldType) {
        return switch (fieldType) {
            case "TITLE_DRAFT" -> "标题草稿";
            case "DESCRIPTION_DRAFT" -> "简介草稿";
            case "CUSTOM_GUIDANCE" -> "自定义创作指导";
            case "TITLE_STYLE" -> "标题风格偏好";
            case "EXTRA_REQUIREMENT" -> "额外补充要求";
            default -> fieldType;
        };
    }

    private String toChineseMaterialName(String materialType) {
        if (CreatorMaterialType.TITLE_DRAFT.name().equals(materialType)) return "标题草稿";
        if (CreatorMaterialType.DESCRIPTION_DRAFT.name().equals(materialType)) return "简介草稿";
        if (CreatorMaterialType.MANUSCRIPT.name().equals(materialType)) return "文稿";
        if (CreatorMaterialType.SUBTITLE.name().equals(materialType)) return "字幕";
        return materialType;
    }
}
