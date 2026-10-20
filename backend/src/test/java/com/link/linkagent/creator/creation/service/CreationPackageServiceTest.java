package com.link.linkagent.creator.creation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.mapper.CreationPackageMapper;
import com.link.linkagent.creator.creation.mapper.GeneratedDraftMapper;
import com.link.linkagent.creator.creation.model.CreationMediaLink;
import com.link.linkagent.creator.creation.model.CreationPackageRecord;
import com.link.linkagent.creator.creation.model.GeneratedDraftAttempt;
import com.link.linkagent.creator.creation.model.GeneratedDraftInput;
import com.link.linkagent.creator.creation.model.GeneratedDraftLease;
import com.link.linkagent.creator.creation.model.GeneratedDraftObject;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewResponse;
import com.link.linkagent.creator.media.preflight.service.PreflightReviewService;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.storage.StoredObjectMetadata;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 发布包只接受与登记清单一致的产物；导出不等同于已发布。 */
class CreationPackageServiceTest {
    private static final List<String> NAMES = List.of("video.mp4", "subtitles.srt", "cover.png", "frame-start.png",
            "frame-middle.png", "frame-end.png", "render.log", "manifest.json");

    final CreationPackageMapper packages = mock(CreationPackageMapper.class);
    final GeneratedDraftMapper drafts = mock(GeneratedDraftMapper.class);
    final MediaUploadMapper media = mock(MediaUploadMapper.class);
    final ObjectStorageService storage = mock(ObjectStorageService.class);
    final PreflightReviewService preflight = mock(PreflightReviewService.class);
    final CreationRuntimeService runtime = mock(CreationRuntimeService.class);
    final GeneratedDraftLease lease = new GeneratedDraftLease("task", "default", "run", "media-worker", 3);
    final CreationMediaLink link = new CreationMediaLink("run", "task", "default", "version", "job-1", "review-1",
            "PACKAGE_PENDING", 3, null);

    @TempDir Path source;
    Path uploaded;
    String uploadedKey;
    CreationPackageRecord stored;

