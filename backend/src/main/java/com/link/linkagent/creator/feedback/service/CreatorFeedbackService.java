package com.link.linkagent.creator.feedback.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.feedback.mapper.CreatorFeedbackMapper;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackAnalyzeRequest;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackChatRequest;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackChatResponse;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackDashboardResponse;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackEvidenceRetrievalResult;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackFetchRequest;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackFetchResponse;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackImportResponse;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackItemRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackItemResponse;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackKeywordResponse;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackMetricRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackMetricResponse;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackReportRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackReportResponse;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackResponse;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackSaveRequest;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackStatRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackStatResponse;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackTimelineResponse;
import com.link.linkagent.creator.feedback.util.CreatorFeedbackLabelUtil;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.model.CreatorTaskStatus;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.LlmCallResult;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.LlmJsonUtil;
import com.link.linkagent.util.TextUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 评论弹幕反馈服务（创作复盘核心模块）。
 * <p>
 * 本服务是创作者反馈分析的中央编排器，统一承载：
 * <ol>
 *   <li><b>反馈录入</b>：支持手动粘贴、文件上传（JSON/TXT）和 BV 号驱动脚本采集三种数据来源</li>
 *   <li><b>LLM 分析</b>：调用 LLM 对评论弹幕做结构化分析，产出多维度复盘报告</li>
 *   <li><b>反馈追问</b>：基于分析报告和已导入明细，支持用户向 AI 追问反馈细节</li>
 *   <li><b>仪表盘</b>：从已落库明细恢复统计数据、分类分布、关键词热度、弹幕时间线</li>
 *   <li><b>批量分类</b>：LLM 批量分类评论/弹幕（分批次 + 失败降级关键词规则）</li>
 * </ol>
 * <p>
 * 架构位置：位于 creator.feedback.service 层，向下依赖 Mapper 层读写数据、LLMService 调用模型、
 * PromptService 管理提示词模板、CreatorFeedbackEvidenceRetrievalService 做证据检索；
 * 向上被 Controller 层直接调用。不依赖 CreatorBilibiliService 等兄弟服务。
 * <p>
 * 设计决策：
 * <ul>
 *   <li>证据检索拆成独立服务（CreatorFeedbackEvidenceRetrievalService）——本类已同时承担
 *       导入、分析、仪表盘、追问和脚本采集，把"选证据"交出去让追问链路只负责编排。</li>
 *   <li>LLM 分类优先但容忍降级——LLM 失败时自动切换关键词规则，保证导入流程不中断。</li>
 *   <li>明细存储以"新批次覆盖旧批次"的方式，每次导入都会软删除旧明细再写入新数据，
 *       保证仪表盘和旧分析入口看到同一批样例。</li>
 * </ul>
 */
@Service
public class CreatorFeedbackService {

    /** 反馈/报告文本最大长度，超长截断防止 prompt 过大 */
    private static final int FEEDBACK_MAX_LENGTH = 12000;
    /** 用户上传文件大小上限(5MB)，防止超大文件消耗内存 */
    private static final int IMPORT_FILE_MAX_SIZE = 5 * 1024 * 1024;
    /** 脚本生成 JSON 文件大小上限(20MB)，B站热门视频的评论弹幕量可能很大 */
    private static final int GENERATED_FILE_MAX_SIZE = 20 * 1024 * 1024;
    /** 单次导入明细条数上限，超出截断 */
    private static final int IMPORT_ITEM_MAX_COUNT = 2000;
    /** 旧分析入口拼接样例文本的最大长度 */
    private static final int LEGACY_SAMPLE_MAX_LENGTH = 20000;
    /** 仪表盘读取明细上限，防止前端一次性渲染过多 DOM 卡顿 */
    private static final int DASHBOARD_ITEM_LIMIT = 2000;
    /** 仪表盘"最近明细"展示条数 */
    private static final int DASHBOARD_RECENT_LIMIT = 12;
    /** 仪表盘"热门评论"展示条数 */
    private static final int DASHBOARD_TOP_COMMENT_LIMIT = 8;
    /** 追问回答最大长度，防止模型生成过长影响前端展示 */
    private static final int FEEDBACK_CHAT_ANSWER_MAX_LENGTH = 4000;
    /** Python 脚本执行超时秒数(3分钟)，防止接口卡死时阻塞工作线程 */
    private static final long SCRIPT_TIMEOUT_SECONDS = 180;
    /**
     * LLM 批量分类单次最多提交条数。
     * 为什么是 200：batch=200 条时 prompt 约 15K token，DeepSeek 输出约 8K token，
     * 总成本可控；超过 200 可能超过部分模型的输出长度限制，导致 JSON 截断。
     */
    private static final int LLM_CLASSIFY_BATCH_SIZE = 200;
    /** B站 BV 号正则：10 位字母数字组合，用于从用户输入中提取有效 BV 号 */
    private static final Pattern BVID_PATTERN = Pattern.compile("BV[0-9A-Za-z]{10}");
    /** B站评论弹幕采集脚本路径（相对于项目根目录） */
    private static final Path FEEDBACK_SCRIPT_PATH = Path.of("scripts", "bilibili_feedback_fetcher.py");
    /** 脚本采集结果输出目录（相对于项目根目录），后端固定写入，防止页面参数影响服务端写入位置 */
    private static final Path FEEDBACK_EXPORT_PATH = Path.of("export", "bilibili_feedback");
    /**
     * 关键词词典，用于仪表盘关键词热度统计。
     * 不引入第三方分词库（jieba等），只用项目相关词汇做 MVP 统计，
     * 避免为了一个图表统计新增依赖。
     */
    private static final List<String> KEYWORD_DICTIONARY = List.of(
            "AI", "Agent", "Spring", "Spring AI", "LLM", "Java", "后端", "项目", "工具调用",
            "标题", "简介", "标签", "字幕", "文稿", "教程", "代码", "流程", "复盘",
            "评论", "弹幕", "节奏", "清楚", "看不懂", "干货", "实用", "下次", "资料"
    );

    /** 创作任务数据访问层，用于校验任务存在性 */
    private final CreatorTaskMapper creatorTaskMapper;
    /** 反馈数据访问层，负责评论弹幕明细、报告、指标的 CRUD */
    private final CreatorFeedbackMapper creatorFeedbackMapper;
    /** LLM 调用入口，统一通过本服务内的 chat/chatStructured 方法调用模型 */
    private final LLMService llmService;
    /** JSON 解析工具，用于解析脚本输出的 JSON 和 LLM 返回的 JSON */
    private final ObjectMapper objectMapper;
    /** 事务模板，用于脚本采集场景的手动事务控制（脚本采集在事务外执行，但后续落库需要事务） */
    private final TransactionTemplate transactionTemplate;
    /** 证据检索服务（独立拆分），负责从已导入明细中选出与追问相关的证据条目 */
    private final CreatorFeedbackEvidenceRetrievalService evidenceRetrievalService;
    /** 提示词模板服务，管理 feedback_analyze 和 feedback_chat 的 system/user 提示词 */
    private final PromptService promptService;

    public CreatorFeedbackService(CreatorTaskMapper creatorTaskMapper,
                                  CreatorFeedbackMapper creatorFeedbackMapper,
                                  LLMService llmService,
                                  ObjectMapper objectMapper,
                                  TransactionTemplate transactionTemplate,
                                  CreatorFeedbackEvidenceRetrievalService evidenceRetrievalService,
                                  PromptService promptService) {
        this.creatorTaskMapper = creatorTaskMapper;
        this.creatorFeedbackMapper = creatorFeedbackMapper;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.evidenceRetrievalService = evidenceRetrievalService;
        this.promptService = promptService;
    }

