package com.link.linkagent.creator.bilibili.service;

import com.link.linkagent.creator.bilibili.mapper.CreatorBilibiliMapper;
import com.link.linkagent.creator.bilibili.model.BilibiliAccountRecord;
import com.link.linkagent.creator.bilibili.model.BilibiliAccountResponse;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoResponse;
import com.link.linkagent.creator.bilibili.model.BindAccountRequest;
import com.link.linkagent.creator.bilibili.model.BindBvRequest;
import com.link.linkagent.creator.bilibili.model.TaskVideoBindingRecord;
import com.link.linkagent.creator.bilibili.model.TaskVideoBindingResponse;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * B站账号绑定与任务视频绑定服务（P0-3）。
 * 独立于已有的 CreatorInteractiveService 和 CreatorTaskService，
 * 避免把账号管理和任务管理耦合在一起。
 * <p>
 * P0-3 的 syncVideos 是第一版占位实现——B站公开API同步能力在后续迭代补齐。
 * 当前只提供账号绑定、BV绑定和已绑定视频查询能力。
 */
@Service
public class CreatorBilibiliService {

    private static final Logger log = LoggerFactory.getLogger(CreatorBilibiliService.class);

    private final CreatorBilibiliMapper bilibiliMapper;
    private final CreatorTaskMapper taskMapper;

    public CreatorBilibiliService(CreatorBilibiliMapper bilibiliMapper,
                                  CreatorTaskMapper taskMapper) {
        this.bilibiliMapper = bilibiliMapper;
        this.taskMapper = taskMapper;
    }

    /**
     * 绑定或更新 B 站账号。
     * 如果用户已有绑定记录则更新 UID（用户可能换号或填错），
     * 没有则创建新记录。这样保证每个平台用户只有一条绑定。
     */
    @Transactional
    public BilibiliAccountResponse bindAccount(BindAccountRequest request) {
        var existing = bilibiliMapper.findAccountByUserId(request.userId());
        if (existing.isPresent()) {
            BilibiliAccountRecord record = existing.get();
            // 已有绑定：更新 UID，同时重置昵称和同步信息。
            // 因为 UID 变了意味着旧缓存和昵称不再有效，等下次同步时重新拉取。
            // 使用专用的 updateAccountUid 而非 updateAccountSyncResult——后者没有权限改动 UID。
            bilibiliMapper.updateAccountUid(record.accountId(), request.bilibiliUid());
            var updated = bilibiliMapper.findAccountByUserId(request.userId()).orElseThrow();
            return toAccountResponse(updated);
        }

        // 首次绑定：创建新记录
        var record = new BilibiliAccountRecord(
                null,
                UUID.randomUUID().toString(),
                request.userId(),
                request.bilibiliUid(),
                null, // 昵称首次为空，等同步后填充
                "ACTIVE",
                null,
                null,
                null,
                null
        );
        bilibiliMapper.insertAccount(record);
        var created = bilibiliMapper.findAccountByUserId(request.userId()).orElseThrow();
        return toAccountResponse(created);
    }

    /**
     * 查询 B 站账号绑定状态。
     * 不存在时返回 null，由 Controller 层处理 404。
     */
    public BilibiliAccountResponse getAccount(String userId) {
        return bilibiliMapper.findAccountByUserId(userId)
                .map(this::toAccountResponse)
                .orElse(null);
    }

