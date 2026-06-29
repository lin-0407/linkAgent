package com.link.linkagent.creator.interactive.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.interactive.mapper.CreatorInteractiveMapper;
import com.link.linkagent.creator.interactive.model.CreativeIdeaOptionRecord;
import com.link.linkagent.creator.interactive.model.CreativeIdeaOptionResponse;
import com.link.linkagent.creator.interactive.model.CreativeOptionsRegenerateRequest;
import com.link.linkagent.creator.interactive.model.InteractiveSessionRecord;
import com.link.linkagent.creator.interactive.model.InteractiveTaskCreateRequest;
import com.link.linkagent.creator.interactive.model.InteractiveTaskResponse;
import com.link.linkagent.creator.task.model.CreatorTaskCreateRequest;
import com.link.linkagent.creator.task.model.CreatorTaskUpdateRequest;
import com.link.linkagent.creator.task.model.CreatorTaskResponse;
import com.link.linkagent.creator.task.service.CreatorTaskService;
import com.link.linkagent.common.DocumentExtractionService;
import com.link.linkagent.common.DocumentExtractionService.ExtractedDocument;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.util.LlmJsonUtil;
import com.link.linkagent.util.TextUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AI 交互式创作服务。
 * 当前只落地第一阶段最小闭环：输入想法、生成三张创意卡片、确认其中一张。
 */
@Service
public class CreatorInteractiveService {

    private static final String DEFAULT_USER_ID = "default";
    private static final String DEFAULT_VIDEO_TYPE = "未分类";
    private static final int TASK_NAME_MAX_LENGTH = 48;
    private static final int OPTION_NAME_MAX_LENGTH = 128;
    private static final int TASK_MATERIAL_MAX_LENGTH = 20000;
    private static final int OPTION_COUNT = 3;
    /** 补充背景文档累计最大字符数，超出后不再追加，避免 Prompt 过长 */
    private static final int MAX_BACKGROUND_CONTEXT_CHARS = 100000;

    private final CreatorInteractiveMapper creatorInteractiveMapper;
    private final CreatorTaskService creatorTaskService;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;
    private final DocumentExtractionService documentExtractionService;

    public CreatorInteractiveService(CreatorInteractiveMapper creatorInteractiveMapper,
                                     CreatorTaskService creatorTaskService,
                                     LLMService llmService,
                                     ObjectMapper objectMapper,
                                     DocumentExtractionService documentExtractionService) {
        this.creatorInteractiveMapper = creatorInteractiveMapper;
        this.creatorTaskService = creatorTaskService;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.documentExtractionService = documentExtractionService;
    }

    /**
     * 创建交互式创作任务。
     * 只创建任务和会话记录，不再自动生成创意方向卡。
     * 改为由用户先（可选）上传补充背景文档 → AI 理解确认 → 再生成方向卡。
     */
    @Transactional
    public InteractiveTaskResponse createInteractiveTask(InteractiveTaskCreateRequest request) {
        String userId = normalizeUserId(request.userId());
        String idea = normalizeIdea(request.idea());
        String videoType = normalizeVideoType(request.videoType());

        CreatorTaskResponse task = creatorTaskService.createTask(new CreatorTaskCreateRequest(
                userId,
                buildTaskName(idea),
                videoType,
                null,
                null,
                buildIdeaMaterial(idea),
                null
        ));

        InteractiveSessionRecord session = new InteractiveSessionRecord();
        session.setSessionId(UUID.randomUUID().toString());
        session.setTaskId(task.taskId());
        session.setUserId(userId);
        session.setIdea(idea);
        session.setVideoType(videoType);
        session.setStatus("IDEA_INPUT");
        session.setParseStatus("PENDING");
        session.setUnderstandingStatus("NONE");
        creatorInteractiveMapper.insertSession(session);

        return getInteractiveTask(task.taskId());
    }

