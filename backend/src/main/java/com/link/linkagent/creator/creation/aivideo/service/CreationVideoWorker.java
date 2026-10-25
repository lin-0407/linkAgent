package com.link.linkagent.creator.creation.aivideo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.aivideo.mapper.CreationVideoMapper;
import com.link.linkagent.creator.creation.aivideo.model.CreationVideo;
import com.link.linkagent.creator.creation.aivideo.model.VideoModels.Proposal;
import com.link.linkagent.creator.creation.aivideo.provider.VideoGenerationProvider;
import com.link.linkagent.creator.creation.asset.model.CreationAsset;
import com.link.linkagent.creator.creation.asset.service.AssetMediaPreparer;
import com.link.linkagent.creator.creation.asset.service.CreationAssetService;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.Charge;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.Run;
import com.link.linkagent.creator.creation.render.FixedProjectRendererProperties;
import com.link.linkagent.creator.creation.service.CreationBillingProperties;
import com.link.linkagent.creator.creation.service.CreationCapabilityGate;
import com.link.linkagent.creator.creation.service.CreationRuntimeService;
import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardCompiler;
import com.link.linkagent.creator.media.config.ObjectStorageProperties;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * AI 视频生成后台执行：只处理用户明确要求生成画面的镜头。
 * <p>
 * 提交前持久化任务、提示词摘要和费用预留，拿到任务标识后只查询该任务；
 * 结果下载后经真实解码规范化、上传并复核摘要，才登记为私有 READY 素材。
 * 片段短于镜头、供应商失败或结果不明时只给提案或受控失败，不保存不可渲染版本。
 */
