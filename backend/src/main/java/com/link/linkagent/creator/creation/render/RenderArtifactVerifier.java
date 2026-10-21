package com.link.linkagent.creator.creation.render;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/** 清单是索引，文件本身才是产物；逐个核验，不能仅凭退出码或文件存在接收。 */
final class RenderArtifactVerifier {
    private static final Map<String, Long> LIMITS = Map.of(
            "video.mp4", 512L * 1024 * 1024, "subtitles.srt", 256L * 1024,
            "cover.png", 8L * 1024 * 1024, "frame-start.png", 8L * 1024 * 1024,
            "frame-middle.png", 8L * 1024 * 1024, "frame-end.png", 8L * 1024 * 1024,
            "render.log", 1024L * 1024);
    private final ObjectMapper json;

    RenderArtifactVerifier(ObjectMapper mapper) {
        json = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(24).maxStringLength(65536).maxNumberLength(100).build());
    }

    JsonNode input(Path path, FixedProjectRenderService.Request request) throws IOException {
        JsonNode input = read(path, 256 * 1024);
        identity(input, request);
        require(Set.of(1, 2).contains(integer(input, "schemaVersion")), "不支持此工程版本");
        JsonNode video = input.path("video");
        int width = integer(video, "width"), height = integer(video, "height");
        int fps = integer(video, "fps"), frames = integer(video, "frameCount");
        require(((width == 1280 && height == 720) || (width == 1920 && height == 1080))
                && Set.of(24, 25, 30).contains(fps) && frames >= fps && frames <= fps * 180,
                "工程画幅或时间线不受支持");
        return input;
    }

    RenderArtifacts verify(Path output, JsonNode input, FixedProjectRenderService.Request request,
                           Runnable check) throws IOException {
        check.run();
        require(Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(output),
                "缺少本次渲染目录");
        Path manifestPath = file(output, "manifest.json", 65536);
        JsonNode manifest = read(manifestPath, 65536);
        identity(manifest, request);
        require(integer(manifest, "schemaVersion") == 1, "不支持此产物清单版本");
        if (input.path("schemaVersion").asInt() == 2)
            require(integer(manifest, "inputSchemaVersion") == 2, "产物工程版本不匹配");
        else if (manifest.has("inputSchemaVersion"))
            require(integer(manifest, "inputSchemaVersion") == 1, "产物工程版本不匹配");
        require(manifest.path("rendererVersion").isTextual()
                && manifest.path("rendererVersion").asText().matches("[0-9]+\\.[0-9]+\\.[0-9]+"), "渲染器版本无效");
        require(manifest.path("fontSha256").asText().matches("[0-9a-f]{64}"), "缺少字体摘要");
        JsonNode media = manifest.path("media"), video = input.path("video");
        for (String name : Set.of("width", "height", "fps", "frameCount"))
            require(integer(media, name) == integer(video, name), "产物媒体参数不匹配");
        long duration = Math.round(integer(video, "frameCount") * 1000.0 / integer(video, "fps"));
        require(longValue(manifest, "durationMs") == duration
                && within(longValue(media, "videoDurationMs"), duration, 1000 / integer(video, "fps") + 1)
                && within(longValue(media, "containerDurationMs"), duration, 65)
                && longValue(manifest, "elapsedMs") >= 0, "产物时长不匹配");
        require("h264".equals(media.path("videoCodec").asText())
                && "aac".equals(media.path("audioCodec").asText())
                && integer(media, "audioSampleRate") == 48000
                && media.path("fullDecodePassed").isBoolean() && media.path("fullDecodePassed").booleanValue()
                && longValue(media, "clippedSamples") == 0, "产物未通过本地媒体检查");
        boolean silent = explicitlySilent(input);
        for (String field : Set.of("decodedPeak", "decodedRms", "sourcePeak", "sourceRms")) {
            JsonNode number = media.path(field);
            require(number.isNumber() && Double.isFinite(number.doubleValue())
                    && (silent ? number.doubleValue() >= 0 && number.doubleValue() <= .0001 : number.doubleValue() > 0)
                    && number.doubleValue() <= 1, "产物音轨检查无效");
        }
        require((silent || media.path("decodedPeak").doubleValue() > .0001)
                && media.path("sourcePeak").doubleValue() <= .95, "产物音轨无效");
        JsonNode declared = manifest.path("files");
        require(declared.isObject() && keys(declared).equals(LIMITS.keySet()), "产物文件清单不完整");
        Map<String, RenderArtifacts.Artifact> artifacts = new HashMap<>();
        for (var entry : LIMITS.entrySet()) {
            check.run();
            String name = entry.getKey();
            Path artifact = file(output, name, entry.getValue());
            JsonNode item = declared.path(name);
            require(name.equals(item.path("name").asText())
                    && longValue(item, "bytes") == Files.size(artifact), "产物文件长度不匹配");
            String actual = digest(artifact, check);
            require(actual.equals(item.path("sha256").asText()), "产物文件摘要不匹配");
            artifacts.put(name, new RenderArtifacts.Artifact(artifact, Files.size(artifact), actual, contentType(name)));
        }
        artifacts.put("manifest.json", new RenderArtifacts.Artifact(manifestPath, Files.size(manifestPath),
                digest(manifestPath, check), "application/json"));
        check.run();
        return new RenderArtifacts(output, artifacts, request.inputHash(), manifest.path("rendererVersion").asText(),
                duration, integer(video, "width"), integer(video, "height"), integer(video, "fps"), integer(video, "frameCount"));
    }

    private boolean explicitlySilent(JsonNode input) {
        if (input.path("schemaVersion").asInt() != 2 || !input.path("scenes").isArray() || input.path("scenes").isEmpty()) return false;
        // 无对白仍可播放用户明确选择的背景音乐，不能按场景 voice 为空误判为整片静音。
        if (input.hasNonNull("backgroundMusic")) return false;
        // 静音必须来自每个场景的明确选择，不能靠清单中的一个标记放宽所有音轨检查。
        for (JsonNode scene : input.path("scenes")) {
            if (!scene.has("voice") || !scene.path("voice").isNull()) return false;
            if ("VIDEO_CLIP".equals(scene.path("layout").asText())
                    && !"MUTE".equals(scene.path("clip").path("audioMode").asText())) return false;
        }
        return true;
    }

    static String digest(Path file, Runnable check) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int size;
                while ((size = stream.read(buffer)) != -1) { check.run(); digest.update(buffer, 0, size); }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private JsonNode read(Path path, int limit) throws IOException {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) > 0 && Files.size(path) <= limit,
                "工程或清单文件无效");
        // 有界读取也覆盖检查后文件变大的情况，避免把本地异常文件读入堆。
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(limit + 1);
            require(bytes.length <= limit, "工程或清单超过限制");
            JsonNode node = json.readTree(bytes);
            require(node != null && node.isObject(), "工程或清单必须是 JSON 对象");
            return node;
        }
    }

    private Path file(Path output, String name, long maximum) throws IOException {
        Path file = output.resolve(name);
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)
                && Files.size(file) > 0 && Files.size(file) <= maximum
                && file.toRealPath().getParent().equals(output.toRealPath()), "缺少产物或产物路径无效");
        return file;
    }

    private void identity(JsonNode node, FixedProjectRenderService.Request request) {
        require(request.runId().equals(node.path("runId").asText())
                && request.attemptId().equals(node.path("attemptId").asText())
                && request.storyboardId().equals(node.path("storyboardId").asText())
                && request.inputHash().equals(node.path("inputHash").asText()), "工程或产物不属于本次尝试");
    }

    private int integer(JsonNode node, String field) {
        require(node.path(field).isIntegralNumber() && node.path(field).canConvertToInt(), "清单整数字段无效");
        return node.path(field).intValue();
    }

    private long longValue(JsonNode node, String field) {
        require(node.path(field).isIntegralNumber() && node.path(field).canConvertToLong(), "清单整数字段无效");
        return node.path(field).longValue();
    }

    private boolean within(long actual, long expected, long tolerance) {
        return actual >= expected - tolerance && actual <= expected + tolerance;
    }

    private Set<String> keys(JsonNode node) {
        Set<String> keys = new HashSet<>(); node.fieldNames().forEachRemaining(keys::add); return keys;
    }

    private static String contentType(String name) {
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".png")) return "image/png";
        return name.endsWith(".srt") ? "application/x-subrip" : "text/plain";
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new RenderExecutionException("INVALID_ARTIFACT", message);
    }
}