    /**
     * 上传补充背景文档。
     * 接收一个或多个文件，通过 Tika 提取纯文本后追加到会话的 background_context 字段。
     * 返回每个文件提取后的文本长度和文件名，方便前端展示。
     */
    @Transactional
    public InteractiveTaskResponse appendContextDocuments(String taskId, List<MultipartFile> files) {
        InteractiveSessionRecord session = getSessionRecord(taskId);
        if (files == null || files.isEmpty()) {
            return getInteractiveTask(session.getTaskId());
        }

        // 先读取当前已有的背景上下文长度，避免重复递增超出上限
        String currentContext = session.getBackgroundContext();
        int currentLength = currentContext == null ? 0 : currentContext.length();

        // 用于累积本次请求中新提取的文本，最终通过 mapper 追加到 DB 已有内容之后
        StringBuilder appended = new StringBuilder();
        int extractedCount = 0;
        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }
            ExtractedDocument doc = documentExtractionService.extract(file);
            // 累计长度控制：避免背景资料过长占用 LLM 上下文窗口
            if (currentLength + appended.length() + doc.text().length() > MAX_BACKGROUND_CONTEXT_CHARS) {
                appended.append("\n\n[后续文件 \"")
                        .append(doc.fileName())
                        .append("\" 因背景资料总长度已达上限 ")
                        .append(MAX_BACKGROUND_CONTEXT_CHARS)
                        .append(" 字符，已跳过]\n");
                break;
            }
            // 每个文件前加分隔标记，方便 LLM 区分不同文档来源
            if (!appended.isEmpty()) {
                appended.append("\n\n---\n\n");
            }
            appended.append("【文档：").append(doc.fileName()).append("】\n");
            appended.append(doc.text());
            extractedCount++;
        }

        if (extractedCount == 0) {
            return getInteractiveTask(session.getTaskId());
        }

        creatorInteractiveMapper.appendBackgroundContext(session.getTaskId(), appended.toString());
        return getInteractiveTask(session.getTaskId());
    }

    /**
     * AI 理解确认。
     * 调用 LLM 理解用户想法 + 补充背景资料，输出结构化理解摘要，
     * 让用户在生成方向卡前核验 AI 是否准确理解了创作意图。
     * 理解确认不可跳过——必须 AI 理解完成后才能进入方向卡生成。
     */
    @Transactional
    public InteractiveTaskResponse triggerUnderstanding(String taskId) {
        InteractiveSessionRecord session = getSessionRecord(taskId);

        // 更新状态为生成中，防止重复提交
        creatorInteractiveMapper.updateUnderstanding(session.getTaskId(), null, "UNDERSTANDING");

        String summary;
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.open(session.getTaskId(), "AI理解确认")) {
            summary = llmService.chat(
                    buildUnderstandingSystemPrompt(),
                    buildUnderstandingUserPrompt(session)
            );
        } catch (RuntimeException exception) {
            // AI 理解失败时回退状态，让用户可以重试
            creatorInteractiveMapper.updateUnderstanding(session.getTaskId(), null, "NONE");
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "AI 理解确认失败: " + exception.getMessage(), exception);
        }

        creatorInteractiveMapper.updateUnderstanding(session.getTaskId(), summary, "READY");
        return getInteractiveTask(session.getTaskId());
    }

    /**
     * 生成创意方向卡。
     * 在 AI 理解确认完成后调用，基于原始想法 + 补充背景 + AI 理解摘要生成 3 张方向卡。
     * 与 regenerateOptions 的区别：本方法首次生成，需要理解状态为 READY 或 CONFIRMED。
     */
    @Transactional
    public InteractiveTaskResponse generateCreativeOptions(String taskId, String extraRequirement) {
        InteractiveSessionRecord session = getSessionRecord(taskId);
        String understandingStatus = session.getUnderstandingStatus() == null ? "NONE" : session.getUnderstandingStatus();

        // 理解确认是必要步骤，防止跳过理解直接生成导致偏差
        if (!"READY".equals(understandingStatus) && !"CONFIRMED".equals(understandingStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "请先完成 AI 理解确认。当前状态：" + understandingStatus);
        }

        // 用户确认了理解 → 标记为 CONFIRMED
        if ("READY".equals(understandingStatus)) {
            creatorInteractiveMapper.updateUnderstanding(session.getTaskId(),
                    session.getUnderstandingSummary(), "CONFIRMED");
        }

        generateAndStoreOptions(session, extraRequirement);
        return getInteractiveTask(session.getTaskId());
    }

    @Transactional
    public InteractiveTaskResponse regenerateOptions(String taskId, CreativeOptionsRegenerateRequest request) {
        InteractiveSessionRecord session = getSessionRecord(taskId);
        String extraRequirement = request == null ? null : TextUtil.trimToNull(request.extraRequirement());
        creatorInteractiveMapper.clearSelectedOptions(session.getSessionId());
        creatorInteractiveMapper.updateSessionSelection(session.getSessionId(), "CREATIVE_GENERATING", null);
        generateAndStoreOptions(session, extraRequirement);
        return getInteractiveTask(session.getTaskId());
    }

    @Transactional
    public InteractiveTaskResponse confirmOption(String taskId, String optionId) {
        String safeTaskId = normalizeTaskId(taskId);
        String safeOptionId = normalizeOptionId(optionId);
        InteractiveSessionRecord session = getSessionRecord(safeTaskId);
        CreativeIdeaOptionRecord selectedOption = creatorInteractiveMapper
                .findOptionByTaskIdAndOptionId(session.getTaskId(), safeOptionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创意卡片不存在"));

        creatorInteractiveMapper.clearSelectedOptions(session.getSessionId());
        int selected = creatorInteractiveMapper.selectOption(session.getSessionId(), selectedOption.getOptionId());
        if (selected == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "创意卡片不存在");
        }
        creatorInteractiveMapper.updateSessionSelection(
                session.getSessionId(),
                "CREATIVE_CONFIRMED",
                selectedOption.getOptionId()
        );

        updateTaskMaterialsBySelectedOption(session, selectedOption);
        return getInteractiveTask(session.getTaskId());
    }

    public InteractiveTaskResponse getInteractiveTask(String taskId) {
        InteractiveSessionRecord session = getSessionRecord(taskId);
        List<CreativeIdeaOptionResponse> options = creatorInteractiveMapper
                .listOptionsBySessionId(session.getSessionId())
                .stream()
                .map(this::toOptionResponse)
                .toList();
        return toTaskResponse(session, options);
    }

    private void generateAndStoreOptions(InteractiveSessionRecord session, String extraRequirement) {
        String rawOutput = "";
        List<CreativeIdeaOptionRecord> options;
        String parseStatus = "PARSED";
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.open(session.getTaskId(), "AI创意方案")) {
            rawOutput = llmService.chat(buildCreativeSystemPrompt(), buildCreativeUserPrompt(session, extraRequirement));
            options = parseOptions(session, rawOutput);
        } catch (RuntimeException exception) {
            parseStatus = "RAW_ONLY";
            rawOutput = TextUtil.hasText(rawOutput) ? rawOutput : "AI 创意生成失败：" + exception.getMessage();
            options = buildFallbackOptions(session);
        }

        creatorInteractiveMapper.deleteOptionsBySessionId(session.getSessionId());
        for (CreativeIdeaOptionRecord option : options) {
            creatorInteractiveMapper.insertOption(option);
        }
        creatorInteractiveMapper.updateSessionGenerationResult(
                session.getSessionId(),
                "CREATIVE_OPTIONS_READY",
                rawOutput,
                parseStatus
        );
    }

    private List<CreativeIdeaOptionRecord> parseOptions(InteractiveSessionRecord session, String rawOutput) {
        try {
            JsonNode rootNode = objectMapper.readTree(LlmJsonUtil.extractJsonObject(rawOutput));
            JsonNode optionsNode = rootNode.get("options");
            if (optionsNode == null || !optionsNode.isArray() || optionsNode.size() == 0) {
                throw new IllegalArgumentException("LLM 输出缺少 options 数组");
            }
            List<CreativeIdeaOptionRecord> records = new ArrayList<>();
            for (int index = 0; index < Math.min(OPTION_COUNT, optionsNode.size()); index++) {
                records.add(toOptionRecord(session, optionsNode.get(index), index));
            }
            while (records.size() < OPTION_COUNT) {
                records.add(buildFallbackOption(session, records.size()));
            }
            return records;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "AI 创意卡片解析失败", exception);
        }
    }

    private CreativeIdeaOptionRecord toOptionRecord(InteractiveSessionRecord session, JsonNode optionNode, int index) {
        CreativeIdeaOptionRecord record = new CreativeIdeaOptionRecord();
        record.setOptionId(UUID.randomUUID().toString());
        record.setSessionId(session.getSessionId());
        record.setTaskId(session.getTaskId());
        record.setOptionName(TextUtil.abbreviate(
                defaultText(LlmJsonUtil.text(optionNode, "optionName"), "创意方向 " + (index + 1)),
                OPTION_NAME_MAX_LENGTH
        ));
        record.setTargetAudience(defaultText(LlmJsonUtil.text(optionNode, "targetAudience"), "对这个主题感兴趣的 B 站观众"));
        record.setTitleOutline(jsonField(optionNode, "titleOutline", "标题突出主题、收益和观看门槛"));
        record.setContentOutline(jsonField(optionNode, "contentOutline", "开头抛出问题，主体分段说明，结尾给出行动建议"));
        record.setDescriptionOutline(jsonField(optionNode, "descriptionOutline", "简介说明视频价值、关键词和互动引导"));
        record.setSellingPoints(jsonField(optionNode, "sellingPoints", "贴合用户原始想法，便于快速进入制作"));
        record.setRiskPoints(jsonField(optionNode, "riskPoints", "需要避免标题过度承诺或内容泛泛而谈"));
        record.setRecommendReason(defaultText(LlmJsonUtil.text(optionNode, "recommendReason"), "这个方向最容易从当前想法落地成视频。"));
        record.setSelected(false);
        return record;
    }

    private String jsonField(JsonNode optionNode, String fieldName, String fallbackText) {
        try {
            String json = LlmJsonUtil.json(objectMapper, optionNode, fieldName);
            if (TextUtil.hasText(json)) {
                return json;
            }
            return objectMapper.writeValueAsString(List.of(fallbackText));
        } catch (JsonProcessingException exception) {
            return "[\"" + fallbackText + "\"]";
        }
    }

    private List<CreativeIdeaOptionRecord> buildFallbackOptions(InteractiveSessionRecord session) {
        List<CreativeIdeaOptionRecord> options = new ArrayList<>();
        for (int index = 0; index < OPTION_COUNT; index++) {
            options.add(buildFallbackOption(session, index));
        }
        return options;
    }

    private CreativeIdeaOptionRecord buildFallbackOption(InteractiveSessionRecord session, int index) {
        String[] names = {"问题切入型", "教程拆解型", "观点复盘型"};
        String[] audiences = {
                "已经对主题有兴趣，但还没有形成清晰理解的观众",
                "想快速学会方法、希望步骤明确的新手观众",
                "关心经验总结、想判断自己能否复用的创作者或学习者"
        };
        CreativeIdeaOptionRecord record = new CreativeIdeaOptionRecord();
        record.setOptionId(UUID.randomUUID().toString());
        record.setSessionId(session.getSessionId());
        record.setTaskId(session.getTaskId());
        record.setOptionName(names[index % names.length]);
        record.setTargetAudience(audiences[index % audiences.length]);
        record.setTitleOutline(toJsonList(
                "用一个具体问题引出主题：" + TextUtil.abbreviate(session.getIdea(), 50),
                "标题里给出明确收益，避免只写概念名"
        ));
        record.setContentOutline(toJsonList(
                "开头说明观众为什么现在需要看",
                "主体按背景、关键步骤、常见误区展开",
                "结尾给出可执行清单或下一步建议"
        ));
        record.setDescriptionOutline(toJsonList(
                "第一句概括本期能解决的问题",
                "补充适合人群和关键词",
                "引导观众评论自己的使用场景"
        ));
        record.setSellingPoints(toJsonList("不需要完整脚本也能先推进选题", "能直接进入发布前优化阶段"));
        record.setRiskPoints(toJsonList("需要后续补充真实素材", "标题不能承诺视频里没有覆盖的结果"));
        record.setRecommendReason("这是 AI 输出异常时的兜底方向，先保证创作流程不中断，后续可重新生成。");
        record.setSelected(false);
        return record;
    }

    private void updateTaskMaterialsBySelectedOption(InteractiveSessionRecord session,
                                                     CreativeIdeaOptionRecord selectedOption) {
        CreatorTaskResponse currentTask = creatorTaskService.getTask(session.getTaskId());
        creatorTaskService.updateTask(session.getTaskId(), new CreatorTaskUpdateRequest(
                currentTask.taskName(),
                session.getVideoType(),
                buildTitleDraft(selectedOption),
                buildDescriptionDraft(selectedOption),
                buildConfirmedIdeaMaterial(session, selectedOption),
                findMaterial(currentTask, "SUBTITLE")
        ));
    }

    private String buildTitleDraft(CreativeIdeaOptionRecord option) {
        return TextUtil.abbreviate(TextUtil.collapseWhitespace(option.getOptionName()), 200);
    }

    private String buildDescriptionDraft(CreativeIdeaOptionRecord option) {
        String content = """
                创意名称：%s
                适合人群：%s
                简介大纲：%s
                """.formatted(
                option.getOptionName(),
                TextUtil.trimToDefault(option.getTargetAudience(), "未提供"),
                TextUtil.trimToDefault(option.getDescriptionOutline(), "未提供")
        );
        return TextUtil.abbreviateWithSuffix(content.trim(), 2000, "\n[内容过长，已截断]");
    }

    private String buildConfirmedIdeaMaterial(InteractiveSessionRecord session, CreativeIdeaOptionRecord option) {
        String content = """
                【用户原始想法】
                %s

                【已确认创意方向】
                %s

                【适合人群】
                %s

                【标题大纲】
                %s

                【内容大纲】
                %s

                【简介大纲】
                %s

                【亮点】
                %s

                【风险】
                %s

                【AI 建议】
                %s
                """.formatted(
                session.getIdea(),
                option.getOptionName(),
                TextUtil.trimToDefault(option.getTargetAudience(), "未提供"),
                TextUtil.trimToDefault(option.getTitleOutline(), "未提供"),
                TextUtil.trimToDefault(option.getContentOutline(), "未提供"),
                TextUtil.trimToDefault(option.getDescriptionOutline(), "未提供"),
                TextUtil.trimToDefault(option.getSellingPoints(), "未提供"),
                TextUtil.trimToDefault(option.getRiskPoints(), "未提供"),
                TextUtil.trimToDefault(option.getRecommendReason(), "未提供")
        );
        return TextUtil.abbreviateWithSuffix(content.trim(), TASK_MATERIAL_MAX_LENGTH, "\n[内容过长，已截断]");
    }

    private String findMaterial(CreatorTaskResponse task, String materialType) {
        return task.materials()
                .stream()
                .filter(material -> materialType.equals(material.materialType()))
                .findFirst()
                .map(material -> TextUtil.trimToNull(material.content()))
                .orElse(null);
    }

    // ──────────────────────────── AI 理解确认 Prompt ────────────────────────────

    private String buildUnderstandingSystemPrompt() {
        return """
                你是 B 站内容创作意图的理解分析助手。
                你的任务是把用户的自然语言想法和补充背景资料结合起来，
                用清晰的中文总结你对创作意图的理解。
                如果背景资料充分，你要基于事实给出理解；如果背景资料不足，你要诚实地指出不确定的地方。
                你必须只输出纯文本，不要使用 Markdown 格式，不要输出 JSON。
                """;
    }

    private String buildUnderstandingUserPrompt(InteractiveSessionRecord session) {
        String backgroundContext = TextUtil.trimToNull(session.getBackgroundContext());

        return """
                请基于下面的信息，总结你对这位创作者的创作意图的理解。

                用户原始想法：
                %s

                视频类型：
                %s

                补充背景资料：
                %s

                请从以下维度分析并输出你的理解：
                1. **视频主题**：用户想做什么内容？核心要传达什么？
                2. **目标观众**：这期视频是拍给谁看的？他们的认知水平和兴趣点是什么？
                3. **核心要点**：视频必须覆盖哪些关键信息？有哪些不可遗漏的事实？
                4. **需要避免的问题**：有哪些容易跑偏、过度承诺、或引发观众误解的地方？
                5. **不确定的地方**：基于现有信息，你还有哪些不确定、需要用户补充的地方？（如果没有不确定，说"信息充分，无需补充"）

                注意：
                - 如果补充背景资料中有具体事实（如技术栈、项目名称、数据），务必在理解中准确引用。
                - 不要在理解中提出创作建议或方向——你的任务只是确认你理解了创作者的意图。
                """.formatted(
                session.getIdea(),
                TextUtil.trimToDefault(session.getVideoType(), DEFAULT_VIDEO_TYPE),
                backgroundContext != null ? backgroundContext : "（未提供补充背景资料）"
        );
    }

    // ──────────────────────────── 创意方向卡生成 Prompt ────────────────────────────

    private String buildCreativeSystemPrompt() {
        return """
                你是 B 站内容创作者的选题策划助手。
                你的任务是把用户的一段自然语言创作想法，拆成 3 个可选创意方向。
                你必须只输出 JSON 对象，不要输出 Markdown，不要解释 JSON 之外的内容。
                每个数组字段必须是字符串数组，每张卡片都要具体到当前用户想法，不要写通用套话。
                """;
    }

    private String buildCreativeUserPrompt(InteractiveSessionRecord session, String extraRequirement) {
        String backgroundContext = TextUtil.trimToNull(session.getBackgroundContext());
        String understandingSummary = TextUtil.trimToNull(session.getUnderstandingSummary());

        return """
                请基于下面信息生成 3 张 B 站视频创意卡片。

                用户原始想法：
                %s

                视频类型：
                %s

                本轮额外要求：
                %s

                补充背景资料（用户上传的文档内容，必须据此生成，不要凭空编造与资料矛盾的信息）：
                %s

                AI 对创作想法的理解（已获用户确认，请按此理解生成方向卡）：
                %s

                输出 JSON 字段固定如下：
                {
                  "options": [
                    {
                      "optionName": "创意名称，一句话概括这个方向",
                      "targetAudience": "适合人群",
                      "titleOutline": ["标题表达方向1", "标题骨架2"],
                      "contentOutline": ["开头怎么切入", "主体怎么展开", "结尾怎么收束"],
                      "descriptionOutline": ["简介第一句", "关键词", "互动引导"],
                      "sellingPoints": ["亮点1", "亮点2"],
                      "riskPoints": ["风险1", "风险2"],
                      "recommendReason": "AI 推荐或不推荐这个方向的具体理由"
                    }
                  ]
                }

                约束：
                1. options 必须恰好 3 个。
                2. 不要给最终标题，只给标题大纲和可选骨架。
                3. 每张卡片的创意角度必须明显不同。
                4. 风险必须具体指出可能跑偏、过度承诺或观众误解的地方。
                5. 所有内容使用中文。
                6. 如果背景资料中包含具体事实（如项目名称、技术栈、数据），方向卡中必须准确引用，不得编造。
                """.formatted(
                session.getIdea(),
                TextUtil.trimToDefault(session.getVideoType(), DEFAULT_VIDEO_TYPE),
                TextUtil.trimToDefault(extraRequirement, "无"),
                backgroundContext != null ? backgroundContext : "（未提供补充背景资料）",
                understandingSummary != null ? understandingSummary : "（AI 理解尚未完成）"
        );
    }

    private String buildIdeaMaterial(String idea) {
        return "【用户原始创作想法】\n" + TextUtil.abbreviateWithSuffix(idea, TASK_MATERIAL_MAX_LENGTH, "\n[内容过长，已截断]");
    }

    private String buildTaskName(String idea) {
        return TextUtil.abbreviate(TextUtil.collapseWhitespace(idea), TASK_NAME_MAX_LENGTH);
    }

    private InteractiveSessionRecord getSessionRecord(String taskId) {
        return creatorInteractiveMapper.findSessionByTaskId(normalizeTaskId(taskId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "交互式创作任务不存在"));
    }

    private InteractiveTaskResponse toTaskResponse(InteractiveSessionRecord session,
                                                   List<CreativeIdeaOptionResponse> options) {
        return new InteractiveTaskResponse(
                session.getTaskId(),
                session.getSessionId(),
                session.getUserId(),
                session.getIdea(),
                session.getVideoType(),
                session.getStatus(),
                session.getSelectedOptionId(),
                session.getParseStatus(),
                session.getBackgroundContext(),
                session.getUnderstandingSummary(),
                session.getUnderstandingStatus(),
                session.getCreateTime(),
                session.getUpdateTime(),
                options
        );
    }

    private CreativeIdeaOptionResponse toOptionResponse(CreativeIdeaOptionRecord record) {
        return new CreativeIdeaOptionResponse(
                record.getId(),
                record.getOptionId(),
                record.getSessionId(),
                record.getTaskId(),
                record.getOptionName(),
                record.getTargetAudience(),
                record.getTitleOutline(),
                record.getContentOutline(),
                record.getDescriptionOutline(),
                record.getSellingPoints(),
                record.getRiskPoints(),
                record.getRecommendReason(),
                Boolean.TRUE.equals(record.getSelected()),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }

    private String normalizeUserId(String userId) {
        return TextUtil.trimToDefault(userId, DEFAULT_USER_ID);
    }

    private String normalizeIdea(String idea) {
        String normalized = TextUtil.trimToNull(idea);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "创作想法不能为空");
        }
        return normalized;
    }

    private String normalizeVideoType(String videoType) {
        return TextUtil.trimToDefault(videoType, DEFAULT_VIDEO_TYPE);
    }

    private String normalizeTaskId(String taskId) {
        String normalized = TextUtil.trimToNull(taskId);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务ID不能为空");
        }
        return normalized;
    }

    private String normalizeOptionId(String optionId) {
        String normalized = TextUtil.trimToNull(optionId);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "创意卡片ID不能为空");
        }
        return normalized;
    }

    private String defaultText(String value, String defaultValue) {
        return TextUtil.trimToDefault(value, defaultValue);
    }

    private String toJsonList(String... values) {
        try {
            return objectMapper.writeValueAsString(List.of(values));
        } catch (JsonProcessingException exception) {
            return "[]";
        }
    }
}
