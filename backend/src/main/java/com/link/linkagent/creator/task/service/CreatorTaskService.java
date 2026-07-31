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
 * 创作任务业务入口 —— 管理创作任务的完整生命周期（创建/编辑/材料导入/删除/查询）。
 * <p>
 * 核心职责：为后续 Agent 分析阶段（发布前优化、评论分析等）提供稳定的任务和材料数据来源。
 * 任务的状态推进由工作流服务（{@link com.link.linkagent.creator.workflow.service.CreatorWorkflowService}）
 * 在确认环节驱动，本类只负责基础 CRUD 和输入校验。
 * <p>
 * 架构定位：位于领域服务层，只依赖 {@link CreatorTaskMapper} 做数据访问，
 * 不调用 LLM/Agent 或记忆层——保证任务管理的纯粹性和低耦合。
 * <p>
 * 材料变更的回退策略（updateTask / importMaterial）：任务仍处于 DRAFT 时，材料或视频类型发生变化会
 * 自动将任务状态保持为 DRAFT。发布方案确认后，任务材料会成为后续制作和竞品分析的事实基线，
 * 不再允许原地修改，避免历史建议、制作结果与任务输入发生错位。
 */
@Service
public class CreatorTaskService {

    // —— 业务常量：不可变配置，集中管理便于统一调整 ——

    /** 匿名用户默认标识，与其他服务保持一致的 "default" */
    private static final String DEFAULT_USER_ID = "default";
    /** 视频类型未指定时的默认值 */
    private static final String DEFAULT_VIDEO_TYPE = "未分类";
    /** 任务列表默认返回条数，覆盖大多数场景的需求 */
    private static final int DEFAULT_LIMIT = 20;
    /** 任务列表最大返回条数，防止前端一次性拉取过多数据导致性能问题 */
    private static final int MAX_LIMIT = 100;
    /** 材料文件导入的最大体积：5MB。B 站字幕/文稿通常不会超过此大小 */
    private static final long MATERIAL_IMPORT_FILE_MAX_SIZE = 5 * 1024 * 1024L;
    /** 标题草稿最大字符数（B 站标题长度上限 80 字，留足余量） */
    private static final int TITLE_DRAFT_MAX_LENGTH = 200;
    /** 简介草稿最大字符数（B 站简介推荐 200-500 字，留足长文本空间） */
    private static final int DESCRIPTION_DRAFT_MAX_LENGTH = 2000;
    /** 长材料（文稿/字幕）最大字符数，超过则截断拒绝 */
    private static final int LONG_MATERIAL_MAX_LENGTH = 20000;
    /** 支持的材料文件导入格式：纯文本 / Markdown / SRT 字幕 / ASS 字幕 */
    private static final List<String> SUPPORTED_MATERIAL_FILE_SUFFIXES = List.of(".txt", ".md", ".srt", ".ass");

    /** 创作任务数据访问，唯一的持久化依赖 */
    private final CreatorTaskMapper creatorTaskMapper;

    public CreatorTaskService(CreatorTaskMapper creatorTaskMapper) {
        this.creatorTaskMapper = creatorTaskMapper;
    }

    /**
     * 创建创作任务并初始化材料记录。
     * <p>
     * 创建流程：
     * <ol>
     *   <li>生成 taskId（UUID），规范化 userId/taskName/videoType</li>
     *   <li>插入任务记录，初始状态为 DRAFT</li>
     *   <li>将请求中的四类材料（标题草稿/简介草稿/文稿/字幕）分别 upsert 到材料表</li>
     *   <li>返回完整的任务详情（含全部材料）</li>
     * </ol>
     * <p>
     * 为什么 taskName 可以从 titleDraft 回退：创建任务时用户可能只提供了标题草稿，
     * 自动截取前 40 字作为任务名，降低用户操作负担。
     *
     * @param request 创建请求，含任务名称、视频类型和可选的四类材料内容
     * @return 创建后的完整任务响应
     */
    @Transactional
    public CreatorTaskResponse createTask(CreatorTaskCreateRequest request) {
        String taskId = UUID.randomUUID().toString();
        String userId = normalizeUserId(request.userId());
        String taskName = normalizeTaskName(request.taskName(), request.titleDraft());
        String videoType = normalizeVideoType(request.videoType());

        CreatorTaskRecord taskRecord = new CreatorTaskRecord();
        taskRecord.setTaskId(taskId);
        taskRecord.setUserId(userId);
        taskRecord.setTaskName(taskName);
        taskRecord.setVideoType(videoType);
        taskRecord.setStatus(CreatorTaskStatus.DRAFT.name());
        creatorTaskMapper.insertTask(taskRecord);

        for (CreatorMaterialRecord materialRecord : buildMaterials(taskId, request)) {
            creatorTaskMapper.upsertMaterial(materialRecord);
        }

        return getTask(taskId);
    }

