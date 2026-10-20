package com.link.linkagent.creator.creation.service;

import com.link.linkagent.creator.creation.mapper.CreationMediaMapper;
import com.link.linkagent.creator.creation.mapper.CreationPackageMapper;
import com.link.linkagent.creator.creation.mapper.GeneratedDraftMapper;
import com.link.linkagent.creator.creation.model.CreationMediaLink;
import com.link.linkagent.creator.creation.model.CreationPackageRecord;
import com.link.linkagent.creator.creation.model.GeneratedDraftLease;
import com.link.linkagent.creator.media.preflight.model.CreatePreflightReviewRequest;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewResponse;
import com.link.linkagent.creator.media.preflight.service.PreflightReviewService;
import com.link.linkagent.creator.media.processing.model.MediaProcessingOptionsRequest;
import com.link.linkagent.creator.media.processing.service.MediaProcessingService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 整片媒体闭环调度器。
 * <p>
 * 数据库中的闭环阶段是唯一事实：每次只推进一个可持久化边界，没有完成就继续等待依赖，
 * 子任务标识落库后只恢复不重建，因此刷新、重启和重复扫描都不会重复创建媒体任务或重复付费。
 * 普通人工入口的冲突门禁保持原样，内部受理另走运行租约校验。
 */
@Service
@ConditionalOnProperty(name = {"creator.media.enabled", "creator.creation.renderer.enabled", "creator.creation.media.enabled"},
        havingValue = "true")
