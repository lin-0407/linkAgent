package com.link.linkagent.creator.task.service;

import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorMaterialResponse;
import com.link.linkagent.creator.task.model.CreatorMaterialType;
import com.link.linkagent.creator.task.model.CreatorTaskCreateRequest;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.model.CreatorTaskResponse;
import com.link.linkagent.creator.task.model.CreatorTaskStatus;
import com.link.linkagent.creator.task.model.CreatorTaskSummaryRecord;
import com.link.linkagent.creator.task.model.CreatorTaskSummaryResponse;
import com.link.linkagent.util.NumberUtil;
import com.link.linkagent.util.TextUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 创作任务业务入口。
 * 当前只处理任务和材料输入，让后续 Agent 分析阶段拥有稳定的数据来源。
 */
@Service
public class CreatorTaskService {

    private static final String DEFAULT_USER_ID = "default";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final CreatorTaskMapper creatorTaskMapper;

    public CreatorTaskService(CreatorTaskMapper creatorTaskMapper) {
        this.creatorTaskMapper = creatorTaskMapper;
    }

    @Transactional
    public CreatorTaskResponse createTask(CreatorTaskCreateRequest request) {
        String taskId = UUID.randomUUID().toString();
        String userId = normalizeUserId(request.userId());
        String taskName = normalizeTaskName(request.taskName(), request.titleDraft());

        CreatorTaskRecord taskRecord = new CreatorTaskRecord();
        taskRecord.setTaskId(taskId);
        taskRecord.setUserId(userId);
        taskRecord.setTaskName(taskName);
        taskRecord.setStatus(CreatorTaskStatus.DRAFT.name());
        creatorTaskMapper.insertTask(taskRecord);

        for (CreatorMaterialRecord materialRecord : buildMaterials(taskId, request)) {
            creatorTaskMapper.upsertMaterial(materialRecord);
        }

        return getTask(taskId);
    }

    public List<CreatorTaskSummaryResponse> listTasks(String userId, Integer limit) {
        return creatorTaskMapper.listTasksByUser(
                        normalizeUserId(userId),
                        NumberUtil.limitOrDefault(limit, DEFAULT_LIMIT, MAX_LIMIT)
                )
                .stream()
                .map(this::toTaskSummaryResponse)
                .toList();
    }

    public CreatorTaskResponse getTask(String taskId) {
        CreatorTaskRecord taskRecord = creatorTaskMapper.findTaskByTaskId(taskId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在"));
        List<CreatorMaterialResponse> materials = creatorTaskMapper.listMaterialsByTaskId(taskRecord.getTaskId()).stream()
                .map(this::toMaterialResponse)
                .toList();
        return toTaskResponse(taskRecord, materials);
    }

    private List<CreatorMaterialRecord> buildMaterials(String taskId, CreatorTaskCreateRequest request) {
        List<CreatorMaterialRecord> records = new ArrayList<>();
        addMaterial(records, taskId, CreatorMaterialType.TITLE_DRAFT, request.titleDraft());
        addMaterial(records, taskId, CreatorMaterialType.DESCRIPTION_DRAFT, request.descriptionDraft());
        addMaterial(records, taskId, CreatorMaterialType.MANUSCRIPT, request.manuscript());
        addMaterial(records, taskId, CreatorMaterialType.SUBTITLE, request.subtitle());
        return records;
    }

    private void addMaterial(List<CreatorMaterialRecord> records,
                             String taskId,
                             CreatorMaterialType materialType,
                             String content) {
        if (TextUtil.isBlank(content)) {
            return;
        }
        CreatorMaterialRecord record = new CreatorMaterialRecord();
        record.setTaskId(taskId);
        record.setMaterialType(materialType.name());
        record.setContent(content.trim());
        records.add(record);
    }

    private CreatorTaskResponse toTaskResponse(CreatorTaskRecord record, List<CreatorMaterialResponse> materials) {
        return new CreatorTaskResponse(
                record.getId(),
                record.getTaskId(),
                record.getUserId(),
                record.getTaskName(),
                record.getStatus(),
                record.getCreateTime(),
                record.getUpdateTime(),
                materials
        );
    }

    private CreatorMaterialResponse toMaterialResponse(CreatorMaterialRecord record) {
        return new CreatorMaterialResponse(
                record.getId(),
                record.getMaterialType(),
                record.getContent(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }

    private CreatorTaskSummaryResponse toTaskSummaryResponse(CreatorTaskSummaryRecord record) {
        return new CreatorTaskSummaryResponse(
                record.getId(),
                record.getTaskId(),
                record.getUserId(),
                record.getTaskName(),
                record.getStatus(),
                record.getMaterialCount(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }

    private String normalizeUserId(String userId) {
        return TextUtil.trimToDefault(userId, DEFAULT_USER_ID);
    }

    private String normalizeTaskName(String taskName, String titleDraft) {
        if (TextUtil.hasText(taskName)) {
            return taskName.trim();
        }
        if (TextUtil.hasText(titleDraft)) {
            return TextUtil.abbreviate(titleDraft.trim(), 40);
        }
        return "未命名创作任务";
    }
}
