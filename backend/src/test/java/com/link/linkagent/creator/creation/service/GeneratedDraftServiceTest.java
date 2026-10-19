package com.link.linkagent.creator.creation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.model.*;
import com.link.linkagent.creator.creation.render.*;
import com.link.linkagent.creator.media.config.ObjectStorageProperties;
import com.link.linkagent.creator.media.storage.*;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 用协议替身覆盖存储失败分支；真实存储及视频由隔离集成测试验证。 */
class GeneratedDraftServiceTest {
    @TempDir Path root;
    private final FixedProjectRenderService renderer = mock(FixedProjectRenderService.class);
    private final GeneratedDraftPersistenceService persistence = mock(GeneratedDraftPersistenceService.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private GeneratedDraftService service;
    private final GeneratedDraftLease lease = new GeneratedDraftLease("task", "default", "run", "worker", 1);
    private FixedProjectRenderService.Request request;
    private List<GeneratedDraftObject> objects;

    @BeforeEach void setup() throws Exception {
        Map<String, RenderArtifacts.Artifact> artifacts = new HashMap<>();
        objects = new ArrayList<>();
        String sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("fixture-1".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for (String name : List.of("video.mp4", "subtitles.srt", "cover.png", "frame-start.png", "frame-middle.png", "frame-end.png", "render.log", "manifest.json")) {
            Path file = root.resolve(name); Files.writeString(file, "fixture-1");
            artifacts.put(name, new RenderArtifacts.Artifact(file, 9, sha, "test/type"));
            objects.add(new GeneratedDraftObject(name, "backend-key/" + name, "test/type", 9, sha));
        }
        var result = new RenderArtifacts(root, artifacts, "a".repeat(64), "0.1.0", 2000, 1280, 720, 24, 48);
        request = new FixedProjectRenderService.Request(root.resolve("input.json"), "run", "attempt", "board", "a".repeat(64));
        when(renderer.verifyExisting(any(), any())).thenReturn(new FixedProjectRenderService.VerifiedOutput(result, "{\"storyboardId\":\"board\"}"));
        var attempt = new GeneratedDraftAttempt("run", "attempt", "task", "default", 1, "version", "plan", "a".repeat(64), "bundle", "bucket", "[]", "UPLOADING");
        when(persistence.prepare(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyList())).thenReturn(attempt);
        when(persistence.objects(attempt)).thenReturn(objects);
        when(storage.headObject(anyString(), anyString())).thenReturn(new StoredObjectMetadata(9, "test/type", "etag-is-not-sha"));
        doAnswer(call -> { Files.writeString(call.getArgument(2, Path.class), "fixture-1"); return null; })
                .when(storage).downloadObject(anyString(), anyString(), any(Path.class));
        var properties = new ObjectStorageProperties(); properties.setBucket("bucket"); properties.setAccessKey("test"); properties.setSecretKey("test");
        properties.setInternalEndpoint("http://storage.test"); properties.setBrowserEndpoint("http://storage.test"); properties.setProviderEndpoint("http://storage.test");
        service = new GeneratedDraftService(renderer, persistence, storage, properties, new ObjectMapper());
    }

    @Test void rejectsWrongRemoteLengthBeforeVersionRegistration() {
        when(storage.headObject(anyString(), anyString())).thenReturn(new StoredObjectMetadata(8, "test/type", "etag"));
        assertThatThrownBy(() -> service.registerGeneratedDraft(lease, request, () -> false)).hasMessageContaining("长度");
        verify(persistence, never()).register(any(), any());
    }

    @Test void rejectsSameLengthRemoteCorruption() throws Exception {
        doAnswer(call -> { Files.writeString(call.getArgument(2, Path.class), "fixture-2"); return null; })
                .when(storage).downloadObject(anyString(), anyString(), any(Path.class));
        assertThatThrownBy(() -> service.registerGeneratedDraft(lease, request, () -> false)).hasMessageContaining("内容校验");
        verify(persistence, never()).register(any(), any());
        try (var files = Files.list(root)) { assertThat(files.filter(path -> path.getFileName().toString().startsWith("object-check-")).toList()).isEmpty(); }
    }

    @Test void cancellationDuringPutDoesNotRegisterOrStartAnotherUpload() {
        AtomicBoolean cancelled = new AtomicBoolean();
        doAnswer(call -> { cancelled.set(true); return null; }).when(storage).putObject(anyString(), anyString(), any(), anyString());
        assertThatThrownBy(() -> service.registerGeneratedDraft(lease, request, cancelled::get)).hasMessageContaining("取消");
        verify(storage, times(1)).putObject(anyString(), anyString(), any(), anyString());
        verify(persistence, never()).register(any(), any());
    }

    @Test void storageFailureLeavesJournalWithoutRegisteringVideo() {
        doThrow(new MediaStorageException("测试存储不可用", null)).when(storage).putObject(anyString(), anyString(), any(), anyString());
        assertThatThrownBy(() -> service.registerGeneratedDraft(lease, request, () -> false)).isInstanceOf(MediaStorageException.class);
        verify(persistence).prepare(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyList());
        verify(persistence, never()).register(any(), any());
        verify(storage, never()).deleteObject(anyString(), anyString());
    }

    @Test void completeObjectRoundTripsAreRequiredBeforeRegistration() {
        service.registerGeneratedDraft(lease, request, () -> false);
        verify(storage, times(8)).putObject(anyString(), anyString(), any(), anyString());
        verify(storage, times(8)).headObject(anyString(), anyString());
        verify(storage, times(8)).downloadObject(anyString(), anyString(), any());
        verify(persistence).register(lease, "attempt");
    }

    @Test void registeredReplayDoesNotRequireLocalArtifactsOrStorage() {
        var draft = mock(DraftVideoRecord.class);
        when(persistence.restoreRegistered(lease, "attempt", request.inputHash())).thenReturn(Optional.of(draft));
        assertThat(service.registerGeneratedDraft(lease, request, () -> false)).isSameAs(draft);
        verifyNoInteractions(renderer, storage);
    }

    @Test void rejectsAnotherRunsInputBeforeStorageCalls() {
        var other = new FixedProjectRenderService.Request(request.input(), "other", "attempt", "board", request.inputHash());
        assertThatThrownBy(() -> service.registerGeneratedDraft(lease, other, () -> false)).hasMessageContaining("不属于");
        verifyNoInteractions(renderer, storage, persistence);
    }
}