public class CreationMediaWorker implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CreationMediaWorker.class);
    private static final String MEDIA_PENDING = "MEDIA_PENDING";
    private static final String MEDIA_RUNNING = "MEDIA_RUNNING";
    private static final String PREFLIGHT_RUNNING = "PREFLIGHT_RUNNING";
    private static final String PACKAGE_PENDING = "PACKAGE_PENDING";
    private static final String PACKAGE_READY = "PACKAGE_READY";

    private final String workerId = "media-" + UUID.randomUUID().toString().replace("-", "");
    private final CreationMediaProperties properties;
    private final CreationMediaMapper links;
    private final CreationPackageMapper packages;
    private final GeneratedDraftMapper drafts;
    private final CreationRuntimeService runtime;
    private final MediaProcessingService processing;
    private final PreflightReviewService preflight;
    private final CreationPackageService packageService;
    private final AtomicBoolean busy = new AtomicBoolean(), stopping = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("creation-media-worker").daemon(true).factory());
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("creation-media-heartbeat").daemon(true).factory());

    public CreationMediaWorker(CreationMediaProperties properties, CreationMediaMapper links,
                               CreationPackageMapper packages, GeneratedDraftMapper drafts,
                               CreationRuntimeService runtime, MediaProcessingService processing,
                               PreflightReviewService preflight, CreationPackageService packageService) {
        properties.validate();
        this.properties = properties; this.links = links; this.packages = packages; this.drafts = drafts;
        this.runtime = runtime; this.processing = processing; this.preflight = preflight;
        this.packageService = packageService;
    }

    @Scheduled(fixedDelayString = "${creator.creation.media.poll-interval-ms:5000}")
    public void poll() {
        if (!properties.isEnabled() || stopping.get() || !busy.compareAndSet(false, true)) return;
        boolean dispatched = false;
        try {
            for (var link : links.candidates()) {
                if (stopping.get()) return;
                if (!ready(link)) continue;
                executor.submit(() -> {
                    try { execute(link); }
                    finally { busy.set(false); }
                });
                dispatched = true;
                return;
            }
        } catch (RuntimeException exception) {
            // 数据库异常不外泄为业务结果，下一轮继续按已落库阶段恢复。
            log.warn("整片媒体闭环扫描暂不可用，类型={}", exception.getClass().getSimpleName());
        } finally {
            if (!dispatched) busy.set(false);
        }
    }

    /** 只有依赖真的推进或可以新领租约时才进入执行线程，避免空转抢锁。 */
    private boolean ready(CreationMediaLink link) {
        var run = runtime.getRun(link.taskId(), link.ownerId(), link.runId());
        if ("CANCEL_REQUESTED".equals(run.status())) return true;
        if (!"WAITING_DEPENDENCY".equals(run.status())) return false;
        return switch (link.stage()) {
            case MEDIA_PENDING, PACKAGE_PENDING, PACKAGE_READY -> true;
            case MEDIA_RUNNING -> processingState(link)
                    .map(state -> "COMPLETED".equals(state) || "FAILED".equals(state)).orElse(false);
            case PREFLIGHT_RUNNING -> reviewState(link)
                    .map(state -> "COMPLETED".equals(state) || "FAILED".equals(state)).orElse(false);
            default -> false;
        };
    }

    private void execute(CreationMediaLink link) {
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicLong generation = new AtomicLong();
        var heartbeat = heartbeats.scheduleAtFixedRate(() -> pulse(link, generation, interrupted),
                properties.getHeartbeatSeconds(), properties.getHeartbeatSeconds(), TimeUnit.SECONDS);
        try {
            var run = runtime.getRun(link.taskId(), link.ownerId(), link.runId());
            if ("CANCEL_REQUESTED".equals(run.status())) { cancel(link, generation); return; }
            if (!"WAITING_DEPENDENCY".equals(run.status())) return;
            if (run.attemptCount() > properties.getMaxAttempts()) { hold(link, "MEDIA_ATTEMPTS_EXHAUSTED"); return; }
            switch (link.stage()) {
                case MEDIA_PENDING -> startProcessing(link, generation);
                case MEDIA_RUNNING -> advanceFromProcessing(link, generation);
                case PREFLIGHT_RUNNING -> advanceFromReview(link, generation, interrupted);
                case PACKAGE_PENDING -> buildPackage(link, claim(link).orElse(null), generation, interrupted);
                case PACKAGE_READY -> complete(link, generation);
                default -> { }
            }
        } catch (RuntimeException exception) {
            if (stopping.get() || Thread.currentThread().isInterrupted()) return;
            log.warn("整片媒体闭环推进失败 runId={} stage={} 类型={}", link.runId(), link.stage(),
                    exception.getClass().getSimpleName());
        } finally {
            heartbeat.cancel(false);
        }
    }

    private void startProcessing(CreationMediaLink link, AtomicLong generation) {
        var lease = claim(link).orElse(null);
        if (lease == null) return;
        generation.set(lease.generation());
        try {
            var job = processing.createJob(link.ownerId(), link.taskId(), link.versionId(), options(),
                    new CreationMediaAcceptance(runtime, drafts, lease));
            links.advance(link.runId(), MEDIA_PENDING, MEDIA_RUNNING, job.jobId(), null, null, lease.generation());
            runtime.waitForDependency(link.taskId(), link.ownerId(), link.runId(), workerId,
                    lease.generation(), job.jobId());
        } catch (RuntimeException exception) {
            failClaimed(link, lease, exception, "MEDIA_PROCESSING_FAILED");
        }
    }

    private void advanceFromProcessing(CreationMediaLink link, AtomicLong generation) {
        var job = processing.getCurrentJob(link.ownerId(), link.taskId(), link.versionId());
        if (!job.jobId().equals(link.processingJobId())) { hold(link, "MEDIA_DEPENDENCY_MISMATCH"); return; }
        if ("FAILED".equals(job.status())) { hold(link, "MEDIA_PROCESSING_FAILED"); return; }
        if (!"COMPLETED".equals(job.status())) return;
        var lease = claim(link).orElse(null);
        if (lease == null) return;
        generation.set(lease.generation());
        try {
            var review = preflight.create(link.ownerId(), link.taskId(), "creation-" + link.runId(),
                    new CreatePreflightReviewRequest(link.versionId(), true,
                            "自动制作：核对成片是否可进入试映"), new CreationMediaAcceptance(runtime, drafts, lease));
            links.advance(link.runId(), MEDIA_RUNNING, PREFLIGHT_RUNNING, job.jobId(), review.reviewId(), null,
                    lease.generation());
            runtime.waitForDependency(link.taskId(), link.ownerId(), link.runId(), workerId,
                    lease.generation(), review.reviewId());
        } catch (RuntimeException exception) {
            failClaimed(link, lease, exception, "PREFLIGHT_START_FAILED");
        }
    }

    private void advanceFromReview(CreationMediaLink link, AtomicLong generation, AtomicBoolean interrupted) {
        var review = preflight.get(link.ownerId(), link.taskId(), link.preflightReviewId());
        if ("FAILED".equals(review.status())) { hold(link, "PREFLIGHT_FAILED"); return; }
        if (!"COMPLETED".equals(review.status())) return;
        if (link.processingJobId() == null) { hold(link, "MEDIA_DEPENDENCY_MISMATCH"); return; }
        var lease = claim(link).orElse(null);
        if (lease == null) return;
        generation.set(lease.generation());
        try {
            if (links.advance(link.runId(), PREFLIGHT_RUNNING, PACKAGE_PENDING, link.processingJobId(),
                    link.preflightReviewId(), null, lease.generation()) != 1) return;
        } catch (RuntimeException exception) {
            failClaimed(link, lease, exception, "PACKAGE_START_FAILED");
            return;
        }
        buildPackage(new CreationMediaLink(link.runId(), link.taskId(), link.ownerId(), link.versionId(),
                link.processingJobId(), link.preflightReviewId(), PACKAGE_PENDING, lease.generation(), null),
                lease, generation, interrupted);
    }

    private void buildPackage(CreationMediaLink link, GeneratedDraftLease lease, AtomicLong generation,
                              AtomicBoolean interrupted) {
        if (lease == null) return;
        generation.set(lease.generation());
        try {
            CreationPackageRecord ready = packageService.ensure(lease, link, interrupted::get);
            if (links.advance(link.runId(), PACKAGE_PENDING, PACKAGE_READY, ready.processingJobId(),
                    ready.preflightReviewId(), null, lease.generation()) != 1) return;
            // 释放租约，下一步只做完成校验；包本身已经 READY，不会重复构建。
            runtime.waitForDependency(link.taskId(), link.ownerId(), link.runId(), workerId,
                    lease.generation(), "PACKAGE_READY");
        } catch (RuntimeException exception) {
            failClaimed(link, lease, exception, "PACKAGE_BUILD_FAILED");
        }
    }

    private void complete(CreationMediaLink link, AtomicLong generation) {
        var lease = claim(link).orElse(null);
        if (lease == null) return;
        generation.set(lease.generation());
        try {
            var pkg = packages.find(link.runId()).orElseThrow(() -> conflict("发布包尚未生成"));
            if (!"READY".equals(pkg.status()) || pkg.objectKey() == null) throw conflict("发布包尚未通过对象校验");
            var review = preflight.get(link.ownerId(), link.taskId(), link.preflightReviewId());
            runtime.completeProduction(link.taskId(), link.ownerId(), link.runId(), workerId,
                    lease.generation(), result(link, pkg, review));
        } catch (RuntimeException exception) {
            failClaimed(link, lease, exception, "PACKAGE_COMPLETE_FAILED");
        }
    }

    private void cancel(CreationMediaLink link, AtomicLong generation) {
        var lease = claim(link).orElse(null);
        if (lease == null) return;
        generation.set(lease.generation());
        // 取消不能承诺供应商退款，只请求停止后续调用；已发送的费用由费用流水保留为待核实。
        try {
            if (link.preflightReviewId() != null) preflight.cancel(link.ownerId(), link.taskId(), link.preflightReviewId());
        } catch (RuntimeException exception) {
            log.info("取消时试映任务已不可取消 runId={}", link.runId());
        }
        try {
            runtime.acknowledgeCancellation(link.taskId(), link.ownerId(), link.runId(), workerId, lease.generation());
        } catch (RuntimeException exception) {
            log.info("取消收尾未写入，执行权已变化 runId={}", link.runId());
        }
    }

    /** 等待依赖中的运行需要先唤醒再领取；取消请求可以直接领取后收尾。 */
    private Optional<GeneratedDraftLease> claim(CreationMediaLink link) {
        try {
            var run = runtime.getRun(link.taskId(), link.ownerId(), link.runId());
            if ("WAITING_DEPENDENCY".equals(run.status())) {
                runtime.wake(link.taskId(), link.ownerId(), link.runId(), run.runRevision());
            } else if (!"CANCEL_REQUESTED".equals(run.status())) {
                return Optional.empty();
            }
            return runtime.claim(link.taskId(), link.ownerId(), link.runId(), workerId, properties.getLeaseSeconds())
                    .map(claimed -> new GeneratedDraftLease(claimed.taskId(), claimed.ownerId(), claimed.runId(),
                            workerId, claimed.leaseGeneration()));
        } catch (ResponseStatusException exception) {
            return Optional.empty();
        }
    }

    /** 可自动恢复的异常释放租约等待下一轮；确定性冲突保留运行并要求作者处理。 */
    private void failClaimed(CreationMediaLink link, GeneratedDraftLease lease, RuntimeException exception, String code) {
        if (exception instanceof ResponseStatusException status) {
            holdWithLease(link, lease, status.getStatusCode().is4xxClientError() ? code : "MEDIA_SERVICE_UNAVAILABLE");
            return;
        }
        try {
            runtime.waitForDependency(link.taskId(), link.ownerId(), link.runId(), workerId, lease.generation(),
                    "MEDIA_RETRY");
        } catch (RuntimeException release) {
            log.info("媒体闭环异常后未能释放租约 runId={}", link.runId());
        }
    }

    private void hold(CreationMediaLink link, String code) {
        var lease = claim(link).orElse(null);
        if (lease == null) return;
        holdWithLease(link, lease, code);
    }

    private void holdWithLease(CreationMediaLink link, GeneratedDraftLease lease, String code) {
        try {
            runtime.holdForUser(link.taskId(), link.ownerId(), link.runId(), workerId, lease.generation(), code);
            links.advance(link.runId(), link.stage(), "FAILED", link.processingJobId(), link.preflightReviewId(),
                    code, lease.generation());
        } catch (RuntimeException exception) {
            log.info("媒体闭环保留运行失败 runId={} code={}", link.runId(), code);
        }
    }

    private String result(CreationMediaLink link, CreationPackageRecord pkg, PreflightReviewResponse review) {
        return """
                {"kind":"FULL_PRODUCTION","versionId":"%s","processingJobId":"%s","preflightReviewId":"%s",\
                "preflightStatus":"%s","preflightIssueCount":%d,"packageObjectKey":"%s","packageBytes":%d,\
                "packageSha256":"%s","published":false}"""
                .formatted(link.versionId(), link.processingJobId(), link.preflightReviewId(), review.status(),
                        review.issues() == null ? 0 : review.issues().size(), pkg.objectKey(), pkg.bytes(),
                        pkg.sha256());
    }

    private Optional<String> processingState(CreationMediaLink link) {
        try { return Optional.of(processing.getCurrentJob(link.ownerId(), link.taskId(), link.versionId()).status()); }
        catch (ResponseStatusException exception) { return Optional.empty(); }
    }

    private Optional<String> reviewState(CreationMediaLink link) {
        try { return Optional.of(preflight.get(link.ownerId(), link.taskId(), link.preflightReviewId()).status()); }
        catch (ResponseStatusException exception) { return Optional.empty(); }
    }

    private MediaProcessingOptionsRequest options() {
        return new MediaProcessingOptionsRequest(properties.getFrameIntervalSeconds(),
                MediaProcessingOptionsRequest.Resolution.valueOf(properties.getResolution()),
                MediaProcessingOptionsRequest.ModelPlan.valueOf(properties.getModelPlan()),
                properties.isIncludeAsr());
    }

    private void pulse(CreationMediaLink link, AtomicLong generation, AtomicBoolean interrupted) {
        if (interrupted.get() || stopping.get() || generation.get() == 0) return;
        try {
            if (!runtime.heartbeat(link.taskId(), link.ownerId(), link.runId(), workerId, generation.get(),
                    properties.getLeaseSeconds())) interrupted.set(true);
        } catch (RuntimeException exception) {
            // 无法证明仍持有活租约就停止本次推进，不在断库时继续上传或登记。
            interrupted.set(true);
        }
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    @Override
    @PreDestroy
    public void close() {
        stopping.set(true);
        heartbeats.shutdownNow();
        executor.shutdownNow();
        try { executor.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