    /**
     * 更新创作任务的基本信息和材料内容。
     * <p>
     * 关键行为：仅 DRAFT 任务可更新；当检测到材料内容发生变化或视频类型变更时，
     * 任务保持在 DRAFT，要求用户重新生成发布方案。发布方案确认后任务会被锁定，
     * 防止后续反馈和复盘引用的基线与用户原地覆盖后的材料不一致。
     * <p>
     * 为什么材料为空时做软删除而非报错：覆盖式编辑允许用户清空某类材料，
     * 不想填的内容置空是合理操作，不应阻止。
     *
     * @param taskId  任务ID
     * @param request 更新请求，含任务名称、视频类型和可选的四类材料
     * @return 更新后的完整任务响应
     */
    @Transactional
    public CreatorTaskResponse updateTask(String taskId, CreatorTaskUpdateRequest request) {
        String safeTaskId = normalizeTaskId(taskId);
        CreatorTaskRecord taskRecord = getTaskRecord(safeTaskId);
        ensureTaskMaterialsEditable(taskRecord);
        List<CreatorMaterialRecord> currentMaterials = creatorTaskMapper.listMaterialsByTaskId(safeTaskId);
        boolean materialChanged = isMaterialChanged(currentMaterials, request);
        String taskName = normalizeTaskName(request.taskName(), request.titleDraft());
        String videoType = normalizeVideoType(request.videoType());
        boolean videoTypeChanged = !videoType.equals(normalizeVideoType(taskRecord.getVideoType()));

        creatorTaskMapper.updateTaskBasicInfo(taskRecord.getTaskId(), taskName, videoType);
        refreshMaterials(taskRecord.getTaskId(), request);
        if (materialChanged || videoTypeChanged) {
            // 材料或视频类型变化后，旧建议对应的分析输入已经不同，必须退回草稿态让用户重新生成。
            creatorTaskMapper.updateTaskStatus(taskRecord.getTaskId(), CreatorTaskStatus.DRAFT.name());
        }

        return getTask(taskRecord.getTaskId());
    }

