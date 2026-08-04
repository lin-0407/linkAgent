package com.link.linkagent.creator.bilibili.service;

import com.link.linkagent.creator.bilibili.mapper.CreatorBilibiliMapper;
import com.link.linkagent.creator.bilibili.model.BilibiliAccountRecord;
import com.link.linkagent.creator.bilibili.model.BilibiliAccountResponse;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoRecord;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoResponse;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoSyncResponse;
import com.link.linkagent.creator.bilibili.model.BindAccountRequest;
import com.link.linkagent.creator.bilibili.model.BindBvRequest;
import com.link.linkagent.creator.bilibili.model.PostPublishReadinessResponse;
import com.link.linkagent.creator.bilibili.model.TaskVideoBindingRecord;
import com.link.linkagent.creator.bilibili.model.TaskVideoBindingResponse;
import com.link.linkagent.creator.media.workflow.CreatorMediaWorkflowGateService;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * B站账号绑定与任务视频绑定服务（P0-3）。
 * <p>
 * 独立于已有的 CreatorInteractiveService 和 CreatorTaskService，
 * 避免把账号管理和任务管理耦合在一起——账号绑定是一次性操作（绑定/解绑/查询），
 * 任务视频绑定是任务生命周期中的关键步骤（BV 号关联分析），两者在业务语义上正交，
 * 拆分可让各自独立演进，不互相拖累。
 * <p>
 * B站公开视频同步由 {@link BilibiliVideoSyncProvider} 负责外部脚本调用，
 * 再由 {@link BilibiliVideoSyncPersistenceService} 在独立事务中保存结果。
 * <p>
 * 架构位置：本服务位于 creator.bilibili 模块的 service 层，向下依赖 Mapper、本模块同步服务
 * 和阶段 7 的发布后流程门禁；门禁只读取成片状态，不反向依赖 B站模块，避免循环依赖。
 */
@Service
public class CreatorBilibiliService {

    private static final Logger log = LoggerFactory.getLogger(CreatorBilibiliService.class);

    /** B站账号与任务视频绑定的数据访问层，负责 MySQL 的 CRUD */
    private final CreatorBilibiliMapper bilibiliMapper;
    /** 创作任务数据访问层，仅用于校验任务是否存在（不侵入任务管理逻辑） */
    private final CreatorTaskMapper taskMapper;
    /** B站公开视频采集 Provider，只负责外部脚本调用和 JSON 解析 */
    private final BilibiliVideoSyncProvider syncProvider;
    /** 同步结果持久化服务，确保外部调用完成后才开启数据库事务 */
    private final BilibiliVideoSyncPersistenceService syncPersistenceService;
    /** 发布后流程门禁，防止新 BV 绑定绕过已完成的成片试映 */
    private final CreatorMediaWorkflowGateService mediaWorkflowGateService;

    public CreatorBilibiliService(CreatorBilibiliMapper bilibiliMapper,
                                   CreatorTaskMapper taskMapper,
                                   BilibiliVideoSyncProvider syncProvider,
                                   BilibiliVideoSyncPersistenceService syncPersistenceService,
                                   CreatorMediaWorkflowGateService mediaWorkflowGateService) {
        this.bilibiliMapper = bilibiliMapper;
        this.taskMapper = taskMapper;
        this.syncProvider = syncProvider;
        this.syncPersistenceService = syncPersistenceService;
        this.mediaWorkflowGateService = mediaWorkflowGateService;
    }

