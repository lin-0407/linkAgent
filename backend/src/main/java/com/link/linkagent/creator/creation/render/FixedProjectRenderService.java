package com.link.linkagent.creator.creation.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 后端与离线渲染器之间的进程边界；调用期间不应持有数据库事务。 */
@Service
@ConditionalOnProperty(prefix = "creator.creation.renderer", name = "enabled", havingValue = "true")
public class FixedProjectRenderService {
    private static final int MAX_PROCESS_OUTPUT = 64 * 1024;
    private static final Set<String> SYSTEM_ENV = Set.of("PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "LANG", "LD_LIBRARY_PATH");
    private static final Set<String> RENDER_CODES = Set.of("INVALID_INPUT", "INPUT_HASH_MISMATCH", "ASSET_MISSING",
            "ASSET_HASH_MISMATCH", "ASSET_CHANGED", "PATH_OUTSIDE_ROOT", "AUDIO_OVERFLOW", "AUDIO_CLIPPING", "INVALID_IMAGE",
            "AUDIO_SILENT", "OUTPUT_EXISTS", "ENVIRONMENT_MISSING", "FONT_MISSING", "FONT_INVALID", "FONT_GLYPH_MISSING", "TEXT_OVERFLOW",
            "QUALITY_FAILED", "OUTPUT_TOO_LARGE", "DISK_SPACE_LOW", "ENCODING_FAILED", "NETWORK_FORBIDDEN",
            "SECRET_FILE_FORBIDDEN", "RENDER_FAILED", "TIMEOUT", "CANCELLED");
    private final FixedProjectRendererProperties properties;
    private final ObjectMapper json;
    private final RenderArtifactVerifier verifier;

    public FixedProjectRenderService(FixedProjectRendererProperties properties, ObjectMapper json) {
        this.properties = properties; this.json = json; this.verifier = new RenderArtifactVerifier(json);
    }

    /** 业务身份来自已受理运行，不能从工程里的身份反向推断。 */
    public record Request(Path input, String runId, String attemptId, String storyboardId, String inputHash) { }

    public record VerifiedOutput(RenderArtifacts artifacts, String projectJson) { }

    /** 恢复或上传前重新验收工作目录，调用方仍须核对持久化租约和内容授权。 */
    public VerifiedOutput verifyExisting(Request request, BooleanSupplier cancelled) {
        validate(request);
        Runnable check = () -> {
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw failure("CANCELLED", "本次渲染已取消");
        };
        try {
            Path root = Path.of(properties.getWorkRoot()).toRealPath();
            Path attempt = root.resolve(request.runId()).resolve(request.attemptId());
            Path input = request.input().toAbsolutePath().normalize();
            if (!attempt.toRealPath().equals(attempt) || !input.startsWith(attempt)
                    || !input.toRealPath().equals(input)) throw failure("INVALID_INPUT", "工程路径不属于本次尝试");
            JsonNode project = verifier.input(input, request);
            String before = RenderArtifactVerifier.digest(input, check);
            RenderArtifacts result = verifier.verify(attempt.resolve("rendered"), project, request, check);
            if (!before.equals(RenderArtifactVerifier.digest(input, check))) throw failure("INPUT_CHANGED", "验收期间工程已变化");
            return new VerifiedOutput(result, json.writeValueAsString(project));
        } catch (IOException e) { throw failure("INVALID_ARTIFACT", "无法读取本次工程或产物"); }
    }

    public RenderArtifacts render(Request request, BooleanSupplier cancelled) {
        validate(request);
        if (cancelled == null) throw new IllegalArgumentException("必须提供取消检查");
        long deadline = System.nanoTime() + properties.getTimeout().toNanos();
        Runnable check = () -> {
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
                throw failure("CANCELLED", "本次渲染已取消");
            if (System.nanoTime() >= deadline) throw failure("TIMEOUT", "本次渲染超时");
        };
        Process process = null;
        Thread reader = null;
        Set<ProcessHandle> children = new HashSet<>();
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        AtomicBoolean outputOverflow = new AtomicBoolean(), readFailure = new AtomicBoolean();
        try {
            check.run();
            Path root = Path.of(properties.getWorkRoot()).toRealPath();
            Path attempt = root.resolve(request.runId()).resolve(request.attemptId());
            if (!Files.isDirectory(attempt, LinkOption.NOFOLLOW_LINKS) || !attempt.toRealPath().equals(attempt))
                throw failure("INVALID_INPUT", "本次尝试工作目录不存在或路径无效");
            Path input = request.input().toAbsolutePath().normalize();
            if (!input.startsWith(attempt) || !Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)
                    || !input.toRealPath().equals(input))
                throw failure("INVALID_INPUT", "输入工程必须位于本次尝试目录");
            Path output = attempt.resolve("rendered");
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS))
                throw failure("OUTPUT_EXISTS", "本次尝试已有输出，请创建新尝试");
            JsonNode project = verifier.input(input, request);
            String inputFileHash = RenderArtifactVerifier.digest(input, check);
            Path module = Path.of(properties.getModuleRoot()).toRealPath();
            if (!Files.isRegularFile(module.resolve("linkagent_renderer/__main__.py")))
                throw failure("ENVIRONMENT_MISSING", "未安装固定工程渲染模块");
            long seconds = Math.max(1, (properties.getTimeout().toMillis() + 999) / 1000);
            List<String> command = List.of(properties.getPythonPath(), "-m", "linkagent_renderer", "render",
                    "--input", input.toString(), "--work-root", attempt.toString(), "--output-dir", output.toString(),
                    "--font", properties.getFontPath(), "--ffmpeg", properties.getFfmpegPath(),
                    "--ffprobe", properties.getFfprobePath(), "--timeout-seconds", Long.toString(seconds));
            ProcessBuilder builder = new ProcessBuilder(command).directory(module.toFile()).redirectErrorStream(true);
            Map<String, String> environment = builder.environment();
            environment.keySet().removeIf(key -> !SYSTEM_ENV.contains(key.toUpperCase(Locale.ROOT)));
            environment.put("PYTHONDONTWRITEBYTECODE", "1");
            environment.put("PYTHONNOUSERSITE", "1");
            environment.put("OPENBLAS_NUM_THREADS", "1");
            environment.put("OMP_NUM_THREADS", "1");
            check.run();
            try { process = builder.start(); }
            catch (IOException e) { throw failure("ENVIRONMENT_MISSING", "无法启动本地渲染进程"); }
            process.getOutputStream().close();
            Process launched = process;
            // 持续排空管道，但仅保留有界输出，避免子进程阻塞或日志耗尽后端内存。
            reader = Thread.ofVirtual().name("creation-render-output").start(() -> {
                try (var stream = launched.getInputStream()) {
                    byte[] buffer = new byte[4096]; int count;
                    while ((count = stream.read(buffer)) != -1) {
                        int remaining = MAX_PROCESS_OUTPUT - capture.size();
                        if (count > remaining) outputOverflow.set(true);
                        if (remaining > 0) capture.write(buffer, 0, Math.min(count, remaining));
                    }
                } catch (IOException e) { readFailure.set(true); }
            });
            while (true) {
                process.descendants().forEach(children::add);
                check.run();
                if (outputOverflow.get()) throw failure("OUTPUT_TOO_LARGE", "渲染进程输出超过限制");
                if (process.waitFor(100, TimeUnit.MILLISECONDS)) break;
            }
            check.run();
            // 根进程已退出也不能忽略它遗留的管道写入者；等待仍受同一取消和超时约束。
            while (reader.isAlive()) { check.run(); reader.join(100); }
            if (outputOverflow.get()) throw failure("OUTPUT_TOO_LARGE", "渲染进程输出超过限制");
            if (readFailure.get()) throw failure("RENDER_FAILED", "无法读取渲染结果");
            if (process.exitValue() != 0) throw processFailure(process.exitValue(), capture.toString(StandardCharsets.UTF_8));
            if (children.stream().anyMatch(ProcessHandle::isAlive))
                throw failure("RENDER_FAILED", "渲染进程未完整退出");
            if (!inputFileHash.equals(RenderArtifactVerifier.digest(input, check)))
                throw failure("INPUT_CHANGED", "渲染期间工程文件发生变化");
            RenderArtifacts result = verifier.verify(output, project, request, check);
            check.run();
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("CANCELLED", "本次渲染已取消");
        } catch (IOException e) {
            throw failure("INVALID_ARTIFACT", "无法读取本次工程或渲染产物");
        } finally {
            if (process != null) stop(process, children);
            if (reader != null && reader.isAlive()) reader.interrupt();
        }
    }

    private void validate(Request request) {
        if (!properties.isEnabled()) throw failure("DISABLED", "后端离线渲染尚未启用");
        if (request == null || request.input() == null || !identifier(request.runId())
                || !identifier(request.attemptId()) || !identifier(request.storyboardId())
                || request.inputHash() == null || !request.inputHash().matches("[0-9a-f]{64}"))
            throw failure("INVALID_INPUT", "本次工程归属或摘要不合法");
        Duration timeout = properties.getTimeout();
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofHours(1)) > 0)
            throw failure("ENVIRONMENT_MISSING", "渲染超时配置无效");
        for (String value : new String[] { properties.getPythonPath(), properties.getModuleRoot(), properties.getWorkRoot(),
                properties.getFontPath(), properties.getFfmpegPath(), properties.getFfprobePath() })
            if (value == null || value.isBlank()) throw failure("ENVIRONMENT_MISSING", "渲染部署配置不完整");
    }

    private RenderExecutionException processFailure(int exit, String output) {
        if (exit == 124) return failure("TIMEOUT", "本次渲染超时");
        if (exit == 130) return failure("CANCELLED", "本次渲染已取消");
        String code = "RENDER_FAILED";
        String last = output.strip();
        int line = last.lastIndexOf('\n');
        try {
            JsonNode result = json.readTree(last.substring(line + 1));
            String reported = result.path("code").asText();
            if ("FAILED".equals(result.path("status").asText()) && RENDER_CODES.contains(reported)) code = reported;
        } catch (IOException | RuntimeException ignored) { }
        return failure(code, "本地渲染失败，请检查本次尝试日志");
    }

    private void stop(Process process, Set<ProcessHandle> children) {
        process.descendants().forEach(children::add);
        boolean interrupted = Thread.interrupted();
        try {
            if (process.isAlive()) process.destroy();
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            // 只回收当前启动链路中观察到的句柄，禁止按进程名或端口批量终止。
            children.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
            process.waitFor(2, TimeUnit.SECONDS);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            for (ProcessHandle child : children)
                if (child.isAlive()) {
                    long remaining = until - System.nanoTime();
                    if (remaining > 0) try { child.onExit().get(remaining, TimeUnit.NANOSECONDS); }
                    catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) { }
                }
        } catch (InterruptedException e) {
            interrupted = true; process.destroyForcibly();
            children.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
        } finally {
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static boolean identifier(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,64}"); }
    private static RenderExecutionException failure(String code, String message) { return new RenderExecutionException(code, message); }
}
