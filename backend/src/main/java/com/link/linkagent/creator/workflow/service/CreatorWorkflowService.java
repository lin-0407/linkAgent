package com.link.linkagent.creator.workflow.service;

import com.link.linkagent.creator.interactive.mapper.CreatorInteractiveMapper;
import com.link.linkagent.creator.interactive.model.InteractiveSessionRecord;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.preference.service.CreatorPreferenceService;
import com.link.linkagent.creator.profile.service.CreatorProfileService;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionResponse;
import com.link.linkagent.creator.suggestion.model.PrePublishAnalyzeRequest;
import com.link.linkagent.creator.suggestion.model.PrePublishSuggestionCandidate;
import com.link.linkagent.creator.suggestion.service.PrePublishSuggestionService;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorMaterialType;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.model.CreatorTaskStatus;
import com.link.linkagent.creator.workflow.event.CreatorWorkflowEventPublisher;
import com.link.linkagent.creator.workflow.mapper.CreatorWorkflowMapper;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowConfirmRequest;
import com.link.linkagent.creator.workflow.model.CreatorIntentAlignmentContext;
import com.link.linkagent.creator.workflow.model.CreatorIntentAlignmentOutput;
import com.link.linkagent.creator.workflow.model.CreatorIntentReviewResult;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowEventResponse;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowEventType;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowMessageContentType;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowMessageCreateRequest;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowMessageRecord;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowMessageResponse;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowMessageRole;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowSessionRecord;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowSessionResponse;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStage;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStartRequest;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStatus;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStepRecord;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStepResponse;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStepStatus;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStepType;
import com.link.linkagent.creator.workflow.model.PrePublishDraftRequest;
import com.link.linkagent.creator.workflow.model.PrePublishDraftResponse;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.usage.LlmApiUsageService;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.llm.usage.WorkflowUsageResponse;
import com.link.linkagent.settings.service.RuntimeSettingService;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 创作者工作流服务 —— 驱动「发布前优化」阶段的业务流程编排。
 * <p>
 * 核心职责：以工作流会话（Session）为单位，管理发布前优化阶段的状态机
 * （上下文加载 → 等待用户输入 → 运行中 → 等待确认 → 已确认 / 失败 / 取消），
 * 协调消息流、步骤回放、建议生成与确认、文稿草稿生成等子流程。
 * <p>
 * 架构定位：位于业务编排层，向上暴露给 Controller，向下编排多个领域服务
 * （{@link PrePublishSuggestionService} 做 LLM/Agent 推理、
 * {@link CreatorPreferenceService} 记录偏好反馈、
 * {@link CreatorProfileService} 维护创作者画像）。
 * 不直接暴露通用 Agent 控制台的 ReAct 循环给 UP 主，而是通过结构化的工作流阶段
 * 引导用户逐步完成发布前优化。
 * <p>
 * 状态机设计：工作流会话的 {@link CreatorWorkflowStatus} 是单向推进的有限状态机，
 * 每个状态只允许特定操作（如在 WAITING_CONFIRMATION 才能确认建议），
 * 操作前通过 ensureXxx 方法做状态前置校验，拒绝状态不合法的调用。
 */
