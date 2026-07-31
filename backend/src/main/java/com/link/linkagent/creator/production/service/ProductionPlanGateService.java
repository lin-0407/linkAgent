package com.link.linkagent.creator.production.service;

import com.link.linkagent.creator.production.mapper.ProductionPlanMapper;
import com.link.linkagent.creator.production.model.ProductionPlanRecord;
import com.link.linkagent.creator.production.model.ProductionPlanStatus;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.workflow.mapper.CreatorWorkflowMapper;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowSessionRecord;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStage;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 阶段 7 P0-1/P0-2 流程门禁；普通任务检查蓝图，已有成片任务检查显式跳过事实。 */
@Service
public class ProductionPlanGateService {

    private final ProductionPlanMapper planMapper;
    private final CreatorWorkflowMapper workflowMapper;
    private final CreatorSuggestionMapper suggestionMapper;

    public ProductionPlanGateService(ProductionPlanMapper planMapper,
                                     CreatorWorkflowMapper workflowMapper,
                                     CreatorSuggestionMapper suggestionMapper) {
        this.planMapper = planMapper;
        this.workflowMapper = workflowMapper;
        this.suggestionMapper = suggestionMapper;
    }

    public void ensurePrePublishConfirmed(String taskId, String ownerId, String nextStageName) {
        CreatorWorkflowSessionRecord session = workflowMapper.findLatestSession(
                        taskId.trim(), CreatorWorkflowStage.PRE_PUBLISH.name())
                .orElseThrow(() -> conflict("请先确认发布方案，才能进入" + nextStageName + "阶段。"));
        if (!ownerId.equals(session.getUserId())
                || !CreatorWorkflowStatus.CONFIRMED.name().equals(session.getStatus())
                || session.getConfirmedResultId() == null
                || session.getConfirmedResultId().isBlank()) {
            throw conflict("请先确认发布方案，才能进入" + nextStageName + "阶段。");
        }
        CreatorSuggestionRecord suggestion = suggestionMapper.findByTaskId(taskId.trim())
                .orElseThrow(() -> conflict("当前发布方案已经变化，请重新确认后再进入" + nextStageName + "阶段。"));
        if (!session.getConfirmedResultId().equals(suggestion.getSuggestionId())) {
            throw conflict("当前发布方案已经变化，请重新确认后再进入" + nextStageName + "阶段。");
        }
    }

    public void ensureRepositionAllowed(String taskId, String ownerId) {
        if (planMapper.countFinalizedPublishingFacts(taskId.trim(), ownerId) > 0) {
            throw conflict("视频已经发布或完成 BV 绑定，不能重新定位制作蓝图，请创建修订任务。");
        }
    }

    public ProductionPlanRecord requireReady(String taskId, String ownerId) {
        ProductionPlanRecord plan = planMapper.findCurrentPlan(taskId.trim(), ownerId)
                .orElseThrow(() -> conflict("请先生成制作蓝图，才能进入成片试映阶段。"));
        if (!ProductionPlanStatus.READY.name().equals(plan.status())) {
            throw conflict("制作蓝图尚未生成完成，暂不能进入成片试映阶段。");
        }
        if (planMapper.countIncompleteSteps(taskId.trim(), plan.planId()) > 0) {
            throw conflict("请先完成或跳过制作蓝图中的全部步骤，才能进入成片试映阶段。");
        }
        return plan;
    }

    /** 已有成片任务允许跳过蓝图；普通任务继续使用原有完整性门禁。 */
    public void ensureReadyForPreflight(String taskId, String ownerId) {
        if (planMapper.countPlanningSkippedTask(taskId.trim(), ownerId) == 1) {
            return;
        }
        requireReady(taskId, ownerId);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
