package com.link.linkagent.creator.media.processing.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.processing.mapper.MediaProcessingMapper;
import com.link.linkagent.creator.media.processing.model.MediaProcessingAssetRecord;
import com.link.linkagent.creator.media.processing.model.MediaProcessingJobRecord;
import com.link.linkagent.creator.media.processing.model.MediaProcessingOptionsRequest;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P0-2 单机媒体处理 Worker。
 * 任务事实保存在数据库，调度线程只领取任务，单独的执行线程负责 FFmpeg，避免长视频阻塞调度器。
 */
@Component
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class MediaProcessingWorker {

    private static final Logger log = LoggerFactory.getLogger(MediaProcessingWorker.class);
    private static final int MAX_FRAME_COUNT = 360;
    private static final Pattern BLACK_START = Pattern.compile("black_start:([0-9.]+)");
    private static final Pattern BLACK_END = Pattern.compile("black_end:([0-9.]+)");
    private static final Pattern BLACK_DURATION = Pattern.compile("black_duration:([0-9.]+)");
    private static final Pattern SILENCE_START = Pattern.compile("silence_start: ([0-9.]+)");
    private static final Pattern SILENCE_END = Pattern.compile("silence_end: ([0-9.]+)");
    private static final Pattern SILENCE_DURATION = Pattern.compile("silence_duration: ([0-9.]+)");
    private static final Pattern FREEZE_START = Pattern.compile("freeze_start:([0-9.]+)");
    private static final Pattern FREEZE_END = Pattern.compile("freeze_end:([0-9.]+)");
    private static final Pattern FREEZE_DURATION = Pattern.compile("freeze_duration:([0-9.]+)");
    private static final Pattern MEAN_VOLUME = Pattern.compile("mean_volume: ([-+0-9.]+) dB");
    private static final Pattern MAX_VOLUME = Pattern.compile("max_volume: ([-+0-9.]+) dB");

    private final CreatorMediaProperties properties;
    private final MediaProcessingMapper mapper;
    private final MediaUploadMapper uploadMapper;
    private final ObjectStorageService storage;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "media-processing-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService leaseExecutor = Executors.newScheduledThreadPool(1, r -> {
        Thread thread = new Thread(r, "media-processing-lease");
        thread.setDaemon(true);
        return thread;
    });

    public MediaProcessingWorker(CreatorMediaProperties properties,
                                 MediaProcessingMapper mapper,
                                 MediaUploadMapper uploadMapper,
                                 ObjectStorageService storage,
                                 ObjectMapper objectMapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.uploadMapper = uploadMapper;
        this.storage = storage;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${creator.media.processing.poll-interval-ms:2000}")
    public void poll() {
        try {
            mapper.requeueExpiredJobs(properties.getProcessing().getMaxAttempts());
            mapper.failExhaustedExpiredJobs(properties.getProcessing().getMaxAttempts());
            mapper.findNextQueuedJob().ifPresent(this::submit);
        } catch (RuntimeException exception) {
            log.warn("媒体预处理任务轮询失败", exception);
        }
    }

    private void submit(MediaProcessingJobRecord candidate) {
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        String leaseOwner = UUID.randomUUID().toString();
        LocalDateTime leaseUntil = LocalDateTime.now().plus(properties.getProcessing().getLeaseDuration());
        if (mapper.claimJob(candidate.jobId(), leaseOwner, leaseUntil, properties.getProcessing().getMaxAttempts()) != 1) {
            busy.set(false);
            return;
        }
        executor.submit(() -> execute(candidate.jobId(), leaseOwner));
    }

    private void execute(String jobId, String leaseOwner) {
        MediaProcessingJobRecord job;
        try {
            job = mapper.findJobForWorker(jobId, leaseOwner).orElse(null);
        } catch (RuntimeException exception) {
            busy.set(false);
            log.warn("媒体预处理任务回读失败 jobId={}", jobId, exception);
            return;
        }
        if (job == null) {
            busy.set(false);
            return;
        }
        Path workDir = null;
        long heartbeatSeconds = Math.max(1L, properties.getProcessing().getLeaseDuration().toSeconds() / 3L);
        var lease = leaseExecutor.scheduleAtFixedRate(
                () -> renewLease(jobId, leaseOwner),
                heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
        try {
            DraftVideoRecord draft = uploadMapper.findDraftVideoByVersion(job.taskId(), job.ownerId(), job.versionId())
                    .orElseThrow(() -> new ProcessingFailure("原片记录不存在"));
            Path root = Path.of(properties.getProcessing().getWorkRoot()).toAbsolutePath().normalize();
            Path candidateDir = root.resolve(jobId).normalize();
            if (!candidateDir.startsWith(root)) {
                throw new ProcessingFailure("媒体处理工作目录无效");
            }
            if (Files.exists(candidateDir)) {
                // 服务崩溃可能绕过 finally，重新领取时先清理同任务残留，确保下载和 FFmpeg 输出可重建。
                deleteTree(candidateDir);
                if (Files.exists(candidateDir)) {
                    throw new ProcessingFailure("媒体处理工作目录清理失败");
                }
            }
            workDir = candidateDir;
            Path taskDir = workDir;
            Files.createDirectories(taskDir.resolve("frames"));
            mapper.resetSteps(jobId);
            mapper.deleteAssets(jobId);
            runStep(job, leaseOwner, "DOWNLOAD", 1, () -> {
                storage.downloadObject(draft.bucketName(), draft.objectKey(), taskDir.resolve("source.mp4"));
                return "原片已下载";
            });
            runStep(job, leaseOwner, "PREVIEW", 2, () -> {
                runFfmpeg(List.of("-y", "-hide_banner", "-loglevel", "error", "-i", taskDir.resolve("source.mp4").toString(),
                        "-map", "0:v:0", "-an", "-vf", scaleFilter(job), "-fpsmax", "24",
                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "28", "-pix_fmt", "yuv420p",
                        "-movflags", "+faststart", taskDir.resolve("preview.mp4").toString()));
                return "分析预览已生成";
            });
            if (Boolean.TRUE.equals(draft.hasAudio())) {
                runStep(job, leaseOwner, "AUDIO", 3, () -> {
                    runFfmpeg(List.of("-y", "-hide_banner", "-loglevel", "error", "-i", taskDir.resolve("source.mp4").toString(),
                            "-vn", "-ac", "1", "-ar", "16000", "-b:a", "64k", taskDir.resolve("audio.mp3").toString()));
                    return "音轨已提取";
                });
            } else {
                updateStep(job, leaseOwner, "AUDIO", 3, "SKIPPED", 100, "原片没有音轨", null);
            }
            runStep(job, leaseOwner, "FRAMES", 4, () -> {
                runFfmpeg(List.of("-y", "-hide_banner", "-loglevel", "error", "-i", taskDir.resolve("source.mp4").toString(),
                        "-vf", scaleFilter(job) + ",fps=1/" + job.frameIntervalSeconds(),
                        "-frames:v", String.valueOf(MAX_FRAME_COUNT), "-q:v", "3",
                        taskDir.resolve("frames/frame-%06d.jpg").toString()));
                return "关键画面已生成";
            });
            String signals = runStep(job, leaseOwner, "SIGNALS", 5,
                    () -> analyzeSignals(taskDir.resolve("source.mp4"), Boolean.TRUE.equals(draft.hasAudio())),
                    "黑屏、静音、音量和冻结检测已完成");
            runStep(job, leaseOwner, "UPLOAD", 6, () -> {
                uploadAssets(job, draft, taskDir);
                return "派生素材已保存";
            });
            if (mapper.completeJob(jobId, leaseOwner, signals) != 1) {
                throw new ProcessingFailure("任务状态已变化");
            }
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                log.info("媒体预处理任务因服务停止而中断，等待租约到期后自动恢复 jobId={}", jobId);
                return;
            }
            String message = exception instanceof ProcessingFailure
                    ? exception.getMessage() : "媒体处理失败，请检查 FFmpeg 和对象存储配置";
            mapper.failJob(jobId, leaseOwner, truncate(message));
            log.warn("媒体预处理任务失败 jobId={}", jobId, exception);
        } finally {
            lease.cancel(true);
            if (workDir != null) {
                deleteTree(workDir);
            }
            busy.set(false);
        }
    }

    private String runStep(MediaProcessingJobRecord job, String owner, String code, int sequence,
                           StepAction action) throws Exception {
        return runStep(job, owner, code, sequence, action, null);
    }

    private String runStep(MediaProcessingJobRecord job, String owner, String code, int sequence,
                           StepAction action, String completionSummary) throws Exception {
        updateStep(job, owner, code, sequence, "RUNNING", 0, null, null);
        try {
            String output = action.run();
            updateStep(job, owner, code, sequence, "COMPLETED", 100,
                    completionSummary == null ? output : completionSummary, null);
            return output;
        } catch (Exception exception) {
            String message = exception instanceof ProcessingFailure ? exception.getMessage() : "当前处理步骤失败";
            updateStep(job, owner, code, sequence, "FAILED", 0, null, truncate(message));
            throw exception;
        }
    }

    private void updateStep(MediaProcessingJobRecord job, String owner, String code, int sequence, String status,
                            int stepProgress, String output, String failure) {
        mapper.updateStep(job.jobId(), code, status, stepProgress, output, failure);
        int progress = status.equals("COMPLETED") || status.equals("SKIPPED")
                ? sequence * 100 / 6 : (sequence - 1) * 100 / 6 + stepProgress / 6;
        mapper.updateJobProgress(job.jobId(), owner, code, Math.min(99, progress),
                LocalDateTime.now().plus(properties.getProcessing().getLeaseDuration()));
    }

    private String scaleFilter(MediaProcessingJobRecord job) {
        int targetWidth = MediaProcessingOptionsRequest.Resolution
                .valueOf(job.targetResolution())
                .getWidth();
        return "scale=w=min(iw\\," + targetWidth + "):h=min(ih\\," + job.targetHeight()
                + "):force_original_aspect_ratio=decrease:force_divisible_by=2";
    }

    private void uploadAssets(MediaProcessingJobRecord job, DraftVideoRecord draft, Path workDir) {
        String prefix = "users/" + draft.ownerId() + "/tasks/" + draft.taskId() + "/versions/"
                + draft.versionId() + "/derived/" + job.jobId();
        List<MediaProcessingAssetRecord> assets = new ArrayList<>();
        Path preview = workDir.resolve("preview.mp4");
        String previewKey = prefix + "/preview.mp4";
        storage.putObject(draft.bucketName(), previewKey, preview, "video/mp4");
        int targetWidth = MediaProcessingOptionsRequest.Resolution
                .valueOf(job.targetResolution())
                .getWidth();
        double scale = Math.min(1D, Math.min(
                (double) targetWidth / draft.width(),
                (double) job.targetHeight() / draft.height()
        ));
        int previewWidth = Math.max(2, ((int) Math.round(draft.width() * scale)) & ~1);
        int previewHeight = Math.max(2, ((int) Math.round(draft.height() * scale)) & ~1);
        assets.add(asset(job, draft, "PREVIEW_VIDEO", previewKey, "video/mp4", fileSize(preview), null, null,
                previewWidth, previewHeight, draft.durationMs()));
        Path audio = workDir.resolve("audio.mp3");
        if (Files.exists(audio)) {
            String audioKey = prefix + "/audio.mp3";
            storage.putObject(draft.bucketName(), audioKey, audio, "audio/mpeg");
            assets.add(asset(job, draft, "AUDIO", audioKey, "audio/mpeg", fileSize(audio), null, null,
                    null, null, draft.durationMs()));
        }
        try (var stream = Files.list(workDir.resolve("frames"))) {
            List<Path> frames = stream.filter(path -> path.getFileName().toString().endsWith(".jpg"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
            for (int i = 0; i < frames.size() && i < MAX_FRAME_COUNT; i++) {
                Path frame = frames.get(i);
                String key = prefix + "/frames/frame-" + String.format("%06d", i + 1) + ".jpg";
                storage.putObject(draft.bucketName(), key, frame, "image/jpeg");
                long timestamp = Math.min(draft.durationMs() == null ? 0 : Math.max(0, draft.durationMs() - 1),
                        (long) i * job.frameIntervalSeconds() * 1000L);
                assets.add(asset(job, draft, "KEYFRAME", key, "image/jpeg", fileSize(frame), i + 1,
                        timestamp, previewWidth, previewHeight, null));
            }
        } catch (IOException exception) {
            throw new ProcessingFailure("关键画面读取失败", exception);
        }
        assets.forEach(mapper::insertAsset);
    }

    private MediaProcessingAssetRecord asset(MediaProcessingJobRecord job, DraftVideoRecord draft, String type,
                                             String key, String contentType, long size, Integer sequence, Long timestamp,
                                             Integer width, Integer height, Long duration) {
        return new MediaProcessingAssetRecord(null, UUID.randomUUID().toString(), job.jobId(), draft.versionId(), type,
                draft.bucketName(), key, contentType, size, sequence, timestamp, width, height, duration, null, null);
    }

    private String analyzeSignals(Path source, boolean hasAudio) throws Exception {
        List<String> arguments = new ArrayList<>(List.of("-hide_banner", "-nostats", "-i", source.toString(),
                "-vf", "blackdetect=d=1:pix_th=0.10,freezedetect=n=-60dB:d=2"));
        if (hasAudio) {
            arguments.addAll(List.of("-af", "silencedetect=n=-35dB:d=1,volumedetect"));
        }
        arguments.addAll(List.of("-f", "null", "-"));
        String output = runFfmpeg(arguments);
        return parseSignalSummary(output, hasAudio);
    }

    String parseSignalSummary(String output, boolean hasAudio) {
        ObjectNode root = objectMapper.createObjectNode();
        addMatches(root, "black", output, BLACK_START, BLACK_END, BLACK_DURATION);
        addMatches(root, "freeze", output, FREEZE_START, FREEZE_END, FREEZE_DURATION);
        root.putArray("silence");
        if (hasAudio) {
            root.remove("silence");
            addMatches(root, "silence", output, SILENCE_START, SILENCE_END, SILENCE_DURATION);
            putNumberOrNull(root, "meanVolumeDb", firstNumber(output, MEAN_VOLUME));
            putNumberOrNull(root, "maxVolumeDb", firstNumber(output, MAX_VOLUME));
        } else {
            root.putNull("meanVolumeDb");
            root.putNull("maxVolumeDb");
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new ProcessingFailure("信号摘要生成失败", exception);
        }
    }

    private void addMatches(ObjectNode root, String name, String output, Pattern start, Pattern end, Pattern duration) {
        ArrayNode values = root.putArray(name);
        Matcher starts = start.matcher(output);
        Matcher ends = end.matcher(output);
        Matcher durations = duration.matcher(output);
        while (starts.find()) {
            ObjectNode value = values.addObject();
            value.put("startSeconds", Double.parseDouble(starts.group(1)));
            if (ends.find()) value.put("endSeconds", Double.parseDouble(ends.group(1)));
            if (durations.find()) value.put("durationSeconds", Double.parseDouble(durations.group(1)));
        }
    }

    private Double firstNumber(String output, Pattern pattern) {
        Matcher matcher = pattern.matcher(output);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : null;
    }

    private void putNumberOrNull(ObjectNode root, String field, Double value) {
        if (value == null) root.putNull(field); else root.put(field, value);
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new ProcessingFailure("派生素材读取失败", exception);
        }
    }

    private String runFfmpeg(List<String> arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(properties.getProcessing().getFfmpegPath());
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> copy(process.getInputStream(), output), "ffmpeg-output-reader");
        reader.setDaemon(true);
        reader.start();
        boolean finished;
        try {
            finished = process.waitFor(
                    properties.getProcessing().getFfmpegTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            throw exception;
        }
        if (!finished) {
            process.destroyForcibly();
            reader.join(2000);
            throw new ProcessingFailure("FFmpeg 处理超时");
        }
        reader.join(2000);
        String outputText = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new ProcessingFailure("FFmpeg 处理失败");
        }
        return outputText;
    }

    private void copy(InputStream input, ByteArrayOutputStream output) {
        try (input) {
            input.transferTo(output);
        } catch (IOException ignored) {
            // 进程被超时终止时读取流会关闭，失败原因由主线程统一收敛。
        }
    }

    private void deleteTree(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
            log.warn("媒体处理临时目录清理失败");
        }
    }

    private String truncate(String message) {
        return message == null ? "媒体处理失败" : message.substring(0, Math.min(500, message.length()));
    }

    private void renewLease(String jobId, String leaseOwner) {
        try {
            mapper.renewLease(jobId, leaseOwner,
                    LocalDateTime.now().plus(properties.getProcessing().getLeaseDuration()));
        } catch (RuntimeException exception) {
            log.warn("媒体预处理任务续租失败 jobId={}", jobId, exception);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        leaseExecutor.shutdownNow();
    }

    @FunctionalInterface
    private interface StepAction { String run() throws Exception; }

    private static class ProcessingFailure extends RuntimeException {
        private ProcessingFailure(String message) { super(message); }
        private ProcessingFailure(String message, Throwable cause) { super(message, cause); }
    }
}
