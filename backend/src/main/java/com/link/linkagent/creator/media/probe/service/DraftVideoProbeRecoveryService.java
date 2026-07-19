package com.link.linkagent.creator.media.probe.service;

import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import com.link.linkagent.creator.media.upload.model.DraftVideoStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 恢复服务中断后遗留的超时媒体探测状态。
 * <p>
 * 只有真正发现过期 PROBING 时才进入独立事务，既保证恢复结果不会被调用方随后返回的 409 回滚，
 * 又避免每次反馈、BV 或复盘门禁检查都额外占用一条数据库连接。
 */
@Service
public class DraftVideoProbeRecoveryService {

    private static final long RECOVERY_GRACE_SECONDS = 5L;

    private final CreatorMediaProperties mediaProperties;
    private final MediaUploadMapper mediaUploadMapper;

    public DraftVideoProbeRecoveryService(CreatorMediaProperties mediaProperties,
                                          MediaUploadMapper mediaUploadMapper) {
        this.mediaProperties = mediaProperties;
        this.mediaUploadMapper = mediaUploadMapper;
    }

    public boolean isStale(DraftVideoRecord draft) {
        return DraftVideoStatus.PROBING.name().equals(draft.status())
                && draft.updateTime() != null
                && draft.updateTime().isBefore(staleBefore());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DraftVideoRecord recover(DraftVideoRecord draft) {
        LocalDateTime staleBefore = staleBefore();
        mediaUploadMapper.recoverStaleDraftVideoProbe(
                draft.taskId(),
                draft.ownerId(),
                draft.versionId(),
                staleBefore
        );
        // CAS 可能由并发请求先完成，必须重新读取当前事实，不能继续使用旧快照。
        return mediaUploadMapper.findDraftVideoByVersion(
                        draft.taskId(),
                        draft.ownerId(),
                        draft.versionId()
                )
                .orElse(draft);
    }

    private LocalDateTime staleBefore() {
        return LocalDateTime.now()
                .minus(mediaProperties.getProcessing().getProbeTimeout())
                .minusSeconds(RECOVERY_GRACE_SECONDS);
    }
}