    @BeforeEach void setup() throws IOException {
        when(runtime.requireProductionLease(lease)).thenReturn(new GeneratedDraftInput("session", "result", "plan",
                null, "board", "c".repeat(64)));
        when(media.findDraftVideoByVersion("task", "default", "version")).thenReturn(Optional.of(draft()));
        when(drafts.contextSnapshot("task", "default", "version")).thenReturn(Optional.of("""
                {"publishingSessionId":"session","publishingResultId":"result","productionPlanId":"plan",
                 "audience":"新手UP","coreMessage":"三分钟讲清一个技巧",
                 "publishingPlan":{"titles":["标题一","标题二"],"description":"简介正文"}}
                """));
        when(preflight.get("default", "task", "review-1")).thenReturn(review());
        when(packages.find("run")).thenReturn(Optional.empty());
        when(packages.begin(any())).thenReturn(1);
        when(packages.ready(any())).thenAnswer(call -> {
            stored = call.getArgument(0); return 1;
        });
        when(packages.fail(anyString(), anyLong(), anyString())).thenReturn(1);
        when(storage.headObject(anyString(), anyString())).thenAnswer(call ->
                new StoredObjectMetadata(Files.size(uploaded), "application/zip", "etag"));
        doAnswer(call -> {
            // 服务会在 finally 删除本地工作目录，测试先复制一份用于断言真实包内容。
            Path built = call.getArgument(2);
            uploaded = Files.createTempFile(source, "package-test-", ".zip");
            Files.copy(built, uploaded, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            uploadedKey = call.getArgument(1);
            return null;
        }).when(storage).putObject(anyString(), anyString(), any(), anyString());
    }

    @Test void buildsVerifiedZipWithPublishingCopyAndExplicitUnpublishedState() throws Exception {
        writeSource(false);
        var ready = service().ensure(lease, link, () -> false);

        assertThat(ready.status()).isEqualTo("READY");
        assertThat(ready.objectKey()).endsWith("/versions/version/package/publish-package.zip");
        assertThat(ready.sha256()).hasSize(64);
        assertThat(ready.bytes()).isEqualTo(Files.size(uploaded));
        assertThat(ready.manifestJson()).contains("\"published\":false", "\"preflightBlockers\":1", "三分钟讲清一个技巧");

        assertThat(stored).isNotNull();
        try (var zip = new ZipFile(uploaded.toFile())) {
            var names = zip.stream().map(entry -> entry.getName()).toList();
            assertThat(names).containsExactlyInAnyOrderElementsOf(
                    List.of("video.mp4", "subtitles.srt", "cover.png", "frame-start.png", "frame-middle.png",
                            "frame-end.png", "render.log", "manifest.json", "publishing.md", "package-manifest.json"));
            var publishing = new String(zip.getInputStream(zip.getEntry("publishing.md")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(publishing).contains("标题一", "简介正文", "未发布", "三分钟讲清一个技巧");
            var manifest = new String(zip.getInputStream(zip.getEntry("package-manifest.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(manifest).contains("\"versionId\":\"version\"", "\"published\":false");
        }
    }

    @Test void tamperedObjectIsRejectedAndBuildMarkedFailed() throws Exception {
        writeSource(true);
        assertThatThrownBy(() -> service().ensure(lease, link, () -> false))
                .hasMessageContaining("与登记清单不一致");
        verify(packages).fail("run", 3L, "PACKAGE_BUILD_FAILED");
        verify(storage, never()).putObject(anyString(), anyString(), any(), anyString());
    }

    @Test void readyPackageIsReusedWithoutDownloadingAgain() throws Exception {
        writeSource(false);
        var existing = new CreationPackageRecord("run", "task", "default", "version", "job-1", "review-1", "READY",
                "bucket", "key", 10L, "f".repeat(64), "{}", 3, null);
        when(packages.find("run")).thenReturn(Optional.of(existing));
        assertThat(service().ensure(lease, link, () -> false)).isSameAs(existing);
        verifyNoInteractions(storage);
        verify(packages, never()).begin(any());
    }

    private CreationPackageService service() {
        return new CreationPackageService(packages, drafts, media, storage, preflight, runtime, new ObjectMapper());
    }

    /** 按登记清单生成八个真实文件；tamper 时让最后一个文件的字节与摘要不一致。 */
    private void writeSource(boolean tamper) throws IOException {
        var objects = new ArrayList<GeneratedDraftObject>();
        for (var name : NAMES) {
            var content = ("内容-" + name).getBytes(StandardCharsets.UTF_8);
            var path = source.resolve(name);
            Files.write(path, content);
            objects.add(new GeneratedDraftObject(name, "users/default/tasks/task/versions/version/rendered/" + name,
                    contentType(name), content.length, sha256(content)));
        }
        if (tamper) {
            // 清单已冻结，磁盘内容被替换后仍必须按摘要拒绝。
            Files.write(source.resolve("video.mp4"), "被替换的视频".getBytes(StandardCharsets.UTF_8));
        }
        var json = new ObjectMapper();
        var attempt = new GeneratedDraftAttempt("run", "attempt-1", "task", "default", 3, "version", "plan",
                "a".repeat(64), "b".repeat(64), "bucket", writeJson(json, objects), "REGISTERED");
        when(drafts.registered("task", "default", "run")).thenReturn(Optional.of(attempt));
        doAnswer(call -> {
            String key = call.getArgument(1);
            Path target = call.getArgument(2);
            Files.copy(source.resolve(key.substring(key.lastIndexOf('/') + 1)), target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return null;
        }).when(storage).downloadObject(anyString(), anyString(), any());
    }

    private static String writeJson(ObjectMapper json, Object value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private static String contentType(String name) {
        return name.endsWith(".mp4") ? "video/mp4" : name.endsWith(".png") ? "image/png"
                : name.endsWith(".srt") ? "application/x-subrip" : name.endsWith(".json") ? "application/json"
                : "text/plain";
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static DraftVideoRecord draft() {
        return new DraftVideoRecord(null, "version", "task", "default", 2, "自动制作初稿", "generated.mp4",
                "bucket", "users/default/tasks/task/versions/version/rendered/video.mp4", "video/mp4", 1024L,
                84167L, 1920, 1080, new BigDecimal("24.000000"), "h264", "aac", true, null,
                "READY_FOR_REVIEW", null, null, null);
    }

    private static PreflightReviewResponse review() {
        var issue = new PreflightReviewResponse.Issue("issue-1", "PACING", "节奏", "开场偏慢", "描述", 0, 1000,
                "BLOCKER", new BigDecimal("0.8"), List.of(), "建议", false, List.of(), "PENDING", null);
        return new PreflightReviewResponse("review-1", "task", "version", "COMPLETED", "DONE", 100, 0, false, 1, 3,
                null, "整体可看", new BigDecimal("0.02"), new BigDecimal("0.017"), 60L, "USD", null, null,
                LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(),
                List.of(), List.of(), List.of(issue), List.of(), List.of());
    }
}
