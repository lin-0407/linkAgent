package com.link.linkagent.creator.media.probe.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.probe.model.MediaProbeResult;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 通过 ffprobe 读取视频元信息。
 * <p>
 * ffprobe 作为外部进程执行，不能拼接 shell 字符串，避免带签名 URL 中的特殊字符被 shell 解释。
 */
@Service
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class FfprobeMediaProbeService {

    private static final int MAX_PROBE_OUTPUT_BYTES = 1024 * 1024;
    private static final int MAX_PROBE_ERROR_BYTES = 64 * 1024;
    private static final int MAX_CONCURRENT_PROBES = 2;

    private final CreatorMediaProperties mediaProperties;
    private final ObjectMapper objectMapper;
    private final Semaphore probeSlots = new Semaphore(MAX_CONCURRENT_PROBES);
    private final ExecutorService outputReaderExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public FfprobeMediaProbeService(CreatorMediaProperties mediaProperties, ObjectMapper objectMapper) {
        this.mediaProperties = mediaProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取视频元信息。
     *
     * @param mediaUrl 私有对象短签 GET URL；不得写日志或持久化
     * @return ffprobe 解析后的媒体元信息
     */
    public MediaProbeResult probe(String mediaUrl) {
        validateMediaUrl(mediaUrl);
        if (!probeSlots.tryAcquire()) {
            throw new MediaProbeException(HttpStatus.SERVICE_UNAVAILABLE, "媒体探测任务较多，请稍后重试");
        }
        try {
            return executeProbe(mediaUrl);
        } finally {
            probeSlots.release();
        }
    }

    private MediaProbeResult executeProbe(String mediaUrl) {
        List<String> command = new ArrayList<>();
        command.add(mediaProperties.getProcessing().getFfprobePath());
        command.add("-v");
        command.add("error");
        // 固定按 ISO BMFF/MP4 容器解析，避免伪装成 .mp4 的播放列表触发嵌套网络请求。
        command.add("-f");
        command.add("mov");
        command.add("-protocol_whitelist");
        command.add("http,https,tcp,tls");
        command.add("-print_format");
        command.add("json");
        command.add("-show_entries");
        command.add("format=format_name,duration:format_tags=major_brand:stream=codec_type,codec_name,width,height,duration,avg_frame_rate:stream_disposition=attached_pic");
        command.add(mediaUrl);

        Process process;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            // FFREPORT 会记录完整命令行，其中包含短签 URL，必须从子进程环境中移除。
            processBuilder.environment().remove("FFREPORT");
            process = processBuilder.start();
        } catch (IOException exception) {
            throw new MediaProbeException(HttpStatus.SERVICE_UNAVAILABLE, "ffprobe 命令不可用，请检查运行环境是否安装 FFmpeg", exception);
        }

        CompletableFuture<String> stdout = readAsync(process.getInputStream(), MAX_PROBE_OUTPUT_BYTES);
        CompletableFuture<String> stderr = readAsync(process.getErrorStream(), MAX_PROBE_ERROR_BYTES);
        boolean finished;
        try {
            Duration timeout = mediaProperties.getProcessing().getProbeTimeout();
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            terminateProcess(process);
            throw new MediaProbeException(HttpStatus.SERVICE_UNAVAILABLE, "媒体探测被中断", exception);
        }
        if (!finished) {
            terminateProcess(process);
            throw new MediaProbeException(HttpStatus.GATEWAY_TIMEOUT, "媒体探测超时，请检查 OSS 回源速度或视频文件结构");
        }

        String output = join(stdout);
        String errorOutput = join(stderr);
        if (process.exitValue() != 0) {
            if (isStorageReadFailure(errorOutput)) {
                throw new MediaProbeException(HttpStatus.SERVICE_UNAVAILABLE, "对象存储媒体读取失败，请稍后重试");
            }
            throw new MediaProbeException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "ffprobe 无法解析该视频，请确认文件是有效 MP4");
        }
        if (output.isBlank()) {
            throw new MediaProbeException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "ffprobe 没有返回可解析的媒体信息");
        }
        return parseProbeJson(output);
    }

    private CompletableFuture<String> readAsync(InputStream stream, int maxBytes) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
                byte[] buffer = new byte[8192];
                int totalBytes = 0;
                int readBytes;
                while ((readBytes = stream.read(buffer)) >= 0) {
                    totalBytes += readBytes;
                    if (totalBytes > maxBytes) {
                        throw new ProbeOutputLimitException();
                    }
                    output.write(buffer, 0, readBytes);
                }
                return output.toString(StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, outputReaderExecutor);
    }

    private String join(CompletableFuture<String> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof ProbeOutputLimitException) {
                throw new MediaProbeException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "ffprobe 返回的媒体信息过大，无法安全处理");
            }
            throw new MediaProbeException(HttpStatus.SERVICE_UNAVAILABLE, "读取 ffprobe 输出失败", exception);
        }
    }

    private void terminateProcess(Process process) {
        process.destroyForcibly();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.getInputStream().close();
                process.getErrorStream().close();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MediaProbeException(HttpStatus.SERVICE_UNAVAILABLE, "媒体探测被中断", exception);
        } catch (IOException ignored) {
            // 子进程已退出时关闭流可能失败；此时不影响超时或中断的既定处理结果。
        }
    }

    MediaProbeResult parseProbeJson(String output) {
        try {
            JsonNode root = objectMapper.readTree(output);
            validateMp4Format(root.path("format"));
            JsonNode videoStream = findPrimaryVideoStream(root);
            if (videoStream == null) {
                throw new MediaProbeException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "视频文件缺少视频流");
            }
            JsonNode audioStream = findStream(root, "audio");
            long durationMs = parseDurationMs(root, root.path("format"));
            if (durationMs <= 0) {
                throw new MediaProbeException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "视频时长无法识别");
            }
            int width = videoStream.path("width").asInt(0);
            int height = videoStream.path("height").asInt(0);
            if (width <= 0 || height <= 0) {
                throw new MediaProbeException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "视频分辨率无法识别");
            }
            String videoCodec = videoStream.path("codec_name").asText("");
            if (videoCodec.isBlank()) {
                throw new MediaProbeException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "视频编码无法识别");
            }
            return new MediaProbeResult(
                    durationMs,
                    width,
                    height,
                    parseFrameRate(videoStream.path("avg_frame_rate").asText("")),
                    videoCodec,
                    audioStream == null ? null : audioStream.path("codec_name").asText(null),
                    audioStream != null
            );
        } catch (MediaProbeException exception) {
            throw exception;
        } catch (IOException | NumberFormatException | ArithmeticException exception) {
            throw new MediaProbeException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "ffprobe 输出不是有效 JSON", exception);
        }
    }

    private void validateMp4Format(JsonNode format) {
        String formatName = format.path("format_name").asText("");
        if (!List.of(formatName.split(",")).contains("mp4")) {
            throw new MediaProbeException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "文件不是有效 MP4 容器");
        }
        String majorBrand = format.path("tags").path("major_brand").asText("").trim();
        if (majorBrand.isBlank()) {
            throw new MediaProbeException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MP4 容器缺少可识别的格式标识");
        }
        if ("qt".equalsIgnoreCase(majorBrand)) {
            throw new MediaProbeException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "P0 暂不支持 QuickTime MOV 文件");
        }
    }

    private JsonNode findPrimaryVideoStream(JsonNode root) {
        JsonNode streams = root.path("streams");
        if (!streams.isArray()) {
            return null;
        }
        JsonNode selected = null;
        BigDecimal selectedDuration = BigDecimal.valueOf(-1L);
        long selectedArea = -1L;
        for (JsonNode stream : streams) {
            if (!"video".equals(stream.path("codec_type").asText())
                    || stream.path("disposition").path("attached_pic").asInt(0) == 1) {
                continue;
            }
            BigDecimal duration = parseDuration(stream.path("duration").asText(""));
            long area = (long) stream.path("width").asInt(0) * stream.path("height").asInt(0);
            if (selected == null
                    || duration.compareTo(selectedDuration) > 0
                    || (duration.compareTo(selectedDuration) == 0 && area > selectedArea)) {
                selected = stream;
                selectedDuration = duration;
                selectedArea = area;
            }
        }
        return selected;
    }

    private JsonNode findStream(JsonNode root, String codecType) {
        JsonNode streams = root.path("streams");
        if (!streams.isArray()) {
            return null;
        }
        for (JsonNode stream : streams) {
            if (codecType.equals(stream.path("codec_type").asText())) {
                return stream;
            }
        }
        return null;
    }

    private long parseDurationMs(JsonNode root, JsonNode format) {
        BigDecimal duration = parseDuration(format.path("duration").asText(""));
        JsonNode streams = root.path("streams");
        if (streams.isArray()) {
            for (JsonNode stream : streams) {
                if ("video".equals(stream.path("codec_type").asText())
                        && stream.path("disposition").path("attached_pic").asInt(0) != 1) {
                    duration = duration.max(parseDuration(stream.path("duration").asText("")));
                }
            }
        }
        if (duration.signum() <= 0) {
            return 0L;
        }
        return duration
                .multiply(BigDecimal.valueOf(1000L))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private BigDecimal parseDuration(String value) {
        if (value == null || value.isBlank() || "N/A".equalsIgnoreCase(value)) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value);
    }

    private BigDecimal parseFrameRate(String value) {
        if (value == null || value.isBlank() || "0/0".equals(value) || "N/A".equalsIgnoreCase(value)) {
            return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        }
        if (value.contains("/")) {
            String[] parts = value.split("/", 2);
            BigDecimal numerator = new BigDecimal(parts[0]);
            BigDecimal denominator = new BigDecimal(parts[1]);
            if (denominator.compareTo(BigDecimal.ZERO) == 0) {
                return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
            }
            return numerator.divide(denominator, MathContext.DECIMAL64).setScale(6, RoundingMode.HALF_UP);
        }
        return new BigDecimal(value).setScale(6, RoundingMode.HALF_UP);
    }

    private void validateMediaUrl(String mediaUrl) {
        try {
            String scheme = URI.create(mediaUrl).getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("unsupported scheme");
            }
        } catch (RuntimeException exception) {
            throw new MediaProbeException(HttpStatus.SERVICE_UNAVAILABLE, "媒体读取地址无效", exception);
        }
    }

    private boolean isStorageReadFailure(String stderr) {
        String normalized = stderr == null ? "" : stderr.toLowerCase();
        return normalized.contains("http error")
                || normalized.contains("server returned 4")
                || normalized.contains("server returned 5")
                || normalized.contains("connection refused")
                || normalized.contains("connection reset")
                || normalized.contains("network is unreachable")
                || normalized.contains("temporary failure")
                || normalized.contains("i/o error");
    }

    @PreDestroy
    void closeOutputReaderExecutor() {
        outputReaderExecutor.close();
    }

    private static class ProbeOutputLimitException extends IOException {
    }
}
