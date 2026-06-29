package com.link.linkagent.creator.workflow.service;

import com.link.linkagent.creator.preference.service.CreatorPreferenceService;
import com.link.linkagent.creator.profile.service.CreatorProfileService;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionResponse;
import com.link.linkagent.creator.suggestion.model.PrePublishAnalyzeRequest;
import com.link.linkagent.creator.suggestion.service.PrePublishSuggestionService;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorMaterialType;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.model.CreatorTaskStatus;
import com.link.linkagent.creator.workflow.event.CreatorWorkflowEventPublisher;
import com.link.linkagent.creator.workflow.mapper.CreatorWorkflowMapper;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowConfirmRequest;
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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 创作者工作流服务。
 * 这里把“业务消息流、步骤回放、发布前建议确认”放在创作任务上下文中，避免把通用 Agent 控制台直接暴露给 UP 主。
 */
@Service
public class CreatorWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(CreatorWorkflowService.class);

    private static final String DEFAULT_USER_ID = "default";
    private static final String DETAIL_REF_TYPE_MATERIAL = "MATERIAL";
    private static final String DETAIL_REF_TYPE_SUGGESTION = "SUGGESTION";
    private static final int WORKFLOW_GUIDANCE_MAX_LENGTH = 2000;
    private static final int ERROR_MESSAGE_MAX_LENGTH = 480;
    private static final int FULL_SCRIPT_MIN_LENGTH = 800;
    private static final int DRAFT_MATERIAL_MAX_LENGTH = 20000;
    private static final int DRAFT_CONTEXT_MATERIAL_MAX_LENGTH = 4000;

    private final CreatorTaskMapper creatorTaskMapper;
    private final CreatorSuggestionMapper creatorSuggestionMapper;
    private final CreatorWorkflowMapper creatorWorkflowMapper;
    private final PrePublishSuggestionService prePublishSuggestionService;
    private final CreatorWorkflowEventPublisher workflowEventPublisher;
    private final LlmApiUsageService llmApiUsageService;
    private final LLMService llmService;
    private final RuntimeSettingService runtimeSettingService;
    private final CreatorPreferenceService creatorPreferenceService;
    private final CreatorProfileService creatorProfileService;

    public CreatorWorkflowService(CreatorTaskMapper creatorTaskMapper,
                                  CreatorSuggestionMapper creatorSuggestionMapper,
                                  CreatorWorkflowMapper creatorWorkflowMapper,
                                  PrePublishSuggestionService prePublishSuggestionService,
                                  CreatorWorkflowEventPublisher workflowEventPublisher,
                                  LlmApiUsageService llmApiUsageService,
                                  LLMService llmService,
                                  RuntimeSettingService runtimeSettingService,
                                  CreatorPreferenceService creatorPreferenceService,
                                  CreatorProfileService creatorProfileService) {
        this.creatorTaskMapper = creatorTaskMapper;
        this.creatorSuggestionMapper = creatorSuggestionMapper;
        this.creatorWorkflowMapper = creatorWorkflowMapper;
        this.prePublishSuggestionService = prePublishSuggestionService;
        this.workflowEventPublisher = workflowEventPublisher;
        this.llmApiUsageService = llmApiUsageService;
        this.llmService = llmService;
        this.runtimeSettingService = runtimeSettingService;
        this.creatorPreferenceService = creatorPreferenceService;
        this.creatorProfileService = creatorProfileService;
    }

    @Transactional
    public CreatorWorkflowSessionResponse startPrePublishWorkflow(String taskId,
                                                                  CreatorWorkflowStartRequest request) {
        CreatorTaskRecord taskRecord = getTaskRecord(taskId);
        List<CreatorMaterialRecord> materials = creatorTaskMapper.listMaterialsByTaskId(taskRecord.getTaskId());
        if (materials.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "创作任务缺少可加载材料");
        }

        if (shouldResumeLatest(request)) {
            return creatorWorkflowMapper.findLatestSession(
                            taskRecord.getTaskId(),
                            CreatorWorkflowStage.PRE_PUBLISH.name()
                    )
                    .map(this::toSessionResponse)
                    .orElseGet(() -> createPrePublishSession(taskRecord, materials, request));
        }

        return createPrePublishSession(taskRecord, materials, request);
    }

    public List<CreatorWorkflowMessageResponse> listMessages(String taskId, String sessionId) {
        CreatorWorkflowSessionRecord sessionRecord = getSessionRecord(taskId, sessionId);
        return creatorWorkflowMapper.listMessages(sessionRecord.getSessionId())
                .stream()
                .map(this::toMessageResponse)
                .toList();
    }

    public List<CreatorWorkflowStepResponse> listSteps(String taskId, String sessionId) {
        CreatorWorkflowSessionRecord sessionRecord = getSessionRecord(taskId, sessionId);
        return creatorWorkflowMapper.listSteps(sessionRecord.getSessionId())
                .stream()
                .map(this::toStepResponse)
                .toList();
    }

    public WorkflowUsageResponse getWorkflowUsage(String taskId, String sessionId) {
        CreatorWorkflowSessionRecord sessionRecord = getSessionRecord(taskId, sessionId);
        return llmApiUsageService.summarizeWorkflowSession(sessionRecord.getTaskId(), sessionRecord.getSessionId());
    }

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

    @Transactional
    public CreatorWorkflowMessageResponse sendMessage(String taskId,
                                                      String sessionId,
                                                      CreatorWorkflowMessageCreateRequest request) {
        CreatorWorkflowSessionRecord sessionRecord = getSessionRecord(taskId, sessionId);
        ensureCanAppendMessage(sessionRecord);

        CreatorWorkflowMessageRecord messageRecord = appendMessage(
                sessionRecord.getSessionId(),
                CreatorWorkflowMessageRole.USER,
                request.content().trim(),
                CreatorWorkflowMessageContentType.TEXT,
                null,
                null
        );
        publishMessage(sessionRecord.getTaskId(), messageRecord);
        updateSessionStatus(
                sessionRecord.getTaskId(),
                sessionRecord.getSessionId(),
                CreatorWorkflowStatus.WAITING_USER_INPUT,
                null
        );
        return toMessageResponse(messageRecord);
    }

    public CreatorSuggestionResponse analyzePrePublishWorkflow(String taskId,
                                                               String sessionId,
                                                               PrePublishAnalyzeRequest request) {
        CreatorWorkflowSessionRecord sessionRecord = getSessionRecord(taskId, sessionId);
        ensureCanAnalyze(sessionRecord);

        List<CreatorMaterialRecord> materials = creatorTaskMapper.listMaterialsByTaskId(sessionRecord.getTaskId());
        if (materials.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "创作任务缺少可分析材料");
        }

        CreatorWorkflowStepRecord currentStep = null;
        try {
            // 确保创作者画像存在（不存在时从历史偏好中 LLM 初始化）
            // 放在分析开始前，是为了让首次使用的用户也能体验到个性化建议
            creatorProfileService.ensureProfile(sessionRecord.getUserId());

            updateSessionStatus(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    CreatorWorkflowStatus.RUNNING,
                    null
            );
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

            PrePublishAnalyzeRequest mergedRequest = mergeWorkflowGuidance(sessionRecord.getSessionId(), request);
            CreatorSuggestionResponse suggestionResponse;
            if (runtimeSettingService.isPrePublishAgentEnabled()) {
                currentStep = startStep(
                        sessionRecord,
                        CreatorWorkflowStepType.AGENT_REASONING,
                        "发布前优化 Agent 推理",
                        "基于任务材料、创作指导、创作者偏好和案例知识库执行 Agent 推理。"
                );
                try {
                    suggestionResponse = generateSuggestionInWorkflowStep(sessionRecord, currentStep, mergedRequest, true);
                    completeStepSuccess(
                            sessionRecord,
                            currentStep,
                            "Agent 已返回发布前优化建议，解析状态：" + suggestionResponse.parseStatus(),
                            suggestionResponse.rawOutput()
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
                    suggestionResponse = generateSuggestionInWorkflowStep(sessionRecord, currentStep, mergedRequest, false);
                    completeStepSuccess(
                            sessionRecord,
                            currentStep,
                            "旧直连 LLM 已返回发布前优化建议，解析状态：" + suggestionResponse.parseStatus(),
                            suggestionResponse.rawOutput()
                    );
                }
            } else {
                currentStep = startStep(
                        sessionRecord,
                        CreatorWorkflowStepType.LLM_CALL,
                        "生成发布前优化建议",
                        "基于任务材料、创作指导和工作流补充消息调用 LLM。"
                );
                suggestionResponse = generateSuggestionInWorkflowStep(sessionRecord, currentStep, mergedRequest, false);
                completeStepSuccess(
                        sessionRecord,
                        currentStep,
                        "LLM 已返回发布前优化建议，解析状态：" + suggestionResponse.parseStatus(),
                        suggestionResponse.rawOutput()
                );
            }

            currentStep = startStep(
                    sessionRecord,
                    CreatorWorkflowStepType.SAVE_RESULT,
                    "保存建议结果消息",
                    "把结构化建议挂到当前工作流会话，等待用户确认。"
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
            return suggestionResponse;
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

    @Transactional
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
        if (hasFullScriptMaterial(materials)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前任务已有较完整文稿或字幕，请直接生成发布方案");
        }

        CreatorWorkflowStepRecord currentStep = null;
        try {
            updateSessionStatus(
                    sessionRecord.getTaskId(),
                    sessionRecord.getSessionId(),
                    CreatorWorkflowStatus.RUNNING,
                    null
            );
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

    @Transactional
    public CreatorWorkflowSessionResponse confirmPrePublishSuggestion(String taskId,
                                                                      String sessionId,
                                                                      CreatorWorkflowConfirmRequest request) {
        CreatorWorkflowSessionRecord sessionRecord = getSessionRecord(taskId, sessionId);
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
                "已采用本轮发布前优化建议，后续可以进入评论弹幕分析阶段。",
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

    private CreatorWorkflowSessionResponse createPrePublishSession(CreatorTaskRecord taskRecord,
                                                                   List<CreatorMaterialRecord> materials,
                                                                   CreatorWorkflowStartRequest request) {
        CreatorWorkflowSessionRecord sessionRecord = new CreatorWorkflowSessionRecord();
        sessionRecord.setSessionId(UUID.randomUUID().toString());
        sessionRecord.setTaskId(taskRecord.getTaskId());
        sessionRecord.setStage(CreatorWorkflowStage.PRE_PUBLISH.name());
        sessionRecord.setStatus(CreatorWorkflowStatus.CONTEXT_LOADING.name());
        sessionRecord.setUserId(normalizeUserId(request == null ? null : request.userId(), taskRecord.getUserId()));
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

    private PrePublishAnalyzeRequest mergeWorkflowGuidance(String sessionId, PrePublishAnalyzeRequest request) {
        PrePublishAnalyzeRequest safeRequest = request == null
                ? new PrePublishAnalyzeRequest(null, null, null, null, null, null)
                : request;
        StringBuilder builder = new StringBuilder();
        if (TextUtil.hasText(safeRequest.customGuidance())) {
            builder.append(safeRequest.customGuidance().trim()).append("\n");
        }

        List<String> userMessages = creatorWorkflowMapper.listMessages(sessionId)
                .stream()
                .filter(message -> CreatorWorkflowMessageRole.USER.name().equals(message.getRole()))
                .map(CreatorWorkflowMessageRecord::getContent)
                .filter(TextUtil::hasText)
                .map(String::trim)
                .toList();
        if (!userMessages.isEmpty()) {
            builder.append("\n工作流补充要求（来自用户主动输入的消息）：\n");
            for (String userMessage : userMessages) {
                builder.append("- ").append(userMessage).append("\n");
            }
        }

        String mergedGuidance = TextUtil.trimToNull(TextUtil.abbreviateWithSuffix(
                builder.toString(),
                WORKFLOW_GUIDANCE_MAX_LENGTH,
                "\n[工作流补充要求过长，已截断]"
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

    private CreatorSuggestionResponse generateSuggestionInWorkflowStep(CreatorWorkflowSessionRecord sessionRecord,
                                                                       CreatorWorkflowStepRecord stepRecord,
                                                                       PrePublishAnalyzeRequest request,
                                                                       boolean agentMode) {
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
                return prePublishSuggestionService.generateSuggestionByAgent(sessionRecord.getTaskId(), request);
            }
            return prePublishSuggestionService.generateSuggestion(sessionRecord.getTaskId(), request);
        }
    }

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
            String markedDraftContent = "【AI 可编辑文稿草稿】\n" + draftContent;
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
        creatorWorkflowMapper.insertStep(stepRecord);
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
        publishSessionStatus(updatedSession);
        return updatedSession;
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
        // 方案二：面向用户展示的字段，让前端不需要理解业务逻辑就能渲染时间轴
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

    private void ensureCanAppendMessage(CreatorWorkflowSessionRecord sessionRecord) {
        if (CreatorWorkflowStatus.RUNNING.name().equals(sessionRecord.getStatus())
                || CreatorWorkflowStatus.CONFIRMED.name().equals(sessionRecord.getStatus())
                || CreatorWorkflowStatus.CANCELLED.name().equals(sessionRecord.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前工作流会话不可继续发送消息");
        }
    }

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

    private boolean hasFullScriptMaterial(List<CreatorMaterialRecord> materials) {
        return materials.stream()
                .filter(material -> CreatorMaterialType.MANUSCRIPT.name().equals(material.getMaterialType())
                        || CreatorMaterialType.SUBTITLE.name().equals(material.getMaterialType()))
                .map(CreatorMaterialRecord::getContent)
                .filter(TextUtil::hasText)
                .anyMatch(content -> content.trim().length() >= FULL_SCRIPT_MIN_LENGTH);
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
