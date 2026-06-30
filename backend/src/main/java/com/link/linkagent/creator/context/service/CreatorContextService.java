package com.link.linkagent.creator.context.service;

import com.link.linkagent.creator.context.mapper.CreatorContextMapper;
import com.link.linkagent.creator.context.model.CreatorContextBundleResponse;
import com.link.linkagent.creator.context.model.CreatorContextTermCreateRequest;
import com.link.linkagent.creator.context.model.CreatorContextTermRecord;
import com.link.linkagent.creator.context.model.CreatorContextTermResponse;
import com.link.linkagent.util.NumberUtil;
import com.link.linkagent.util.TextUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 创作者语境库服务 —— 管理创作者的个性化表达知识库（词条 CRUD + 提示词拼接）。
 * <p>
 * 核心职责：维护”某个创作者在某类视频里怎么表达”的业务语境（关键词、梗/黑话、标题套路、
 * 观众关注点、禁忌表达），供发布前优化和评论分析的 LLM 提示词使用。
 * 不替代评论弹幕的事实证据——语境是基于创作者偏好和成功经验的提炼，而非从评论中
 * 直接统计出的数据。
 * <p>
 * 架构定位：位于领域服务层，向上暴露给工作流服务（用于拼入 LLM 提示词）和 Controller
 * （用于词条管理界面），向下只依赖 {@link CreatorContextMapper} 做数据访问。
 * <p>
 * 词条权重设计（{@link #initialWeight}）：不同来源类型的词条有不同的初始权重，
 * 权重影响词条在提示词中的排序和后续反馈调整。核心原则：
 * <ul>
 *   <li>用户主动保存 > AI 自动采纳 > 视频成功验证 > 评论自动提取 > 用户拒绝</li>
 *   <li>禁忌词/负面评价标签额外加成（+6），因为这些对避免风险更重要</li>
 * </ul>
 * <p>
 * 提示词拼接策略（{@link #buildBundle}）：将词条按类型分组后拼入提示词，
 * 每组上限 {@link #PROMPT_SECTION_LIMIT} 条、总上下文截断到 {@link #PROMPT_CONTEXT_MAX_LENGTH} 字符，
 * 在信息量和 Token 成本之间取得平衡。
 */
@Service
public class CreatorContextService {

    /** 全局视频类型常量，查询时匹配所有视频类型的通用词条 */
    public static final String GLOBAL_VIDEO_TYPE = "GLOBAL";

    // —— 业务常量 ——

    /** 匿名用户默认标识 */
    private static final String DEFAULT_USER_ID = "default";
    /** 视频类型默认值 */
    private static final String DEFAULT_VIDEO_TYPE = "未分类";
    /** 词条类型默认值 */
    private static final String DEFAULT_TERM_TYPE = "KEYWORD";
    /** 情感极性默认值 */
    private static final String DEFAULT_POLARITY = "NEUTRAL";
    /** 词条来源类型默认值 */
    private static final String DEFAULT_SOURCE_TYPE = "USER_SAVE";
    /** 默认使用场景 */
    private static final String SCENE_PRE_PUBLISH = "PRE_PUBLISH";
    /** 词条列表默认返回条数 */
    private static final int DEFAULT_LIST_LIMIT = 50;
    /** 词条列表最大返回条数 */
    private static final int MAX_LIST_LIMIT = 100;
    /** 提示词中词条总数上限：控制 LLM 上下文窗口内的语境信息量 */
    private static final int PROMPT_TERM_LIMIT = 40;
    /**
     * 提示词中每组词条的数量上限。
     * 每组（关键词、梗/黑话、标题套路、观众关注点）最多取此数量，
     * 防止某类词条过多挤占其他类型的空间。
     */
    private static final int PROMPT_SECTION_LIMIT = 12;
    /** 拼入提示词的语境文本最大字符数，超过则截断 */
    private static final int PROMPT_CONTEXT_MAX_LENGTH = 4000;

    /** 语境词条数据访问，唯一的持久化依赖 */
    private final CreatorContextMapper creatorContextMapper;

    public CreatorContextService(CreatorContextMapper creatorContextMapper) {
        this.creatorContextMapper = creatorContextMapper;
    }

    /**
     * 保存或更新一条语境词条（通过 (userId, videoType, normalizedTerm, termType) 唯一键 upsert）。
     * <p>
     * 数据结构设计：term 是用户看到的展示文本，normalizedTerm 是 trim + lowerCase 后的
     * 身份标识版本——保证"大小写不同但语义相同的词"能正确合并，而不会因为 CASING 差异
     * 产生重复词条。term 保留原始大小写用于前端展示。
     * <p>
     * TABOO 类型的词条强制 polarity=NEGATIVE：禁忌词的语义天然是负面的，不应允许用户
     * 将其标记为 POSITIVE 或 NEUTRAL，这会导致下游分析出现逻辑矛盾。
     *
     * @param request 创建请求，含词条文本、类型、情感极性、来源、依据等
     * @return 保存后的词条响应（含服务端生成的 termId 和初始权重）
     */
    @Transactional
    public CreatorContextTermResponse saveTerm(CreatorContextTermCreateRequest request) {
        CreatorContextTermRecord record = new CreatorContextTermRecord();
        record.setTermId(UUID.randomUUID().toString());
        record.setUserId(normalizeUserId(request.userId()));
        record.setVideoType(normalizeVideoType(request.videoType()));
        record.setTerm(normalizeTermDisplay(request.term()));
        record.setNormalizedTerm(normalizeTermIdentity(request.term()));
        record.setTermType(normalizeTermType(request.termType()));
        record.setPolarity(normalizePolarity(request.polarity(), record.getTermType()));
        record.setSourceType(normalizeSourceType(request.sourceType()));
        record.setSourceTaskId(TextUtil.trimToNull(request.sourceTaskId()));
        record.setEvidenceText(TextUtil.trimToNull(request.evidenceText()));
        record.setWeight(initialWeight(record.getTermType(), record.getPolarity(), record.getSourceType()));
        record.setEnabled(true);

        creatorContextMapper.upsertTerm(record);
        return creatorContextMapper.findByIdentity(
                        record.getUserId(),
                        record.getVideoType(),
                        record.getNormalizedTerm(),
                        record.getTermType()
                )
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "语境词条保存后读取失败"));
    }

    /**
     * 分页查询词条列表，支持按视频类型、词条类型、启用状态过滤。
     * <p>
     * videoType/termType 为空时不做过滤（返回全部），includeDisabled=true 时
     * 包含已禁用的词条（默认只返回启用中的词条）。
     *
     * @param userId          用户ID
     * @param videoType       视频类型过滤（可选）
     * @param termType        词条类型过滤（可选）
     * @param includeDisabled 是否包含已禁用词条
     * @param limit           返回条数上限
     * @return 词条列表，按权重降序排列
     */
    public List<CreatorContextTermResponse> listTerms(String userId,
                                                      String videoType,
                                                      String termType,
                                                      Boolean includeDisabled,
                                                      Integer limit) {
        int safeLimit = NumberUtil.limitOrDefault(limit, DEFAULT_LIST_LIMIT, MAX_LIST_LIMIT);
        return creatorContextMapper.listTerms(
                        normalizeUserId(userId),
                        TextUtil.hasText(videoType) ? normalizeVideoType(videoType) : null,
                        TextUtil.hasText(termType) ? normalizeTermType(termType) : null,
                        Boolean.TRUE.equals(includeDisabled),
                        safeLimit
                )
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 为 LLM 提示词构建语境数据包（Bundle），将词条按类型分组并生成可拼入提示词的文本。
     * <p>
     * 查询范围：同时匹配指定 videoType 和 GLOBAL 类型的词条，确保用户针对具体视频类型的
     * 特定语境和跨类型通用语境都能被 LLM 参考。
     * <p>
     * 分组策略：
     * <ul>
     *   <li>keywords（KEYWORD 类型）→ 适合使用的关键词</li>
     *   <li>slangTerms（SLANG/MEME 类型）→ 梗/黑话</li>
     *   <li>titlePatterns（TITLE_PATTERN 类型）→ 历史有效标题套路</li>
     *   <li>audienceConcerns（AUDIENCE_CONCERN 类型）→ 观众常见关注点</li>
     *   <li>tabooTerms（TABOO 类型 或 polarity=NEGATIVE 的任意类型）→ 慎用或避免表达</li>
     * </ul>
     * <p>
     * 每组上限 {@link #PROMPT_SECTION_LIMIT} 条，总词条上限 {@link #PROMPT_TERM_LIMIT} 条。
     *
     * @param userId    用户ID
     * @param videoType 视频类型
     * @param scene     使用场景（PRE_PUBLISH / FEEDBACK_ANALYZE / REPORT）
     * @return 语境数据包，含分组词条和格式化后的提示词文本
     */
    public CreatorContextBundleResponse buildBundle(String userId, String videoType, String scene) {
        String safeUserId = normalizeUserId(userId);
        String safeVideoType = normalizeVideoType(videoType);
        String safeScene = normalizeScene(scene);
        List<CreatorContextTermResponse> terms = creatorContextMapper
                .listForPrompt(safeUserId, safeVideoType, GLOBAL_VIDEO_TYPE, PROMPT_TERM_LIMIT)
                .stream()
                .map(this::toResponse)
                .toList();

        List<String> keywords = filterTerms(terms, "KEYWORD");
        List<String> slangTerms = filterTerms(terms, "SLANG", "MEME");
        List<String> titlePatterns = filterTerms(terms, "TITLE_PATTERN");
        List<String> audienceConcerns = filterTerms(terms, "AUDIENCE_CONCERN");
        List<String> tabooTerms = terms.stream()
                .filter(term -> "TABOO".equals(term.termType()) || "NEGATIVE".equals(term.polarity()))
                .limit(PROMPT_SECTION_LIMIT)
                .map(this::formatPromptTerm)
                .toList();

        return new CreatorContextBundleResponse(
                safeUserId,
                safeVideoType,
                safeScene,
                terms,
                keywords,
                slangTerms,
                titlePatterns,
                audienceConcerns,
                tabooTerms,
                buildPromptContext(safeVideoType, safeScene, keywords, slangTerms, titlePatterns, audienceConcerns, tabooTerms)
        );
    }

    /**
     * 为 LLM 提示词构建格式化的语境文本（从 Bundle 中取 promptContext 字段的快捷方法）。
     * <p>
     * 发布前优化读取的是整理后的语境摘要，而不是原始词条 JSON，避免提示词被
     * 低价值字段（termId、权重、创建时间等）撑长，浪费 Token。
     *
     * @param userId    用户ID
     * @param videoType 视频类型
     * @param scene     使用场景
     * @return 格式化后的语境文本，可直接拼入 LLM 系统/用户提示词
     */
    public String buildPromptContext(String userId, String videoType, String scene) {
        return buildBundle(userId, videoType, scene).promptContext();
    }

    /**
     * 禁用一条语境词条（不物理删除，保留历史记录用于审计和恢复）。
     * <p>
     * 禁用后的词条不再出现在 LLM 提示词中，但仍保留在数据库中用于：
     * <ol>
     *   <li>误操作恢复：用户可重新启用</li>
     *   <li>审计追溯：了解哪些词条曾被禁用及其原因</li>
     *   <li>重复检测：防止相同词条被再次添加</li>
     * </ol>
     *
     * @param termId 词条ID
     * @return 禁用后的词条响应
     */
    @Transactional
    public CreatorContextTermResponse disableTerm(String termId) {
        String safeTermId = normalizeTermId(termId);
        CreatorContextTermRecord before = creatorContextMapper.findByTermId(safeTermId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "语境词条不存在"));
        creatorContextMapper.disableTerm(before.getTermId());
        return creatorContextMapper.findByTermId(before.getTermId())
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "语境词条禁用后读取失败"));
    }

    /**
     * 记录词条反馈（采纳/拒绝），用于调整词条权重。
     * <p>
     * 采纳（accepted=true）：增加使用计数和采纳计数，权重上升，未来更可能出现在提示词中。
     * 拒绝（accepted=false）：增加使用计数和拒绝计数，权重下降，未来更可能被过滤。
     * <p>
     * 为什么反馈要影响权重：用户对 AI 建议词的采纳/拒绝是最真实的偏好信号，
     * 比初始权重更能反映该词条对当前创作者的实际价值。
     *
     * @param termId   词条ID
     * @param accepted true=采纳，false=拒绝
     * @return 更新后的词条响应
     */
    @Transactional
    public CreatorContextTermResponse recordFeedback(String termId, boolean accepted) {
        String safeTermId = normalizeTermId(termId);
        if (accepted) {
            creatorContextMapper.acceptTerm(safeTermId);
        } else {
            creatorContextMapper.rejectTerm(safeTermId);
        }
        return creatorContextMapper.findByTermId(safeTermId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "语境词条不存在"));
    }

    private String buildPromptContext(String videoType,
                                      String scene,
                                      List<String> keywords,
                                      List<String> slangTerms,
                                      List<String> titlePatterns,
                                      List<String> audienceConcerns,
                                      List<String> tabooTerms) {
        if (keywords.isEmpty()
                && slangTerms.isEmpty()
                && titlePatterns.isEmpty()
                && audienceConcerns.isEmpty()
                && tabooTerms.isEmpty()) {
            return "当前视频类型【" + videoType + "】暂无已沉淀语境。";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("当前视频类型：").append(videoType).append("\n");
        builder.append("使用场景：").append(sceneLabel(scene)).append("\n");
        appendSection(builder, "适合使用的关键词", keywords);
        appendSection(builder, "适合使用的梗/黑话", slangTerms);
        appendSection(builder, "历史有效标题套路", titlePatterns);
        appendSection(builder, "观众常见关注点", audienceConcerns);
        appendSection(builder, "慎用或避免表达", tabooTerms);
        return TextUtil.abbreviateWithSuffix(
                builder.toString().trim(),
                PROMPT_CONTEXT_MAX_LENGTH,
                "\n[语境库内容过长，已截断用于本次分析]"
        );
    }

    private void appendSection(StringBuilder builder, String title, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        builder.append(title).append("：\n");
        for (String value : values) {
            builder.append("- ").append(value).append("\n");
        }
    }

    /**
     * 从词条列表中按类型过滤，返回格式化后的提示词文本列表。
     * 每个类型组上限 {@link #PROMPT_SECTION_LIMIT} 条。
     */
    private List<String> filterTerms(List<CreatorContextTermResponse> terms, String... termTypes) {
        List<String> values = new ArrayList<>();
        for (CreatorContextTermResponse term : terms) {
            if (values.size() >= PROMPT_SECTION_LIMIT) {
                break;
            }
            for (String termType : termTypes) {
                if (termType.equals(term.termType())) {
                    values.add(formatPromptTerm(term));
                    break;
                }
            }
        }
        return values;
    }

    /**
     * 格式化单条词条为提示词文本：有依据文本时附带依据（截断到 80 字），
     * 否则仅返回词条本身。
     */
    private String formatPromptTerm(CreatorContextTermResponse term) {
        if (TextUtil.isBlank(term.evidenceText())) {
            return term.term();
        }
        return term.term() + "（依据：" + TextUtil.abbreviate(term.evidenceText().trim(), 80) + "）";
    }

    /**
     * 计算词条初始权重（0-100），权重决定在提示词中的排序和展示优先级。
     * <p>
     * 权重策略（来源类型基础分）：
     * <ul>
     *   <li>USER_SAVE (70)：用户主动保存，信任度最高</li>
     *   <li>AI_ACCEPTED (62)：AI 建议后被用户采纳</li>
     *   <li>VIDEO_SUCCESS (58)：在成功视频中验证过</li>
     *   <li>COMMENT_EXTRACTED (42)：从评论中自动提取，需进一步验证</li>
     *   <li>USER_REJECTED (24)：曾被用户拒绝，但仍保留供参考</li>
     *   <li>其他来源 (50)：默认中位值</li>
     * </ul>
     * <p>
     * TABOO 类型或 NEGATIVE 极性的词条额外 +6：因为风险规避类信息比正面信息
     * 更有"不能忽略"的价值，在提示词中应稍微靠前。
     */
    private int initialWeight(String termType, String polarity, String sourceType) {
        int weight = switch (sourceType) {
            case "USER_SAVE" -> 70;
            case "AI_ACCEPTED" -> 62;
            case "VIDEO_SUCCESS" -> 58;
            case "COMMENT_EXTRACTED" -> 42;
            case "USER_REJECTED" -> 24;
            default -> 50;
        };
        if ("TABOO".equals(termType) || "NEGATIVE".equals(polarity)) {
            weight += 6;
        }
        return Math.min(weight, 100);
    }

    private String normalizeUserId(String userId) {
        return TextUtil.trimToDefault(userId, DEFAULT_USER_ID);
    }

    private String normalizeVideoType(String videoType) {
        return TextUtil.trimToDefault(videoType, DEFAULT_VIDEO_TYPE);
    }

    /**
     * 规范化词条的展示文本：合并连续空白字符为单个空格。
     * 保留原始大小写和标点，不改变用户输入的表达方式。
     */
    private String normalizeTermDisplay(String term) {
        return TextUtil.collapseWhitespace(term);
    }

    /**
     * 规范化词条的身份标识文本：合并空白 + 全小写。
     * 用于唯一键匹配和去重——"Hello World"和"hello  world"在身份层面是同一个词条。
     */
    private String normalizeTermIdentity(String term) {
        return TextUtil.collapseWhitespace(term).toLowerCase(Locale.ROOT);
    }

    private String normalizeTermType(String termType) {
        return TextUtil.trimToDefault(termType, DEFAULT_TERM_TYPE).toUpperCase(Locale.ROOT);
    }

    /**
     * 规范化情感极性：TABOO 类型强制 NEGATIVE（禁忌词的语义天然为负面），
     * 其他类型按用户输入或回退 NEUTRAL。
     */
    private String normalizePolarity(String polarity, String termType) {
        if ("TABOO".equals(termType)) {
            return "NEGATIVE";
        }
        return TextUtil.trimToDefault(polarity, DEFAULT_POLARITY).toUpperCase(Locale.ROOT);
    }

    private String normalizeSourceType(String sourceType) {
        return TextUtil.trimToDefault(sourceType, DEFAULT_SOURCE_TYPE).toUpperCase(Locale.ROOT);
    }

    private String normalizeScene(String scene) {
        return TextUtil.trimToDefault(scene, SCENE_PRE_PUBLISH).toUpperCase(Locale.ROOT);
    }

    private String normalizeTermId(String termId) {
        if (TextUtil.isBlank(termId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "语境词条ID不能为空");
        }
        return termId.trim();
    }

    private String sceneLabel(String scene) {
        if (SCENE_PRE_PUBLISH.equals(scene)) {
            return "发布前优化";
        }
        if ("FEEDBACK_ANALYZE".equals(scene)) {
            return "评论弹幕分析";
        }
        if ("REPORT".equals(scene)) {
            return "创作复盘";
        }
        return scene;
    }

    private CreatorContextTermResponse toResponse(CreatorContextTermRecord record) {
        return new CreatorContextTermResponse(
                record.getId(),
                record.getTermId(),
                record.getUserId(),
                record.getVideoType(),
                record.getTerm(),
                record.getTermType(),
                record.getPolarity(),
                record.getSourceType(),
                record.getSourceTaskId(),
                record.getEvidenceText(),
                record.getWeight(),
                record.getUsageCount(),
                record.getAcceptCount(),
                record.getRejectCount(),
                record.getEnabled(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
