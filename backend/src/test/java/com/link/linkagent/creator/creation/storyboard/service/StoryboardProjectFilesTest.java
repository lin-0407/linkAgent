package com.link.linkagent.creator.creation.storyboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.render.FixedProjectRendererProperties;
import com.link.linkagent.creator.creation.service.CreationInputRules;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.media.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoryboardProjectFilesTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    final ObjectStorageService storage = mock(ObjectStorageService.class);
    final byte[] bytes = StoryboardCompiler.PLACEHOLDER.clone();

    @Test void materializationDownloadsHistoricalBucketAndVerifiesFullBytes() throws Exception {
        var asset = image(); var project = project(asset);
        when(storage.headObject(asset.bucketName(), asset.objectKey())).thenReturn(new StoredObjectMetadata(bytes.length, "image/png", "not-sha256"));
        doAnswer(i -> { Files.write(i.getArgument(2, Path.class), bytes); return null; }).when(storage)
                .downloadObject(eq("historical-private-bucket"), eq("frozen/normalized/photo.png"), any(Path.class), any());
        var request = files().materialize(project, List.of(asset), () -> false);
        assertThat(request.input()).isEqualTo(root.resolve("run/attempt/input.json"));
        assertThat(Files.readAllBytes(root.resolve("run/attempt/assets/photo.png"))).isEqualTo(bytes);
        assertThat(json.readTree(request.input().toFile())).isEqualTo(project);
        verify(storage).downloadObject(eq(asset.bucketName()), eq(asset.objectKey()), eq(root.resolve("run/attempt/assets/photo.png")), any());
        verify(storage, never()).presignGetObject(any(), any(), any());
    }

    @Test void corruptedDownloadedContentIsRejectedAndOnlyOwnAttemptIsRemoved() throws Exception {
        var asset = image(); var project = project(asset);
        Files.createDirectories(root.resolve("run/old-attempt")); Files.writeString(root.resolve("run/old-attempt/keep.txt"), "保留作者已有工程");
        when(storage.headObject(asset.bucketName(), asset.objectKey())).thenReturn(new StoredObjectMetadata(bytes.length, "image/png", "etag"));
        doAnswer(i -> { byte[] wrong = bytes.clone(); wrong[10]++; Files.write(i.getArgument(2, Path.class), wrong); return null; }).when(storage).downloadObject(any(), any(), any(), any());
        assertThatThrownBy(() -> files().materialize(project, List.of(asset), () -> false)).hasMessageContaining("冻结摘要");
        assertThat(root.resolve("run/attempt")).doesNotExist();
        assertThat(Files.readString(root.resolve("run/old-attempt/keep.txt"))).isEqualTo("保留作者已有工程");
    }

    @Test void incorrectHeadSizeDoesNotDownload() throws Exception {
        var asset = image(); var project = project(asset);
        when(storage.headObject(asset.bucketName(), asset.objectKey())).thenReturn(new StoredObjectMetadata(1, "image/png", "etag"));
        assertThatThrownBy(() -> files().materialize(project, List.of(asset), () -> false)).hasMessageContaining("大小");
        verify(storage, never()).downloadObject(any(), any(), any(), any());
        assertThat(root.resolve("run")).doesNotExist();
    }

    @Test void resignedPathTraversalIsRefusedWithoutDeletingSiblingDirectory() throws Exception {
        var asset = image(); var project = project(asset);
        ((ObjectNode)project.path("assets").get(0)).put("path", "../outside.png"); resign(project);
        Files.writeString(root.resolve("keep.txt"), "existing");
        assertThatThrownBy(() -> files().materialize(project, List.of(asset), () -> false)).hasMessageContaining("路径");
        assertThat(Files.readString(root.resolve("keep.txt"))).isEqualTo("existing");
        assertThat(root.resolve("run")).doesNotExist();
        verifyNoInteractions(storage);
    }

    @Test void existingAttemptIsNeverOverwrittenOrDeleted() throws Exception {
        var asset = image(); var project = project(asset);
        Files.createDirectories(root.resolve("run/attempt")); Files.writeString(root.resolve("run/attempt/keep.txt"), "existing");
        assertThatThrownBy(() -> files().materialize(project, List.of(asset), () -> false)).hasMessageContaining("工作目录");
        assertThat(Files.readString(root.resolve("run/attempt/keep.txt"))).isEqualTo("existing");
        verifyNoInteractions(storage);
    }

    @Test void cancellationAfterDownloadCleansOnlyNewAttempt() throws Exception {
        var asset = image(); var project = project(asset); AtomicInteger cancelled = new AtomicInteger();
        when(storage.headObject(asset.bucketName(), asset.objectKey())).thenReturn(new StoredObjectMetadata(bytes.length, "image/png", "etag"));
        doAnswer(i -> { Files.write(i.getArgument(2, Path.class), bytes); cancelled.incrementAndGet(); return null; }).when(storage).downloadObject(any(), any(), any(), any());
        assertThatThrownBy(() -> files().materialize(project, List.of(asset), () -> cancelled.get() > 0)).hasMessageContaining("取消");
        assertThat(root.resolve("run")).doesNotExist();
    }

    @Test void silentBoardMaterializesOnlyExplicitSystemPlaceholderWithoutStorageAccess() throws Exception {
        var compiler = new StoryboardCompiler(json);
        var candidate = new Candidate(List.of(StoryboardCompilerTest.scene("s1", "TITLE_CARD", 1000, null, null, null, "NO_DIALOGUE", null, List.of())));
        var project = compiler.compile("board", json.createObjectNode().put("targetDurationMs", 1000).put("dialogueMode", "NO_DIALOGUE"), candidate, List.of(), "run", "attempt");
        var request = files().materialize(project, List.of(), () -> false);
        assertThat(Files.readAllBytes(request.input().getParent().resolve("assets/system-static-placeholder.png"))).isEqualTo(StoryboardCompiler.PLACEHOLDER);
        verifyNoInteractions(storage);
    }

    @Test void projectCannotChangeItsFrozenHash() {
        var asset = image(); var project = project(asset); project.put("seed", 8);
        assertThatThrownBy(() -> files().materialize(project, List.of(asset), () -> false)).hasMessageContaining("摘要");
        assertThat(root.resolve("run")).doesNotExist(); verifyNoInteractions(storage);
    }

    @Test void discardRequiresMatchingStoredIdentityAndPreservesOtherAttempts() throws Exception {
        var compiler = new StoryboardCompiler(json);
        var candidate = new Candidate(List.of(StoryboardCompilerTest.scene("s1", "TITLE_CARD", 1000, null, null, null, "NO_DIALOGUE", null, List.of())));
        var project = compiler.compile("board", json.createObjectNode().put("targetDurationMs", 1000).put("dialogueMode", "NO_DIALOGUE"), candidate, List.of(), "run", "attempt");
        var files = files(); var request = files.materialize(project, List.of(), () -> false);
        Files.createDirectory(root.resolve("run/sibling")); Files.writeString(root.resolve("run/sibling/keep"), "existing");
        var wrong = new com.link.linkagent.creator.creation.render.FixedProjectRenderService.Request(request.input(), request.runId(), request.attemptId(), "wrong-board", request.inputHash());
        assertThatThrownBy(() -> files.discard(wrong)).hasMessageContaining("身份");
        assertThat(request.input()).exists();
        files.discard(request); files.discard(request);
        assertThat(root.resolve("run/attempt")).doesNotExist();
        assertThat(Files.readString(root.resolve("run/sibling/keep"))).isEqualTo("existing");
    }

    @Test void discardCannotUseAnUnrelatedFileEvenWithValidBusinessIds() throws Exception {
        Path outside = root.resolve("keep.json"); Files.writeString(outside, "{}");
        var request = new com.link.linkagent.creator.creation.render.FixedProjectRenderService.Request(outside, "run", "attempt", "board", "a".repeat(64));
        assertThatThrownBy(() -> files().discard(request)).hasMessageContaining("路径");
        assertThat(outside).exists();
    }

    @Test void selectedMusicCanDownloadBeyondThirtyTwoMibAndStillVerifiesEntireFrozenFile() throws Exception {
        // 稀疏字节夹具只验证文件上限与完整复制，不把这份夹具当成可解码音乐。
        Path fixture = root.resolve("music-fixture.wav");
        try (var file = new RandomAccessFile(fixture.toFile(), "rw")) { file.setLength(32L * 1024 * 1024 + 1); }
        String digest = digest(fixture); var asset = music(Files.size(fixture), digest);
        var project = musicProject(asset);
        when(storage.headObject(asset.bucketName(), asset.objectKey())).thenReturn(new StoredObjectMetadata(asset.bytes(), "audio/wav", "etag"));
        doAnswer(i -> { Files.copy(fixture, i.getArgument(2, Path.class)); return null; }).when(storage).downloadObject(any(), any(), any(), any());
        var request = files().materialize(project, List.of(asset), () -> false);
        Path result = request.input().getParent().resolve("assets/music.wav");
        assertThat(Files.size(result)).isEqualTo(asset.bytes()); assertThat(digest(result)).isEqualTo(digest);
        files().discard(request); assertThat(fixture).exists();
    }

    @Test void backgroundNameCannotRaiseNarrationLimitAndMusicCannotExceedSixtyFourMib() {
        var music = music(32L * 1024 * 1024 + 1, "d".repeat(64));
        var project = musicProject(music);
        var wrongKind = new Asset(music.assetId(), "NARRATION", music.bucketName(), music.objectKey(), music.sha256(), music.bytes(), music.contentType(), music.metadata());
        assertThatThrownBy(() -> files().materialize(project, List.of(wrongKind), () -> false)).hasMessageContaining("冻结私有对象");
        var tooLarge = music(64L * 1024 * 1024 + 1, "d".repeat(64));
        ((ObjectNode)project.path("assets").get(0)).put("sha256", tooLarge.sha256()); resign(project);
        assertThatThrownBy(() -> files().materialize(project, List.of(tooLarge), () -> false)).hasMessageContaining("冻结私有对象");
        verifyNoInteractions(storage);
    }

    private ObjectNode musicProject(Asset asset) {
        var candidate = new Candidate(List.of(StoryboardCompilerTest.scene("silent", "TITLE_CARD", 1000, null, null, null, "NO_DIALOGUE", null, List.of())));
        var brief = json.createObjectNode().put("targetDurationMs", 1000).put("dialogueMode", "NO_DIALOGUE");
        brief.putObject("backgroundMusic").put("assetId", "music").put("sourceInMs", 0).put("sourceOutMs", 1000)
                .put("gainDb", -12).put("fadeInMs", 0).put("fadeOutMs", 0).put("ducking", false);
        // 超限场景从合法工程改声明，文件边界必须独立拒绝，不能依赖编译器的前置检查。
        var accepted = music(Math.min(asset.bytes(), 64L * 1024 * 1024), asset.sha256());
        return new StoryboardCompiler(json).compile("board", brief, candidate, List.of(accepted), "run", "attempt");
    }
    private Asset music(long size, String digest) {
        ObjectNode metadata = json.createObjectNode(); var normalized = metadata.putObject("normalized"); normalized.putObject("format").put("duration", 2);
        normalized.putArray("streams").addObject().put("codec_type", "audio").put("codec_name", "pcm_s16le").put("sample_rate", "48000").put("channels", 2).put("duration", 2);
        return new Asset("music", "MUSIC", "historical-private-bucket", "frozen/normalized/music.wav", digest, size, "audio/wav", metadata);
    }
    private String digest(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) { byte[] buffer = new byte[65536]; int count; while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count); }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Asset image() {
        ObjectNode metadata = json.createObjectNode(); metadata.putObject("normalized").putArray("streams").addObject().put("codec_type", "video").put("codec_name", "png").put("width", 1).put("height", 1);
        return new Asset("photo", "IMAGE", "historical-private-bucket", "frozen/normalized/photo.png", StoryboardCompiler.sha256(bytes), bytes.length, "image/png", metadata);
    }
    private ObjectNode project(Asset asset) {
        var candidate = new Candidate(List.of(StoryboardCompilerTest.scene("s1", "IMAGE_CARD", 1000, "photo", null, null, "NO_DIALOGUE", null, List.of())));
        return new StoryboardCompiler(json).compile("board", json.createObjectNode().put("targetDurationMs", 1000).put("dialogueMode", "NO_DIALOGUE"), candidate, List.of(asset), "run", "attempt");
    }
    private StoryboardProjectFiles files() {
        var properties = new FixedProjectRendererProperties(); properties.setEnabled(true); properties.setWorkRoot(root.toString());
        return new StoryboardProjectFiles(properties, storage, json);
    }
    private void resign(ObjectNode project) { project.put("inputHash", CreationInputRules.renderInputHash(json, project)); }
}
