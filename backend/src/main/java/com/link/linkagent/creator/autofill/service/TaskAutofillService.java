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
 * <p>
 * 核心职责：当创作者在发布前任务表单中点击输入框旁的 AI 补全按钮时，
 * 拼装任务全局上下文（已填材料 + 已有分析 + 创作者画像 + 历史偏好 + 类型语境 + 历史建议），
 * 调用轻量模型为指定字段生成补全建议。
 * <p>
 * 架构位置：位于创作者服务层，是前端表单输入体验的 AI 增强组件。
 * 上游聚合来自分析/画像/偏好/语境等多服务的数据，下游直接返回文本建议给前端展示。
 * <p>
 * 设计决策：
 * <ul>
 *   <li><b>动态上下文拼装，不建新表</b>——每次请求实时从已有表中查询数据拼装上下文，
 *       而非将上下文写入中间表再读取。原因：上下文涉及的图片来源分散（任务表、材料表、分析表、
 *       画像表、偏好表、语境表），建新表会造成数据冗余和一致性问题；动态拼装保证每次补全
 *       都基于最新数据。</li>
 *   <li><b>独立轻量模型</b>——补全请求使用独立的轻量模型（如 dpv4flash），与主分析模型区分。
 *       补全任务简单（填空/续写），不需要强推理能力；用轻量模型降低每次补全的 token 消耗和延迟，
 *       提升按钮点击后的响应速度。</li>
 *   <li><b>上下文长度截断</b>——限制全局上下文最长 4000 字符，防止多素材多分析场景下
 *       prompt 过长导致轻量模型响应变慢或超出上下文窗口。</li>
 *   <li><b>补全失败抛 500</b>——与事件记录/画像更新的"静默降级"策略不同，补全是用户主动触发的动作，
 *       用户点击按钮后看到报错是合理的；返回明确的错误信息比返回空内容让用户困惑更好。</li>
 * </ul>
 *
 * @see CreatorTaskRecord   创作任务记录
 * @see CreatorMaterialRecord 任务素材（已填材料）
 * @see CreatorSuggestionRecord 分析建议记录
 */
@Service
public class TaskAutofillService {

    private static final Logger log = LoggerFactory.getLogger(TaskAutofillService.class);

    /**
     * 全局上下文最大长度（字符数）。
     * 限制为 4000 的原因：
     * 1) 轻量模型（如 dpv4flash）的上下文窗口通常较小，过长 prompt 可能被截断或导致超时；
     * 2) 补全任务不需要完整上下文——4000 字符已足够涵盖素材摘要 + 画像 + 偏好 + 语境的关键信息；
     * 3) 截断后追加 "[上下文过长，已截断]" 提示，让 LLM 知道信息不完整，避免强行补全。
     */
    private static final int CONTEXT_MAX_LENGTH = 4000;

    /** 任务表读写，用于查询创建任务及其关联的材料 */
    private final CreatorTaskMapper taskMapper;

    /** 分析建议表只读，用于查询已生成的分析结果加入上下文 */
    private final CreatorSuggestionMapper suggestionMapper;

    /** 创作者画像服务，提供风格标签/语气/受众等画像上下文 */
    private final CreatorProfileService profileService;

    /** 创作者偏好服务，提供历史修改反馈等偏好上下文 */
    private final CreatorPreferenceService preferenceService;

    /** 语境服务，提供视频类型相关的语境词条上下文 */
    private final CreatorContextService contextService;

    /** LLM 调用入口，用于调用模型生成补全文本 */
    private final LLMService llmService;

    /** 提示词模板管理，用于渲染补全相关 prompt 并支持模板 A/B 测试 */
    private final PromptService promptService;

    /**
     * 补全专用模型名称，从配置 {@code spring.ai.openai.autofill-model} 注入。
     * 为什么与主分析模型分拆配置：补全任务高频但简单（单字段填空），用轻量模型成本更低、响应更快；
     * 独立配置允许运维根据成本和延迟分别调优两个模型。
     */
    private final String autofillModel;

