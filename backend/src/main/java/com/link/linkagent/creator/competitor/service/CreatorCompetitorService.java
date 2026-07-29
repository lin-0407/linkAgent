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
import com.link.linkagent.creator.report.mapper.CreatorReviewInvalidationMapper;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorMaterialType;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.model.CreatorTaskStatus;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.ReferenceVideoItemRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoRecord;
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
 *   <li><b>结构校验</b>：只有字段完整的结构化输出才会落库，避免空报告继续解锁总体复盘。</li>
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
    /** 复盘链路失效映射器，竞品重做后清理依赖旧竞品的总体复盘和偏好 */
    private final CreatorReviewInvalidationMapper reviewInvalidationMapper;
    /** 参考案例知识库映射器（P1-1：让竞品分析能读取 BV 导入管道采集的参考案例数据） */
    private final KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper;
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
                                    CreatorReviewInvalidationMapper reviewInvalidationMapper,
                                    KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper,
                                    LLMService llmService,
                                    ObjectMapper objectMapper,
                                    PromptService promptService) {
        this.creatorTaskMapper = creatorTaskMapper;
        this.creatorSuggestionMapper = creatorSuggestionMapper;
        this.creatorFeedbackMapper = creatorFeedbackMapper;
        this.creatorCompetitorMapper = creatorCompetitorMapper;
        this.reviewInvalidationMapper = reviewInvalidationMapper;
        this.knowledgeReferenceVideoMapper = knowledgeReferenceVideoMapper;
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
        // 样本一旦保存，所有依赖旧样本的结论都必须在同一事务内失效，避免页面继续展示旧分析。
        reviewInvalidationMapper.invalidateCompetitorReport(taskRecord.getTaskId());
        reviewInvalidationMapper.invalidateCreatorReport(taskRecord.getTaskId());
        reviewInvalidationMapper.invalidateGeneratedPreference(taskRecord.getTaskId());
        if (CreatorTaskStatus.COMPETITOR_ANALYZED.name().equals(taskRecord.getStatus())
                || CreatorTaskStatus.ANALYZED.name().equals(taskRecord.getStatus())) {
            creatorTaskMapper.updateTaskStatus(taskRecord.getTaskId(), CreatorTaskStatus.FEEDBACK_ANALYZED.name());
        }
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
     *   <li><b>结果校验</b>：使用强类型结构化输出校验字段和必填内容，失败时不落库也不推进状态。</li>
     *   <li><b>状态更新</b>：持久化分析报告 + 将任务状态推进至 COMPETITOR_ANALYZED。</li>
     * </ol>
     * @param taskId 关联的创作任务标识
     * @param request 分析请求，含可选的 customGuidance、analysisFocus、extraRequirement
     * @return 完整的竞品分析报告响应（含解析后的结构化字段）
     */
    @Transactional
    public CreatorCompetitorReportResponse analyze(String taskId, CreatorCompetitorAnalyzeRequest request) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        CreatorFeedbackReportRecord feedbackReportRecord = requireParsedFeedbackReport(taskRecord);
        // 手动竞品分析要求先登记竞品视频（BV + 文稿），与参考案例路径不同
        CreatorCompetitorSampleRecord sampleRecord = creatorCompetitorMapper.findCompetitorVideoByTaskId(taskRecord.getTaskId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先提交同类型竞品视频"));
        List<CreatorMaterialRecord> materials = creatorTaskMapper.listMaterialsByTaskId(taskRecord.getTaskId());
        CreatorSuggestionRecord suggestionRecord = creatorSuggestionMapper.findByTaskId(taskRecord.getTaskId()).orElse(null);
        return executeAnalyze(
                taskRecord,
                materials,
                suggestionRecord,
                feedbackReportRecord,
                sampleRecord,
                request
        );
    }

    /**
     * 基于参考案例触发竞品分析（P1-1：参考案例体系融入竞品分析）。
     * <p>
     * 与 {@link #analyze} 的核心差异：
     * <ul>
     *   <li><b>数据来源不同</b>：本方法从参考案例知识库（creator_reference_video +
     *       creator_reference_video_item）读取竞品数据，而非要求用户手动填写竞品文稿。</li>
     *   <li><b>零录入体验</b>：参考案例已通过 BV 导入管道自动采集了标题、描述、
     *       互动数据、亮点摘要和清洗后的优质评论/弹幕，用户只需在知识库页面点击
     *       「对比我的创作」即可触发分析。</li>
     *   <li><b>复用分析引擎</b>：将参考案例数据组装为虚拟的 {@link CreatorCompetitorSampleRecord}，
     *       然后调用 {@link #executeAnalyze} 复用已有的 prompt 拼装、LLM 调用、
     *       结果解析和持久化逻辑。</li>
     * </ul>
     * <p>
     * 为什么参考案例的评论弹幕能充当"竞品样本"？
     * 竞品分析的本质是回答"观众为什么喜欢这个视频"——评论和弹幕正是观众真实反应的直接证据。
     * 将清洗后的优质评论弹幕拼入 prompt，AI 可以从观众视角分析竞品的吸引力和自身可改进点。
     *
     * @param taskId           关联的创作任务标识
     * @param referenceVideoId 参考案例的视频唯一标识（UUID，非 BV 号）
     * @param request          分析请求，含可选的 customGuidance、analysisFocus、extraRequirement
     * @return 完整的竞品分析报告响应
     */
    @Transactional
    public CreatorCompetitorReportResponse analyzeByReferenceVideo(
            String taskId, String referenceVideoId, CreatorCompetitorAnalyzeRequest request) {
        // 1. 校验任务存在
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        CreatorFeedbackReportRecord feedbackReportRecord = requireParsedFeedbackReport(taskRecord);
        // 2. 从知识库读取参考案例视频数据
        ReferenceVideoRecord refVideo = knowledgeReferenceVideoMapper.findByVideoId(referenceVideoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "参考案例不存在"));
        // 3. 读取优质评论/弹幕作为"竞品反馈/样本"（取 50 条高价值证据，避免上下文溢出）
        List<ReferenceVideoItemRecord> refItems = knowledgeReferenceVideoMapper.listEvidenceItemsByVideoId(referenceVideoId, 50);
        // 4. 加载任务的其他上下文数据（素材、选题建议、数据反馈）
        List<CreatorMaterialRecord> materials = creatorTaskMapper.listMaterialsByTaskId(taskRecord.getTaskId());
        CreatorSuggestionRecord suggestionRecord = creatorSuggestionMapper.findByTaskId(taskRecord.getTaskId()).orElse(null);
        // 5. 将参考案例数据组装为虚拟竞品样本记录，复用已有 prompt 拼装逻辑
        CreatorCompetitorSampleRecord virtualSample = buildVirtualSampleFromReference(refVideo, refItems);
        // 6. 执行分析（复用核心引擎）
        return executeAnalyze(
                taskRecord,
                materials,
                suggestionRecord,
                feedbackReportRecord,
                virtualSample,
                request
        );
    }

    /**
     * 核心分析执行引擎：组装 prompt → LLM 调用 → 解析 → 持久化 → 更新任务状态。
     * <p>
     * 抽取为独立方法供 {@link #analyze} 和 {@link #analyzeByReferenceVideo} 共用，
     * 避免 LLM 调用、结果解析、报告持久化三块逻辑在两处重复维护。
     * <p>
     * 设计约束：本方法不关心 {@code sampleRecord} 的来源——它可以是用户手动登记的
     * 竞品视频记录，也可以是从参考案例知识库组装出的虚拟记录。
     * 只要字段（competitorBvId、competitorVideoName、competitorSamples 等）被正确填充，
     * 下游的 {@link #buildUserPrompt} 就能无差别处理。
     *
     * @param taskRecord           已校验存在的创作任务
     * @param materials            任务的创作素材列表
     * @param suggestionRecord     选题建议（可为 null）
     * @param feedbackReportRecord 已完成且结构完整的数据反馈报告
     * @param sampleRecord         竞品样本记录（实体或虚拟）
     * @param request              分析请求参数
     * @return 完整的竞品分析报告响应
     */
    private CreatorCompetitorReportResponse executeAnalyze(
            CreatorTaskRecord taskRecord,
            List<CreatorMaterialRecord> materials,
            CreatorSuggestionRecord suggestionRecord,
            CreatorFeedbackReportRecord feedbackReportRecord,
            CreatorCompetitorSampleRecord sampleRecord,
            CreatorCompetitorAnalyzeRequest request) {
        String rawOutput;
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.open(taskRecord.getTaskId(), "同类型视频竞品分析")) {
            rawOutput = llmService.chat(
                    buildSystemPrompt(),
                    buildUserPrompt(taskRecord, materials, sampleRecord, suggestionRecord, feedbackReportRecord, request)
            );
        }
        CreatorCompetitorReportRecord reportRecord = buildReportRecord(taskRecord.getTaskId(), rawOutput);
        // 新竞品结果一旦确认可用，旧总体复盘和它生成的偏好就失去依据，必须在同一事务内先失效。
        reviewInvalidationMapper.invalidateCreatorReport(taskRecord.getTaskId());
        reviewInvalidationMapper.invalidateGeneratedPreference(taskRecord.getTaskId());
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
     * 竞品分析必须建立在当前有效反馈报告上，不能仅凭任务曾经到过某个阶段继续执行。
     */
    private CreatorFeedbackReportRecord requireParsedFeedbackReport(CreatorTaskRecord taskRecord) {
        String status = taskRecord.getStatus();
        if (!CreatorTaskStatus.FEEDBACK_ANALYZED.name().equals(status)
                && !CreatorTaskStatus.COMPETITOR_ANALYZED.name().equals(status)
                && !CreatorTaskStatus.ANALYZED.name().equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先完成评论弹幕分析，再进行竞品分析");
        }
        CreatorFeedbackReportRecord record = creatorFeedbackMapper.findReportByTaskId(taskRecord.getTaskId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "当前反馈分析报告不存在，请重新完成评论弹幕分析"
                ));
        if (!"PARSED".equals(record.getParseStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "当前反馈分析报告不完整，请重新完成评论弹幕分析"
            );
        }
        return record;
    }

    /**
     * 构建竞品分析报告记录；格式或必填内容不完整时直接拒绝落库。
     * <p>
     * reportId 使用 UUID：报告是独立的业务实体，UUID 避免自增 ID 猜测，
     * 且支持未来扩展到分享功能（如生成分享链接包含报告 ID）。
     *
     * @param taskId 关联的创作任务标识
     * @param rawOutput 模型原始输出
     * @return 填充了基础字段和解析字段的报告记录
     */
    private CreatorCompetitorReportRecord buildReportRecord(String taskId, String rawOutput) {
        CreatorCompetitorReportRecord record = new CreatorCompetitorReportRecord();
        record.setReportId(UUID.randomUUID().toString());
        record.setTaskId(taskId);
        record.setRawOutput(rawOutput);
        fillParsedFields(record, rawOutput);
        return record;
    }

    private void fillParsedFields(CreatorCompetitorReportRecord record, String rawOutput) {
        try {
            JsonNode root = objectMapper.readTree(LlmJsonUtil.extractJsonObject(rawOutput));
            requireText(root, "competitorSummary");
            requireArray(root, "competitorAdvantages", "advantage", "evidence", "lesson");
            requireArray(root, "ownAdvantages", "advantage", "evidence");
            requireArray(root, "ownDisadvantages", "disadvantage", "evidence", "risk");
            requireArray(root, "gapAnalysis", "dimension", "gap", "priority");
            requireArray(root, "improvementSuggestions", "suggestion", "reason", "action");
            requireText(root, "differentiationStrategy");
            record.setCompetitorSummary(LlmJsonUtil.text(root, "competitorSummary"));
            record.setCompetitorAdvantages(LlmJsonUtil.json(objectMapper, root, "competitorAdvantages"));
            record.setOwnAdvantages(LlmJsonUtil.json(objectMapper, root, "ownAdvantages"));
            record.setOwnDisadvantages(LlmJsonUtil.json(objectMapper, root, "ownDisadvantages"));
            record.setGapAnalysis(LlmJsonUtil.json(objectMapper, root, "gapAnalysis"));
            record.setImprovementSuggestions(LlmJsonUtil.json(objectMapper, root, "improvementSuggestions"));
            record.setDifferentiationStrategy(LlmJsonUtil.text(root, "differentiationStrategy"));
            record.setParseStatus("PARSED");
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("竞品分析输出格式或内容不完整", exception);
        }
    }

    private void requireText(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(fieldName);
        }
    }

    private void requireArray(JsonNode root, String fieldName, String... itemFields) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw new IllegalArgumentException(fieldName);
        }
        for (JsonNode item : value) {
            for (String itemField : itemFields) {
                requireText(item, itemField);
            }
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
     * @param record 已完成且结构完整的反馈报告记录
     * @return 格式化的反馈报告文本块
     */
    private String buildFeedbackReportPrompt(CreatorFeedbackReportRecord record) {
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

    // ==================== P1-1：参考案例数据组装为竞品样本 ====================

    /**
     * 将参考案例数据组装为虚拟的竞品样本记录。
     * <p>
     * 参考案例已通过 BV 导入管道采集了标题、描述、互动数据、亮点摘要和清洗后的优质评论弹幕。
     * 这里把它们映射为竞品分析所需的 {@link CreatorCompetitorSampleRecord} 字段，
     * 让基于参考案例的竞品分析能复用与手动竞品完全相同的 prompt 拼装逻辑。
     * <p>
     * 为什么 comparatorBvId 用参考案例的 bvId？prompt 模板中 {competitorBvId} 变量
     * 用于向 LLM 标识"正在分析哪个视频"，填写真实 BV 号能让 AI 的输出更具体。
     *
     * @param refVideo 参考案例父表记录
     * @param refItems 参考案例的优质评论/弹幕列表
     * @return 填充了竞品信息的虚拟样本记录（不持久化到 creator_competitor_sample 表）
     */
    private CreatorCompetitorSampleRecord buildVirtualSampleFromReference(
            ReferenceVideoRecord refVideo, List<ReferenceVideoItemRecord> refItems) {
        CreatorCompetitorSampleRecord sample = new CreatorCompetitorSampleRecord();
        // BV 号作为竞品标识，未采集到时兜底为"未知"
        sample.setCompetitorBvId(TextUtil.trimToDefault(refVideo.getBvId(), "未知"));
        // 视频标题作为竞品名称，让 AI 能结合标题分析选题策略
        sample.setCompetitorVideoName(TextUtil.trimToDefault(refVideo.getTitle(), "未知视频"));
        // 参考案例的分区作为竞品分类，帮助 AI 理解赛道上下文
        sample.setCategory(TextUtil.trimToNull(refVideo.getCategory()));
        // 参考案例分析不预设对比维度，由 AI 根据视频内容和观众反馈自行判断
        sample.setCompareDimension(null);
        // 热度数据 + 质量分作为额外上下文，帮助 AI 判断竞品的市场表现
        sample.setExtraContext(buildReferenceExtraContext(refVideo));
        // 描述 + 亮点摘要 + 评论弹幕组装为"竞品样本"文本
        sample.setCompetitorSamples(buildReferenceSamples(refVideo, refItems));
        return sample;
    }

    /**
     * 从参考案例的父表字段和子表条目组装竞品样本文本。
     * <p>
     * 组装策略按信息来源分三块：
     * <ol>
     *   <li><b>视频简介</b>：参考案例的 description 字段，帮助 AI 理解视频的内容定位</li>
     *   <li><b>观众反馈亮点摘要</b>：导入管道在 5.1b 阶段通过 LLM 从评论弹幕中提取的
     *       结构化摘要（highlight_summary），比原始评论更精炼</li>
     *   <li><b>观众评论与弹幕精选</b>：清洗后的优质评论/弹幕原文，标注来源类型
     *       （评论/弹幕）和情感倾向（👍正面/👎负面），让 AI 能从观众视角
     *       理解竞品的吸引力和改进空间</li>
     * </ol>
     * <p>
     * 样本文本最终会通过 {@link #buildUserPrompt} 注入到模板变量 {competitorSamples}，
     * 并经过 {@link #SAMPLE_MAX_LENGTH} 截断（12000 字符），保证不溢出 LLM 上下文窗口。
     *
     * @param refVideo 参考案例父表记录
     * @param refItems 参考案例的优质评论/弹幕列表
     * @return 拼接后的竞品样本文本
     */
    private String buildReferenceSamples(ReferenceVideoRecord refVideo, List<ReferenceVideoItemRecord> refItems) {
        StringBuilder sb = new StringBuilder();
        // 第一块：视频简介 —— 帮助 AI 理解视频讲了什么
        if (TextUtil.hasText(refVideo.getDescription())) {
            sb.append("【视频简介】\n").append(refVideo.getDescription()).append("\n\n");
        }
        // 第二块：亮点摘要 —— LLM 已从评论弹幕中提取的结构化洞察
        if (TextUtil.hasText(refVideo.getHighlightSummary())) {
            sb.append("【观众反馈亮点摘要】\n").append(refVideo.getHighlightSummary()).append("\n\n");
        }
        // 第三块：原始评论/弹幕精选 —— 标注来源和情感，让 AI 能逐条理解观众反应
        if (!refItems.isEmpty()) {
            sb.append("【观众评论与弹幕精选】\n");
            for (ReferenceVideoItemRecord item : refItems) {
                // 标注来源类型（评论 vs 弹幕）和情感（👍 vs 👎），帮助 AI 区分不同渠道的观众反馈
                String typeLabel = "COMMENT".equals(item.getSourceType()) ? "评论" : "弹幕";
                String sentimentLabel = "POSITIVE".equals(item.getSentiment()) ? "👍" : "👎";
                sb.append("[").append(typeLabel).append(" ").append(sentimentLabel).append("] ")
                        .append(item.getContent()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 从参考案例的热度数据和质量分构建额外上下文文本。
     * <p>
     * 这些数据帮助 AI 判断竞品的市场表现——播放量、点赞数、弹幕数等热度指标
     * 直接反映视频的传播效果，质量分则综合了热度和互动质量。
     * <p>
     * 数值使用中文单位格式化（如"12.5万"而非"125000"），
     * 因为 LLM 对中文数字的理解优于长数字串。
     *
     * @param refVideo 参考案例父表记录
     * @return 格式化的额外上下文文本
     */
    private String buildReferenceExtraContext(ReferenceVideoRecord refVideo) {
        StringBuilder sb = new StringBuilder();
        sb.append("来源：参考案例库（层级=").append(TextUtil.trimToDefault(refVideo.getTier(), "未知")).append("）");
        if (refVideo.getViewCount() != null && refVideo.getViewCount() > 0) {
            sb.append("，播放量=").append(formatLargeNumber(refVideo.getViewCount()));
        }
        if (refVideo.getLikeCount() != null && refVideo.getLikeCount() > 0) {
            sb.append("，点赞=").append(formatLargeNumber(refVideo.getLikeCount()));
        }
        if (refVideo.getDanmakuCount() != null && refVideo.getDanmakuCount() > 0) {
            sb.append("，弹幕=").append(formatLargeNumber(refVideo.getDanmakuCount()));
        }
        if (refVideo.getReplyCount() != null && refVideo.getReplyCount() > 0) {
            sb.append("，评论=").append(formatLargeNumber(refVideo.getReplyCount()));
        }
        if (refVideo.getQualityScore() != null) {
            sb.append("，质量分=").append(refVideo.getQualityScore());
        }
        return sb.toString();
    }

    /**
     * 将大数值格式化为中文友好的展示形式。
     * <p>
     * 为什么用"万"而非纯数字：LLM 对"12.5万"的理解比对"125000"的理解更准确，
     * 因为训练语料中中文数字带单位的表达更常见。且热度数据本身是估算值，
     * 精确到个位反而误导 AI 以为这是精确统计。
     *
     * @param count 原始数值
     * @return 格式化后的字符串（如 "12.5万"、"8900"）
     */
    private String formatLargeNumber(long count) {
        if (count >= 10000) {
            return String.format("%.1f万", count / 10000.0);
        }
        return String.valueOf(count);
    }
}
