package com.link.linkagent.creator.media.workflow;

import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.processing.mapper.MediaProcessingMapper;
import com.link.linkagent.creator.media.processing.model.MediaProcessingJobRecord;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewRecord;
import com.link.linkagent.creator.media.probe.service.DraftVideoProbeRecoveryService;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import com.link.linkagent.creator.media.upload.model.DraftVideoStatus;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.workflow.mapper.CreatorWorkflowMapper;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowSessionRecord;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStage;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 阶段 7 发布后流程门禁。
 * <p>
 * 成片上传成功不代表已完成发布前检查。BV 绑定检查当前成片，反馈等后续操作优先检查明确的发布版本，
 * 必须通过 FFprobe、媒体预处理和发布前试映，才能避免绕过“成片试映 -> 实际发布”的检查。
 */
@Service
public class CreatorMediaWorkflowGateService {

    private final CreatorMediaProperties mediaProperties;
    private final MediaUploadMapper mediaUploadMapper;
    private final MediaProcessingMapper mediaProcessingMapper;
    private final PreflightReviewMapper preflightReviewMapper;
    private final CreatorWorkflowMapper creatorWorkflowMapper;
    private final CreatorSuggestionMapper creatorSuggestionMapper;
    private final DraftVideoProbeRecoveryService probeRecoveryService;

    public CreatorMediaWorkflowGateService(CreatorMediaProperties mediaProperties,
                                            MediaUploadMapper mediaUploadMapper,
                                            MediaProcessingMapper mediaProcessingMapper,
                                            PreflightReviewMapper preflightReviewMapper,
                                            CreatorWorkflowMapper creatorWorkflowMapper,
                                            CreatorSuggestionMapper creatorSuggestionMapper,
                                            DraftVideoProbeRecoveryService probeRecoveryService) {
        this.mediaProperties = mediaProperties;
        this.mediaUploadMapper = mediaUploadMapper;
        this.mediaProcessingMapper = mediaProcessingMapper;
        this.preflightReviewMapper = preflightReviewMapper;
        this.creatorWorkflowMapper = creatorWorkflowMapper;
        this.creatorSuggestionMapper = creatorSuggestionMapper;
        this.probeRecoveryService = probeRecoveryService;
    }

    /** BV 事务先锁任务再读取状态，避免检查旧版后却把刚上传的新版关联到 BV。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForVideoBinding(String taskId, String ownerId) {
        mediaUploadMapper.lockMediaTask(taskId.trim(), ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在"));
        if (mediaUploadMapper.countActiveCreationRuns(taskId.trim(), ownerId) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "制作运行尚未结束，不能绑定 BV");
        }
        if (mediaUploadMapper.findCurrentUpload(taskId.trim(), ownerId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "成片仍在上传或确认，请完成后再绑定 BV");
        }
    }

    /** 同步先与上传、重新定位共用任务锁；任务已删除时只跳过该绑定。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockTaskForBindingSync(String taskId, String ownerId) {
        return mediaUploadMapper.lockMediaTask(taskId.trim(), ownerId).isPresent();
    }

    /** 显式绑定固定本次通过检查的当前版本，不能沿用待修正绑定中的旧版本。 */
    public String requireReadyVersionForBinding(String taskId, String ownerId) {
        return requireReadyDraft(taskId, ownerId, "BV绑定",
                () -> mediaUploadMapper.findDraftVideo(taskId.trim(), ownerId)).versionId();
    }

