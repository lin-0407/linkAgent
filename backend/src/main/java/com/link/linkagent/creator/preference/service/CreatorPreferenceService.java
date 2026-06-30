package com.link.linkagent.creator.preference.service;

import com.link.linkagent.creator.preference.mapper.CreatorPreferenceMapper;
import com.link.linkagent.creator.preference.model.CreatorPreferenceRecord;
import com.link.linkagent.creator.preference.model.CreatorPreferenceResponse;
import com.link.linkagent.creator.report.model.CreatorReportRecord;
import com.link.linkagent.creator.report.service.CreatorReportService;
import com.link.linkagent.creator.suggestion.service.PrePublishSuggestionService;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.util.NumberUtil;
import com.link.linkagent.util.TextUtil;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 创作者长期偏好服务 —— 管理创作者在多次创作任务中积累的创作风格偏好、历史洞察和
 * 对 AI 建议的采用/拒绝反馈，为发布前优化和复盘提供个性化的上下文参考。
 *
 * <h3>架构定位</h3>
 * 独立于通用对话记忆（ShortTermMemory / LongTermMemory），专门管理创作领域的长期偏好。
 * 业务偏好与通用对话记忆分开保存的核心理由：发布前优化只需要读取和"创作决策"相关
 * 的历史经验（如"创作者喜欢短句+数字开头的标题""创作者的内容受众偏向硬核技术"），
 * 不需要 LLM 看到创作者日常聊天的偏好（如"喜欢这样回复"），避免偏好系统中混入
 * 与创作无关的噪音数据。
 *
 * <h3>核心设计决策</h3>
 * <ol>
 *   <li><b>来源双通道</b>：
 *       <ul>
 *         <li>复盘沉淀：复盘完成后由 {@link #saveFromReport} 将 LLM 识别出的偏好洞察
 *             写入偏好表。此类记录带 sourceReportId，可追溯到具体复盘报告。</li>
 *         <li>用户反馈：用户对 AI 建议的采用/拒绝/修改后采用，由
 *             {@link #recordAdoptionFeedback} 写入偏好表。此类记录以
 *             "ADOPTION_FEEDBACK_" 前缀标识，无 sourceReportId。</li>
 *       </ul></li>
 *   <li><b>只沉淀已解析的报告</b>：{@link #saveFromReport} 只处理 PARSED 状态的报告，
 *       避免把 LLM 解析失败的原始文本（可能是乱码或不完整 JSON）误当成偏好带入后续任务。</li>
 *   <li><b>空值过滤</b>：偏好洞察为 null、空串、"[]"、"null" 时均不保存，避免存储无意义数据。</li>
 *   <li><b>提示词长度控制</b>：{@link #buildPromptContext} 生成的上下文总长度不超过
 *       {@link #PROMPT_CONTEXT_MAX_LENGTH}，防止历史偏好无限增长挤占本期文稿上下文。</li>
 *   <li><b>采用/拒绝反馈独立查询</b>：反馈记录通过专门的 Mapper 方法查询（listAdoptionFeedbackByUserId），
 *       与常规偏好记录分开，因为两者的业务语义不同：常规偏好是"LLM 根据复盘推测的认知"，
 *       反馈是"用户主动确认的真实行动"，后者在 AI 理解用户风格时权重应更高。</li>
 * </ol>
 *
 * @see PrePublishSuggestionService 发布前优化会读取偏好上下文注入到分析 prompt
 * @see CreatorReportService 复盘完成后调用 {@link #saveFromReport} 沉淀偏好洞察
 */
@Service
public class CreatorPreferenceService {

    /** 匿名用户/未登录场景下的默认用户 ID */
    private static final String DEFAULT_USER_ID = "default";
    /** 列表查询的默认返回数量 */
    private static final int DEFAULT_LIST_LIMIT = 10;
    /** 列表查询的最大返回数量，防止一次查询拉取过多数据 */
    private static final int MAX_LIST_LIMIT = 20;
    /** 注入到提示词中的历史偏好的最大条数，5 条覆盖近期几期的创作迭代趋势 */
    private static final int PROMPT_HISTORY_LIMIT = 5;
    /** 偏好上下文在提示词中的最大总长度（字符数），防止历史偏好膨胀挤压本期内容空间 */
    private static final int PROMPT_CONTEXT_MAX_LENGTH = 6000;

    /** 偏好数据的 DAO 层 */
    private final CreatorPreferenceMapper creatorPreferenceMapper;

    public CreatorPreferenceService(CreatorPreferenceMapper creatorPreferenceMapper) {
        this.creatorPreferenceMapper = creatorPreferenceMapper;
    }

    /**
     * 从复盘报告中提取创作者偏好洞察并持久化，供后续发布前优化复用。
     *
     * <p>调用时机：由 {@link CreatorReportService#analyze} 在复盘完成后调用，
     * 形成"优化 → 发布 → 复盘 → 偏好沉淀 → 优化"的闭环。
     *
     * <p>保存前置条件（任一不满足即跳过，不抛异常）：
     * <ol>
     *   <li>taskRecord 和 reportRecord 均不为 null</li>
     *   <li>报告的解析状态为 PARSED（RAW_ONLY 说明 LLM 输出不可靠，不应作为偏好来源）</li>
     *   <li>creatorPreferenceInsight 字段有实质内容（排除 null、空串、"[]"、"null"）</li>
     * </ol>
     *
     * <p>为什么 RAW_ONLY 跳过：假设 LLM 输出是截断或格式错乱的 JSON，"解析"出的
     * creatorPreferenceInsight 可能是乱码或不完整文本。把这种内容当偏好写入会导致
     * 后续发布前优化时 LLM 读到噪音数据，产生误导性建议。
     *
     * @param taskRecord   当前任务记录，提供 userId 和 taskId 用于关联
     * @param reportRecord 已生成的复盘报告记录，从中提取 creatorPreferenceInsight
     */
    public void saveFromReport(CreatorTaskRecord taskRecord, CreatorReportRecord reportRecord) {
        if (taskRecord == null
                || reportRecord == null
                || !"PARSED".equals(reportRecord.getParseStatus())
                || !hasPreferenceContent(reportRecord.getCreatorPreferenceInsight())) {
            return;
        }

        CreatorPreferenceRecord preferenceRecord = new CreatorPreferenceRecord();
        preferenceRecord.setPreferenceId(UUID.randomUUID().toString());
        preferenceRecord.setUserId(normalizeUserId(taskRecord.getUserId()));
        // sourceTaskId 和 sourceReportId 建立完整追溯链：从偏好记录可定位到具体的任务和复盘报告
        preferenceRecord.setSourceTaskId(taskRecord.getTaskId());
        preferenceRecord.setSourceReportId(reportRecord.getReportId());
        preferenceRecord.setPreferenceContent(reportRecord.getCreatorPreferenceInsight().trim());
        creatorPreferenceMapper.upsert(preferenceRecord);
    }

    /**
     * 分页查询用户的偏好记录列表（对外暴露的查询接口）。
     *
     * @param userId 用户 ID；null/空串回退 "default"
     * @param limit  返回数量上限；null/越界时使用默认值（10），上限 20
     * @return 偏好记录列表（按创建时间倒序，最新的在前）
     */
    public List<CreatorPreferenceResponse> listPreferences(String userId, Integer limit) {
        int safeLimit = NumberUtil.limitOrDefault(limit, DEFAULT_LIST_LIMIT, MAX_LIST_LIMIT);
        return creatorPreferenceMapper.listByUserId(normalizeUserId(userId), safeLimit)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 构建注入到发布前优化提示词中的偏好上下文。
     *
     * <p>由 {@link PrePublishSuggestionService} 在构建分析 prompt 时调用，
     * 将创作者的长期偏好和用户对历史建议的反馈拼接为一段上下文字符串，
     * 注入到 LLM 分析提示词中，让 AI 做分析时能参考创作者的历史风格。
     *
     * <p>上下文结构（分两段）：
     * <ol>
     *   <li><b>历史偏好内容</b>：最近 {@link #PROMPT_HISTORY_LIMIT} 期复盘沉淀的偏好洞察，
     *       格式为 "序号. 来源任务 taskId：content"。这些是 LLM 根据复盘推测的创作者认知。</li>
     *   <li><b>采用/拒绝反馈记录</b>：用户对 AI 历史建议的真实采纳行为（采用了哪些标题风格、
     *       拒绝了什么建议）。这些是用户主动确认的真实行动，对 AI 理解用户实际风格偏好
     *       的权重比复盘推测更高。无反馈记录时此段省略。</li>
     * </ol>
     *
     * <p>为什么限制条数和长度：
     * <ul>
     *   <li>条数限制：最近 5 期足够反映近期创作趋势，更多历史期的偏好可能已过时。</li>
     *   <li>长度限制：通过 {@link TextUtil#abbreviateWithSuffix} 对总长度做 6000 字截断，
     *       防止历史偏好膨胀挤压本期文稿在 LLM 上下文窗口中的空间。</li>
     * </ul>
     *
     * @param userId 用户 ID；null/空串回退 "default"
     * @return 偏好上下文字符串；无历史记录时返回 "暂无历史创作者偏好"
     */
    public String buildPromptContext(String userId) {
        List<CreatorPreferenceRecord> records = creatorPreferenceMapper.listByUserId(
                normalizeUserId(userId),
                PROMPT_HISTORY_LIMIT
        );
        if (records.isEmpty()) {
            return "暂无历史创作者偏好";
        }

        StringBuilder builder = new StringBuilder();

        // 第一段：汇总复盘沉淀的偏好洞察（LLM 推测的认知）
        for (int index = 0; index < records.size(); index++) {
            CreatorPreferenceRecord record = records.get(index);
            builder.append(index + 1)
                    .append(". 来源任务 ")
                    .append(record.getSourceTaskId())
                    .append("：")
                    .append(record.getPreferenceContent())
                    .append("\n");
        }

        // 第二段：追加采用/拒绝反馈记录（用户主动确认的真实行为），让 AI 了解用户实际偏好什么风格
        // 反馈记录的权重逻辑上高于复盘推测，因为它代表用户真实的选择而非 LLM 的猜测
        List<CreatorPreferenceRecord> feedbackRecords = creatorPreferenceMapper.listAdoptionFeedbackByUserId(
                normalizeUserId(userId),
                PROMPT_HISTORY_LIMIT
        );
        if (!feedbackRecords.isEmpty()) {
            builder.append("\n你的标题风格偏好（基于历史采用/拒绝记录）：\n");
            for (int index = 0; index < feedbackRecords.size(); index++) {
                CreatorPreferenceRecord record = feedbackRecords.get(index);
                builder.append(index + 1)
                        .append(". ")
                        .append(record.getPreferenceContent())
                        .append("\n");
            }
        }

        // 最终截断：防止历史偏好 + 反馈记录总长度超出令牌预算
        return TextUtil.abbreviateWithSuffix(
                builder.toString().trim(),
                PROMPT_CONTEXT_MAX_LENGTH,
                "\n[历史偏好过长，已截断用于本次分析]"
        );
    }

    /**
     * 记录用户对 AI 建议的采用/拒绝反馈。
     *
     * <p>调用时机：用户在发布前优化中确认采用某个标题风格、手动修改后采用、
     * 或显式拒绝某条建议时调用。用户的真实选择被写入偏好表，供后续分析时参考。
     *
     * <p>实现注意：
     * <ul>
     *   <li>与复盘沉淀记录使用不同的 Mapper 方法和 sourceReportId 标记区分：
     *       复盘记录走 {@code creatorPreferenceMapper.upsert}，反馈记录走
     *       {@code creatorPreferenceMapper.upsertAdoptionFeedback}，两者分表/分索引存储。</li>
     *   <li>sourceReportId 使用 "ADOPTION_FEEDBACK_" + preferenceType 格式标记，
     *       而非真实 reportId。这样在查询时可以按前缀过滤反馈记录，也能区分具体反馈类型。</li>
     *   <li>preferenceContent 以 "[ADOPTED/MODIFIED/REJECTED] 描述" 格式存储，
     *       在 buildPromptContext 中直接拼接展示，无需二次解析。</li>
     * </ul>
     *
     * @param userId         用户 ID；null/空串回退 "default"
     * @param taskId         当前任务 ID，用于追溯反馈来源
     * @param preferenceType 偏好类型：ADOPTED（采用）、MODIFIED（修改后采用）、REJECTED（拒绝）
     * @param description    偏好描述，例如"采用短句+数字开头的标题风格，拒绝了长句偏严肃风格"
     */
    public void recordAdoptionFeedback(String userId, String taskId, String preferenceType, String description) {
        if (TextUtil.isBlank(description)) {
            return;
        }
        CreatorPreferenceRecord record = new CreatorPreferenceRecord();
        record.setPreferenceId(UUID.randomUUID().toString());
        record.setUserId(normalizeUserId(userId));
        record.setSourceTaskId(taskId);
        // 采用/拒绝反馈没有真实的 sourceReportId，使用 "ADOPTION_FEEDBACK_" + 类型 作为标记
        // 该标记在查询层用于区分常规偏好记录和反馈记录，并按类型过滤
        record.setSourceReportId("ADOPTION_FEEDBACK_" + preferenceType);
        record.setPreferenceContent("[" + preferenceType + "] " + description.trim());
        creatorPreferenceMapper.upsertAdoptionFeedback(record);
    }

    /**
     * 检查偏好内容是否为有效值。
     * 排除 null、空串、空数组 "[]"、字符串 "null" 四种无意义情况，
     * LLM 有时会输出这些值表示"无偏好"
     */
    private boolean hasPreferenceContent(String preferenceContent) {
        if (TextUtil.isBlank(preferenceContent)) {
            return false;
        }
        String normalized = preferenceContent.trim();
        return !"[]".equals(normalized) && !"null".equalsIgnoreCase(normalized);
    }

    /** 标准化用户 ID：null/空串回退 "default"，非空 trim 去首尾空格 */
    private String normalizeUserId(String userId) {
        return TextUtil.trimToDefault(userId, DEFAULT_USER_ID);
    }

    /** 将数据库记录转为前端响应对象 */
    private CreatorPreferenceResponse toResponse(CreatorPreferenceRecord record) {
        return new CreatorPreferenceResponse(
                record.getId(),
                record.getPreferenceId(),
                record.getUserId(),
                record.getSourceTaskId(),
                record.getSourceReportId(),
                record.getPreferenceContent(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
