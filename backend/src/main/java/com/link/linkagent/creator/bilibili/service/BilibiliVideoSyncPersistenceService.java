package com.link.linkagent.creator.bilibili.service;

import com.link.linkagent.creator.bilibili.mapper.CreatorBilibiliMapper;
import com.link.linkagent.creator.bilibili.model.BilibiliAccountRecord;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoRecord;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoSyncItem;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoSyncPayload;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoSyncResponse;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoVerificationResult;
import com.link.linkagent.creator.bilibili.model.TaskVideoBindingRecord;
import com.link.linkagent.util.TextUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * B站同步结果持久化服务。
 * <p>
 * 外部脚本执行完成后才会进入本类，因此数据库事务只覆盖缓存写入、绑定状态校验和账号同步结果更新，
 * 避免网络等待期间长期占用 MySQL 连接。同步过程中任何数据库异常都会整体回滚，避免出现半套缓存。
 */
@Service
public class BilibiliVideoSyncPersistenceService {

    private static final Pattern BVID_PATTERN = Pattern.compile("^BV[0-9A-Za-z]{10}$");
    private static final ZoneId BILIBILI_ZONE = ZoneId.of("Asia/Shanghai");

    private final CreatorBilibiliMapper bilibiliMapper;

    public BilibiliVideoSyncPersistenceService(CreatorBilibiliMapper bilibiliMapper) {
        this.bilibiliMapper = bilibiliMapper;
    }

    /**
     * 在一个事务中保存同步结果并推进任务绑定状态。
     *
     * @param account 当前平台用户的 B站账号记录
     * @param payload 脚本返回的同步结果
     * @param bindings 当前平台用户的全部任务视频绑定
     * @return 可直接返回前端的同步结果
     */
    @Transactional
    public BilibiliVideoSyncResponse persist(BilibiliAccountRecord account,
                                             BilibiliVideoSyncPayload payload,
                                             List<TaskVideoBindingRecord> bindings) {
        Set<String> warningSet = new LinkedHashSet<>();
        for (String warning : payload.warnings()) {
            if (TextUtil.hasText(warning)) {
                warningSet.add(warning);
            }
        }
        Map<String, BilibiliVideoSyncItem> validVideos = collectValidVideos(account, payload, warningSet);
        LocalDateTime syncTime = LocalDateTime.now();

        for (BilibiliVideoSyncItem item : validVideos.values()) {
            bilibiliMapper.insertVideo(toVideoRecord(account.getBilibiliUid(), item, syncTime));
        }

        Map<String, BilibiliVideoVerificationResult> verificationMap = new LinkedHashMap<>();
        for (BilibiliVideoVerificationResult verification : payload.verificationResults()) {
            if (verification != null && TextUtil.hasText(verification.bvid())) {
                verificationMap.put(verification.bvid(), verification);
            }
        }

        int linkedCount = 0;
        int anomalyCount = 0;
        if (bindings != null) {
            for (TaskVideoBindingRecord binding : bindings) {
                BindingDecision decision = decideBinding(
                        account,
                        binding,
                        validVideos,
                        verificationMap,
                        warningSet
                );
                if (decision.status() == null) {
                    continue;
                }
                bilibiliMapper.updateBindingStatus(
                        binding.getBindingId(),
                        decision.status(),
                        decision.message()
                );
                if ("BOUND".equals(decision.status())) {
                    linkedCount++;
                } else {
                    anomalyCount++;
                }
            }
        }

        String lastError = warningSet.isEmpty()
                ? null
                : TextUtil.abbreviate(String.join("；", warningSet), 500);
        String nickname = TextUtil.hasText(payload.nickname())
                ? TextUtil.trimToNull(payload.nickname())
                : account.getNickname();
        bilibiliMapper.updateAccountSyncResult(
                account.getAccountId(),
                nickname,
                "ACTIVE",
                syncTime,
                lastError
        );

        List<String> warnings = new ArrayList<>(warningSet);
        String syncStatus = payload.partial() || !warnings.isEmpty() ? "PARTIAL" : "SUCCESS";
        String message = "SUCCESS".equals(syncStatus)
                ? "同步完成"
                : "同步完成，但部分数据未能读取或校验";
        return new BilibiliVideoSyncResponse(
                account.getBilibiliUid(),
                syncStatus,
                validVideos.size(),
                linkedCount,
                anomalyCount,
                lastError,
                warnings,
                payload.hasMore(),
                message
        );
    }