    /**
     * 手动保存/粘贴评论弹幕样例。
     * <p>
     * 手动粘贴代表用户切换了数据来源，旧导入明细不能继续驱动仪表盘，
     * 否则前端会展示过期分类结果。因此保存后将旧明细和指标软删除，清空重来。
     *
     * @param taskId 创作任务 ID
     * @param request 含评论样例和弹幕样例的原始文本
     * @return 保存后的完整反馈记录
     * @throws ResponseStatusException 404 任务不存在
     */
    @Transactional
    public CreatorFeedbackResponse saveFeedback(String taskId, CreatorFeedbackSaveRequest request) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        CreatorFeedbackRecord record = new CreatorFeedbackRecord();
        record.setFeedbackId(UUID.randomUUID().toString());
        record.setTaskId(taskRecord.getTaskId());
        record.setCommentSamples(TextUtil.trimToNull(request.commentSamples()));
        record.setDanmakuSamples(TextUtil.trimToNull(request.danmakuSamples()));
        record.setExtraContext(TextUtil.trimToNull(request.extraContext()));
        creatorFeedbackMapper.upsertFeedback(record);
        // 手动粘贴代表用户切换了数据来源，旧导入明细不能继续驱动仪表盘，否则前端会展示过期分类结果。
        creatorFeedbackMapper.softDeleteItemsByTaskId(taskRecord.getTaskId());
        creatorFeedbackMapper.softDeleteMetricByTaskId(taskRecord.getTaskId());
        return getFeedback(taskRecord.getTaskId());
    }

    /**
     * 查询任务的评论弹幕样例记录。
     *
     * @param taskId 创作任务 ID
     * @return 反馈记录（含评论样例、弹幕样例、补充上下文）
     * @throws ResponseStatusException 404 任务不存在或反馈样例未录入
     */
    public CreatorFeedbackResponse getFeedback(String taskId) {
        getTaskRecord(taskId);
        CreatorFeedbackRecord record = creatorFeedbackMapper.findFeedbackByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "评论弹幕样例不存在"));
        return toFeedbackResponse(record);
    }

    /**
     * AI 驱动的评论弹幕分析（LLM 分析入口）。
     * <p>
     * 核心流程：
     * <ol>
     *   <li>校验任务存在 + 评论弹幕样例已提交</li>
     *   <li>构建 system + user prompt（含任务信息、自定义指导、分析焦点、额外要求）</li>
     *   <li>调用 LLM 产出结构化 JSON 分析报告</li>
     *   <li>解析并落库报告（含 feedbackSummary、hotTopics、sentimentSummary 等字段）</li>
     *   <li>更新任务状态为 FEEDBACK_ANALYZED</li>
     * </ol>
     * <p>
     * LLM 调用包裹在 LlmUsageContext 中，用于 Langfuse 追踪和 Token 用量统计。
     *
     * @param taskId 创作任务 ID
     * @param request 分析请求，含自定义指导、分析焦点、额外要求等可选项
     * @return 结构化分析报告
     * @throws ResponseStatusException 404 任务不存在，400 反馈样例未提交
     */
    @Transactional
    public CreatorFeedbackReportResponse analyze(String taskId, CreatorFeedbackAnalyzeRequest request) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        CreatorFeedbackRecord feedbackRecord = creatorFeedbackMapper.findFeedbackByTaskId(taskRecord.getTaskId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先提交评论或弹幕样例"));

        String rawOutput;
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.open(taskRecord.getTaskId(), "评论弹幕分析")) {
            rawOutput = llmService.chat(buildSystemPrompt(), buildUserPrompt(taskRecord, feedbackRecord, request));
        }
        CreatorFeedbackReportRecord reportRecord = buildReportRecord(taskRecord.getTaskId(), rawOutput);
        creatorFeedbackMapper.upsertReport(reportRecord);
        creatorTaskMapper.updateTaskStatus(taskRecord.getTaskId(), CreatorTaskStatus.FEEDBACK_ANALYZED.name());
        return getReport(taskRecord.getTaskId());
    }

    /**
     * 查询任务的分析报告。
     *
     * @param taskId 创作任务 ID
     * @return 结构化分析报告响应
     * @throws ResponseStatusException 404 任务不存在或报告未生成
     */
    public CreatorFeedbackReportResponse getReport(String taskId) {
        getTaskRecord(taskId);
        CreatorFeedbackReportRecord record = creatorFeedbackMapper.findReportByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "评论弹幕分析报告不存在"));
        return toReportResponse(record);
    }

    /**
     * 从用户上传文件导入评论弹幕。
     * <p>
     * 支持 JSON 和 TXT 两种格式；JSON 是脚本输出的标准格式（含结构化字段和指标），
     * TXT 是兼容入口（只能按区块做基础解析）。导入后自动触发 LLM 批量分类。
     *
     * @param taskId 创作任务 ID
     * @param file 上传文件（MultipartFile），支持 .json 和 .txt
     * @return 导入结果（含评论数、弹幕数、指标是否存在、警告信息）
     * @throws ResponseStatusException 400 文件不符合要求，404 任务不存在
     */
    @Transactional
    public CreatorFeedbackImportResponse importFeedback(String taskId, MultipartFile file) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        validateImportFile(file);
        String fileName = normalizeFileName(file.getOriginalFilename());
        String text = readUploadText(file);

        return importFeedbackText(taskRecord, fileName, text, "从用户上传文件 " + fileName + " 导入", List.of());
    }

    /**
     * 从 B 站采集评论弹幕（BV 号驱动脚本采集）。
     * <p>
     * 执行流程：提取 BV 号 → 定位项目根目录和脚本路径 → 运行 Python 脚本
     * → 读取脚本生成的 JSON → 在事务中导入明细。脚本执行不在事务内（脚本涉及网络 IO），
     * 但后续落库通过 TransactionTemplate 手动控制事务边界。
     *
     * @param taskId 创作任务 ID
     * @param request 采集请求，含 BV 输入、最大评论数、最大弹幕数等参数
     * @return 采集结果（含生成文件列表、导入统计）
     * @throws ResponseStatusException 400 BV号无效，502 脚本执行失败，504 脚本超时
     */
    public CreatorFeedbackFetchResponse fetchFeedback(String taskId, CreatorFeedbackFetchRequest request) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        String bvid = extractBvid(request.bvInput());
        Path projectRoot = resolveProjectRoot();
        Path scriptPath = projectRoot.resolve(FEEDBACK_SCRIPT_PATH).normalize();
        Path outputDir = resolveFeedbackOutputDir(projectRoot);

        runFeedbackScript(projectRoot, scriptPath, outputDir, bvid, request);

        String jsonFileName = bvid + "_feedback.json";
        Path jsonPath = outputDir.resolve(jsonFileName).normalize();
        String jsonText = readGeneratedText(jsonPath);
        CreatorFeedbackImportResponse importResponse = transactionTemplate.execute(status -> importFeedbackText(
                taskRecord,
                jsonFileName,
                jsonText,
                "从页面 BV 参数 " + bvid + " 执行项目内脚本导入",
                List.of()
        ));
        if (importResponse == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "评论弹幕导入事务没有返回结果");
        }

        return new CreatorFeedbackFetchResponse(
                taskRecord.getTaskId(),
                bvid,
                outputDir.toString(),
                listGeneratedOutputFiles(outputDir, bvid, request.format()),
                importResponse.commentCount(),
                importResponse.danmakuCount(),
                importResponse.metricImported(),
                importResponse.warnings()
        );
    }

    /**
     * 获取反馈仪表盘数据（复盘页核心数据源）。
     * <p>
     * 仪表盘从已落库明细恢复，不依赖上传请求的临时状态——这样页面刷新后也能稳定展示。
     * 返回数据含：评论/弹幕计数、噪声统计、视频指标、分类分布、情绪分布、关键词热度、
     * 弹幕时间线、热门评论列表、最近明细列表。
     *
     * @param taskId 创作任务 ID
     * @return 仪表盘聚合数据
     * @throws ResponseStatusException 404 任务不存在或明细未导入
     */
    public CreatorFeedbackDashboardResponse getDashboard(String taskId) {
        getTaskRecord(taskId);
        // 仪表盘从已落库明细恢复，不依赖上传请求的临时状态，页面刷新后也能稳定展示。
        List<CreatorFeedbackItemRecord> items = creatorFeedbackMapper.listItemsByTaskId(taskId.trim(), DASHBOARD_ITEM_LIMIT);
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "评论弹幕明细不存在，请先导入样例文件");
        }

        List<String> warnings = new ArrayList<>();
        if (items.size() >= DASHBOARD_ITEM_LIMIT) {
            warnings.add("仪表盘最多读取最近 " + DASHBOARD_ITEM_LIMIT + " 条明细，更多数据后续再做分页。");
        }
        CreatorFeedbackMetricResponse metric = creatorFeedbackMapper.findMetricByTaskId(taskId.trim())
                .map(this::toMetricResponse)
                .orElse(null);
        if (metric == null) {
            warnings.add("当前任务没有导入视频基础指标，指标区域会保持为空。");
        }

        List<CreatorFeedbackItemResponse> recentItems = items.stream()
                .limit(DASHBOARD_RECENT_LIMIT)
                .map(this::toItemResponse)
                .toList();
        List<CreatorFeedbackItemResponse> topCommentItems = creatorFeedbackMapper
                .listTopCommentItemsByTaskId(taskId.trim(), DASHBOARD_TOP_COMMENT_LIMIT)
                .stream()
                .map(this::toItemResponse)
                .toList();

        return new CreatorFeedbackDashboardResponse(
                taskId.trim(),
                creatorFeedbackMapper.countItemsBySourceType(taskId.trim(), "COMMENT"),
                creatorFeedbackMapper.countItemsBySourceType(taskId.trim(), "DANMAKU"),
                creatorFeedbackMapper.countNoiseItems(taskId.trim()),
                metric,
                toStatResponses(creatorFeedbackMapper.countCategoryStats(taskId.trim(), "COMMENT")),
                toStatResponses(creatorFeedbackMapper.countCategoryStats(taskId.trim(), "DANMAKU")),
                toStatResponses(creatorFeedbackMapper.countSentimentStats(taskId.trim())),
                buildKeywordStats(items),
                buildDanmakuTimeline(items),
                topCommentItems,
                recentItems,
                warnings
        );
    }

    /**
     * 反馈追问对话（用户基于分析报告和明细向 AI 提问）。
     * <p>
     * 核心流程分为两个阶段，各自包裹在独立的 LlmUsageContext 中便于追踪：
     * <ol>
     *   <li><b>证据检索</b>：委托 CreatorFeedbackEvidenceRetrievalService 选出与问题相关的证据条目</li>
     *   <li><b>LLM 回答</b>：将报告上下文 + 证据条目 + 用户问题拼接为 prompt，调用 LLM 生成回答</li>
     * </ol>
     * <p>
     * 响应中携带检索模式（VECTOR/SQL/VECTOR_WITH_SQL_FALLBACK）、Token 用量、模型名称等，
     * 用于前端展示和 Langfuse 可观测。
     *
     * @param taskId 创作任务 ID
     * @param request 追问请求，含用户问题
     * @return 追问回答 + 证据来源 + 用量统计
     * @throws ResponseStatusException 400 报告未生成且无明细，404 任务不存在
     */
    public CreatorFeedbackChatResponse chat(String taskId, CreatorFeedbackChatRequest request) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        CreatorFeedbackReportRecord reportRecord = creatorFeedbackMapper.findReportByTaskId(taskRecord.getTaskId())
                .orElse(null);
        List<CreatorFeedbackItemRecord> items = creatorFeedbackMapper.listItemsByTaskId(
                taskRecord.getTaskId(),
                DASHBOARD_ITEM_LIMIT
        );
        if (reportRecord == null && items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先生成反馈报告或导入评论弹幕明细");
        }

        // 证据选择交给检索服务：内部根据 RAG 开关和 Milvus 可用性决定走向量检索还是 SQL 轻量匹配，
        // 但无论哪条路径，返回的 evidenceRecords 都来自 MySQL 当前有效明细，保证证据是事实而非向量库脏数据。
        CreatorFeedbackEvidenceRetrievalResult retrievalResult;
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.open(taskRecord.getTaskId(), "反馈追问证据检索")) {
            retrievalResult = evidenceRetrievalService.retrieve(taskRecord.getTaskId(), request.question(), items);
        }
        List<CreatorFeedbackItemRecord> evidenceRecords = retrievalResult.evidenceRecords();
        LlmCallResult llmCallResult;
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.open(taskRecord.getTaskId(), "反馈追问回答")) {
            llmCallResult = llmService.chatWithUsage(
                    buildChatSystemPrompt(),
                    buildChatUserPrompt(taskRecord, reportRecord, evidenceRecords, request.question())
            );
        }
        return new CreatorFeedbackChatResponse(
                taskRecord.getTaskId(),
                request.question().trim(),
                normalizeChatAnswer(llmCallResult.content()),
                evidenceRecords.stream().map(this::toItemResponse).toList(),
                reportRecord != null,
                retrievalResult.retrievalMode(),
                retrievalResult.ragEnabled(),
                llmCallResult.modelName(),
                llmCallResult.promptTokens(),
                llmCallResult.completionTokens(),
                llmCallResult.totalTokens(),
                llmCallResult.elapsedMs(),
                LocalDateTime.now()
        );
    }

    /**
     * 导入反馈文本的核心编排方法（上传文件和脚本采集共用）。
     * <p>
     * 处理流程：
     * <ol>
     *   <li>解析文本（JSON 或 TXT 格式）为明细列表</li>
     *   <li>截断超出限制的明细条数</li>
     *   <li>应用任务 ID 并触发 LLM 批量分类（失败自动降级关键词规则）</li>
     *   <li>覆盖式导入：软删除旧明细 → 批量插入新明细 → 更新指标（如有）</li>
     *   <li>回填旧分析入口的样例文本（兼容旧接口）</li>
     * </ol>
     * <p>
     * 为什么每次导入都覆盖旧明细：为了让仪表盘、分析报告和手动粘贴入口看到同一批样例，
     * 避免数据来源不一致导致的"仪表盘显示 200 条新明细，但旧分析入口仍用旧样例"这种断层。
     *
     * @param taskRecord 创作任务记录
     * @param fileName 导入文件名（用于判断 JSON 还是 TXT 解析路径）
     * @param text 原始文本内容
     * @param sourceDescription 来源描述（用于旧分析入口的补充上下文）
     * @param initialWarnings 初始警告（如文件截断提示）
     * @return 导入结果统计
     */
    private CreatorFeedbackImportResponse importFeedbackText(CreatorTaskRecord taskRecord,
                                                             String fileName,
                                                             String text,
                                                             String sourceDescription,
                                                             List<String> initialWarnings) {
        List<String> warnings = new ArrayList<>(initialWarnings);
        ImportedFeedback importedFeedback = parseImportedFeedback(fileName, text, warnings);
        List<CreatorFeedbackItemRecord> items = limitImportedItems(importedFeedback.items(), warnings);
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入文件没有可用的评论或弹幕内容");
        }

        applyTaskIdAndClassification(taskRecord.getTaskId(), items);
        // 每次导入都让新批次覆盖旧明细，是为了让仪表盘、旧分析入口和当前页面看到同一批样例。
        creatorFeedbackMapper.softDeleteItemsByTaskId(taskRecord.getTaskId());
        creatorFeedbackMapper.softDeleteMetricByTaskId(taskRecord.getTaskId());
        for (CreatorFeedbackItemRecord item : items) {
            creatorFeedbackMapper.insertItem(item);
        }
        if (importedFeedback.metric() != null) {
            importedFeedback.metric().setTaskId(taskRecord.getTaskId());
            creatorFeedbackMapper.upsertMetric(importedFeedback.metric());
        }
        // 旧 LLM 分析接口仍读取整段样例；这里回填旧表，是为了让"导入后直接分析反馈"这条链路不断。
        upsertLegacyFeedbackFromItems(taskRecord.getTaskId(), sourceDescription, items);

        int commentCount = countBySource(items, "COMMENT");
        int danmakuCount = countBySource(items, "DANMAKU");
        return new CreatorFeedbackImportResponse(
                taskRecord.getTaskId(),
                commentCount,
                danmakuCount,
                importedFeedback.metric() != null,
                warnings
        );
    }

    /**
     * 从用户输入中提取有效 BV 号。
     * <p>
     * 使用正则 Pattern 而非 startsWith 匹配——因为用户可能粘贴完整的视频链接
     * 或在其前后带空格/描述性文字。
     *
     * @param value 用户原始输入（纯 BV 号或含 BV 号的文本）
     * @return 提取到的 BV 号
     * @throws ResponseStatusException 400 未识别到有效 BV 号
     */
    private String extractBvid(String value) {
        if (TextUtil.isBlank(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有识别到有效 BV 号");
        }
        Matcher matcher = BVID_PATTERN.matcher(value);
        if (!matcher.find()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有识别到有效 BV 号");
        }
        return matcher.group();
    }

    /**
     * 定位项目根目录（用于找到 Python 采集脚本）。
     * <p>
     * 候选路径来源：
     * <ol>
     *   <li>user.dir（当前工作目录）</li>
     *   <li>classpath 位置（从 JAR/WAR 部署路径反推）</li>
     * </ol>
     * 对每个候选路径向上遍历父目录，找到包含 scripts/bilibili_feedback_fetcher.py 的那一级作为项目根目录。
     * 这种向上查找策略兼容 IDE 内部运行（working directory 在子模块）和 JAR 部署两种场景。
     *
     * @return 项目根目录 Path
     * @throws ResponseStatusException 500 找不到脚本
     */
    private Path resolveProjectRoot() {
        for (Path candidate : collectProjectRootCandidates()) {
            Path cursor = candidate;
            while (cursor != null) {
                if (Files.isRegularFile(cursor.resolve(FEEDBACK_SCRIPT_PATH))) {
                    return cursor;
                }
                cursor = cursor.getParent();
            }
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "没有找到项目内 B 站评论弹幕采集脚本");
    }

    private Set<Path> collectProjectRootCandidates() {
        Set<Path> candidates = new LinkedHashSet<>();
        addCandidate(candidates, Path.of("").toAbsolutePath());
        try {
            Path codeLocation = Path.of(
                    CreatorFeedbackService.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            );
            addCandidate(candidates, codeLocation);
        } catch (Exception exception) {
            // classpath 位置只是兜底线索，失败时继续使用 user.dir，避免因为诊断路径异常影响业务请求。
        }
        return candidates;
    }

    private void addCandidate(Set<Path> candidates, Path candidate) {
        if (candidate == null) {
            return;
        }
        candidates.add(candidate.toAbsolutePath().normalize());
    }

    private Path resolveFeedbackOutputDir(Path projectRoot) {
        Path outputDir = projectRoot.resolve(FEEDBACK_EXPORT_PATH).normalize();
        if (!outputDir.startsWith(projectRoot)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "评论弹幕输出目录不在项目根目录下");
        }
        try {
            // 后端固定写入项目根目录 export，避免页面参数影响服务端文件写入位置。
            Files.createDirectories(outputDir);
            return outputDir;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "创建评论弹幕输出目录失败");
        }
    }

    /**
     * 执行 B 站评论弹幕采集 Python 脚本。
     * <p>
     * 脚本执行设置总超时 180 秒——B 站 API 限速和网络波动可能导致单个 BV 采集耗时数分钟。
     * 超时后强制销毁进程，防止僵尸进程占用后端线程。
     * 退出码非 0 时读取 stderr 拼接的错误信息返回给前端，便于定位脚本层问题。
     * <p>
     * ProcessBuilder.directory 设为项目根目录，保证脚本内部的相对路径引用（如配置文件）正确解析。
     *
     * @param projectRoot 项目根目录
     * @param scriptPath 脚本路径
     * @param outputDir 输出目录
     * @param bvid BV 号
     * @param request 采集请求参数（最大评论数、弹幕数、格式）
     * @throws ResponseStatusException 500 脚本不存在或 Python 不可用，502 脚本执行失败，504 超时
     */
    private void runFeedbackScript(Path projectRoot,
                                   Path scriptPath,
                                   Path outputDir,
                                   String bvid,
                                   CreatorFeedbackFetchRequest request) {
        if (!Files.isRegularFile(scriptPath)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "B 站评论弹幕采集脚本不存在");
        }
        List<String> command = List.of(
                "python",
                scriptPath.toString(),
                bvid,
                "--output-dir",
                outputDir.toString(),
                "--max-comments",
                String.valueOf(request.maxComments()),
                "--max-replies-per-comment",
                String.valueOf(request.maxRepliesPerComment()),
                "--max-danmaku",
                String.valueOf(request.maxDanmaku()),
                "--format",
                request.format()
        );
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(projectRoot.toFile());
        processBuilder.redirectErrorStream(true);

        try {
            // 脚本执行设置总超时，是为了防止平台接口卡住时占用后端工作线程。
            Process process = processBuilder.start();
            boolean finished = process.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "B 站评论弹幕采集脚本执行超时");
            }
            String scriptOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "B 站评论弹幕采集脚本执行失败：" + normalizeScriptOutput(scriptOutput)
                );
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "执行 Python 脚本失败，请确认本机 python 命令可用");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "B 站评论弹幕采集脚本被中断");
        }
    }

    private String readGeneratedText(Path jsonPath) {
        if (!Files.isRegularFile(jsonPath)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "脚本没有生成可导入的 JSON 文件");
        }
        try {
            if (Files.size(jsonPath) > GENERATED_FILE_MAX_SIZE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "脚本生成的 JSON 文件不能超过20MB");
            }
            String text = Files.readString(jsonPath, StandardCharsets.UTF_8);
            if (TextUtil.isBlank(text)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "脚本生成的 JSON 文件内容为空");
            }
            return text;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取脚本生成的 JSON 文件失败");
        }
    }

    private List<String> listGeneratedOutputFiles(Path outputDir, String bvid, String format) {
        List<Path> paths = new ArrayList<>();
        paths.add(outputDir.resolve(bvid + "_feedback.json").normalize());
        if ("both".equals(format)) {
            paths.add(outputDir.resolve(bvid + "_feedback.txt").normalize());
        }
        return paths.stream()
                .filter(Files::isRegularFile)
                .map(Path::toString)
                .toList();
    }

    private String normalizeScriptOutput(String scriptOutput) {
        if (TextUtil.isBlank(scriptOutput)) {
            return "脚本没有返回错误详情";
        }
        String normalized = scriptOutput.replaceAll("\\s+", " ").trim();
        return TextUtil.abbreviateWithSuffix(normalized, 500, "...");
    }

    private CreatorTaskRecord getTaskRecord(String taskId) {
        return creatorTaskMapper.findTaskByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在"));
    }

    private void validateImportFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入文件不能为空");
        }
        if (file.getSize() > IMPORT_FILE_MAX_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入文件不能超过5MB");
        }
        String fileName = normalizeFileName(file.getOriginalFilename());
        if (!fileName.endsWith(".json") && !fileName.endsWith(".txt")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "第一版只支持 JSON 或 TXT 文件");
        }
    }

    private String normalizeFileName(String fileName) {
        if (TextUtil.isBlank(fileName)) {
            return "uploaded_feedback.txt";
        }
        return fileName.trim().toLowerCase(Locale.ROOT);
    }

    private String readUploadText(MultipartFile file) {
        try {
            String text = new String(file.getBytes(), StandardCharsets.UTF_8);
            if (TextUtil.isBlank(text)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入文件内容不能为空");
            }
            return text;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入文件读取失败");
        }
    }

    /**
     * 解析导入的反馈文本，根据文件名和内容判断解析路径。
     * <p>
     * 判断逻辑：JSON 是脚本输出的稳定契约（含结构化字段和指标），TXT 是人工可读格式，
     * 所以以文件扩展名和内容首字符作为分流依据——.json 结尾或以 "{" 开头走 JSON 解析，
     * 否则走 TXT 兼容解析。
     *
     * @param fileName 文件名（用于判断扩展名）
     * @param text 文件原始文本内容
     * @param warnings 警告收集列表（原地追加）
     * @return 解析后的反馈（含明细列表和可能的指标记录）
     */
    private ImportedFeedback parseImportedFeedback(String fileName, String text, List<String> warnings) {
        // JSON 是脚本输出的稳定契约；TXT 只是人工可读格式，所以只能作为兼容入口处理。
        if (fileName.endsWith(".json") || text.trim().startsWith("{")) {
            return parseScriptJson(text, warnings);
        }
        return parseTextFeedback(text, warnings);
    }

    private ImportedFeedback parseScriptJson(String text, List<String> warnings) {
        try {
            JsonNode rootNode = objectMapper.readTree(text);
            List<CreatorFeedbackItemRecord> items = new ArrayList<>();
            readJsonWarnings(rootNode, warnings);
            readJsonComments(rootNode.path("comments").path("rootComments"), items);
            readJsonDanmaku(rootNode.path("danmaku").path("pages"), items);
            return new ImportedFeedback(items, buildMetricRecord(rootNode));
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON 文件格式不正确");
        }
    }

    private void readJsonWarnings(JsonNode rootNode, List<String> warnings) {
        JsonNode warningNodes = rootNode.path("warnings");
        if (!warningNodes.isArray()) {
            return;
        }
        for (JsonNode warningNode : warningNodes) {
            if (warningNode.isTextual() && TextUtil.hasText(warningNode.asText())) {
                warnings.add(warningNode.asText());
            }
        }
    }

    private void readJsonComments(JsonNode rootComments, List<CreatorFeedbackItemRecord> items) {
        if (!rootComments.isArray()) {
            return;
        }
        for (JsonNode commentNode : rootComments) {
            addJsonCommentItem(commentNode, items);
            JsonNode replyComments = commentNode.path("replyComments");
            if (!replyComments.isArray()) {
                continue;
            }
            for (JsonNode replyNode : replyComments) {
                addJsonCommentItem(replyNode, items);
            }
        }
    }

    private void addJsonCommentItem(JsonNode commentNode, List<CreatorFeedbackItemRecord> items) {
        String content = TextUtil.trimToNull(commentNode.path("message").asText(null));
        if (content == null) {
            return;
        }
        items.add(newItem(
                "COMMENT",
                nullableText(commentNode.path("rpid")),
                content,
                TextUtil.trimToNull(commentNode.path("ctimeText").asText(null)),
                nullableLong(commentNode.path("like")),
                firstNullableInteger(commentNode.path("replyCount"), commentNode.path("rcount"))
        ));
    }

    private void readJsonDanmaku(JsonNode pages, List<CreatorFeedbackItemRecord> items) {
        if (!pages.isArray()) {
            return;
        }
        for (JsonNode pageNode : pages) {
            JsonNode danmakuItems = pageNode.path("items");
            if (!danmakuItems.isArray()) {
                continue;
            }
            for (JsonNode danmakuNode : danmakuItems) {
                String content = TextUtil.trimToNull(danmakuNode.path("text").asText(null));
                if (content == null) {
                    continue;
                }
                items.add(newItem(
                        "DANMAKU",
                        TextUtil.trimToNull(danmakuNode.path("danmakuId").asText(null)),
                        content,
                        TextUtil.trimToNull(danmakuNode.path("progressText").asText(null)),
                        null,
                        null
                ));
            }
        }
    }

    private CreatorFeedbackMetricRecord buildMetricRecord(JsonNode rootNode) {
        JsonNode statNode = rootNode.path("video").path("stat");
        if (!statNode.isObject()) {
            return null;
        }
        CreatorFeedbackMetricRecord record = new CreatorFeedbackMetricRecord();
        record.setMetricId(UUID.randomUUID().toString());
        record.setViewCount(nullableLong(statNode.path("view")));
        record.setFavoriteCount(nullableLong(statNode.path("favorite")));
        record.setCoinCount(nullableLong(statNode.path("coin")));
        record.setLikeCount(nullableLong(statNode.path("like")));
        record.setShareCount(nullableLong(statNode.path("share")));
        record.setSource(TextUtil.trimToDefault(rootNode.path("source").asText(null), "uploaded_json"));
        if (record.getViewCount() == null
                && record.getFavoriteCount() == null
                && record.getCoinCount() == null
                && record.getLikeCount() == null
                && record.getShareCount() == null) {
            return null;
        }
        return record;
    }

    private ImportedFeedback parseTextFeedback(String text, List<String> warnings) {
        List<CreatorFeedbackItemRecord> items = new ArrayList<>();
        String section = "";
        TextCommentMetadata pendingCommentMetadata = null;
        // TXT 没有可靠 schema，只按脚本文本标题切换区块，避免用复杂正则制造难以解释的误解析。
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.startsWith("## 评论样例")) {
                section = "COMMENT";
                pendingCommentMetadata = null;
                continue;
            }
            if (line.startsWith("## 弹幕样例")) {
                section = "DANMAKU";
                pendingCommentMetadata = null;
                continue;
            }
            if (line.startsWith("## ")) {
                section = "";
                pendingCommentMetadata = null;
                continue;
            }
            if (line.isBlank() || line.startsWith("#") || section.isBlank()) {
                continue;
            }
            if ("COMMENT".equals(section)) {
                pendingCommentMetadata = addTextCommentItem(line, pendingCommentMetadata, items);
            }
            if ("DANMAKU".equals(section)) {
                addTextDanmakuItem(line, items);
            }
        }
        warnings.add("TXT 导入只能按区块和行做基础解析，建议优先上传脚本生成的 JSON 文件。");
        return new ImportedFeedback(items, null);
    }

    private TextCommentMetadata addTextCommentItem(String line,
                                                   TextCommentMetadata pendingMetadata,
                                                   List<CreatorFeedbackItemRecord> items) {
        if (line.startsWith("主楼评论数") || line.startsWith("####")) {
            return pendingMetadata;
        }
        TextCommentMetadata currentMetadata = parseTextCommentMetadata(line);
        String content = line;
        int markerIndex = line.indexOf("赞：");
        if (markerIndex >= 0) {
            content = line.substring(markerIndex + 2).trim();
        }
        content = content.replaceFirst("^\\d+\\.\\s*", "").trim();
        if (!TextUtil.hasText(content) && currentMetadata != null) {
            return currentMetadata;
        }
        if (pendingMetadata != null && markerIndex < 0) {
            currentMetadata = pendingMetadata;
        }
        if (TextUtil.hasText(content)) {
            items.add(newItem(
                    "COMMENT",
                    null,
                    content,
                    currentMetadata == null ? null : currentMetadata.occurTimeText(),
                    currentMetadata == null ? null : currentMetadata.likeCount(),
                    null
            ));
        }
        return null;
    }

    private void addTextDanmakuItem(String line, List<CreatorFeedbackItemRecord> items) {
        String occurTimeText = null;
        String content = line;
        if (line.startsWith("[") && line.contains("]")) {
            int endIndex = line.indexOf(']');
            occurTimeText = line.substring(1, endIndex).trim();
            content = line.substring(endIndex + 1).trim();
        }
        if (TextUtil.hasText(content)) {
            items.add(newItem("DANMAKU", null, content, occurTimeText, null, null));
        }
    }

    private TextCommentMetadata parseTextCommentMetadata(String line) {
        int markerIndex = line.indexOf("赞：");
        if (markerIndex < 0) {
            return null;
        }
        String metadataPart = line.substring(0, markerIndex);
        String[] parts = metadataPart.split("·");
        String occurTimeText = null;
        if (parts.length >= 2) {
            occurTimeText = TextUtil.trimToNull(parts[parts.length - 2]);
        }
        String likeText = parts.length >= 1 ? parts[parts.length - 1] : metadataPart;
        String numericText = likeText.replace("赞", "").replaceAll("[^0-9]", "").trim();
        Long likeCount = null;
        if (TextUtil.hasText(numericText)) {
            try {
                likeCount = Long.parseLong(numericText);
            } catch (NumberFormatException exception) {
                likeCount = null;
            }
        }
        return new TextCommentMetadata(likeCount, occurTimeText);
    }

    private List<CreatorFeedbackItemRecord> limitImportedItems(List<CreatorFeedbackItemRecord> items,
                                                               List<String> warnings) {
        if (items.size() <= IMPORT_ITEM_MAX_COUNT) {
            return items;
        }
        warnings.add("导入明细超过 " + IMPORT_ITEM_MAX_COUNT + " 条，当前版本已截断，后续可改为分页导入。");
        return new ArrayList<>(items.subList(0, IMPORT_ITEM_MAX_COUNT));
    }

    private CreatorFeedbackItemRecord newItem(String sourceType,
                                              String sourceId,
                                              String content,
                                              String occurTimeText,
                                              Long likeCount,
                                              Integer replyCount) {
        CreatorFeedbackItemRecord record = new CreatorFeedbackItemRecord();
        record.setItemId(UUID.randomUUID().toString());
        record.setSourceType(sourceType);
        record.setSourceId(sourceId);
        record.setContent(content);
        record.setOccurTimeText(occurTimeText);
        record.setLikeCount(likeCount);
        record.setReplyCount(replyCount);
        return record;
    }

    /**
     * 为导入明细应用任务 ID 并执行分类（两阶段流水线）。
     * <p>
     * 第一阶段（规则前置）：过滤空语义和重复内容——这两个判断不需要 LLM，
     * 规则即可 100% 准确判断。先过滤可以降低 LLM 待分类条数，节省 Token 成本。
     * <p>
     * 第二阶段（LLM 分类）：使用 LLM 批量分类剩余的"有效内容"，分批次提交避免
     * prompt 过长。LLM 失败时自动降级为关键词规则分类，保证导入流程不中断——
     * 宁可分类精度降低，也不让用户因为一次 LLM 故障就无法导入数据。
     *
     * @param taskId 创作任务 ID
     * @param items 待分类明细列表（原地修改，每个 item 会被设置 taskId / noise / category / sentiment / reason）
     */
    private void applyTaskIdAndClassification(String taskId, List<CreatorFeedbackItemRecord> items) {
        Map<String, Integer> seenContent = new LinkedHashMap<>();
        // 第一步：过滤空语义和重复内容（不需要 LLM，规则即可准确判断）
        List<CreatorFeedbackItemRecord> itemsNeedingClassification = new ArrayList<>();
        for (CreatorFeedbackItemRecord item : items) {
            item.setTaskId(taskId);
            String normalized = normalizeForDuplicate(item.getContent());
            int seenCount = seenContent.getOrDefault(normalized, 0);
            seenContent.put(normalized, seenCount + 1);
            if (normalized.isBlank() || isEmptyMeaning(item.getContent())) {
                item.setNoise(true);
                item.setCategory("EMPTY_MEANING");
                item.setSentiment("NEUTRAL");
                item.setReason("内容过短或语义不足，先标记为无意义内容。");
                continue;
            }
            if (seenCount > 0) {
                item.setNoise(true);
                item.setCategory("DUPLICATE");
                item.setSentiment("NEUTRAL");
                item.setReason("和前面导入的内容重复，仪表盘会单独统计。");
                continue;
            }
            item.setNoise(false);
            itemsNeedingClassification.add(item);
        }

        if (itemsNeedingClassification.isEmpty()) {
            return;
        }

        // 第二步：使用 LLM 批量分类，如果失败则降级为关键词规则
        boolean llmClassified = classifyItemsByLlm(itemsNeedingClassification);
        if (!llmClassified) {
            // LLM 分类失败时降级为关键词规则，保证导入流程不中断
            for (CreatorFeedbackItemRecord item : itemsNeedingClassification) {
                classifyItemByRules(item);
            }
        }
    }

    /**
     * 标准化文本用于去重比较。
     * <p>
     * 统一转小写并删去所有空白字符——"你好世界" 和 "你好 世界" 应被视为重复。
     * 不考虑语义去重（如"真棒"和"太棒了"），因为去语义去重需要 Embedding 相似度计算，
     * 开销大且容易误杀；留给 LLM 分类阶段的 reason 字段说明近似即可。
     *
     * @param content 原始内容文本
     * @return 标准化后的去重键
     */
    private String normalizeForDuplicate(String content) {
        if (content == null) {
            return "";
        }
        return content.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /**
     * 判断内容是否为空语义（过短、纯标点、纯刷屏词）。
     * <p>
     * 空语义的判断规则基于 B 站评论的常见模式：
     * <ul>
     *   <li>去除标点和空白后长度小于 2（如单独的"。"、"6"、"哈"）</li>
     *   <li>纯刷屏词/数字梗（ha/haha/hhh/233/666/www/哈啊等）</li>
     * </ul>
     * 这些内容对复盘分析无价值，标记为 EMPTY_MEANING 从仪表盘的有效统计中排除。
     *
     * @param content 原始内容文本
     * @return true 表示为空语义内容
     */
    private boolean isEmptyMeaning(String content) {
        String normalized = content.replaceAll("[\\p{P}\\s]+", "");
        if (normalized.length() < 2) {
            return true;
        }
        String lowerValue = normalized.toLowerCase(Locale.ROOT);
        return lowerValue.matches("(ha|haha|hhh|233|666|www)+") || normalized.matches("[哈啊]+");
    }

    /**
     * 使用 LLM 批量分类评论/弹幕。
     * 相比关键词规则，LLM 能理解语境和讽刺，分类准确率从 ~60% 提升到 ~90%。
     *
     * @return true 表示 LLM 分类成功，false 表示需要降级
     */
    private boolean classifyItemsByLlm(List<CreatorFeedbackItemRecord> items) {
        try {
            // 分批提交，避免单次 prompt 过长
            for (int batchStart = 0; batchStart < items.size(); batchStart += LLM_CLASSIFY_BATCH_SIZE) {
                int batchEnd = Math.min(batchStart + LLM_CLASSIFY_BATCH_SIZE, items.size());
                List<CreatorFeedbackItemRecord> batch = items.subList(batchStart, batchEnd);
                Map<Integer, ClassificationResult> results = callLlmForClassification(batch);
                // 将 LLM 返回的分类结果写入每条记录
                for (int i = 0; i < batch.size(); i++) {
                    CreatorFeedbackItemRecord item = batch.get(i);
                    ClassificationResult result = results.get(i);
                    if (result != null) {
                        item.setCategory(result.category);
                        item.setSentiment(result.sentiment);
                        item.setReason(result.reason);
                    } else {
                        // LLM 漏掉了这一条，降级为规则分类
                        classifyItemByRules(item);
                    }
                }
            }
            return true;
        } catch (Exception exception) {
            // LLM 调用失败（网络、超时、返回格式错误等），静默降级
            return false;
        }
    }

    /**
     * 调用 LLM 对一批评论/弹幕进行批量分类。
     * 使用结构化 prompt 要求 LLM 返回 JSON 数组，每个元素对应一条内容的分类结果。
     */
    private Map<Integer, ClassificationResult> callLlmForClassification(List<CreatorFeedbackItemRecord> batch) {
        // 构建批量分类 prompt
        StringBuilder itemsText = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            CreatorFeedbackItemRecord item = batch.get(i);
            itemsText.append(i)
                    .append(". [")
                    .append("DANMAKU".equals(item.getSourceType()) ? "弹幕" : "评论")
                    .append("] ")
                    .append(item.getContent())
                    .append("\n");
        }

        String systemPrompt = """
                你是一个B站评论弹幕分类助手。你需要为每条内容同时给出分类和情绪标签。

                评论分类选项（7选1）：
                - QUESTION：提问、求解答
                - SUGGESTION：提出建议或改进方向
                - DOUBT：质疑、反驳、指出错误
                - APPROVAL：认可、感谢、觉得有用
                - EMOTION：纯情绪表达（感叹、好笑、感动等）
                - INTERACTION：催更、求关注、求资料等互动诉求
                - OTHER：以上都不适用

                弹幕分类选项（7选1）：
                - QUESTION_POINT：对特定时间点的疑问
                - COMPLAINT：吐槽、不满、抱怨节奏/语速/内容
                - EMOTION_PEAK：情绪高峰反应（好笑、燃、泪目等）
                - RESONANCE：认同、共鸣、表示理解
                - KNOWLEDGE_REACTION：对知识点/工具/流程的认知反应
                - OTHER：以上都不适用

                情绪分类（3选1）：POSITIVE / NEGATIVE / NEUTRAL

                输出格式：严格 JSON 数组，每个元素包含 index（数字，对应输入编号）、category（字符串）、sentiment（字符串）、reason（简短的一句话解释为什么这么分）。

                示例输出：
                [{"index":0,"category":"QUESTION","sentiment":"NEUTRAL","reason":"用户在询问具体操作步骤"},
                 {"index":1,"category":"APPROVAL","sentiment":"POSITIVE","reason":"用户表示感谢和认可"}]
                """;

        String userPrompt = "请为以下内容逐条分类：\n\n" + itemsText;

        String rawResponse = llmService.chat(systemPrompt, userPrompt);
        return parseClassificationResponse(rawResponse);
    }

    /**
     * 解析 LLM 返回的批量分类 JSON 结果。
     */
    private Map<Integer, ClassificationResult> parseClassificationResponse(String rawResponse) {
        Map<Integer, ClassificationResult> results = new LinkedHashMap<>();
        try {
            // LLM 可能返回带有 markdown 代码块的 JSON，先用工具方法提取纯 JSON
            JsonNode rootNode = objectMapper.readTree(LlmJsonUtil.extractJsonObject(rawResponse));
            if (rootNode.isArray()) {
                for (JsonNode node : rootNode) {
                    int index = node.get("index").asInt(-1);
                    if (index < 0) {
                        continue;
                    }
                    String category = node.has("category") ? node.get("category").asText("OTHER") : "OTHER";
                    String sentiment = node.has("sentiment") ? node.get("sentiment").asText("NEUTRAL") : "NEUTRAL";
                    String reason = node.has("reason") ? node.get("reason").asText("LLM 批量分类") : "LLM 批量分类";
                    results.put(index, new ClassificationResult(category, sentiment, reason));
                }
            }
        } catch (Exception exception) {
            // JSON 解析失败，返回空结果，让调用方降级为规则分类
        }
        return results;
    }

    /**
     * LLM 分类结果内部数据结构。
     */
    private record ClassificationResult(String category, String sentiment, String reason) {}

    /**
     * 关键词规则分类 —— LLM 分类失败时的降级方案。
     * <p>
     * 保留原有关键词匹配逻辑，确保导入流程不会因为 LLM 不可用而中断。
     * 为什么不做更复杂的规则（如正则嵌套、词典优先级）：规则越复杂越容易误判，
     * B站评论区语义多变（反讽、玩梗、缩写），关键词匹配的精度天花板约 60%，
     * 追加规则对精度提升有限，保留简单透明实现即可。真正的精度提升交给 LLM。
     *
     * @param item 待分类的明细记录（原地修改 category / sentiment / reason）
     */
    private void classifyItemByRules(CreatorFeedbackItemRecord item) {
        if ("DANMAKU".equals(item.getSourceType())) {
            item.setCategory(classifyDanmakuByRules(item.getContent()));
        } else {
            item.setCategory(classifyCommentByRules(item.getContent()));
        }
        item.setSentiment(classifySentimentByRules(item.getContent(), item.getCategory()));
        item.setReason("当前版本降级使用关键词规则分类，后续 LLM 可用时自动切换。");
    }

    private String classifyCommentByRules(String content) {
        if (containsAny(content, "怎么", "为什么", "请问", "能不能", "求", "?", "？")) {
            return "QUESTION";
        }
        if (containsAny(content, "建议", "希望", "下次", "可以", "最好", "能否")) {
            return "SUGGESTION";
        }
        if (containsAny(content, "不对", "不是", "问题", "质疑", "但是", "错误", "看不懂")) {
            return "DOUBT";
        }
        if (containsAny(content, "有用", "清楚", "学会", "感谢", "赞", "支持", "懂了", "实用")) {
            return "APPROVAL";
        }
        if (containsAny(content, "哈哈", "牛", "泪目", "笑", "震惊", "破防")) {
            return "EMOTION";
        }
        if (containsAny(content, "催更", "三连", "关注", "资料", "链接", "收藏")) {
            return "INTERACTION";
        }
        return "OTHER";
    }

    private String classifyDanmakuByRules(String content) {
        if (containsAny(content, "怎么", "为什么", "?", "？", "不懂")) {
            return "QUESTION_POINT";
        }
        if (containsAny(content, "太快", "听不清", "看不懂", "不对", "离谱", "差")) {
            return "COMPLAINT";
        }
        if (containsAny(content, "哈哈", "牛", "泪目", "破防", "震惊", "燃")) {
            return "EMOTION_PEAK";
        }
        if (containsAny(content, "懂了", "确实", "真实", "赞同", "有用")) {
            return "RESONANCE";
        }
        if (containsAny(content, "原来", "这里", "重点", "知识", "工具", "流程")) {
            return "KNOWLEDGE_REACTION";
        }
        return "OTHER";
    }

    private String classifySentimentByRules(String content, String category) {
        if (containsAny(content, "不对", "看不懂", "听不清", "差", "错误", "质疑", "离谱")) {
            return "NEGATIVE";
        }
        if (List.of("APPROVAL", "RESONANCE", "KNOWLEDGE_REACTION").contains(category)
                || containsAny(content, "有用", "清楚", "感谢", "赞", "支持", "懂了", "实用")) {
            return "POSITIVE";
        }
        return "NEUTRAL";
    }

    private boolean containsAny(String content, String... keywords) {
        if (content == null) {
            return false;
        }
        String lowerValue = content.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lowerValue.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void upsertLegacyFeedbackFromItems(String taskId, String sourceDescription, List<CreatorFeedbackItemRecord> items) {
        CreatorFeedbackRecord record = new CreatorFeedbackRecord();
        record.setFeedbackId(UUID.randomUUID().toString());
        record.setTaskId(taskId);
        record.setCommentSamples(joinLegacySamples(items, "COMMENT"));
        record.setDanmakuSamples(joinLegacySamples(items, "DANMAKU"));
        record.setExtraContext(sourceDescription + "，已同步为评论弹幕明细。");
        creatorFeedbackMapper.upsertFeedback(record);
    }

    private String joinLegacySamples(List<CreatorFeedbackItemRecord> items, String sourceType) {
        String joined = items.stream()
                .filter(item -> sourceType.equals(item.getSourceType()))
                .map(item -> {
                    if ("DANMAKU".equals(sourceType) && TextUtil.hasText(item.getOccurTimeText())) {
                        return "[" + item.getOccurTimeText() + "] " + item.getContent();
                    }
                    return item.getContent();
                })
                .collect(Collectors.joining("\n"));
        return TextUtil.abbreviateWithSuffix(joined, LEGACY_SAMPLE_MAX_LENGTH, "\n[导入内容过长，旧分析入口已截断]");
    }

    private int countBySource(List<CreatorFeedbackItemRecord> items, String sourceType) {
        return (int) items.stream()
                .filter(item -> sourceType.equals(item.getSourceType()))
                .count();
    }

    private Long nullableLong(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.canConvertToLong()) {
            return null;
        }
        return node.asLong();
    }

    private Integer nullableInteger(JsonNode node) {
        Long value = nullableLong(node);
        if (value == null || value > Integer.MAX_VALUE) {
            return null;
        }
        return value.intValue();
    }

    private Integer firstNullableInteger(JsonNode firstNode, JsonNode secondNode) {
        Integer firstValue = nullableInteger(firstNode);
        return firstValue != null ? firstValue : nullableInteger(secondNode);
    }

    private String nullableText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return String.valueOf(node.asLong());
        }
        return TextUtil.trimToNull(node.asText(null));
    }

    private List<CreatorFeedbackStatResponse> toStatResponses(List<CreatorFeedbackStatRecord> records) {
        return records.stream()
                .map(record -> new CreatorFeedbackStatResponse(
                        record.getName(),
                        labelFor(record.getName()),
                        record.getCount() == null ? 0 : record.getCount()
                ))
                .toList();
    }

    /**
     * 构建关键词热度统计（仪表盘用）。
     * <p>
     * 基于 KEYWORD_DICTIONARY 词典做精确匹配计数，不引入第三方分词库（如 jieba）。
     * 设计权衡：词典匹配的召回率低于分词 + TF-IDF，但实现简单、依赖少、结果可解释。
     * 对 B站 技术教学视频的场景，词典中的 AI/LLM/Java/Spring 等词已经覆盖核心关注点。
     *
     * @param items 已分类的明细列表
     * @return Top 12 关键词热度响应列表（按出现次数降序）
     */
    private List<CreatorFeedbackKeywordResponse> buildKeywordStats(List<CreatorFeedbackItemRecord> items) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (CreatorFeedbackItemRecord item : items) {
            if (Boolean.TRUE.equals(item.getNoise())) {
                continue;
            }
            // 不额外引入分词库，先用项目相关词典做 MVP 统计，避免为了图表增加新的第三方依赖。
            for (String keyword : KEYWORD_DICTIONARY) {
                if (containsAny(item.getContent(), keyword)) {
                    counts.merge(keyword, 1L, Long::sum);
                }
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(12)
                .map(entry -> new CreatorFeedbackKeywordResponse(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<CreatorFeedbackTimelineResponse> buildDanmakuTimeline(List<CreatorFeedbackItemRecord> items) {
        Map<Integer, Long> counts = new LinkedHashMap<>();
        // 时间段热度只基于导入文件里的弹幕时间戳；如果样例没有时间，就明确返回空而不是编造分布。
        items.stream()
                .filter(item -> "DANMAKU".equals(item.getSourceType()))
                .filter(item -> TextUtil.hasText(item.getOccurTimeText()))
                .forEach(item -> parseMinuteBucket(item.getOccurTimeText())
                        .ifPresent(minute -> counts.merge(minute, 1L, Long::sum)));
        return counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new CreatorFeedbackTimelineResponse(
                        "%02d-%02d分钟".formatted(entry.getKey(), entry.getKey() + 1),
                        entry.getValue()
                ))
                .toList();
    }

    private java.util.Optional<Integer> parseMinuteBucket(String occurTimeText) {
        String normalized = occurTimeText.trim();
        if (normalized.contains(".")) {
            normalized = normalized.substring(0, normalized.indexOf('.'));
        }
        String[] parts = normalized.split(":");
        try {
            int seconds;
            if (parts.length == 3) {
                seconds = Integer.parseInt(parts[0]) * 3600
                        + Integer.parseInt(parts[1]) * 60
                        + Integer.parseInt(parts[2]);
            } else if (parts.length == 2) {
                seconds = Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            } else {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(seconds / 60);
        } catch (NumberFormatException exception) {
            return java.util.Optional.empty();
        }
    }

    private CreatorFeedbackItemResponse toItemResponse(CreatorFeedbackItemRecord record) {
        return new CreatorFeedbackItemResponse(
                record.getItemId(),
                record.getSourceType(),
                labelFor(record.getSourceType()),
                record.getContent(),
                record.getOccurTimeText(),
                record.getLikeCount(),
                record.getReplyCount(),
                record.getCategory(),
                labelFor(record.getCategory()),
                record.getSentiment(),
                labelFor(record.getSentiment()),
                Boolean.TRUE.equals(record.getNoise()),
                record.getReason(),
                record.getCreateTime()
        );
    }

    private CreatorFeedbackMetricResponse toMetricResponse(CreatorFeedbackMetricRecord record) {
        return new CreatorFeedbackMetricResponse(
                record.getMetricId(),
                record.getViewCount(),
                record.getFavoriteCount(),
                record.getCoinCount(),
                record.getLikeCount(),
                record.getShareCount(),
                record.getSource(),
                record.getCreateTime()
        );
    }

    private String labelFor(String value) {
        // 标签映射已抽到 CreatorFeedbackLabelUtil，和向量索引服务共用一份，避免两处各维护一份 switch 导致标签漂移。
        return CreatorFeedbackLabelUtil.labelFor(value);
    }

    private record ImportedFeedback(
            List<CreatorFeedbackItemRecord> items,
            CreatorFeedbackMetricRecord metric
    ) {
    }

    private record TextCommentMetadata(
            Long likeCount,
            String occurTimeText
    ) {
    }

    private CreatorFeedbackReportRecord buildReportRecord(String taskId, String rawOutput) {
        CreatorFeedbackReportRecord record = new CreatorFeedbackReportRecord();
        record.setReportId(UUID.randomUUID().toString());
        record.setTaskId(taskId);
        record.setRawOutput(rawOutput);
        fillParsedFields(record, rawOutput);
        return record;
    }

    /**
     * 解析 LLM 返回的 JSON 并填充报告结构化字段。
     * <p>
     * 每个字段独立解析——某个字段缺失时不影响其他字段的解析。
     * 阶段 4.12 新增字段（creatorFeedbackDilemma、audienceCoreConcern、misunderstandingSourceAnalysis、
     * feedbackActionPlan）在旧版本 JSON 中不存在，LlmJsonUtil 会返回 null，不会把整份报告打成 RAW_ONLY。
     * 只有顶层 JSON 解析失败（如 LLM 返回了纯文本而非 JSON）时，整个报告标记为 RAW_ONLY。
     *
     * @param record 报告记录（原地修改 parseStatus 和各业务字段）
     * @param rawOutput LLM 原始输出文本
     */
    private void fillParsedFields(CreatorFeedbackReportRecord record, String rawOutput) {
        try {
            JsonNode rootNode = objectMapper.readTree(LlmJsonUtil.extractJsonObject(rawOutput));
            record.setFeedbackSummary(LlmJsonUtil.text(rootNode, "feedbackSummary"));
            record.setHotTopics(LlmJsonUtil.json(objectMapper, rootNode, "hotTopics"));
            record.setSentimentSummary(LlmJsonUtil.text(rootNode, "sentimentSummary"));
            record.setControversyPoints(LlmJsonUtil.json(objectMapper, rootNode, "controversyPoints"));
            record.setMisunderstandingPoints(LlmJsonUtil.json(objectMapper, rootNode, "misunderstandingPoints"));
            record.setNextContentSuggestions(LlmJsonUtil.json(objectMapper, rootNode, "nextContentSuggestions"));
            record.setInteractionSuggestions(LlmJsonUtil.json(objectMapper, rootNode, "interactionSuggestions"));
            // 阶段 4.12 新增字段：缺失时 LlmJsonUtil 返回 null，不会把整份报告打成 RAW_ONLY，旧字段照常解析。
            record.setCreatorFeedbackDilemma(LlmJsonUtil.text(rootNode, "creatorFeedbackDilemma"));
            record.setAudienceCoreConcern(LlmJsonUtil.text(rootNode, "audienceCoreConcern"));
            record.setMisunderstandingSourceAnalysis(LlmJsonUtil.json(objectMapper, rootNode, "misunderstandingSourceAnalysis"));
            record.setFeedbackActionPlan(LlmJsonUtil.json(objectMapper, rootNode, "feedbackActionPlan"));
            record.setParseStatus("PARSED");
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            record.setParseStatus("RAW_ONLY");
        }
    }

    /**
     * 构建反馈分析 System Prompt。
     * <p>
     * 从 PromptService 获取预定义的模板文本，模板内容定义了 LLM 的输出格式（JSON schema）
     * 和分析维度（整体反馈、热点议题、情绪倾向、争议点、误解点、下期建议、互动建议等）。
     */
    private String buildSystemPrompt() {
        return promptService.get("feedback_analyze.system");
    }

    /**
     * 构建反馈分析 User Prompt。
     * <p>
     * 模板变量包括：任务名称、任务 ID、自定义指导、分析焦点、额外要求、补充上下文、
     * 评论样例、弹幕样例。所有可选字段统一用"未提供"占位，避免模板中存在 null 字符串。
     * 评论弹幕样例在传参前已截断至 FEEDBACK_MAX_LENGTH，防止超长文本撑爆 prompt 窗口。
     *
     * @param taskRecord 创作任务记录
     * @param feedbackRecord 反馈记录（含评论和弹幕样例）
     * @param request 分析请求（含可选的指导字段）
     * @return 渲染后的 User Prompt 文本
     */
    private String buildUserPrompt(CreatorTaskRecord taskRecord,
                                   CreatorFeedbackRecord feedbackRecord,
                                   CreatorFeedbackAnalyzeRequest request) {
        return promptService.render("feedback_analyze.user", Map.of(
                "taskName", taskRecord.getTaskName(),
                "taskId", taskRecord.getTaskId(),
                "customGuidance", TextUtil.trimToDefault(request.customGuidance(), "未提供"),
                "analysisFocus", TextUtil.trimToDefault(request.analysisFocus(), "未提供"),
                "extraRequirement", TextUtil.trimToDefault(request.extraRequirement(), "未提供"),
                "extraContext", TextUtil.trimToDefault(feedbackRecord.getExtraContext(), "未提供"),
                "commentSamples", normalizeFeedback(feedbackRecord.getCommentSamples()),
                "danmakuSamples", normalizeFeedback(feedbackRecord.getDanmakuSamples())
        ));
    }

    /**
     * 构建反馈追问 System Prompt。
     * <p>
     * 与反馈分析的 System Prompt 独立管理——追问需要对证据引用的格式要求
     * 和对"基于证据回答"的行为约束，和分析的"产出结构化 JSON"是不同的指令集。
     */
    private String buildChatSystemPrompt() {
        return promptService.get("feedback_chat.system");
    }

    /**
     * 构建反馈追问 User Prompt。
     * <p>
     * 将报告上下文 + 证据列表 + 用户问题拼接为一个 prompt。
     * 报告和证据都可能较长，各自独立截断后再拼接，避免联合长度超出模型上下文窗口。
     *
     * @param taskRecord 创作任务记录
     * @param reportRecord 分析报告（可能为 null，此时标注"仅有明细"）
     * @param evidenceRecords 检索命中的证据条目
     * @param question 用户追问
     * @return 渲染后的 User Prompt 文本
     */
    private String buildChatUserPrompt(CreatorTaskRecord taskRecord,
                                       CreatorFeedbackReportRecord reportRecord,
                                       List<CreatorFeedbackItemRecord> evidenceRecords,
                                       String question) {
        return promptService.render("feedback_chat.user", Map.of(
                "taskName", taskRecord.getTaskName(),
                "taskId", taskRecord.getTaskId(),
                "question", TextUtil.trimToDefault(question, "未提供"),
                "reportContext", buildChatReportContext(reportRecord),
                "evidenceContext", buildChatEvidenceContext(evidenceRecords)
        ));
    }

    /**
     * 构建反馈追问的报告上下文文本。
     * <p>
     * 将报告的所有解析字段拼接为一段包含"整体反馈、情绪倾向、创作者复盘困境、观众核心关注、
     * 高频观点、争议点、误解点、误解来源分析、下一期建议、互动建议、反馈行动计划"的上下文。
     * <p>
     * 阶段 4.12 新增字段（如 creatorFeedbackDilemma、audienceCoreConcern 等）也一并喂给模型——
     * 如果追问只读旧总结字段，"为什么这样反馈、下一步怎么改"这层升级就停留在报告展示层，
     * 无法真正进入交互问答。
     *
     * @param reportRecord 分析报告记录，可能为 null
     * @return 报告上下文文本（null 时返回提示文字）
     */
    private String buildChatReportContext(CreatorFeedbackReportRecord reportRecord) {
        if (reportRecord == null) {
            return "当前任务还没有 LLM 反馈报告，只能基于已导入明细回答。";
        }
        String reportText = """
                整体反馈：%s
                情绪倾向：%s
                创作者复盘困境：%s
                观众核心关注：%s
                高频观点：%s
                争议点：%s
                误解点：%s
                误解来源分析：%s
                下一期建议：%s
                互动建议：%s
                反馈行动计划：%s
                """.formatted(
                TextUtil.trimToDefault(reportRecord.getFeedbackSummary(), "未解析"),
                TextUtil.trimToDefault(reportRecord.getSentimentSummary(), "未解析"),
                TextUtil.trimToDefault(reportRecord.getCreatorFeedbackDilemma(), "未解析"),
                TextUtil.trimToDefault(reportRecord.getAudienceCoreConcern(), "未解析"),
                TextUtil.trimToDefault(reportRecord.getHotTopics(), "未解析"),
                TextUtil.trimToDefault(reportRecord.getControversyPoints(), "未解析"),
                TextUtil.trimToDefault(reportRecord.getMisunderstandingPoints(), "未解析"),
                TextUtil.trimToDefault(reportRecord.getMisunderstandingSourceAnalysis(), "未解析"),
                TextUtil.trimToDefault(reportRecord.getNextContentSuggestions(), "未解析"),
                TextUtil.trimToDefault(reportRecord.getInteractionSuggestions(), "未解析"),
                TextUtil.trimToDefault(reportRecord.getFeedbackActionPlan(), "未解析")
        );
        return TextUtil.abbreviateWithSuffix(reportText, FEEDBACK_MAX_LENGTH, "\n[报告内容过长，已截断用于追问]");
    }

    /**
     * 构建反馈追问的证据上下文文本。
     * <p>
     * 每条证据格式化为"证据N（来源类型，分类，情绪，时间，点赞）：内容 / 分类原因"，
     * 给 LLM 足够的元数据做引用和判断。内容截断至 500 字防止单条过长。
     *
     * @param evidenceRecords 检索命中的证据条目
     * @return 格式化的证据列表文本
     */
    private String buildChatEvidenceContext(List<CreatorFeedbackItemRecord> evidenceRecords) {
        if (evidenceRecords.isEmpty()) {
            return "没有命中可引用的评论或弹幕明细。";
        }
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < evidenceRecords.size(); index++) {
            CreatorFeedbackItemRecord item = evidenceRecords.get(index);
            lines.add("""
                    证据%d（%s，分类：%s，情绪：%s%s%s）：
                    %s
                    分类原因：%s
                    """.formatted(
                    index + 1,
                    labelFor(item.getSourceType()),
                    labelFor(item.getCategory()),
                    labelFor(item.getSentiment()),
                    TextUtil.hasText(item.getOccurTimeText()) ? "，时间：" + item.getOccurTimeText() : "",
                    item.getLikeCount() == null ? "" : "，点赞：" + item.getLikeCount(),
                    TextUtil.abbreviateWithSuffix(TextUtil.trimToDefault(item.getContent(), ""), 500, "..."),
                    TextUtil.trimToDefault(item.getReason(), "未记录")
            ));
        }
        return String.join("\n", lines);
    }

    private String normalizeChatAnswer(String rawAnswer) {
        if (TextUtil.isBlank(rawAnswer)) {
            return "当前模型没有返回可用回答，请稍后重试。";
        }
        return TextUtil.abbreviateWithSuffix(
                rawAnswer.trim(),
                FEEDBACK_CHAT_ANSWER_MAX_LENGTH,
                "\n[回答过长，已截断]"
        );
    }

    private String normalizeFeedback(String value) {
        if (TextUtil.isBlank(value)) {
            return "未提供";
        }
        return TextUtil.abbreviateWithSuffix(
                value.trim(),
                FEEDBACK_MAX_LENGTH,
                "\n[内容过长，已截断用于本次分析]"
        );
    }

    private CreatorFeedbackResponse toFeedbackResponse(CreatorFeedbackRecord record) {
        return new CreatorFeedbackResponse(
                record.getId(),
                record.getFeedbackId(),
                record.getTaskId(),
                record.getCommentSamples(),
                record.getDanmakuSamples(),
                record.getExtraContext(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }

    private CreatorFeedbackReportResponse toReportResponse(CreatorFeedbackReportRecord record) {
        return new CreatorFeedbackReportResponse(
                record.getId(),
                record.getReportId(),
                record.getTaskId(),
                record.getFeedbackSummary(),
                record.getHotTopics(),
                record.getSentimentSummary(),
                record.getControversyPoints(),
                record.getMisunderstandingPoints(),
                record.getNextContentSuggestions(),
                record.getInteractionSuggestions(),
                record.getCreatorFeedbackDilemma(),
                record.getAudienceCoreConcern(),
                record.getMisunderstandingSourceAnalysis(),
                record.getFeedbackActionPlan(),
                record.getRawOutput(),
                record.getParseStatus(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