    /**
     * 同步 B 站视频列表（P0-3 占位实现）。
     * 第一版不实际调用 B 站 API，只更新同步时间并返回提示信息。
     * 后续迭代会接入 B 站公开视频接口，自动拉取创作者视频列表。
     */
    @Transactional
    public Map<String, Object> syncVideos(String userId) {
        var account = bilibiliMapper.findAccountByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "未找到B站账号绑定，请先绑定UID"));

        LocalDateTime now = LocalDateTime.now();
        bilibiliMapper.updateAccountSyncResult(
                account.accountId(),
                account.nickname(),
                "ACTIVE",
                now,
                null
        );

        log.info("B站视频同步（占位）：userId={}, bilibiliUid={}", userId, account.bilibiliUid());
        return Map.of(
                "bilibiliUid", account.bilibiliUid(),
                "syncedCount", 0,
                "linkedCount", 0,
                "anomalyCount", 0,
                "lastError", (Object) null,
                "message", "B站视频同步功能开发中。请先在创作任务中手动绑定已发布视频的BV号。"
        );
    }

    /**
     * 将 BV 号绑定到创作任务。
     * 校验链：任务存在 → 已有绑定检查 → BV 冲突检测（警告但不阻止）。
     * 绑定后默认状态为 WAITING_VERIFY，等待后续 UID 同步校验。
     */
    @Transactional
    public TaskVideoBindingResponse bindBvToTask(String taskId, BindBvRequest request) {
        // 校验任务存在
        var task = taskMapper.findTaskByTaskId(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "创作任务不存在：" + taskId));

        // 检查是否已有绑定——已有则直接返回，不允许覆盖
        var existingBinding = bilibiliMapper.findBindingByTaskId(taskId);
        if (existingBinding.isPresent()) {
            log.info("任务已有BV绑定，直接返回：taskId={}, bvid={}", taskId, existingBinding.get().bvid());
            return toBindingResponse(existingBinding.get());
        }

        // 检查 BV 是否已被其他任务绑定——区分同用户和跨用户冲突
        var conflictingBindings = bilibiliMapper.findBindingsByBvid(request.bvid());
        String verifyMessage = null;
        if (!conflictingBindings.isEmpty()) {
            // 区分同用户和跨用户冲突：跨用户冲突应阻止而非仅警告
            boolean hasCrossUserConflict = conflictingBindings.stream()
                    .anyMatch(b -> !request.userId().equals(b.userId()));
            if (hasCrossUserConflict) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "该BV号已被其他用户绑定，请确认BV号是否正确");
            }
            // 同用户冲突：警告但允许，用户可能想对同一视频做多次分析
            verifyMessage = String.format("该BV号已被你的%d个其他任务绑定，请确认是否需要重复分析",
                    conflictingBindings.size());
            log.warn("BV同用户冲突：bvid={}, userId={}, 已有绑定数={}",
                    request.bvid(), request.userId(), conflictingBindings.size());
        }

        // 创建绑定（并发安全：捕获 DuplicateKeyException 后返回已有记录）
        var record = new TaskVideoBindingRecord(
                null,
                UUID.randomUUID().toString(),
                taskId,
                request.userId(),
                request.bilibiliUid(),
                request.bvid(),
                "WAITING_VERIFY",
                verifyMessage,
                null,
                null
        );
        try {
            bilibiliMapper.insertBinding(record);
        } catch (DuplicateKeyException e) {
            // 并发场景下另一个请求已经创建了绑定，重新查询并返回已有记录
            log.info("并发创建绑定冲突，返回已有记录：taskId={}", taskId);
            var existing = bilibiliMapper.findBindingByTaskId(taskId).orElseThrow();
            return toBindingResponse(existing);
        }

        var created = bilibiliMapper.findBindingByTaskId(taskId).orElseThrow();
        return toBindingResponse(created);
    }

    /**
     * 查询任务视频绑定。
     * 不存在时返回 null，由 Controller 层处理 404。
     */
    public TaskVideoBindingResponse getTaskBinding(String taskId) {
        return bilibiliMapper.findBindingByTaskId(taskId)
                .map(this::toBindingResponse)
                .orElse(null);
    }

    /**
     * 获取某 B 站 UID 下已绑定任务的视频列表。
     * 这是视频分析页的核心查询：只展示和平台任务关联的视频，不展示账号下全部视频。
     * <p>
     * 关联逻辑：binding (按 UID 过滤) → task (获取 taskName) → video (获取封面/指标)。
     * 如果视频缓存表中还没有对应记录（用户绑了 BV 但还没同步），
     * 也返回一条只含 bvid + taskId + taskName 的基础响应，保证卡片列表不丢数据。
     * <p>
     * userId 参数用于数据隔离：只返回当前用户的绑定，防止跨用户数据泄露。
     * bindingStatus 只保留 "BOUND"，null 状态（未校验）视为不展示。
     * 批量查询 taskName 替代原来的逐条 N+1 查询。
     */
    @Transactional(readOnly = true)
    public List<BilibiliVideoResponse> getLinkedVideos(String bilibiliUid, String userId) {
        var bindings = bilibiliMapper.listBindingsByUid(bilibiliUid);
        if (bindings.isEmpty()) {
            return List.of();
        }

        // 收集所有关联的 taskId，批量查询任务名称，避免 N+1
        List<String> taskIds = bindings.stream()
                .map(TaskVideoBindingRecord::taskId)
                .distinct()
                .toList();
        var tasks = bilibiliMapper.findTasksByTaskIds(taskIds);
        // 转为 Map 以便 O(1) 查找
        var taskMap = tasks.stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.link.linkagent.creator.task.model.CreatorTaskRecord::getTaskId,
                        com.link.linkagent.creator.task.model.CreatorTaskRecord::getTaskName,
                        (a, b) -> a
                ));

        List<BilibiliVideoResponse> result = new ArrayList<>();
        for (var binding : bindings) {
            // 只展示已校验通过的绑定；null 状态视为未校验，不展示
            if (!"BOUND".equals(binding.bindingStatus())) {
                continue;
            }
            // userId 隔离：只返回当前用户的绑定，防止跨用户数据泄露
            if (!userId.equals(binding.userId())) {
                continue;
            }

            String taskName = taskMap.getOrDefault(binding.taskId(), null);

            // 查找视频缓存
            var video = bilibiliMapper.findVideoByBvidAndUid(binding.bvid(), bilibiliUid);
            if (video.isPresent()) {
                var v = video.get();
                result.add(new BilibiliVideoResponse(
                        v.videoId(), v.bilibiliUid(), v.bvid(), v.aid(),
                        v.title(), v.coverUrl(), v.publishTime(),
                        v.viewCount(), v.likeCount(), v.coinCount(),
                        v.favoriteCount(), v.shareCount(),
                        v.syncStatus(), v.lastSyncTime(),
                        true, binding.taskId(), taskName
                ));
            } else {
                // 缓存中没有视频信息时返回基础响应
                result.add(new BilibiliVideoResponse(
                        null, bilibiliUid, binding.bvid(), null,
                        null, null, null,
                        null, null, null, null, null,
                        "STALE", null,
                        true, binding.taskId(), taskName
                ));
            }
        }

        return result;
    }

    // ── 内部转换方法 ──

    private BilibiliAccountResponse toAccountResponse(BilibiliAccountRecord record) {
        return new BilibiliAccountResponse(
                record.accountId(),
                record.userId(),
                record.bilibiliUid(),
                record.nickname(),
                record.bindStatus(),
                record.lastSyncTime(),
                record.lastSyncError(),
                record.createTime(),
                record.updateTime()
        );
    }

    private TaskVideoBindingResponse toBindingResponse(TaskVideoBindingRecord record) {
        return new TaskVideoBindingResponse(
                record.bindingId(),
                record.taskId(),
                record.userId(),
                record.bilibiliUid(),
                record.bvid(),
                record.bindingStatus(),
                record.verifyMessage(),
                record.createTime(),
                record.updateTime()
        );
    }
}