    private Map<String, BilibiliVideoSyncItem> collectValidVideos(BilibiliAccountRecord account,
                                                                   BilibiliVideoSyncPayload payload,
                                                                   Set<String> warningSet) {
        Map<String, BilibiliVideoSyncItem> validVideos = new LinkedHashMap<>();
        for (BilibiliVideoSyncItem item : payload.videos()) {
            if (item == null || item.bvid() == null || !BVID_PATTERN.matcher(item.bvid()).matches()) {
                warningSet.add("同步结果包含格式不正确的BV号，已忽略");
                continue;
            }
            if (!account.getBilibiliUid().equals(item.ownerUid())) {
                warningSet.add("视频 " + item.bvid() + " 的归属UID与当前账号不一致，已忽略");
                continue;
            }
            // 定向详情结果排在账号列表结果之后，覆盖旧的简略指标，保证已绑定视频展示完整数据。
            validVideos.put(item.bvid(), item);
        }
        return validVideos;
    }

    private BindingDecision decideBinding(BilibiliAccountRecord account,
                                          TaskVideoBindingRecord binding,
                                          Map<String, BilibiliVideoSyncItem> validVideos,
                                          Map<String, BilibiliVideoVerificationResult> verificationMap,
                                          Set<String> warningSet) {
        if (!account.getBilibiliUid().equals(binding.getBilibiliUid())) {
            return new BindingDecision(
                    "UID_MISMATCH",
                    "任务绑定的B站UID与当前账号不一致，请在任务中重新确认UID"
            );
        }

        BilibiliVideoVerificationResult verification = verificationMap.get(binding.getBvid());
        if (verification == null && validVideos.containsKey(binding.getBvid())) {
            return new BindingDecision("BOUND", "视频已在当前UID的公开视频列表中，绑定校验通过");
        }
        if (verification == null) {
            // 账号同步只读取最近一页范围；未命中的旧视频不能直接判定为不存在。
            return new BindingDecision(null, null);
        }

        String status = verification.status() == null
                ? "UNKNOWN"
                : verification.status().toUpperCase(Locale.ROOT);
        return switch (status) {
            case "FOUND" -> new BindingDecision("BOUND", TextUtil.trimToDefault(verification.message(), "BV归属校验通过"));
            case "UID_MISMATCH" -> new BindingDecision(
                    "UID_MISMATCH",
                    TextUtil.trimToDefault(verification.message(), "BV不属于当前绑定UID")
            );
            case "VIDEO_NOT_FOUND" -> new BindingDecision(
                    "VIDEO_NOT_FOUND",
                    TextUtil.trimToDefault(verification.message(), "B站未找到该公开视频")
            );
            default -> {
                if (validVideos.containsKey(binding.getBvid())) {
                    yield new BindingDecision("BOUND", "视频已在当前UID的公开视频列表中，绑定校验通过");
                }
                if (TextUtil.hasText(verification.message())) {
                    warningSet.add(verification.message());
                }
                yield new BindingDecision(null, null);
            }
        };
    }

    private BilibiliVideoRecord toVideoRecord(String bilibiliUid,
                                              BilibiliVideoSyncItem item,
                                              LocalDateTime syncTime) {
        LocalDateTime publishTime = item.publishTimestamp() == null
                ? null
                : LocalDateTime.ofInstant(Instant.ofEpochSecond(item.publishTimestamp()), BILIBILI_ZONE);
        return new BilibiliVideoRecord(
                null,
                UUID.randomUUID().toString(),
                bilibiliUid,
                item.bvid(),
                item.aid(),
                TextUtil.abbreviate(item.title(), 255),
                TextUtil.abbreviate(item.coverUrl(), 500),
                publishTime,
                item.viewCount(),
                item.likeCount(),
                item.coinCount(),
                item.favoriteCount(),
                item.shareCount(),
                "SYNCED",
                syncTime,
                TextUtil.hasText(item.rawSnapshot()) ? item.rawSnapshot() : "{}",
                null,
                null
        );
    }

    private record BindingDecision(String status, String message) {
    }
}
