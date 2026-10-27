package com.link.linkagent.creator.creation.service;

import com.link.linkagent.creator.creation.mapper.GeneratedDraftMapper;
import com.link.linkagent.creator.creation.model.GeneratedDraftLease;
import com.link.linkagent.creator.media.access.InternalMediaAcceptance;

import static com.link.linkagent.creator.creation.service.CreationInputRules.conflict;

/**
 * 整片运行专用的媒体受理凭据。
 * <p>
 * 每次创建子任务前重新核对：运行是当前活动运行、执行租约仍然有效、整片授权未撤销、
 * 目标成片正是本运行 REGISTERED 的那一个版本。任何一条不满足都不会产生新的媒体任务。
 */
public final class CreationMediaAcceptance implements InternalMediaAcceptance {

    private final CreationRuntimeService runtime;
    private final GeneratedDraftMapper drafts;
    private final GeneratedDraftLease lease;

    public CreationMediaAcceptance(CreationRuntimeService runtime, GeneratedDraftMapper drafts,
                                   GeneratedDraftLease lease) {
        this.runtime = runtime;
        this.drafts = drafts;
        this.lease = lease;
    }

    @Override
    public void verify(String taskId, String ownerId, String versionId) {
        if (!lease.taskId().equals(taskId) || !lease.ownerId().equals(ownerId)) {
            throw conflict("媒体受理不属于当前制作运行");
        }
        runtime.requireProductionLease(lease);
        var attempt = drafts.registered(taskId, ownerId, lease.runId())
                .orElseThrow(() -> conflict("本次运行尚未登记系统成片"));
        if (!attempt.versionId().equals(versionId)) {
            throw conflict("媒体受理版本不属于本次运行");
        }
    }
}