@Service
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class CreationVideoWorker implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CreationVideoWorker.class);
    private static final Duration STORAGE_TIMEOUT = Duration.ofSeconds(300);
    private static final long MAX_DOWNLOAD_BYTES = 256L * 1024 * 1024;
    private static final int LEASE_SECONDS = 300;

    private final CreationVideoMapper mapper;
    private final CreationVideoPersistence persistence;
    private final CreationVideoProperties settings;
    private final CreationRuntimeService runtime;
    private final CreationBillingProperties billing;
    private final StoryboardMapper boards;
    private final StoryboardCodec codec;
    private final StoryboardCompiler compiler;
    private final AssetMediaPreparer preparer;
    private final CreationAssetService assets;
    private final ObjectStorageService storage;
    private final ObjectStorageProperties storageProperties;
    private final FixedProjectRendererProperties renderer;
    private final List<VideoGenerationProvider> providers;
    private final ObjectMapper json;
    private final CreationCapabilityGate capabilities;

    private final String worker = "video-" + UUID.randomUUID();
    private final AtomicBoolean busy = new AtomicBoolean(), stopping = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon(true).name("creation-video-worker").factory());
    private final ScheduledExecutorService pulses = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("creation-video-heartbeat").factory());

    public CreationVideoWorker(CreationVideoMapper mapper, CreationVideoPersistence persistence,
            CreationVideoProperties settings, CreationRuntimeService runtime, CreationBillingProperties billing,
            StoryboardMapper boards, StoryboardCodec codec, StoryboardCompiler compiler, AssetMediaPreparer preparer,
            CreationAssetService assets, ObjectStorageService storage, ObjectStorageProperties storageProperties,
            FixedProjectRendererProperties renderer, List<VideoGenerationProvider> providers, ObjectMapper json) {
        this(mapper, persistence, settings, runtime, billing, boards, codec, compiler, preparer, assets, storage,
                storageProperties, renderer, providers, json, CreationCapabilityGate.defaults(false, settings.isEnabled()));
    }

    @Autowired
    public CreationVideoWorker(CreationVideoMapper mapper, CreationVideoPersistence persistence,
            CreationVideoProperties settings, CreationRuntimeService runtime, CreationBillingProperties billing,
            StoryboardMapper boards, StoryboardCodec codec, StoryboardCompiler compiler, AssetMediaPreparer preparer,
            CreationAssetService assets, ObjectStorageService storage, ObjectStorageProperties storageProperties,
            FixedProjectRendererProperties renderer, List<VideoGenerationProvider> providers, ObjectMapper json,
            CreationCapabilityGate capabilities) {
        this.mapper = mapper; this.persistence = persistence; this.settings = settings; this.runtime = runtime;
        this.billing = billing; this.boards = boards; this.codec = codec; this.compiler = compiler;
        this.preparer = preparer; this.assets = assets; this.storage = storage; this.storageProperties = storageProperties;
        this.renderer = renderer; this.providers = providers; this.json = json; this.capabilities = capabilities;
    }

    @Scheduled(fixedDelayString = "${creator.creation.video.scan-interval-ms:3000}")
    public void poll() {
        if (stopping.get() || !busy.compareAndSet(false, true)) return;
        try {
            executor.submit(() -> {
                try {
                    mapper.terminalWithPending().forEach(ref -> persistence.closeRows(ref.runId(),
                            "CANCELLED".equals(ref.runStatus()) ? "CANCELLED" : "FAILED",
                            "CANCELLED".equals(ref.runStatus()) ? null : "VIDEO_RUN_FAILED"));
                    // 关闭后仍要领取运行，才能把已提交的供应商任务查询完并结算；新的提交由 execute 跳过。
                    for (var job : mapper.candidates()) { if (stopping.get()) break; process(job); }
                } catch (RuntimeException ex) { log.warn("视频扫描暂不可用，类型={}", ex.getClass().getSimpleName()); }
                finally { busy.set(false); }
            });
        } catch (RuntimeException ex) { busy.set(false); if (!stopping.get()) log.warn("视频扫描暂不可用，类型={}", ex.getClass().getSimpleName()); }
    }

    /** 同步入口供受控测试推进同一数据库队列，不绕过领取、发送标记与租约围栏。 */
    public void process(CreationVideoMapper.Job job) {
        Run run;
        try {
            run = runtime.claim(job.taskId(), job.ownerId(), job.runId(), worker, LEASE_SECONDS).orElse(null);
            if (run == null) return;
        } catch (ResponseStatusException exception) {
            return;
        }
        if ("CANCEL_REQUESTED".equals(run.status())) {
            closeCancelled(run);
            return;
        }
        var lost = new AtomicBoolean();
        var pulse = pulses.scheduleAtFixedRate(() -> {
            try {
                if (!runtime.heartbeat(run.taskId(), run.ownerId(), run.runId(), worker,
                        run.leaseGeneration(), LEASE_SECONDS)
                        || !"RUNNING".equals(runtime.getRun(run.taskId(), run.ownerId(), run.runId()).status()))
                    lost.set(true);
            } catch (RuntimeException ex) { lost.set(true); }
        }, 0, 2, TimeUnit.SECONDS);
        try {
            execute(job, run, lost);
        } catch (VideoFailure failure) {
            log.info("AI 视频准备未完成，运行={}，原因={}", run.runId(), failure.code);
        } catch (Exception exception) {
            // 结算冲突、DAO 异常等不能把运行留在 RUNNING：否则心跳停止后会被反复重领并重复同一路径。
            log.warn("AI 视频准备中止，运行={}，类型={}", run.runId(), exception.getClass().getSimpleName());
            try { settleRunAsFailed(run, "VIDEO_RUN_ABORTED"); }
            catch (RuntimeException ignored) { log.info("视频中止收尾等待租约恢复，运行={}", run.runId()); }
        } finally {
            pulse.cancel(false);
        }
    }

    private void execute(CreationVideoMapper.Job job, Run run, AtomicBoolean lost) {
        BooleanSupplier cancelled = () -> stopping.get() || lost.get() || Thread.currentThread().isInterrupted();
        // 上次进程可能在提交中途退出：已发送的场景不能重发，未发送的场景可以安全回到排队。
        if (recoverInterrupted(run)) {
            settleRunAsFailed(run, "VIDEO_RESULT_UNKNOWN");
            return;
        }
        var config=codec.read(run.requestJson()).path("price").path("providerConfigurationHash").asText();
        if(!config.isBlank() && !config.equals(com.link.linkagent.creator.creation.execution.ProviderConfiguration.videoFingerprint(settings))){
            settleRunAsFailed(run,"PROVIDER_CONFIGURATION_CHANGED");return;
        }
        String failureCode = null;
        boolean paused = false;
        boolean enabled = capabilities.videoEnabled();
        for (CreationVideo row : mapper.scenes(run.runId())) {
            if (cancelled.getAsBoolean()) break;
            if (!"QUEUED".equals(row.getStatus())) continue;
            // 关闭后不再提交新镜头；已提交的 SUBMITTED 仍会进入下面的轮询与结算。
            if (!enabled) { paused = true; continue; }
            try {
                submit(run, row, cancelled);
            } catch (VideoFailure failure) {
                if (cancelled.getAsBoolean()) break;
                markFailed(row, failure.code, run.leaseGeneration());
                // 停止新增付费提交，但已经提交的任务仍要继续查询与结算，不能把它们丢在供应商侧。
                failureCode = failure.code;
                break;
            }
        }
        if (!cancelled.getAsBoolean()) {
            long deadline = System.nanoTime() + settings.getTaskTimeout().toNanos();
            while (true) {
                if (cancelled.getAsBoolean()) break;
                List<CreationVideo> pending = mapper.scenes(run.runId()).stream()
                        .filter(row -> "SUBMITTED".equals(row.getStatus())).toList();
                if (pending.isEmpty()) break;
                for (CreationVideo row : pending) {
                    if (cancelled.getAsBoolean()) break;
                    try { pollScene(run, row, cancelled); }
                    catch (VideoFailure failure) {
                        if (cancelled.getAsBoolean()) break;
                        markFailed(row, failure.code, run.leaseGeneration());
                        if (failureCode == null) failureCode = failure.code;
                    }
                }
                if (cancelled.getAsBoolean()) break;
                if (System.nanoTime() >= deadline) {
                    for (CreationVideo row : mapper.scenes(run.runId()))
                        if ("SUBMITTED".equals(row.getStatus())) {
                            // 已提交但结果未回：保留任务标识，费用未知，不在这里自动重新提交。
                            markUnknown(run, videoKey(run.runId(), row.getSceneId()));
                            markFailed(row, "VIDEO_GENERATION_TIMEOUT", run.leaseGeneration());
                        }
                    if (failureCode == null) failureCode = "VIDEO_GENERATION_TIMEOUT";
                    break;
                }
                sleep(settings.getPollInterval());
            }
        }
        if (cancelled.getAsBoolean()) { cancelIfRequested(run); return; }
        if (failureCode != null) {
            try { settleRunAsFailed(run, failureCode); }
            catch (RuntimeException exception) {
                log.info("视频失败收尾等待租约恢复，运行={}，原因={}", run.runId(), failureCode);
            }
            return;
        }
        // 还有未提交的镜头被开关暂停：保留运行，重新开启后从排队状态继续。
        if (paused) return;
        List<CreationVideo> rows = mapper.scenes(run.runId());
        List<CreationVideo> unresolved = rows.stream()
                .filter(row -> !Set.of("READY", "NEEDS_REVIEW").contains(row.getStatus())).toList();
        if (!unresolved.isEmpty()) {
            markFailed(unresolved.getFirst(), "VIDEO_PREPARATION_INCOMPLETE", run.leaseGeneration());
            settleRunAsFailed(run, "VIDEO_PREPARATION_INCOMPLETE");
            return;
        }
        boolean blocked = rows.stream().anyMatch(row -> "NEEDS_REVIEW".equals(row.getStatus()));
        try {
            if (blocked) {
                runtime.releaseUnsent(run.taskId(), run.ownerId(), run.runId());
                runtime.complete(run.taskId(), run.ownerId(), run.runId(), worker, run.leaseGeneration(),
                        reviewResult(job, rows));
                return;
            }
            finish(job, run, rows);
        } catch (VideoFailure failure) {
            log.warn("AI 视频收尾未通过，运行={}，原因={}", run.runId(), failure.code);
            try { settleRunAsFailed(run, failure.code); }
            catch (RuntimeException ignored) { log.info("视频失败收尾等待租约恢复，运行={}", run.runId()); }
        } catch (RuntimeException exception) {
            log.warn("AI 视频收尾失败，运行={}，类型={}", run.runId(), exception.getClass().getSimpleName());
            try { settleRunAsFailed(run); }
            catch (RuntimeException ignored) { log.info("视频失败收尾等待租约恢复，运行={}", run.runId()); }
        }
    }

    private boolean recoverInterrupted(Run run) {
        for (CreationVideo row : mapper.scenes(run.runId())) {
            if (!"SUBMITTING".equals(row.getStatus())) continue;
            String key = videoKey(run.runId(), row.getSceneId());
            var charge = runtime.findCharge(run.taskId(), run.ownerId(), run.runId(), key);
            if (charge.isPresent() && charge.get().sentAt() != null) {
                runtime.markUnknown(run.taskId(), run.ownerId(), key);
                markFailed(row, "VIDEO_RESULT_UNKNOWN", run.leaseGeneration());
                return true;
            }
            // 没有发送事实才能重排，避免把可能已经提交的调用再付一次。
            mapper.requeue(run.runId(), row.getSceneId());
        }
        return false;
    }

    private void cancelIfRequested(Run run) {
        if ("CANCEL_REQUESTED".equals(runtime.getRun(run.taskId(), run.ownerId(), run.runId()).status()))
            closeCancelled(run);
    }

    /**
     * 取消不承诺供应商退款。已经发送的调用不能用免费释放掩盖，统一保留为未知费用，
     * 迟到的结果不再写入素材或版本；未发送的预留在运行收尾时释放。
     */
    private void closeCancelled(Run run) {
        for (CreationVideo row : mapper.scenes(run.runId())) {
            if (Set.of("READY", "NEEDS_REVIEW", "FAILED", "CANCELLED").contains(row.getStatus())) continue;
            String key = videoKey(run.runId(), row.getSceneId());
            var charge = runtime.findCharge(run.taskId(), run.ownerId(), run.runId(), key);
            if (charge.isPresent() && charge.get().sentAt() != null) markUnknown(run, key);
        }
        persistence.closeRows(run.runId(), "CANCELLED", null);
        acknowledge(run);
    }

    // ---------------------------------------------------------------- 提交与查询

    private void submit(Run run, CreationVideo row, BooleanSupplier cancelled) {
        var provider = providers.isEmpty() ? null : providers.getFirst();
        if (provider == null) throw new VideoFailure("VIDEO_PROVIDER_NOT_CONFIGURED");
        int seconds = row.getRequestedSeconds();
        if (seconds <= 0) throw new VideoFailure("VIDEO_DURATION_INVALID");
        if (mapper.beginSubmit(run.runId(), row.getSceneId(), provider.provider(), provider.model(), seconds,
                worker, run.leaseGeneration()) != 1) return;
        row = reload(row);
        String key = videoKey(run.runId(), row.getSceneId());
        // 预留与快照沿用受理时冻结的单价；估算确认后改配置不能改变同一请求的金额。
        BigDecimal unit = settings.getUsdPerSecond(), rate = billing.getUsdToCny();
        try {
            var price = json.readTree(run.requestJson()).path("price");
            if (price.path("usdPerSecond").isNumber() && price.path("usdToCny").isNumber()) {
                unit = price.path("usdPerSecond").decimalValue();
                rate = price.path("usdToCny").decimalValue();
            }
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new VideoFailure("VIDEO_PRICE_SNAPSHOT_INVALID");
        }
        if (unit.signum() <= 0 || rate.signum() <= 0) throw new VideoFailure("VIDEO_PRICE_SNAPSHOT_INVALID");
        BigDecimal usd = unit.multiply(BigDecimal.valueOf(seconds));
        String snapshot = priceSnapshot(usd, rate, Map.of("model", provider.model(), "seconds", seconds,
                "usdPerSecond", unit.toPlainString()));
        Charge charge = beginCharge(run, key, cny(usd, rate), snapshot);
        if (!send(run, charge, key)) throw new VideoFailure("VIDEO_SUBMIT_NOT_SENT");
        if (cancelled.getAsBoolean()) throw new VideoFailure("VIDEO_CANCELLED");
        VideoGenerationProvider.TaskRef ref;
        try {
            var capabilities = provider.capabilities();
            ref = provider.submit(new VideoGenerationProvider.Submit(row.getPromptText(), seconds,
                    capabilities.width(), capabilities.height(), seed(row.getPromptSha256())));
        } catch (VideoGenerationProvider.VideoRejectedException rejected) {
            // 明确的 4xx 拒绝没有创建任务，按 0 结算，不能把额度长期挂成未知。
            settle(run, key, BigDecimal.ZERO.setScale(6), "video-rejected-" + row.getSceneId());
            throw new VideoFailure("VIDEO_GENERATION_REJECTED");
        } catch (VideoGenerationProvider.VideoNotConfiguredException absent) {
            settle(run, key, BigDecimal.ZERO.setScale(6), "video-not-configured-" + row.getSceneId());
            throw new VideoFailure("VIDEO_PROVIDER_NOT_CONFIGURED");
        } catch (RuntimeException exception) {
            markUnknown(run, key);
            throw new VideoFailure("VIDEO_RESULT_UNKNOWN");
        }
        try {
            persistence.submitted(row, ref.taskId(), ref.requestId(), worker, run.leaseGeneration());
        } catch (RuntimeException exception) {
            markUnknown(run, key);
            throw new VideoFailure("VIDEO_SUBMIT_UNCERTAIN");
        }
    }

    private void pollScene(Run run, CreationVideo row, BooleanSupplier cancelled) {
        var provider = providers.isEmpty() ? null : providers.getFirst();
        if (provider == null) throw new VideoFailure("VIDEO_PROVIDER_NOT_CONFIGURED");
        VideoGenerationProvider.QueryResult result;
        try {
            result = provider.query(row.getProviderTaskId());
        } catch (RuntimeException exception) {
            // 查询失败不代表任务失败；继续在整体等待窗口内重试，不重复提交。
            return;
        }
        String key = videoKey(run.runId(), row.getSceneId());
        switch (result.status()) {
            case QUEUED, RUNNING -> { }
            case FAILED -> {
                settleUsage(run, key, result.usageSeconds(), row.getRequestedSeconds(), row.getSceneId(), false);
                throw new VideoFailure("VIDEO_GENERATION_FAILED");
            }
            case SUCCEEDED -> {
                settleUsage(run, key, result.usageSeconds(), row.getRequestedSeconds(), row.getSceneId(), true);
                persistResult(run, row, result, cancelled);
            }
        }
    }

    private void persistResult(Run run, CreationVideo row, VideoGenerationProvider.QueryResult result,
                               BooleanSupplier cancelled) {
        byte[] bytes;
        try {
            bytes = providers.getFirst().download(result.videoUrl(), MAX_DOWNLOAD_BYTES);
        } catch (RuntimeException exception) {
            throw new VideoFailure("VIDEO_RESULT_UNAVAILABLE");
        }
        if (!mp4(bytes)) throw new VideoFailure("VIDEO_FORMAT_UNSUPPORTED");
        String bucket = storageProperties.getBucket();
        if (bucket == null || bucket.isBlank()) throw new VideoFailure("VIDEO_STORAGE_NOT_CONFIGURED");
        Path work = null, output = null;
        List<String> uploaded = new ArrayList<>();
        try {
            Path root = Path.of(renderer.getWorkRoot()).toAbsolutePath().normalize().resolve("ai-video");
            Files.createDirectories(root);
            if (!root.toRealPath().equals(root)) throw new VideoFailure("VIDEO_WORKSPACE_INVALID");
            work = Files.createTempDirectory(root, run.runId() + "-" + row.getSceneId() + "-");
            Path source = work.resolve("source.mp4");
            Files.write(source, bytes);
            output = Files.createDirectory(work.resolve("prepared"));
            var prepared = preparer.prepare(source, output, "VIDEO_CLIP", cancelled);
            // 与编译器使用同一来源和同一上限：视频流时长、800 万像素；不能一套口径放行、另一套口径拒绝。
            double materialSeconds = streamSeconds(prepared.normalizedMetadata());
            Integer width = prepared.width(), height = prepared.height();
            if (!(materialSeconds > 0) || width == null || height == null || width <= 0 || height <= 0)
                throw new VideoFailure("VIDEO_FORMAT_INVALID");
            if ((long) width * height > 8_000_000L) throw new VideoFailure("VIDEO_RESOLUTION_INVALID");
            long sceneMs = quantizedSceneMs(row.getSceneDurationMs());
            long materialMs = (long) Math.floor(materialSeconds * 1000);
            boolean tooShort = materialSeconds * 1000 + .001 < sceneMs;
            Path normalized = prepared.files().stream().filter(file -> "NORMALIZED".equals(file.role()))
                    .findFirst().orElseThrow(() -> new VideoFailure("VIDEO_FORMAT_INVALID")).path();
            if (Files.size(normalized) > settings.getMaxNormalizedBytes())
                throw new VideoFailure("VIDEO_TOO_LARGE");
            String originalSha = digest(source), normalizedSha = digest(normalized);
            String assetId = UUID.randomUUID().toString();
            String base = "users/" + row.getOwnerId() + "/tasks/" + row.getTaskId() + "/assets/" + assetId;
            String sourceKey = base + "/original/source.mp4";
            String normalizedKey = base + "/prepared/video/normalized.mp4";
            check(cancelled);
            storage.putObject(bucket, sourceKey, source, "video/mp4", STORAGE_TIMEOUT);
            uploaded.add(sourceKey);
            storage.putObject(bucket, normalizedKey, normalized, "video/mp4", STORAGE_TIMEOUT);
            uploaded.add(normalizedKey);
            if (storage.headObject(bucket, sourceKey).contentLength() != Files.size(source)
                    || storage.headObject(bucket, normalizedKey).contentLength() != Files.size(normalized))
                throw new VideoFailure("VIDEO_STORAGE_MISMATCH");
            // 不能只信 PUT 成功，下载回来按完整摘要核对，避免把损坏片段写进素材事实。
            Path verify = work.resolve("verify.mp4");
            storage.downloadObject(bucket, normalizedKey, verify, STORAGE_TIMEOUT);
            if (!normalizedSha.equals(digest(verify))) throw new VideoFailure("VIDEO_STORAGE_MISMATCH");
            ObjectNode metadata = json.createObjectNode();
            metadata.set("original", prepared.originalMetadata());
            metadata.set("normalized", prepared.normalizedMetadata());
            metadata.putObject("generated").put("kind", "AI_VIDEO").put("provider", row.getProvider())
                    .put("model", row.getModel()).put("promptSha256", row.getPromptSha256())
                    .put("prompt", row.getPromptText()).put("requestedSeconds", row.getRequestedSeconds())
                    .put("providerTaskId", row.getProviderTaskId()).put("runId", row.getRunId())
                    .put("sceneId", row.getSceneId()).put("storyboardId", row.getStoryboardId())
                    .put("width", width).put("height", height).put("durationMs", materialMs);
            ArrayNode objects = json.createArrayNode();
            objects.addObject().put("role", "NORMALIZED").put("objectKey", normalizedKey)
                    .put("contentType", "video/mp4").put("bytes", Files.size(normalized)).put("sha256", normalizedSha);
            var record = new CreationVideoPersistence.GeneratedVideo(bucket, assetId,
                    "video-" + row.getSceneId() + ".mp4", Files.size(source), originalSha, sourceKey,
                    Files.size(normalized), normalizedSha, materialMs, width, height,
                    codec.write(metadata), codec.write(objects));
            check(cancelled);
            try {
                if (tooShort) {
                    // 不循环、定格或变速；片段仍登记为真实素材，缩短场景后可免费复用，不必再次付费。
                    long proposed = frameFloor(materialMs);
                    persistence.saveReview(row, assetKey(row.getRunId(), row.getSceneId()), record,
                            "VIDEO_CLIP_TOO_SHORT", proposalJson(new Proposal(row.getSceneId(), "SHORTEN_SCENE",
                                    "生成片段实际只有 " + materialMs + " 毫秒，短于场景 " + sceneMs
                                            + " 毫秒；请缩短场景并重新确认全片时长后再次生成（该片段会直接复用），"
                                            + "或重新生成更长的片段", proposed)),
                            worker, run.leaseGeneration());
                } else {
                    persistence.saveVideo(row, assetKey(row.getRunId(), row.getSceneId()), record, worker,
                            run.leaseGeneration());
                }
            } catch (ResponseStatusException exception) {
                throw new VideoFailure(cancelled.getAsBoolean() ? "VIDEO_CANCELLED"
                        : tooShort ? "VIDEO_REVIEW_SAVE_FAILED" : "VIDEO_RESULT_SAVE_FAILED");
            }
            uploaded.clear();
        } catch (VideoFailure failure) {
            throw failure;
        } catch (AssetMediaPreparer.AssetPreparationException exception) {
            throw new VideoFailure("VIDEO_FORMAT_INVALID");
        } catch (Exception exception) {
            throw new VideoFailure("VIDEO_STORAGE_UNAVAILABLE");
        } finally {
            discardQuietly(uploaded, bucket);
            deleteQuietly(work);
            deleteQuietly(output == null ? null : output.getParent());
        }
    }

    // ---------------------------------------------------------------- 收尾

    private void finish(CreationVideoMapper.Job job, Run run, List<CreationVideo> rows) {
        var board = boards.board(job.taskId(), job.ownerId(), job.storyboardId())
                .orElseThrow(() -> new VideoFailure("VIDEO_BOARD_MISSING"));
        Candidate source = codec.candidate(codec.read(board.documentJson()).path("candidate"));
        Map<String, CreationVideo> byScene = new HashMap<>();
        rows.forEach(row -> byScene.put(row.getSceneId(), row));
        List<Scene> scenes = new ArrayList<>();
        List<Asset> extra = new ArrayList<>();
        Set<String> extraIds = new HashSet<>();
        for (Scene scene : source.scenes()) {
            CreationVideo row = byScene.get(scene.id());
            if (row == null) { scenes.add(scene); continue; }
            if (row.getVideoAssetId() == null) throw new VideoFailure("VIDEO_ASSET_MISSING");
            CreationAsset asset = asset(job, row);
            Asset frozen = persistence.asset(asset);
            extra.add(frozen);
            extraIds.add(asset.getAssetId());
            // 画面来源切换后正文不再是屏幕内容，但仍保留为提示词事实；显式写回提示词避免标题变化导致摘要漂移。
            scenes.add(new Scene(scene.id(), "AI_VIDEO", scene.title(), List.of(), scene.narration(),
                    scene.durationMs(), asset.getAssetId(), 0L, quantizedSceneMs(scene.durationMs()),
                    scene.fit(), scene.audioMode(), scene.narrationAssetId(), scene.cues(),
                    scene.sourceRefs(), row.getPromptText()));
        }
        Candidate candidate = new Candidate(List.copyOf(scenes));
        var brief = persistence.brief(job.taskId(), job.ownerId(), job.briefId());
        var frozen = new ArrayList<>(persistence.frozen(board));
        var before = compiler.check(job.storyboardId(), codec.read(brief.documentJson()), source, frozen);
        for (Asset asset : extra) if (frozen.stream().noneMatch(item -> item.assetId().equals(asset.assetId()))) frozen.add(asset);
        var check = compiler.check(job.storyboardId(), codec.read(brief.documentJson()), candidate, frozen);
        if (!com.link.linkagent.creator.creation.storyboard.service.StoryboardSelection.prepared(before, check,
                rows.stream().map(CreationVideo::getSceneId).collect(java.util.stream.Collectors.toSet()),
                Set.of("AI_VIDEO_", "VISUAL_", "CLIP_"))) {
            log.warn("AI 视频故事板仍不可渲染，运行={}，问题={}", run.runId(),
                    check.problems().stream().map(Problem::code).toList());
            throw new VideoFailure("VIDEO_BOARD_NOT_RENDERABLE");
        }
        Board saved;
        try {
            saved = persistence.saveBoard(job, candidate, List.copyOf(extra), extraIds, worker,
                    run.leaseGeneration(), "video-" + run.runId());
        } catch (ResponseStatusException exception) {
            throw new VideoFailure("VIDEO_BOARD_SAVE_FAILED");
        }
        runtime.complete(job.taskId(), job.ownerId(), run.runId(), worker, run.leaseGeneration(),
                boardResult(job, saved, rows));
    }

    private CreationAsset asset(CreationVideoMapper.Job job, CreationVideo row) {
        CreationAsset generated = mapper.assetByKey(job.taskId(), job.ownerId(),
                assetKey(row.getRunId(), row.getSceneId())).orElse(null);
        if (generated != null) return generated;
        // 复用片段来自更早的运行，素材身份仍属同一任务，读取前再次核对归属与可用性。
        try { return assets.require(job.taskId(), job.ownerId(), row.getVideoAssetId()); }
        catch (RuntimeException exception) { throw new VideoFailure("VIDEO_ASSET_MISSING"); }
    }

    private void settleRunAsFailed(Run run) { settleRunAsFailed(run, "VIDEO_PREPARATION_FAILED"); }

    private void settleRunAsFailed(Run run, String code) {
        runtime.releaseUnsent(run.taskId(), run.ownerId(), run.runId());
        runtime.fail(run.taskId(), run.ownerId(), run.runId(), worker, run.leaseGeneration(), code);
    }

    private void markFailed(CreationVideo row, String code, long generation) {
        try { persistence.fail(row, code, worker, generation); }
        catch (RuntimeException exception) { log.info("视频失败结果等待租约恢复，场景={}", row.getSceneId()); }
    }

    private void acknowledge(Run run) {
        try { runtime.acknowledgeCancellation(run.taskId(), run.ownerId(), run.runId(), worker, run.leaseGeneration()); }
        catch (RuntimeException exception) { log.info("视频取消等待原运行收尾，运行={}", run.runId()); }
    }

    private String boardResult(CreationVideoMapper.Job job, Board board, List<CreationVideo> rows) {
        ObjectNode result = json.createObjectNode();
        result.put("storyboardId", board.storyboardId()).put("sourceStoryboardId", job.storyboardId());
        result.put("sceneCount", rows.size()).put("generatedCount",
                rows.stream().filter(row -> row.getProviderTaskId() != null).count())
                .put("reusedCount", rows.stream().filter(row -> row.getProviderTaskId() == null).count());
        rows.forEach(row -> result.withArray("scenes").addObject().put("sceneId", row.getSceneId())
                .put("videoAssetId", row.getVideoAssetId()).put("durationMs", row.getVideoDurationMs())
                .put("requestedSeconds", row.getRequestedSeconds()));
        return codec.write(result);
    }

    private String reviewResult(CreationVideoMapper.Job job, List<CreationVideo> rows) {
        ObjectNode result = json.createObjectNode();
        result.put("sourceStoryboardId", job.storyboardId());
        ArrayNode proposals = result.putArray("proposals");
        for (CreationVideo row : rows) {
            if (row.getProposalJson() == null) continue;
            try { json.readTree(row.getProposalJson()).forEach(proposals::add); }
            catch (com.fasterxml.jackson.core.JsonProcessingException exception) { log.info("视频提案无法读取，场景={}", row.getSceneId()); }
        }
        rows.forEach(row -> result.withArray("scenes").addObject().put("sceneId", row.getSceneId())
                .put("status", row.getStatus()).put("videoAssetId", row.getVideoAssetId())
                .put("durationMs", row.getVideoDurationMs()));
        return codec.write(result);
    }

    private String proposalJson(Proposal proposal) {
        return codec.write(List.of(proposal));
    }

    // ---------------------------------------------------------------- 费用

    private Charge reserve(Run run, String key, BigDecimal amount, String snapshot) {
        try {
            return runtime.reserve(run.taskId(), run.ownerId(), run.runId(), worker, run.leaseGeneration(),
                    key, "VIDEO_GENERATION", amount, snapshot);
        } catch (ResponseStatusException exception) {
            throw new VideoFailure("VIDEO_BUDGET_BLOCKED");
        }
    }

    /** 只有第一次发送返回 true；已发送的调用不能重发。 */
    private boolean send(Run run, Charge charge, String key) {
        if (charge.sentAt() != null) return false;
        try { return runtime.markSent(run.taskId(), run.ownerId(), key, worker, run.leaseGeneration()); }
        catch (ResponseStatusException exception) { return false; }
    }

    /**
     * 领取执行时会把未发送的预留释放为 RELEASED。只有确认从未发送才重新占用，
     * 已发送但没有结果的调用保持 UNKNOWN，绝不重发。
     */
    private Charge beginCharge(Run run, String key, BigDecimal amount, String snapshot) {
        var existing = runtime.findCharge(run.taskId(), run.ownerId(), run.runId(), key);
        if (existing.isPresent() && existing.get().sentAt() != null) {
            markUnknown(run, key);
            throw new VideoFailure("VIDEO_RESULT_UNKNOWN");
        }
        if (existing.isPresent() && "RELEASED".equals(existing.get().status())) {
            try { runtime.rearm(run.taskId(), run.ownerId(), run.runId(), worker, run.leaseGeneration(), key); }
            catch (ResponseStatusException exception) { throw new VideoFailure("VIDEO_BUDGET_BLOCKED"); }
        }
        return reserve(run, key, amount, snapshot);
    }

    private void settle(Run run, String key, BigDecimal amount, String evidence) {
        runtime.settle(run.taskId(), run.ownerId(), key, amount, "USAGE_ESTIMATE", evidence);
    }

    /**
     * 供应商回报用量优先。成功但缺少用量时按实际请求秒数估算，不冒充真实账单；
     * 失败且没有用量证据时保留 UNKNOWN，不能按请求时长臆造已发生费用。
     */
    private void settleUsage(Run run, String key, Long usageSeconds, int requestedSeconds, String sceneId,
                             boolean succeeded) {
        Long seconds = usageSeconds;
        if (seconds == null && succeeded && requestedSeconds > 0) seconds = (long) requestedSeconds;
        if (seconds == null) { markUnknown(run, key); return; }
        settle(run, key, amountFromCharge(run, key, BigDecimal.valueOf(Math.max(0, seconds))),
                "video-" + sceneId);
    }

    /** 结算沿用预留时的价格快照，重启或改配置都不能改变同一物理调用的金额依据。 */
    private BigDecimal amountFromCharge(Run run, String key, BigDecimal seconds) {
        var charge = runtime.findCharge(run.taskId(), run.ownerId(), run.runId(), key)
                .orElseThrow(() -> new VideoFailure("VIDEO_CHARGE_MISSING"));
        try {
            var price = json.readTree(charge.priceSnapshotJson());
            BigDecimal unit = new BigDecimal(price.path("usdPerSecond").asText());
            BigDecimal rate = new BigDecimal(price.path("usdToCny").asText());
            return cny(unit.multiply(seconds), rate);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new VideoFailure("VIDEO_PRICE_SNAPSHOT_INVALID");
        }
    }

    private BigDecimal cny(BigDecimal usd) { return cny(usd, billing.getUsdToCny()); }

    private BigDecimal cny(BigDecimal usd, BigDecimal rate) {
        BigDecimal value = usd.multiply(rate).setScale(6, RoundingMode.HALF_UP);
        return value.signum() == 0 ? new BigDecimal("0.000001") : value;
    }

    private String priceSnapshot(BigDecimal usd, BigDecimal rate, Map<String, Object> extra) {
        ObjectNode node = json.createObjectNode();
        node.put("currency", "USD").put("usdUpperBound", usd.toPlainString())
                .put("usdToCny", rate.toPlainString())
                .put("capability", "VIDEO_GENERATION").put("basis", "CONFIGURED_ESTIMATE")
                .put("accounting", "USAGE_ESTIMATE");
        extra.forEach((name, value) -> node.put(name, String.valueOf(value)));
        return node.toString();
    }

    private void markUnknown(Run run, String key) {
        try { runtime.markUnknown(run.taskId(), run.ownerId(), key); }
        catch (RuntimeException exception) { log.info("视频未知费用等待后续核对，运行={}", run.runId()); }
    }

    // ---------------------------------------------------------------- 杂项

    private CreationVideo reload(CreationVideo row) {
        return mapper.scene(row.getRunId(), row.getSceneId()).orElse(row);
    }

    private static double streamSeconds(JsonNode metadata) {
        if (metadata == null) return 0;
        for (JsonNode stream : metadata.path("streams"))
            if ("video".equals(stream.path("codec_type").asText())
                    && stream.path("disposition").path("attached_pic").asInt() != 1) {
                try {
                    double value = Double.parseDouble(stream.path("duration").asText());
                    return Double.isFinite(value) && value > 0 ? value : 0;
                } catch (RuntimeException exception) { return 0; }
            }
        return 0;
    }

    /** 提案时长向下量化到帧，保证不超过片段实际尾部。 */
    private static long frameFloor(long millis) {
        long frames = (long) Math.floor(millis * 30.0 / 1000);
        return Math.max(1, Math.round(frames * 1000.0 / 30));
    }

    private static boolean mp4(byte[] bytes) {
        return bytes != null && bytes.length > 12 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p';
    }

    private static long seed(String sha) {
        if (sha == null || sha.length() < 8) return 7;
        return Math.floorMod(Long.parseLong(sha.substring(0, 8), 16), 1_000_000);
    }

    private static long quantizedSceneMs(long millis) {
        return Math.round(Math.round(millis * 30.0 / 1000) * 1000.0 / 30);
    }

    private static String digest(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void sleep(Duration duration) {
        try { Thread.sleep(duration.toMillis()); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new VideoFailure("VIDEO_INTERRUPTED"); }
    }

    private static void check(BooleanSupplier cancelled) { if (cancelled.getAsBoolean()) throw new VideoFailure("VIDEO_CANCELLED"); }

    private void discardQuietly(List<String> keys, String bucket) {
        for (String key : keys) try { storage.deleteObject(bucket, key); }
        catch (RuntimeException exception) { log.warn("视频对象等待后续清理，对象={}", key); }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        } catch (Exception ignored) { }
    }

    private static String videoKey(String runId, String sceneId) { return "video-gen-" + runId + "-" + sceneId; }
    private static String assetKey(String runId, String sceneId) { return "video-" + runId + "-" + sceneId; }

    private static final class VideoFailure extends RuntimeException {
        private final String code;
        VideoFailure(String code) { super(code); this.code = code; }
    }

    @Override @PreDestroy
    public void close() {
        stopping.set(true);
        executor.shutdownNow(); pulses.shutdownNow();
        try { executor.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
