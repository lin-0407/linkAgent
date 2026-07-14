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
 * AI 交互式创作服务 —— Creator 模块的核心编排层，驱动"创作意图理解 → 方向卡生成 → 用户确认"全流程。
 * <p>
 * <b>在架构中的位置</b>：位于 Creator 模块的服务层，上游对接 Controller（接收用户 HTTP 请求），
 * 下游协调 {@link CreatorTaskService}（任务管理）、{@link LLMService}（AI 推理）、
 * {@link DocumentExtractionService}（文档文本提取）、{@link CreatorInteractiveMapper}（持久化）。
 * 服务本身不直接操作数据库，所有 SQL 通过 Mapper 接口完成。
 * <p>
 * <b>核心设计决策</b>
 * <ol>
 *   <li><b>两段式交互</b>：先让 AI 理解用户想法并产出理解摘要（用户可核验），再基于理解结果生成方向卡。
 *       这样做是为了避免一次生成导致 AI 误解用户意图——理解确认环节是"校准步骤"，不是装饰。</li>
 *   <li><b>方向卡数量固定为 3</b>：用户可在 3 张卡片中选 1 张确认。3 是经过权衡的值——太少选择不足，
 *       太多决策疲劳。每张卡片必须从明显不同的创意角度切入。</li>
 *   <li><b>异常兜底策略</b>：当 LLM 输出解析失败时，不抛异常中断流程，而是自动生成 3 张内置兜底方向卡。
 *       兜底卡提供通用模板框架，保证创作流程不中断，用户可以后续重新生成。</li>
 *   <li><b>背景文档长度控制</b>：用户上传的补充文档文本累计不超过 {@link #MAX_BACKGROUND_CONTEXT_CHARS} 字符，
 *       防止 Prompt 过长导致 LLM 上下文窗口溢出或推理质量下降。</li>
 *   <li><b>状态机驱动</b>：session 的 status / understandingStatus / parseStatus 三个字段组成联合状态机，
 *       确保操作顺序正确（必须理解完成后才能生成方向卡），防止跳步导致数据不一致。</li>
 * </ol>
 * <p>
 * <b>主要流程</b>
 * <pre>
 * createInteractiveTask (创建任务+会话)
 *   → appendContextDocuments (可选上传背景文档)
 *     → triggerUnderstanding (AI理解确认，必须)
 *       → generateCreativeOptions (生成3张方向卡)
 *         → confirmOption (确认其中一张)
 *           → 方向卡内容回填到 CreatorTask 的素材字段
 * </pre>
 */
@Service
public class CreatorInteractiveService {

    /** 未登录/匿名用户的默认标识，与 AgentExecutor 的 default 策略保持一致 */
    private static final String DEFAULT_USER_ID = "default";
    /** 用户未选择视频类型时的默认分类标签 */
    private static final String DEFAULT_VIDEO_TYPE = "未分类";
    /** 任务名称最大长度（字符数），取自用户想法的缩略版本，用于在任务列表中快速辨识 */
    private static final int TASK_NAME_MAX_LENGTH = 48;
    /** 创意卡片名称最大长度（字符数），防止 LLM 输出过长名称破坏列表展示 */
    private static final int OPTION_NAME_MAX_LENGTH = 128;
    /** 任务素材字段最大字符数，超长内容截断并附加提示，防止数据库字段溢出 */
    private static final int TASK_MATERIAL_MAX_LENGTH = 20000;
    /** 每轮生成的创意方向卡数量，固定为 3 张，提供适度选择而不造成决策疲劳 */
    private static final int OPTION_COUNT = 3;
    /** 补充背景文档累计最大字符数。超出后不再追加新文档，避免 Prompt 过长导致 LLM 上下文溢出 */
    private static final int MAX_BACKGROUND_CONTEXT_CHARS = 100000;

    /** 交互式创作会话的数据访问层，负责 session / option 的 CRUD */
    private final CreatorInteractiveMapper creatorInteractiveMapper;
    /** CreatorTask 任务服务，交互式创作流程中新建任务时会创建关联的 CreatorTask */
    private final CreatorTaskService creatorTaskService;
    /** LLM 调用入口，负责发起 AI 理解确认和创意方向卡生成的推理请求 */
    private final LLMService llmService;
    /** JSON 序列化/反序列化工具，用于解析 LLM 输出的 JSON 方向卡、序列化兜底卡 */
    private final ObjectMapper objectMapper;
    /** 文档内容提取服务，通过 Apache Tika 从用户上传文档中提取纯文本 */
    private final DocumentExtractionService documentExtractionService;

    /**
     * 构造器注入全部依赖。所有字段在构造完成后即不可变，保证线程安全。
     */
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
     * <p>
     * 此方法只创建 CreatorTask 任务记录和 InteractiveSessionRecord 会话记录，<b>不立即生成方向卡</b>。
     * 两段式流程设计：先让用户（可选）上传补充背景文档并完成 AI 理解确认，确保 AI 准确理解创作意图后，
     * 再由 {@link #generateCreativeOptions} 生成方向卡。这样避免了"AI 一次生成就直接跑偏"的问题。
     * <p>
     * 会话初始状态：status=IDEA_INPUT, understandingStatus=NONE（尚未理解），
     * parseStatus=PENDING（尚未触发生成）。
     * <p>
     * 事务边界：创建 CreatorTask + 插入 Session 在同一事务中，任一失败都回滚。
     *
     * @param request 包含 userId、创作想法(idea)、视频类型(videoType)的请求对象；
     *                idea 为必填（空时抛出 400），userId 为空时默认 "default"
     * @return 完整的交互任务响应，包含刚创建的 taskId 和 sessionId
     */
    @Transactional
    public InteractiveTaskResponse createInteractiveTask(InteractiveTaskCreateRequest request) {
        String userId = normalizeUserId(request.userId());
        String idea = normalizeIdea(request.idea());
        String videoType = normalizeVideoType(request.videoType());

        // CreatorTask 在创建交互任务时使用 buildIdeaMaterial 填充初始 IDEA 素材，
        // 这里用 null 让 createTask 使用默认值（标题和描述留空，由后续阶段补充）
        CreatorTaskResponse task = creatorTaskService.createTask(new CreatorTaskCreateRequest(
                userId,
                buildTaskName(idea),
                videoType,
                null, // 标题草稿：初始为空，后续确认方向卡后回填
                null, // 简介草稿：初始为空
                buildIdeaMaterial(idea),
                null  // 字幕素材：初始为空
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
     * 上传补充背景文档，将文档纯文本追加到会话的 background_context 字段。
     * <p>
     * 文档处理流程：对每个文件依次调用 {@link DocumentExtractionService#extract}（Tika 提取纯文本），
     * 提取结果在内存中拼接后通过 mapper 的 appendBackgroundContext 原子追加到数据库已有内容之后。
     * <p>
     * 长度控制策略：累计当前已有背景文本 + 本次新追加文本，一旦总量超过
     * {@link #MAX_BACKGROUND_CONTEXT_CHARS} 即停止后续文件的处理并在末尾追加跳过提示。
     * 这样设计是因为：1) LLM 上下文窗口有限，背景过大会挤占推理空间；
     * 2) 越早的文件越可能是用户主动上传的核心资料，应优先保留。
     * <p>
     * 每个文档前添加【文档：文件名】标记，文档之间用 "---" 分隔，方便 LLM 区分不同文档来源。
     * <p>
     * 事务边界：appendBackgroundContext 为单条 UPDATE，独立事务。
     *
     * @param taskId 已创建的交互任务 ID
     * @param files  用户上传的文件列表，可为空（空时直接返回当前任务状态）
     * @return 更新后的交互任务响应（含最新的 backgroundContext）
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
     * AI 理解确认 —— 调用 LLM 将用户的自然语言想法和补充背景资料融合理解，输出结构化理解摘要。
     * <p>
     * <b>为什么必须有这一步</b>：用户的自然语言想法往往模糊、片段化，且可能携带大量背景资料。
     * 如果直接从想法跳到方向卡生成，AI 容易误解用户意图（例如把"做 React 教程"理解成"做前端入行指南"）。
     * 理解确认环节让用户有机会核验 AI 是否真正理解了自己的创作意图，是生成方向卡前的"校准步骤"。
     * <p>
     * <b>状态机流转</b>：
     * <ul>
     *   <li>调用前 any → UNDERSTANDING（防重复提交锁）</li>
     *   <li>LLM 成功返回 → 更新摘要 + 状态 READY</li>
     *   <li>LLM 失败 → 回退状态 NONE，让用户可以重试</li>
     * </ul>
     * <p>
     * 理解确认不可跳过 —— {@link #generateCreativeOptions} 会校验 understandingStatus 必须为 READY 或 CONFIRMED。
     *
     * @param taskId 交互任务 ID
     * @return 更新后的交互任务响应，包含 understandingSummary 和 understandingStatus=READY
     */
    @Transactional
    public InteractiveTaskResponse triggerUnderstanding(String taskId) {
        InteractiveSessionRecord session = getSessionRecord(taskId);

        // 更新状态为 UNDERSTANDING（生成中），防止用户重复点击提交导致并发 LLM 调用
        creatorInteractiveMapper.updateUnderstanding(session.getTaskId(), null, "UNDERSTANDING");

        String summary;
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.open(session.getTaskId(), "AI理解确认")) {
            // 用 try-with-resources 包裹 LlmUsageContext，确保 LLM 调用结束后正确记录用量统计
            summary = llmService.chat(
                    buildUnderstandingSystemPrompt(),
                    buildUnderstandingUserPrompt(session)
            );
        } catch (RuntimeException exception) {
            // AI 理解失败时回退状态到 NONE，让用户可以重试；
            // 不保留 UNDERSTANDING 状态，否则会阻塞用户再次点击
            creatorInteractiveMapper.updateUnderstanding(session.getTaskId(), null, "NONE");
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "AI 理解确认失败: " + exception.getMessage(), exception);
        }

        // 理解成功后写入摘要文本并标记 READY，供用户在前端查看和确认
        creatorInteractiveMapper.updateUnderstanding(session.getTaskId(), summary, "READY");
        return getInteractiveTask(session.getTaskId());
    }

    /**
     * 生成创意方向卡（首次生成）。
     * <p>
     * 前置条件：AI 理解确认必须已完成（understandingStatus 为 READY 或 CONFIRMED），
     * 否则抛出 400 错误提示用户先完成理解确认。
     * <p>
     * 调用 LLM 基于"原始想法 + 补充背景资料 + AI 理解摘要 + 本轮额外要求"生成 3 张创意方向卡。
     * 每张卡片包含：创意名称、适合人群、标题大纲、内容大纲、简介大纲、亮点、风险、AI 推荐理由。
     * <p>
     * 与 {@link #regenerateOptions} 的区别：
     * <ul>
     *   <li>本方法要求 understandingStatus 为 READY/CONFIRMED，并将 READY 态自动转为 CONFIRMED</li>
     *   <li>regenerateOptions 没有理解状态前置校验，会清空之前的选中记录后重新生成</li>
     * </ul>
     * <p>
     * 事务边界：清理旧方向卡 + 插入新方向卡 + 更新会话状态在同一事务中完成。
     *
     * @param taskId          交互任务 ID
     * @param extraRequirement 用户可选的额外要求（如"多关注技术深度""偏入门向"），可为 null
     * @return 更新后的交互任务响应，包含新生成的 3 张方向卡
     */
    @Transactional
    public InteractiveTaskResponse generateCreativeOptions(String taskId, String extraRequirement) {
        InteractiveSessionRecord session = getSessionRecord(taskId);
        String understandingStatus = session.getUnderstandingStatus() == null ? "NONE" : session.getUnderstandingStatus();

        // 理解确认是必要步骤 —— 防止跳过理解直接生成，AI 对用户想法的理解可能完全偏离
        if (!"READY".equals(understandingStatus) && !"CONFIRMED".equals(understandingStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "请先完成 AI 理解确认。当前状态：" + understandingStatus);
        }

        // 用户查看了理解确认且触发了生成，说明认可 AI 理解 → 标记为 CONFIRMED
        // 与 "仅查看理解但不生成" 的场景区分开：CONFIRMED 表示理解已被用户认可
        if ("READY".equals(understandingStatus)) {
            creatorInteractiveMapper.updateUnderstanding(session.getTaskId(),
                    session.getUnderstandingSummary(), "CONFIRMED");
        }

        generateAndStoreOptions(session, extraRequirement);
        return getInteractiveTask(session.getTaskId());
    }

    /**
     * 重新生成创意方向卡。
     * <p>
     * 与 {@link #generateCreativeOptions} 的区别：
     * <ul>
     *   <li>不要求 understandingStatus 为 READY/CONFIRMED —— 理解已完成过的会话可以直接重新生成</li>
     *   <li>会先清除上一轮所有选项的选中标记（clearSelectedOptions），防止残留选中状态</li>
     *   <li>状态流转：ANY → CREATIVE_GENERATING → CREATIVE_OPTIONS_READY</li>
     * </ul>
     * <p>
     * 适用场景：用户对当前 3 张方向卡都不满意，带上额外要求（如"不要太技术向""多从流量角度考虑"）重新生成。
     *
     * @param taskId  交互任务 ID
     * @param request 重新生成请求，可携带 extraRequirement 额外要求；可为 null（无额外要求的重新生成）
     * @return 更新后的交互任务响应，包含重新生成的 3 张方向卡
     */
    @Transactional
    public InteractiveTaskResponse regenerateOptions(String taskId, CreativeOptionsRegenerateRequest request) {
        InteractiveSessionRecord session = getSessionRecord(taskId);
        String extraRequirement = request == null ? null : TextUtil.trimToNull(request.extraRequirement());
        // 清除上一轮所有选项的选中状态，因为用户明确表示对当前结果不满意
        creatorInteractiveMapper.clearSelectedOptions(session.getSessionId());
        creatorInteractiveMapper.updateSessionSelection(session.getSessionId(), "CREATIVE_GENERATING", null);
        generateAndStoreOptions(session, extraRequirement);
        return getInteractiveTask(session.getTaskId());
    }

    /**
     * 确认选定一张创意方向卡。
     * <p>
     * 用户从 3 张方向卡中选择最满意的一张后调用此方法，完成"生成 → 确认"闭环。
     * 确认后的操作：
     * <ol>
     *   <li><b>原子选中</b>：先清除同会话下所有选项的选中标记，再单独选中目标卡片。
     *       这是为了确保同一会话同一时间只有一张卡片被选中。</li>
     *   <li><b>更新会话状态</b>：status 更新为 CREATIVE_CONFIRMED，selectedOptionId 记录选中的卡片 ID。</li>
     *   <li><b>回填任务素材</b>：将选中卡片的所有创意信息（标题大纲、内容大纲、亮点、风险等）
     *       格式化后写入关联 CreatorTask 的 material 字段，供后续发布前优化阶段使用。</li>
     * </ol>
     * <p>
     * 防并发策略：clearSelectedOptions + selectOption + updateSessionSelection 在同一事务中执行，
     * 选中失败（影响行数为 0）时抛出 404。
     *
     * @param taskId   交互任务 ID
     * @param optionId 用户选中的创意卡片 ID
     * @return 更新后的交互任务响应，status=CREATIVE_CONFIRMED 且 selectedOptionId 已设置
     */
    @Transactional
    public InteractiveTaskResponse confirmOption(String taskId, String optionId) {
        String safeTaskId = normalizeTaskId(taskId);
        String safeOptionId = normalizeOptionId(optionId);
        InteractiveSessionRecord session = getSessionRecord(safeTaskId);
        // 先查卡片是否存在且属于当前任务，避免非法的 optionId 传入
        CreativeIdeaOptionRecord selectedOption = creatorInteractiveMapper
                .findOptionByTaskIdAndOptionId(session.getTaskId(), safeOptionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创意卡片不存在"));

        // 原子选中：先清除全部选中，再标记目标卡片
        creatorInteractiveMapper.clearSelectedOptions(session.getSessionId());
        int selected = creatorInteractiveMapper.selectOption(session.getSessionId(), selectedOption.getOptionId());
        if (selected == 0) {
            // 理论上不会走到这里（前面已确认存在），是防御性检查 —— 防止极端并发下卡片被删除
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "创意卡片不存在");
        }
        creatorInteractiveMapper.updateSessionSelection(
                session.getSessionId(),
                "CREATIVE_CONFIRMED",
                selectedOption.getOptionId()
        );

        // 将选中卡片的创意信息回填到 CreatorTask 素材字段，衔接后续的发布前优化流程
        updateTaskMaterialsBySelectedOption(session, selectedOption);
        return getInteractiveTask(session.getTaskId());
    }

    /**
     * 查询交互任务完整状态。
     * <p>
     * 返回会话记录 + 关联的所有创意方向卡，是目前状态最完整的查询接口。
     * 所有写入方法在完成操作后都调用此方法构造统一的响应，保证前后端字段结构一致。
     *
     * @param taskId 交互任务 ID
     * @return 完整的交互任务响应（会话信息 + 方向卡列表）
     */
    public InteractiveTaskResponse getInteractiveTask(String taskId) {
        InteractiveSessionRecord session = getSessionRecord(taskId);
        List<CreativeIdeaOptionResponse> options = creatorInteractiveMapper
                .listOptionsBySessionId(session.getSessionId())
                .stream()
                .map(this::toOptionResponse)
                .toList();
        return toTaskResponse(session, options);
    }

    /**
     * 核心生成逻辑：调用 LLM 生成方向卡 → 解析 JSON → 写入数据库。
     * <p>
     * 同时被 {@link #generateCreativeOptions} 和 {@link #regenerateOptions} 共用，
     * 避免生成链路代码重复。两者唯一区别在前置校验和状态流转，实际 LLM 调用与存储逻辑完全一致。
     * <p>
     * <b>异常兜底策略</b>：
     * <ul>
     *   <li>LLM 调用成功 + JSON 解析成功 → parseStatus = "PARSED"，正常写入</li>
     *   <li>LLM 调用成功但 JSON 解析失败 → parseStatus = "RAW_ONLY"，保留 LLM 原始输出，
     *       用内置兜底卡替代，用户可查看原始输出定位问题</li>
     *   <li>LLM 调用本身抛出异常 → parseStatus = "RAW_ONLY"，生成内置兜底卡，
     *       错误信息写入 rawOutput 字段</li>
     * </ul>
     * 无论哪种路径都会写入 3 张方向卡（正常或兜底），保证前端始终有内容可展示。
     * <p>
     * 事务边界：先删旧卡再插新卡，确保同一会话只有最新一轮的方向卡。
     *
     * @param session          当前交互会话记录
     * @param extraRequirement 用户额外要求，拼入 LLM Prompt 供 AI 参考
     */
    private void generateAndStoreOptions(InteractiveSessionRecord session, String extraRequirement) {
        String rawOutput = "";
        List<CreativeIdeaOptionRecord> options;
        String parseStatus = "PARSED";
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.open(session.getTaskId(), "AI创意方案")) {
            rawOutput = llmService.chat(buildCreativeSystemPrompt(), buildCreativeUserPrompt(session, extraRequirement));
            options = parseOptions(session, rawOutput);
        } catch (RuntimeException exception) {
            // LLM 调用异常或 JSON 解析异常的统一兜底处理：
            // 保留 rawOutput 供排查（如有），用内置兜底卡保证用户体验不中断
            parseStatus = "RAW_ONLY";
            rawOutput = TextUtil.hasText(rawOutput) ? rawOutput : "AI 创意生成失败：" + exception.getMessage();
            options = buildFallbackOptions(session);
        }

        // 先删除旧的再插入新的，保证原子性 —— 用户看到的是完整的一轮方向卡，不是新旧混合
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

    /**
     * 解析 LLM 输出的 JSON 方向卡文本为记录列表。
     * <p>
     * 解析流程：从 LLM 原始输出中提取 JSON 对象 → 解析 Jackson 树 → 校验 options 数组存在且非空
     * → 遍历取前 OPTION_COUNT 条 → 不足时用兜底卡补齐。
     * <p>
     * 不足补齐的边界：LLM 可能因输出截断或其他原因返回少于 OPTION_COUNT 条，
     * 此时用内置兜底卡补齐至 OPTION_COUNT，确保前端始终展示固定数量的卡片。
     * JSON 结构异常时（如完全无法解析）直接抛出异常，交给 {@link #generateAndStoreOptions} 的 catch 兜底。
     *
     * @param session  当前交互会话
     * @param rawOutput LLM 原始输出文本
     * @return 解析后的 OPTION_COUNT 张方向卡记录
     * @throws ResponseStatusException JSON 完全无法解析或缺少 options 数组时
     */
    private List<CreativeIdeaOptionRecord> parseOptions(InteractiveSessionRecord session, String rawOutput) {
        try {
            // step1: 从 LLM 原始输出中提取 JSON 对象——LlmJsonUtil.extractJsonObject
            // 内部会处理 LLM 在 JSON 前后可能输出的额外文本（如代码块标记）
            JsonNode rootNode = objectMapper.readTree(LlmJsonUtil.extractJsonObject(rawOutput));
            JsonNode optionsNode = rootNode.get("options");
            // step2: 校验 options 数组存在且非空——任一条件不满足都视为解析失败
            if (optionsNode == null || !optionsNode.isArray() || optionsNode.size() == 0) {
                throw new IllegalArgumentException("LLM 输出缺少 options 数组");
            }
            // step3: 遍历取前 OPTION_COUNT 条，防止 LLM 输出过多卡片导致数据库冗余
            List<CreativeIdeaOptionRecord> records = new ArrayList<>();
            for (int index = 0; index < Math.min(OPTION_COUNT, optionsNode.size()); index++) {
                records.add(toOptionRecord(session, optionsNode.get(index), index));
            }
            // step4: 不足 OPTION_COUNT 条时用兜底卡补齐——保证前端始终展示固定数量的卡片
            while (records.size() < OPTION_COUNT) {
                records.add(buildFallbackOption(session, records.size()));
            }
            return records;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            // JsonProcessingException: JSON 格式本身无效（非法的 JSON 语法）
            // IllegalArgumentException: JSON 结构合法但缺少必要字段（options 数组）
            // 两种异常统一转为 500 并向上抛出，由 generateAndStoreOptions 的 catch 兜底
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "AI 创意卡片解析失败", exception);
        }
    }

    /**
     * 将单个 JSON 方向卡节点转换为数据库记录。
     * <p>
     * 每个字段都设了默认值：如果 LLM 输出中某个字段缺失或为空，使用 fallback 兜底，
     * 避免个别字段缺失导致整张卡片不可用。这是设计权衡——宁可展示一张信息不完整的卡片，
     * 也不因为这一个小字段丢失整张卡。
     * <p>
     * optionName 会通过 TextUtil.abbreviate 截断至 {@link #OPTION_NAME_MAX_LENGTH} 字符，
     * 防止 LLM 输出过长名称影响前端列表展示。
     *
     * @param session    当前交互会话
     * @param optionNode Jackson JSON 节点，对应单张方向卡
     * @param index      卡片索引（0-based），用于生成默认名称
     * @return 转换后的数据库记录
     */
    private CreativeIdeaOptionRecord toOptionRecord(InteractiveSessionRecord session, JsonNode optionNode, int index) {
        CreativeIdeaOptionRecord record = new CreativeIdeaOptionRecord();
        // 生成全局唯一的 optionId，即使同一会话内不同轮次的方向卡也不会 ID 冲突
        record.setOptionId(UUID.randomUUID().toString());
        record.setSessionId(session.getSessionId());
        record.setTaskId(session.getTaskId());
        // optionName 需要截断——LLM 可能输出过长的创意名称
        record.setOptionName(TextUtil.abbreviate(
                defaultText(LlmJsonUtil.text(optionNode, "optionName"), "创意方向 " + (index + 1)),
                OPTION_NAME_MAX_LENGTH
        ));
        // 以下每个字段都有默认 fallback：LLM 缺失某字段时用通用描述兜底，
        // 保证前端始终有可展示的文本，而不是空白或 null
        record.setTargetAudience(defaultText(LlmJsonUtil.text(optionNode, "targetAudience"), "对这个主题感兴趣的 B 站观众"));
        // 大纲类字段使用 jsonField 而非 defaultText：因为它们是 JSON 数组格式，
        // 需要统一序列化为 JSON 数组字符串存储
        record.setTitleOutline(jsonField(optionNode, "titleOutline", "标题突出主题、收益和观看门槛"));
        record.setContentOutline(jsonField(optionNode, "contentOutline", "开头抛出问题，主体分段说明，结尾给出行动建议"));
        record.setDescriptionOutline(jsonField(optionNode, "descriptionOutline", "简介说明视频价值、关键词和互动引导"));
        record.setSellingPoints(jsonField(optionNode, "sellingPoints", "贴合用户原始想法，便于快速进入制作"));
        record.setRiskPoints(jsonField(optionNode, "riskPoints", "需要避免标题过度承诺或内容泛泛而谈"));
        record.setRecommendReason(defaultText(LlmJsonUtil.text(optionNode, "recommendReason"), "这个方向最容易从当前想法落地成视频。"));
        // 新生成的卡片默认未选中——选中由 confirmOption 方法单独标记
        record.setSelected(false);
        return record;
    }

    /**
     * 安全提取 JSON 数组字段。
     * <p>
     * 所有大纲类字段（titleOutline、contentOutline 等）在 Prompt 中定义为字符串数组。
     * 此方法统一处理 LLM 可能返回的三种情况：
     * <ul>
     *   <li>正常返回 JSON 数组字符串 → 直接返回</li>
     *   <li>字段存在但值为空数组或空字符串 → 序列化 fallbackText 为单元素 JSON 数组返回</li>
     *   <li>字段不存在或格式异常 → 手动拼接 fallbackText 为 JSON 数组字符串返回</li>
     * </ul>
     * 手动拼接 fallback 字符串（而非抛异常）是为了降低对 LLM 输出格式的依赖——个别字段异常
     * 不应阻断整张方向卡的生成，但用户会在前端看到 fallback 文本从而感知异常。
     *
     * @param optionNode   单张方向卡的 JSON 节点
     * @param fieldName    字段名（如 "titleOutline"）
     * @param fallbackText 兜底文本，当字段缺失或解析失败时使用
     * @return JSON 数组字符串，保证不为空
     */
    private String jsonField(JsonNode optionNode, String fieldName, String fallbackText) {
        try {
            String json = LlmJsonUtil.json(objectMapper, optionNode, fieldName);
            if (TextUtil.hasText(json)) {
                return json;
            }
            // LLM 返回了空值或空数组：用 fallback 文本兜底
            return objectMapper.writeValueAsString(List.of(fallbackText));
        } catch (JsonProcessingException exception) {
            // 序列化本身异常（极端情况）：手动拼接 JSON 字符串兜底，避免空值导致前端崩溃
            return "[\"" + fallbackText + "\"]";
        }
    }

    /**
     * 批量生成 OPTION_COUNT 张内置兜底方向卡。
     * <p>
     * 当 LLM 调用完全失败或 JSON 解析失败时调用。内置兜底卡覆盖三种通用创作范式
     * （问题切入型、教程拆解型、观点复盘型），提供通用模板框架而非具体内容，
     * 保证创作流程不中断——用户后续可以基于兜底卡补充具体信息或重新生成。
     */
    private List<CreativeIdeaOptionRecord> buildFallbackOptions(InteractiveSessionRecord session) {
        List<CreativeIdeaOptionRecord> options = new ArrayList<>();
        for (int index = 0; index < OPTION_COUNT; index++) {
            options.add(buildFallbackOption(session, index));
        }
        return options;
    }

    /**
     * 构建单张内置兜底方向卡。
     * <p>
     * 通过 index 循环轮换三种预设创作范式：
     * <ul>
     *   <li>index % 3 == 0 → "问题切入型"：从一个具体问题引出主题</li>
     *   <li>index % 3 == 1 → "教程拆解型"：面向想快速学会方法的新手</li>
     *   <li>index % 3 == 2 → "观点复盘型"：面向关心经验总结的创作者</li>
     * </ul>
     * 兜底卡的所有字段都是通用式描述，不包含用户的具体想法内容（因为 LLM 调用失败，
     * 无法获知具体内容）。recommendReason 明确告知用户这是异常兜底结果。
     * <p>
     * 兜底卡保留用户的原始想法（截断至 50 字符）嵌入标题大纲，提供最低限度的个性化。
     *
     * @param session 当前交互会话
     * @param index   卡片索引（0-based），决定使用哪种范式模板
     * @return 一张内置兜底方向卡
     */
    private CreativeIdeaOptionRecord buildFallbackOption(InteractiveSessionRecord session, int index) {
        // 三种预设创作范式的名称和对应受众，通过 index 轮换保证 3 张卡类型不重复
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
        // index % names.length 保证即使 OPTION_COUNT 变化也不会数组越界
        record.setOptionName(names[index % names.length]);
        record.setTargetAudience(audiences[index % audiences.length]);
        // 兜底卡在标题大纲中嵌入用户原始想法（截断 50 字符），提供最低限度的个性化
        record.setTitleOutline(toJsonList(
                "用一个具体问题引出主题：" + TextUtil.abbreviate(session.getIdea(), 50),
                "标题里给出明确收益，避免只写概念名"
        ));
        // 内容大纲给出通用的"背景→关键点→常见误区→行动建议"结构
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
        // 风险提示是通用建议，告诉用户兜底卡的局限性
        record.setRiskPoints(toJsonList("需要后续补充真实素材", "标题不能承诺视频里没有覆盖的结果"));
        // recommendReason 明确告知用户这是异常兜底，让用户知道可以重新生成
        record.setRecommendReason("这是 AI 输出异常时的兜底方向，先保证创作流程不中断，后续可重新生成。");
        record.setSelected(false);
        return record;
    }

    /**
     * 将用户选中的方向卡信息回填到关联 CreatorTask 的素材字段中。
     * <p>
     * 这是交互式创作流程与 CreatorTask 体系的衔接点：当用户确认方向卡后，
     * 卡片的标题大纲、内容大纲、亮点、风险等信息被格式化为结构化文本，
     * 写入 CreatorTask 的 material（IDEA 类型），供后续"发布前优化"阶段直接使用。
     * <p>
     * 保留原有 material 中的 SUBTITLE 素材不变，因为用户可能已在其他入口上传过字幕。
     *
     * @param session        当前交互会话
     * @param selectedOption 用户选中的方向卡
     */
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

    /**
     * 从方向卡名称构建标题草稿。
     * 折叠多余空白后截断至 200 字符，作为 CreatorTask 标题草稿的初始值。
     */
    private String buildTitleDraft(CreativeIdeaOptionRecord option) {
        return TextUtil.abbreviate(TextUtil.collapseWhitespace(option.getOptionName()), 200);
    }

    /**
     * 从方向卡信息构建简介草稿。
     * 包含创意名称、适合人群、简介大纲三个字段，截断至 2000 字符。
     */
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

    /**
     * 将选中方向卡的全部信息格式化为结构化素材文本。
     * <p>
     * 包含 9 个维度的信息（原始想法、创意方向、适合人群、标题大纲、内容大纲、简介大纲、
     * 亮点、风险、AI 建议），以中文标签分隔。超长内容截断至 {@link #TASK_MATERIAL_MAX_LENGTH} 字符。
     * 此文本将被写入 CreatorTask 的 IDEA 类型 material 字段，供后续发布前优化阶段使用。
     */
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

    /**
     * 从 CreatorTask 的素材列表中按类型查找第一条素材内容。
     * 用于更新任务时保留特定类型的已有素材（如 SUBTITLE），避免覆盖用户其他入口的数据。
     *
     * @param task         当前任务响应
     * @param materialType 素材类型标识（如 "SUBTITLE"）
     * @return 匹配的素材内容，未找到返回 null
     */
    private String findMaterial(CreatorTaskResponse task, String materialType) {
        return task.materials()
                .stream()
                .filter(material -> materialType.equals(material.materialType()))
                .findFirst()
                .map(material -> TextUtil.trimToNull(material.content()))
                .orElse(null);
    }

    // ──────────────────────────── AI 理解确认 Prompt ────────────────────────────
    // 理解确认阶段的设计原则：
    // 1. 系统提示词要求 LLM 专注"理解"，而非"建议"——避免 AI 在这一步就开始自作主张地推荐方向
    // 2. 要求纯文本输出：理解摘要需要展示给用户阅读，纯文本比 JSON 更易读；且避免 Markdown
    //    渲染问题（前端可能不渲染 Markdown）
    // 3. 用户提示词从 5 个维度引导分析：确保理解覆盖用户关心的所有方面
    //    （主题、观众、要点、风险、不确定性），结构化为用户的审查提供清晰的 checklist

    /**
     * 构建 AI 理解确认的系统提示词。
     * <p>
     * 核心约束：只输出纯文本，不要 Markdown，不要 JSON。理解摘要是给用户阅读的文本，
     * 不是机器解析的格式。同时要求 AI 基于事实给出理解，背景资料不足时要诚实指出，
     * 防止 AI 在信息不足时编造内容。
     */
    private String buildUnderstandingSystemPrompt() {
        return """
                你是 B 站内容创作意图的理解分析助手。
                你的任务是把用户的自然语言想法和补充背景资料结合起来，
                用清晰的中文总结你对创作意图的理解。
                如果背景资料充分，你要基于事实给出理解；如果背景资料不足，你要诚实地指出不确定的地方。
                你必须只输出纯文本，不要使用 Markdown 格式，不要输出 JSON。
                """;
    }

    /**
     * 构建 AI 理解确认的用户提示词（填充用户想法、视频类型、背景资料）。
     * <p>
     * 5 维度分析结构的设计理由：
     * <ul>
     *   <li>视频主题 + 目标观众：基础对齐，确保 AI 理解"做什么、给谁看"</li>
     *   <li>核心要点：提取背景资料中的关键事实，防止 AI 遗漏重要信息</li>
     *   <li>需要避免的问题：提前识别陷阱（过度承诺、误解风险），让用户提前纠偏</li>
     *   <li>不确定的地方：强制 AI 承认知识盲区而不是编造，这是"诚实对齐"的关键机制</li>
     * </ul>
     * 特别注意：明确要求 AI 不要在理解中提出创作建议——这一步的唯一目的是确认理解，
     * 建议是下一阶段方向卡生成的事。
     */
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
    // 创意生成阶段的设计原则：
    // 1. 系统提示词要求只输出 JSON（不要 Markdown/解释）：因为此处输出由程序解析，
    //    任何非 JSON 内容都会导致解析失败。强调"具体到当前用户想法"防止 AI 输出通用模板
    // 2. 用户提示词按优先级分层：原始想法 → 额外要求 → 背景资料 → AI 理解摘要。
    //    AI 理解摘要放在最后且有强调性说明，因为它是已被用户确认的权威理解，应具有最高权重
    // 3. JSON Schema 内嵌在用户提示词中（而非系统提示词），因为字段含义与业务上下文高度相关，
    //    放在用户消息中 LLM 能更准确地理解每个字段的生产语义
    // 4. 要求恰好 3 张且角度明显不同：保证用户有足够的选择空间且不做无效选项

    /**
     * 构建创意方向卡生成的系统提示词。
     * <p>
     * 核心约束：只输出 JSON 对象，不要 Markdown/解释文字，因为此处输出需要程序解析。
     * 严格要求每张卡片具体到当前用户想法，防止 AI 输出千篇一律的通用模板。
     * 保持包级可见，让同包的人工评测复用生产提示词，避免测试复制后与真实链路逐渐漂移。
     */
    String buildCreativeSystemPrompt() {
        return """
                你是 B 站内容创作者的选题策划助手。
                你的任务是把用户的一段自然语言创作想法，拆成 3 个可选创意方向。
                你必须只输出 JSON 对象，不要输出 Markdown，不要解释 JSON 之外的内容。
                每个数组字段必须是字符串数组，每张卡片都要具体到当前用户想法，不要写通用套话。
                """;
    }

    /**
     * 构建创意方向卡生成的用户提示词（填充原始想法、视频类型、额外要求、背景资料、AI 理解摘要）。
     * <p>
     * 信息层级设计（从低权重到高权重）：
     * <ol>
     *   <li>原始想法 + 视频类型：用户的基础输入，最重要的参考源</li>
     *   <li>额外要求：用户本轮特有的偏好（如"偏入门"），可覆盖基础输入的一些倾向</li>
     *   <li>背景资料：用户上传的事实性文档，要求 AI 必须据此生成，不得编造与资料矛盾的信息</li>
     *   <li>AI 理解摘要（最高权重）：已被用户确认的权威理解，方向卡必须与此理解对齐</li>
     * </ol>
     * <p>
     * JSON Schema 内嵌设计：将字段定义直接放在用户提示词中，每个字段附带中文说明，
     * 让 LLM 清楚知道每个字段的业务语义。这比放在系统提示词中更有效，
     * 因为 LLM 对用户消息中的指令关注度更高。
     * 保持包级可见，让评测输入和生产调用使用完全相同的用户提示词构造规则。
     */
    String buildCreativeUserPrompt(InteractiveSessionRecord session, String extraRequirement) {
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

    /**
     * 将用户原始想法包装为 CreatorTask 的 IDEA 素材格式。
     * 仅用于任务创建时的初始素材填充，后续确认方向卡后会被 {@link #buildConfirmedIdeaMaterial} 覆盖。
     */
    private String buildIdeaMaterial(String idea) {
        return "【用户原始创作想法】\n" + TextUtil.abbreviateWithSuffix(idea, TASK_MATERIAL_MAX_LENGTH, "\n[内容过长，已截断]");
    }

    /**
     * 从用户想法文本生成任务名称。
     * 折叠多余空白后截断至 {@link #TASK_NAME_MAX_LENGTH} 字符，用于任务列表快速辨识。
     */
    private String buildTaskName(String idea) {
        return TextUtil.abbreviate(TextUtil.collapseWhitespace(idea), TASK_NAME_MAX_LENGTH);
    }

    /**
     * 按任务 ID 查询交互会话记录，不存在时抛出 404。
     * taskId 先经 normalize 处理（trim + 非空校验），防止空字符串或纯空格导致无效查询。
     */
    private InteractiveSessionRecord getSessionRecord(String taskId) {
        return creatorInteractiveMapper.findSessionByTaskId(normalizeTaskId(taskId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "交互式创作任务不存在"));
    }

    /**
     * 将会话记录 + 方向卡列表组装为统一的交互任务响应 DTO。
     * 所有字段直接映射，不做额外转换。
     */
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

    /**
     * 将单张方向卡数据库记录转换为响应 DTO。
     * selected 字段需要 Boolean.TRUE.equals 做空安全处理，因为数据库中可能为 null。
     */
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

    /**
     * 规范化用户 ID：trim 后若为空回退到 "default"。
     * 与 AgentExecutor 的匿名用户策略一致，保证长期记忆隔离的语义统一。
     */
    private String normalizeUserId(String userId) {
        return TextUtil.trimToDefault(userId, DEFAULT_USER_ID);
    }

    /**
     * 规范化创作想法：trim 后若为空则抛出 400。
     * 创作想法是整个交互流程的起点，必须非空。
     */
    private String normalizeIdea(String idea) {
        String normalized = TextUtil.trimToNull(idea);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "创作想法不能为空");
        }
        return normalized;
    }

    /**
     * 规范化视频类型：trim 后若为空回退到"未分类"。
     */
    private String normalizeVideoType(String videoType) {
        return TextUtil.trimToDefault(videoType, DEFAULT_VIDEO_TYPE);
    }

    /**
     * 规范化任务 ID：trim 后若为空则抛出 400。
     * 所有操作都需要有效的 taskId 来定位会话。
     */
    private String normalizeTaskId(String taskId) {
        String normalized = TextUtil.trimToNull(taskId);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务ID不能为空");
        }
        return normalized;
    }

    /**
     * 规范化卡片 ID：trim 后若为空则抛出 400。
     */
    private String normalizeOptionId(String optionId) {
        String normalized = TextUtil.trimToNull(optionId);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "创意卡片ID不能为空");
        }
        return normalized;
    }

    /**
     * 文本兜底工具：value 非空返回 value，为空返回 defaultValue。
     * 统一调用 TextUtil.trimToDefault，简化调用方代码。
     */
    private String defaultText(String value, String defaultValue) {
        return TextUtil.trimToDefault(value, defaultValue);
    }

    /**
     * 将字符串数组序列化为 JSON 数组字符串。
     * 用于兜底卡的大纲字段序列化。序列化异常时返回空数组 "[]" 作为极端兜底。
     */
    private String toJsonList(String... values) {
        try {
            return objectMapper.writeValueAsString(List.of(values));
        } catch (JsonProcessingException exception) {
            // Jackson 对字符串数组序列化几乎不会失败，但为防御性编程保留兜底
            return "[]";
        }
    }
}
