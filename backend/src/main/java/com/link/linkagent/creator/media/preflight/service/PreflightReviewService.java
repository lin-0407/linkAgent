package com.link.linkagent.creator.media.preflight.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.preflight.model.CreatePreflightReviewRequest;
import com.link.linkagent.creator.media.preflight.model.EditTaskRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightIssueRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewResponse;
import com.link.linkagent.creator.media.preflight.model.PreflightStepRecord;
import com.link.linkagent.creator.media.preflight.model.UpdateEditTaskRequest;
import com.link.linkagent.creator.media.preflight.model.UpdatePreflightIssueRequest;
import com.link.linkagent.creator.media.processing.mapper.MediaProcessingMapper;
import com.link.linkagent.creator.media.processing.model.MediaProcessingJobRecord;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import com.link.linkagent.util.TextUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** P0-3/P0-4 发布前试映应用服务。 */
@Service
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class PreflightReviewService {

    private static final List<StepDefinition> STEPS = List.of(
            new StepDefinition("TRANSCRIBE", 1),
            new StepDefinition("BUILD_TIMELINE", 2),
            new StepDefinition("ANALYZE_VIDEO", 3),
            new StepDefinition("REVIEW_SEGMENTS", 4),
            new StepDefinition("SCREEN_AUDIENCE", 5)
    );

    private final CreatorMediaProperties properties;
    private final PreflightReviewMapper mapper;
    private final MediaUploadMapper uploadMapper;
    private final MediaProcessingMapper processingMapper;
    private final PreflightEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public PreflightReviewService(CreatorMediaProperties properties,
                                  PreflightReviewMapper mapper,
                                  MediaUploadMapper uploadMapper,
                                  MediaProcessingMapper processingMapper,
                                  PreflightEventPublisher eventPublisher,
                                  ObjectMapper objectMapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.uploadMapper = uploadMapper;
        this.processingMapper = processingMapper;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PreflightReviewResponse create(String ownerId,
                                          String taskId,
                                          String idempotencyKey,
                                          CreatePreflightReviewRequest request) {
        mapper.lockDraftVersion(taskId, ownerId, request.versionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "成片记录不存在"));
        PreflightReviewRecord idempotent = mapper.findByIdempotency(taskId, ownerId, idempotencyKey)
                .orElse(null);
        if (idempotent != null) {
            if (!idempotent.versionId().equals(request.versionId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "同一幂等键已经用于另一个成片版本");
            }
            return toResponse(idempotent);
        }
        PreflightReviewRecord active = mapper.findActiveByVersion(taskId, ownerId, request.versionId())
                .orElse(null);
        if (active != null) return toResponse(active);
        PreflightReviewRecord current = mapper.findCurrentByVersion(taskId, ownerId, request.versionId())
                .orElse(null);
        if (current != null && "COMPLETED".equals(current.status())) {
            boolean matchesCurrentProcessing = processingMapper.findCurrentJob(taskId, ownerId, request.versionId())
                    .filter(job -> "COMPLETED".equals(job.status()))
                    .map(job -> job.jobId().equals(current.processingJobId()))
                    .orElse(false);
            if (matchesCurrentProcessing) return toResponse(current);
        }
        if (current != null && "FAILED".equals(current.status())) {
            if ("ASR_SUBMISSION_AMBIGUOUS".equals(current.errorCode())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "ASR 提交结果不确定，为避免重复计费不能新建任务，请先核对 DashScope 任务记录"
                );
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前试映任务失败，请重试原任务");
        }

        DraftVideoRecord draft = uploadMapper.findDraftVideoByVersion(taskId, ownerId, request.versionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "成片记录不存在"));
        MediaProcessingJobRecord processing = processingMapper.findCurrentJob(taskId, ownerId, request.versionId())
                .filter(job -> "COMPLETED".equals(job.status()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "请先完成媒体预处理，再开始发布前试映"));
        boolean transcribe = Boolean.TRUE.equals(draft.hasAudio()) && Boolean.TRUE.equals(processing.includeAsr());
        requireVideoAnalysisConfigured();
        requirePreviewAsset(processing.jobId());
        if (transcribe) {
            requireAsrConfigured();
            requireAudioAsset(processing.jobId());
        }

        String reviewId = UUID.randomUUID().toString();
        String fingerprint = sha256(String.join("|",
                draft.versionId(), processing.jobId(), processing.pricingVersion(),
                String.valueOf(processing.includeAsr())));
        String capabilityGaps = transcribe ? null : json(Map.of(
                "asr", Boolean.TRUE.equals(draft.hasAudio()) ? "用户未启用 ASR" : "原片没有音轨"
        ));
        PreflightReviewRecord review = new PreflightReviewRecord(
                null, reviewId, taskId, request.versionId(), ownerId, processing.jobId(), idempotencyKey,
                TextUtil.trimToNull(request.reviewFocus()), "QUEUED", transcribe ? "TRANSCRIBE" : "BUILD_TIMELINE",
                0, 0L, false, 0, properties.getPreflight().getMaxAttempts(), LocalDateTime.now(),
                null, null, fingerprint, json(Map.of(
                        "provider", "DASHSCOPE",
                        "asrModel", properties.getPreflight().getAsrModel(),
                        "videoModel", properties.getPreflight().getVideoModel(),
                        "videoFps", properties.getPreflight().getVideoFps(),
                        "segmentReviewModel", properties.getPreflight().getSegmentReviewModel(),
                        "segmentReviewFps", properties.getPreflight().getSegmentReviewFps(),
                        "segmentReviewMaxCount", properties.getPreflight().getSegmentReviewMaxCount()
                )), capabilityGaps, null, processing.estimatedTotalCostUsd(), null, null,
                "USD", null, null, null, null, null, null
        );
        if (mapper.insertReview(review) != 1) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "创建发布前试映任务失败");
        }
        for (StepDefinition definition : STEPS) {
            String status = !transcribe && "TRANSCRIBE".equals(definition.type()) ? "SKIPPED" : "PENDING";
            PreflightStepRecord step = new PreflightStepRecord(
                    null, UUID.randomUUID().toString(), reviewId, definition.type(), definition.sequenceNo(),
                    status, 0, fingerprint, null, null, null, null, null,
                    "SKIPPED".equals(status) ? LocalDateTime.now() : null, null, null
            );
            if (mapper.insertStep(step) != 1) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "创建发布前试映步骤失败");
            }
        }
        if (mapper.attachReviewToDraft(taskId, ownerId, request.versionId(), reviewId) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "成片状态已变化，请刷新后重试");
        }
        return get(ownerId, taskId, reviewId);
    }

    public PreflightReviewResponse get(String ownerId, String taskId, String reviewId) {
        return toResponse(requireReview(ownerId, taskId, reviewId));
    }

    public PreflightReviewResponse getCurrent(String ownerId, String taskId, String versionId) {
        PreflightReviewRecord review = mapper.findCurrentByVersion(taskId, ownerId, versionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "当前成片尚无发布前试映任务"));
        MediaProcessingJobRecord processing = processingMapper.findCurrentJob(taskId, ownerId, versionId)
                .filter(job -> "COMPLETED".equals(job.status()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "当前媒体预处理结果尚未完成"));
        if (!processing.jobId().equals(review.processingJobId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前媒体预处理结果尚未开始发布前试映");
        }
        return toResponse(review);
    }

    public SseEmitter subscribe(String ownerId, String taskId, String reviewId, long afterSequence) {
        PreflightReviewResponse snapshot = get(ownerId, taskId, reviewId);
        return eventPublisher.register(snapshot);
    }

    @Transactional
    public PreflightReviewResponse cancel(String ownerId, String taskId, String reviewId) {
        PreflightReviewRecord review = requireReview(ownerId, taskId, reviewId);
        if (isTerminal(review.status())) return toResponse(review);
        if (mapper.requestCancel(taskId, ownerId, reviewId) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "试映任务状态已变化，请刷新后重试");
        }
        return get(ownerId, taskId, reviewId);
    }

    @Transactional
    public PreflightReviewResponse retry(String ownerId, String taskId, String reviewId) {
        PreflightReviewRecord review = requireReview(ownerId, taskId, reviewId);
        if (!"FAILED".equals(review.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有失败的试映任务可以重试");
        }
        if ("ASR_SUBMISSION_AMBIGUOUS".equals(review.errorCode())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "ASR 提交结果不确定，为避免重复计费不能直接重试，请先核对 DashScope 任务记录"
            );
        }
        PreflightReviewRecord current = mapper.findCurrentByVersion(taskId, ownerId, review.versionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "当前成片试映任务已经变化"));
        if (!review.reviewId().equals(current.reviewId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只能重试当前成片的最新试映任务");
        }
        if (mapper.retryReview(taskId, ownerId, reviewId) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "试映任务状态已变化，请刷新后重试");
        }
        mapper.resetFailedSteps(reviewId);
        return get(ownerId, taskId, reviewId);
    }

    /** 已完成的 P0-4a 任务由作者显式补全，避免页面刷新后自动产生新的模型费用。 */
    @Transactional
    public PreflightReviewResponse completeScreening(String ownerId, String taskId, String reviewId) {
        PreflightReviewRecord review = requireReview(ownerId, taskId, reviewId);
        PreflightStepRecord audience = mapper.findStep(reviewId, "SCREEN_AUDIENCE").orElse(null);
        if (audience != null) {
            if ("SUCCEEDED".equals(audience.status()) || isActive(review.status())) return toResponse(review);
            if ("FAILED".equals(review.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "观众试映补全失败，请重试当前任务");
            }
        }
        if (!"COMPLETED".equals(review.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请等待当前发布前试映完成后再补全观众视角");
        }
        if (!"SUCCEEDED".equals(mapper.findStep(reviewId, "ANALYZE_VIDEO")
                .map(PreflightStepRecord::status).orElse(null))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前任务尚未完成全片体检");
        }
        insertMissingStep(review, "REVIEW_SEGMENTS", 4);
        insertMissingStep(review, "SCREEN_AUDIENCE", 5);
        if (mapper.queueScreeningCompletion(taskId, ownerId, reviewId) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "试映任务状态已变化，请刷新后重试");
        }
        return get(ownerId, taskId, reviewId);
    }

    @Transactional
    public PreflightReviewResponse updateIssue(String ownerId,
                                               String taskId,
                                               String issueId,
                                               UpdatePreflightIssueRequest request) {
        PreflightIssueRecord issue = mapper.findIssueForOwner(taskId, ownerId, issueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "体检问题不存在"));
        PreflightReviewRecord review = requireReview(ownerId, taskId, issue.reviewId());
        if (!"COMPLETED".equals(review.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请等待发布前试映完成后再整理修改清单");
        }
        if (!"SUCCEEDED".equals(mapper.findStep(issue.reviewId(), "SCREEN_AUDIENCE")
                .map(PreflightStepRecord::status).orElse(null))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先补全三类观众试映，再确认问题和修改清单");
        }
        String reason = TextUtil.trimToNull(request.reason());
        if (mapper.updateIssueDisposition(issue.reviewId(), issue.issueId(), request.disposition(), reason) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "问题状态已变化，请刷新后重试");
        }
        if ("ACCEPTED".equals(request.disposition())) {
            mapper.upsertEditTask(
                    UUID.randomUUID().toString(), issue.reviewId(), issue.issueId(), taskId, issue.versionId(),
                    issue.title(), issue.suggestedAction(), issue.startMs(), issue.endMs(), issue.severity(),
                    "完成建议动作后通看该时间段，确认问题已消除且前后衔接自然。"
            );
        } else {
            mapper.ignoreEditTaskByIssue(issue.issueId(), reason);
        }
        mapper.touchReview(issue.reviewId());
        PreflightReviewResponse response = get(ownerId, taskId, issue.reviewId());
        eventPublisher.publish(response, "review_updated");
        return response;
    }

    public List<PreflightReviewResponse.EditTask> listEditTasks(String ownerId, String taskId) {
        DraftVideoRecord draft = uploadMapper.findDraftVideo(taskId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "当前任务尚无成片"));
        PreflightReviewRecord review = mapper.findCurrentByVersion(taskId, ownerId, draft.versionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "当前成片尚无发布前试映任务"));
        return mapper.listEditTasks(review.reviewId()).stream().map(this::toEditTaskResponse).toList();
    }

    @Transactional
    public PreflightReviewResponse.EditTask updateEditTask(String ownerId,
                                                           String taskId,
                                                           String editTaskId,
                                                           UpdateEditTaskRequest request) {
        EditTaskRecord task = mapper.findEditTaskForOwner(taskId, ownerId, editTaskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "修改任务不存在"));
        if (!task.status().equals(request.status()) && !isAllowedTransition(task.status(), request.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前修改任务不能直接切换到该状态");
        }
        if (!task.status().equals(request.status()) && mapper.updateEditTaskStatus(
                editTaskId, request.status(), TextUtil.trimToNull(request.note())) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "修改任务状态已变化，请刷新后重试");
        }
        mapper.touchReview(task.reviewId());
        PreflightReviewResponse response = get(ownerId, taskId, task.reviewId());
        eventPublisher.publish(response, "review_updated");
        return response.editTasks().stream()
                .filter(item -> editTaskId.equals(item.editTaskId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "修改任务不存在"));
    }

    public void publish(String reviewId, String eventType) {
        mapper.findReviewInternal(reviewId).ifPresent(review -> eventPublisher.publish(toResponse(review), eventType));
    }

    private PreflightReviewRecord requireReview(String ownerId, String taskId, String reviewId) {
        return mapper.findReview(taskId, ownerId, reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "发布前试映任务不存在"));
    }

    private void requireAudioAsset(String processingJobId) {
        boolean found = processingMapper.listAssets(processingJobId).stream()
                .anyMatch(asset -> "AUDIO".equals(asset.assetType()));
        if (!found) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "媒体预处理没有生成 ASR 音轨，请先重新处理成片");
        }
    }

    private void requirePreviewAsset(String processingJobId) {
        boolean found = processingMapper.listAssets(processingJobId).stream()
                .anyMatch(asset -> "PREVIEW_VIDEO".equals(asset.assetType()));
        if (!found) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "媒体预处理没有生成分析预览，请先重新处理成片");
        }
    }

    private void requireAsrConfigured() {
        CreatorMediaProperties.Preflight preflight = properties.getPreflight();
        if (preflight.getDashScopeApiKey() == null || preflight.getDashScopeApiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DASHSCOPE_API_KEY 未配置");
        }
        if (preflight.getDashScopeBaseUrl() == null || preflight.getDashScopeBaseUrl().isBlank()
                || preflight.getAsrModel() == null || preflight.getAsrModel().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DashScope ASR 配置不完整");
        }
    }

    private void requireVideoAnalysisConfigured() {
        CreatorMediaProperties.Preflight preflight = properties.getPreflight();
        if (preflight.getDashScopeApiKey() == null || preflight.getDashScopeApiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DASHSCOPE_API_KEY 未配置");
        }
        if (preflight.getDashScopeBaseUrl() == null || preflight.getDashScopeBaseUrl().isBlank()
                || preflight.getVideoModel() == null || preflight.getVideoModel().isBlank()
                || preflight.getSegmentReviewModel() == null || preflight.getSegmentReviewModel().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DashScope 视频理解配置不完整");
        }
    }

    private PreflightReviewResponse toResponse(PreflightReviewRecord review) {
        List<PreflightReviewResponse.Step> steps = mapper.listSteps(review.reviewId()).stream()
                .map(step -> new PreflightReviewResponse.Step(
                        step.stepId(), step.stepType(), step.sequenceNo(), step.status(), step.attemptCount(),
                        step.providerTaskId(), step.errorCode(), step.errorMessage()
                )).toList();
        List<PreflightReviewResponse.Evidence> evidence = mapper.listEvidence(review.reviewId()).stream()
                .map(item -> new PreflightReviewResponse.Evidence(
                        item.evidenceId(), item.sourceType(), item.startMs(), item.endMs(), item.content(),
                        item.confidence(), item.assetId(), Boolean.TRUE.equals(item.assetAvailable()), item.metadataJson()
                )).toList();
        List<PreflightReviewResponse.Issue> issues = mapper.listIssues(review.reviewId()).stream()
                .map(item -> new PreflightReviewResponse.Issue(
                        item.issueId(), item.issueType(), item.dimension(), item.title(), item.description(),
                        item.startMs(), item.endMs(), item.severity(), item.confidence(),
                        stringList(item.evidenceRefs()), item.suggestedAction(),
                        Boolean.TRUE.equals(item.needsHumanReview()), stringList(item.affectedPersonas()),
                        item.userDisposition(), item.ignoreReason()
                )).toList();
        List<PreflightReviewResponse.AudienceScreening> audienceScreenings = mapper
                .listAudienceScreenings(review.reviewId()).stream()
                .map(item -> new PreflightReviewResponse.AudienceScreening(
                        item.screeningId(), item.personaType(), item.overallReaction(),
                        stringList(item.interestPoints()), stringList(item.confusionPoints()),
                        stringList(item.dropRisks()), stringList(item.evidenceRefs()), item.confidence()
                )).toList();
        List<PreflightReviewResponse.EditTask> editTasks = mapper.listEditTasks(review.reviewId()).stream()
                .map(this::toEditTaskResponse).toList();
        return new PreflightReviewResponse(
                review.reviewId(), review.taskId(), review.versionId(), review.status(), review.currentStep(),
                review.progressPercent(), review.eventSequence(), Boolean.TRUE.equals(review.cancelRequested()),
                review.attemptCount(), review.maxAttempts(), review.reviewFocus(), review.executiveSummary(),
                review.estimatedCostUsd(), review.actualCostUsd(), review.usageSeconds(), review.currency(),
                review.errorCode(), review.errorMessage(), review.startedAt(), review.completedAt(),
                review.createTime(), review.updateTime(), steps, evidence, issues,
                audienceScreenings, editTasks
        );
    }

    private PreflightReviewResponse.EditTask toEditTaskResponse(EditTaskRecord item) {
        return new PreflightReviewResponse.EditTask(
                item.editTaskId(), item.issueId(), item.title(), item.action(),
                item.startMs(), item.endMs(), item.priority(), item.targetOutcome(),
                item.status(), item.userNote(), item.completedAt(), item.updateTime()
        );
    }

    private void insertMissingStep(PreflightReviewRecord review, String type, int sequenceNo) {
        if (mapper.findStep(review.reviewId(), type).isPresent()) return;
        PreflightStepRecord step = new PreflightStepRecord(
                null, UUID.randomUUID().toString(), review.reviewId(), type, sequenceNo,
                "PENDING", 0, review.inputFingerprint(), null, null, null, null,
                null, null, null, null
        );
        if (mapper.insertStep(step) != 1) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "补全发布前试映步骤失败");
        }
    }

    private boolean isActive(String status) {
        return "QUEUED".equals(status) || "RUNNING".equals(status)
                || "RETRY_WAIT".equals(status) || "CANCEL_REQUESTED".equals(status);
    }

    private boolean isAllowedTransition(String current, String next) {
        return switch (current) {
            case "TODO" -> "IN_PROGRESS".equals(next) || "IGNORED".equals(next);
            case "IN_PROGRESS" -> "COMPLETED".equals(next) || "IGNORED".equals(next);
            case "IGNORED" -> "TODO".equals(next);
            default -> false;
        };
    }

    private List<String> stringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("体检问题证据引用解析失败", exception);
        }
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("试映任务配置序列化失败", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("试映输入摘要计算失败", exception);
        }
    }

    private record StepDefinition(String type, int sequenceNo) {
    }
}