    /**
     * 构造注入所有依赖。
     * 全部字段用 final 修饰 + 构造注入，保证不可变性和单测中可显式传入 mock 依赖。
     *
     * @param taskMapper       任务表 Mapper
     * @param suggestionMapper 分析建议表 Mapper
     * @param profileService   创作者画像服务
     * @param preferenceService 创作者偏好服务
     * @param contextService   语境服务
     * @param llmService       LLM 调用服务
     * @param promptService    提示词模板服务
     * @param autofillModel    补全专用模型名称（从 application.properties 注入）
     */
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
     * 根据任务全局上下文，为指定字段生成 AI 补全建议。
     * <p>
     * 调用流程：
     * <ol>
     *   <li>根据 taskId 查任务记录——不存在时抛 404，因为补全必须在已有任务上操作</li>
     *   <li>拼装全局上下文（材料 + 分析 + 画像 + 偏好 + 语境），限制在 {@value #CONTEXT_MAX_LENGTH} 字符内</li>
     *   <li>渲染提示词模板，调用轻量模型生成补全文本</li>
     *   <li>清洗 LLM 输出（去引号/代码块标记/前缀标签）后返回</li>
     * </ol>
     * <p>
     * 为什么补全失败抛 500 而非静默降级：补全是用户主动点击 AI 按钮触发的动作，
     * 用户预期看到 AI 的回复。静默返回空字符串会让用户困惑"按钮没反应"；
     * 返回明确错误让用户知道 AI 服务暂时不可用，可以稍后重试。
     * 这与事件记录/画像更新的"静默降级"策略不同——后者是后台自动触发的副作用。
     *
     * @param taskId    创作任务 ID（前端传入，可能带首尾空格）
     * @param fieldType 要补全的字段类型枚举值（如 TITLE_DRAFT / DESCRIPTION_DRAFT / CUSTOM_GUIDANCE 等）
     * @return 清洗后的补全文本建议
     * @throws ResponseStatusException 任务不存在（404）或 LLM 调用失败（500）
     */
    public String suggestField(String taskId, String fieldType) {
        CreatorTaskRecord task = taskMapper.findTaskByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在"));

        // 动态拼装全局上下文：每次请求实时查询，保证上下文基于最新数据。
        // 为什么不缓存上下文：上下文来源涉及 6 张表，部分表（如材料表）可能在两个补全请求之间
        // 被用户修改；直接实时查询保证每次补全看到的是最新数据。
        String globalContext = buildGlobalContext(task);

        // 渲染系统提示词 + 用户提示词。
        // 为什么拆成 system/user 两层：system prompt 定义补全工具的角色和行为规则，
        // user prompt 包含具体任务信息——分层让提示词模板可独立维护和 A/B 测试。
        String systemPrompt = promptService.get("field_autofill.system");
        String userPrompt = promptService.render("field_autofill.user", Map.of(
                "fieldType", toChineseFieldName(fieldType),
                "taskName", task.getTaskName(),
                "videoType", TextUtil.trimToDefault(task.getVideoType(), "未分类"),
                "globalContext", globalContext
        ));

        try {
            // 用独立的轻量模型（如 dpv4flash），与主分析模型（如 deepseek-chat）区分。
            // 补全任务只需要填空/续写能力，不需要复杂推理——用轻量模型降低延迟和 token 成本。
            String result = llmService.chatWithModel(autofillModel, systemPrompt, userPrompt);
            return cleanResult(result);
        } catch (Exception e) {
            log.warn("字段自动补全失败：taskId={}, fieldType={}", taskId, fieldType, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "AI 补全失败，请稍后重试");
        }
    }

    /**
     * 拼装任务全局上下文文本。
     * <p>
     * 信息来源按优先级排序（为什么按这个顺序）：
     * <ol>
     *   <li><b>已填材料</b>——最直接的上下文，已填的标题/简介/文稿/字幕是补全最重要的参考信息</li>
     *   <li><b>已有分析</b>——分析结果包含内容摘要/受众/定位/标题建议，
     *       从"分析视角"提供补全参考，帮 LLM 理解"这个视频是讲什么的"</li>
     *   <li><b>创作者画像</b>——风格标签/语气偏好/受众认知，让补全结果符合创作者一贯风格</li>
     *   <li><b>历史偏好</b>——创作者过去对被修改内容的反馈，让补全避开已踩过的坑</li>
     *   <li><b>类型语境</b>——视频类型对应的语境词条，提供类型特定的补全方向约束</li>
     * </ol>
     * <p>
     * 拼装完成后做长度截断（{@value #CONTEXT_MAX_LENGTH} 字符），防止 prompt 过长。
     * 截断策略是前缀保留（abbreviateWithSuffix），优先保留排在前面的材料和分析内容，
     * 末尾截断并追加 "[上下文过长，已截断]" 标记。
     *
     * @param task 创作任务记录（已在调用方校验非 null）
     * @return 格式化的全局上下文文本；可能被截断
     */
    private String buildGlobalContext(CreatorTaskRecord task) {
        StringBuilder ctx = new StringBuilder();

        // 1. 已填材料——直接上下文，是补全最重要的参考
        // 为什么只取前 500 字符预览而非完整内容：完整文稿可能数千字，
        // 但补全字段（如标题/简介）只需要材料的大致内容方向，不需要逐字对照
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

        // 2. 已有分析建议——做过分析就有，含内容摘要/受众/定位/标题建议
        // 为什么只取 PARSED 状态的分析：UNPARSED（队列中/解析中）的分析内容可能不完整或不可用
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

        // 3. 创作者画像——风格标签/语气/受众，让补全结果符合创作者一贯风格
        // 为什么用 buildProfilePromptContext 而非直接查表：画像格式化逻辑集中管理，
        // buildProfilePromptContext 内部处理了空画像的情况（返回空字符串）
        String profileCtx = profileService.buildProfilePromptContext(task.getUserId());
        if (!profileCtx.isEmpty()) {
            ctx.append(profileCtx).append("\n\n");
        }

        // 4. 历史偏好——创作者过去反馈的修改倾向，帮 LLM 避开已踩的坑
        // 为什么过滤"暂无历史创作者偏好"字符串：PreferenceService 在无历史时返回这个兜底文本，
        // 拼入上下文没有意义，需要过滤掉以减少噪音
        String prefCtx = preferenceService.buildPromptContext(task.getUserId());
        if (!prefCtx.isEmpty() && !"暂无历史创作者偏好".equals(prefCtx)) {
            ctx.append("【历史偏好】\n").append(prefCtx).append("\n\n");
        }

        // 5. 语境词条——视频类型相关的语境约束，提供类型特定的补全方向
        // 固定 stage=PRE_PUBLISH：补全发生在发布前阶段，取 PRE_PUBLISH 阶段的语境词条
        String typeCtx = contextService.buildPromptContext(task.getUserId(), task.getVideoType(), "PRE_PUBLISH");
        if (!typeCtx.isEmpty()) {
            ctx.append("【类型语境】\n").append(typeCtx).append("\n");
        }

        // 最终截断：保证上下文不超过轻量模型的合理输入长度。
        // 为什么在前缀保留而非居中提取：排在前面的信息（材料、分析）优先级更高，
        // 截断后缀（如类型语境）损失的信息价值更低。
        return TextUtil.abbreviateWithSuffix(ctx.toString().trim(), CONTEXT_MAX_LENGTH, "\n[上下文过长，已截断]");
    }

    /**
     * 清洗 LLM 返回的补全结果，去掉常见的格式包裹和前缀标签。
     * <p>
     * 清洗步骤（按顺序）：
     * <ol>
     *   <li><b>Markdown 代码块</b>——LLM 可能把补全结果包在 \`\`\` 中</li>
     *   <li><b>引号包裹</b>——LLM 可能在文本外加双引号或中文书名号（「 」）</li>
     *   <li><b>前缀标签</b>——LLM 可能加"建议："、"标题建议："等自然语言前缀，
     *       这些前缀对前端展示是多余的</li>
     * </ol>
     * 为什么不在 system prompt 中约束 LLM 直接输出纯文本：经验表明即使 prompt 要求"只输出结果，不加任何前缀"，
     * LLM 仍可能偶尔输出带引号/代码块/前缀的内容——因此加入防御性清洗作为兜底。
     *
     * @param raw LLM 原始输出
     * @return 清洗后的纯文本建议；输入为空时返回空字符串
     */
    private String cleanResult(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String cleaned = raw.trim();

        // 去掉 Markdown 代码块包裹：\`\`\`json 或 \`\`\` 或 \`\`\`text 等
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("```[a-z]*\\s*", "").replaceAll("```\\s*", "").trim();
        }

        // 去掉引号包裹：双引号 "..." 或中文书名号 「...」
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("「") && cleaned.endsWith("」"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }

        // 去掉前缀标签："建议："、"标题建议："、"简介建议：" 等 LLM 常见的礼貌前缀。
        // 使用 replaceFirst 而非 replaceAll：前缀标签理论上只在开头出现一次
        cleaned = cleaned.replaceFirst("^(建议[：:]\\s*|标题建议[：:]\\s*|简介建议[：:]\\s*)", "");
        return cleaned;
    }