@Service
public class CreatorWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(CreatorWorkflowService.class);

    // —— 业务常量：不可变配置，集中管理便于统一调整 ——

    /** 匿名用户默认标识，与 AgentExecutor 的 “default” 保持一致 */
    private static final String DEFAULT_USER_ID = "default";
    /** 消息详情引用类型 —— 材料，前端通过(detailRefType, detailRefId)查询材料详情 */
    private static final String DETAIL_REF_TYPE_MATERIAL = "MATERIAL";

    /** 消息详情引用类型 —— 建议，前端通过(detailRefType, detailRefId)查询建议卡片 */
    private static final String DETAIL_REF_TYPE_SUGGESTION = "SUGGESTION";
    /** 想法对齐消息标记，前端据此过滤旧流程中的过程话术，只展示真实对话。 */
    private static final String DETAIL_REF_TYPE_INTENT_ALIGNMENT = "INTENT_ALIGNMENT";
    /** 同一上下文最多成功生成三次发布方案，失败调用不计入次数。 */
    private static final int MAX_PLAN_GENERATION_COUNT = 3;
    /** 给发布方案生成器注入原始想法、背景资料和用户消息时的最大长度。 */
    private static final int PLAN_GUIDANCE_MAX_LENGTH = 12000;
    /** 想法对齐上下文总长度上限，避免补充文档无限挤占模型窗口。 */
    private static final int INTENT_SOURCE_MAX_LENGTH = 30000;
    /** 单份任务材料进入想法对齐上下文时的最大长度。 */
    private static final int INTENT_MATERIAL_MAX_LENGTH = 3000;
    /**
     * 工作流补充指导的最大字符数。
     * 合并用户在消息流中的补充要求到 LLM 提示词时，截断上限防止提示词过长导致成本激增。
     */
    private static final int WORKFLOW_GUIDANCE_MAX_LENGTH = 2000;
    /**
     * 错误消息展示的最大字符数。
     * 截断后端异常堆栈中的长错误信息，防止前端展示过长文本影响体验。
     */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 480;
    /**
     * 完整文稿/字幕的最小字符数阈值。
     * 当任务已有材料中 MANUSCRIPT 或 SUBTITLE 类型内容超过此长度时，
     * 视为已有较完整文稿，拒绝重复生成文稿草稿（避免覆盖用户真实内容）。
     */
    private static final int FULL_SCRIPT_MIN_LENGTH = 800;
    /**
     * AI 补稿写入材料后的稳定前缀。
     * <p>
     * 短创意方向不能被误判为完整文稿，但已成功保存的 AI 草稿可以继续进入发布方案生成；
     * 因此用该前缀在不改动现有表结构的前提下区分两种材料来源。
     */
    private static final String AI_MANUSCRIPT_DRAFT_PREFIX = "【AI 可编辑文稿草稿】";
    /**
     * 文稿草稿保存时的最大字符数。
     * LLM 生成的长文稿截断到此上限后写入 material 表，
     * 防止单条材料过大（MySQL 字段长度约束 + Token 成本考虑）。
     */
    private static final int DRAFT_MATERIAL_MAX_LENGTH = 20000;
    /**
     * 文稿草稿生成时每条任务材料的最大字符数。
     * 为控制 LLM 上下文窗口内的 Token 用量，每条材料截断到此长度后再拼入提示词。
     */
    private static final int DRAFT_CONTEXT_MATERIAL_MAX_LENGTH = 4000;

    // —— 注入依赖：每个依赖的业务含义 ——

    /** 创作任务数据访问，用于读取任务和材料信息 */
    private final CreatorTaskMapper creatorTaskMapper;
    /** 发布前优化建议数据访问，用于查询/确认建议记录 */
    private final CreatorSuggestionMapper creatorSuggestionMapper;
    /** 工作流会话/消息/步骤数据访问 */
    private final CreatorWorkflowMapper creatorWorkflowMapper;
    /** 交互式创作会话访问，用于读取用户最初想法、视频类型和补充背景资料 */
    private final CreatorInteractiveMapper creatorInteractiveMapper;
    /** 专用双 Agent 对齐服务，审查 Agent 只标记偏离，不参与改写 */
    private final CreatorIntentAlignmentService creatorIntentAlignmentService;
    /** 发布前优化建议生成服务，封装 LLM/Agent 推理逻辑 */
    private final PrePublishSuggestionService prePublishSuggestionService;
    /** 工作流事件发布器，通过 SSE 向前端实时推送状态变更 */
    private final CreatorWorkflowEventPublisher workflowEventPublisher;
    /** LLM API 用量统计服务，用于按工作流步骤核算 Token 消耗 */
    private final LlmApiUsageService llmApiUsageService;
    /** LLM 调用服务，文稿草稿生成等非 Agent 路径直接调用 */
    private final LLMService llmService;
    /** 运行期设置服务，控制 Agent 模式开关等动态配置 */
    private final RuntimeSettingService runtimeSettingService;
    /** 创作者偏好服务，记录用户采纳/拒绝建议的行为偏好 */
    private final CreatorPreferenceService creatorPreferenceService;
    /** 创作者画像服务，维护和更新创作者个性化画像 */
    private final CreatorProfileService creatorProfileService;
    /** 媒体能力总开关，确认后必须进入试映链路，不能回退到旧反馈链路 */
    private final CreatorMediaProperties creatorMediaProperties;

    public CreatorWorkflowService(CreatorTaskMapper creatorTaskMapper,
                                  CreatorSuggestionMapper creatorSuggestionMapper,
                                  CreatorWorkflowMapper creatorWorkflowMapper,
                                  CreatorInteractiveMapper creatorInteractiveMapper,
                                  CreatorIntentAlignmentService creatorIntentAlignmentService,
                                  PrePublishSuggestionService prePublishSuggestionService,
                                  CreatorWorkflowEventPublisher workflowEventPublisher,
                                  LlmApiUsageService llmApiUsageService,
                                  LLMService llmService,
                                  RuntimeSettingService runtimeSettingService,
                                  CreatorPreferenceService creatorPreferenceService,
                                  CreatorProfileService creatorProfileService,
                                  CreatorMediaProperties creatorMediaProperties) {
        this.creatorTaskMapper = creatorTaskMapper;
        this.creatorSuggestionMapper = creatorSuggestionMapper;
        this.creatorWorkflowMapper = creatorWorkflowMapper;
        this.creatorInteractiveMapper = creatorInteractiveMapper;
        this.creatorIntentAlignmentService = creatorIntentAlignmentService;
        this.prePublishSuggestionService = prePublishSuggestionService;
        this.workflowEventPublisher = workflowEventPublisher;
        this.llmApiUsageService = llmApiUsageService;
        this.llmService = llmService;
        this.runtimeSettingService = runtimeSettingService;
        this.creatorPreferenceService = creatorPreferenceService;
        this.creatorMediaProperties = creatorMediaProperties;
        this.creatorProfileService = creatorProfileService;
    }

    // ============================================================
    // 公开 API：工作流会话生命周期管理
    // ============================================================

    /**
     * 启动或恢复「发布前优化」工作流会话。
     * <p>
     * 状态机入口：根据请求参数决定新建会话还是恢复最近会话（shouldResumeLatest）。
     * 创建新会话时，自动加载任务的所有材料到消息流中（上下文消息），
     * 并将状态从 CONTEXT_LOADING 推进到 WAITING_USER_INPUT。
     * <p>
     * 为什么需要恢复机制：UNI-App 页面切换或断网重连场景下，用户期望回到上次的会话上下文，
     * 而不是每次进入都从头开始——恢复最近会话可以保留消息历史和已生成的建议。
     *
     * @param taskId  创作任务ID，用于关联任务和加载材料
     * @param request 启动请求，含 userId 和是否恢复最近会话的标志
     * @return 工作流会话响应，含 sessionId、当前状态和全部消息
     */
    @Transactional
    public CreatorWorkflowSessionResponse startPrePublishWorkflow(String taskId,
                                                                  CreatorWorkflowStartRequest request) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        List<CreatorMaterialRecord> materials = creatorTaskMapper.listMaterialsByTaskId(taskRecord.getTaskId());
        if (materials.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "创作任务缺少可加载材料");
        }

        // 先锁稳定存在的任务行，再查会话；空索引间隙本身不能可靠串行两个首次恢复请求。
        if (shouldResumeLatest(request)) {
            creatorWorkflowMapper.lockTaskByTaskId(taskRecord.getTaskId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在"));
            return creatorWorkflowMapper.findLatestSessionForUpdate(
                            taskRecord.getTaskId(),
                            CreatorWorkflowStage.PRE_PUBLISH.name()
                    )
                    .map(this::toSessionResponse)
                    .orElseGet(() -> createPrePublishSession(taskRecord, materials, request));
        }

        return createPrePublishSession(taskRecord, materials, request);
    }

    /**
     * 查询工作流会话的完整消息流（按时间序）。
     * <p>
     * 先校验 taskId + sessionId 的关联性（防止跨任务访问），再按 sessionId 拉取消息列表。
     *
     * @param taskId    创作任务ID，用于权限校验
     * @param sessionId 工作流会话ID
     * @return 消息列表，按时间序排列
     */
    public List<CreatorWorkflowMessageResponse> listMessages(String taskId, String sessionId) {
        CreatorWorkflowSessionRecord sessionRecord = getSessionRecord(taskId, sessionId);
        return creatorWorkflowMapper.listMessages(sessionRecord.getSessionId())
                .stream()
                .map(this::toMessageResponse)
                .toList();
    }

    /**
     * 查询工作流会话的步骤执行记录（用于前端渲染进度时间轴）。
     *
     * @param taskId    创作任务ID，用于权限校验
     * @param sessionId 工作流会话ID
     * @return 步骤记录列表，按执行时间排序
     */
    public List<CreatorWorkflowStepResponse> listSteps(String taskId, String sessionId) {
        CreatorWorkflowSessionRecord sessionRecord = getSessionRecord(taskId, sessionId);
        return creatorWorkflowMapper.listSteps(sessionRecord.getSessionId())
                .stream()
                .map(this::toStepResponse)
                .toList();
    }

    /**
     * 查询工作流会话的 LLM Token 用量统计（按模型、按步骤维度汇总）。
     *
     * @param taskId    创作任务ID，用于权限校验
     * @param sessionId 工作流会话ID
     * @return 用量统计响应，含各步骤的输入/输出 Token 明细
     */
    public WorkflowUsageResponse getWorkflowUsage(String taskId, String sessionId) {
        CreatorWorkflowSessionRecord sessionRecord = getSessionRecord(taskId, sessionId);
        return llmApiUsageService.summarizeWorkflowSession(sessionRecord.getTaskId(), sessionRecord.getSessionId());
    }

    /**
     * 订阅工作流会话的 SSE 事件流，用于前端实时接收消息/步骤/状态变更推送。
     * <p>
     * 订阅时先回放历史消息（MESSAGE_CREATED 事件）、当前会话状态（SESSION_STATUS 事件）
     * 和心跳（HEARTBEAT 事件），确保前端在建立连接后立即获得完整上下文。
     * 后续的状态变更由 {@link CreatorWorkflowEventPublisher} 通过 SSE 通道实时推动。
     *
     * @param taskId    创作任务ID，用于权限校验
     * @param sessionId 工作流会话ID
     * @return SSE 发射器，前端通过 EventSource API 消费事件流
     */
    public SseEmitter subscribeEvents(String taskId, String sessionId) {
        CreatorWorkflowSessionRecord sessionRecord = getSessionRecord(taskId, sessionId);
        SseEmitter emitter = workflowEventPublisher.register(sessionRecord.getSessionId());

        creatorWorkflowMapper.listMessages(sessionRecord.getSessionId()).stream()
                .map(this::toMessageResponse)
                .forEach(message -> workflowEventPublisher.sendToEmitter(
                        emitter,
                        buildEvent(
                                sessionRecord.getTaskId(),
                                sessionRecord.getSessionId(),
                                CreatorWorkflowEventType.MESSAGE_CREATED,
                                message.sequenceNo(),
                                message
                        )
                ));
        workflowEventPublisher.sendToEmitter(
                emitter,
                buildEvent(
                        sessionRecord.getTaskId(),
                        sessionRecord.getSessionId(),
                        CreatorWorkflowEventType.SESSION_STATUS,
                        null,
                        buildSessionStatusPayload(sessionRecord)
                )
        );
        workflowEventPublisher.sendToEmitter(
                emitter,
                buildEvent(
                        sessionRecord.getTaskId(),
                        sessionRecord.getSessionId(),
                        CreatorWorkflowEventType.HEARTBEAT,
                        null,
                        payload("time", LocalDateTime.now().toString())
                )
        );
        return emitter;
    }

    /**
     * 用户在交互台中发送文本消息。
     * <p>
     * 状态约束：WAITING_USER_INPUT、FAILED 和 WAITING_CONFIRMATION 状态下可以追加消息，
     * 让用户在看到建议后补充修改要求；RUNNING/CONFIRMED/CANCELLED 状态拒绝追加，防止并发干扰。
     * 消息写入后自动将状态推进为 WAITING_USER_INPUT，等待用户下一轮操作。
     *
     * @param taskId    创作任务ID
     * @param sessionId 工作流会话ID
     * @param request   消息请求，含文本内容
     * @return 创建的消息记录
     */
    @Transactional
    public CreatorWorkflowMessageResponse sendMessage(String taskId,
                                                      String sessionId,
                                                      CreatorWorkflowMessageCreateRequest request) {
        CreatorWorkflowSessionRecord sessionRecord = getSessionRecordForUpdate(taskId, sessionId);
        ensureCanAppendMessage(sessionRecord);

        CreatorWorkflowMessageRecord messageRecord = appendMessage(
                sessionRecord.getSessionId(),
                CreatorWorkflowMessageRole.USER,
                request.content().trim(),
                CreatorWorkflowMessageContentType.TEXT,
                null,
                null
        );
        // 用户原话发生变化后，旧方案次数不再代表同一上下文；同时把新流程理解状态标记为待更新。
        creatorWorkflowMapper.resetPlanGenerationState(sessionRecord.getSessionId());
        creatorInteractiveMapper.markIntentAlignmentPending(sessionRecord.getTaskId());
        publishMessage(sessionRecord.getTaskId(), messageRecord);
        updateSessionStatus(
                sessionRecord.getTaskId(),
                sessionRecord.getSessionId(),
                CreatorWorkflowStatus.WAITING_USER_INPUT,
                null
        );
        return toMessageResponse(messageRecord);
    }

    /**
     * 让主 Agent 基于用户原话、补充资料和消息流说明当前理解。
     * 审查 Agent 在服务内部读取同一份原始上下文，只返回偏离位置；最终只保存主 Agent 的回复。
     */
    public CreatorWorkflowMessageResponse alignPrePublishIntent(String taskId, String sessionId) {
        CreatorWorkflowSessionRecord sessionRecord = getSessionRecord(taskId, sessionId);
        ensureCanAlignIntent(sessionRecord);
        List<CreatorMaterialRecord> materials = creatorTaskMapper.listMaterialsByTaskId(sessionRecord.getTaskId());
        if (materials.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "创作任务缺少可对齐材料");
        }

        sessionRecord = claimPrePublishExecution(sessionRecord);
        CreatorWorkflowStepRecord currentStep = startStep(
                sessionRecord,
                CreatorWorkflowStepType.AGENT_REASONING,
                "对齐创作想法",
                "主 Agent 说明当前理解，独立审查 Agent 只检查是否偏离用户原话。"
        );
        try {
            CreatorIntentAlignmentContext context = buildIntentAlignmentContext(sessionRecord, materials);
            CreatorIntentAlignmentOutput output;
            try (LlmUsageContext.UsageScope ignored = LlmUsageContext.openWorkflowStep(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    currentStep.getStepId(),
                    currentStep.getStepName(),
                    sessionRecord.getStage(),
                    "创作想法对齐"
            )) {
                output = creatorIntentAlignmentService.align(context);
            }
            String content = creatorIntentAlignmentService.render(output);
            completeStepSuccess(
                    sessionRecord,
                    currentStep,
                    "已完成本轮理解和偏离检查。",
                    content
            );
            currentStep = null;

            CreatorWorkflowMessageRecord messageRecord = appendAndPublishMessage(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    CreatorWorkflowMessageRole.AGENT,
                    content,
                    CreatorWorkflowMessageContentType.TEXT,
                    DETAIL_REF_TYPE_INTENT_ALIGNMENT,
                    null
            );
            creatorInteractiveMapper.markIntentAligned(sessionRecord.getTaskId(), content);
            updateSessionStatus(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    CreatorWorkflowStatus.WAITING_USER_INPUT,
                    null
            );
            return toMessageResponse(messageRecord);
        } catch (RuntimeException exception) {
            if (currentStep != null) {
                completeStepFailure(sessionRecord, currentStep, exception);
            }
            String errorMessage = TextUtil.abbreviateWithSuffix(
                    exception.getMessage(),
                    ERROR_MESSAGE_MAX_LENGTH,
                    "..."
            );
            updateSessionStatus(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    CreatorWorkflowStatus.FAILED,
                    TextUtil.trimToDefault(errorMessage, "想法对齐失败")
            );
            throw exception;
        }
    }

    /**
     * 执行发布前优化分析——工作流的核心编排方法。
     * <p>
     * 执行流程分为 5 个步骤（每个步骤都有前端可观测的 {@link CreatorWorkflowStepRecord}）：
     * <ol>
     *   <li><b>LOAD_CONTEXT</b>：读取任务材料，统计数量</li>
     *   <li><b>AGENT_REASONING 或 LLM_CALL</b>：根据运行期开关选择 Agent 模式或直连 LLM 模式生成建议。
     *        Agent 模式失败时自动回退到直连 LLM 模式（保证可用性优先）</li>
     *   <li><b>SAVE_RESULT</b>：将结构化建议挂到消息流中（RESULT_CARD 类型消息）</li>
     *   <li>状态推进：RUNNING → WAITING_CONFIRMATION，等待用户确认或修改</li>
     *   <li>推送 RESULT_READY 事件，通知前端展示建议卡片</li>
     * </ol>
     * <p>
     * 异常处理策略：任何步骤失败都会记录 FAILED 状态、向消息流注入错误消息、
     * 将 session 状态设为 FAILED，然后重新抛出异常让上层统一处理。
     * 这样做保证失败现场完整可追踪（步骤 + 消息 + 状态三者一致），同时不吞掉异常。
     * <p>
     * Agent 回退策略（步骤 2 内）：Agent 模式异常时先记录步骤失败，再新建一个
     * LLM_CALL 步骤走直连 LLM 链路。回退的语义是"Agent 增强能力不可用时，
     * 退化为基础 LLM 能力，保证用户至少能拿到可用的发布前建议"。
     *
     * @param taskId    创作任务ID
     * @param sessionId 工作流会话ID
     * @param request   分析请求，含创作指导、偏好、标题风格等参数
     * @return 发布前优化建议响应，含结构化建议字段和解析状态
     */
    public CreatorSuggestionResponse analyzePrePublishWorkflow(String taskId,
                                                               String sessionId,
                                                               PrePublishAnalyzeRequest request) {
        CreatorWorkflowSessionRecord sessionRecord = getSessionRecord(taskId, sessionId);
        ensureCanAnalyze(sessionRecord);

        List<CreatorMaterialRecord> materials = creatorTaskMapper.listMaterialsByTaskId(sessionRecord.getTaskId());
        if (materials.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "创作任务缺少可分析材料");
        }
        ensureIntentReadyForPlan(sessionRecord);
        CreatorIntentAlignmentContext intentContext = buildIntentAlignmentContext(sessionRecord, materials);
        PrePublishAnalyzeRequest mergedRequest = mergeWorkflowGuidance(
                request,
                intentContext
        );
        String planContextHash = buildPlanContextHash(intentContext, mergedRequest);
        int successfulGenerationCount = resolveSuccessfulGenerationCount(sessionRecord, planContextHash);

        // 先由数据库原子抢占执行权，再开始画像初始化和 LLM 调用。
        // 前端禁用按钮只能减少重复点击，无法覆盖双标签页、网络重试或直接调用接口的并发请求；
        // 条件更新返回 0 时说明会话状态已被其他请求改变，必须在产生模型成本前立即拒绝本次请求。
        sessionRecord = claimPrePublishExecution(sessionRecord);
        if (successfulGenerationCount >= MAX_PLAN_GENERATION_COUNT) {
            stopPlanGenerationAndAskForClarification(sessionRecord, intentContext);
            log.warn("发布方案生成被三次门禁停止：taskId={}, sessionId={}, generationCount={}",
                    sessionRecord.getTaskId(), sessionRecord.getSessionId(), successfulGenerationCount);
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "同一上下文已经生成三次发布方案，请先回答 AI 的具体问题再继续。"
            );
        }

        long workflowStartNanos = System.nanoTime();
        log.info("发布方案生成开始：taskId={}, sessionId={}, generationNo={}, materialCount={}",
                sessionRecord.getTaskId(),
                sessionRecord.getSessionId(),
                successfulGenerationCount + 1,
                materials.size());
        CreatorWorkflowStepRecord currentStep = null;
        try {
            boolean agentModeEnabled = runtimeSettingService.isPrePublishAgentEnabled();
            log.info("发布方案生成模式：taskId={}, sessionId={}, agentModeEnabled={}",
                    sessionRecord.getTaskId(), sessionRecord.getSessionId(), agentModeEnabled);

            // 确保创作者画像存在（不存在时从历史偏好中 LLM 初始化）
            // 放在分析开始前，是为了让首次使用的用户也能体验到个性化建议
            // 虽然 ensureProfile 可能触发 LLM 调用，但未将其包装为步骤——
            // 因为画像初始化是一次性动作，不是每轮分析都需要展示给用户的操作
            creatorProfileService.ensureProfile(sessionRecord.getUserId());

            appendAndPublishMessage(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    CreatorWorkflowMessageRole.AGENT,
                    "开始执行发布前优化分析，本轮会先读取任务材料，再生成建议，最后等待你确认。",
                    CreatorWorkflowMessageContentType.TEXT,
                    null,
                    null
            );

            currentStep = startStep(
                    sessionRecord,
                    CreatorWorkflowStepType.LOAD_CONTEXT,
                    "读取创作任务材料",
                    "任务材料数量：" + materials.size()
            );
            completeStepSuccess(
                    sessionRecord,
                    currentStep,
                    "已读取 " + materials.size() + " 份用户主动提供的材料。",
                    null
            );
            appendAndPublishMessage(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    CreatorWorkflowMessageRole.AGENT,
                    "已完成任务材料读取，将把消息流中的补充要求合并到本轮分析提示中。",
                    CreatorWorkflowMessageContentType.TEXT,
                    null,
                    null
            );

            PrePublishSuggestionCandidate suggestionCandidate;
            // Agent 模式与直连 LLM 模式的切换由运行期设置控制，无需重启服务。
            // 当 Agent 模式生产环境出现不稳定时，可快速关闭开关回退到直连 LLM。
            if (agentModeEnabled) {
                currentStep = startStep(
                        sessionRecord,
                        CreatorWorkflowStepType.AGENT_REASONING,
                        "发布前优化 Agent 推理",
                        "基于任务材料、创作指导、创作者偏好和案例知识库执行 Agent 推理。"
                );
                try {
                    suggestionCandidate = generateSuggestionInWorkflowStep(sessionRecord, currentStep, mergedRequest, true);
                    completeStepSuccess(
                            sessionRecord,
                            currentStep,
                            "Agent 已返回发布前优化建议，解析状态：" + suggestionCandidate.parseStatus(),
                            suggestionCandidate.rawOutput()
                    );
                } catch (RuntimeException agentException) {
                    completeStepFailure(sessionRecord, currentStep, agentException);
                    log.warn("发布前优化 Agent 链路失败，准备回退旧直连 LLM。taskId={}, sessionId={}",
                            sessionRecord.getTaskId(), sessionRecord.getSessionId(), agentException);
                    currentStep = startStep(
                            sessionRecord,
                            CreatorWorkflowStepType.LLM_CALL,
                            "直连 LLM 回退生成建议",
                            "Agent 结构化输出或工具链路失败后，使用旧直连 LLM 链路保证发布前优化可用。"
                    );
                    suggestionCandidate = generateSuggestionInWorkflowStep(sessionRecord, currentStep, mergedRequest, false);
                    completeStepSuccess(
                            sessionRecord,
                            currentStep,
                            "旧直连 LLM 已返回发布前优化建议，解析状态：" + suggestionCandidate.parseStatus(),
                            suggestionCandidate.rawOutput()
                    );
                }
            } else {
                currentStep = startStep(
                        sessionRecord,
                        CreatorWorkflowStepType.LLM_CALL,
                        "生成发布前优化建议",
                        "基于任务材料、创作指导和工作流补充消息调用 LLM。"
                );
                suggestionCandidate = generateSuggestionInWorkflowStep(sessionRecord, currentStep, mergedRequest, false);
                completeStepSuccess(
                        sessionRecord,
                        currentStep,
                        "LLM 已返回发布前优化建议，解析状态：" + suggestionCandidate.parseStatus(),
                        suggestionCandidate.rawOutput()
                );
            }

            currentStep = startStep(
                    sessionRecord,
                    CreatorWorkflowStepType.LLM_CALL,
                    "检查发布方案是否偏离用户想法",
                    "审查 Agent 只对照用户原话标记偏离，不提供改写意见。"
            );
            CreatorIntentReviewResult reviewResult = reviewCandidateInWorkflowStep(
                    sessionRecord,
                    currentStep,
                    intentContext,
                    suggestionCandidate.rawOutput()
            );
            completeStepSuccess(
                    sessionRecord,
                    currentStep,
                    reviewResult.deviated() ? "发现发布方案存在偏离，主 Agent 将重答一次。" : "发布方案未发现实质偏离。",
                    creatorIntentAlignmentService.buildReviewReminder(reviewResult)
            );

            if (reviewResult.deviated() && !reviewResult.issues().isEmpty()) {
                currentStep = startStep(
                        sessionRecord,
                        CreatorWorkflowStepType.LLM_CALL,
                        "按用户原话重新生成发布方案",
                        "只使用用户原始上下文和审查指出的偏离位置，最多重答一次。"
                );
                suggestionCandidate = generateSuggestionInWorkflowStep(
                        sessionRecord,
                        currentStep,
                        mergedRequest,
                        false,
                        creatorIntentAlignmentService.buildReviewReminder(reviewResult)
                );
                completeStepSuccess(
                        sessionRecord,
                        currentStep,
                        "主 Agent 已基于用户原话完成一次重答。",
                        suggestionCandidate.rawOutput()
                );

                currentStep = startStep(
                        sessionRecord,
                        CreatorWorkflowStepType.LLM_CALL,
                        "复查重答后的发布方案",
                        "确认最终候选没有继续偏离用户原话。"
                );
                CreatorIntentReviewResult retryReview = reviewCandidateInWorkflowStep(
                        sessionRecord,
                        currentStep,
                        intentContext,
                        suggestionCandidate.rawOutput()
                );
                completeStepSuccess(
                        sessionRecord,
                        currentStep,
                        retryReview.deviated() ? "重答后仍有偏离，停止保存并向用户问清楚。" : "重答后的方案已通过偏离检查。",
                        creatorIntentAlignmentService.buildReviewReminder(retryReview)
                );
                currentStep = null;
                if (retryReview.deviated() && !retryReview.issues().isEmpty()) {
                    stopPlanAfterRepeatedDeviation(sessionRecord, retryReview);
                }
            }

            currentStep = startStep(
                    sessionRecord,
                    CreatorWorkflowStepType.SAVE_RESULT,
                    "保存建议结果消息",
                    "把结构化建议挂到当前工作流会话，等待用户确认。"
            );
            CreatorSuggestionResponse suggestionResponse = prePublishSuggestionService.saveSuggestion(
                    suggestionCandidate,
                    sessionRecord.getSessionId()
            );
            appendAndPublishMessage(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    CreatorWorkflowMessageRole.RESULT,
                    "已生成发布前优化建议，建议先检查标题、简介和标签，再点击采用本轮建议。",
                    CreatorWorkflowMessageContentType.RESULT_CARD,
                    DETAIL_REF_TYPE_SUGGESTION,
                    suggestionResponse.suggestionId()
            );
            completeStepSuccess(
                    sessionRecord,
                    currentStep,
                    "建议结果消息已保存，suggestionId=" + suggestionResponse.suggestionId(),
                    null
            );
            currentStep = null;

            int nextGenerationCount = successfulGenerationCount + 1;
            creatorWorkflowMapper.updatePlanGenerationState(
                    sessionRecord.getSessionId(),
                    planContextHash,
                    nextGenerationCount
            );
            sessionRecord.setPlanContextHash(planContextHash);
            sessionRecord.setPlanGenerationCount(nextGenerationCount);

            updateSessionStatus(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    CreatorWorkflowStatus.WAITING_CONFIRMATION,
                    null
            );
            publishEvent(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    CreatorWorkflowEventType.RESULT_READY,
                    null,
                    payload("suggestionId", suggestionResponse.suggestionId(), "parseStatus", suggestionResponse.parseStatus())
            );
            log.info("发布方案生成完成：taskId={}, sessionId={}, suggestionId={}, parseStatus={}, "
                            + "generationCount={}, elapsedMs={}",
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    suggestionResponse.suggestionId(),
                    suggestionResponse.parseStatus(),
                    nextGenerationCount,
                    elapsedMillis(workflowStartNanos));
            return suggestionResponse;
        } catch (RuntimeException exception) {
            if (exception instanceof PlanClarificationRequiredException) {
                log.warn("发布方案生成停止并等待用户澄清：taskId={}, sessionId={}, elapsedMs={}, reason={}",
                        sessionRecord.getTaskId(),
                        sessionRecord.getSessionId(),
                        elapsedMillis(workflowStartNanos),
                        exception.getMessage());
                throw exception;
            }
            log.error("发布方案生成失败：taskId={}, sessionId={}, stepId={}, stepName={}, elapsedMs={}",
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    currentStep == null ? null : currentStep.getStepId(),
                    currentStep == null ? null : currentStep.getStepName(),
                    elapsedMillis(workflowStartNanos),
                    exception);
            if (currentStep != null) {
                completeStepFailure(sessionRecord, currentStep, exception);
            }
            String errorMessage = TextUtil.abbreviateWithSuffix(
                    exception.getMessage(),
                    ERROR_MESSAGE_MAX_LENGTH,
                    "..."
            );
            appendAndPublishMessage(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    CreatorWorkflowMessageRole.AGENT,
                    "发布前优化分析失败：" + TextUtil.trimToDefault(errorMessage, "未知错误"),
                    CreatorWorkflowMessageContentType.ERROR,
                    null,
                    null
            );
            updateSessionStatus(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    CreatorWorkflowStatus.FAILED,
                    TextUtil.trimToDefault(errorMessage, "发布前优化分析失败")
            );
            throw exception;
        }
    }

    /**
     * 根据已确认的创意方向生成可编辑的文稿草稿。
     * <p>
     * 前置校验：
     * <ul>
     *   <li>阶段必须是 PRE_PUBLISH</li>
     *   <li>任务已有材料不为空</li>
     *   <li>任务中尚未存在较完整文稿/字幕（内容长度 >= {@link #FULL_SCRIPT_MIN_LENGTH}）——
     *       防止覆盖用户已有真实文稿</li>
     * </ul>
     * <p>
     * 生成流程：
     * <ol>
     *   <li>LLM_CALL 步骤：拼接任务材料 + 用户消息流补充要求 + 额外要求，生成文稿草稿</li>
     *   <li>SAVE_RESULT 步骤：将 AI 补全内容以 MANUSCRIPT 类型写入任务材料表</li>
     *   <li>状态推进为 WAITING_USER_INPUT，用户可以继续补充修改要求或进入下一步</li>
     * </ol>
     * <p>
     * 为什么不使用覆盖整个方法的事务：方法中包含 LLM 调用，长事务会长期持有会话行锁。
     * 进入 RUNNING 前已经通过条件更新完成短暂的原子抢占，后续写入与发布前分析保持相同的可追踪策略。
     * <p>
     * 为什么先检查是否有完整文稿：文稿草稿是"从大纲/创意点子扩写口播稿"，如果用户已经上传
     * 了完整的口播脚本或字幕，扩写不仅多余而且可能覆盖用户的真实创作内容。
     *
     * @param taskId    创作任务ID
     * @param sessionId 工作流会话ID
     * @param request   文稿草稿请求，含额外要求
     * @return 文稿草稿响应，含生成的文稿全文和关联消息
     */
    public PrePublishDraftResponse generatePrePublishManuscriptDraft(String taskId,
                                                                     String sessionId,
                                                                     PrePublishDraftRequest request) {
        CreatorWorkflowSessionRecord sessionRecord = getSessionRecord(taskId, sessionId);
        ensureCanGeneratePrePublishDraft(sessionRecord);

        CreatorTaskRecord taskRecord = getTaskRecord(sessionRecord.getTaskId());
        List<CreatorMaterialRecord> materials = creatorTaskMapper.listMaterialsByTaskId(sessionRecord.getTaskId());
        if (materials.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "创作任务缺少可扩写材料");
        }
        if (hasFullScriptMaterial(materials) || hasGeneratedManuscriptDraft(materials)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前任务已有可用文稿或 AI 草稿，请直接生成发布方案");
        }

        sessionRecord = claimPrePublishExecution(sessionRecord);
        CreatorWorkflowStepRecord currentStep = null;
        try {
            appendAndPublishMessage(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    CreatorWorkflowMessageRole.AGENT,
                    "我会先根据已确认的创意方向和你的补充要求，补一版可编辑文稿草稿。",
                    CreatorWorkflowMessageContentType.TEXT,
                    null,
                    null
            );

            currentStep = startStep(
                    sessionRecord,
                    CreatorWorkflowStepType.LLM_CALL,
                    "生成可编辑文稿草稿",
                    "基于当前任务材料、创意方向和用户补充消息生成文稿草稿。"
            );
            String draftContent = generateManuscriptDraftInWorkflowStep(
                    sessionRecord,
                    currentStep,
                    taskRecord,
                    materials,
                    request
            );
            completeStepSuccess(
                    sessionRecord,
                    currentStep,
                    "已生成文稿草稿，约 " + draftContent.length() + " 字。",
                    draftContent
            );
            currentStep = null;

            currentStep = startStep(
                    sessionRecord,
                    CreatorWorkflowStepType.SAVE_RESULT,
                    "保存文稿草稿",
                    "把 AI 补全内容写入任务材料，后续发布方案会读取这份文稿。"
            );
            CreatorMaterialRecord materialRecord = new CreatorMaterialRecord();
            materialRecord.setTaskId(sessionRecord.getTaskId());
            materialRecord.setMaterialType(CreatorMaterialType.MANUSCRIPT.name());
            materialRecord.setContent(draftContent);
            creatorTaskMapper.upsertMaterial(materialRecord);
            CreatorWorkflowMessageRecord messageRecord = appendAndPublishMessage(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    CreatorWorkflowMessageRole.AGENT,
                    "已把 AI 补全的文稿草稿写入任务材料。你可以继续补充修改要求，也可以直接生成发布方案。",
                    CreatorWorkflowMessageContentType.TEXT,
                    null,
                    null
            );
            completeStepSuccess(
                    sessionRecord,
                    currentStep,
                    "文稿草稿已保存为 " + CreatorMaterialType.MANUSCRIPT.name() + " 材料。",
                    null
            );
            currentStep = null;

            updateSessionStatus(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    CreatorWorkflowStatus.WAITING_USER_INPUT,
                    null
            );
            return new PrePublishDraftResponse(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    CreatorMaterialType.MANUSCRIPT.name(),
                    draftContent,
                    toMessageResponse(messageRecord)
            );
        } catch (RuntimeException exception) {
            if (currentStep != null) {
                completeStepFailure(sessionRecord, currentStep, exception);
            }
            String errorMessage = TextUtil.abbreviateWithSuffix(
                    exception.getMessage(),
                    ERROR_MESSAGE_MAX_LENGTH,
                    "..."
            );
            appendAndPublishMessage(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    CreatorWorkflowMessageRole.AGENT,
                    "文稿草稿生成失败：" + TextUtil.trimToDefault(errorMessage, "未知错误"),
                    CreatorWorkflowMessageContentType.ERROR,
                    null,
                    null
            );
            updateSessionStatus(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    CreatorWorkflowStatus.FAILED,
                    TextUtil.trimToDefault(errorMessage, "文稿草稿生成失败")
            );
            throw exception;
        }
    }

    /**
     * 用户确认采用某条发布前优化建议。
     * <p>
     * 状态约束：
     * <ul>
     *   <li>仅 WAITING_CONFIRMATION 状态可确认（其他状态拒绝并返回 400）</li>
     *   <li>若已确认过（CONFIRMED 状态）且 suggestionId 相同 → 幂等返回已有会话</li>
     *   <li>若已确认过但 suggestionId 不同 → 拒绝，防止一个会话多次确认不同建议</li>
     * </ul>
     * <p>
     * 确认后的副作用链：
     * <ol>
     *   <li>更新 session 状态为 CONFIRMED + 记录 confirmedResultId</li>
     *   <li>推进任务状态为 PRE_PUBLISH_ANALYZED</li>
     *   <li>记录用户采纳偏好到 {@link CreatorPreferenceService}（提取标题风格特征）</li>
     *   <li>写入创作者画像事件流水（{@link CreatorProfileService}），触发画像更新检查</li>
     * </ol>
     * <p>
     * 为什么偏好记录和画像事件的异常被吞掉：用户已明确采纳建议，偏好持久化是辅助功能，
     * 不应因为画像/偏好服务异常就让主流程的确认操作失败返回错误。
     *
     * @param taskId    创作任务ID
     * @param sessionId 工作流会话ID
     * @param request   确认请求，含 suggestionId
     * @return 更新后的工作流会话响应
     */
    @Transactional
    public CreatorWorkflowSessionResponse confirmPrePublishSuggestion(String taskId,
                                                                      String sessionId,
                                                                      CreatorWorkflowConfirmRequest request) {
        if (!creatorMediaProperties.isEnabled()) {
            // 确认会推进任务主状态，必须先阻止未启用试映时跳入旧的反馈链路。
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "发布前试映能力未启用，不能确认发布方案。"
            );
        }
        CreatorWorkflowSessionRecord sessionRecord = getSessionRecordForUpdate(taskId, sessionId);
        CreatorSuggestionRecord suggestionRecord = creatorSuggestionMapper.findBySuggestionId(request.suggestionId().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "发布前优化建议不存在"));
        if (!sessionRecord.getTaskId().equals(suggestionRecord.getTaskId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能确认当前任务下的发布前优化建议");
        }

        if (CreatorWorkflowStatus.CONFIRMED.name().equals(sessionRecord.getStatus())) {
            if (request.suggestionId().trim().equals(sessionRecord.getConfirmedResultId())) {
                return toSessionResponse(sessionRecord);
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前工作流会话已经确认过其他建议");
        }

        if (!CreatorWorkflowStatus.WAITING_CONFIRMATION.name().equals(sessionRecord.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前工作流会话还没有可确认的建议");
        }

        CreatorWorkflowStepRecord stepRecord = startStep(
                sessionRecord,
                CreatorWorkflowStepType.CONFIRM_RESULT,
                "确认发布前优化建议",
                "用户确认 suggestionId=" + request.suggestionId().trim()
        );
        creatorWorkflowMapper.updateSessionConfirmation(
                sessionRecord.getSessionId(),
                CreatorWorkflowStatus.CONFIRMED.name(),
                request.suggestionId().trim()
        );
        creatorTaskMapper.updateTaskStatus(sessionRecord.getTaskId(), CreatorTaskStatus.PRE_PUBLISH_ANALYZED.name());
        appendAndPublishMessage(
                sessionRecord.getTaskId(),
                sessionRecord.getSessionId(),
                CreatorWorkflowMessageRole.SYSTEM,
                "已采用本轮发布前优化建议，可以进入成片试映阶段。",
                CreatorWorkflowMessageContentType.TEXT,
                null,
                null
        );
        completeStepSuccess(
                sessionRecord,
                stepRecord,
                "任务状态已推进为 " + CreatorTaskStatus.PRE_PUBLISH_ANALYZED.name(),
                null
        );

        // 将用户"采用"行为写入偏好和事件流水，让后续发布前优化能参考用户实际偏好的标题风格
        try {
            CreatorTaskRecord taskRecord = getTaskRecord(taskId);
            String styleDescription = extractTitleStyleFromSuggestion(suggestionRecord);
            if (TextUtil.hasText(styleDescription)) {
                creatorPreferenceService.recordAdoptionFeedback(
                        taskRecord.getUserId(),
                        taskId,
                        "ADOPTED",
                        "采用发布前优化建议：" + styleDescription
                );
            }
            // 记录事件到创作者事件流水（方案一：画像更新的信号源）
            creatorProfileService.recordEvent(
                    taskRecord.getUserId(),
                    "SUGGESTION_ADOPTED",
                    taskId,
                    java.util.Map.of("suggestionId", suggestionRecord.getSuggestionId(),
                            "styleDescription", TextUtil.trimToDefault(styleDescription, "已采用"))
            );
            // 检查是否达到画像更新阈值
            creatorProfileService.tryTriggerProfileUpdate(taskRecord.getUserId());
        } catch (Exception ignored) {
            // 偏好记录和事件记录不影响主流程
        }

        CreatorWorkflowSessionRecord updatedSession = creatorWorkflowMapper.findSession(
                        sessionRecord.getTaskId(),
                        sessionRecord.getSessionId()
                )
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "工作流会话确认后读取失败"));
        publishSessionStatus(updatedSession);
        return toSessionResponse(updatedSession);
    }

    /**
     * 创建新的发布前工作流会话并加载上下文消息。
     * <p>
     * 初始化流程：新建 session（状态=CONTEXT_LOADING）→ 注入任务材料的上下文消息 →
     * 状态推进为 WAITING_USER_INPUT。返回数据库中最新的 session 快照（含自增 ID）。
     */
    private CreatorWorkflowSessionResponse createPrePublishSession(CreatorTaskRecord taskRecord,
                                                                   List<CreatorMaterialRecord> materials,
                                                                   CreatorWorkflowStartRequest request) {
        CreatorWorkflowSessionRecord sessionRecord = new CreatorWorkflowSessionRecord();
        sessionRecord.setSessionId(UUID.randomUUID().toString());
        sessionRecord.setTaskId(taskRecord.getTaskId());
        sessionRecord.setStage(CreatorWorkflowStage.PRE_PUBLISH.name());
        sessionRecord.setStatus(CreatorWorkflowStatus.CONTEXT_LOADING.name());
        sessionRecord.setUserId(normalizeUserId(request == null ? null : request.userId(), taskRecord.getUserId()));
        sessionRecord.setPlanGenerationCount(0);
        creatorWorkflowMapper.insertSession(sessionRecord);

        appendPrePublishContextMessages(sessionRecord.getSessionId(), taskRecord, materials);
        creatorWorkflowMapper.updateSessionStatus(
                sessionRecord.getSessionId(),
                CreatorWorkflowStatus.WAITING_USER_INPUT.name(),
                null
        );

        return creatorWorkflowMapper.findSession(taskRecord.getTaskId(), sessionRecord.getSessionId())
                .map(this::toSessionResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "工作流会话创建后读取失败"));
    }

    /**
     * 向新创建的发布前工作流会话注入上下文消息。
     * <p>
     * 消息注入顺序和角色设计：
     * <ol>
     *   <li>SYSTEM 消息："已进入发布前优化阶段"——阶段标识</li>
     *   <li>SYSTEM 消息：显示任务名称</li>
     *   <li>SYSTEM 消息 × N：每条材料生成一条 MATERIAL_SUMMARY 类型的摘要消息，
     *       前端通过(detailRefType=MATERIAL, detailRefId=材料ID)查询原始内容</li>
     *   <li>AGENT 消息：首句引导语，告知用户接下来的步骤</li>
     * </ol>
     * 为什么用 SYSTEM 角色传递材料摘要而不是直接嵌入原始内容：
     * 消息流是面向用户的，原始材料可能很长（如字幕文件），直接展示会撑坏前端。
     * 摘要消息（长度 + 类型）让用户知道加载了什么，需要时点击查看详情。
     */
    private void appendPrePublishContextMessages(String sessionId,
                                                 CreatorTaskRecord taskRecord,
                                                 List<CreatorMaterialRecord> materials) {
        appendMessage(
                sessionId,
                CreatorWorkflowMessageRole.SYSTEM,
                "已进入发布前优化阶段。",
                CreatorWorkflowMessageContentType.TEXT,
                null,
                null
        );
        appendMessage(
                sessionId,
                CreatorWorkflowMessageRole.SYSTEM,
                "已读取任务：" + taskRecord.getTaskName() + "。",
                CreatorWorkflowMessageContentType.TEXT,
                null,
                null
        );

        for (CreatorMaterialRecord material : materials) {
            String materialName = toChineseMaterialName(material.getMaterialType());
            appendMessage(
                    sessionId,
                    CreatorWorkflowMessageRole.SYSTEM,
                    "已加载" + materialName + "，约 " + material.getContent().length() + " 字，点击查看详情。",
                    CreatorWorkflowMessageContentType.MATERIAL_SUMMARY,
                    DETAIL_REF_TYPE_MATERIAL,
                    String.valueOf(material.getId())
            );
        }

        appendMessage(
                sessionId,
                CreatorWorkflowMessageRole.AGENT,
                "我将先提炼内容卖点，再检查标题、简介和标签的表达风险。",
                CreatorWorkflowMessageContentType.TEXT,
                null,
                null
        );
    }

    /**
     * 合并用户在消息流中主动输入的内容到分析请求的创作指导中。
     * <p>
     * 为什么需要合并而不是直接使用 request.customGuidance：用户在发送消息后触发分析时，
     * 其追加要求已经存在于消息流中（通过 sendMessage API 写入），但这些消息不在分析请求体里。
     * 此方法将它们提取出来合并到 customGuidance 中，保证 Agent/LLM 能看到用户的实时补充。
     * <p>
     * 合并后的指导内容截断到 {@link #WORKFLOW_GUIDANCE_MAX_LENGTH}，防止提示词过长。
     */
    private PrePublishAnalyzeRequest mergeWorkflowGuidance(PrePublishAnalyzeRequest request,
                                                            CreatorIntentAlignmentContext intentContext) {
        PrePublishAnalyzeRequest safeRequest = request == null
                ? new PrePublishAnalyzeRequest(null, null, null, null, null, null)
                : request;
        StringBuilder builder = new StringBuilder("用户已经提供并完成对齐的补充上下文：\n")
                .append(intentContext.planContext())
                .append("\n");
        if (TextUtil.hasText(safeRequest.customGuidance())) {
            builder.append("\n本次界面额外指导：\n")
                    .append(safeRequest.customGuidance().trim())
                    .append("\n");
        }

        String mergedGuidance = TextUtil.trimToNull(TextUtil.abbreviateWithSuffix(
                builder.toString(),
                PLAN_GUIDANCE_MAX_LENGTH,
                "\n[用户上下文过长，已截断用于发布方案生成]"
        ));
        return new PrePublishAnalyzeRequest(
                mergedGuidance,
                safeRequest.creatorPreference(),
                safeRequest.titleStyle(),
                safeRequest.extraRequirement(),
                safeRequest.preferenceMode(),
                safeRequest.analysisStrategy()
        );
    }

    /**
     * 统一整理主 Agent、审查 Agent 和发布方案生成器读取的用户上下文。
     * 系统提示词不会写入这里，也不会进入消息历史，避免后续轮次把系统规则当成用户事实。
     */
    private CreatorIntentAlignmentContext buildIntentAlignmentContext(CreatorWorkflowSessionRecord sessionRecord,
                                                                       List<CreatorMaterialRecord> materials) {
        StringBuilder sourceBuilder = new StringBuilder();
        StringBuilder planBuilder = new StringBuilder();
        InteractiveSessionRecord interactiveSession = creatorInteractiveMapper
                .findSessionByTaskId(sessionRecord.getTaskId())
                .orElse(null);
        if (interactiveSession != null) {
            sourceBuilder.append("【用户最初的创作想法】\n")
                    .append(TextUtil.trimToDefault(interactiveSession.getIdea(), "（未记录）"))
                    .append("\n\n【用户选择的视频类型】\n")
                    .append(TextUtil.trimToDefault(interactiveSession.getVideoType(), "未分类"))
                    .append("\n");
            // 最初想法已经作为任务材料进入发布方案生成器，这里只补它拿不到的视频类型和背景资料。
            planBuilder.append("【用户选择的视频类型】\n")
                    .append(TextUtil.trimToDefault(interactiveSession.getVideoType(), "未分类"))
                    .append("\n");
            if (TextUtil.hasText(interactiveSession.getBackgroundContext())) {
                String backgroundContext = TextUtil.abbreviateWithSuffix(
                        interactiveSession.getBackgroundContext().trim(),
                        PLAN_GUIDANCE_MAX_LENGTH,
                        "\n[补充资料过长，已截断]"
                );
                sourceBuilder.append("\n【用户主动提供的补充资料】\n")
                        .append(backgroundContext)
                        .append("\n");
                planBuilder.append("\n【用户主动提供的补充资料】\n")
                        .append(backgroundContext)
                        .append("\n");
            }
        } else {
            CreatorTaskRecord taskRecord = getTaskRecord(sessionRecord.getTaskId());
            sourceBuilder.append("【任务名称】\n")
                    .append(TextUtil.trimToDefault(taskRecord.getTaskName(), "未命名任务"))
                    .append("\n\n【视频类型】\n")
                    .append(TextUtil.trimToDefault(taskRecord.getVideoType(), "未分类"))
                    .append("\n");
            planBuilder.append(sourceBuilder);
        }

        sourceBuilder.append("\n【任务材料】\n");
        for (CreatorMaterialRecord material : materials) {
            String content = TextUtil.trimToNull(material.getContent());
            if (content == null) {
                continue;
            }
            // 交互式会话已经保留用户最初想法时，不再重复塞入自动生成的同一份想法材料。
            if (interactiveSession != null && content.startsWith("【用户原始创作想法】")) {
                continue;
            }
            sourceBuilder.append("- ")
                    .append(toChineseMaterialName(material.getMaterialType()))
                    .append("：\n")
                    .append(TextUtil.abbreviateWithSuffix(
                            content,
                            INTENT_MATERIAL_MAX_LENGTH,
                            "\n[材料过长，已截断]"
                    ))
                    .append("\n");
        }

        List<String> userMessages = creatorWorkflowMapper.listMessages(sessionRecord.getSessionId())
                .stream()
                .filter(message -> CreatorWorkflowMessageRole.USER.name().equals(message.getRole()))
                .map(CreatorWorkflowMessageRecord::getContent)
                .filter(TextUtil::hasText)
                .map(String::trim)
                .toList();
        if (!userMessages.isEmpty()) {
            sourceBuilder.append("\n【用户后续补充的原话，按时间顺序】\n");
            planBuilder.append("\n【用户后续补充的原话，按时间顺序】\n");
            for (String userMessage : userMessages) {
                sourceBuilder.append("- ").append(userMessage).append("\n");
                planBuilder.append("- ").append(userMessage).append("\n");
            }
        }

        return new CreatorIntentAlignmentContext(
                TextUtil.abbreviateWithSuffix(
                        sourceBuilder.toString().trim(),
                        INTENT_SOURCE_MAX_LENGTH,
                        "\n[对齐上下文过长，已截断]"
                ),
                TextUtil.abbreviateWithSuffix(
                        planBuilder.toString().trim(),
                        PLAN_GUIDANCE_MAX_LENGTH,
                        "\n[发布方案上下文过长，已截断]"
                )
        );
    }

    /**
     * 同一份用户上下文和界面参数生成稳定哈希，只有它们都没变时才累计重试次数。
     */
    static String buildPlanContextHash(CreatorIntentAlignmentContext context,
                                       PrePublishAnalyzeRequest request) {
        String source = String.join("\u001f",
                TextUtil.trimToDefault(context.sourceContext(), ""),
                TextUtil.trimToDefault(request.customGuidance(), ""),
                TextUtil.trimToDefault(request.creatorPreference(), ""),
                TextUtil.trimToDefault(request.titleStyle(), ""),
                TextUtil.trimToDefault(request.extraRequirement(), ""),
                TextUtil.trimToDefault(request.preferenceMode(), ""),
                TextUtil.trimToDefault(request.analysisStrategy(), "")
        );
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            // Java 运行时必须提供 SHA-256；如果环境损坏，直接暴露错误，不能用不稳定哈希绕过门禁。
            throw new IllegalStateException("当前 Java 环境不支持 SHA-256", exception);
        }
    }

    static int resolveSuccessfulGenerationCount(CreatorWorkflowSessionRecord sessionRecord,
                                                String contextHash) {
        if (!contextHash.equals(sessionRecord.getPlanContextHash())) {
            return 0;
        }
        return Math.max(0, sessionRecord.getPlanGenerationCount() == null
                ? 0
                : sessionRecord.getPlanGenerationCount());
    }

    private CreatorIntentReviewResult reviewCandidateInWorkflowStep(CreatorWorkflowSessionRecord sessionRecord,
                                                                     CreatorWorkflowStepRecord stepRecord,
                                                                     CreatorIntentAlignmentContext context,
                                                                     String candidate) {
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.openWorkflowStep(
                sessionRecord.getTaskId(),
                sessionRecord.getSessionId(),
                stepRecord.getStepId(),
                stepRecord.getStepName(),
                sessionRecord.getStage(),
                "创作意图偏离审查"
        )) {
            return creatorIntentAlignmentService.review(context, candidate);
        }
    }

    /**
     * 达到三次门禁后让主 Agent 先问清楚分歧，并把会话放回等待用户输入状态。
     */
    private void stopPlanGenerationAndAskForClarification(CreatorWorkflowSessionRecord sessionRecord,
                                                           CreatorIntentAlignmentContext context) {
        CreatorWorkflowStepRecord stepRecord = startStep(
                sessionRecord,
                CreatorWorkflowStepType.AGENT_REASONING,
                "停止重复生成并追问分歧",
                "同一上下文已经成功生成三次发布方案，本轮禁止继续生成。"
        );
        try {
            CreatorIntentAlignmentOutput output;
            try (LlmUsageContext.UsageScope ignored = LlmUsageContext.openWorkflowStep(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    stepRecord.getStepId(),
                    stepRecord.getStepName(),
                    sessionRecord.getStage(),
                    "发布方案三次门禁澄清"
            )) {
                output = creatorIntentAlignmentService.clarifyAfterPlanLimit(context);
            }
            String content = creatorIntentAlignmentService.render(output);
            completeStepSuccess(sessionRecord, stepRecord, "已停止重复生成并提出具体问题。", content);
            appendAndPublishMessage(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    CreatorWorkflowMessageRole.AGENT,
                    content,
                    CreatorWorkflowMessageContentType.TEXT,
                    DETAIL_REF_TYPE_INTENT_ALIGNMENT,
                    null
            );
            updateSessionStatus(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    CreatorWorkflowStatus.WAITING_USER_INPUT,
                    null
            );
        } catch (RuntimeException exception) {
            completeStepFailure(sessionRecord, stepRecord, exception);
            updateSessionStatus(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    CreatorWorkflowStatus.FAILED,
                    TextUtil.trimToDefault(exception.getMessage(), "发布方案重试门禁澄清失败")
            );
            throw exception;
        }
    }

    /**
     * 发布方案重答后仍偏离时不保存候选，转回想法对齐等待用户补充。
     */
    private void stopPlanAfterRepeatedDeviation(CreatorWorkflowSessionRecord sessionRecord,
                                                CreatorIntentReviewResult reviewResult) {
        String content = creatorIntentAlignmentService.render(
                creatorIntentAlignmentService.clarifyDeviation(reviewResult)
        );
        appendAndPublishMessage(
                sessionRecord.getTaskId(),
                sessionRecord.getSessionId(),
                CreatorWorkflowMessageRole.AGENT,
                content,
                CreatorWorkflowMessageContentType.TEXT,
                DETAIL_REF_TYPE_INTENT_ALIGNMENT,
                null
        );
        creatorInteractiveMapper.markIntentAlignmentPending(sessionRecord.getTaskId());
        updateSessionStatus(
                sessionRecord.getTaskId(),
                sessionRecord.getSessionId(),
                CreatorWorkflowStatus.WAITING_USER_INPUT,
                null
        );
        throw new PlanClarificationRequiredException("发布方案重答后仍未对齐，请先回答 AI 的具体问题。");
    }

    /**
     * 在 LLM Token 用量追踪上下文中生成发布前优化建议。
     * <p>
     * 使用 try-with-resources 自动管理 {@link LlmUsageContext.UsageScope}，
     * 确保无论成功/失败/异常，本步骤的 Token 消耗都会被正确归入工作流用量统计。
     * agentMode=true 走 Agent 推理路径（结构化的多步 ReAct），
     * agentMode=false 走直连 LLM 路径（一次 chat 调用返回建议 JSON）。
     */
    private PrePublishSuggestionCandidate generateSuggestionInWorkflowStep(CreatorWorkflowSessionRecord sessionRecord,
                                                                           CreatorWorkflowStepRecord stepRecord,
                                                                           PrePublishAnalyzeRequest request,
                                                                           boolean agentMode) {
        return generateSuggestionInWorkflowStep(sessionRecord, stepRecord, request, agentMode, null);
    }

    /**
     * 偏离提醒作为内部控制信息单独传递，不能拼进用户的 customGuidance。
     */
    private PrePublishSuggestionCandidate generateSuggestionInWorkflowStep(CreatorWorkflowSessionRecord sessionRecord,
                                                                           CreatorWorkflowStepRecord stepRecord,
                                                                           PrePublishAnalyzeRequest request,
                                                                           boolean agentMode,
                                                                           String deviationReminder) {
        String scene = agentMode ? "发布前优化 Agent 推理" : "发布前优化 LLM 回退";
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.openWorkflowStep(
                sessionRecord.getTaskId(),
                sessionRecord.getSessionId(),
                stepRecord.getStepId(),
                stepRecord.getStepName(),
                sessionRecord.getStage(),
                scene
        )) {
            if (agentMode) {
                return prePublishSuggestionService.generateSuggestionCandidateByAgent(
                        sessionRecord.getTaskId(),
                        request
                );
            }
            return prePublishSuggestionService.generateSuggestionCandidate(
                    sessionRecord.getTaskId(),
                    request,
                    deviationReminder
            );
        }
    }

    /**
     * 在 Token 用量追踪上下文中生成文稿草稿。
     * <p>
     * 通过 {@link LLMService#chat} 直连 LLM 生成文稿，不使用 Agent 路径——
     * 文稿生成是单轮文本生成任务，不需要工具调用或多步推理。
     * 生成后自动添加"【AI 可编辑文稿草稿】"前缀并截断到 {@link #DRAFT_MATERIAL_MAX_LENGTH}，
     * 确保写入 materials 表的内容不会因过长导致存储或后续 Token 成本问题。
     */
    private String generateManuscriptDraftInWorkflowStep(CreatorWorkflowSessionRecord sessionRecord,
                                                         CreatorWorkflowStepRecord stepRecord,
                                                         CreatorTaskRecord taskRecord,
                                                         List<CreatorMaterialRecord> materials,
                                                         PrePublishDraftRequest request) {
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.openWorkflowStep(
                sessionRecord.getTaskId(),
                sessionRecord.getSessionId(),
                stepRecord.getStepId(),
                stepRecord.getStepName(),
                sessionRecord.getStage(),
                "发布前优化文稿草稿"
        )) {
            String rawOutput = llmService.chat(
                    buildManuscriptDraftSystemPrompt(),
                    buildManuscriptDraftUserPrompt(sessionRecord, taskRecord, materials, request)
            );
            String draftContent = TextUtil.trimToNull(rawOutput);
            if (draftContent == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "AI 未返回可用文稿草稿");
            }
            String markedDraftContent = AI_MANUSCRIPT_DRAFT_PREFIX + "\n" + draftContent;
            return TextUtil.abbreviateWithSuffix(
                    markedDraftContent,
                    DRAFT_MATERIAL_MAX_LENGTH,
                    "\n[文稿草稿过长，已截断保存]"
            );
        }
    }

    private String buildManuscriptDraftSystemPrompt() {
        return """
                你是 B 站内容创作者的发布前文稿助手。
                你的任务是根据用户已确认的创意方向、标题/简介草稿和补充要求，补一版可继续编辑的视频文稿草稿。
                不要输出 JSON，不要输出 Markdown 代码块，不要解释你的工作过程。
                草稿必须服务后续发布前优化：要能体现核心卖点、观众钩子、内容结构和风险边界。
                """;
    }

    private String buildManuscriptDraftUserPrompt(CreatorWorkflowSessionRecord sessionRecord,
                                                  CreatorTaskRecord taskRecord,
                                                  List<CreatorMaterialRecord> materials,
                                                  PrePublishDraftRequest request) {
        String extraRequirement = request == null ? null : request.extraRequirement();
        return """
                请为下面这期 B 站视频补一版可编辑文稿草稿。

                任务信息：
                - 任务名称：%s
                - 视频类型：%s

                用户在当前 AI 交互台里补充过的要求：
                %s

                本次草稿额外要求：
                %s

                已有任务材料：
                %s

                输出要求：
                1. 直接输出文稿草稿正文，不要输出解释。
                2. 文稿要分成「开场钩子」「主体段落」「结尾互动」三类段落，但不要写成表格。
                3. 如果已有材料只是创意大纲，就把它扩写成自然口播；不要假装用户已经提供完整成片数据。
                4. 不要编造真实播放量、评论、弹幕、竞品数据或未给出的事实。
                5. 标出仍需用户确认的占位内容，例如「【这里补充真实案例】」。
                6. 所有内容使用中文。
                """.formatted(
                TextUtil.trimToDefault(taskRecord.getTaskName(), "未命名任务"),
                TextUtil.trimToDefault(taskRecord.getVideoType(), "未分类"),
                buildWorkflowUserMessagePrompt(sessionRecord.getSessionId()),
                TextUtil.trimToDefault(extraRequirement, "无"),
                buildDraftMaterialPrompt(materials)
        );
    }

    private String buildWorkflowUserMessagePrompt(String sessionId) {
        List<String> userMessages = creatorWorkflowMapper.listMessages(sessionId)
                .stream()
                .filter(message -> CreatorWorkflowMessageRole.USER.name().equals(message.getRole()))
                .map(CreatorWorkflowMessageRecord::getContent)
                .filter(TextUtil::hasText)
                .map(String::trim)
                .toList();
        if (userMessages.isEmpty()) {
            return "无";
        }
        StringBuilder builder = new StringBuilder();
        for (String userMessage : userMessages) {
            builder.append("- ").append(userMessage).append("\n");
        }
        return TextUtil.abbreviateWithSuffix(
                builder.toString().trim(),
                WORKFLOW_GUIDANCE_MAX_LENGTH,
                "\n[用户补充要求过长，已截断]"
        );
    }

    private String buildDraftMaterialPrompt(List<CreatorMaterialRecord> materials) {
        StringBuilder builder = new StringBuilder();
        for (CreatorMaterialRecord material : materials) {
            builder.append("\n【")
                    .append(toChineseMaterialName(material.getMaterialType()))
                    .append("】\n")
                    .append(TextUtil.trimToDefault(
                            TextUtil.abbreviateWithSuffix(
                                    material.getContent(),
                                    DRAFT_CONTEXT_MATERIAL_MAX_LENGTH,
                                    "\n[材料过长，已截断用于草稿生成]"
                            ),
                            "（空）"
                    ))
                    .append("\n");
        }
        return builder.toString();
    }

    /**
     * 追加消息到数据库并立即通过 SSE 发布给前端。
     * <p>
     * 合并写入+发布为一步是为了减少样板代码：所有需要让前端实时看到消息的地方
     * （AGENT 进度提示、RESULT 建议卡片、ERROR 错误信息）都需要这两个动作绑定。
     */
    private CreatorWorkflowMessageRecord appendAndPublishMessage(String taskId,
                                                                 String sessionId,
                                                                 CreatorWorkflowMessageRole role,
                                                                 String content,
                                                                 CreatorWorkflowMessageContentType contentType,
                                                                 String detailRefType,
                                                                 String detailRefId) {
        CreatorWorkflowMessageRecord messageRecord = appendMessage(sessionId, role, content, contentType, detailRefType, detailRefId);
        publishMessage(taskId, messageRecord);
        return messageRecord;
    }

    private CreatorWorkflowMessageRecord appendMessage(String sessionId,
                                                       CreatorWorkflowMessageRole role,
                                                       String content,
                                                       CreatorWorkflowMessageContentType contentType,
                                                       String detailRefType,
                                                       String detailRefId) {
        CreatorWorkflowMessageRecord messageRecord = new CreatorWorkflowMessageRecord();
        messageRecord.setMessageId(UUID.randomUUID().toString());
        messageRecord.setSessionId(sessionId);
        messageRecord.setRole(role.name());
        messageRecord.setContent(content);
        messageRecord.setContentType(contentType.name());
        messageRecord.setDetailRefType(detailRefType);
        messageRecord.setDetailRefId(detailRefId);
        messageRecord.setSequenceNo(creatorWorkflowMapper.nextMessageSequence(sessionId));
        creatorWorkflowMapper.insertMessage(messageRecord);
        creatorWorkflowMapper.touchSession(sessionId);
        return creatorWorkflowMapper.findMessageByMessageId(messageRecord.getMessageId())
                .orElse(messageRecord);
    }

    /**
     * 在工作流中开始一个新步骤，写入数据库并向前端推送 STEP_STARTED 事件。
     * <p>
     * 步骤状态初始为 RUNNING，后续通过 {@link #completeStepSuccess} 或
     * {@link #completeStepFailure} 更新为 SUCCESS / FAILED。
     *
     * @param sessionRecord 当前会话记录
     * @param stepType      步骤类型（LOAD_CONTEXT / AGENT_REASONING / LLM_CALL / SAVE_RESULT / CONFIRM_RESULT）
     * @param stepName      步骤名称，用于前端时间轴展示
     * @param inputSummary  步骤输入摘要，描述本轮要做什么
     * @return 步骤记录（含生成的 stepId），供后续完成/失败时引用
     */
    private CreatorWorkflowStepRecord startStep(CreatorWorkflowSessionRecord sessionRecord,
                                                CreatorWorkflowStepType stepType,
                                                String stepName,
                                                String inputSummary) {
        CreatorWorkflowStepRecord stepRecord = new CreatorWorkflowStepRecord();
        stepRecord.setStepId(UUID.randomUUID().toString());
        stepRecord.setSessionId(sessionRecord.getSessionId());
        stepRecord.setStepType(stepType.name());
        stepRecord.setStepName(stepName);
        stepRecord.setStatus(CreatorWorkflowStepStatus.RUNNING.name());
        stepRecord.setInputSummary(inputSummary);
        // SSE 事件在数据库回查前就会发出，因此同时写入内存开始时间，让页面和日志立即拿到一致时间。
        stepRecord.setStartTime(LocalDateTime.now());
        creatorWorkflowMapper.insertStep(stepRecord);
        log.info("工作流步骤开始：taskId={}, sessionId={}, stepId={}, stepType={}, stepName={}",
                sessionRecord.getTaskId(),
                sessionRecord.getSessionId(),
                stepRecord.getStepId(),
                stepRecord.getStepType(),
                stepRecord.getStepName());
        // 步骤开始即推送——让前端立即看到时间轴上新增了一个正在执行的步骤节点
        publishEvent(
                sessionRecord.getTaskId(),
                sessionRecord.getSessionId(),
                CreatorWorkflowEventType.STEP_STARTED,
                null,
                buildStepPayload(stepRecord)
        );
        return stepRecord;
    }

    private void completeStepSuccess(CreatorWorkflowSessionRecord sessionRecord,
                                     CreatorWorkflowStepRecord stepRecord,
                                     String outputSummary,
                                     String rawOutput) {
        creatorWorkflowMapper.completeStepSuccess(
                stepRecord.getStepId(),
                CreatorWorkflowStepStatus.SUCCESS.name(),
                outputSummary,
                rawOutput
        );
        stepRecord.setStatus(CreatorWorkflowStepStatus.SUCCESS.name());
        stepRecord.setOutputSummary(outputSummary);
        stepRecord.setRawOutput(rawOutput);
        stepRecord.setEndTime(LocalDateTime.now());
        log.info("工作流步骤完成：taskId={}, sessionId={}, stepId={}, stepType={}, stepName={}, elapsedMs={}",
                sessionRecord.getTaskId(),
                sessionRecord.getSessionId(),
                stepRecord.getStepId(),
                stepRecord.getStepType(),
                stepRecord.getStepName(),
                elapsedMillis(stepRecord.getStartTime(), stepRecord.getEndTime()));
        publishEvent(
                sessionRecord.getTaskId(),
                sessionRecord.getSessionId(),
                CreatorWorkflowEventType.STEP_COMPLETED,
                null,
                buildStepPayload(stepRecord)
        );
    }

    private void completeStepFailure(CreatorWorkflowSessionRecord sessionRecord,
                                     CreatorWorkflowStepRecord stepRecord,
                                     RuntimeException exception) {
        String errorMessage = TextUtil.abbreviateWithSuffix(
                exception.getMessage(),
                ERROR_MESSAGE_MAX_LENGTH,
                "..."
        );
        creatorWorkflowMapper.completeStepFailure(
                stepRecord.getStepId(),
                CreatorWorkflowStepStatus.FAILED.name(),
                TextUtil.trimToDefault(errorMessage, "步骤执行失败")
        );
        stepRecord.setStatus(CreatorWorkflowStepStatus.FAILED.name());
        stepRecord.setErrorMessage(errorMessage);
        stepRecord.setEndTime(LocalDateTime.now());
        log.warn("工作流步骤失败：taskId={}, sessionId={}, stepId={}, stepType={}, stepName={}, "
                        + "elapsedMs={}, error={}",
                sessionRecord.getTaskId(),
                sessionRecord.getSessionId(),
                stepRecord.getStepId(),
                stepRecord.getStepType(),
                stepRecord.getStepName(),
                elapsedMillis(stepRecord.getStartTime(), stepRecord.getEndTime()),
                TextUtil.trimToDefault(errorMessage, "步骤执行失败"));
        publishEvent(
                sessionRecord.getTaskId(),
                sessionRecord.getSessionId(),
                CreatorWorkflowEventType.STEP_FAILED,
                null,
                buildStepPayload(stepRecord)
        );
    }

    private CreatorWorkflowSessionRecord updateSessionStatus(String taskId,
                                                             String sessionId,
                                                             CreatorWorkflowStatus status,
                                                             String errorMessage) {
        creatorWorkflowMapper.updateSessionStatus(
                sessionId,
                status.name(),
                errorMessage
        );
        CreatorWorkflowSessionRecord updatedSession = creatorWorkflowMapper.findSession(taskId, sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "工作流会话状态更新后读取失败"));
        log.info("工作流会话状态更新：taskId={}, sessionId={}, status={}, planGenerationCount={}, hasError={}",
                updatedSession.getTaskId(),
                updatedSession.getSessionId(),
                updatedSession.getStatus(),
                updatedSession.getPlanGenerationCount(),
                TextUtil.hasText(updatedSession.getErrorMessage()));
        publishSessionStatus(updatedSession);
        return updatedSession;
    }

    private static long elapsedMillis(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static long elapsedMillis(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime == null || endTime == null) {
            return -1L;
        }
        return Math.max(0L, Duration.between(startTime, endTime).toMillis());
    }

    private void publishMessage(String taskId, CreatorWorkflowMessageRecord messageRecord) {
        CreatorWorkflowMessageResponse response = toMessageResponse(messageRecord);
        publishEvent(
                taskId,
                messageRecord.getSessionId(),
                CreatorWorkflowEventType.MESSAGE_CREATED,
                response.sequenceNo(),
                response
        );
    }

    private void publishSessionStatus(CreatorWorkflowSessionRecord sessionRecord) {
        publishEvent(
                sessionRecord.getTaskId(),
                sessionRecord.getSessionId(),
                CreatorWorkflowEventType.SESSION_STATUS,
                null,
                buildSessionStatusPayload(sessionRecord)
        );
    }

    private void publishEvent(String taskId,
                              String sessionId,
                              CreatorWorkflowEventType eventType,
                              Integer sequenceNo,
                              Object payload) {
        workflowEventPublisher.publish(
                sessionId,
                buildEvent(taskId, sessionId, eventType, sequenceNo, payload)
        );
    }

    private CreatorWorkflowEventResponse buildEvent(String taskId,
                                                    String sessionId,
                                                    CreatorWorkflowEventType eventType,
                                                    Integer sequenceNo,
                                                    Object payload) {
        return new CreatorWorkflowEventResponse(
                UUID.randomUUID().toString(),
                sessionId,
                taskId,
                eventType.eventName(),
                sequenceNo,
                payload,
                LocalDateTime.now()
        );
    }

    private Map<String, Object> buildSessionStatusPayload(CreatorWorkflowSessionRecord sessionRecord) {
        return payload(
                "status", sessionRecord.getStatus(),
                "confirmedResultId", sessionRecord.getConfirmedResultId(),
                "planGenerationCount", sessionRecord.getPlanGenerationCount(),
                "errorMessage", sessionRecord.getErrorMessage()
        );
    }

    private Map<String, Object> buildStepPayload(CreatorWorkflowStepRecord stepRecord) {
        Map<String, Object> stepPayload = payload(
                "stepId", stepRecord.getStepId(),
                "stepType", stepRecord.getStepType(),
                "stepName", stepRecord.getStepName(),
                "status", stepRecord.getStatus(),
                "inputSummary", stepRecord.getInputSummary(),
                "outputSummary", stepRecord.getOutputSummary(),
                "errorMessage", stepRecord.getErrorMessage()
        );
        // 方案二：面向用户展示的字段，让前端不需要理解业务逻辑就能渲染时间轴。
        // 前端直接取 userLabel 做节点标题、userDetail 做展开详情，无需判断 stepType
        // 来映射业务含义。这样新增步骤类型时前端不需要改代码。
        // userLabel 用于时间轴节点标题，userDetail 用于节点展开后的详情
        stepPayload.put("userLabel", stepRecord.getStepName());
        if (TextUtil.hasText(stepRecord.getOutputSummary())) {
            stepPayload.put("userDetail", stepRecord.getOutputSummary());
        }
        // 步骤耗时：从 startTime 和 endTime 计算，让前端展示每步用时
        if (stepRecord.getStartTime() != null && stepRecord.getEndTime() != null) {
            long durationMs = java.time.Duration.between(
                    stepRecord.getStartTime(), stepRecord.getEndTime()).toMillis();
            stepPayload.put("durationMs", durationMs);
        }
        return stepPayload;
    }

    private Map<String, Object> payload(Object... keyValues) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            Object key = keyValues[index];
            Object value = keyValues[index + 1];
            if (key != null && value != null) {
                payload.put(String.valueOf(key), value);
            }
        }
        return payload;
    }

    private CreatorTaskRecord getTaskRecord(String taskId) {
        return creatorTaskMapper.findTaskByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在"));
    }

    private CreatorWorkflowSessionRecord getSessionRecord(String taskId, String sessionId) {
        getTaskRecord(taskId);
        return creatorWorkflowMapper.findSession(taskId.trim(), sessionId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工作流会话不存在"));
    }

    /**
     * 锁定会话后读取最新状态。
     *
     * 此方法只能在短事务的发送消息或确认建议流程中调用。锁会在事务提交后立即释放，
     * 不用于包裹 LLM 调用，避免慢模型请求长期阻塞用户后续操作。
     */
    private CreatorWorkflowSessionRecord getSessionRecordForUpdate(String taskId, String sessionId) {
        getTaskRecord(taskId);
        return creatorWorkflowMapper.findSessionForUpdate(taskId.trim(), sessionId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工作流会话不存在"));
    }

    /**
     * 原子抢占发布前分析或文稿草稿生成的执行权。
     *
     * 条件更新成功后状态已提交为 RUNNING，其他写入操作会在锁定会话时读到 RUNNING 并拒绝。
     * 这里不持有 Java 同步锁或数据库事务等待 LLM 返回，既防止重复模型调用，也避免慢调用阻塞会话。
     */
    private CreatorWorkflowSessionRecord claimPrePublishExecution(CreatorWorkflowSessionRecord sessionRecord) {
        if (creatorWorkflowMapper.claimPrePublishExecution(
                sessionRecord.getSessionId(),
                CreatorWorkflowStatus.RUNNING.name()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前工作流正在运行或状态已变化，请刷新后重试");
        }
        sessionRecord.setStatus(CreatorWorkflowStatus.RUNNING.name());
        sessionRecord.setErrorMessage(null);
        publishSessionStatus(sessionRecord);
        return sessionRecord;
    }

    /**
     * 状态前置校验：用户可在等待输入、失败或等待确认时追加消息。
     * RUNNING 时追加会干扰正在执行的 Agent/LLM 流程；
     * CONFIRMED/CANCELLED 时追加没有业务意义（会话已终结）。
     */
    private void ensureCanAppendMessage(CreatorWorkflowSessionRecord sessionRecord) {
        if (CreatorWorkflowStatus.RUNNING.name().equals(sessionRecord.getStatus())
                || CreatorWorkflowStatus.CONFIRMED.name().equals(sessionRecord.getStatus())
                || CreatorWorkflowStatus.CANCELLED.name().equals(sessionRecord.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前工作流会话不可继续发送消息");
        }
    }

    private void ensureCanAlignIntent(CreatorWorkflowSessionRecord sessionRecord) {
        if (!CreatorWorkflowStage.PRE_PUBLISH.name().equals(sessionRecord.getStage())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前工作流会话不是发布前优化阶段");
        }
        if (CreatorWorkflowStatus.RUNNING.name().equals(sessionRecord.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前工作流正在运行，请稍后再继续对齐");
        }
        if (CreatorWorkflowStatus.CONFIRMED.name().equals(sessionRecord.getStatus())
                || CreatorWorkflowStatus.CANCELLED.name().equals(sessionRecord.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前工作流会话已经结束，不能继续对齐");
        }
    }

    /**
     * 新对齐流程必须先让主 Agent 回应最新用户原话；历史方向卡任务不套用这条新门禁。
     */
    private void ensureIntentReadyForPlan(CreatorWorkflowSessionRecord sessionRecord) {
        InteractiveSessionRecord interactiveSession = creatorInteractiveMapper
                .findSessionByTaskId(sessionRecord.getTaskId())
                .orElse(null);
        if (interactiveSession == null || !"INTENT_ALIGNMENT".equals(interactiveSession.getStatus())) {
            return;
        }
        if (!"READY".equals(interactiveSession.getUnderstandingStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "用户补充了新信息，请先完成本轮想法对齐再生成发布方案。"
            );
        }
    }

    /**
     * 状态前置校验：分析操作的状态守卫。
     * <ul>
     *   <li>非 PRE_PUBLISH 阶段 → 拒绝（其他阶段有自己的分析入口）</li>
     *   <li>RUNNING → 拒绝（避免并发分析造成 LLM 调用和消息流混乱）</li>
     *   <li>CONFIRMED → 拒绝（已确认的建议不应被新分析覆盖；这是用户已做出的决策）</li>
     *   <li>CANCELLED → 拒绝（已取消的会话不可恢复）</li>
     * </ul>
     */
    private void ensureCanAnalyze(CreatorWorkflowSessionRecord sessionRecord) {
        if (!CreatorWorkflowStage.PRE_PUBLISH.name().equals(sessionRecord.getStage())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前工作流会话不是发布前优化阶段");
        }
        if (CreatorWorkflowStatus.RUNNING.name().equals(sessionRecord.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前工作流正在运行，请勿重复触发分析");
        }
        if (CreatorWorkflowStatus.CONFIRMED.name().equals(sessionRecord.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发布前优化建议已确认，如需修改请新建后续版本");
        }
        if (CreatorWorkflowStatus.CANCELLED.name().equals(sessionRecord.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前工作流会话不可继续分析");
        }
    }

    /**
     * 这是正常的业务分支，不应被统一异常处理改成 FAILED 状态。
     */
    private static final class PlanClarificationRequiredException extends ResponseStatusException {

        private PlanClarificationRequiredException(String reason) {
            super(HttpStatus.CONFLICT, reason);
        }
    }

    /**
     * 状态前置校验：文稿生成操作的状态守卫。
     * CONFIRMED 拒绝的语义：用户已确认建议后不应自动覆盖文稿，避免 AI 生成的草稿
     * 覆盖用户手动修改的版本。如需重新生成，应先回到 DRAFT 态。
     */
    private void ensureCanGeneratePrePublishDraft(CreatorWorkflowSessionRecord sessionRecord) {
        if (!CreatorWorkflowStage.PRE_PUBLISH.name().equals(sessionRecord.getStage())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前工作流会话不是发布前优化阶段");
        }
        if (CreatorWorkflowStatus.RUNNING.name().equals(sessionRecord.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前工作流正在运行，请勿重复触发文稿生成");
        }
        if (CreatorWorkflowStatus.CONFIRMED.name().equals(sessionRecord.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发布前优化建议已确认，不能再自动覆盖文稿");
        }
        if (CreatorWorkflowStatus.CANCELLED.name().equals(sessionRecord.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前工作流会话不可继续生成文稿");
        }
    }

    /**
     * 判断任务是否已有完整文稿/字幕。
     * MANUSCRIPT 或 SUBTITLE 类型材料中，只要有一条内容长度 >=
     * {@link #FULL_SCRIPT_MIN_LENGTH}（800 字），即视为已有较完整内容，
     * 拒绝重复生成文稿草稿。
     */
    private boolean hasFullScriptMaterial(List<CreatorMaterialRecord> materials) {
        return materials.stream()
                .filter(material -> CreatorMaterialType.MANUSCRIPT.name().equals(material.getMaterialType())
                        || CreatorMaterialType.SUBTITLE.name().equals(material.getMaterialType()))
                .map(CreatorMaterialRecord::getContent)
                .filter(TextUtil::hasText)
                .anyMatch(content -> content.trim().length() >= FULL_SCRIPT_MIN_LENGTH);
    }

    /**
     * 判断任务是否已经保存过本工作流生成的文稿草稿。
     * <p>
     * AI 草稿可能短于完整文稿阈值，但它已经是一次明确成功的补稿结果；继续重复生成只会覆盖
     * 上一版草稿，并让前端始终停留在“缺少完整文稿”的错误状态。
     */
    private boolean hasGeneratedManuscriptDraft(List<CreatorMaterialRecord> materials) {
        return materials.stream()
                .filter(material -> CreatorMaterialType.MANUSCRIPT.name().equals(material.getMaterialType()))
                .map(CreatorMaterialRecord::getContent)
                .filter(TextUtil::hasText)
                .anyMatch(content -> content.trim().startsWith(AI_MANUSCRIPT_DRAFT_PREFIX));
    }

    private boolean shouldResumeLatest(CreatorWorkflowStartRequest request) {
        return request == null || request.shouldResumeLatest();
    }

    private String normalizeUserId(String requestUserId, String taskUserId) {
        if (TextUtil.hasText(requestUserId)) {
            return requestUserId.trim();
        }
        return TextUtil.trimToDefault(taskUserId, DEFAULT_USER_ID);
    }

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

    private CreatorWorkflowSessionResponse toSessionResponse(CreatorWorkflowSessionRecord record) {
        List<CreatorWorkflowMessageResponse> messages = creatorWorkflowMapper.listMessages(record.getSessionId())
                .stream()
                .map(this::toMessageResponse)
                .toList();
        return new CreatorWorkflowSessionResponse(
                record.getId(),
                record.getSessionId(),
                record.getTaskId(),
                record.getStage(),
                record.getStatus(),
                record.getUserId(),
                record.getConfirmedResultId(),
                record.getPlanGenerationCount() == null ? 0 : record.getPlanGenerationCount(),
                record.getErrorMessage(),
                record.getCreateTime(),
                record.getUpdateTime(),
                messages
        );
    }

    private CreatorWorkflowMessageResponse toMessageResponse(CreatorWorkflowMessageRecord record) {
        return new CreatorWorkflowMessageResponse(
                record.getId(),
                record.getMessageId(),
                record.getSessionId(),
                record.getRole(),
                record.getContent(),
                record.getContentType(),
                record.getDetailRefType(),
                record.getDetailRefId(),
                record.getSequenceNo(),
                record.getCreateTime()
        );
    }

    private CreatorWorkflowStepResponse toStepResponse(CreatorWorkflowStepRecord record) {
        return new CreatorWorkflowStepResponse(
                record.getId(),
                record.getStepId(),
                record.getSessionId(),
                record.getStepType(),
                record.getStepName(),
                record.getStatus(),
                record.getInputSummary(),
                record.getOutputSummary(),
                record.getRawOutput(),
                record.getErrorMessage(),
                record.getStartTime(),
                record.getEndTime(),
                record.getCreateTime()
        );
    }

    /**
     * 从用户确认采用的发布前优化建议中提取标题风格特征。
     * 使用轻量规则描述标题的句式、长度、语气等特征，供后续偏好学习使用。
     * 不调用 LLM —— 避免在确认流程中增加额外延迟和成本。
     */
    private String extractTitleStyleFromSuggestion(CreatorSuggestionRecord suggestionRecord) {
        String titleSuggestions = suggestionRecord.getTitleSuggestions();
        if (TextUtil.isBlank(titleSuggestions)) {
            return null;
        }

        StringBuilder style = new StringBuilder();
        // 从标题建议 JSON 中提取标题文本，分析风格特征
        String lowerTitles = titleSuggestions.toLowerCase(java.util.Locale.ROOT);

        // 句式特征
        if (lowerTitles.contains("？") || lowerTitles.contains("?")) {
            style.append("倾向问句式标题；");
        }
        if (lowerTitles.matches(".*\\d+.*")) {
            style.append("标题含数字；");
        }
        if (lowerTitles.contains("！") || lowerTitles.contains("!")) {
            style.append("倾向感叹句式；");
        }

        // 长度特征：统计标题平均长度
        int totalLength = 0;
        int count = 0;
        java.util.regex.Matcher titleMatcher = java.util.regex.Pattern.compile("\"title\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(titleSuggestions);
        while (titleMatcher.find()) {
            totalLength += titleMatcher.group(1).length();
            count++;
        }
        if (count > 0) {
            int avgLength = totalLength / count;
            if (avgLength <= 15) {
                style.append("短标题风格（≤15字）；");
            } else if (avgLength <= 25) {
                style.append("中等长度标题（16-25字）；");
            } else {
                style.append("长标题风格（>25字）；");
            }
        }

        // 语气特征
        if (containsAnyKeyword(lowerTitles, "必看", "震惊", "绝了", "爆", "竟然", "居然")) {
            style.append("偏网感/夸张语气；");
        }
        if (containsAnyKeyword(lowerTitles, "教程", "指南", "攻略", "方法", "步骤", "技巧")) {
            style.append("偏教程/实用导向；");
        }
        if (containsAnyKeyword(lowerTitles, "故事", "经历", "回忆", "记得", "当年")) {
            style.append("偏叙事/故事化表达；");
        }

        return style.isEmpty() ? "已采用发布前优化建议" : style.toString();
    }

    private boolean containsAnyKeyword(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
