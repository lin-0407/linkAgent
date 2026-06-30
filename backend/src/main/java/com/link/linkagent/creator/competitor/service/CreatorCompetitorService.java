package com.link.linkagent.creator.competitor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.competitor.mapper.CreatorCompetitorMapper;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorAnalyzeRequest;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorReportRecord;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorReportResponse;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorSampleRecord;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorSampleResponse;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorSaveRequest;
import com.link.linkagent.creator.feedback.mapper.CreatorFeedbackMapper;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackReportRecord;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorMaterialType;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.model.CreatorTaskStatus;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.LlmJsonUtil;
import com.link.linkagent.util.TextUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 竞品分析服务 —— 通过 B 站同类优秀视频建立对照基准，输出差异化策略和改进建议。
 * <p>
 * 核心职责：
 * <ol>
 *   <li><b>竞品视频登记</b>：接收创作者手动提交的竞品 BV 号、视频名称和内容样本，
 *       建立对照基准。为什么需要手动提交？B 站 API 对全量视频数据的访问受限，
 *       且创作者最了解哪些视频是"真正的竞品"（主观判断远胜算法推荐）。</li>
 *   <li><b>AI 竞品分析</b>：将创作者的创作素材（素材库、选题建议、数据反馈）+ 竞品样本
 *       拼接成 prompt 发给 LLM，产出结构化分析报告，包含竞品优势、自身优劣势、
 *       差距分析、改进建议和差异化策略。</li>
 *   <li><b>分析报告存取</b>：分析结果持久化（含原始输出 + 解析后的结构化字段），
 *       支持后续查看和对比（多轮竞品分析的版本演进）。</li>
 * </ol>
 * <p>
 * 架构定位：本服务是竞品分析场景的编排中枢——它既管理竞品数据（save/get），
 * 也编排 AI 分析流程（analyze）。与 CreatorEvaluationService（纯数据存取）
 * 不同，本服务包含了 LLM 调用的编排逻辑，因为竞品分析的 prompt 组装高度依赖
 * 多源数据（任务、素材、选题建议、反馈报告），这些数据的获取属于本服务的编排职责，
 * 抽取为独立层会导致过多的上下文传递。
 * <p>
 * 设计权衡：
 * <ul>
 *   <li><b>内容截断策略</b>：素材最大 4000 字符、竞品样本最大 12000 字符。
 *       因为竞品样本通常包含完整视频文案（字幕），可能几万字，不截断会超 LLM 上下文窗口。
 *       截断阈值不是硬编码的上下文窗口限制，而是为 AI 保留足够空间给输出结论。</li>
 *   <li><b>解析兜底</b>：LLM 输出解析失败不抛异常，标记 RAW_ONLY 保留原始文本。
 *       后续改进解析器后可重新解析历史数据，不损失已花费的 AI 调用成本。</li>
 *   <li><b>LlmUsageContext</b>：通过 {@link LlmUsageContext#open} 包裹 LLM 调用，
 *       将 Token 用量自动上报到 Langfuse，实现每次竞品分析的成本追踪。</li>
 * </ul>
 */
@Service
public class CreatorCompetitorService {

    /**
     * 素材内容的最大截断长度。
     * 4000 字符对应约 1500~2000 Token（中文），为 LLM 的输入侧保留足够空间，
     * 因为竞品分析的输出字段较多（7 个结构化字段），需要给输出侧留出上下文预算。
     */
    private static final int MATERIAL_MAX_LENGTH = 4000;

    /**
     * 竞品样本（如字幕/文稿）的最大截断长度。
     * 12000 字符对应约 5000~7000 Token。竞品视频的全量字幕可能数万字，
     * 必须截断才能放入 LLM 上下文。设置比素材更长的原因是竞品样本是分析的核心材料，
     * 样本越完整，AI 的对比分析越有深度。
     */
    private static final int SAMPLE_MAX_LENGTH = 12000;

    /** 创作任务持久化（查询任务存在性 + 关联竞品视频到任务） */
    private final CreatorTaskMapper creatorTaskMapper;
    /** 选题建议持久化（拼入竞品分析 prompt 提供创作方向上下文） */
    private final CreatorSuggestionMapper creatorSuggestionMapper;
    /** 数据反馈持久化（拼入竞品分析 prompt 提供观众真实反应上下文） */
    private final CreatorFeedbackMapper creatorFeedbackMapper;
    /** 竞品数据的持久化映射器 */
    private final CreatorCompetitorMapper creatorCompetitorMapper;
    /** LLM 调用服务（发起竞品分析的 AI 请求） */
    private final LLMService llmService;
    /** JSON 解析器（解析 LLM 结构化输出为 Java 对象） */
    private final ObjectMapper objectMapper;
    /** 提示词模板服务（加载和渲染竞品分析的系统/用户提示词模板） */
    private final PromptService promptService;

    public CreatorCompetitorService(CreatorTaskMapper creatorTaskMapper,
                                    CreatorSuggestionMapper creatorSuggestionMapper,
                                    CreatorFeedbackMapper creatorFeedbackMapper,
                                    CreatorCompetitorMapper creatorCompetitorMapper,
                                    LLMService llmService,
                                    ObjectMapper objectMapper,
                                    PromptService promptService) {
        this.creatorTaskMapper = creatorTaskMapper;
        this.creatorSuggestionMapper = creatorSuggestionMapper;
        this.creatorFeedbackMapper = creatorFeedbackMapper;
        this.creatorCompetitorMapper = creatorCompetitorMapper;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.promptService = promptService;
    }

    /**
     * 登记竞品视频。
     * <p>
     * 创作者手动提交一个同类优秀视频的 BV 号、名称和内容样本（如字幕/文稿），
     * 作为后续 AI 分析的对标基准。每个任务只保留一条竞品记录（通过 upsert 语义），
     * 因为竞品分析是一个对比决策，多个竞品应该合并在一次分析中对比，
     * 而非多次独立分析（这样 AI 才能在一个上下文中综合对比）。
     * <p>
     * 为什么用 BV 号而非 URL 作为标识？BV 号是 B 站视频的唯一短标识，
     * 比 URL 更短更稳定（不受 cdn/bvid 转换影响），且方便创作者直接复制分享链接中的 BV 号部分。
     *
     * @param taskId 关联的创作任务标识
     * @param request 竞品视频登记请求，至少包含 BV 号和样本内容
     * @return 登记后的竞品视频响应
     */
    @Transactional
    public CreatorCompetitorSampleResponse saveCompetitorVideo(String taskId, CreatorCompetitorSaveRequest request) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        CreatorCompetitorSampleRecord record = new CreatorCompetitorSampleRecord();
        record.setCompetitorBvId(request.competitorBvId().trim());
        record.setCompetitorVideoName(request.competitorVideoName().trim());
        record.setTaskId(taskRecord.getTaskId());
        record.setCategory(TextUtil.trimToNull(request.category()));
        record.setCompetitorSamples(request.competitorSamples().trim());
        record.setCompareDimension(TextUtil.trimToNull(request.compareDimension()));
        record.setExtraContext(TextUtil.trimToNull(request.extraContext()));
        creatorCompetitorMapper.upsertCompetitorVideo(record);
        return getCompetitorVideo(taskRecord.getTaskId());
    }

    /**
     * 查询任务已登记的竞品视频。
     * <p>
     * 先校验任务存在性，再查竞品记录。不存在时 404 明确告知"还未登记竞品视频"。
     *
     * @param taskId 创作任务标识
     * @return 竞品视频响应
     */
    public CreatorCompetitorSampleResponse getCompetitorVideo(String taskId) {
        getTaskRecord(taskId);
        CreatorCompetitorSampleRecord record = creatorCompetitorMapper.findCompetitorVideoByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "竞品视频不存在"));
        return toSampleResponse(record);
    }

    /**
     * 执行竞品分析（AI 调用 + 结果持久化）。
     * <p>
     * 这是竞品分析的核心方法，执行以下步骤：
     * <ol>
     *   <li><b>数据组装</b>：从四个数据源拼装完整的分析上下文——
     *       <ul>
     *         <li>创作任务信息（taskRecord）：任务名称、创作目标</li>
     *         <li>创作素材（materials）：标题草稿、简介草稿、文稿、字幕等</li>
     *         <li>选题建议（suggestionRecord）：前序阶段 AI 产出的内容定位、卖点、风险点等</li>
     *         <li>数据反馈（feedbackReportRecord）：发布后的观众反馈、争议点、误解来源等</li>
     *       </ul>
     *       为什么需要这么多上下文？竞品分析不是孤立的"我的视频 vs 别人的视频"，
     *       而是"在已知我的创作策略、素材准备、观众反馈的前提下，对比竞品的优劣势"。
     *       缺少任何一环，AI 都会在信息缺失的位置做猜测，降低分析质量。</li>
     *   <li><b>LLM 调用</b>：通过 {@link LlmUsageContext} 包裹以追踪 Token 用量；
     *       system prompt 从模板加载（competitor.system），user prompt 通过 PromptService#render
     *       按模板渲染（competitor.user），保证 prompt 内容可配置、可版本管理。</li>
     *   <li><b>结果解析</b>：尝试从 LLM 的 JSON 输出中提取结构化字段
     *       （竞品优势、自身优劣势、差距分析、改进建议、差异化策略），
     *       解析失败不抛异常——保留原始输出（RAW_ONLY），后续可改进解析器重新解析。</li>
     *   <li><b>状态更新</b>：持久化分析报告 + 将任务状态推进至 COMPETITOR_ANALYZED。</li>
     * </ol>
     * <p>
     * 设计权衡：反馈报告和选题建议用 Optional.orElse(null)，而非强校验。
     * 因为竞品分析可能在创作流程的任何阶段执行——发布前没有反馈报告，
     * 早期阶段没有选题建议。让这些上下文作为可选的"加分项"而非"必需项"，
     * 能保证竞品分析在任何阶段都可用（只是信息越全，分析越有深度）。
     *
     * @param taskId 关联的创作任务标识
     * @param request 分析请求，含可选的 customGuidance、analysisFocus、extraRequirement
     * @return 完整的竞品分析报告响应（含解析后的结构化字段）
     */
    @Transactional
    public CreatorCompetitorReportResponse analyze(String taskId, CreatorCompetitorAnalyzeRequest request) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        CreatorCompetitorSampleRecord sampleRecord = creatorCompetitorMapper.findCompetitorVideoByTaskId(taskRecord.getTaskId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先提交同类型竞品视频"));
        List<CreatorMaterialRecord> materials = creatorTaskMapper.listMaterialsByTaskId(taskRecord.getTaskId());
        CreatorSuggestionRecord suggestionRecord = creatorSuggestionMapper.findByTaskId(taskRecord.getTaskId()).orElse(null);
        CreatorFeedbackReportRecord feedbackReportRecord = creatorFeedbackMapper.findReportByTaskId(taskRecord.getTaskId()).orElse(null);

        String rawOutput;
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.open(taskRecord.getTaskId(), "同类型视频竞品分析")) {
            rawOutput = llmService.chat(
                    buildSystemPrompt(),
                    buildUserPrompt(taskRecord, materials, sampleRecord, suggestionRecord, feedbackReportRecord, request)
            );
        }
        CreatorCompetitorReportRecord reportRecord = buildReportRecord(taskRecord.getTaskId(), rawOutput);
        creatorCompetitorMapper.upsertReport(reportRecord);
        creatorTaskMapper.updateTaskStatus(taskRecord.getTaskId(), CreatorTaskStatus.COMPETITOR_ANALYZED.name());
        return getReport(taskRecord.getTaskId());
    }

    /**
     * 查询任务的竞品分析报告。
     * <p>
     * 先校验任务存在性，再查报告。不存在时明确 404（而非返回空），
     * 让前端能区分"任务存在但还没有分析报告"和"任务本身不存在"。
     *
     * @param taskId 创作任务标识
     * @return 完整的竞品分析报告响应
     */
    public CreatorCompetitorReportResponse getReport(String taskId) {
        getTaskRecord(taskId);
        CreatorCompetitorReportRecord record = creatorCompetitorMapper.findReportByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "竞品分析报告不存在"));
        return toReportResponse(record);
    }

    /**
     * 根据 taskId 获取创作任务记录，不存在时抛出 404。
     * <p>
     * 抽取为私有方法避免 save/get/analyze 等入口各自重复任务存在性校验。
     */
    private CreatorTaskRecord getTaskRecord(String taskId) {
        return creatorTaskMapper.findTaskByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在"));
    }

    /**
     * 构建竞品分析报告记录（含原始输出和解析后的结构化字段）。
     * <p>
     * reportId 使用 UUID：报告是独立的业务实体，UUID 避免自增 ID 猜测，
     * 且支持未来扩展到分享功能（如生成分享链接包含报告 ID）。
     *
     * @param taskId 关联的创作任务标识
     * @param rawOutput LLM 的原始输出文本
     * @return 填充了基础字段和解析字段的报告记录
     */
    private CreatorCompetitorReportRecord buildReportRecord(String taskId, String rawOutput) {
        CreatorCompetitorReportRecord record = new CreatorCompetitorReportRecord();
        record.setReportId(UUID.randomUUID().toString());
        record.setTaskId(taskId);
        // 始终保留原始输出：解析失败时可回溯原始数据
        record.setRawOutput(rawOutput);
        fillParsedFields(record, rawOutput);
        return record;
    }

    /**
     * 从 LLM 的原始输出中提取结构化字段。
     * <p>
     * 解析流程：
     * <ol>
     *   <li>{@link LlmJsonUtil#extractJsonObject} —— 从可能包含 markdown 包裹的文本中
     *       提取纯净的 JSON 对象（去除 ```json 代码块、前后说明文字等噪音）。</li>
     *   <li>{@link ObjectMapper#readTree} —— 将 JSON 字符串解析为 Jackson 的树模型（JsonNode），
     *       树模型比强类型 POJO 更宽容：字段缺失返回 null 而非抛异常。</li>
     *   <li>{@link LlmJsonUtil#text} / {@link LlmJsonUtil#json} —— 从 JsonNode 中安全提取
     *       各字段值。text() 用于简单字符串字段，json() 用于嵌套对象/数组字段
     *       （通过 ObjectMapper.writeValueAsString 序列化回 JSON 字符串存储）。</li>
     * </ol>
     * <p>
     * 为什么用 JsonNode 树模型而非强类型 POJO 反序列化？
     * 竞品分析的输出字段有 7 个，其中 4 个是嵌套结构（advantages/disadvantages/gap/suggestions），
     * 每个的结构松散且可能因 prompt 版本变化而变化。强类型 POJO 容错性差——
     * 任一字段结构不匹配就会导致整个反序列化失败，丢失所有可解析字段。
     * JsonNode 的宽松模式：只解析能解析的部分，缺失的字段自动为 null。
     * <p>
     * 异常处理策略：解析失败不抛异常，标记 RAW_ONLY 保留原始数据。
     * 这样即使本次 prompt 产出的 JSON 格式有 bug，已消耗的 Token 不会白费——
     * 修复 prompt 后可通过后台任务重新解析历史报告。
     *
     * @param record 待填充的报告记录（原地修改）
     * @param rawOutput LLM 的原始输出文本
     */
    private void fillParsedFields(CreatorCompetitorReportRecord record, String rawOutput) {
        try {
            // 第一步：从可能被 markdown 包裹的文本中提取纯净 JSON
            JsonNode rootNode = objectMapper.readTree(LlmJsonUtil.extractJsonObject(rawOutput));
            // 第二步：逐字段安全提取。text() 用于简单字符串，json() 用于嵌套结构（序列化回 JSON 字符串存储）
            record.setCompetitorSummary(LlmJsonUtil.text(rootNode, "competitorSummary"));
            record.setCompetitorAdvantages(LlmJsonUtil.json(objectMapper, rootNode, "competitorAdvantages"));
            record.setOwnAdvantages(LlmJsonUtil.json(objectMapper, rootNode, "ownAdvantages"));
            record.setOwnDisadvantages(LlmJsonUtil.json(objectMapper, rootNode, "ownDisadvantages"));
            record.setGapAnalysis(LlmJsonUtil.json(objectMapper, rootNode, "gapAnalysis"));
            record.setImprovementSuggestions(LlmJsonUtil.json(objectMapper, rootNode, "improvementSuggestions"));
            record.setDifferentiationStrategy(LlmJsonUtil.text(rootNode, "differentiationStrategy"));
            record.setParseStatus("PARSED");
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            // 解析失败不抛异常：保留原始输出，后续可改进解析器重新处理
            record.setParseStatus("RAW_ONLY");
        }
    }

    /**
     * 构建竞品分析的系统提示词。
     * <p>
     * 直接通过 {@link PromptService#get} 获取静态模板（不带变量替换），
     * 因为系统提示词通常固定定义 AI 的"角色"和"输出格式要求"，
     * 不需要运行时变量注入。
     *
     * @return 竞品分析的系统提示词文本
     */
    private String buildSystemPrompt() {
        return promptService.get("competitor.system");
    }

    /**
     * 构建竞品分析的用户提示词（运行时数据注入）。
     * <p>
     * 模板渲染通过 {@link PromptService#render} 实现，模板文件为 competitor.user。
     * 注入的数据按语义分组：
     * <ul>
     *   <li><b>任务基础信息</b>：taskName、taskId —— 帮助 AI 关联上下文</li>
     *   <li><b>分析定制参数</b>：customGuidance、analysisFocus、extraRequirement ——
     *       创作者可以指定分析重点关注的方向（如"重点分析标题的吸引力"），
     *       未提供时默认"未提供"，让 AI 知道没有特殊约束可以自由分析</li>
     *   <li><b>竞品信息</b>：BV 号、视频名、分类、对比维度、额外上下文</li>
     *   <li><b>创作素材</b>：标题草稿、文稿、字幕等（通过 buildMaterialPrompt 格式化）</li>
     *   <li><b>选题建议</b>：前序阶段 AI 产出的内容策略（通过 buildSuggestionPrompt 格式化）</li>
     *   <li><b>数据反馈</b>：观众真实反馈（通过 buildFeedbackReportPrompt 格式化）</li>
     *   <li><b>竞品样本</b>：竞品视频的文字内容（已做长度截断）</li>
     * </ul>
     * <p>
     * 为什么所有可选字段都默认"未提供"而非省略？LLM 需要明确知道"这个信息不存在"
     * 才能避免在缺失位置做猜测。省略某个字段会让 AI 以为是 prompt 模板 bug。
     */
    private String buildUserPrompt(CreatorTaskRecord taskRecord,
                                   List<CreatorMaterialRecord> materials,
                                   CreatorCompetitorSampleRecord sampleRecord,
                                   CreatorSuggestionRecord suggestionRecord,
                                   CreatorFeedbackReportRecord feedbackReportRecord,
                                   CreatorCompetitorAnalyzeRequest request) {
        return promptService.render("competitor.user", Map.ofEntries(
                Map.entry("taskName", taskRecord.getTaskName()),
                Map.entry("taskId", taskRecord.getTaskId()),
                Map.entry("customGuidance", TextUtil.trimToDefault(request.customGuidance(), "未提供")),
                Map.entry("analysisFocus", TextUtil.trimToDefault(request.analysisFocus(), "未提供")),
                Map.entry("extraRequirement", TextUtil.trimToDefault(request.extraRequirement(), "未提供")),
                Map.entry("competitorBvId", sampleRecord.getCompetitorBvId()),
                Map.entry("competitorVideoName", sampleRecord.getCompetitorVideoName()),
                Map.entry("category", TextUtil.trimToDefault(sampleRecord.getCategory(), "未提供")),
                Map.entry("compareDimension", TextUtil.trimToDefault(sampleRecord.getCompareDimension(), "未提供")),
                Map.entry("extraContext", TextUtil.trimToDefault(sampleRecord.getExtraContext(), "未提供")),
                Map.entry("materials", buildMaterialPrompt(materials)),
                Map.entry("suggestionResult", buildSuggestionPrompt(suggestionRecord)),
                Map.entry("feedbackResult", buildFeedbackReportPrompt(feedbackReportRecord)),
                Map.entry("competitorSamples", limitSection(sampleRecord.getCompetitorSamples(), SAMPLE_MAX_LENGTH))
        ));
    }

    /**
     * 将创作素材列表格式化为给 LLM 的文本块。
     * <p>
     * 每种素材类型用中文名称标注（如"文稿"、"字幕"），让 LLM 理解不同素材的语义角色。
     * 素材内容做长度截断（{@link #MATERIAL_MAX_LENGTH}），因为多篇文稿 + 字幕
     * 可能达到数十万字，不截断会撑满上下文窗口。
     * <p>
     * 空素材列表返回"未提供"：与 buildUserPrompt 的默认值设计一致，
     * 让 AI 明确知道"该任务没有素材"而非"忘记提供了"。
     *
     * @param materials 素材列表（可能为空）
     * @return 格式化的素材文本块
     */
    private String buildMaterialPrompt(List<CreatorMaterialRecord> materials) {
        if (materials.isEmpty()) {
            return "未提供";
        }
        StringBuilder builder = new StringBuilder();
        for (CreatorMaterialRecord material : materials) {
            // 用中文名称标注素材类型，帮助 LLM 区分标题草稿/文稿/字幕等不同语义
            builder.append("\n【")
                    .append(toChineseMaterialName(material.getMaterialType()))
                    .append("】\n")
                    .append(limitSection(material.getContent(), MATERIAL_MAX_LENGTH))
                    .append("\n");
        }
        return builder.toString();
    }

    /**
     * 将选题建议记录格式化为给 LLM 的文本块。
     * <p>
     * 选题建议来自创作者工作流的前序阶段（选题建议 AI 产出），包含内容定位、
     * 核心卖点、风险点等信息。这些信息对竞品分析至关重要——它让 AI 理解
     * "创作者原本打算做什么"，从而在对比中发现策略偏差。
     * <p>
     * 每个字段都经过 normalizeSection 处理：trim 默认值 + 长度截断，
     * 防止单个字段过长（如 AI 产出的超长修改计划）撑满上下文。
     *
     * @param record 选题建议记录，可能为 null
     * @return 格式化的选题建议文本块，或"未提供"
     */
    private String buildSuggestionPrompt(CreatorSuggestionRecord record) {
        if (record == null) {
            return "未提供";
        }
        return """
                内容摘要：%s
                创作者困境：%s
                观众钩子：%s
                内容定位：%s
                核心卖点：%s
                风险点：%s
                标题建议：%s
                可执行修改计划：%s
                标签建议：%s
                """.formatted(
                normalizeSection(record.getContentSummary()),
                normalizeSection(record.getCreatorDilemma()),
                normalizeSection(record.getAudienceHook()),
                normalizeSection(record.getContentPositioning()),
                normalizeSection(record.getSellingPoints()),
                normalizeSection(record.getRiskPoints()),
                normalizeSection(record.getTitleSuggestions()),
                normalizeSection(record.getActionableRevisionPlan()),
                normalizeSection(record.getTagSuggestions())
        );
    }

    /**
     * 将数据反馈报告格式化为给 LLM 的文本块。
     * <p>
     * 数据反馈来自发布后的观众真实反应——观众说了什么、争议点在哪、
     * 哪些内容被误解了。这些信息让竞品分析不再是"假设性的"而是"基于真实反馈的"，
     * 帮助发现"观众喜欢竞品的什么，而我的视频缺少什么"。
     * <p>
     * 同样经过 normalizeSection 处理每个字段，防止单个字段溢出。
     *
     * @param record 反馈报告记录，可能为 null（发布前竞品分析时不存在）
     * @return 格式化的反馈报告文本块，或"未提供"
     */
    private String buildFeedbackReportPrompt(CreatorFeedbackReportRecord record) {
        if (record == null) {
            return "未提供";
        }
        return """
                反馈摘要：%s
                创作者反馈困境：%s
                观众核心关注：%s
                高频观点：%s
                情绪倾向：%s
                争议点：%s
                误解来源分析：%s
                下一期内容建议：%s
                反馈行动计划：%s
                """.formatted(
                normalizeSection(record.getFeedbackSummary()),
                normalizeSection(record.getCreatorFeedbackDilemma()),
                normalizeSection(record.getAudienceCoreConcern()),
                normalizeSection(record.getHotTopics()),
                normalizeSection(record.getSentimentSummary()),
                normalizeSection(record.getControversyPoints()),
                normalizeSection(record.getMisunderstandingSourceAnalysis()),
                normalizeSection(record.getNextContentSuggestions()),
                normalizeSection(record.getFeedbackActionPlan())
        );
    }

    /**
     * 规范化文本段落：trim 默认值 + 长度截断。
     * <p>
     * 统一处理两层防御：
     * <ol>
     *   <li>null/空值 → "未提供"</li>
     *   <li>超长文本 → 截断到 {@link #SAMPLE_MAX_LENGTH}，末尾追加截断提示</li>
     * </ol>
     * 为什么用 SAMPLE_MAX_LENGTH（12000）而非 MATERIAL_MAX_LENGTH（4000）？
     * 选题建议和反馈报告的字段都是 AI 产出的结构化内容，比素材文本更有"信息密度"，
     * 给更多空间能让 AI 看到更完整的上下文。
     */
    private String normalizeSection(String value) {
        return limitSection(TextUtil.trimToDefault(value, "未提供"), SAMPLE_MAX_LENGTH);
    }

    /**
     * 文本截断：超过 maxLength 时裁剪并追加截断提示后缀。
     * <p>
     * 截断提示用 "\n" 开头确保另起一行，不会和正文混在一起。
     * 中文提示"内容过长，已截断用于本次竞品分析"让 LLM 知道文本不完整，
     * 避免对截断处的内容产生误解（如截断在句子中间被理解为不完整观点）。
     *
     * @param value 原始文本
     * @param maxLength 最大保留长度
     * @return 截断后的文本（可能带截断提示后缀）
     */
    private String limitSection(String value, int maxLength) {
        String normalized = TextUtil.trimToDefault(value, "未提供");
        return TextUtil.abbreviateWithSuffix(
                normalized,
                maxLength,
                "\n[内容过长，已截断用于本次竞品分析]"
        );
    }

    /**
     * 将素材类型枚举名转为中文名称，方便 LLM 理解不同素材的语义角色。
     * <p>
     * 素材类型枚举值为英文（如 TITLE_DRAFT），直接喂给 LLM 会降低 prompt 的语义质量。
     * 中文命名（如"标题草稿"）让 LLM 更准确地关联素材与竞品分析维度——
     * 例如"标题草稿"天然对应"标题吸引力"的对比分析。
     * <p>
     * 未知类型降级为原始枚举名：作为防御性兜底，即使新增素材类型也至少能传递给 LLM。
     *
     * @param materialType 素材类型的枚举名（如 TITLE_DRAFT）
     * @return 中文名称，未知枚举返回原始值
     */
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
        // 未知类型降级为原始枚举名，保证新增素材类型不阻塞业务流程
        return materialType;
    }

    /**
     * 将数据库竞品视频记录转换为前端响应对象。
     */
    private CreatorCompetitorSampleResponse toSampleResponse(CreatorCompetitorSampleRecord record) {
        return new CreatorCompetitorSampleResponse(
                record.getId(),
                record.getCompetitorBvId(),
                record.getCompetitorVideoName(),
                record.getTaskId(),
                record.getCategory(),
                record.getCompetitorSamples(),
                record.getCompareDimension(),
                record.getExtraContext(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }

    /**
     * 将数据库竞品分析报告记录转换为前端响应对象。
     * <p>
     * 注意：rawOutput 和 parseStatus 也一并返回，前端可据此决定——
     * parseStatus 为 RAW_ONLY 时展示原始输出文本，
     * parseStatus 为 PARSED 时展示结构化字段。
     */
    private CreatorCompetitorReportResponse toReportResponse(CreatorCompetitorReportRecord record) {
        return new CreatorCompetitorReportResponse(
                record.getId(),
                record.getReportId(),
                record.getTaskId(),
                record.getCompetitorSummary(),
                record.getCompetitorAdvantages(),
                record.getOwnAdvantages(),
                record.getOwnDisadvantages(),
                record.getGapAnalysis(),
                record.getImprovementSuggestions(),
                record.getDifferentiationStrategy(),
                record.getRawOutput(),
                record.getParseStatus(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
