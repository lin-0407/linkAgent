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
import org.springframework.web.server.ResponseStatusException;

/**
 * 阶段 7 发布后流程门禁。
 * <p>
 * 成片上传成功不代表已完成发布前检查。反馈、BV 绑定等发布后写入操作必须确认当前成片
 * 已通过 FFprobe、媒体预处理和发布前试映，才能避免任务绕过“确认发布方案 -> 成片试映 -> 实际发布”的主流程。
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
     * 检查任务的当前成片是否已通过媒体探测、P0-2 媒体预处理和 P0-4a 完整发布前体检。
     *
     * @param taskId        创作任务 ID
     * @param ownerId       当前单人工作台可信归属
     * @param nextStageName 即将进入的发布后阶段名称
     */
    public void ensureReadyForPostPublish(String taskId, String ownerId, String nextStageName) {
        ensureMediaEnabled(nextStageName);
        requirePrePublishConfirmed(taskId, ownerId, nextStageName);
        DraftVideoRecord draft = mediaUploadMapper.findDraftVideo(taskId.trim(), ownerId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "请先上传成片并完成媒体探测，才能进入" + nextStageName + "阶段。"
                ));
        draft = recoverStaleProbeIfNecessary(draft);
        if (DraftVideoStatus.READY_FOR_REVIEW.name().equals(draft.status())) {
            MediaProcessingJobRecord processingJob = mediaProcessingMapper
                    .findCurrentJob(taskId.trim(), ownerId, draft.versionId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "请先生成预览成片并完成媒体预处理，才能进入" + nextStageName + "阶段。"
                    ));
            if ("COMPLETED".equals(processingJob.status())) {
                requireCompletedPreflightReview(
                        taskId.trim(), ownerId, draft.versionId(), processingJob.jobId(), nextStageName);
                return;
            }
            if ("QUEUED".equals(processingJob.status()) || "RUNNING".equals(processingJob.status())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "成片正在生成预览和关键画面，请等待完成后再进入" + nextStageName + "阶段。"
                );
            }
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "成片媒体预处理尚未完成，不能进入" + nextStageName + "阶段。"
            );
        }
        if (DraftVideoStatus.PROBING.name().equals(draft.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "成片正在媒体探测中，请等待完成后再进入" + nextStageName + "阶段。"
            );
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "成片尚未通过媒体探测，不能进入" + nextStageName + "阶段。"
        );
    }

    private void requireCompletedPreflightReview(String taskId,
                                                 String ownerId,
                                                 String versionId,
                                                 String processingJobId,
                                                 String nextStageName) {
        PreflightReviewRecord review = preflightReviewMapper.findCurrentByVersion(taskId, ownerId, versionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "请先完成发布前试映，才能进入" + nextStageName + "阶段。"
                ));
        if (!processingJobId.equals(review.processingJobId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "当前发布前试映不对应最新媒体预处理结果，请重新完成试映后再进入" + nextStageName + "阶段。"
            );
        }
        if ("COMPLETED".equals(review.status())) {
            return;
        }
        if ("QUEUED".equals(review.status())
                || "RUNNING".equals(review.status())
                || "RETRY_WAIT".equals(review.status())
                || "CANCEL_REQUESTED".equals(review.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "发布前试映仍在处理中，请等待完成后再进入" + nextStageName + "阶段。"
            );
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "发布前试映尚未完成，请处理失败或取消的任务后再进入" + nextStageName + "阶段。"
        );
    }

    /**
     * 校验成片上传前的发布方案确认事实。
     * <p>
     * 任务主状态中的 PRE_PUBLISH_ANALYZED 只能说明生成过建议，不能证明用户已经采用；
     * 这里必须读取最新 PRE_PUBLISH 会话的 CONFIRMED 状态，避免直接 API 从草稿任务发起成片上传。
     */
    public void ensurePrePublishConfirmed(String taskId, String ownerId, String nextStageName) {
        ensureMediaEnabled(nextStageName);
        requirePrePublishConfirmed(taskId, ownerId, nextStageName);
    }

    private void requirePrePublishConfirmed(String taskId, String ownerId, String nextStageName) {
        String normalizedTaskId = taskId.trim();
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