    /**
     * 只有当 value 非空时才向 StringBuilder 追加一行 "label：value"。
     * 避免上下文中出现 "内容摘要：" 这样的空字段行，减少噪音 token。
     *
     * @param sb    目标 StringBuilder
     * @param label 字段中文标签
     * @param value 字段值，为空则跳过
     */
    private void appendIfPresent(StringBuilder sb, String label, String value) {
        if (TextUtil.hasText(value)) {
            sb.append(label).append("：").append(value).append("\n");
        }
    }

    /**
     * 将字段类型枚举值映射为中文名称，用于 LLM prompt 中的人类可读描述。
     * <p>
     * 为什么不用 i18n 资源文件：字段类型是固定的业务枚举（5 个值），直接用 switch 映射
     * 比维护一份额外资源文件更内聚。如果未来字段类型扩展到 20+ 个，再考虑抽取为配置。
     *
     * @param fieldType 字段类型枚举值（如 TITLE_DRAFT）
     * @return 对应的中文名称（如 "标题草稿"）；未知类型原样返回
     */
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

    /**
     * 将素材类型枚举值映射为中文名称，用于上下文文本中的人类可读标签。
     * <p>
     * 为什么用 if-return 而非 switch：素材类型来自 {@link CreatorMaterialType} 的枚举 name()，
     * 只有 4 个已知类型，if-return 链比 switch 更紧凑且可读性相当。
     *
     * @param materialType 素材类型的枚举名称（如 "MANUSCRIPT"）
     * @return 对应的中文标签（如 "文稿"）；未知类型原样返回
     */
    private String toChineseMaterialName(String materialType) {
        if (CreatorMaterialType.TITLE_DRAFT.name().equals(materialType)) return "标题草稿";
        if (CreatorMaterialType.DESCRIPTION_DRAFT.name().equals(materialType)) return "简介草稿";
        if (CreatorMaterialType.MANUSCRIPT.name().equals(materialType)) return "文稿";
        if (CreatorMaterialType.SUBTITLE.name().equals(materialType)) return "字幕";
        return materialType;
    }
}
