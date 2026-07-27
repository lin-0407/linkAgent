package com.link.linkagent.creator.suggestion.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.core.AgentExecutor;
import com.link.linkagent.creator.context.service.CreatorContextService;
import com.link.linkagent.creator.preference.service.CreatorPreferenceService;
import com.link.linkagent.creator.profile.service.CreatorProfileService;
import com.link.linkagent.creator.report.service.CreatorReportService;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.AnalysisStrategy;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionResponse;
import com.link.linkagent.creator.suggestion.model.PrePublishAnalyzeRequest;
import com.link.linkagent.creator.suggestion.model.PrePublishSuggestionCandidate;
import com.link.linkagent.creator.suggestion.model.PrePublishAuditReport;
import com.link.linkagent.creator.suggestion.model.PrePublishEvidenceRef;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorMaterialType;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.model.CreatorTaskStatus;
import com.link.linkagent.dto.AgentChatResponse;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.LlmJsonUtil;
import com.link.linkagent.util.TextUtil;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * 发布前优化服务 —— 创作者工作流首段：在内容发布前对标题、简介、标签、内容结构等维度
 * 进行 AI 分析，输出结构化优化建议。
 *
 * <h3>架构定位</h3>
 * 位于创作者工作流管道的首端（素材录入 → 发布前优化 → 反馈分析/竞品分析 → 复盘报告）。
 * 该服务是创作者首次与 AI 交互的核心入口，输出将作为后续复盘阶段的基准参照。
 *
 * <h3>核心设计决策</h3>
 * <ol>
 *   <li><b>双路径生成</b>：提供 {@link #generateSuggestion}（单次 LLM 调用）和
 *       {@link #generateSuggestionByAgent}（ReAct Agent 自主取证）两种分析路径。
 *       单次调用性能好适合常规场景；Agent 路径可调用 knowledge_search 等工具检索
 *       同类型案例做参考，适合需要外部信息支撑的深度分析场景。</li>
 *   <li><b>偏好上下文注入</b>：分析时会根据偏好模式（沿用历史/本期换风格/试验新方向）
 *       注入不同权重和范围的创作者历史偏好和视频类型语境，让建议更贴合创作者风格。</li>
 *   <li><b>分析策略路由</b>：根据用户选定的分析策略（教程/Vlog/测评/评论），调整 LLM
 *       的关注重点和评判维度，避免对所有视频类型用同一套分析标准。</li>
 *   <li><b>三明治截断</b>：长文本（文稿/字幕）用"开头 + 中间抽样 + 结尾"的方式截断，
 *       既控制 token 消耗，又保留开头的"钩子信息"和结尾的"总结信息"。</li>
 *   <li><b>解析容错</b>：LLM 原始输出先尝试解析为结构化 JSON 字段；解析失败时标记为
 *       RAW_ONLY，不影响前端展示原始文本。</li>
 * </ol>
 *
 * @see CreatorPreferenceService 偏好上下文的数据来源
 * @see CreatorReportService 复盘阶段会读取本服务的输出作为基准参照
 */
@Service
public class PrePublishSuggestionService {

    /** 非文稿/字幕类素材在提示词中的最大长度（字符数），标题/简介通常较短 */
    private static final int MATERIAL_MAX_LENGTH = 12000;

    /** 三明治截断保留的开头字符数。开头含标题、开场钩子等信息，必须保留让 AI 判断吸引力 */
    private static final int SANDWICH_HEAD_CHARS = 2000;

    /** 三明治截断保留的结尾字符数。结尾含总结、呼吁关注等信息，也需保留 */
    private static final int SANDWICH_TAIL_CHARS = 2000;

    /** 三明治截断中间抽样的字符数。从正文中部均匀抽样，保持对主体内容的感知 */
    private static final int SANDWICH_MIDDLE_CHARS = 8000;

    /** 偏好模式：沿用历史偏好，历史记录作为主要参考 */
    private static final String PREFERENCE_MODE_USE_HISTORY = "USE_HISTORY";
    /** 偏好模式：本期不参考历史偏好，全新风格 */
    private static final String PREFERENCE_MODE_IGNORE_HISTORY = "IGNORE_HISTORY";
    /** 偏好模式：试验新方向，历史偏好仅用于避开不适配点 */
    private static final String PREFERENCE_MODE_EXPERIMENT = "EXPERIMENT";
    /** 分析策略提示词的最大长度（字符数），防止策略描述挤占本期内容上下文 */
    private static final int STRATEGY_PROMPT_MAX_LENGTH = 800;

    /** 任务 CRUD 的 DAO 层 */
    private final CreatorTaskMapper creatorTaskMapper;
    /** 发布前优化建议自身的 DAO */
    private final CreatorSuggestionMapper creatorSuggestionMapper;
    /** 创作者偏好服务，用于构建上下文中的历史偏好段落 */
    private final CreatorPreferenceService creatorPreferenceService;
    /** 视频类型语境服务，提供同类视频的创作语境库 */
    private final CreatorContextService creatorContextService;
    /** 创作者画像服务，提供跨任务聚合的风格/语气/受众认知（方案一） */
    private final CreatorProfileService creatorProfileService;
    /** LLM 调用入口 */
    private final LLMService llmService;
    /** ReAct Agent 执行器，用于 Agent 路径的自主取证分析 */
    private final AgentExecutor agentExecutor;
    /** JSON 解析器，用于解析 LLM 结构化输出 */
    private final ObjectMapper objectMapper;
    /** 提示词模板服务 */
    private final PromptService promptService;
    /** 发布前优化证据收集器，用于把材料、偏好和案例库结果整理成可追溯依据 */
    private final PrePublishEvidenceCollector evidenceCollector;
    /** 发布前优化建议审查器，用确定性规则标记无证据和夸大承诺等质量问题 */
    private final PrePublishSuggestionAuditor suggestionAuditor;

    @Autowired
    public PrePublishSuggestionService(CreatorTaskMapper creatorTaskMapper,
                                       CreatorSuggestionMapper creatorSuggestionMapper,
                                       CreatorPreferenceService creatorPreferenceService,
                                       CreatorContextService creatorContextService,
                                       CreatorProfileService creatorProfileService,
                                       LLMService llmService,
                                       AgentExecutor agentExecutor,
                                       ObjectMapper objectMapper,
                                       PromptService promptService,
                                       PrePublishEvidenceCollector evidenceCollector,
                                       PrePublishSuggestionAuditor suggestionAuditor) {
        this.creatorTaskMapper = creatorTaskMapper;
        this.creatorSuggestionMapper = creatorSuggestionMapper;
        this.creatorPreferenceService = creatorPreferenceService;
        this.creatorContextService = creatorContextService;
        this.creatorProfileService = creatorProfileService;
        this.llmService = llmService;
        this.agentExecutor = agentExecutor;
        this.objectMapper = objectMapper;
        this.promptService = promptService;
        this.evidenceCollector = evidenceCollector;
        this.suggestionAuditor = suggestionAuditor;
    }

    /**
     * 包级可见构造器：用于单测场景，不注入 AgentExecutor、CreatorProfileService（允许为 null）。
     * 单测通常只测单次 LLM 调用的 prompt 构建和解析逻辑，不涉及 Agent 路径和画像服务。
     */
    PrePublishSuggestionService(CreatorTaskMapper creatorTaskMapper,
                                CreatorSuggestionMapper creatorSuggestionMapper,
                                CreatorPreferenceService creatorPreferenceService,
                                CreatorContextService creatorContextService,
                                LLMService llmService,
                                ObjectMapper objectMapper,
                                PromptService promptService) {
        this(creatorTaskMapper, creatorSuggestionMapper, creatorPreferenceService, creatorContextService,
                null, llmService, null, objectMapper, promptService, null, null);
    }

    /**
     * 分析 + 推进任务状态（对外主入口）。
     * 生成优化建议后自动将任务状态推进到 PRE_PUBLISH_ANALYZED。
     *
     * @param taskId  创作任务 ID
     * @param request 用户的分析要求（自定义指导、偏好模式、标题风格等）
     * @return 结构化的发布前优化建议
     */
    @Transactional
    public CreatorSuggestionResponse analyze(String taskId, PrePublishAnalyzeRequest request) {
        CreatorSuggestionResponse response = generateSuggestion(taskId, request);
        creatorTaskMapper.updateTaskStatus(response.taskId(), CreatorTaskStatus.PRE_PUBLISH_ANALYZED.name());
        return response;
    }

    /**
     * 生成并保存发布前优化建议（单次 LLM 调用路径），但不推进任务状态。
     *
     * <p>为什么与 {@link #analyze} 分离：analyze 是一步到位的快捷模式（分析+推进状态），
     * 而 generateSuggestion 支持"先预览建议、用户确认后再推进"的工作流模式。
     * 工作流模式需要用户看到建议后手动确认，确认后才改变任务状态进入下一阶段。
     *
     * <p>使用单次 LLM 调用（buildSystemPrompt + buildUserPrompt），
     * 适合不需要外部数据取证的常规分析场景。性能比 Agent 路径高（一次调用完成），
     * 但不支持工具调用（如 knowledge_search 检索同类型案例）。
     *
     * @param taskId  创作任务 ID
     * @param request 用户的分析要求
     * @return 结构化的发布前优化建议
     */
    @Transactional
    public CreatorSuggestionResponse generateSuggestion(String taskId, PrePublishAnalyzeRequest request) {
        return generateSuggestion(taskId, request, null);
    }

    /**
     * 使用独立的内部偏离提醒重新生成方案。
     * 该提醒不属于用户输入，必须放在系统提示词中，避免多 Agent 传递时被误当成用户的新需求。
     */
    @Transactional
    public CreatorSuggestionResponse generateSuggestion(String taskId,
                                                        PrePublishAnalyzeRequest request,
                                                        String deviationReminder) {
        return saveSuggestion(generateSuggestionCandidate(taskId, request, deviationReminder));
    }

    /**
     * 只生成候选，不写数据库；工作流需要先让独立审查 Agent 检查该候选。
     */
    public PrePublishSuggestionCandidate generateSuggestionCandidate(String taskId,
                                                                     PrePublishAnalyzeRequest request,
                                                                     String deviationReminder) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        List<CreatorMaterialRecord> materials = creatorTaskMapper.listMaterialsByTaskId(taskRecord.getTaskId());
        if (materials.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "创作任务缺少可分析材料");
        }

        PrePublishAnalyzeRequest safeRequest = normalizeRequest(request);
        String preferenceContext = buildPreferencePromptContext(taskRecord, safeRequest);
        List<PrePublishEvidenceRef> evidenceRefs = collectEvidence(taskRecord, materials, safeRequest, preferenceContext);
        String rawOutput;
        // 用 try-with-resources 打开用量上下文，确保 LLM 调用被 Langfuse 正确追踪
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.open(taskRecord.getTaskId(), "发布前优化")) {
            rawOutput = llmService.chat(buildSystemPrompt(deviationReminder),
                    buildUserPrompt(taskRecord, materials, safeRequest, preferenceContext, evidenceRefs));
        }
        CreatorSuggestionRecord suggestionRecord = buildSuggestionRecord(taskRecord.getTaskId(), rawOutput,
                evidenceRefs, "DIRECT_LLM_EVIDENCE", "EVIDENCE_COLLECTED");
        return new PrePublishSuggestionCandidate(suggestionRecord);
    }

    /**
     * 通过 ReAct Agent 自主取证生成发布前优化建议（Agent 路径）。
     *
     * <p>与单次 LLM 调用路径的核心区别：
     * <ul>
     *   <li>Agent 可调用注册过的工具（如 knowledge_search 检索 B 站同类型案例），
     *       先取证再生成建议，输出更接地气。</li>
     *   <li>Agent 内部走完整 ReAct 循环，可能多轮推理 + 工具调用，Token 消耗更高。</li>
     *   <li>Agent 输出必须是合法 JSON（结构化内核走 schema 约束），因此额外校验
     *       最终答案不为空且解析状态为 PARSED，否则抛错让调用方感知。</li>
     * </ul>
     *
     * <p>为什么 Agent 输出必须能解析为结构化 JSON：Agent 路径的提示词中已明确约定
     * JSON schema，如果输出仍无法解析，说明 LLM 严重偏离指令——此时返回 RAW_ONLY
     * 对调用方的价值有限（前端不知道如何渲染无序文本），直接报错更清晰。
     *
     * @param taskId  创作任务 ID
     * @param request 用户的分析要求
     * @return 结构化的发布前优化建议
     * @throws ResponseStatusException Agent 未初始化、最终答案为空或无法解析为 JSON 时抛 500
     */
    @Transactional
    public CreatorSuggestionResponse generateSuggestionByAgent(String taskId, PrePublishAnalyzeRequest request) {
        return saveSuggestion(generateSuggestionCandidateByAgent(taskId, request));
    }

    /**
     * Agent 路径同样先返回候选，避免审查前覆盖数据库里的上一版可用方案。
     */
    public PrePublishSuggestionCandidate generateSuggestionCandidateByAgent(String taskId,
                                                                            PrePublishAnalyzeRequest request) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        List<CreatorMaterialRecord> materials = creatorTaskMapper.listMaterialsByTaskId(taskRecord.getTaskId());
        if (materials.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "创作任务缺少可分析材料");
        }

        PrePublishAnalyzeRequest safeRequest = normalizeRequest(request);
        String preferenceContext = buildPreferencePromptContext(taskRecord, safeRequest);
        List<PrePublishEvidenceRef> evidenceRefs = collectEvidence(taskRecord, materials, safeRequest, preferenceContext);
        if (agentExecutor == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "发布前优化 Agent 未初始化");
        }
        AgentChatResponse agentResponse = agentExecutor.runTask(
                buildAgentTaskPrompt(taskRecord, materials, safeRequest, preferenceContext, evidenceRefs));
        if (TextUtil.hasText(agentResponse.stopReason()) && !TextUtil.hasText(agentResponse.finalAnswer())) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "发布前优化 Agent 未能生成最终答案：" + agentResponse.stopReason());
        }
        String rawOutput = TextUtil.trimToNull(agentResponse.finalAnswer());
        if (rawOutput == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "发布前优化 Agent 返回为空");
        }

        CreatorSuggestionRecord suggestionRecord = buildSuggestionRecord(taskRecord.getTaskId(), rawOutput,
                evidenceRefs, "AGENT_RAG_EVIDENCE", "EVIDENCE_COLLECTED");
        if (!"PARSED".equals(suggestionRecord.getParseStatus())) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "发布前优化 Agent 输出无法解析为结构化 JSON");
        }
        return new PrePublishSuggestionCandidate(suggestionRecord);
    }

    /**
     * 候选通过工作流审查后再保存，并复用现有响应转换逻辑返回完整结果。
     */
    @Transactional
    public CreatorSuggestionResponse saveSuggestion(PrePublishSuggestionCandidate candidate) {
        CreatorSuggestionRecord record = candidate.record();
        creatorSuggestionMapper.upsert(record);
        return getSuggestion(record.getTaskId());
    }

    /**
     * 根据任务 ID 查询已生成的发布前优化建议。
     *
     * @param taskId 创作任务 ID
     * @return 结构化的发布前优化建议
     */
    public CreatorSuggestionResponse getSuggestion(String taskId) {
        getTaskRecord(taskId);
        CreatorSuggestionRecord record = creatorSuggestionMapper.findByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "发布前优化建议不存在"));
        return toResponse(record);
    }

    /** 按任务 ID 查任务记录，不存在抛 404 */
    private CreatorTaskRecord getTaskRecord(String taskId) {
        return creatorTaskMapper.findTaskByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在"));
    }

    /**
     * 将可能为 null 的请求体标准化为全 null 字段的空对象。
     * 不要求用户必须填写所有字段，未填写的以"未提供"兜底传入 prompt。
     */
    private PrePublishAnalyzeRequest normalizeRequest(PrePublishAnalyzeRequest request) {
        if (request != null) {
            return request;
        }
        return new PrePublishAnalyzeRequest(null, null, null, null, null, null);
    }

    /**
     * 根据 LLM 原始输出构建建议记录。
     * 生成唯一 suggestionId、关联 taskId、保存原始文本，然后尝试解析为结构化字段。
     */
    private CreatorSuggestionRecord buildSuggestionRecord(String taskId, String rawOutput,
                                                          List<PrePublishEvidenceRef> evidenceRefs,
                                                          String generationMode,
                                                          String qualityStatus) {
        CreatorSuggestionRecord record = new CreatorSuggestionRecord();
        record.setSuggestionId(UUID.randomUUID().toString());
        record.setTaskId(taskId);
        record.setRawOutput(rawOutput);
        record.setEvidenceRefs(toJson(evidenceRefs));
        record.setGenerationMode(generationMode);
        record.setQualityStatus(qualityStatus);
        fillParsedFields(record, rawOutput);
        auditSuggestion(record, evidenceRefs);
        return record;
    }
    private void auditSuggestion(CreatorSuggestionRecord record, List<PrePublishEvidenceRef> evidenceRefs) {
        if (suggestionAuditor == null) {
            record.setAuditReport(toJson(new PrePublishAuditReport(
                    "AUDIT_SKIPPED",
                    0,
                    "当前运行环境未注入建议审查器，无法执行确定性质量审查。",
                    List.of(),
                    null
            )));
            record.setQualityStatus("AUDIT_SKIPPED");
            return;
        }
        PrePublishAuditReport auditReport = suggestionAuditor.audit(record, evidenceRefs);
        record.setAuditReport(toJson(auditReport));
        record.setQualityStatus(auditReport.status());
    }


    private List<PrePublishEvidenceRef> collectEvidence(CreatorTaskRecord taskRecord,
                                                        List<CreatorMaterialRecord> materials,
                                                        PrePublishAnalyzeRequest request,
                                                        String preferenceContext) {
        if (evidenceCollector == null) {
            return List.of(new PrePublishEvidenceRef(
                    "E1",
                    "SYSTEM_LIMITATION",
                    "证据收集",
                    "pre_publish:evidence_collector",
                    "",
                    "当前运行环境未注入证据收集器，本次建议只保存基础生成结果。",
                    0.3D
            ));
        }
        return evidenceCollector.collect(taskRecord, materials, request, preferenceContext);
    }

    private String buildEvidencePromptInstruction(List<PrePublishEvidenceRef> evidenceRefs) {
        return """
                可引用证据：
                %s

                证据使用要求：
                1. 标题建议、风险点和 HIGH 优先级修改计划必须尽量引用 evidenceIds。
                2. evidenceIds 只能使用上方已经出现的编号，不要编造新编号。
                3. 不要声称知道 B 站推荐算法、真实完播率或未提供的竞品数据。
                4. 如果证据不足，请在 missingInfo 中说明缺失信息。
                5. titleSuggestions 单项请补充 evidenceIds、confidence、assumption；actionableRevisionPlan 单项请补充 evidenceIds、confidence。
                """.formatted(formatEvidenceRefs(evidenceRefs));
    }

    private String formatEvidenceRefs(List<PrePublishEvidenceRef> evidenceRefs) {
        if (evidenceRefs == null || evidenceRefs.isEmpty()) {
            return "没有可引用证据。";
        }
        StringBuilder builder = new StringBuilder();
        for (PrePublishEvidenceRef evidence : evidenceRefs) {
            builder.append("[")
                    .append(evidence.evidenceId())
                    .append("] ")
                    .append(evidence.type())
                    .append("｜")
                    .append(TextUtil.trimToDefault(evidence.sourceName(), "未知来源"))
                    .append("｜置信度=")
                    .append(evidence.confidence())
                    .append("\n摘录：")
                    .append(TextUtil.trimToDefault(evidence.quote(), "无原文摘录"))
                    .append("\n说明：")
                    .append(TextUtil.trimToDefault(evidence.summary(), "无说明"))
                    .append("\n");
        }
        return builder.toString();
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "发布前优化证据序列化失败");
        }
    }

    /**
     * 尝试将 LLM 原始输出解析为结构化 JSON 字段，写入 record。
     *
     * <p>解析容错策略同 {@link CreatorReportService}：
     * 先通过 {@link LlmJsonUtil#extractJsonObject} 提取 JSON 部分，
     * 再用 ObjectMapper 解析。解析成功标记 PARSED，失败标记 RAW_ONLY 保留原始文本。
     */
    private void fillParsedFields(CreatorSuggestionRecord record, String rawOutput) {
        try {
            JsonNode rootNode = objectMapper.readTree(LlmJsonUtil.extractJsonObject(rawOutput));
            record.setContentSummary(LlmJsonUtil.text(rootNode, "contentSummary"));
            record.setCreatorDilemma(LlmJsonUtil.text(rootNode, "creatorDilemma"));
            record.setAudienceProfile(LlmJsonUtil.text(rootNode, "audienceProfile"));
            record.setAudienceHook(LlmJsonUtil.text(rootNode, "audienceHook"));
            record.setContentPositioning(LlmJsonUtil.text(rootNode, "contentPositioning"));
            record.setSellingPoints(LlmJsonUtil.json(objectMapper, rootNode, "sellingPoints"));
            record.setRiskPoints(LlmJsonUtil.json(objectMapper, rootNode, "riskPoints"));
            record.setTitleSuggestions(LlmJsonUtil.json(objectMapper, rootNode, "titleSuggestions"));
            record.setDescriptionSuggestion(LlmJsonUtil.text(rootNode, "descriptionSuggestion"));
            record.setActionableRevisionPlan(LlmJsonUtil.json(objectMapper, rootNode, "actionableRevisionPlan"));
            record.setTagSuggestions(LlmJsonUtil.json(objectMapper, rootNode, "tagSuggestions"));
            record.setPartitionSuggestion(LlmJsonUtil.text(rootNode, "partitionSuggestion"));
            record.setMissingInfo(LlmJsonUtil.json(objectMapper, rootNode, "missingInfo"));
            record.setParseStatus("PARSED");
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            // LLM 输出格式异常时不做二次重试：保留原始文本，让调用方根据 RAW_ONLY 状态决定后续处理
            record.setParseStatus("RAW_ONLY");
        }
    }

    /** 从提示词模板服务中加载发布前优化的系统提示词 */
    private String buildSystemPrompt(String deviationReminder) {
        String basePrompt = promptService.get("pre_publish.system");
        if (!TextUtil.hasText(deviationReminder)) {
            return basePrompt;
        }
        return basePrompt + """


                【内部偏离提醒】
                下面内容来自独立审查，只指出上一版哪里偏离用户原话，不是用户新增的要求，也不是改写方案。
                请重新阅读用户材料后自行调整；不得把提醒扩写成新的目标、受众、立场或限制。
                %s
                """.formatted(deviationReminder.trim());
    }

    /**
     * 构建发布前优化的用户提示词（单次 LLM 调用路径）。
     * 将任务信息、素材内容、用户偏好设置、分析要求拼接为一个完整的 prompt，
     * 通过模板渲染确保格式一致。
     *
     * @param taskRecord 当前任务记录
     * @param materials  任务关联的素材列表
     * @param request    用户的分析要求
     * @return 完整的用户提示词字符串
     */
    private String buildUserPrompt(CreatorTaskRecord taskRecord,
                                   List<CreatorMaterialRecord> materials,
                                   PrePublishAnalyzeRequest request,
                                   String preferenceContext,
                                   List<PrePublishEvidenceRef> evidenceRefs) {
        String basePrompt = promptService.render("pre_publish.user", Map.of(
                "taskName", taskRecord.getTaskName(),
                "taskId", taskRecord.getTaskId(),
                "customGuidance", TextUtil.trimToDefault(request.customGuidance(), "未提供"),
                "preferenceMode", preferenceModeLabel(request.preferenceMode()),
                "preferenceContext", preferenceContext,
                "creatorPreference", TextUtil.trimToDefault(request.creatorPreference(), "未提供"),
                "titleStyle", TextUtil.trimToDefault(request.titleStyle(), "未提供"),
                "extraRequirement", TextUtil.trimToDefault(request.extraRequirement(), "未提供"),
                "materials", buildMaterialPrompt(materials),
                "strategyContext", buildStrategyPrompt(request.analysisStrategy())
        ));
        return basePrompt + "\n\n" + buildEvidencePromptInstruction(evidenceRefs);
    }

    /**
     * 构建 Agent 路径的任务提示词。
     *
     * <p>与单次 LLM 调用路径的 buildUserPrompt 的关键区别：
     * <ul>
     *   <li>不通过模板服务渲染（promptService.render），而是用 Java 字符串格式化，
     *       因为 Agent 路径的提示词内容更固定（固定 JSON schema 和任务目标），
     *       不需要 promptService 的模板管理能力。</li>
     *   <li>包含明确的 JSON schema 约定和 Agent 行为指令（"你的目标"段落），
     *       指导 Agent 在 ReAct 循环中按预期格式输出。</li>
     *   <li>指导 Agent 优先调用知识库工具检索同类型案例，但允许降级（工具不可用时
     *       不报错继续生成），保证 Agent 路径的鲁棒性。</li>
     * </ul>
     */
    private String buildAgentTaskPrompt(CreatorTaskRecord taskRecord,
                                        List<CreatorMaterialRecord> materials,
                                        PrePublishAnalyzeRequest request,
                                        String preferenceContext,
                                        List<PrePublishEvidenceRef> evidenceRefs) {
        return """
                你正在帮助 B 站 UP 主做发布前优化。

                任务信息：
                - taskId：%s
                - taskName：%s
                - videoType：%s
                - 用户补充创作指导：%s
                - 偏好使用方式：%s
                - 用户手动补充偏好：%s
                - 标题风格：%s
                - 额外要求：%s

                创作者历史偏好和当前视频类型语境：
                %s

                已收集的可引用证据：
                %s

                用户主动提供的任务材料：
                %s

                你的目标：
                - 用户原话和已确认限制是最高依据，不要替用户改变主题、受众、立场或边界。
                - 先判断当前内容真正要解决的问题和核心卖点，再给标题、简介、标签、风险点和修改动作；不要套通用运营模板。
                - 可以调用 knowledge_search 补充案例，但最终结论优先引用“已收集的可引用证据”；工具不可用或检索失败时直接降级，不要假装查到结果。
                - 参考案例只说明可借鉴之处，不照搬标题或话术。标题建议和 HIGH 优先级修改动作尽量填写 evidenceIds。
                - 字段内容保持短、自然、能直接修改使用；不要写 AI 套话、报告腔或 P0/P1/1a 式表达。
                - 最终回答必须是一个 JSON 对象，不使用 Markdown 代码块，不输出 JSON 之外的解释。若 Agent 内核要求 finalAnswer，请把完整 JSON 作为 finalAnswer 字符串。
                - 证据不足时写入 missingInfo，不得编造 B 站算法、推荐机制、发布时间效果、播放增长或竞品数据。

                JSON 字段固定如下：
                {
                  "contentSummary": "100字以内的内容摘要",
                  "creatorDilemma": "本期创作者最可能纠结或最容易做错的表达问题，必须具体到当前材料",
                  "audienceProfile": "目标观众判断",
                  "audienceHook": "观众为什么愿意点进来、继续看或收藏评论的核心钩子",
                  "contentPositioning": "本期内容在同类 B 站内容中的表达定位和差异化方向，不得编造具体竞品数据",
                  "sellingPoints": ["核心卖点1", "核心卖点2", "核心卖点3"],
                  "riskPoints": ["可能的表达风险或内容短板"],
                  "titleSuggestions": [
                    {"title": "标题1", "viewerPsychology": "对应的观众心理", "clickReason": "为什么会点", "trustRisk": "可能损伤信任的点", "bestScenario": "最适合的使用场景", "reason": "推荐理由", "risk": "风险提醒", "evidenceIds": ["E1"], "confidence": "HIGH/MEDIUM/LOW", "assumption": "成立前提"}
                  ],
                  "descriptionSuggestion": "简介建议",
                  "actionableRevisionPlan": [
                    {"priority": "HIGH/MEDIUM/LOW", "target": "标题/开头/简介/标签/结构", "problem": "当前具体问题", "action": "可以直接执行的修改动作", "expectedEffect": "这个动作解决的观众或创作者问题", "evidenceIds": ["E1"], "confidence": "HIGH/MEDIUM/LOW"}
                  ],
                  "tagSuggestions": ["标签1", "标签2", "标签3", "标签4", "标签5"],
                  "partitionSuggestion": "建议分区",
                  "missingInfo": ["会影响判断但当前缺失的信息"]
                }
                """.formatted(
                taskRecord.getTaskId(),
                taskRecord.getTaskName(),
                TextUtil.trimToDefault(taskRecord.getVideoType(), "未分类"),
                TextUtil.trimToDefault(request.customGuidance(), "未提供"),
                preferenceModeLabel(request.preferenceMode()),
                TextUtil.trimToDefault(request.creatorPreference(), "未提供"),
                TextUtil.trimToDefault(request.titleStyle(), "未提供"),
                TextUtil.trimToDefault(request.extraRequirement(), "未提供"),
                preferenceContext,
                formatEvidenceRefs(evidenceRefs),
                buildMaterialPrompt(materials)
        );
    }

    /**
     * 构建偏好上下文，根据偏好模式决定历史偏好的注入强度和范围。
     *
     * <p>三种模式的设计权衡：
     * <ul>
     *   <li><b>USE_HISTORY</b>（沿用历史偏好）：注入完整的创作者画像 + 历史偏好 + 视频类型语境库，
     *       AI 会尽量保持延续创作者的历史风格。</li>
     *   <li><b>IGNORE_HISTORY</b>（本期换风格）：不注入任何历史数据，AI 仅根据本期材料
     *       和用户手动要求进行分析。适用于创作者想尝试全新表达风格的场景。</li>
     *   <li><b>EXPERIMENT</b>（试验新方向）：注入历史数据，但标记"历史偏好仅用于避开明显不适配点，
     *       本期用户手动要求优先"。适用于创作者想小步迭代而非完全推翻的场景。</li>
     * </ul>
     *
     * <p>上下文拼接顺序：创作者画像 → 分析策略 → 历史偏好 → 视频类型语境库。
     * 画像放在最前面是因为它是最粗粒度的背景信息，类型语境库放在最后因为与本期视频类型直接相关。
     */
    private String buildPreferencePromptContext(CreatorTaskRecord taskRecord, PrePublishAnalyzeRequest request) {
        String preferenceMode = normalizePreferenceMode(request.preferenceMode());
        if (PREFERENCE_MODE_IGNORE_HISTORY.equals(preferenceMode)) {
            return "本次选择不使用历史创作者偏好和视频类型语境库，请只参考本期用户输入和任务材料。";
        }

        // 创作者画像：跨任务聚合的风格/语气/受众认知（方案一）
        String profileContext = creatorProfileService == null
                ? ""
                : creatorProfileService.buildProfilePromptContext(taskRecord.getUserId());

        // 分析策略：用户指定的分析切入角度（方案三）
        String strategyContext = buildStrategyPrompt(request.analysisStrategy());

        String promptContext = creatorPreferenceService.buildPromptContext(taskRecord.getUserId());
        String typeContext = creatorContextService.buildPromptContext(
                taskRecord.getUserId(),
                taskRecord.getVideoType(),
                "PRE_PUBLISH"
        );
        String mergedContext = (profileContext.isEmpty() ? "" : profileContext + "\n\n")
                + (strategyContext.isEmpty() ? "" : strategyContext + "\n\n")
                + promptContext + "\n\n当前视频类型语境库：\n" + typeContext;
        if (PREFERENCE_MODE_EXPERIMENT.equals(preferenceMode)) {
            return mergedContext + "\n本次选择试验新方向：历史偏好和语境库只用于避开明显不适配点，本期用户手动要求优先。";
        }
        return mergedContext;
    }

    /**
     * 根据分析策略名称构建注入到提示词中的策略指导文本。
     *
     * <p>为什么需要分析策略：不同视频类型（教程/Vlog/测评/评论）的评判标准截然不同——
     * 教程要判断知识点是否完整，Vlog 要判断情感起伏是否自然，用同一套标准评价所有类型
     * 会产生不准确甚至误导的建议。
     *
     * <p>策略文本控制在 {@link #STRATEGY_PROMPT_MAX_LENGTH} 字以内，通过
     * {@link TextUtil#abbreviateWithSuffix} 做截断。策略描述包含两个部分：
     * "请侧重以下维度"（告诉 AI 关注什么）和"不要过度关注"（告诉 AI 忽略什么），
     * 后者和前者同样重要——防止 AI 用教程的评判标准去评价 Vlog 的情感表达。
     *
     * @param strategyName 策略标识字符串，对应 {@link AnalysisStrategy} 枚举值
     * @return 策略指导文本；GENERAL 或未知策略返回空串（通用策略不需要额外注入）
     */
    private String buildStrategyPrompt(String strategyName) {
        AnalysisStrategy strategy = AnalysisStrategy.fromString(strategyName);
        if (strategy == AnalysisStrategy.GENERAL) {
            return ""; // 通用策略不需要额外注入
        }
        String hint = switch (strategy) {
            case TUTORIAL -> """
                    分析策略：教程向分析
                    请侧重以下维度：
                    1. 知识点是否完整且逻辑递进，观众能否跟着学会
                    2. 标题应引导用户明确能学到什么，突出技能获得感
                    3. 标签应覆盖技能关键词，方便用户搜索到教程
                    4. 是否缺少必要的前置知识说明
                    不要过度关注情感表达和叙事节奏，教程观众的首要诉求是"学会"。""";
            case VLOG -> """
                    分析策略：Vlog向分析
                    请侧重以下维度：
                    1. 是否有清晰的情感起伏线和高潮点
                    2. 标题应引发共鸣而非信息罗列，突出情感钩子
                    3. 人物弧光和场景转换是否自然流畅
                    4. 是否有能让观众共情的个人视角
                    不要过度关注知识点覆盖度和信息密度。""";
            case REVIEW -> """
                    分析策略：测评向分析
                    请侧重以下维度：
                    1. 对比框架是否清晰，优劣是否客观有依据
                    2. 标题应突出对比结论或核心差异点
                    3. 购买建议是否具体可执行，而非泛泛"按需选择"
                    4. 是否遗漏了目标用户最关心的对比维度
                    不要过度关注情感叙事，测评观众要的是决策依据。""";
            case COMMENTARY -> """
                    分析策略：评论向分析
                    请侧重以下维度：
                    1. 观点是否独特且有论据支撑，而非人云亦云
                    2. 标题应突出核心观点和讨论价值
                    3. 论据链条是否完整，逻辑是否有漏洞
                    4. 是否预判了可能的反对意见并做了回应
                    不要过度关注信息罗列，评论观众要的是思考深度。""";
            default -> "";
        };
        return TextUtil.abbreviateWithSuffix(hint, STRATEGY_PROMPT_MAX_LENGTH, "");
    }

    /**
     * 标准化偏好模式值：空串/null 默认回退 USE_HISTORY（向后兼容——未传默认沿用历史），
     * 非空做 trim 去首尾空格。
     */
    private String normalizePreferenceMode(String preferenceMode) {
        if (TextUtil.isBlank(preferenceMode)) {
            return PREFERENCE_MODE_USE_HISTORY;
        }
        return preferenceMode.trim();
    }

    /** 将偏好模式枚举值转换为面向用户的中文说明文本 */
    private String preferenceModeLabel(String preferenceMode) {
        String normalized = normalizePreferenceMode(preferenceMode);
        if (PREFERENCE_MODE_IGNORE_HISTORY.equals(normalized)) {
            return "本期换风格，不使用历史偏好";
        }
        if (PREFERENCE_MODE_EXPERIMENT.equals(normalized)) {
            return "试验新方向，历史偏好仅作避坑参考";
        }
        return "沿用历史偏好";
    }

    /**
     * 将素材列表格式化为提示词片段。
     *
     * <p>对文稿和字幕使用三明治截断（保留开头+中间抽样+结尾），因为这两种素材通常很长，
     * 直接全文送入 LLM 会撑爆上下文窗口。三明治截断通过保留首尾保留"钩子信息"（开头）
     * 和"总结信息"（结尾），中间抽样保持对主体内容结构的感知。
     *
     * <p>对标题草稿和简介草稿使用简单截断（abbreviateWithSuffix），因为这两种素材通常较短。
     */
    private String buildMaterialPrompt(List<CreatorMaterialRecord> materials) {
        StringBuilder builder = new StringBuilder();
        for (CreatorMaterialRecord material : materials) {
            builder.append("\n【")
                    .append(toChineseMaterialName(material.getMaterialType()))
                    .append("】\n");

            // 文稿和字幕通常很长，用三明治截断保留开头钩子和结尾总结，避免 AI 丢失关键语境
            String materialType = material.getMaterialType();
            if (CreatorMaterialType.MANUSCRIPT.name().equals(materialType)
                    || CreatorMaterialType.SUBTITLE.name().equals(materialType)) {
                builder.append(TextUtil.abbreviateSandwich(
                        material.getContent(),
                        SANDWICH_HEAD_CHARS,
                        SANDWICH_TAIL_CHARS,
                        SANDWICH_MIDDLE_CHARS,
                        "中间段落已抽样保留关键信息"
                ));
            } else {
                // 标题草稿和简介草稿通常较短，直接用简单截断
                builder.append(TextUtil.abbreviateWithSuffix(
                        material.getContent(),
                        MATERIAL_MAX_LENGTH,
                        "\n[内容过长，已截断用于本次分析]"
                ));
            }
            builder.append("\n");
        }
        return builder.toString();
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

    /** 将数据库记录转为前端响应对象 */
    private CreatorSuggestionResponse toResponse(CreatorSuggestionRecord record) {
        return new CreatorSuggestionResponse(
                record.getId(),
                record.getSuggestionId(),
                record.getTaskId(),
                record.getContentSummary(),
                record.getCreatorDilemma(),
                record.getAudienceProfile(),
                record.getAudienceHook(),
                record.getContentPositioning(),
                record.getSellingPoints(),
                record.getRiskPoints(),
                record.getTitleSuggestions(),
                record.getDescriptionSuggestion(),
                record.getActionableRevisionPlan(),
                record.getTagSuggestions(),
                record.getPartitionSuggestion(),
                record.getEvidenceRefs(),
                record.getMissingInfo(),
                record.getGenerationMode(),
                record.getQualityStatus(),
                record.getAuditReport(),
                record.getRawOutput(),
                record.getParseStatus(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
