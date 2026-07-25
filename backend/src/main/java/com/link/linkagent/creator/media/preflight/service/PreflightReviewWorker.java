package com.link.linkagent.creator.media.preflight.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightStepRecord;
import com.link.linkagent.creator.media.preflight.provider.SpeechRecognitionProvider;
import com.link.linkagent.creator.media.processing.mapper.MediaProcessingMapper;
import com.link.linkagent.creator.media.processing.model.MediaProcessingAssetRecord;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P0-3/P0-4a 持久化试映 Worker。
 * 每次只推进一个可持久化边界，等待异步 Provider 时主动释放租约，页面关闭不会影响任务继续执行。
 */
@Component
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class PreflightReviewWorker {

    private static final Logger log = LoggerFactory.getLogger(PreflightReviewWorker.class);
    private static final List<Duration> RETRY_DELAYS = List.of(
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(120));

    private final CreatorMediaProperties properties;
    private final PreflightReviewMapper mapper;
    private final MediaProcessingMapper processingMapper;
    private final SpeechRecognitionProvider asrProvider;
    private final PreflightAsrService asrService;
    private final PreflightTimelineService timelineService;
    private final PreflightVideoAnalysisService videoAnalysisService;
    private final PreflightReviewService reviewService;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "preflight-review-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService leaseExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "preflight-review-lease");
        thread.setDaemon(true);
        return thread;
    });

    public PreflightReviewWorker(CreatorMediaProperties properties,
                                 PreflightReviewMapper mapper,
                                 MediaProcessingMapper processingMapper,
                                 SpeechRecognitionProvider asrProvider,
                                 PreflightAsrService asrService,
                                 PreflightTimelineService timelineService,
                                 PreflightVideoAnalysisService videoAnalysisService,
                                 PreflightReviewService reviewService,
                                 ObjectMapper objectMapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.processingMapper = processingMapper;
        this.asrProvider = asrProvider;
        this.asrService = asrService;
        this.timelineService = timelineService;
        this.videoAnalysisService = videoAnalysisService;
        this.reviewService = reviewService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${creator.media.preflight.poll-interval-ms:2000}")
    public void poll() {
        try {
            mapper.requeueExpiredReviews();
            mapper.cancelExpiredRequestedReviews();
            mapper.findNextRunnableReview().ifPresent(this::submit);
        } catch (RuntimeException exception) {
            log.warn("发布前试映任务轮询失败", exception);
        }
    }

    private void submit(PreflightReviewRecord candidate) {
        if (!busy.compareAndSet(false, true)) return;
        String leaseOwner = UUID.randomUUID().toString();
        LocalDateTime leaseUntil = LocalDateTime.now().plus(properties.getPreflight().getLeaseDuration());
        if (mapper.claimReview(candidate.reviewId(), leaseOwner, leaseUntil) != 1) {
            busy.set(false);
            return;
        }
        executor.submit(() -> execute(candidate.reviewId(), leaseOwner));
    }

    private void execute(String reviewId, String leaseOwner) {
        long heartbeatSeconds = Math.max(1L, properties.getPreflight().getLeaseDuration().toSeconds() / 3L);
        var heartbeat = leaseExecutor.scheduleAtFixedRate(
                () -> renewLease(reviewId, leaseOwner),
                heartbeatSeconds,
                heartbeatSeconds,
                TimeUnit.SECONDS
        );
        try {
            PreflightReviewRecord review = mapper.findReviewForWorker(reviewId, leaseOwner).orElse(null);
            if (review == null) return;
            reviewService.publish(reviewId, "review_status");
            if (Boolean.TRUE.equals(review.cancelRequested()) || "CANCEL_REQUESTED".equals(review.status())) {
                mapper.finishCancellation(reviewId, leaseOwner);
                reviewService.publish(reviewId, "review_cancelled");
                return;
            }
            PreflightStepRecord transcribe = requireStep(reviewId, "TRANSCRIBE");
            if (!"SUCCEEDED".equals(transcribe.status()) && !"SKIPPED".equals(transcribe.status())) {
                processTranscription(review, transcribe, leaseOwner);
                return;
            }
            processTimeline(review, leaseOwner);
        } catch (PreflightAsrService.AmbiguousSubmissionException exception) {
            failPermanently(reviewId, leaseOwner, "ASR_SUBMISSION_AMBIGUOUS", exception.getMessage());
        } catch (PermanentFailure exception) {
            failPermanently(reviewId, leaseOwner, exception.code, exception.getMessage());
        } catch (Exception exception) {
            handleRetryableFailure(reviewId, leaseOwner, exception);
        } finally {
            heartbeat.cancel(true);
            busy.set(false);
        }
    }

    private void processTranscription(PreflightReviewRecord review,
                                      PreflightStepRecord step,
                                      String leaseOwner) {
        String providerTaskId = step.providerTaskId();
        if (providerTaskId == null || providerTaskId.isBlank()) {
            MediaProcessingAssetRecord audio = processingMapper.listAssets(review.processingJobId()).stream()
                    .filter(asset -> "AUDIO".equals(asset.assetType()))
                    .findFirst()
                    .orElseThrow(() -> new PermanentFailure("ASR_AUDIO_MISSING", "媒体预处理音轨不存在"));
            providerTaskId = asrService.ensureSubmitted(review, step, audio);
            step = requireStep(review.reviewId(), "TRANSCRIBE");
            reviewService.publish(review.reviewId(), "step_started");
        }
        SpeechRecognitionProvider.QueryResult query = asrProvider.query(providerTaskId);
        if (query.status() == SpeechRecognitionProvider.Status.PENDING
                || query.status() == SpeechRecognitionProvider.Status.RUNNING) {
            waitForProvider(review, leaseOwner);
            reviewService.publish(review.reviewId(), "step_progress");
            return;
        }
        if (query.status() == SpeechRecognitionProvider.Status.FAILED) {
            mapper.finishStep(review.reviewId(), "TRANSCRIBE", "FAILED", null,
                    "ASR_PROVIDER_FAILED", truncate(query.errorMessage()));
            mapper.failAsrCall(review.reviewId(), providerTaskId,
                    "ASR_PROVIDER_FAILED", truncate(query.errorMessage()));
            throw new PermanentFailure("ASR_PROVIDER_FAILED", "ASR 转写失败");
        }
        SpeechRecognitionProvider.TranscriptionResult result = asrProvider.loadResult(query.transcriptionUrl());
        BigDecimal actualCost = asrCost(query.usageSeconds());
        asrService.replaceTranscript(review, step, result, query.usageSeconds(), actualCost);
        if (mapper.advanceReview(review.reviewId(), leaseOwner, "BUILD_TIMELINE", 65,
                query.usageSeconds(), actualCost) != 1) {
            throw new IllegalStateException("试映任务状态已变化");
        }
        reviewService.publish(review.reviewId(), "step_completed");
        processTimeline(mapper.findReviewForWorker(review.reviewId(), leaseOwner)
                .orElseThrow(() -> new IllegalStateException("试映任务租约已失效")), leaseOwner);
    }

    private void processTimeline(PreflightReviewRecord review, String leaseOwner) {
        PreflightReviewRecord current = mapper.findReviewForWorker(review.reviewId(), leaseOwner)
                .orElseThrow(() -> new IllegalStateException("试映任务租约已失效"));
        if (Boolean.TRUE.equals(current.cancelRequested()) || "CANCEL_REQUESTED".equals(current.status())) {
            mapper.finishCancellation(review.reviewId(), leaseOwner);
            reviewService.publish(review.reviewId(), "review_cancelled");
            return;
        }
        PreflightStepRecord step = requireStep(review.reviewId(), "BUILD_TIMELINE");
        int timelineEvidenceCount;
        if ("SUCCEEDED".equals(step.status())) {
            timelineEvidenceCount = mapper.listEvidence(review.reviewId()).size();
        } else {
            if ("PENDING".equals(step.status()) && mapper.startStep(review.reviewId(), "BUILD_TIMELINE") != 1) {
                throw new IllegalStateException("时间轴步骤状态已变化");
            }
            reviewService.publish(review.reviewId(), "step_started");
            step = requireStep(review.reviewId(), "BUILD_TIMELINE");
            timelineEvidenceCount = timelineService.rebuild(review, step);
            String outputRef = json(Map.of("evidenceCount", timelineEvidenceCount));
            if (mapper.finishStep(review.reviewId(), "BUILD_TIMELINE", "SUCCEEDED",
                    outputRef, null, null) != 1) {
                throw new IllegalStateException("时间轴步骤完成状态保存失败");
            }
            reviewService.publish(review.reviewId(), "step_completed");
        }
        current = mapper.findReviewForWorker(review.reviewId(), leaseOwner)
                .orElseThrow(() -> new IllegalStateException("试映任务租约已失效"));
        if (Boolean.TRUE.equals(current.cancelRequested()) || "CANCEL_REQUESTED".equals(current.status())) {
            mapper.finishCancellation(review.reviewId(), leaseOwner);
            reviewService.publish(review.reviewId(), "review_cancelled");
            return;
        }
        if (mapper.advanceReview(review.reviewId(), leaseOwner, "ANALYZE_VIDEO", 75,
                current.usageSeconds(), current.actualCostUsd()) != 1) {
            throw new IllegalStateException("试映任务状态已变化");
        }
        reviewService.publish(review.reviewId(), "step_completed");
        processVideoAnalysis(mapper.findReviewForWorker(review.reviewId(), leaseOwner)
                .orElseThrow(() -> new IllegalStateException("试映任务租约已失效")), leaseOwner);
    }

    private void processVideoAnalysis(PreflightReviewRecord review, String leaseOwner) {
        PreflightStepRecord step = requireStep(review.reviewId(), "ANALYZE_VIDEO");
        PreflightVideoAnalysisService.Result result;
        if ("SUCCEEDED".equals(step.status())) {
            result = restoreVideoResult(step);
        } else {
            if ("PENDING".equals(step.status()) && mapper.startStep(review.reviewId(), "ANALYZE_VIDEO") != 1) {
                throw new IllegalStateException("视频理解步骤状态已变化");
            }
            reviewService.publish(review.reviewId(), "step_started");
            step = requireStep(review.reviewId(), "ANALYZE_VIDEO");
            result = videoAnalysisService.analyze(review, step);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("executiveSummary", result.executiveSummary());
            output.put("issueCount", result.issueCount());
            output.put("actualCostUsd", result.actualCostUsd());
            if (mapper.finishStep(review.reviewId(), "ANALYZE_VIDEO", "SUCCEEDED",
                    json(output), null, null) != 1) {
                throw new IllegalStateException("视频理解步骤完成状态保存失败");
            }
        }
        BigDecimal totalActualCost = addCost(review.actualCostUsd(), result.actualCostUsd());
        if (mapper.completeReview(review.reviewId(), leaseOwner,
                result.executiveSummary(), totalActualCost) != 1) {
            throw new IllegalStateException("试映任务状态已变化");
        }
        reviewService.publish(review.reviewId(), "review_completed");
    }

    private PreflightVideoAnalysisService.Result restoreVideoResult(PreflightStepRecord step) {
        try {
            var root = objectMapper.readTree(step.outputRef());
            String summary = root.path("executiveSummary").asText();
            if (summary.isBlank()) throw new IllegalStateException("视频理解摘要不存在");
            BigDecimal cost = root.path("actualCostUsd").isNumber()
                    ? root.path("actualCostUsd").decimalValue() : null;
            return new PreflightVideoAnalysisService.Result(
                    summary, root.path("issueCount").asInt(), cost
            );
        } catch (Exception exception) {
            throw new PermanentFailure("VIDEO_RESULT_INCOMPLETE", "视频理解结果恢复失败");
        }
    }

    private void waitForProvider(PreflightReviewRecord review, String leaseOwner) {
        if (mapper.waitForRetry(
                review.reviewId(), leaseOwner,
                LocalDateTime.now().plus(properties.getPreflight().getProviderPollInterval()),
                0, null, null) != 1) {
            throw new IllegalStateException("试映任务状态已变化");
        }
    }

    private void handleRetryableFailure(String reviewId, String leaseOwner, Exception exception) {
        PreflightReviewRecord review = mapper.findReviewForWorker(reviewId, leaseOwner).orElse(null);
        if (review == null) return;
        if (Boolean.TRUE.equals(review.cancelRequested()) || "CANCEL_REQUESTED".equals(review.status())) {
            mapper.finishCancellation(reviewId, leaseOwner);
            reviewService.publish(reviewId, "review_cancelled");
            return;
        }
        int nextAttempt = review.attemptCount() + 1;
        if (nextAttempt >= review.maxAttempts()) {
            failPermanently(reviewId, leaseOwner, "PREFLIGHT_RETRY_EXHAUSTED",
                    "试映任务多次执行失败，请手动重试");
            log.warn("发布前试映任务自动重试已耗尽 reviewId={}", reviewId, exception);
            return;
        }
        Duration delay = RETRY_DELAYS.get(Math.min(nextAttempt - 1, RETRY_DELAYS.size() - 1));
        mapper.waitForRetry(reviewId, leaseOwner, LocalDateTime.now().plus(delay), 1,
                "PREFLIGHT_TRANSIENT_FAILURE", "外部服务暂时不可用，任务将自动重试");
        reviewService.publish(reviewId, "step_failed");
        log.warn("发布前试映任务执行失败，等待自动重试 reviewId={}", reviewId, exception);
    }

    private void failPermanently(String reviewId, String leaseOwner, String code, String message) {
        mapper.findReviewForWorker(reviewId, leaseOwner).ifPresent(review -> {
            if (!"DONE".equals(review.currentStep())) {
                mapper.finishStep(reviewId, review.currentStep(), "FAILED", null, code, truncate(message));
            }
        });
        mapper.failReview(reviewId, leaseOwner, code, truncate(message));
        reviewService.publish(reviewId, "step_failed");
    }

    private PreflightStepRecord requireStep(String reviewId, String stepType) {
        return mapper.findStep(reviewId, stepType)
                .orElseThrow(() -> new IllegalStateException("试映步骤不存在"));
    }

    private BigDecimal asrCost(Long usageSeconds) {
        if (usageSeconds == null) return null;
        return properties.getProcessing().getAsrUsdPerSecond()
                .multiply(BigDecimal.valueOf(usageSeconds))
                .setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal addCost(BigDecimal first, BigDecimal second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.add(second).setScale(8, RoundingMode.HALF_UP);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("试映步骤摘要序列化失败", exception);
        }
    }

    private String truncate(String message) {
        String safe = message == null || message.isBlank() ? "发布前试映失败" : message;
        return safe.substring(0, Math.min(500, safe.length()));
    }

    private void renewLease(String reviewId, String leaseOwner) {
        try {
            mapper.renewLease(reviewId, leaseOwner,
                    LocalDateTime.now().plus(properties.getPreflight().getLeaseDuration()));
        } catch (RuntimeException exception) {
            log.warn("发布前试映任务续租失败 reviewId={}", reviewId, exception);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        leaseExecutor.shutdownNow();
    }

    private static class PermanentFailure extends RuntimeException {
        private final String code;

        private PermanentFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