    /**
     * 从文件导入材料内容（支持 TXT/MD/SRT/ASS 格式）。
     * <p>
     * 校验链：文件非空 → 大小不超过 5MB → 后缀名在支持列表中 → 内容非空（去 BOM 后）→
     * 内容长度在类型对应的上限内。
     * <p>
     * 导入后自动修剪首尾空白并去除 UTF-8 BOM 头（部分编辑器会在文件开头写入 ﻿）。
     * 与 updateTask 一致：只允许 DRAFT 任务导入材料；导入内容变化后任务保持在 DRAFT。
     * <p>
     * 为什么用文件导入而非文本框粘贴：B 站创作者的实际素材通常以文件形式存在
     * （SRT 字幕、Markdown 文稿），文件导入减少手动复制粘贴的格式丢失风险。
     *
     * @param taskId       任务ID
     * @param materialType 材料类型（TITLE_DRAFT / DESCRIPTION_DRAFT / MANUSCRIPT / SUBTITLE）
     * @param file         上传的文件
     * @return 更新后的完整任务响应
     */
    @Transactional
    public CreatorTaskResponse importMaterial(String taskId, String materialType, MultipartFile file) {
        String safeTaskId = normalizeTaskId(taskId);
        CreatorTaskRecord taskRecord = getTaskRecord(safeTaskId);
        ensureTaskMaterialsEditable(taskRecord);
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

    /**
     * 将已有成片的草稿任务直接切换到成片试映路径。
     * <p>
     * 这里只保存用户主动跳过前期策划的事实，不生成虚假的发布方案或制作蓝图。
     */
    @Transactional
    public CreatorTaskResponse skipToPreflight(String taskId) {
        String safeTaskId = normalizeTaskId(taskId);
        CreatorTaskRecord taskRecord = getTaskRecord(safeTaskId);
        if (taskRecord.isPlanningSkipped()) {
            return getTask(safeTaskId);
        }
        if (!CreatorTaskStatus.DRAFT.name().equals(taskRecord.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有尚未确认发布方案的草稿任务可以直接进入成片试映");
        }
        if (creatorTaskMapper.markPlanningSkipped(safeTaskId) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务状态已经变化，请刷新后重试");
        }
        return getTask(safeTaskId);
    }

    /**
     * 删除创作任务（逻辑删除：标记为 ARCHIVED，同时物理删除关联材料）。
     * <p>
     * 为什么用逻辑删除 + 物理删材料：任务本身保留为 ARCHIVED（方便后续恢复或排障追溯），
     * 但材料内容可能很大（文稿/字幕文件），物理删除以释放存储空间。
     * 任务删除后仍可通过 admin 接口查询归档任务，材料不可恢复。
     *
     * @param taskId 任务ID
     */
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

    /**
     * 查询任务列表（摘要视图），按更新时间倒序。
     * <p>
     * userId 参数作为未来多人隔离的兼容能力预留；当前单人工作台模式尽量不因
     * userId 过滤导致查询结果为空。userId 为空或未提供时使用全局最近任务列表。
     *
     * @param userId 用户标识（可选，为多人支持预留）
     * @param limit  返回条数上限，最大 {@link #MAX_LIMIT} 条
     * @return 任务摘要列表（不含材料详情）
     */
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

    /**
     * 查询单个任务的完整详情（含全部材料内容）。
     *
     * @param taskId 任务ID
     * @return 完整的任务响应，含基本信息 + 四类材料
     */
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

    /**
     * 刷新单类材料：内容为空时软删除（避免旧内容被 Agent 误读），
     * 有内容时 upsert（同类型只保留最新一条，因为材料是任务维度的单值属性而非历史集合）。
     */
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
                normalizeVideoType(record.getVideoType()),
                record.getStatus(),
                record.isPlanningSkipped(),
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
                normalizeVideoType(record.getVideoType()),
                record.getStatus(),
                record.getMaterialCount(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }

    private String normalizeUserId(String userId) {
        return TextUtil.trimToDefault(userId, DEFAULT_USER_ID);
    }

    /**
     * 规范化任务名称：优先使用显式 taskName，其次从标题草稿截取前 40 字，
     * 都为空时回退 "未命名创作任务"。
     */
    private String normalizeTaskName(String taskName, String titleDraft) {
        if (TextUtil.hasText(taskName)) {
            return taskName.trim();
        }
        if (TextUtil.hasText(titleDraft)) {
            return TextUtil.abbreviate(titleDraft.trim(), 40);
        }
        return "未命名创作任务";
    }

    private String normalizeVideoType(String videoType) {
        return TextUtil.trimToDefault(videoType, DEFAULT_VIDEO_TYPE);
    }

    private CreatorTaskRecord getTaskRecord(String taskId) {
        return creatorTaskMapper.findTaskByTaskId(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在"));
    }

    /**
     * 校验任务材料是否仍允许修改。
     *
     * 发布方案确认后，任务状态会离开 DRAFT，标题、视频类型和文稿都已经参与过建议生成，
     * 并会继续作为反馈分析和复盘报告的对照基线。这里在服务层统一拦截表单保存和文件导入，
     * 避免只依赖前端只读状态而被直接调用接口绕过。
     *
     * @param taskRecord 当前任务记录
     */
    private void ensureTaskMaterialsEditable(CreatorTaskRecord taskRecord) {
        if (!CreatorTaskStatus.DRAFT.name().equals(taskRecord.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "发布方案已确认，任务资料已锁定；如需调整请新建修订任务"
            );
        }
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

    /**
     * 逐类对比当前材料与请求材料，只要任一类内容发生变化即返回 true。
     * <p>
     * 使用 {@link EnumMap} 做当前材料的索引，将 O(N*M) 的嵌套循环降为 O(N+M)。
     * normalizeMaterialContent 保证对比时忽略前后空白差异（trim 后对比）。
     */
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
