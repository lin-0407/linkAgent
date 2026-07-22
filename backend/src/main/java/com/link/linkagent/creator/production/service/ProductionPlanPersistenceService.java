package com.link.linkagent.creator.production.service;

import com.link.linkagent.creator.production.mapper.ProductionPlanMapper;
import com.link.linkagent.creator.production.model.ProductionBlueprintStepOutput;
import com.link.linkagent.creator.production.model.ProductionPlanRecord;
import com.link.linkagent.creator.production.model.ProductionPlanStatus;
import com.link.linkagent.creator.production.model.ProductionStepRecord;
import com.link.linkagent.creator.production.model.ProductionStepStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 制作蓝图的事务边界。
 * LLM 和网页抓取不放进事务，只有状态切换及步骤批量落库使用事务，避免外部调用长期占用数据库连接。
 */
@Service
public class ProductionPlanPersistenceService {

    private final ProductionPlanMapper mapper;

    public ProductionPlanPersistenceService(ProductionPlanMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public ProductionPlanRecord startGenerating(ProductionPlanRecord record) {
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

    public ProductionStepRecord toStepRecord(String planId,
                                              String taskId,
                                              int sequenceNo,
                                              ProductionBlueprintStepOutput output,
                                              String toolRefs,
                                              String prerequisites,
                                              String operations,
                                              String expectedOutputs,
                                              String acceptanceCriteria) {
        return new ProductionStepRecord(
                null,
                UUID.randomUUID().toString(),
                planId,
                taskId,
                sequenceNo,
                output.phase(),
                output.stepName(),
                output.objective(),
                prerequisites,
                operations,
                toolRefs,
                expectedOutputs,
                acceptanceCriteria,
                output.difficulty(),
                output.required() == null || output.required(),
                ProductionStepStatus.PENDING.name(),
                0L,
                null,
                null,
                null
        );
    }
}
