package com.link.linkagent.creator.production.service;

import com.link.linkagent.creator.production.mapper.ProductionPlanMapper;
import com.link.linkagent.creator.production.model.ProductionPlanRecord;
import com.link.linkagent.creator.production.model.ProductionStepRecord;
import com.link.linkagent.creator.report.mapper.CreatorReviewInvalidationMapper;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 制作蓝图的事务边界。
 * LLM 和网页抓取不放进事务，只有状态切换及步骤批量落库使用事务，避免外部调用长期占用数据库连接。
 */
@Service
public class ProductionPlanPersistenceService {

    private final ProductionPlanMapper mapper;
    private final CreatorReviewInvalidationMapper reviewInvalidationMapper;
    private final MediaUploadMapper mediaUploadMapper;

    public ProductionPlanPersistenceService(ProductionPlanMapper mapper,
                                            CreatorReviewInvalidationMapper reviewInvalidationMapper,
                                            MediaUploadMapper mediaUploadMapper) {
        this.mapper = mapper;
        this.reviewInvalidationMapper = reviewInvalidationMapper;
        this.mediaUploadMapper = mediaUploadMapper;
    }

    @Transactional
    public ProductionPlanRecord startGenerating(ProductionPlanRecord record) {
        // 与上传完成及 BV 绑定共用任务锁，事务外的发布事实检查不能充当最终门禁。
        mediaUploadMapper.lockMediaTask(record.taskId(), record.ownerId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在"));
        if (mediaUploadMapper.countActiveCreationRuns(record.taskId(), record.ownerId()) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "制作运行尚未结束，不能重新定位");
        }
        if (mapper.countFinalizedPublishingFacts(record.taskId(), record.ownerId()) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "视频已经发布或完成 BV 绑定，请创建修订任务");
        }
        mediaUploadMapper.lockTaskDraftVersions(record.taskId(), record.ownerId());
        mediaUploadMapper.adoptMediaWorkspace(record.taskId(), record.ownerId());
        var workspace = mediaUploadMapper.findMediaWorkspace(record.taskId(), record.ownerId()).orElseThrow();
        mapper.deleteUnboundVideoBindingsForReposition(record.taskId(), record.ownerId());
        mapper.invalidateMediaUploads(record.taskId(), record.ownerId());
        mapper.invalidateMediaProcessingJobs(record.taskId(), record.ownerId());
        mapper.invalidatePreflightReviews(record.taskId(), record.ownerId());
        mapper.cancelUnfinishedDraftsForReposition(record.taskId(), record.ownerId());
        if (mediaUploadMapper.clearCurrentVideoForReposition(record.taskId(), record.ownerId(),
                workspace.workspaceRevision()) != 1) {
            throw new IllegalStateException("视频制作上下文已经变化，请刷新后重试");
        }
        reviewInvalidationMapper.invalidateFeedbackReport(record.taskId());
        reviewInvalidationMapper.invalidateCompetitorReport(record.taskId());
        reviewInvalidationMapper.invalidateCreatorReport(record.taskId());
        reviewInvalidationMapper.invalidateGeneratedPreference(record.taskId());
        mapper.resetTaskStatusForReposition(record.taskId(), record.ownerId());
        mapper.markCurrentPlansStale(record.taskId(), record.ownerId());
        if (mapper.insertPlan(record) != 1) {
            throw new IllegalStateException("制作蓝图保存失败");
        }
        return record;
    }

    @Transactional
    public void markReady(String planId,
                          String planTitle,
                          String positioningSummary,
                          String toolPreferences,
                          String sourceSnapshot,
                          String rawOutput,
                          String promptVersion,
                          List<ProductionStepRecord> steps) {
        for (ProductionStepRecord step : steps) {
            mapper.insertStep(step);
        }
        if (mapper.markPlanReady(planId, planTitle, positioningSummary, toolPreferences,
                sourceSnapshot, rawOutput, promptVersion) != 1) {
            throw new IllegalStateException("制作蓝图状态已变化，无法切换为 READY");
        }
    }

    @Transactional
    public void markFailed(String planId, String failureMessage) {
        mapper.markPlanFailed(planId, failureMessage);
    }

    @Transactional
    public void updateStep(String taskId,
                           String planId,
                           String stepId,
                           String status,
                           String skipReason,
                           long rowVersion) {
        if (mapper.countAutomaticStep(taskId, planId, stepId) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "系统制作步骤由真实产物更新，不能手工勾选或跳过");
        }
        if (mapper.updateStepStatus(taskId, planId, stepId, status, skipReason, rowVersion) != 1) {
            throw new IllegalStateException("制作步骤已被其他页面更新，请刷新后重试");
        }
    }

}