    /** 延迟同步只能验证绑定时选定的版本，不能在当前版通过后替旧版放行。 */
    public void ensureBoundVersionReady(String taskId, String ownerId, String versionId) {
        if (versionId == null || versionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "历史绑定未记录发布版本，请重新确认 BV 绑定");
        }
        requireReadyDraft(taskId, ownerId, "BV绑定",
                () -> mediaUploadMapper.findDraftVideoByVersion(taskId.trim(), ownerId, versionId));
    }

    /**
     * 检查媒体能力总开关。
     * <p>
     * 该检查应在读取任务或执行外部脚本前调用，确保关闭能力时不会写入下游发布后数据。
     */
    public void ensureMediaEnabled(String nextStageName) {
        if (!mediaProperties.isEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "发布前试映能力未启用，不能进入" + nextStageName + "阶段。"
            );
        }
    }

    /**
     * 已有明确 BV 发布版本时检查该版本，否则沿用当前成片的媒体处理与完整试映检查。
     *
     * @param taskId        创作任务 ID
     * @param ownerId       当前单人工作台可信归属
     * @param nextStageName 即将进入的发布后阶段名称
     */
    public void ensureReadyForPostPublish(String taskId, String ownerId, String nextStageName) {
        requireReadyDraft(taskId, ownerId, nextStageName, () -> findPostPublishDraft(taskId, ownerId));
    }

    private DraftVideoRecord requireReadyDraft(String taskId, String ownerId, String nextStageName,
                                               Supplier<Optional<DraftVideoRecord>> candidate) {
        ensureMediaEnabled(nextStageName);
        Optional<DraftVideoRecord> selected = candidate.get();
        if (selected.isPresent()) {
            ensureVersionContextOrLegacyConfirmation(taskId, ownerId, selected.get().versionId(), nextStageName);
        } else {
            requirePrePublishConfirmed(taskId, ownerId, nextStageName);
        }
        DraftVideoRecord draft = selected
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "请先上传成片并完成媒体探测，才能进入" + nextStageName + "阶段。"
                ));
        draft = recoverStaleProbeIfNecessary(draft);
        PostPublishReadiness readiness = evaluatePostPublishReadiness(
                taskId.trim(), ownerId, nextStageName, draft);
        if (!readiness.ready()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, readiness.blockingReason());
        }
        return draft;
    }

    private Optional<DraftVideoRecord> findPostPublishDraft(String taskId, String ownerId) {
        Optional<String> published = mediaUploadMapper.findPublishedVideoVersionId(taskId.trim(), ownerId);
        // 已记录的发布版本若不存在或被删除，必须阻断，不能回退到另一个当前版本。
        return published.isPresent()
                ? mediaUploadMapper.findDraftVideoByVersion(taskId.trim(), ownerId, published.get())
                : mediaUploadMapper.findDraftVideo(taskId.trim(), ownerId);
    }

    /**
     * 只读查询任务能否进入发布后流程。
     * <p>
     * 查询接口只展示当前持久化事实，因此不能调用陈旧探测恢复；否则用户打开视频分析页就会
     * 隐式改写媒体状态。写接口仍由 {@link #ensureReadyForPostPublish(String, String, String)}
     * 保留原有恢复行为和 409 门禁。
     */
    public PostPublishReadiness inspectPostPublishReadiness(String taskId,
                                                            String ownerId,
                                                            String nextStageName) {
        try {
            ensureMediaEnabled(nextStageName);
            Optional<DraftVideoRecord> selected = findPostPublishDraft(taskId, ownerId);
            if (selected.isPresent()) {
                ensureVersionContextOrLegacyConfirmation(taskId, ownerId, selected.get().versionId(), nextStageName);
                return evaluatePostPublishReadiness(taskId.trim(), ownerId, nextStageName, selected.get());
            }
            requirePrePublishConfirmed(taskId, ownerId, nextStageName);
            return new PostPublishReadiness(false, "请先上传成片并完成媒体探测，才能进入" + nextStageName + "阶段。");
        } catch (ResponseStatusException exception) {
            return new PostPublishReadiness(false, exception.getReason());
        }
    }

    /** 有验证过的不可变上下文就读取原依据；旧任务仍沿用真实确认门禁。 */
    public boolean ensureVersionContextOrLegacyConfirmation(String taskId, String ownerId,
                                                             String versionId, String nextStageName) {
        ensureMediaEnabled(nextStageName);
        var context = mediaUploadMapper.findVersionContext(taskId.trim(), ownerId, versionId);
        if (context.isPresent()) {
            var value = context.get();
            if (!value.planningSkipped() && (value.publishingSessionId() == null
                    || value.publishingResultId() == null || value.productionPlanId() == null)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "成片制作上下文不完整，请重新确认");
            }
            return true;
        }
        requirePrePublishConfirmed(taskId, ownerId, nextStageName);
        return false;
    }

    /** 选版和绑定使用同一套精确版本的试映判断。 */
    public void ensureVersionReady(String taskId, String ownerId, String versionId, String nextStageName) {
        requireReadyDraft(taskId, ownerId, nextStageName,
                () -> mediaUploadMapper.findDraftVideoByVersion(taskId.trim(), ownerId, versionId));
    }

    private PostPublishReadiness evaluatePostPublishReadiness(String taskId,
                                                               String ownerId,
                                                               String nextStageName,
                                                               DraftVideoRecord draft) {
        if (DraftVideoStatus.READY_FOR_REVIEW.name().equals(draft.status())) {
            MediaProcessingJobRecord processingJob = mediaProcessingMapper
                    .findCurrentJob(taskId, ownerId, draft.versionId())
                    .orElse(null);
            if (processingJob == null) {
                return new PostPublishReadiness(
                        false,
                        "请先生成预览成片并完成媒体预处理，才能进入" + nextStageName + "阶段。"
                );
            }
            if ("COMPLETED".equals(processingJob.status())) {
                return evaluateCompletedPreflightReview(
                        taskId, ownerId, draft.versionId(), processingJob.jobId(), nextStageName);
            }
            if ("QUEUED".equals(processingJob.status()) || "RUNNING".equals(processingJob.status())) {
                return new PostPublishReadiness(
                        false,
                        "成片正在生成预览和关键画面，请等待完成后再进入" + nextStageName + "阶段。"
                );
            }
            return new PostPublishReadiness(
                    false,
                    "成片媒体预处理尚未完成，不能进入" + nextStageName + "阶段。"
            );
        }
        if (DraftVideoStatus.PROBING.name().equals(draft.status())) {
            return new PostPublishReadiness(
                    false,
                    "成片正在媒体探测中，请等待完成后再进入" + nextStageName + "阶段。"
            );
        }
        return new PostPublishReadiness(
                false,
                "成片尚未通过媒体探测，不能进入" + nextStageName + "阶段。"
        );
    }

    private PostPublishReadiness evaluateCompletedPreflightReview(String taskId,
                                                                  String ownerId,
                                                                  String versionId,
                                                                  String processingJobId,
                                                                  String nextStageName) {
        PreflightReviewRecord review = preflightReviewMapper.findCurrentByVersion(taskId, ownerId, versionId)
                .orElse(null);
        if (review == null) {
            return new PostPublishReadiness(
                    false,
                    "请先完成发布前试映，才能进入" + nextStageName + "阶段。"
            );
        }
        if (!processingJobId.equals(review.processingJobId())) {
            return new PostPublishReadiness(
                    false,
                    "当前发布前试映不对应最新媒体预处理结果，请重新完成试映后再进入" + nextStageName + "阶段。"
            );
        }
        if ("COMPLETED".equals(review.status())) {
            return new PostPublishReadiness(true, null);
        }
        if ("QUEUED".equals(review.status())
                || "RUNNING".equals(review.status())
                || "RETRY_WAIT".equals(review.status())
                || "CANCEL_REQUESTED".equals(review.status())) {
            return new PostPublishReadiness(
                    false,
                    "发布前试映仍在处理中，请等待完成后再进入" + nextStageName + "阶段。"
            );
        }
        return new PostPublishReadiness(
                false,
                "发布前试映尚未完成，请处理失败或取消的任务后再进入" + nextStageName + "阶段。"
        );
    }

    /** 只暴露就绪结论和可直接展示的阻塞原因，避免查询调用方复制门禁判断。 */
    public record PostPublishReadiness(boolean ready, String blockingReason) {
    }

    /**
     * 校验成片上传前的发布方案确认事实。
     * <p>
     * 普通任务必须读取最新 PRE_PUBLISH 会话的 CONFIRMED 状态；只有用户通过已有成片入口
     * 显式保存 planning_skipped 后才能跳过，避免直接 API 从普通草稿任务发起成片上传。
     */
    public void ensurePrePublishConfirmed(String taskId, String ownerId, String nextStageName) {
        ensureMediaEnabled(nextStageName);
        requirePrePublishConfirmed(taskId, ownerId, nextStageName);
    }

    private void requirePrePublishConfirmed(String taskId, String ownerId, String nextStageName) {
        String normalizedTaskId = taskId.trim();
        // 已有成片任务由用户显式选择跳过前期策划，不能再要求补造发布方案。
        if (mediaUploadMapper.countPlanningSkippedTask(normalizedTaskId, ownerId) == 1) {
            return;
        }
        CreatorWorkflowSessionRecord session = creatorWorkflowMapper.findLatestSession(
                        normalizedTaskId,
                        CreatorWorkflowStage.PRE_PUBLISH.name()
                )
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "请先确认发布方案，才能进入" + nextStageName + "阶段。"
                ));
        if (!CreatorWorkflowStatus.CONFIRMED.name().equals(session.getStatus())
                || session.getConfirmedResultId() == null
                || session.getConfirmedResultId().isBlank()
                || !ownerId.equals(session.getUserId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "请先确认发布方案，才能进入" + nextStageName + "阶段。"
            );
        }
        CreatorSuggestionRecord suggestion = creatorSuggestionMapper.findByTaskId(normalizedTaskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "当前发布方案已经变化，请重新确认后再进入" + nextStageName + "阶段。"
                ));
        if (!session.getConfirmedResultId().equals(suggestion.getSuggestionId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "当前发布方案已经变化，请重新确认后再进入" + nextStageName + "阶段。"
            );
        }
    }

    /**
     * 在流程门禁读取到超时探测时主动恢复状态。
     * <p>
     * 不能要求调用方必须先打开试映页才能恢复服务中断留下的 PROBING 状态，
     * 否则直接调用反馈或 BV 绑定接口会被永久误判为“仍在探测中”。恢复动作由独立服务使用新事务，
     * 所以调用方随后返回的 409 不会回滚这次状态恢复。
     */
    private DraftVideoRecord recoverStaleProbeIfNecessary(DraftVideoRecord draft) {
        if (!probeRecoveryService.isStale(draft)) {
            return draft;
        }
        return probeRecoveryService.recover(draft);
    }
}
