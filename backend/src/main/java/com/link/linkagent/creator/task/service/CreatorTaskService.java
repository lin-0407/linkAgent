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
import com.link.linkagent.creator.task.model.CreatorTaskUpdateRequest;
import com.link.linkagent.util.NumberUtil;
import com.link.linkagent.util.TextUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final long MATERIAL_IMPORT_FILE_MAX_SIZE = 5 * 1024 * 1024L;
    private static final int TITLE_DRAFT_MAX_LENGTH = 200;
    private static final int DESCRIPTION_DRAFT_MAX_LENGTH = 2000;
    private static final int LONG_MATERIAL_MAX_LENGTH = 20000;
    private static final List<String> SUPPORTED_MATERIAL_FILE_SUFFIXES = List.of(".txt", ".md", ".srt", ".ass");

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

    @Transactional
    public CreatorTaskResponse updateTask(String taskId, CreatorTaskUpdateRequest request) {
        String safeTaskId = normalizeTaskId(taskId);
        CreatorTaskRecord taskRecord = getTaskRecord(safeTaskId);
        List<CreatorMaterialRecord> currentMaterials = creatorTaskMapper.listMaterialsByTaskId(safeTaskId);
        boolean materialChanged = isMaterialChanged(currentMaterials, request);
        String taskName = normalizeTaskName(request.taskName(), request.titleDraft());

        creatorTaskMapper.updateTaskName(taskRecord.getTaskId(), taskName);
        refreshMaterials(taskRecord.getTaskId(), request);
        if (materialChanged) {
            // 材料变化后旧发布建议和复盘结论不应继续被状态链默认认可，因此退回草稿态让用户重新生成。
            creatorTaskMapper.updateTaskStatus(taskRecord.getTaskId(), CreatorTaskStatus.DRAFT.name());
        }

        return getTask(taskRecord.getTaskId());
    }

    @Transactional
    public CreatorTaskResponse importMaterial(String taskId, String materialType, MultipartFile file) {
        String safeTaskId = normalizeTaskId(taskId);
        CreatorTaskRecord taskRecord = getTaskRecord(safeTaskId);
        CreatorMaterialType safeMaterialType = parseMaterialType(materialType);
        validateMaterialImportFile(file);
        String importedContent = readMaterialImportText(file);
        validateMaterialContentLength(safeMaterialType, importedContent);

        String normalizedImportedContent = normalizeMaterialContent(importedContent);
        String currentContent = findCurrentMaterialContent(
                creatorTaskMapper.listMaterialsByTaskId(taskRecord.getTaskId()),
                safeMaterialType
        );
        refreshMaterial(taskRecord.getTaskId(), safeMaterialType, importedContent);
        if (!normalizedImportedContent.equals(currentContent)) {
            // 文件导入和手工编辑一样会改变分析输入，所以必须让用户重新生成后续建议。
            creatorTaskMapper.updateTaskStatus(taskRecord.getTaskId(), CreatorTaskStatus.DRAFT.name());
        }

        return getTask(taskRecord.getTaskId());
    }

    @Transactional
    public void deleteTask(String taskId) {
        String safeTaskId = normalizeTaskId(taskId);
        CreatorTaskRecord taskRecord = getTaskRecord(safeTaskId);
        int deleted = creatorTaskMapper.deleteTask(taskRecord.getTaskId(), CreatorTaskStatus.ARCHIVED.name());
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在");
        }
        // 删除任务只做逻辑删除，保留历史 Agent 产物，方便后续需要恢复或排障时仍有依据。
        creatorTaskMapper.deleteMaterialsByTaskId(taskRecord.getTaskId());
    }

    public List<CreatorTaskSummaryResponse> listTasks(String userId, Integer limit) {
        int safeLimit = NumberUtil.limitOrDefault(limit, DEFAULT_LIMIT, MAX_LIMIT);
        List<CreatorTaskSummaryRecord> records = TextUtil.hasText(userId)
                // userId 只作为未来多人隔离的兼容能力；当前单人工作台默认不应该被它过滤。
                ? creatorTaskMapper.listTasksByUser(userId.trim(), safeLimit)
                : creatorTaskMapper.listRecentTasks(safeLimit);
        return records
                .stream()
                .map(this::toTaskSummaryResponse)
                .toList();
    }

    public CreatorTaskResponse getTask(String taskId) {
        CreatorTaskRecord taskRecord = getTaskRecord(normalizeTaskId(taskId));
        List<CreatorMaterialResponse> materials = creatorTaskMapper
                .listMaterialsByTaskId(taskRecord.getTaskId())
                .stream()
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

    private void refreshMaterials(String taskId, CreatorTaskUpdateRequest request) {
        refreshMaterial(taskId, CreatorMaterialType.TITLE_DRAFT, request.titleDraft());
        refreshMaterial(taskId, CreatorMaterialType.DESCRIPTION_DRAFT, request.descriptionDraft());
        refreshMaterial(taskId, CreatorMaterialType.MANUSCRIPT, request.manuscript());
        refreshMaterial(taskId, CreatorMaterialType.SUBTITLE, request.subtitle());
    }

    private void refreshMaterial(String taskId, CreatorMaterialType materialType, String content) {
        if (TextUtil.isBlank(content)) {
            // 覆盖式编辑允许用户清空某类材料，软删除能避免旧内容继续被 Agent 读取。
            creatorTaskMapper.deleteMaterialByType(taskId, materialType.name());
            return;
        }

        CreatorMaterialRecord record = new CreatorMaterialRecord();
        record.setTaskId(taskId);
        record.setMaterialType(materialType.name());
        record.setContent(content.trim());
        creatorTaskMapper.upsertMaterial(record);
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

    private CreatorTaskRecord getTaskRecord(String taskId) {
        return creatorTaskMapper.findTaskByTaskId(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在"));
    }

    private String normalizeTaskId(String taskId) {
        if (TextUtil.isBlank(taskId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务ID不能为空");
        }
        return taskId.trim();
    }

    private CreatorMaterialType parseMaterialType(String materialType) {
        if (TextUtil.isBlank(materialType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "材料类型不能为空");
        }
        try {
            return CreatorMaterialType.valueOf(materialType.trim());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "材料类型不支持");
        }
    }

    private void validateMaterialImportFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入文件不能为空");
        }
        if (file.getSize() > MATERIAL_IMPORT_FILE_MAX_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入文件不能超过5MB");
        }

        String fileName = normalizeMaterialFileName(file.getOriginalFilename());
        boolean supported = SUPPORTED_MATERIAL_FILE_SUFFIXES
                .stream()
                .anyMatch(fileName::endsWith);
        if (!supported) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "创作材料导入只支持 TXT、MD、SRT 或 ASS 文本文件");
        }
    }

    private String normalizeMaterialFileName(String fileName) {
        if (TextUtil.isBlank(fileName)) {
            return "uploaded_material.txt";
        }
        return fileName.trim().toLowerCase(Locale.ROOT);
    }

    private String readMaterialImportText(MultipartFile file) {
        try {
            String text = new String(file.getBytes(), StandardCharsets.UTF_8);
            // 部分文本编辑器会写入 UTF-8 BOM，不去掉会让首行材料多出不可见字符。
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            String trimmedText = text.trim();
            if (TextUtil.isBlank(trimmedText)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入文件内容不能为空");
            }
            return trimmedText;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入文件读取失败");
        }
    }

    private void validateMaterialContentLength(CreatorMaterialType materialType, String content) {
        int maxLength = switch (materialType) {
            case TITLE_DRAFT -> TITLE_DRAFT_MAX_LENGTH;
            case DESCRIPTION_DRAFT -> DESCRIPTION_DRAFT_MAX_LENGTH;
            case MANUSCRIPT, SUBTITLE -> LONG_MATERIAL_MAX_LENGTH;
        };
        if (content.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, materialTypeLabel(materialType) + "导入内容不能超过 " + maxLength + " 个字符");
        }
    }

    private String materialTypeLabel(CreatorMaterialType materialType) {
        return switch (materialType) {
            case TITLE_DRAFT -> "标题草稿";
            case DESCRIPTION_DRAFT -> "简介草稿";
            case MANUSCRIPT -> "文稿";
            case SUBTITLE -> "字幕";
        };
    }

    private String findCurrentMaterialContent(List<CreatorMaterialRecord> currentMaterials,
                                              CreatorMaterialType materialType) {
        return currentMaterials
                .stream()
                .filter(record -> materialType.name().equals(record.getMaterialType()))
                .findFirst()
                .map(record -> normalizeMaterialContent(record.getContent()))
                .orElse("");
    }

    private boolean isMaterialChanged(List<CreatorMaterialRecord> currentMaterials,
                                      CreatorTaskUpdateRequest request) {
        Map<CreatorMaterialType, String> currentMaterialMap = new EnumMap<>(CreatorMaterialType.class);
        for (CreatorMaterialRecord materialRecord : currentMaterials) {
            currentMaterialMap.put(
                    CreatorMaterialType.valueOf(materialRecord.getMaterialType()),
                    normalizeMaterialContent(materialRecord.getContent())
            );
        }

        return isSingleMaterialChanged(currentMaterialMap, CreatorMaterialType.TITLE_DRAFT, request.titleDraft())
                || isSingleMaterialChanged(currentMaterialMap, CreatorMaterialType.DESCRIPTION_DRAFT, request.descriptionDraft())
                || isSingleMaterialChanged(currentMaterialMap, CreatorMaterialType.MANUSCRIPT, request.manuscript())
                || isSingleMaterialChanged(currentMaterialMap, CreatorMaterialType.SUBTITLE, request.subtitle());
    }

    private boolean isSingleMaterialChanged(Map<CreatorMaterialType, String> currentMaterialMap,
                                            CreatorMaterialType materialType,
                                            String nextContent) {
        return !normalizeMaterialContent(nextContent).equals(currentMaterialMap.getOrDefault(materialType, ""));
    }

    private String normalizeMaterialContent(String content) {
        return TextUtil.hasText(content) ? content.trim() : "";
    }
}
