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
 * <p>
 * 独立于已有的 CreatorInteractiveService 和 CreatorTaskService，
 * 避免把账号管理和任务管理耦合在一起——账号绑定是一次性操作（绑定/解绑/查询），
 * 任务视频绑定是任务生命周期中的关键步骤（BV 号关联分析），两者在业务语义上正交，
 * 拆分可让各自独立演进，不互相拖累。
 * <p>
 * P0-3 的 syncVideos 是第一版占位实现——B站公开API同步能力在后续迭代补齐。
 * 当前只提供账号绑定、BV绑定和已绑定视频查询能力。
 * <p>
 * 架构位置：本服务位于 creator.bilibili 模块的 service 层，向下依赖 Mapper 层做数据读写，
 * 向上被 Controller 层直接调用。不依赖任何其他业务 Service，避免循环依赖。
 */
@Service
public class CreatorBilibiliService {

    private static final Logger log = LoggerFactory.getLogger(CreatorBilibiliService.class);

    /** B站账号与任务视频绑定的数据访问层，负责 MySQL 的 CRUD */
    private final CreatorBilibiliMapper bilibiliMapper;
    /** 创作任务数据访问层，仅用于校验任务是否存在（不侵入任务管理逻辑） */
    private final CreatorTaskMapper taskMapper;

    public CreatorBilibiliService(CreatorBilibiliMapper bilibiliMapper,
                                  CreatorTaskMapper taskMapper) {
        this.bilibiliMapper = bilibiliMapper;
        this.taskMapper = taskMapper;
    }

    /**
     * 绑定或更新 B 站账号。
     * <p>
     * 设计决策：每个平台用户只维护一条绑定记录，不往历史表里堆叠。
     * 如果用户已有绑定记录则更新 UID（用户可能换号或填错），没有则创建新记录。
     * 这样保证每个平台用户只有一条绑定，查询和同步时不会出现"到底用哪个 UID"的歧义。
     * <p>
     * UID 变更时同步重置昵称：因为 UID 变了意味着旧缓存和昵称不再有效，
     * 等下次同步时重新拉取。使用专用的 updateAccountUid 而非 updateAccountSyncResult——
     * 后者没有权限改动 UID，强制走正确的方法防止误操作。
     *
     * @param request 绑定请求，含 userId 和 bilibiliUid
     * @return 绑定后的账号响应（含新/更新后的完整记录）
     */
    @Transactional
    public BilibiliAccountResponse bindAccount(BindAccountRequest request) {
        var existing = bilibiliMapper.findAccountByUserId(request.userId());
        if (existing.isPresent()) {
            BilibiliAccountRecord record = existing.get();
            // 已有绑定：更新 UID，同时重置昵称和同步信息。
            // 因为 UID 变了意味着旧缓存和昵称不再有效，等下次同步时重新拉取。
            // 使用专用的 updateAccountUid 而非 updateAccountSyncResult——后者没有权限改动 UID。
            bilibiliMapper.updateAccountUid(record.getAccountId(), request.bilibiliUid());
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
     * <p>
     * 不存在时返回 null，由 Controller 层处理 404。
     * 返回 null 而非抛异常的设计考量：Controller 层可能需要区分"未绑定"和"查询失败"两种情况，
     * null 表示正常的未绑定状态，异常表示系统错误。
     *
     * @param userId 平台用户 ID
     * @return 账号绑定响应；不存在时返回 null
     */
    public BilibiliAccountResponse getAccount(String userId) {
        return bilibiliMapper.findAccountByUserId(userId)
                .map(this::toAccountResponse)
                .orElse(null);
    }

    /**
     * 同步 B 站视频列表（P0-3 占位实现）。
     * <p>
     * 第一版不实际调用 B 站 API，只更新同步时间并返回提示信息。
     * 后续迭代会接入 B 站公开视频接口，自动拉取创作者视频列表。
     * 为什么现在就要做占位：提前把接口契约和调用方（前端）对齐，后续接入真实 API 时
     * 只需改本方法内部逻辑，前端和 Controller 层的调用关系无需变动。
     *
     * @param userId 平台用户 ID，需要已绑定 B 站 UID
     * @return 同步结果，含占位提示信息
     * @throws ResponseStatusException 404 如果用户未绑定 B 站账号
     */
    @Transactional
    public Map<String, Object> syncVideos(String userId) {
        var account = bilibiliMapper.findAccountByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "未找到B站账号绑定，请先绑定UID"));

        LocalDateTime now = LocalDateTime.now();
        bilibiliMapper.updateAccountSyncResult(
                account.getAccountId(),
                account.getNickname(),
                "ACTIVE",
                now,
                null
        );

        log.info("B站视频同步（占位）：userId={}, bilibiliUid={}", userId, account.getBilibiliUid());
        return Map.of(
                "bilibiliUid", account.getBilibiliUid(),
                "syncedCount", 0,
                "linkedCount", 0,
                "anomalyCount", 0,
                "lastError", (Object) null,
                "message", "B站视频同步功能开发中。请先在创作任务中手动绑定已发布视频的BV号。"
        );
    }

