package com.link.linkagent.creator.production.service;

import com.link.linkagent.creator.production.mapper.ProductionPlanMapper;
import com.link.linkagent.creator.production.model.ProductionPlanRecord;
import com.link.linkagent.creator.production.model.ProductionStepRecord;
import com.link.linkagent.creator.report.mapper.CreatorReviewInvalidationMapper;
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

    public ProductionPlanPersistenceService(ProductionPlanMapper mapper,
                                            CreatorReviewInvalidationMapper reviewInvalidationMapper) {
        this.mapper = mapper;
        this.reviewInvalidationMapper = reviewInvalidationMapper;
    }

    @Transactional
    public ProductionPlanRecord startGenerating(ProductionPlanRecord record) {
        mapper.deleteUnboundVideoBindingsForReposition(record.taskId(), record.ownerId());
        mapper.invalidateMediaUploads(record.taskId(), record.ownerId());
        mapper.invalidateMediaProcessingJobs(record.taskId(), record.ownerId());
        mapper.invalidatePreflightReviews(record.taskId(), record.ownerId());
        mapper.resetDraftVideosForReposition(record.taskId(), record.ownerId());
        reviewInvalidationMapper.invalidateFeedbackReport(record.taskId());
        reviewInvalidationMapper.invalidateCompetitorReport(record.taskId());
        reviewInvalidationMapper.invalidateCreatorReport(record.taskId());
        reviewInvalidationMapper.invalidateGeneratedPreference(record.taskId());
        mapper.resetTaskStatusForReposition(record.taskId(), record.ownerId());
        mapper.markCurrentPlansStale(record.taskId(), record.ownerId());
        mapper.insertPlan(record);
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
        if (mapper.updateStepStatus(taskId, planId, stepId, status, skipReason, rowVersion) != 1) {
            throw new IllegalStateException("制作步骤已被其他页面更新，请刷新后重试");
        }
    }

}
