package com.link.linkagent.creator.creation.storyboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.render.*;
import com.link.linkagent.creator.creation.service.CreationInputRules;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.Asset;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;

/** 只下载已冻结的私有规范化对象，不把页面路径或短签地址当成工程资产来源。 */
@Component
@ConditionalOnProperty(prefix="creator.media", name="enabled", havingValue="true")
public class StoryboardProjectFiles {
    private static final Duration STORAGE_TIMEOUT = Duration.ofSeconds(120);
    private final FixedProjectRendererProperties properties;
    private final ObjectStorageService storage;
    private final ObjectMapper json;

    public StoryboardProjectFiles(FixedProjectRendererProperties properties, ObjectStorageService storage, ObjectMapper json) {
        this.properties = properties; this.storage = storage; this.json = json;
    }

    /** 受理事务失败只撤回这次已核对身份的工程，相邻尝试和作者文件不在清理范围内。 */
    public void discard(FixedProjectRenderService.Request request) {
        if (request == null || request.input() == null) throw failure("INVALID_INPUT", "缺少待撤回工程");
        identifier(request.runId()); identifier(request.attemptId()); identifier(request.storyboardId());
        if (request.inputHash() == null || !request.inputHash().matches("[0-9a-f]{64}")) throw failure("INVALID_INPUT", "待撤回工程摘要无效");
        try {
            Path root = Path.of(properties.getWorkRoot()).toAbsolutePath().normalize();
            Path attempt = root.resolve(request.runId()).resolve(request.attemptId());
            Path input = request.input().toAbsolutePath().normalize();
            if (!input.equals(attempt.resolve("input.json"))) throw failure("INVALID_INPUT", "待撤回工程路径不属于该运行");
            if (!Files.exists(attempt, LinkOption.NOFOLLOW_LINKS)) return;
            if (!root.toRealPath().equals(root) || !attempt.toRealPath().equals(attempt)
                    || !Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS) || !input.toRealPath().equals(input))
                throw failure("INVALID_INPUT", "待撤回工程目录或文件无效");
            byte[] bytes;
            try (var stream = Files.newInputStream(input)) { bytes = stream.readNBytes(256 * 1024 + 1); }
            if (bytes.length > 256 * 1024) throw failure("INVALID_INPUT", "待撤回工程过大");
            var document = json.readTree(bytes);
            if (!(document instanceof ObjectNode object) || !request.runId().equals(document.path("runId").asText())
                    || !request.attemptId().equals(document.path("attemptId").asText()) || !request.storyboardId().equals(document.path("storyboardId").asText())
                    || !request.inputHash().equals(document.path("inputHash").asText()) || !request.inputHash().equals(CreationInputRules.renderInputHash(json, object)))
                throw failure("INPUT_HASH_MISMATCH", "待撤回工程内容或身份已变化");
            cleanup(attempt);
            if (Files.exists(attempt, LinkOption.NOFOLLOW_LINKS)) throw failure("CLEANUP_FAILED", "本次工程清理未完成");
            try { Files.deleteIfExists(attempt.getParent()); } catch (DirectoryNotEmptyException ignored) { }
        } catch (IOException exception) { throw failure("CLEANUP_FAILED", "无法核对或清理本次故事板工程"); }
    }

    public FixedProjectRenderService.Request materialize(ObjectNode project, List<Asset> supplied, BooleanSupplier cancelled) {
        if (!properties.isEnabled()) throw failure("DISABLED", "故事板渲染尚未启用");
        if (project == null || cancelled == null) throw failure("INVALID_INPUT", "缺少工程或取消检查");
        String runId = identifier(project.path("runId").asText()), attemptId = identifier(project.path("attemptId").asText());
        String storyboardId = identifier(project.path("storyboardId").asText()), inputHash = project.path("inputHash").asText();
        if (!CreationInputRules.renderInputHash(json, project).equals(inputHash)) throw failure("INPUT_HASH_MISMATCH", "工程内容与摘要不一致");
        Map<String, Asset> assets = new HashMap<>();
        if (supplied != null) for (Asset asset : supplied) {
            if (asset == null || assets.putIfAbsent(asset.assetId(), asset) != null) throw failure("INVALID_INPUT", "素材快照为空或身份重复");
        }
        Path attempt = null, run = null;
        boolean madeRun = false, succeeded = false;
        try {
            check(cancelled);
            Path root = Path.of(properties.getWorkRoot()).toAbsolutePath().normalize();
            Files.createDirectories(root);
            if (!root.toRealPath().equals(root)) throw failure("INVALID_INPUT", "工程根目录包含符号链接");
            run = root.resolve(runId);
            if (!Files.exists(run, LinkOption.NOFOLLOW_LINKS)) { Files.createDirectory(run); madeRun = true; }
            if (!Files.isDirectory(run, LinkOption.NOFOLLOW_LINKS) || !run.toRealPath().equals(run)) throw failure("INVALID_INPUT", "运行目录不是受控本地目录");
            Path expectedAttempt = run.resolve(attemptId);
            Files.createDirectory(expectedAttempt);
            attempt = expectedAttempt;
            if (!attempt.toRealPath().equals(attempt)) throw failure("INVALID_INPUT", "工程尝试目录无效");
            Path assetRoot = Files.createDirectory(attempt.resolve("assets"));
            var declarations = project.path("assets");
            if (!declarations.isArray() || declarations.isEmpty() || declarations.size() > 64) throw failure("INVALID_INPUT", "工程素材声明无效");
            long total = 0;
            Set<String> seen = new HashSet<>();
            for (var declaration : declarations) {
                check(cancelled);
                String id = identifier(declaration.path("id").asText());
                String mime = declaration.path("mime").asText(), path = declaration.path("path").asText();
                if (!seen.add(id) || !StoryboardCompiler.assetPath(id, mime).equals(path)) throw failure("INVALID_INPUT", "素材路径或身份与固定工程契约不同");
                Path destination = attempt.resolve(path).normalize();
                if (!destination.getParent().equals(assetRoot)) throw failure("INVALID_INPUT", "素材越出本次工程目录");
                String sha256 = declaration.path("sha256").asText();
                if (StoryboardCompiler.PLACEHOLDER_ID.equals(id) && "image/png".equals(mime)
                        && StoryboardCompiler.sha256(StoryboardCompiler.PLACEHOLDER).equals(sha256) && declarations.size() == 1) {
                    for (var scene : project.path("scenes")) if (id.equals(scene.path("imageAssetId").asText())
                            || id.equals(scene.path("clip").path("assetId").asText()) || id.equals(scene.path("voice").path("assetId").asText()))
                        throw failure("INVALID_INPUT", "系统占位资产不能成为场景画面或配音");
                    Files.write(destination, StoryboardCompiler.PLACEHOLDER, StandardOpenOption.CREATE_NEW);
                    total += StoryboardCompiler.PLACEHOLDER.length;
                    continue;
                }
                Asset asset = assets.get(id);
                // 只有明确被全片选作背景音乐的 MUSIC 工作文件可使用 64 MiB，上限不外溢到录音或画面。
                boolean music = id.equals(project.path("backgroundMusic").path("assetId").asText())
                        && asset != null && "MUSIC".equals(asset.kind()) && "audio/wav".equals(mime);
                if (music) for (var scene : project.path("scenes")) if (id.equals(scene.path("voice").path("assetId").asText())
                        || id.equals(scene.path("clip").path("assetId").asText()) || id.equals(scene.path("imageAssetId").asText())) music = false;
                long limit = (music ? 64L : 32L) * 1024 * 1024;
                if (asset == null || !mime.equals(asset.contentType()) || !sha256.equals(asset.sha256())
                        || asset.bytes() <= 0 || asset.bytes() > limit
                        || asset.bucketName() == null || asset.bucketName().isBlank() || asset.objectKey() == null || asset.objectKey().isBlank())
                    throw failure("INVALID_INPUT", "工程素材与已冻结私有对象不一致");
                total += asset.bytes();
                if (total > 256L * 1024 * 1024) throw failure("INVALID_INPUT", "工程素材合计超过 256 MiB");
                if (storage.headObject(asset.bucketName(), asset.objectKey()).contentLength() != asset.bytes())
                    throw failure("ASSET_SIZE_MISMATCH", "私有素材大小与已冻结记录不同");
                check(cancelled);
                storage.downloadObject(asset.bucketName(), asset.objectKey(), destination, STORAGE_TIMEOUT);
                check(cancelled);
                if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) || !destination.toRealPath().equals(destination)
                        || Files.size(destination) != asset.bytes()) throw failure("ASSET_SIZE_MISMATCH", "下载的私有素材大小不正确");
                if (!sha256.equals(digest(destination, cancelled))) throw failure("ASSET_HASH_MISMATCH", "下载素材与冻结摘要不同");
            }
            byte[] document = json.writeValueAsString(project).getBytes(StandardCharsets.UTF_8);
            if (document.length > 256 * 1024) throw failure("INVALID_INPUT", "工程超过 256 KiB");
            Path input = attempt.resolve("input.json");
            Files.write(input, document, StandardOpenOption.CREATE_NEW);
            check(cancelled);
            succeeded = true;
            return new FixedProjectRenderService.Request(input, runId, attemptId, storyboardId, inputHash);
        } catch (IOException exception) { throw failure("ASSET_MISSING", "无法建立故事板工作目录或下载素材"); }
        finally {
            if (!succeeded && attempt != null) cleanup(attempt);
            if (!succeeded && madeRun && run != null) try { Files.deleteIfExists(run); } catch (IOException ignored) { }
        }
    }

    private String digest(Path file, BooleanSupplier cancelled) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) { check(cancelled); digest.update(buffer, 0, count); }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static void cleanup(Path owned) {
        // owned 只在本次 createDirectory 成功后赋值，既有工程不会因失败而被清理。
        try (var paths = Files.walk(owned)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) { }
    }
    private static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) throw failure("INVALID_INPUT", "工程业务身份无效");
        return value;
    }
    private static void check(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw failure("CANCELLED", "故事板工程准备已取消");
    }
    private static RenderExecutionException failure(String code, String message) { return new RenderExecutionException(code, message); }
}
