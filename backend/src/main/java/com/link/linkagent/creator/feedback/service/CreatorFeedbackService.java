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
 * 评论弹幕反馈服务。
 * 本阶段同时支持粘贴、文件导入和用户填写 BV 后的本地脚本采集，统一落到任务维度做复盘。
 */
@Service
public class CreatorFeedbackService {

    private static final int FEEDBACK_MAX_LENGTH = 12000;
    private static final int IMPORT_FILE_MAX_SIZE = 5 * 1024 * 1024;
    private static final int GENERATED_FILE_MAX_SIZE = 20 * 1024 * 1024;
    private static final int IMPORT_ITEM_MAX_COUNT = 2000;
    private static final int LEGACY_SAMPLE_MAX_LENGTH = 20000;
    private static final int DASHBOARD_ITEM_LIMIT = 2000;
    private static final int DASHBOARD_RECENT_LIMIT = 12;
    private static final int DASHBOARD_TOP_COMMENT_LIMIT = 8;
    private static final int FEEDBACK_CHAT_ANSWER_MAX_LENGTH = 4000;
    private static final long SCRIPT_TIMEOUT_SECONDS = 180;
    private static final Pattern BVID_PATTERN = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final Path FEEDBACK_SCRIPT_PATH = Path.of("scripts", "bilibili_feedback_fetcher.py");
    private static final Path FEEDBACK_EXPORT_PATH = Path.of("export", "bilibili_feedback");
    private static final List<String> KEYWORD_DICTIONARY = List.of(
            "AI", "Agent", "Spring", "Spring AI", "LLM", "Java", "后端", "项目", "工具调用",
            "标题", "简介", "标签", "字幕", "文稿", "教程", "代码", "流程", "复盘",
            "评论", "弹幕", "节奏", "清楚", "看不懂", "干货", "实用", "下次", "资料"
    );

    private final CreatorTaskMapper creatorTaskMapper;
    private final CreatorFeedbackMapper creatorFeedbackMapper;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    // 证据检索拆成独立服务：本类已同时承担导入、分析、仪表盘、追问和脚本采集，把“选证据”交出去能让追问链路只负责编排。
    private final CreatorFeedbackEvidenceRetrievalService evidenceRetrievalService;
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

    public CreatorFeedbackResponse getFeedback(String taskId) {
        getTaskRecord(taskId);
        CreatorFeedbackRecord record = creatorFeedbackMapper.findFeedbackByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "评论弹幕样例不存在"));
        return toFeedbackResponse(record);
    }

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

    public CreatorFeedbackReportResponse getReport(String taskId) {
        getTaskRecord(taskId);
        CreatorFeedbackReportRecord record = creatorFeedbackMapper.findReportByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "评论弹幕分析报告不存在"));
        return toReportResponse(record);
    }

    @Transactional
    public CreatorFeedbackImportResponse importFeedback(String taskId, MultipartFile file) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        validateImportFile(file);
        String fileName = normalizeFileName(file.getOriginalFilename());
        String text = readUploadText(file);

        return importFeedbackText(taskRecord, fileName, text, "从用户上传文件 " + fileName + " 导入", List.of());
    }

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
        // 旧 LLM 分析接口仍读取整段样例；这里回填旧表，是为了让“导入后直接分析反馈”这条链路不断。
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

    private void applyTaskIdAndClassification(String taskId, List<CreatorFeedbackItemRecord> items) {
        Map<String, Integer> seenContent = new LinkedHashMap<>();
        for (CreatorFeedbackItemRecord item : items) {
            item.setTaskId(taskId);
            classifyItem(item, seenContent);
        }
    }

    private void classifyItem(CreatorFeedbackItemRecord item, Map<String, Integer> seenContent) {
        String normalized = normalizeForDuplicate(item.getContent());
        int seenCount = seenContent.getOrDefault(normalized, 0);
        seenContent.put(normalized, seenCount + 1);
        // 先过滤空语义和重复内容，是为了让后续情绪/分类统计不要被“哈哈哈”和刷屏样例冲高。
        if (normalized.isBlank() || isEmptyMeaning(item.getContent())) {
            item.setNoise(true);
            item.setCategory("EMPTY_MEANING");
            item.setSentiment("NEUTRAL");
            item.setReason("内容过短或语义不足，先标记为无意义内容。");
            return;
        }
        if (seenCount > 0) {
            item.setNoise(true);
            item.setCategory("DUPLICATE");
            item.setSentiment("NEUTRAL");
            item.setReason("和前面导入的内容重复，仪表盘会单独统计。");
            return;
        }

        // 规则分类是可替换的第一版实现，后续接 LLM 分类时只需要改这里，不需要重做表结构和前端。
        item.setNoise(false);
        if ("DANMAKU".equals(item.getSourceType())) {
            item.setCategory(classifyDanmaku(item.getContent()));
        } else {
            item.setCategory(classifyComment(item.getContent()));
        }
        item.setSentiment(classifySentiment(item.getContent(), item.getCategory()));
        item.setReason("当前版本使用轻量规则分类，后续可替换为 LLM 分类或人工复核。");
    }

    private String normalizeForDuplicate(String content) {
        if (content == null) {
            return "";
        }
        return content.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private boolean isEmptyMeaning(String content) {
        String normalized = content.replaceAll("[\\p{P}\\s]+", "");
        if (normalized.length() < 2) {
            return true;
        }
        String lowerValue = normalized.toLowerCase(Locale.ROOT);
        return lowerValue.matches("(ha|haha|hhh|233|666|www)+") || normalized.matches("[哈啊]+");
    }

    private String classifyComment(String content) {
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

    private String classifyDanmaku(String content) {
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

    private String classifySentiment(String content, String category) {
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

    private String buildSystemPrompt() {
        return promptService.get("feedback_analyze.system");
    }

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

    private String buildChatSystemPrompt() {
        return promptService.get("feedback_chat.system");
    }

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

    private String buildChatReportContext(CreatorFeedbackReportRecord reportRecord) {
        if (reportRecord == null) {
            return "当前任务还没有 LLM 反馈报告，只能基于已导入明细回答。";
        }
        // 接入阶段 4.12 新增的高信号字段：如果追问继续只读旧总结字段，会让“为什么这样反馈、下一步怎么改”
        // 这层升级停留在报告展示层，无法真正进入交互问答，所以把它们一并喂给追问模型。
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