    /**
     * 将 BV 号绑定到创作任务。
     * <p>
     * 校验链：任务存在 → 已有绑定检查 → BV 冲突检测（同用户警告、跨用户阻止）。
     * 绑定后默认状态为 WAITING_VERIFY，等待后续 UID 同步校验。
     * <p>
     * 设计决策：
     * <ul>
     *   <li>已有绑定不可覆盖——防止用户误操作清掉完成校验的 BOUND 状态。</li>
     *   <li>同用户 BV 冲突只警告不阻止——用户可能想对同一视频做多次分析（不同版本/不同角度）。</li>
     *   <li>跨用户 BV 冲突直接拒绝——同一 BV 属于两个不同创作者在业务上不合理。</li>
     *   <li>DuplicateKeyException 并发兜底——高并发下 insert 可能触发唯一索引冲突，
     *       捕获后重新查询已有记录返回，保证幂等性。</li>
     * </ul>
     *
     * @param taskId 创作任务 ID
     * @param request 绑定请求，含 BV 号和用户信息
     * @return 绑定响应
     * @throws ResponseStatusException 404 任务不存在，409 跨用户 BV 冲突
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
            log.info("任务已有BV绑定，直接返回：taskId={}, bvid={}", taskId, existingBinding.get().getBvid());
            return toBindingResponse(existingBinding.get());
        }

        // 检查 BV 是否已被其他任务绑定——区分同用户和跨用户冲突
        var conflictingBindings = bilibiliMapper.findBindingsByBvid(request.bvid());
        String verifyMessage = null;
        if (!conflictingBindings.isEmpty()) {
            // 区分同用户和跨用户冲突：跨用户冲突应阻止而非仅警告
            boolean hasCrossUserConflict = conflictingBindings.stream()
                    .anyMatch(b -> !request.userId().equals(b.getUserId()));
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
     * <p>
     * 不存在时返回 null，由 Controller 层处理 404。
     * 与 getAccount 一致：返回 null 表示正常的未绑定状态，异常表示系统错误。
     *
     * @param taskId 创作任务 ID
     * @return 绑定响应；不存在时返回 null
     */
    public TaskVideoBindingResponse getTaskBinding(String taskId) {
        return bilibiliMapper.findBindingByTaskId(taskId)
                .map(this::toBindingResponse)
                .orElse(null);
    }

