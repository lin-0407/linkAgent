package com.link.linkagent.creator.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.competitor.mapper.CreatorCompetitorMapper;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorReportRecord;
import com.link.linkagent.creator.feedback.mapper.CreatorFeedbackMapper;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackReportRecord;
import com.link.linkagent.creator.media.workflow.CreatorMediaWorkflowGateService;
import com.link.linkagent.creator.preference.service.CreatorPreferenceService;
import com.link.linkagent.creator.report.mapper.CreatorReportMapper;
import com.link.linkagent.creator.report.model.CreatorReportAnalyzeRequest;
import com.link.linkagent.creator.report.model.CreatorReportAnalysisOutput;
import com.link.linkagent.creator.report.model.CreatorReportRecord;
import com.link.linkagent.creator.report.model.CreatorReportResponse;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.suggestion.service.PrePublishSuggestionService;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorMaterialType;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.model.CreatorTaskStatus;
import com.link.linkagent.creator.task.model.CreatorTaskSummaryRecord;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.TextUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 创作复盘报告服务 —— 创作者工作流终段：聚合发布前优化建议、观众反馈分析和竞品对比结果，
 * 调用 LLM 生成一份结构化的全维度创作复盘报告。
 *
 * <h3>架构定位</h3>
 * 位于创作者工作流管道的最末端（发布前优化 → 反馈分析/竞品分析 → 复盘报告）。该服务
 * 的角色是"汇总者"而非"入口"——它只读取已落库的前序阶段产物，不直接访问 B 站 API
 * 或原始评论数据，避免把复盘阶段变成新的数据入口，导致职责边界模糊和重复拉取开销。
 *
 * <h3>核心设计决策</h3>
 * <ol>
 *   <li><b>前置依赖校验</b>：analyze 方法强制要求发布前建议、反馈分析、竞品分析都已
 *       完成，任一缺失即报错。这是有意为之——缺环节的复盘是片面的，给创作者错误信号
 *       比不给信号更危险。</li>
 *   <li><b>跨期趋势对比</b>：复盘报告不仅看当期表现，还会拉取同一创作者近期已完成
 *       复盘的历史报告，让 LLM 识别"反复出现的问题"和"持续进步的方向"，输出比单期
 *       复盘更有深度。</li>
 *   <li><b>结构完整性校验</b>：通过 LLMService 的结构化输出能力生成强类型结果；
 *       JSON 缺少必填字段时由 DTO 构造校验触发模型重试，避免继续落库空报告。</li>
 *   <li><b>创作者偏好沉淀</b>：复盘完成后自动将 LLM 识别出的创作者偏好洞察写入偏好表，
 *       供后续发布前优化复用，形成"优化→发布→复盘→偏好沉淀→优化"的闭环。</li>
 * </ol>
 *
 * @see CreatorPreferenceService 复盘完成后将偏好洞察写入该服务
 * @see PrePublishSuggestionService 复盘依赖的发布前优化建议来源
 */
@Service
public class CreatorReportService {

    /** 素材内容在提示词中的最大长度（字符数），防止超长文稿撑爆 LLM 上下文窗口 */
    private static final int MATERIAL_MAX_LENGTH = 4000;

    /** 单个段落（如摘要、总判断等）在提示词中的最大长度，防止某一段落过长挤压其他段落的 token 预算 */
    private static final int SECTION_MAX_LENGTH = 8000;

    /** 跨期对比拉取的历史报告数量上限。3 期足够看趋势，更多则 Token 成本过高且边际信息增益低 */
    private static final int CROSS_PERIOD_LIMIT = 3;

    /** 跨期对比中每期报告的单个段落最大长度，比 SECTION_MAX_LENGTH 更紧以控制上下文总量 */
    private static final int CROSS_PERIOD_SECTION_MAX_LENGTH = 3000;

    /** 任务 CRUD 的 DAO 层 */
    private final CreatorTaskMapper creatorTaskMapper;
    /** 发布前优化建议的 DAO，用于读取前序阶段的产物 */
    private final CreatorSuggestionMapper creatorSuggestionMapper;
    /** 观众反馈分析报告的 DAO */
    private final CreatorFeedbackMapper creatorFeedbackMapper;
    /** 竞品对比报告的 DAO */
    private final CreatorCompetitorMapper creatorCompetitorMapper;
    /** 复盘报告自身的 DAO */
    private final CreatorReportMapper creatorReportMapper;
    /** 创作者偏好服务，用于在复盘完成后沉淀 LLM 识别出的偏好洞察 */
    private final CreatorPreferenceService creatorPreferenceService;
    /** LLM 调用入口 */
    private final LLMService llmService;
    /** JSON 处理器，用于序列化报告字段并支持 Markdown 导出 */
    private final ObjectMapper objectMapper;
    /** 提示词模板服务，管理系统提示词和用户提示词模板 */
    private final PromptService promptService;
    /** 发布后流程门禁，避免存量前置数据绕过成片试映重新生成复盘报告。 */
    private final CreatorMediaWorkflowGateService mediaWorkflowGateService;

