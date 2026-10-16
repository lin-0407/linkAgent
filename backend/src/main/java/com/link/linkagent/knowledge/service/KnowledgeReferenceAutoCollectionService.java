package com.link.linkagent.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.knowledge.model.ReferenceAutoCollectionRequest;
import com.link.linkagent.knowledge.model.ReferenceAutoCollectionResponse;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 页面只负责启动一轮既有脚本；候选续采、筛选、入库和清理由脚本继续负责。 */
@Service
public class KnowledgeReferenceAutoCollectionService {
    private final KnowledgeReferenceFetchService fetchService;
    private final ObjectMapper mapper;
    private final String pythonCommand;
    private final String configPath;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "reference-auto-collection");
        thread.setDaemon(true);
        return thread;
    });
    private Run current;

    public KnowledgeReferenceAutoCollectionService(KnowledgeReferenceFetchService fetchService, ObjectMapper mapper,
            @Value("${knowledge.reference-auto.python-command:python}") String pythonCommand,
            @Value("${knowledge.reference-auto.config-path:}") String configPath) {
        this.fetchService = fetchService;
        this.mapper = mapper;
        this.pythonCommand = pythonCommand;
        this.configPath = configPath;
    }

    public synchronized ReferenceAutoCollectionResponse status() {
        if (current != null) return current.response();
        return new ReferenceAutoCollectionResponse(null, "IDLE", null, null, "尚未开始采集", settings(loadConfig()), null);
    }

    public synchronized ReferenceAutoCollectionResponse start(ReferenceAutoCollectionRequest request, int port, String contextPath) {
        if (current != null && current.running()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有采集正在运行，请等待完成或先停止");
        }
        Path root = fetchService.resolveProjectRoot();
        Path script = root.resolve("scripts/bilibili_reference_auto.py");
        if (!Files.isRegularFile(script)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "没有找到自动采集脚本");
        }
        ObjectNode config = loadConfig();
        if (request.minIndex() != null) config.put("min_index", request.minIndex());
        if (request.maxImports() != null) config.put("max_imports", request.maxImports());
        if (request.maxChecks() != null) config.put("max_checks", request.maxChecks());
        if (request.rids() != null) config.set("rids", mapper.valueToTree(request.rids()));
        config.put("api_base", "http://127.0.0.1:" + port + contextPath);
        Path directory = null;
        try {
            Path exports = root.resolve("export/bilibili_reference");
            Files.createDirectories(exports);
            directory = Files.createTempDirectory(exports, "auto-web-");
            Path configFile = directory.resolve("config.json");
            Path reportFile = directory.resolve("report.json");
            Path logFile = directory.resolve("output.log");
            mapper.writeValue(configFile.toFile(), config);
            ProcessBuilder builder = new ProcessBuilder(pythonCommand, script.toString(), "--config",
                    configFile.toString(), "--report", reportFile.toString());
            builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(logFile.toFile());
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            Run run = new Run(builder.start(), directory, settings(config));
            current = run;
            executor.submit(() -> awaitCompletion(run));
            return run.response();
        } catch (IOException exception) {
            cleanRunFiles(directory);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "无法启动采集，请确认后端环境的 python 命令和采集脚本可用", exception);
        }
    }

    public synchronized ReferenceAutoCollectionResponse stop() {
        if (current == null || !current.running()) return status();
        current.cancelled = true;
        current.status = "STOPPING";
        current.message = "正在停止，已经入库的案例会保留";
        terminate(current.process);
        return current.response();
    }

    private void awaitCompletion(Run run) {
        String status = "FAILED";
        String message;
        JsonNode report = null;
        try {
            // 脚本含逐请求限时；再限制整轮时长，避免异常外部接口长期占用后台进程。
            boolean ended = run.process.waitFor(2, TimeUnit.HOURS);
            if (!ended) terminate(run.process);
            Path reportFile = run.directory.resolve("report.json");
            if (Files.isRegularFile(reportFile) && Files.size(reportFile) <= 10 * 1024 * 1024) {
                report = mapper.readTree(reportFile.toFile());
            }
            if (!ended) {
                message = "本轮采集超过两小时，已停止；可重新启动继续处理候选";
            } else if (run.process.exitValue() == 0 && report != null) {
                status = "SUCCEEDED";
                message = "采集完成";
            } else if (run.process.exitValue() == 2 && report != null) {
                status = "WARNING";
                message = report.hasNonNull("blocked") ? "采集已停止：公开接口要求验证或限流，请稍后再试" : "本轮已结束，部分来源或视频未采集成功";
            } else {
                Path output = run.directory.resolve("output.log");
                String detail = Files.size(output) <= 1024 * 1024 ? Files.readString(output, StandardCharsets.UTF_8).strip() : "脚本输出过长";
                message = "采集失败：" + detail.substring(Math.max(0, detail.length() - 1500));
            }
        } catch (Exception exception) {
            terminate(run.process);
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            message = "采集未完成：" + exception.getMessage();
        }
        synchronized (this) {
            run.status = run.cancelled ? "CANCELLED" : status;
            run.message = run.cancelled ? "已停止，已经入库的案例会保留，可再次启动继续采集" : message;
            run.finishedAt = Instant.now();
            run.report = report;
        }
        // 只删除本轮生成的三个临时文件，候选状态库继续保留用于下次续采。
        cleanRunFiles(run.directory);
    }

    private ObjectNode loadConfig() {
        Path root = fetchService.resolveProjectRoot();
        Path local = root.resolve("scripts/bilibili_reference_auto.local.json");
        Path source = configPath.isBlank()
                ? (Files.isRegularFile(local) ? local : root.resolve("scripts/bilibili_reference_auto.example.json"))
                : Path.of(configPath).toAbsolutePath().normalize();
        try {
            JsonNode node = mapper.readTree(source.toFile());
            if (!(node instanceof ObjectNode object)) throw new IOException("配置不是JSON对象");
            return object;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "读取自动采集配置失败，请检查脚本示例或本地配置", exception);
        }
    }

    private ReferenceAutoCollectionRequest settings(ObjectNode config) {
        List<Integer> rids = config.has("rids") ? mapper.convertValue(config.get("rids"),
                mapper.getTypeFactory().constructCollectionType(List.class, Integer.class)) : List.of(1, 3, 4, 36, 188, 160, 211, 181);
        return new ReferenceAutoCollectionRequest(config.path("min_index").asDouble(60), config.path("max_imports").asInt(10),
                config.path("max_checks").asInt(100), rids);
    }

    private static void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void cleanRunFiles(Path directory) {
        if (directory == null) return;
        for (String name : List.of("config.json", "report.json", "output.log")) {
            try { Files.deleteIfExists(directory.resolve(name)); } catch (IOException ignored) {}
        }
        try { Files.deleteIfExists(directory); } catch (IOException ignored) {}
    }

    @PreDestroy
    public synchronized void close() {
        if (current != null && current.running()) {
            current.cancelled = true;
            terminate(current.process);
        }
        executor.shutdownNow();
    }

    private static final class Run {
        final String id = UUID.randomUUID().toString();
        final Instant startedAt = Instant.now();
        final Process process;
        final Path directory;
        final ReferenceAutoCollectionRequest settings;
        String status = "RUNNING";
        String message = "正在发现候选、筛选指数并采集入库，请稍候";
        Instant finishedAt;
        JsonNode report;
        boolean cancelled;

        Run(Process process, Path directory, ReferenceAutoCollectionRequest settings) {
            this.process = process;
            this.directory = directory;
            this.settings = settings;
        }

        boolean running() { return "RUNNING".equals(status) || "STOPPING".equals(status); }

        ReferenceAutoCollectionResponse response() {
            return new ReferenceAutoCollectionResponse(id, status, startedAt, finishedAt, message, settings, report);
        }
    }
}