    /**
     * 获取某 B 站 UID 下已绑定任务的视频列表（视频分析页核心查询）。
     * <p>
     * 只展示和平台任务关联的视频，不展示账号下全部视频——因为创作复盘关注的是"某任务对应的具体视频"，
     * 而不是该创作者的全部作品列表。这是视频分析页的核心查询，直接驱动前端卡片列表渲染。
     * <p>
     * 关联链路：binding (按 UID 过滤) → task (获取 taskName) → video (获取封面/指标)。
     * 如果视频缓存表中还没有对应记录（用户绑了 BV 但还没同步），
     * 也返回一条只含 bvid + taskId + taskName 的基础响应，保证卡片列表不丢数据。
     * <p>
     * 安全性设计：
     * <ul>
     *   <li>userId 参数用于数据隔离——只返回当前用户的绑定，防止跨用户数据泄露。</li>
     *   <li>bindingStatus 只保留 "BOUND"，null 状态（未校验）视为不展示。</li>
     * </ul>
     * <p>
     * 性能优化：批量查询 taskName 替代原来的逐条 N+1 查询。将 taskIds 集合一次性送入
     * Mapper 的 IN 查询，再用 Collectors.toMap 转为 O(1) 查找的 Map。
     *
     * @param bilibiliUid B 站 UID，用于过滤该创作者的所有绑定记录
     * @param userId 平台用户 ID，用于数据隔离
     * @return 已绑定视频列表（按 taskId 维度），空列表表示无绑定数据
     */
    @Transactional(readOnly = true)
    public List<BilibiliVideoResponse> getLinkedVideos(String bilibiliUid, String userId) {
        var bindings = bilibiliMapper.listBindingsByUid(bilibiliUid);
        if (bindings.isEmpty()) {
            return List.of();
        }

        // 收集所有关联的 taskId，批量查询任务名称，避免 N+1
        List<String> taskIds = bindings.stream()
                .map(TaskVideoBindingRecord::getTaskId)
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
            if (!"BOUND".equals(binding.getBindingStatus())) {
                continue;
            }
            // userId 隔离：只返回当前用户的绑定，防止跨用户数据泄露
            if (!userId.equals(binding.getUserId())) {
                continue;
            }

            String taskName = taskMap.getOrDefault(binding.getTaskId(), null);

            // 查找视频缓存
            var video = bilibiliMapper.findVideoByBvidAndUid(binding.getBvid(), bilibiliUid);
            if (video.isPresent()) {
                var v = video.get();
                result.add(new BilibiliVideoResponse(
                        v.getVideoId(), v.getBilibiliUid(), v.getBvid(), v.getAid(),
                        v.getTitle(), v.getCoverUrl(), v.getPublishTime(),
                        v.getViewCount(), v.getLikeCount(), v.getCoinCount(),
                        v.getFavoriteCount(), v.getShareCount(),
                        v.getSyncStatus(), v.getLastSyncTime(),
                        true, binding.getTaskId(), taskName
                ));
            } else {
                // 缓存中没有视频信息时返回基础响应
                result.add(new BilibiliVideoResponse(
                        null, bilibiliUid, binding.getBvid(), null,
                        null, null, null,
                        null, null, null, null, null,
                        "STALE", null,
                        true, binding.getTaskId(), taskName
                ));
            }
        }

        return result;
    }

    // ── 内部转换方法 ──
    // 将数据库 Record 转为前端 Response，实现数据访问层与展示层的解耦。
    // 如果将来响应字段需要脱敏、格式化或合并其他数据源，只需改这里的转换逻辑。

    /**
     * 将账号数据库记录转为前端响应对象。
     * <p>
     * 一对一字段映射；如果将来需要脱敏或追加"绑定天数"等计算字段，可在此扩展。
     *
     * @param record 数据库中的账号绑定记录
     * @return 前端可消费的账号响应
     */
    private BilibiliAccountResponse toAccountResponse(BilibiliAccountRecord record) {
        return new BilibiliAccountResponse(
                record.getAccountId(),
                record.getUserId(),
                record.getBilibiliUid(),
                record.getNickname(),
                record.getBindStatus(),
                record.getLastSyncTime(),
                record.getLastSyncError(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }

    /**
     * 将任务视频绑定记录转为前端响应对象。
     * <p>
     * verifyMessage 可作为前端提示直接在卡片下方展示，帮助用户理解绑定状态的来由。
     *
     * @param record 数据库中的绑定记录
     * @return 前端可消费的绑定响应
     */
    private TaskVideoBindingResponse toBindingResponse(TaskVideoBindingRecord record) {
        return new TaskVideoBindingResponse(
                record.getBindingId(),
                record.getTaskId(),
                record.getUserId(),
                record.getBilibiliUid(),
                record.getBvid(),
                record.getBindingStatus(),
                record.getVerifyMessage(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