    public CreatorReportService(CreatorTaskMapper creatorTaskMapper,
                                CreatorSuggestionMapper creatorSuggestionMapper,
                                CreatorFeedbackMapper creatorFeedbackMapper,
                                CreatorCompetitorMapper creatorCompetitorMapper,
                                CreatorReportMapper creatorReportMapper,
                                CreatorPreferenceService creatorPreferenceService,
                                 LLMService llmService,
                                 ObjectMapper objectMapper,
                                 PromptService promptService,
                                 CreatorMediaWorkflowGateService mediaWorkflowGateService) {
        this.creatorTaskMapper = creatorTaskMapper;
        this.creatorSuggestionMapper = creatorSuggestionMapper;
        this.creatorFeedbackMapper = creatorFeedbackMapper;
        this.creatorCompetitorMapper = creatorCompetitorMapper;
        this.creatorReportMapper = creatorReportMapper;
        this.creatorPreferenceService = creatorPreferenceService;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.promptService = promptService;
        this.mediaWorkflowGateService = mediaWorkflowGateService;
    }

    /**
     * 执行完整的创作复盘分析，聚合发布前建议、反馈分析和竞品对比结果，调用 LLM 生成结构化复盘报告。
     *
     * <p>执行流程：
     * <ol>
     *   <li>校验任务存在性</li>
     *   <li>强制校验三道前序关口：发布前建议、反馈分析、竞品分析均需已完成（缺一则抛错）</li>
     *   <li>拉取任务关联的素材文件</li>
     *   <li>构建跨期对比上下文（创作者历史报告摘要）</li>
     *   <li>调用 LLM 生成并校验结构化复盘报告</li>
     *   <li>将强类型输出映射为现有数据库字段，upsert 入库</li>
     *   <li>将 LLM 识别出的创作者偏好洞察沉淀到偏好表</li>
     *   <li>将任务状态推进到 ANALYZED</li>
     * </ol>
     *
     * <p>为什么三道关口缺一就必须报错：复盘报告需要综合多维度信息才能给出有价值的分析，
     * 缺了任何一环（如没有反馈分析），报告就变成了"只看创作者自己说了什么"，失去了
     * 复盘应有的客观性和全面性，给创作者错误信号比不给信号更危险。
     *
     * @param taskId  创作任务 ID
     * @param request 用户对复盘分析的自定义要求（聚焦方向、补充说明等）
     * @return 结构化的复盘报告
     * @throws ResponseStatusException 前序阶段任一未完成时抛 BAD_REQUEST
     */
    @Transactional
    public CreatorReportResponse analyze(String taskId, CreatorReportAnalyzeRequest request) {
        CreatorTaskRecord taskRecord = getTaskReadyForReport(taskId);
        // 三道前序关口强制校验：缺一不可，保证复盘报告的综合性和客观性
        CreatorSuggestionRecord suggestionRecord = creatorSuggestionMapper.findByTaskId(taskRecord.getTaskId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先生成发布前优化建议"));
        CreatorFeedbackReportRecord feedbackReportRecord = creatorFeedbackMapper.findReportByTaskId(taskRecord.getTaskId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先完成评论弹幕分析"));
        CreatorCompetitorReportRecord competitorReportRecord = creatorCompetitorMapper.findReportByTaskId(taskRecord.getTaskId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先完成同类型视频竞品分析"));
        List<CreatorMaterialRecord> materials = creatorTaskMapper.listMaterialsByTaskId(taskRecord.getTaskId());

        // 拉取同一创作者最近几期复盘报告摘要，供 LLM 识别"反复出现的问题"和"持续进步的方向"
        String crossPeriodContext = buildCrossPeriodContext(taskRecord);

        CreatorReportAnalysisOutput analysisOutput;
        // 用 try-with-resources 打开用量上下文，确保 LLM 调用被 Langfuse 正确追踪
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.open(taskRecord.getTaskId(), "创作复盘报告")) {
            analysisOutput = llmService.chatStructured(
                    buildSystemPrompt(),
                    buildUserPrompt(taskRecord, materials, suggestionRecord, feedbackReportRecord, competitorReportRecord,
                            crossPeriodContext, request),
                    CreatorReportAnalysisOutput.class
            );
        }
        CreatorReportRecord reportRecord = buildReportRecord(taskRecord.getTaskId(), analysisOutput);
        creatorReportMapper.upsert(reportRecord);
        // 复盘完成后，将 LLM 识别出的创作者偏好洞察沉淀到偏好表，供后续发布前优化复用
        creatorPreferenceService.saveFromReport(taskRecord, reportRecord);
        creatorTaskMapper.updateTaskStatus(taskRecord.getTaskId(), CreatorTaskStatus.ANALYZED.name());
        return getReport(taskRecord.getTaskId());
    }

    /**
     * 根据任务 ID 查询已生成的复盘报告。
     *
     * @param taskId 创作任务 ID
     * @return 结构化的复盘报告
     */
    public CreatorReportResponse getReport(String taskId) {
        getTaskRecord(taskId);
        return toResponse(getReportRecord(taskId));
    }

    /**
     * 将复盘报告导出为 Markdown 文本，用于创作者下载/分享/存档。
     *
     * <p>若读取到历史 RAW_ONLY 报告，Markdown 中会额外附加原始输出段，
     * 保留旧数据的排障线索；新报告只有结构校验成功后才会落库。
     *
     * @param taskId 创作任务 ID
     * @return Markdown 格式的复盘报告全文
     */
    public String exportMarkdown(String taskId) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        CreatorReportRecord reportRecord = getReportRecord(taskRecord.getTaskId());
        return buildMarkdownReport(taskRecord, reportRecord);
    }

    /** 按任务 ID 查任务记录，不存在抛 404 */
    private CreatorTaskRecord getTaskRecord(String taskId) {
        return creatorTaskMapper.findTaskByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在"));
    }

    /**
     * 复盘生成会调用模型并覆盖报告、偏好和任务状态，因此不能把它当作历史结果查询绕过试映。
     */
    private CreatorTaskRecord getTaskReadyForReport(String taskId) {
        mediaWorkflowGateService.ensureMediaEnabled("创作复盘");
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        mediaWorkflowGateService.ensureReadyForPostPublish(
                taskRecord.getTaskId(),
                taskRecord.getUserId(),
                "创作复盘"
        );
        return taskRecord;
    }

    /** 按任务 ID 查复盘报告记录，不存在抛 404 */
    private CreatorReportRecord getReportRecord(String taskId) {
        return creatorReportMapper.findByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创作复盘报告不存在"));
    }

    /** 将已完成字段校验的模型输出映射到现有报告表。 */
    private CreatorReportRecord buildReportRecord(String taskId, CreatorReportAnalysisOutput analysisOutput) {
        CreatorReportRecord record = new CreatorReportRecord();
        record.setReportId(UUID.randomUUID().toString());
        record.setTaskId(taskId);
        record.setContentSummary(analysisOutput.contentSummary());
        record.setCoreSellingPoints(writeJson(analysisOutput.coreSellingPoints()));
        record.setTitleDescriptionReview(writeJson(analysisOutput.titleDescriptionReview()));
        record.setAudienceFeedbackSummary(analysisOutput.audienceFeedbackSummary());
        record.setCompetitorComparison(writeJson(analysisOutput.competitorComparison()));
        record.setControversyAndMisunderstanding(writeJson(analysisOutput.controversyAndMisunderstanding()));
        record.setNextActionSuggestions(writeJson(analysisOutput.nextActionSuggestions()));
        record.setCreatorPreferenceInsight(writeJson(analysisOutput.creatorPreferenceInsight()));
        record.setOverallConclusion(analysisOutput.overallConclusion());
        record.setRawOutput(writeJson(analysisOutput));
        record.setParseStatus("PARSED");
        return record;
    }

    /** 将结构化模型对象或列表序列化为数据库现有 JSON 字段。 */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("结构化复盘报告序列化失败", exception);
        }
    }

    /**
     * 构建跨期对比上下文，让 LLM 在复盘本期时能看到创作者的历史表现趋势。
     *
     * <p>为什么需要跨期对比：单期复盘只能看到"这一期做了什么"，无法回答"创作者是
     * 在进步还是退步""哪些老问题反复出现"。跨期对比提供了时间维度的参照系，
     * 让 LLM 的分析从"静态点评"升级为"动态趋势洞察"。
     *
     * <p>实现细节：
     * <ul>
     *   <li>拉取同一创作者最近的 N 期任务（多拉一些以留冗余），从中过滤掉当前任务和
     *       未完成复盘的任务，取前 {@link #CROSS_PERIOD_LIMIT} 期。</li>
     *   <li>每期只取"总判断、核心卖点、观众反馈、下一步建议"四个最高信息量的字段，
     *       而非全文，避免上下文膨胀。</li>
     *   <li>单期报告读取失败不中断整体流程（静默跳过），因为没有历史趋势只是少了维度，
     *       不应阻止本期复盘生成。</li>
     *   <li>最终拼接后的文本通过 {@link TextUtil#abbreviateWithSuffix} 做总长度截断，
     *       上限为 {@link #CROSS_PERIOD_SECTION_MAX_LENGTH} * 2。</li>
     * </ul>
     *
     * @param currentTask 当前任务记录，用于过滤掉自身
     * @return 跨期对比提示词上下文；若为第一期则返回无历史记录提示
     */
    private String buildCrossPeriodContext(CreatorTaskRecord currentTask) {
        String userId = TextUtil.trimToDefault(currentTask.getUserId(), "default");
        // 多拉 10 条做冗余，因为过滤掉当前任务和未完成复盘后可能不足 CROSS_PERIOD_LIMIT 条
        List<CreatorTaskSummaryRecord> recentTasks = creatorTaskMapper.listTasksByUser(userId, CROSS_PERIOD_LIMIT + 10);
        // 过滤掉当前任务和未完成复盘的任务，只取已完成复盘的
        List<CreatorTaskSummaryRecord> completedTasks = recentTasks.stream()
                .filter(task -> !task.getTaskId().equals(currentTask.getTaskId()))
                .filter(task -> CreatorTaskStatus.ANALYZED.name().equals(task.getStatus()))
                .limit(CROSS_PERIOD_LIMIT)
                .toList();

        if (completedTasks.isEmpty()) {
            return "暂无历史复盘记录可供对比（这是该创作者的第一期复盘）。";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("以下是该创作者最近 ").append(completedTasks.size()).append(" 期已完成复盘的视频摘要：\n\n");
        for (int i = 0; i < completedTasks.size(); i++) {
            CreatorTaskSummaryRecord task = completedTasks.get(i);
            try {
                CreatorReportRecord report = creatorReportMapper.findByTaskId(task.getTaskId()).orElse(null);
                // 只取 PARSED 状态的报告：RAW_ONLY 说明 LLM 输出不可靠，跳过
                if (report != null && "PARSED".equals(report.getParseStatus())) {
                    builder.append("--- 第").append(i + 1).append("期：")
                            .append(TextUtil.trimToDefault(task.getTaskName(), "未命名"))
                            .append(" ---\n");
                    // 只取四个最有信息量的维度做跨期对比，每个维度都做长度截断防膨胀
                    builder.append("总判断：")
                            .append(limitSection(TextUtil.trimToDefault(report.getOverallConclusion(), "无"), CROSS_PERIOD_SECTION_MAX_LENGTH))
                            .append("\n");
                    builder.append("核心卖点：")
                            .append(limitSection(TextUtil.trimToDefault(report.getCoreSellingPoints(), "无"), CROSS_PERIOD_SECTION_MAX_LENGTH))
                            .append("\n");
                    builder.append("观众反馈：")
                            .append(limitSection(TextUtil.trimToDefault(report.getAudienceFeedbackSummary(), "无"), CROSS_PERIOD_SECTION_MAX_LENGTH))
                            .append("\n");
                    builder.append("下一步建议：")
                            .append(limitSection(TextUtil.trimToDefault(report.getNextActionSuggestions(), "无"), CROSS_PERIOD_SECTION_MAX_LENGTH))
                            .append("\n\n");
                }
            } catch (Exception ignored) {
                // 单期报告读取失败不影响整体流程：跨期对比是锦上添花，不是必备
            }
        }

        // 引导 LLM 从三个维度做跨期分析：进步/退步、反复问题、方向优化
        builder.append("请基于以上历史趋势，在本期复盘中分析：\n");
        builder.append("1. 本期相比前几期是否有明显进步或退步。\n");
        builder.append("2. 哪些问题在多期中反复出现（说明需要重点改进）。\n");
        builder.append("3. 创作者的内容方向是否在持续优化。\n");

        // 最终做总长度截断：2 倍 CROSS_PERIOD_SECTION_MAX_LENGTH 作为跨期上下文的上限
        // 这样即使有多期报告，总 token 消耗也是可控的
        return TextUtil.abbreviateWithSuffix(
                builder.toString().trim(),
                CROSS_PERIOD_SECTION_MAX_LENGTH * 2,
                "\n[跨期对比内容过长，已截断]"
        );
    }

    /** 从提示词模板服务中加载复盘报告的系统提示词 */
    private String buildSystemPrompt() {
        return promptService.get("report.system");
    }

    /**
     * 构建复盘报告的用户提示词，将所有前序阶段产物 + 素材 + 跨期对比 + 用户自定义要求
     * 拼接为一个完整的 prompt，通过模板渲染确保格式一致。
     *
     * @param taskRecord          当前任务记录
     * @param materials           任务关联的素材列表
     * @param suggestionRecord    发布前优化建议
     * @param feedbackReportRecord 观众反馈分析报告
     * @param competitorReportRecord 竞品对比报告
     * @param crossPeriodContext  跨期对比上下文
     * @param request             用户自定义复盘要求
     * @return 完整的用户提示词字符串
     */
    private String buildUserPrompt(CreatorTaskRecord taskRecord,
                                   List<CreatorMaterialRecord> materials,
                                   CreatorSuggestionRecord suggestionRecord,
                                   CreatorFeedbackReportRecord feedbackReportRecord,
                                   CreatorCompetitorReportRecord competitorReportRecord,
                                   String crossPeriodContext,
                                   CreatorReportAnalyzeRequest request) {
        return promptService.render("report.user", Map.of(
                "taskName", taskRecord.getTaskName(),
                "taskId", taskRecord.getTaskId(),
                "customGuidance", TextUtil.trimToDefault(request.customGuidance(), "未提供"),
                "reviewFocus", TextUtil.trimToDefault(request.reviewFocus(), "未提供"),
                "extraRequirement", TextUtil.trimToDefault(request.extraRequirement(), "未提供"),
                "materials", buildMaterialPrompt(materials),
                "suggestionResult", buildSuggestionPrompt(suggestionRecord),
                "feedbackResult", buildFeedbackReportPrompt(feedbackReportRecord),
                "competitorResult", buildCompetitorReportPrompt(competitorReportRecord),
                "crossPeriodContext", crossPeriodContext
        ));
    }

    /** 将素材列表格式化为提示词片段，每种素材用中文名称标注，内容做长度截断 */
    private String buildMaterialPrompt(List<CreatorMaterialRecord> materials) {
        if (materials.isEmpty()) {
            return "未提供";
        }
        StringBuilder builder = new StringBuilder();
        for (CreatorMaterialRecord material : materials) {
            builder.append("\n【")
                    .append(toChineseMaterialName(material.getMaterialType()))
                    .append("】\n")
                    .append(limitSection(material.getContent(), MATERIAL_MAX_LENGTH))
                    .append("\n");
        }
        return builder.toString();
    }

    /**
     * 将发布前优化建议格式化为提示词片段。
     * 每个字段用中文标签标注，空值以"未提供"兜底并做长度截断，防止 null 值和超长文本污染 prompt。
     */
    private String buildSuggestionPrompt(CreatorSuggestionRecord record) {
        return """
                内容摘要：%s
                创作者困境：%s
                目标受众：%s
                观众钩子：%s
                内容定位：%s
                核心卖点：%s
                风险点：%s
                标题建议：%s
                简介建议：%s
                可执行修改计划：%s
                标签建议：%s
                分区建议：%s
                解析状态：%s
                """.formatted(
                normalizeSection(record.getContentSummary()),
                normalizeSection(record.getCreatorDilemma()),
                normalizeSection(record.getAudienceProfile()),
                normalizeSection(record.getAudienceHook()),
                normalizeSection(record.getContentPositioning()),
                normalizeSection(record.getSellingPoints()),
                normalizeSection(record.getRiskPoints()),
                normalizeSection(record.getTitleSuggestions()),
                normalizeSection(record.getDescriptionSuggestion()),
                normalizeSection(record.getActionableRevisionPlan()),
                normalizeSection(record.getTagSuggestions()),
                normalizeSection(record.getPartitionSuggestion()),
                normalizeSection(record.getParseStatus())
        );
    }

    /** 将观众反馈分析报告格式化为提示词片段 */
    private String buildFeedbackReportPrompt(CreatorFeedbackReportRecord record) {
        return """
                反馈摘要：%s
                创作者反馈困境：%s
                观众核心关注：%s
                高频观点：%s
                情绪倾向：%s
                争议点：%s
                误解点：%s
                误解来源分析：%s
                下一期内容建议：%s
                互动回应建议：%s
                反馈行动计划：%s
                解析状态：%s
                """.formatted(
                normalizeSection(record.getFeedbackSummary()),
                normalizeSection(record.getCreatorFeedbackDilemma()),
                normalizeSection(record.getAudienceCoreConcern()),
                normalizeSection(record.getHotTopics()),
                normalizeSection(record.getSentimentSummary()),
                normalizeSection(record.getControversyPoints()),
                normalizeSection(record.getMisunderstandingPoints()),
                normalizeSection(record.getMisunderstandingSourceAnalysis()),
                normalizeSection(record.getNextContentSuggestions()),
                normalizeSection(record.getInteractionSuggestions()),
                normalizeSection(record.getFeedbackActionPlan()),
                normalizeSection(record.getParseStatus())
        );
    }

    /** 将竞品对比报告格式化为提示词片段 */
    private String buildCompetitorReportPrompt(CreatorCompetitorReportRecord record) {
        return """
                竞品整体打法：%s
                竞品优势：%s
                本视频优势：%s
                本视频短板：%s
                差距分析：%s
                改进建议：%s
                差异化策略：%s
                解析状态：%s
                """.formatted(
                normalizeSection(record.getCompetitorSummary()),
                normalizeSection(record.getCompetitorAdvantages()),
                normalizeSection(record.getOwnAdvantages()),
                normalizeSection(record.getOwnDisadvantages()),
                normalizeSection(record.getGapAnalysis()),
                normalizeSection(record.getImprovementSuggestions()),
                normalizeSection(record.getDifferentiationStrategy()),
                normalizeSection(record.getParseStatus())
        );
    }

    /**
     * 统一处理段落文本：null/空串回退"未提供"，再按 {@link #SECTION_MAX_LENGTH} 截断。
     * 所有 buildXxxPrompt 方法通过此方法保证一致的空值兜底和长度控制。
     */
    private String normalizeSection(String value) {
        return limitSection(TextUtil.trimToDefault(value, "未提供"), SECTION_MAX_LENGTH);
    }

    /**
     * 对段落文本做长度截断，null/空串回退"未提供"。
     * 截断时使用 {@link TextUtil#abbreviateWithSuffix} 做字符级精确截断，并附加截断标记。
     *
     * @param value     原始段落值（可能为 null）
     * @param maxLength 最大允许长度（字符）
     * @return 截断后的文本
     */
    private String limitSection(String value, int maxLength) {
        String normalized = TextUtil.trimToDefault(value, "未提供");
        return TextUtil.abbreviateWithSuffix(
                normalized,
                maxLength,
                "\n[内容过长，已截断用于本次复盘]"
        );
    }

    /** 将素材类型枚举名转换为中文展示名称，未匹配到的类型原样返回（兼容未来新增类型） */
    private String toChineseMaterialName(String materialType) {
        if (CreatorMaterialType.TITLE_DRAFT.name().equals(materialType)) {
            return "标题草稿";
        }
        if (CreatorMaterialType.DESCRIPTION_DRAFT.name().equals(materialType)) {
            return "简介草稿";
        }
        if (CreatorMaterialType.MANUSCRIPT.name().equals(materialType)) {
            return "文稿";
        }
        if (CreatorMaterialType.SUBTITLE.name().equals(materialType)) {
            return "字幕";
        }
        return materialType;
    }

    /**
     * 将复盘报告构建为 Markdown 格式全文，用于下载/分享/存档场景。
     *
     * <p>Markdown 结构：
     * <ul>
     *   <li>一级标题：任务名称 + "创作复盘报告"</li>
     *   <li>元信息：任务ID、报告ID、解析状态、生成时间、更新时间</li>
     *   <li>正文各段落：依次输出内容摘要、核心卖点、标题简介复盘等 9 个分析维度</li>
     *   <li>兜底段落：若解析状态非 PARSED，额外输出原始输出段保留排障证据</li>
     * </ul>
     *
     * <p>为什么 RAW_ONLY 时必须额外输出原始文本：解析失败说明 LLM 输出格式异常，
     * 若仅展示空字段，创作者和开发者都无法知道 LLM 实际输出了什么，失去排障线索。
     * 原始输出段在 PARSED 状态下不输出，避免干扰正常阅读体验。
     */
    private String buildMarkdownReport(CreatorTaskRecord taskRecord, CreatorReportRecord reportRecord) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ")
                .append(TextUtil.trimToDefault(TextUtil.collapseWhitespace(taskRecord.getTaskName()), "未命名任务"))
                .append(" 创作复盘报告\n\n");
        builder.append("- 任务ID：").append(taskRecord.getTaskId()).append("\n");
        builder.append("- 报告ID：").append(reportRecord.getReportId()).append("\n");
        builder.append("- 解析状态：").append(TextUtil.trimToDefault(reportRecord.getParseStatus(), "未提供")).append("\n");
        builder.append("- 生成时间：").append(reportRecord.getCreateTime() == null ? "未提供" : reportRecord.getCreateTime()).append("\n");
        builder.append("- 更新时间：").append(reportRecord.getUpdateTime() == null ? "未提供" : reportRecord.getUpdateTime()).append("\n\n");

        appendMarkdownSection(builder, "内容摘要", reportRecord.getContentSummary());
        appendMarkdownSection(builder, "核心卖点", reportRecord.getCoreSellingPoints());
        appendMarkdownSection(builder, "标题简介复盘", reportRecord.getTitleDescriptionReview());
        appendMarkdownSection(builder, "观众反馈摘要", reportRecord.getAudienceFeedbackSummary());
        appendMarkdownSection(builder, "竞品对照结论", reportRecord.getCompetitorComparison());
        appendMarkdownSection(builder, "争议与误解", reportRecord.getControversyAndMisunderstanding());
        appendMarkdownSection(builder, "下一步动作建议", reportRecord.getNextActionSuggestions());
        appendMarkdownSection(builder, "创作者偏好洞察", reportRecord.getCreatorPreferenceInsight());
        appendMarkdownSection(builder, "复盘总判断", reportRecord.getOverallConclusion());

        if (!"PARSED".equals(reportRecord.getParseStatus())) {
            // 解析失败时必须保留原始输出，否则导出的报告会丢失排查 LLM 输出格式问题的关键证据
            appendMarkdownSection(builder, "原始输出", reportRecord.getRawOutput());
        }
        return builder.toString();
    }

    /** 向 Markdown 追加一个二级标题段落，值为空时显示"未提供" */
    private void appendMarkdownSection(StringBuilder builder, String title, String value) {
        builder.append("## ").append(title).append("\n\n");
        builder.append(formatMarkdownValue(value)).append("\n\n");
    }

    /**
     * 将报告字段值格式化为 Markdown 文本。
     * 若值为合法 JSON，递归展开为缩进 Markdown 列表；若为纯文本，直接返回。
     * JSON 解析失败时回退纯文本——优先保证输出可用，不因格式问题丢失内容。
     */
    private String formatMarkdownValue(String value) {
        String normalized = TextUtil.trimToDefault(value, "未提供");
        try {
            JsonNode rootNode = objectMapper.readTree(normalized);
            StringBuilder builder = new StringBuilder();
            appendJsonNodeMarkdown(builder, rootNode, 0);
            return TextUtil.trimToDefault(builder.toString(), "未提供");
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            // JSON 解析失败回退纯文本：格式降级但内容不丢
            return normalized;
        }
    }

    /**
     * 递归将 JSON 节点展开为 Markdown 缩进列表。
     * 分发策略：值节点 → 标量；数组 → {@link #appendJsonArrayMarkdown}；对象 → {@link #appendJsonObjectMarkdown}。
     */
    private void appendJsonNodeMarkdown(StringBuilder builder, JsonNode node, int indent) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            builder.append(indent(indent)).append("未提供\n");
            return;
        }
        if (node.isValueNode()) {
            builder.append(indent(indent)).append(toMarkdownScalar(node)).append("\n");
            return;
        }
        if (node.isArray()) {
            appendJsonArrayMarkdown(builder, node, indent);
            return;
        }
        appendJsonObjectMarkdown(builder, node, indent);
    }

    /** 将 JSON 数组展开为 Markdown 列表项，元素为对象时尝试提取摘要字段做首行概览 */
    private void appendJsonArrayMarkdown(StringBuilder builder, JsonNode arrayNode, int indent) {
        if (arrayNode.size() == 0) {
            builder.append(indent(indent)).append("- 未提供\n");
            return;
        }
        for (JsonNode item : arrayNode) {
            if (item.isObject()) {
                builder.append(indent(indent)).append("- ").append(resolveObjectSummary(item)).append("\n");
                appendJsonObjectMarkdown(builder, item, indent + 2);
            } else if (item.isArray()) {
                builder.append(indent(indent)).append("-\n");
                appendJsonArrayMarkdown(builder, item, indent + 2);
            } else {
                builder.append(indent(indent)).append("- ").append(toMarkdownScalar(item)).append("\n");
            }
        }
    }

    /** 将 JSON 对象展开为 Markdown 缩进列表，key 通过 {@link #labelForReportKey} 转为中文标签 */
    private void appendJsonObjectMarkdown(StringBuilder builder, JsonNode objectNode, int indent) {
        Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
        if (!fields.hasNext()) {
            builder.append(indent(indent)).append("- 未提供\n");
            return;
        }
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode childNode = field.getValue();
            String label = labelForReportKey(field.getKey());
            if (childNode == null || childNode.isNull() || childNode.isMissingNode()) {
                builder.append(indent(indent)).append("- **").append(label).append("**：未提供\n");
                continue;
            }
            if (childNode.isValueNode()) {
                builder.append(indent(indent))
                        .append("- **")
                        .append(label)
                        .append("**：")
                        .append(toMarkdownScalar(childNode))
                        .append("\n");
                continue;
            }
            builder.append(indent(indent)).append("- **").append(label).append("**：\n");
            appendJsonNodeMarkdown(builder, childNode, indent + 2);
        }
    }

    /**
     * 尝试从 JSON 对象的预设摘要字段中提取一行摘要文本，用于 Markdown 列表的首行概览。
     *
     * <p>查找顺序：suggestion > point > title > topic > target > benchmarkConclusion > titleConclusion，
     * 第一个命中且非空的字符串值即为摘要。若所有字段都不存在，返回"条目"。
     */
    private String resolveObjectSummary(JsonNode objectNode) {
        List<String> summaryKeys = List.of(
                "suggestion",
                "point",
                "title",
                "topic",
                "target",
                "benchmarkConclusion",
                "titleConclusion"
        );
        for (String key : summaryKeys) {
            JsonNode valueNode = objectNode.get(key);
            if (valueNode != null && valueNode.isValueNode()) {
                String value = TextUtil.trimToDefault(valueNode.asText(), "");
                if (TextUtil.hasText(value)) {
                    return value;
                }
            }
        }
        return "条目";
    }

    /** 将 JSON 标量值转为 Markdown 展示文本：布尔值转"是/否"，其他值取其文本表示，null/空回退"未提供" */
    private String toMarkdownScalar(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "未提供";
        }
        if (node.isBoolean()) {
            return node.asBoolean() ? "是" : "否";
        }
        return TextUtil.trimToDefault(node.asText(), "未提供");
    }

    /** 将复盘报告 JSON 字段 key 映射为中文可读标签，未覆盖的 key 原样返回（兼容未来新增字段） */
    private String labelForReportKey(String key) {
        return switch (key) {
            case "titleConclusion" -> "标题结论";
            case "descriptionConclusion" -> "简介结论";
            case "tagAndPartitionConclusion" -> "标签与分区结论";
            case "riskReminder" -> "风险提醒";
            case "benchmarkConclusion" -> "对标结论";
            case "ownAdvantages" -> "本视频优势";
            case "ownDisadvantages" -> "本视频短板";
            case "differentiationStrategy" -> "差异化策略";
            case "point" -> "问题点";
            case "impact" -> "影响";
            case "action" -> "处理建议";
            case "suggestion" -> "动作建议";
            case "reason" -> "依据";
            case "priority" -> "优先级";
            case "title" -> "标题";
            case "topic" -> "选题";
            case "target" -> "目标";
            default -> key;
        };
    }

    /** 生成指定数量的空格缩进，用于 Markdown 层级展示（Math.max(0, count) 防止负数异常） */
    private String indent(int count) {
        return " ".repeat(Math.max(0, count));
    }

    /** 将数据库记录转为前端响应对象 */
    private CreatorReportResponse toResponse(CreatorReportRecord record) {
        return new CreatorReportResponse(
                record.getId(),
                record.getReportId(),
                record.getTaskId(),
                record.getContentSummary(),
                record.getCoreSellingPoints(),
                record.getTitleDescriptionReview(),
                record.getAudienceFeedbackSummary(),
                record.getCompetitorComparison(),
                record.getControversyAndMisunderstanding(),
                record.getNextActionSuggestions(),
                record.getCreatorPreferenceInsight(),
                record.getOverallConclusion(),
                record.getRawOutput(),
                record.getParseStatus(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
