package com.link.linkagent.creator.creation.service;

import com.link.linkagent.creator.creation.mapper.CreationMediaMapper;
import com.link.linkagent.creator.creation.mapper.GeneratedDraftMapper;
import com.link.linkagent.creator.creation.model.CreationMediaLink;
import com.link.linkagent.creator.creation.model.GeneratedDraftLease;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.Supplier;

import static com.link.linkagent.creator.creation.service.CreationInputRules.conflict;

/** 把已探测可用的系统成片交给真实媒体链路；与等待依赖同事务，运行和闭环不会只落一半。 */
@Service
@Transactional
public class CreationMediaService {
    private final CreationMediaMapper links;
    private final GeneratedDraftMapper drafts;
    private final CreationRuntimeService runtime;

    public CreationMediaService(CreationMediaMapper links, GeneratedDraftMapper drafts, CreationRuntimeService runtime) {
        this.links = links; this.drafts = drafts; this.runtime = runtime;
    }

    public void begin(GeneratedDraftLease lease, String versionId) {
        runtime.requireProductionLease(lease);
        var attempt = drafts.registered(lease.taskId(), lease.ownerId(), lease.runId())
                .orElseThrow(() -> conflict("本次运行尚未登记系统成片"));
        if (!attempt.versionId().equals(versionId)) throw conflict("媒体处理版本不属于本次运行");
        var existing = links.find(lease.runId());
        if (existing.isPresent()) {
            // 同一运行只处理一个精确版本；重复扫描沿用已有闭环，不新建子任务。
            if (!existing.get().versionId().equals(versionId)) throw conflict("本次运行已进入其他版本的媒体处理");
        } else {
            links.insert(new CreationMediaLink(lease.runId(), lease.taskId(), lease.ownerId(), versionId,
                    null, null, "MEDIA_PENDING", lease.generation(), null));
        }
        runtime.waitForDependency(lease.taskId(), lease.ownerId(), lease.runId(), lease.worker(),
                lease.generation(), versionId);
    }

    @Transactional(readOnly = true)
    public Optional<CreationMediaLink> find(String runId) {
        return links.find(runId);
    }

    /** 子任务受理只含数据库操作；和归属绑定同事务提交，试映 worker 不会先看到一个未记账的任务。 */
    public void acceptProcessing(GeneratedDraftLease lease, CreationMediaLink expected, Supplier<String> acceptance) {
        verify(lease, expected);
        String jobId = acceptance.get();
        advanceAndWait(lease, expected, "MEDIA_RUNNING", jobId, null, jobId);
    }

    public void acceptReview(GeneratedDraftLease lease, CreationMediaLink expected, Supplier<String> acceptance) {
        verify(lease, expected);
        String reviewId = acceptance.get();
        advanceAndWait(lease, expected, "PREFLIGHT_RUNNING", expected.processingJobId(), reviewId, reviewId);
    }

    public void advanceAndWait(GeneratedDraftLease lease, CreationMediaLink expected, String nextStage,
                               String jobId, String reviewId, String dependencyId) {
        verify(lease, expected);
        if (links.advance(lease.runId(), expected.stage(), nextStage, jobId, reviewId, null, lease.generation()) != 1)
            throw conflict("媒体闭环阶段已变化或执行租约已失效");
        // 最后再用数据库时钟检查租约，阶段和等待状态必须整体提交或整体回滚。
        runtime.waitForDependency(lease.taskId(), lease.ownerId(), lease.runId(), lease.worker(),
                lease.generation(), dependencyId);
    }

    public void hold(GeneratedDraftLease lease, CreationMediaLink expected, String errorCode) {
        verify(lease, expected);
        if (links.advance(lease.runId(), expected.stage(), "FAILED", expected.processingJobId(),
                expected.preflightReviewId(), errorCode, lease.generation()) != 1)
            throw conflict("媒体闭环阶段已变化或执行租约已失效");
        runtime.holdForUser(lease.taskId(), lease.ownerId(), lease.runId(), lease.worker(), lease.generation(), errorCode);
    }

    private void verify(GeneratedDraftLease lease, CreationMediaLink expected) {
        runtime.requireProductionLease(lease);
        var actual = links.find(lease.runId()).orElseThrow(() -> conflict("媒体闭环不存在"));
        if (!lease.runId().equals(expected.runId()) || !lease.taskId().equals(expected.taskId())
                || !lease.ownerId().equals(expected.ownerId()) || !actual.versionId().equals(expected.versionId())
                || !actual.stage().equals(expected.stage()) || actual.leaseGeneration() != expected.leaseGeneration())
            throw conflict("媒体闭环已由其他执行者推进");
    }
}