    /**
     * 绑定或更新 B 站账号。
     * <p>
     * 设计决策：每个平台用户只维护一条绑定记录，不往历史表里堆叠。
     * 如果用户已有绑定记录则更新 UID（用户可能换号或填错），没有则创建新记录。
     * 这样保证每个平台用户只有一条绑定，查询和同步时不会出现"到底用哪个 UID"的歧义。
     * <p>
     * UID 变更时同步重置昵称和头像：因为 UID 变了意味着旧账号公开资料不再有效，
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
            // 已有绑定：更新 UID，同时重置昵称、头像和同步信息。
            // 因为 UID 变了意味着旧账号公开资料不再有效，等下次同步时重新拉取。
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
                null, // 头像同样来自公开账号同步，不能沿用旧 UID 的图片。
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
     * 同步 B 站视频列表并校验任务 BV 归属。
     * <p>
     * 先执行外部脚本，再把完整结果交给独立事务持久化；这样 B站接口变慢时不会长时间持有数据库连接。
     * 同步失败时保留上一次成功缓存，只记录本次错误，避免一次临时网络故障把页面变成空状态。
     *
     * @param userId 平台用户 ID，需要已绑定 B 站账号
     * @return 同步结果，含视频数量、绑定数量和异常说明
     * @throws ResponseStatusException 404 如果用户未绑定 B 站账号
     */
    public BilibiliVideoSyncResponse syncVideos(String userId) {
        var account = bilibiliMapper.findAccountByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "未找到B站账号绑定，请先绑定UID"));

        var bindings = bilibiliMapper.listBindingsByUserId(userId);
        List<String> targetBvids = bindings == null
                ? List.of()
                : bindings.stream()
                        .map(TaskVideoBindingRecord::getBvid)
                        .filter(value -> value != null)
                        .distinct()
                        .toList();

        try {
            var payload = syncProvider.fetch(account.getBilibiliUid(), targetBvids);
            BilibiliVideoSyncResponse result = syncPersistenceService.persist(account, payload, bindings);
            log.info("B站公开视频同步完成：userId={}, bilibiliUid={}, syncedCount={}, linkedCount={}, anomalyCount={}",
                    userId, account.getBilibiliUid(), result.syncedCount(), result.linkedCount(), result.anomalyCount());
            return result;
        } catch (ResponseStatusException exception) {
            recordSyncFailure(account, exception.getReason());
            throw exception;
        } catch (RuntimeException exception) {
            recordSyncFailure(account, "同步结果保存失败，请稍后重试");
            log.error("B站公开视频同步结果处理失败：userId={}, bilibiliUid={}",
                    userId, account.getBilibiliUid(), exception);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "B站公开视频同步失败，请稍后重试");
        }
    }

    /** 同步失败只更新一条账号记录，不触碰上一次成功的同步时间和视频缓存。 */
    private void recordSyncFailure(BilibiliAccountRecord account, String reason) {
        String normalizedReason = reason == null || reason.isBlank()
                ? "B站公开视频同步失败"
                : reason.trim();
        if (normalizedReason.length() > 500) {
            normalizedReason = normalizedReason.substring(0, 500);
        }
        try {
            bilibiliMapper.updateAccountSyncResult(
                    account.getAccountId(),
                    account.getNickname(),
                    account.getAvatarUrl(),
                    "SYNC_FAILED",
                    account.getLastSyncTime(),
                    normalizedReason
            );
        } catch (RuntimeException exception) {
            // 失败状态记录是补偿动作，不能覆盖真正的同步异常，否则调用方会拿到错误的根因。
            log.error("记录B站同步失败状态时数据库写入失败：accountId={}", account.getAccountId(), exception);
        }
        log.warn("B站公开视频同步失败：userId={}, bilibiliUid={}, reason={}",
                account.getUserId(), account.getBilibiliUid(), normalizedReason);
    }

    /**
     * 将 BV 号绑定到创作任务。
     * <p>
     * 校验链：任务存在 → 已有绑定检查 → BV 冲突检测（同用户警告、跨用户阻止）。
     * 已有可信视频缓存时直接进入 BOUND，否则进入 WAITING_VERIFY，等待后续 UID 同步校验。
     * <p>
     * 设计决策：
     * <ul>
     *   <li>BOUND 绑定不可覆盖——防止用户误操作清掉完成校验的状态；异常或待校验绑定允许修正。</li>
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

        if (!request.userId().equals(task.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作其他用户的创作任务");
        }

        // 已确认的绑定不能覆盖，避免用户误操作清掉已经验证通过的任务；异常或待校验状态允许修正。
        var existingBinding = bilibiliMapper.findBindingByTaskId(taskId);
        if (existingBinding.isPresent()
                && "BOUND".equals(existingBinding.get().getBindingStatus())) {
            log.info("任务已有BV绑定，直接返回：taskId={}, bvid={}", taskId, existingBinding.get().getBvid());
            return toBindingResponse(existingBinding.get());
        }

        mediaWorkflowGateService.ensureReadyForPostPublish(
                task.getTaskId(),
                task.getUserId(),
                "BV绑定"
        );

        var account = bilibiliMapper.findAccountByUserId(request.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "请先绑定B站UID，再绑定任务BV"));
        if (!request.bilibiliUid().equals(account.getBilibiliUid())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "任务中的B站UID必须与当前账号绑定的UID一致");
        }

        // 检查 BV 是否已被其他任务绑定——区分同用户和跨用户冲突
        String existingBindingId = existingBinding.map(TaskVideoBindingRecord::getBindingId).orElse(null);
        var conflictingBindings = bilibiliMapper.findBindingsByBvid(request.bvid()).stream()
                // 修正已有绑定时排除它自己，否则同一个任务会被误报为“重复绑定”。
                .filter(binding -> binding != null && !Objects.equals(existingBindingId, binding.getBindingId()))
                .toList();
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

        // 已经同步过的视频可以直接完成校验，避免用户在“先同步、后绑定”时还要重复点击同步。
        String bindingStatus = bilibiliMapper.findVideoByBvidAndUid(request.bvid(), request.bilibiliUid())
                .filter(video -> "SYNCED".equals(video.getSyncStatus()))
                .isPresent()
                ? "BOUND"
                : "WAITING_VERIFY";
        if ("BOUND".equals(bindingStatus)) {
            String cachedMessage = "视频已在当前UID的公开视频缓存中，绑定校验通过";
            verifyMessage = verifyMessage == null ? cachedMessage : verifyMessage + "；" + cachedMessage;
        }

        // 已有异常绑定直接更新，避免用户必须删除任务才能修正 BV；新绑定仍走插入和并发幂等兜底。
        if (existingBinding.isPresent()) {
            TaskVideoBindingRecord record = existingBinding.get();
            int updatedRows = bilibiliMapper.updateBindingDetails(
                    record.getBindingId(),
                    request.bilibiliUid(),
                    request.bvid(),
                    bindingStatus,
                    verifyMessage
            );
            // 同步线程可能在本次请求期间先把状态推进为 BOUND，此时保留数据库中的已确认结果。
            if (updatedRows == 0) {
                return bilibiliMapper.findBindingByTaskId(taskId)
                        .map(this::toBindingResponse)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR, "更新任务视频绑定时记录已不存在"));
            }
            return bilibiliMapper.findBindingByTaskId(taskId)
                    .map(this::toBindingResponse)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.INTERNAL_SERVER_ERROR, "更新任务视频绑定后无法读取结果"));
        }

        // 创建绑定（并发安全：捕获 DuplicateKeyException 后返回已有记录）
        var record = new TaskVideoBindingRecord(
                null,
                UUID.randomUUID().toString(),
                taskId,
                request.userId(),
                request.bilibiliUid(),
                request.bvid(),
                bindingStatus,
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
     * 查询任务进入 BV 绑定前的发布后就绪状态。
     * <p>
     * 归属从任务记录读取，避免前端传入 userId 后查询到其他归属下的媒体事实；门禁查询本身不恢复
     * 陈旧探测任务，也不会替代绑定写接口上的强制校验。
     */
    public PostPublishReadinessResponse getPostPublishReadiness(String taskId) {
        var task = taskMapper.findTaskByTaskId(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "创作任务不存在：" + taskId
                ));
        var readiness = mediaWorkflowGateService.inspectPostPublishReadiness(
                taskId,
                task.getUserId(),
                "BV绑定"
        );
        return new PostPublishReadinessResponse(
                taskId,
                readiness.ready(),
                readiness.blockingReason()
        );
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
                .collect(Collectors.toMap(
                        com.link.linkagent.creator.task.model.CreatorTaskRecord::getTaskId,
                        com.link.linkagent.creator.task.model.CreatorTaskRecord::getTaskName,
                        (a, b) -> a
                ));
        List<String> bvids = bindings.stream()
                .filter(binding -> "BOUND".equals(binding.getBindingStatus()))
                .filter(binding -> userId.equals(binding.getUserId()))
                .map(TaskVideoBindingRecord::getBvid)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, BilibiliVideoRecord> videoMap = bvids.isEmpty()
                ? Map.of()
                : bilibiliMapper.listVideosByBvidsAndUid(bvids, bilibiliUid)
                .stream()
                .collect(Collectors.toMap(
                        BilibiliVideoRecord::getBvid,
                        Function.identity(),
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

            // 视频缓存已批量查好，循环内只做内存映射，避免绑定越多 SQL 越多。
            var v = videoMap.get(binding.getBvid());
            if (v != null) {
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
                record.getAvatarUrl(),
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
