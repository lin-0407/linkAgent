package com.link.linkagent.creator.creation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.mapper.CreationPackageMapper;
import com.link.linkagent.creator.creation.mapper.GeneratedDraftMapper;
import com.link.linkagent.creator.creation.model.CreationMediaLink;
import com.link.linkagent.creator.creation.model.CreationPackageRecord;
import com.link.linkagent.creator.creation.model.GeneratedDraftLease;
import com.link.linkagent.creator.creation.model.GeneratedDraftObject;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewResponse;
import com.link.linkagent.creator.media.preflight.service.PreflightReviewService;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.storage.StoredObjectMetadata;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.link.linkagent.creator.creation.service.CreationInputRules.conflict;
import static com.link.linkagent.creator.creation.service.CreationInputRules.id;

/**
 * 最小真实发布包。
 * <p>
 * 只打包本次运行登记并通过对象校验的八个产物，加上投稿文案、来源和包清单；
 * 导出不等于已发布，实际投稿仍由作者在 B 站完成。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = {"creator.media.enabled", "creator.creation.renderer.enabled"}, havingValue = "true")
public class CreationPackageService {
    private static final Logger log = LoggerFactory.getLogger(CreationPackageService.class);
    private static final List<String> VIDEO_FILES = List.of("video.mp4");
    private static final String PUBLISHING_FILE = "publishing.md";
    private static final String PACKAGE_MANIFEST = "package-manifest.json";
    private static final String NOTICE = "本包只包含导出结果；实际投稿、BV 绑定和观众反馈仍由作者在 B 站完成。";

    private final CreationPackageMapper packages;
    private final GeneratedDraftMapper drafts;
    private final MediaUploadMapper media;
    private final ObjectStorageService storage;
    private final PreflightReviewService preflight;
    private final CreationRuntimeService runtime;
    private final ObjectMapper json;

    public CreationPackageService(CreationPackageMapper packages, GeneratedDraftMapper drafts, MediaUploadMapper media,
                                  ObjectStorageService storage, PreflightReviewService preflight,
                                  CreationRuntimeService runtime, ObjectMapper json) {
        this.packages = packages; this.drafts = drafts; this.media = media; this.storage = storage;
        this.preflight = preflight; this.runtime = runtime; this.json = json;
    }

    /**
     * 已 READY 的包直接复用，重复扫描不会重新下载或重新编码；
     * 只有未完成的构建才在本地重建并由新代数登记。
     */
    public CreationPackageRecord ensure(GeneratedDraftLease lease, CreationMediaLink link, BooleanSupplier cancelled) {
        runtime.requireProductionLease(lease);
        var attempt = drafts.registered(lease.taskId(), lease.ownerId(), lease.runId())
                .orElseThrow(() -> conflict("本次运行尚未登记系统成片"));
        if (!attempt.versionId().equals(link.versionId())) throw conflict("发布包版本与媒体闭环不一致");
        var existing = packages.find(lease.runId());
        if (existing.isPresent() && "READY".equals(existing.get().status())) return existing.get();
        var draft = media.findDraftVideoByVersion(lease.taskId(), lease.ownerId(), link.versionId())
                .orElseThrow(() -> conflict("成片版本不存在"));
        if (draft.mediaDeletedAt() != null) throw conflict("成片媒体已删除，不能生成发布包");

        var context = drafts.contextSnapshot(lease.taskId(), lease.ownerId(), link.versionId())
                .orElseThrow(() -> conflict("成片缺少不可变制作上下文"));
        var review = preflight.get(lease.ownerId(), lease.taskId(), link.preflightReviewId());
        var objects = objects(attempt.objectsJson());
        var beginning = new CreationPackageRecord(lease.runId(), lease.taskId(), lease.ownerId(), link.versionId(),
                link.processingJobId(), link.preflightReviewId(), "BUILDING", null, null, null, null,
                "{}", lease.generation(), null);
        packages.begin(beginning);
        Path work = null;
        try {
            work = Files.createTempDirectory("linkagent-package-");
            var files = downloadVerified(attempt.bucketName(), objects, work, cancelled);
            var published = Files.writeString(work.resolve(PUBLISHING_FILE),
                    publishingMarkdown(draft, review, context, files), StandardCharsets.UTF_8);
            var manifestJson = packageManifest(lease, link, draft, review, context, files, attempt);
            var manifestPath = Files.writeString(work.resolve(PACKAGE_MANIFEST), manifestJson, StandardCharsets.UTF_8);
            var zip = work.resolve("publish-package.zip");
            zip(files, List.of(published, manifestPath), zip);
            check(cancelled);
            long size = Files.size(zip);
            String digest = sha256(zip);
            String objectKey = "users/" + id(lease.ownerId()) + "/tasks/" + id(lease.taskId()) + "/versions/"
                    + id(link.versionId()) + "/package/publish-package.zip";
            storage.putObject(attempt.bucketName(), objectKey, zip, "application/zip");
            check(cancelled);
            StoredObjectMetadata metadata = storage.headObject(attempt.bucketName(), objectKey);
            if (metadata.contentLength() != size)
                throw conflict("发布包对象大小与本地构建不一致，已拒绝登记");
            var ready = new CreationPackageRecord(lease.runId(), lease.taskId(), lease.ownerId(), link.versionId(),
                    link.processingJobId(), link.preflightReviewId(), "READY", attempt.bucketName(), objectKey,
                    size, digest, manifestJson, lease.generation(), null);
            if (packages.ready(ready) != 1) throw conflict("发布包状态已变化，不能采纳旧代构建结果");
            return ready;
        } catch (IOException exception) {
            packages.fail(lease.runId(), lease.generation(), "PACKAGE_IO_FAILED");
            throw conflict("发布包构建失败，请检查本地工作目录和媒体对象");
        } catch (RuntimeException exception) {
            packages.fail(lease.runId(), lease.generation(), "PACKAGE_BUILD_FAILED");
            throw exception;
        } finally {
            deleteTree(work);
        }
    }

    /** 只有本地摘要与登记清单一致的产物才允许进入包；缺失或篡改一律拒绝。 */
    private Map<String, Path> downloadVerified(String bucket, List<GeneratedDraftObject> objects, Path work,
                                               BooleanSupplier cancelled) throws IOException {
        var files = new LinkedHashMap<String, Path>();
        for (var object : objects) {
            check(cancelled);
            Path target = work.resolve(object.name());
            storage.downloadObject(bucket, object.objectKey(), target);
            long bytes = Files.size(target);
            String digest = sha256(target);
            if (bytes != object.bytes() || !digest.equals(object.sha256()))
                throw conflict("发布包产物与登记清单不一致：" + object.name());
            files.put(object.name(), target);
        }
        if (files.size() != objects.size()) throw conflict("发布包产物不完整");
        return files;
    }

    private void zip(Map<String, Path> files, List<Path> textFiles, Path target) throws IOException {
        var entries = new TreeMap<String, Path>();
        entries.putAll(files);
        for (var path : textFiles) entries.put(path.getFileName().toString(), path);
        try (OutputStream out = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                boolean store = VIDEO_FILES.contains(entry.getKey());
                var zipEntry = new ZipEntry(entry.getKey());
                if (store) {
                    // 视频已是压缩格式，按存储方式写入，避免整片重新压缩消耗时间。
                    zipEntry.setMethod(ZipEntry.STORED);
                    zipEntry.setSize(Files.size(entry.getValue()));
                    zipEntry.setCrc(crc(entry.getValue()));
                } else {
                    zipEntry.setMethod(ZipEntry.DEFLATED);
                }
                zip.putNextEntry(zipEntry);
                Files.copy(entry.getValue(), zip);
                zip.closeEntry();
            }
        }
    }

    private String packageManifest(GeneratedDraftLease lease, CreationMediaLink link, DraftVideoRecord draft,
                                   PreflightReviewResponse review, String context, Map<String, Path> files,
                                   com.link.linkagent.creator.creation.model.GeneratedDraftAttempt attempt) {
        ObjectNode manifest = json.createObjectNode();
        manifest.put("schemaVersion", 1);
        manifest.put("generatedAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        manifest.put("taskId", lease.taskId()).put("runId", lease.runId()).put("versionId", link.versionId())
                .put("attemptId", attempt.attemptId()).put("inputHash", attempt.inputHash())
                .put("bundleHash", attempt.bundleHash()).put("published", false).put("notice", NOTICE);
        ObjectNode video = manifest.putObject("video");
        video.put("file", "video.mp4").put("contentType", draft.contentType()).put("bytes", draft.fileSize());
        if (draft.durationMs() != null) video.put("durationMs", draft.durationMs());
        if (draft.width() != null) video.put("width", draft.width());
        if (draft.height() != null) video.put("height", draft.height());
        if (draft.videoCodec() != null) video.put("videoCodec", draft.videoCodec());
        if (draft.audioCodec() != null) video.put("audioCodec", draft.audioCodec());
        video.put("hasAudio", Boolean.TRUE.equals(draft.hasAudio()));
        ArrayNode fileList = manifest.putArray("files");
        for (var entry : new TreeMap<>(files).entrySet()) {
            var node = fileList.addObject().put("name", entry.getKey()).put("bytes", sizeOf(entry.getValue()));
            node.put("sha256", sha256Quietly(entry.getValue()));
        }
        fileList.addObject().put("name", PUBLISHING_FILE).put("derived", true);
        fileList.addObject().put("name", PACKAGE_MANIFEST).put("derived", true);
        ObjectNode mediaFacts = manifest.putObject("media");
        mediaFacts.put("processingJobId", link.processingJobId()).put("preflightReviewId", link.preflightReviewId())
                .put("preflightStatus", review.status())
                .put("preflightIssues", review.issues() == null ? 0 : review.issues().size())
                .put("preflightBlockers", countSeverity(review, "BLOCKER"))
                .put("preflightActualCostUsd", review.actualCostUsd() == null ? "0" : review.actualCostUsd().toPlainString());
        try {
            var snapshot = json.readTree(context);
            manifest.set("publishing", snapshot.path("publishingPlan"));
            manifest.put("audience", snapshot.path("audience").asText()).put("coreMessage", snapshot.path("coreMessage").asText());
            manifest.put("contextHash", sha256(context.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException exception) {
            throw conflict("成片制作上下文无法解析");
        }
        return write(manifest);
    }

    private String publishingMarkdown(DraftVideoRecord draft, PreflightReviewResponse review, String context,
                                      Map<String, Path> files) {
        JsonNode snapshot;
        try { snapshot = json.readTree(context); }
        catch (IOException exception) { throw conflict("成片制作上下文无法解析"); }
        var titles = new StringBuilder();
        var plan = snapshot.path("publishingPlan").path("titles");
        if (plan.isArray()) {
            for (JsonNode title : plan) titles.append("- ").append(title.asText()).append('\n');
        }
        if (titles.isEmpty()) titles.append("- （已确认方案未提供候选标题，请作者自行填写）\n");
        StringBuilder text = new StringBuilder();
        text.append("# 投稿文案（未发布）\n\n");
        text.append("> ").append(NOTICE).append("\n\n");
        text.append("## 候选标题\n").append(titles).append('\n');
        text.append("## 简介\n\n").append(snapshot.path("publishingPlan").path("description").asText("（未提供）")).append("\n\n");
        text.append("## 目标观众与核心信息\n\n");
        text.append("- 目标观众：").append(snapshot.path("audience").asText("见已确认发布方案")).append('\n');
        text.append("- 核心信息：").append(snapshot.path("coreMessage").asText("见已确认发布方案")).append("\n\n");
        text.append("## 成片事实\n\n");
        text.append("- 版本标识：").append(draft.versionId()).append('\n');
        text.append("- 时长：").append(draft.durationMs() == null ? "未知" : draft.durationMs() + " ms").append('\n');
        text.append("- 画面：").append(draft.width() == null ? "未知" : draft.width() + "x" + draft.height())
                .append("，编码 ").append(draft.videoCodec() == null ? "未知" : draft.videoCodec()).append('\n');
        text.append("- 音轨：").append(Boolean.TRUE.equals(draft.hasAudio()) ? "有" : "无").append('\n');
        text.append("- 包内文件：").append(String.join("、", new TreeMap<>(files).keySet())).append('\n');
        text.append('\n');
        text.append("## 完整试映摘要\n\n");
        text.append("- 试映任务：").append(review.reviewId()).append("（").append(review.status()).append("）\n");
        text.append("- 问题数：").append(review.issues() == null ? 0 : review.issues().size())
                .append("，其中阻断级 ").append(countSeverity(review, "BLOCKER")).append('\n');
        text.append("- 试映摘要：").append(review.executiveSummary() == null ? "（无）" : review.executiveSummary()).append("\n\n");
        text.append("## 来源\n\n");
        text.append("- 发布方案会话：").append(snapshot.path("publishingSessionId").asText("")).append('\n');
        text.append("- 发布建议结果：").append(snapshot.path("publishingResultId").asText("")).append('\n');
        text.append("- 制作蓝图：").append(snapshot.path("productionPlanId").asText("")).append('\n');
        text.append("- 说明：以上标识指向本次运行冻结的方案与蓝图；包内不包含模型密钥、签名地址或临时链接。\n");
        return text.toString();
    }

    private int countSeverity(PreflightReviewResponse review, String severity) {
        if (review.issues() == null) return 0;
        return (int) review.issues().stream().filter(issue -> severity.equals(issue.severity())).count();
    }

    private List<GeneratedDraftObject> objects(String document) {
        try { return json.readerForListOf(GeneratedDraftObject.class).readValue(document); }
        catch (IOException exception) { throw conflict("已保存的产物清单无效"); }
    }

    private long sizeOf(Path path) {
        try { return Files.size(path); }
        catch (IOException exception) { throw conflict("发布包产物读取失败"); }
    }

    private String sha256Quietly(Path path) {
        try { return sha256(path); }
        catch (IOException exception) { throw conflict("发布包产物摘要计算失败"); }
    }

    static String sha256(Path path) throws IOException {
        var digest = digest();
        try (InputStream input = Files.newInputStream(path); DigestInputStream stream = new DigestInputStream(input, digest)) {
            stream.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] value) {
        return HexFormat.of().formatHex(digest().digest(value));
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static long crc(Path path) throws IOException {
        var crc = new CRC32();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) crc.update(buffer, 0, read);
        }
        return crc.getValue();
    }

    private static void check(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
            throw conflict("发布包构建已停止");
    }

    private static void deleteTree(Path root) {
        if (root == null) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException exception) {
            log.warn("发布包临时目录清理失败");
        }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }
}
