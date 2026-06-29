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
            // 已有绑定：更新 UID 和状态，重置同步信息（因为 UID 变了，旧缓存不再有效）
            BilibiliAccountRecord record = existing.get();
            bilibiliMapper.updateAccountSyncResult(
                    record.accountId(),
                    null, // 昵称等下次同步时再更新
                    "ACTIVE",
                    null, // 清除旧同步时间
                    null  // 清除旧错误
            );
            // 重新查询返回最新状态
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

        // 检查 BV 是否已被其他任务绑定——记录警告但不阻止
        var conflictingBindings = bilibiliMapper.findBindingsByBvid(request.bvid());
        String verifyMessage = null;
        if (!conflictingBindings.isEmpty()) {
            verifyMessage = String.format("该BV号已被%d个其他任务绑定，请确认是否正确",
                    conflictingBindings.size());
            log.warn("BV冲突：bvid={}, 已有绑定数={}", request.bvid(), conflictingBindings.size());
        }

        // 创建绑定
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
        bilibiliMapper.insertBinding(record);

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
     */
    public List<BilibiliVideoResponse> getLinkedVideos(String bilibiliUid) {
        var bindings = bilibiliMapper.listBindingsByUid(bilibiliUid);
        List<BilibiliVideoResponse> result = new ArrayList<>();

        for (var binding : bindings) {
            // 跳过异常状态绑定（UID 不匹配等），不展示在正常列表里
            if ("UID_MISMATCH".equals(binding.bindingStatus())) {
                continue;
            }

            String taskName = null;
            var task = taskMapper.findTaskByTaskId(binding.taskId());
            if (task.isPresent()) {
                taskName = task.get().getTaskName();
            }

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
