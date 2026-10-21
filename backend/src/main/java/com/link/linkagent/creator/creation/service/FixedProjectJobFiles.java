package com.link.linkagent.creator.creation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.model.FixedProjectJob;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.Run;
import com.link.linkagent.creator.creation.render.*;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import static com.link.linkagent.creator.creation.service.CreationInputRules.*;

/** 新租约复制独立输入，旧进程的残留文件不能成为新尝试的完成证据。 */
@Component
public class FixedProjectJobFiles {
    private final FixedProjectRendererProperties properties;
    private final ObjectMapper json;

    public FixedProjectJobFiles(FixedProjectRendererProperties properties, ObjectMapper json) {
        this.properties = properties; this.json = json;
    }

    /** 仅供后端准备好的固定工程接入；没有对应的公开路径上传 API。 */
    public FixedProjectJob freeze(String taskId, String ownerId, FixedProjectRenderService.Request request) {
        if (!properties.isEnabled()) throw failure("DISABLED", "固定工程渲染尚未启用");
        if (request == null || request.input() == null) throw failure("INVALID_INPUT", "缺少源工程");
        id(request.runId()); id(request.attemptId()); id(request.storyboardId());
        try {
            Path source = attempt(request.runId(), request.attemptId());
            Path input = request.input().toAbsolutePath().normalize();
            if (!input.equals(source.resolve("input.json")) || !Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)
                    || !input.toRealPath().equals(input)) throw failure("INVALID_INPUT", "源工程不属于本运行工作目录");
            byte[] bytes;
            try (var stream = Files.newInputStream(input)) { bytes = stream.readNBytes(256 * 1024 + 1); }
            if (bytes.length > 256 * 1024) throw failure("INVALID_INPUT", "源工程超过大小限制");
            String document = renderDocument(json, StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString());
            ObjectNode node = (ObjectNode) json.readTree(document);
            if (!node.path("schemaVersion").isIntegralNumber() || !node.path("schemaVersion").canConvertToInt()
                    || !Set.of(1, 2).contains(node.path("schemaVersion").asInt()) || !request.runId().equals(node.path("runId").asText())
                    || !request.attemptId().equals(node.path("attemptId").asText())
                    || !request.storyboardId().equals(node.path("storyboardId").asText())
                    || !renderInputHash(json, node).equals(request.inputHash())
                    || !request.inputHash().equals(node.path("inputHash").asText()))
                throw failure("INVALID_INPUT", "源工程身份或摘要不一致");
            return new FixedProjectJob(request.runId(), taskId, ownerId, request.attemptId(), document);
        } catch (IOException e) { throw failure("ASSET_MISSING", "源工程工作目录不可用"); }
    }

    public FixedProjectRenderService.Request prepare(FixedProjectJob job, Run run, BooleanSupplier cancelled) {
        if (!job.runId().equals(run.runId()) || !job.taskId().equals(run.taskId()) || !job.ownerId().equals(run.ownerId()))
            throw failure("INVALID_INPUT", "工程与本次运行不一致");
        try {
            check(cancelled);
            Path source = attempt(job.runId(), job.sourceAttemptId());
            String attemptId = "exec-" + run.leaseGeneration() + "-" + UUID.randomUUID().toString().replace("-", "");
            Path target = source.getParent().resolve(attemptId);
            Files.createDirectory(target);
            ObjectNode project = (ObjectNode) json.readTree(renderDocument(json, job.projectJson()));
            var assets = project.path("assets");
            if (!assets.isArray() || assets.isEmpty() || assets.size() > 64) throw failure("INVALID_INPUT", "工程资产数量无效");
            long total = 0;
            Set<String> copied = new HashSet<>();
            for (var asset : assets) {
                check(cancelled);
                String raw = asset.path("path").asText();
                String name = raw.replace('\\', '/');
                if (name.isBlank() || name.length() > 256 || name.startsWith("/") || name.contains(":"))
                    throw failure("INVALID_INPUT", "工程资产路径无效");
                for (String part : name.split("/", -1))
                    if (part.isEmpty() || part.startsWith(".")) throw failure("INVALID_INPUT", "工程资产路径无效");
                if (name.equals("input.json") || name.equals("rendered") || name.startsWith("rendered/"))
                    throw failure("INVALID_INPUT", "工程资产占用保留目录");
                Path original = source.resolve(name).normalize(), destination = target.resolve(name).normalize();
                if (!original.startsWith(source) || !Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)
                        || !original.toRealPath().equals(original)) throw failure("ASSET_MISSING", "源资产不存在或路径无效");
                long size = Files.size(original); total += size;
                long limit = backgroundMusicOnly(project, asset) ? 64L * 1024 * 1024 : 32L * 1024 * 1024;
                if (size <= 0 || size > limit || total > 256L * 1024 * 1024)
                    throw failure("INVALID_INPUT", "工程资产超过大小限制");
                String expected = asset.path("sha256").asText();
                if (!expected.matches("[0-9a-f]{64}")) throw failure("INVALID_INPUT", "工程资产摘要无效");
                if (copied.add(name)) {
                    Files.createDirectories(destination.getParent());
                    if (!destination.getParent().toRealPath().equals(destination.getParent())) throw failure("INVALID_INPUT", "尝试目录路径无效");
                    copy(original, destination, expected, limit, cancelled);
                } else if (!expected.equals(digest(destination, cancelled))) throw failure("ASSET_HASH_MISMATCH", "重复资产声明不一致");
            }
            project.put("runId", run.runId()).put("attemptId", attemptId);
            String inputHash = renderInputHash(json, project);
            project.put("inputHash", inputHash);
            Path input = target.resolve("input.json");
            Files.writeString(input, renderDocument(json, json.writeValueAsString(project)), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            check(cancelled);
            return new FixedProjectRenderService.Request(input, run.runId(), attemptId, project.path("storyboardId").asText(), inputHash);
        } catch (IOException e) { throw failure("ASSET_MISSING", "无法恢复固定工程的源资产或工作目录"); }
    }

    private Path attempt(String runId, String attemptId) throws IOException {
        id(runId); id(attemptId);
        Path root = Path.of(properties.getWorkRoot()).toRealPath();
        Path source = root.resolve(runId).resolve(attemptId);
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) || !source.toRealPath().equals(source))
            throw failure("INVALID_INPUT", "源工程目录不存在或路径无效");
        return source;
    }

    private boolean backgroundMusicOnly(ObjectNode project, com.fasterxml.jackson.databind.JsonNode asset) {
        String assetId = asset.path("id").asText();
        if (project.path("schemaVersion").asInt() != 2 || assetId.isBlank() || !"audio/wav".equals(asset.path("mime").asText())
                || !assetId.equals(project.path("backgroundMusic").path("assetId").asText())) return false;
        // 冻结工程没有业务 kind 字段，仅对独占背景角色放宽，不能借该声明扩大旁白或视频资产限制。
        for (var scene : project.path("scenes")) if (assetId.equals(scene.path("voice").path("assetId").asText())
                || assetId.equals(scene.path("clip").path("assetId").asText()) || assetId.equals(scene.path("imageAssetId").asText())) return false;
        return true;
    }

    private void copy(Path source, Path target, String expected, long limit, BooleanSupplier cancelled) throws IOException {
        try (var input = Files.newInputStream(source); var output = Files.newOutputStream(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536]; int count; long length = 0;
            while ((count = input.read(buffer)) != -1) {
                check(cancelled); length += count;
                if (length > limit) throw failure("INVALID_INPUT", "复制期间源资产超过大小限制");
                output.write(buffer, 0, count);
            }
        }
        if (!expected.equals(digest(target, cancelled))) throw failure("ASSET_HASH_MISMATCH", "源资产与冻结摘要不一致");
    }

    private String digest(Path file, BooleanSupplier cancelled) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) { check(cancelled); digest.update(buffer, 0, count); }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void check(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw failure("CANCELLED", "本次工程准备已取消");
    }
    private static RenderExecutionException failure(String code, String message) { return new RenderExecutionException(code, message); }
}
