package com.link.linkagent.creator.creation.service;

import com.link.linkagent.creator.creation.mapper.FixedProjectJobMapper;
import com.link.linkagent.creator.creation.model.*;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.Run;
import com.link.linkagent.creator.creation.render.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 数据库是队列和执行权的唯一依据；线程池仅承载当前领取的一次尝试。 */
@Service
@ConditionalOnProperty(name = {"creator.media.enabled", "creator.creation.renderer.enabled", "creator.creation.worker.enabled"}, havingValue = "true")
public class FixedProjectWorker implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(FixedProjectWorker.class);
    private final String workerId = "fixed-" + UUID.randomUUID().toString().replace("-", "");
    private final CreationWorkerProperties properties;
    private final FixedProjectJobMapper jobs;
    private final CreationRuntimeService runtime;
    private final FixedProjectJobFiles files;
    private final FixedProjectRenderService renderer;
    private final GeneratedDraftService drafts;
    private final GeneratedDraftPersistenceService persistence;
    private final GeneratedDraftProbeService probe;
    private final AtomicBoolean busy = new AtomicBoolean(), stopping = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("creation-fixed-worker").daemon(true).factory());
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("creation-fixed-heartbeat").daemon(true).factory());

    public FixedProjectWorker(CreationWorkerProperties properties, FixedProjectJobMapper jobs,
            CreationRuntimeService runtime, FixedProjectJobFiles files, FixedProjectRenderService renderer,
            GeneratedDraftService drafts, GeneratedDraftPersistenceService persistence, GeneratedDraftProbeService probe) {
        properties.validate(); this.properties = properties; this.jobs = jobs; this.runtime = runtime;
        this.files = files; this.renderer = renderer; this.drafts = drafts; this.persistence = persistence;
        this.probe = probe;
    }

    @Scheduled(fixedDelayString = "${creator.creation.worker.poll-interval-ms:2000}")
    public void poll() {
        if (!properties.isEnabled() || stopping.get() || !busy.compareAndSet(false, true)) return;
        boolean dispatched = false;
        try {
            for (var job : jobs.candidates()) {
                if (stopping.get()) return;
                var claimed = runtime.claim(job.taskId(), job.ownerId(), job.runId(), workerId, properties.getLeaseSeconds());
                if (claimed.isEmpty()) continue;
                executor.submit(() -> {
                    try { execute(job, claimed.get()); }
                    finally { busy.set(false); }
                });
                dispatched = true;
                return;
            }
        } catch (RuntimeException e) {
            // 不把数据库或存储异常原文写入用户结果，领取后退出则由租约到期恢复。
            log.warn("固定工程扫描暂不可用，类型={}", e.getClass().getSimpleName());
        } finally {
            if (!dispatched) busy.set(false);
        }
    }

    private void execute(FixedProjectJob job, Run run) {
        var lease = new GeneratedDraftLease(run.taskId(), run.ownerId(), run.runId(), workerId, run.leaseGeneration());
        AtomicBoolean interruptedLease = new AtomicBoolean();
        ScheduledFuture<?> heartbeat = null;
        try {
            if ("CANCEL_REQUESTED".equals(run.status())) {
                runtime.acknowledgeCancellation(run.taskId(), run.ownerId(), run.runId(), workerId, run.leaseGeneration());
                return;
            }
            heartbeat = heartbeats.scheduleAtFixedRate(() -> pulse(run, interruptedLease), 0,
                    properties.getHeartbeatSeconds(), TimeUnit.SECONDS);
            java.util.function.BooleanSupplier cancelled = () -> stopping.get() || interruptedLease.get()
                    || Thread.currentThread().isInterrupted();
            var restored = persistence.registeredResult(lease);
            com.link.linkagent.creator.media.upload.model.DraftVideoRecord draft;
            if (restored.isPresent()) draft = restored.get();
            else {
                if (run.attemptCount() > properties.getMaxAttempts()) {
                    runtime.fail(run.taskId(), run.ownerId(), run.runId(), workerId, run.leaseGeneration(), "ATTEMPTS_EXHAUSTED");
                    return;
                }
                persistence.validateBeforeRender(lease);
                var request = files.prepare(job, run, cancelled);
                persistence.checkLive(lease);
                renderer.render(request, cancelled);
                draft = drafts.registerGeneratedDraft(lease, request, cancelled);
            }
            if (cancelled.getAsBoolean()) throw new RenderExecutionException("CANCELLED", "本次执行已停止");
            probe.probe(lease, draft.versionId(), cancelled);
            if (cancelled.getAsBoolean()) throw new RenderExecutionException("CANCELLED", "本次执行已停止");
            // 成片登记之后仍有真实媒体处理和完整试映；不能提前完成 FULL 或释放活动槽。
            runtime.waitForDependency(run.taskId(), run.ownerId(), run.runId(), workerId, run.leaseGeneration(), draft.versionId());
        } catch (RuntimeException e) {
            if (stopping.get() || Thread.currentThread().isInterrupted()) return;
            try {
                var current = runtime.getRun(run.taskId(), run.ownerId(), run.runId());
                if ("CANCEL_REQUESTED".equals(current.status()))
                    runtime.acknowledgeCancellation(run.taskId(), run.ownerId(), run.runId(), workerId, run.leaseGeneration());
                else if (!interruptedLease.get()) {
                    String code = e instanceof RenderExecutionException render ? render.code() : "FIXED_JOB_FAILED";
                    runtime.fail(run.taskId(), run.ownerId(), run.runId(), workerId, run.leaseGeneration(), code);
                }
            } catch (RuntimeException fence) {
                log.info("固定工程结果未写入，运行={}，执行权已变化或暂不可用", run.runId());
            }
        } finally {
            if (heartbeat != null) heartbeat.cancel(false);
        }
    }

    private void pulse(Run run, AtomicBoolean interruptedLease) {
        if (interruptedLease.get() || stopping.get()) return;
        try {
            if (!runtime.heartbeat(run.taskId(), run.ownerId(), run.runId(), workerId,
                    run.leaseGeneration(), properties.getLeaseSeconds())
                    || !"RUNNING".equals(runtime.getRun(run.taskId(), run.ownerId(), run.runId()).status()))
                interruptedLease.set(true);
        } catch (RuntimeException e) {
            // 无法证明仍持有活租约就停本次进程，不在断库时继续上传或登记。
            interruptedLease.set(true);
        }
    }

    @Override
    @PreDestroy
    public void close() {
        stopping.set(true); heartbeats.shutdownNow(); executor.shutdownNow();
        try { executor.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
