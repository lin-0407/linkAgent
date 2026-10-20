package com.link.linkagent.creator.creation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.Run;
import com.link.linkagent.creator.creation.render.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.link.linkagent.creator.creation.service.CreationInputRules.*;

/** 真实文件验证冻结、复制和路径边界；本组不声称这些协议夹具是可播放视频。 */
class FixedProjectJobFilesTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    FixedProjectJobFiles files;
    ObjectNode document;
    Path source;

    @BeforeEach void setup() throws Exception {
        var properties = new FixedProjectRendererProperties(); properties.setEnabled(true); properties.setWorkRoot(root.toString());
        files = new FixedProjectJobFiles(properties, json);
        source = Files.createDirectories(root.resolve("run/source"));
        Files.createDirectories(source.resolve("assets")); Files.writeString(source.resolve("assets/picture.png"), "asset-fixture");
        document = (ObjectNode) json.readTree("""
                {"schemaVersion":1,"runId":"run","attemptId":"source","storyboardId":"board","seed":7,
                "assets":[{"id":"picture","path":"assets/picture.png","mime":"image/png","sha256":""}],
                "video":{"width":1280,"height":720,"fps":24,"frameCount":48}}
                """);
        ((ObjectNode) document.path("assets").get(0)).put("sha256", hash("asset-fixture"));
    }

    @Test void frozenJsonAndCopiedAssetsDoNotFollowLaterSourceChanges() throws Exception {
        var job = files.freeze("task", "default", write());
        Files.writeString(source.resolve("input.json"), "malformed later change");
        var request = files.prepare(job, run(1), () -> false);
        var prepared = json.readTree(request.input().toFile());
        assertThat(prepared.path("seed").asInt()).isEqualTo(7);
        assertThat(request.attemptId()).isNotEqualTo("source");
        assertThat(prepared.path("inputHash").asText()).isEqualTo(request.inputHash());
        assertThat(renderContentHash(json, job.projectJson())).isEqualTo(renderContentHash(json, prepared.toString()));
        Files.writeString(source.resolve("assets/picture.png"), "changed-after-copy");
        assertThat(Files.readString(request.input().getParent().resolve("assets/picture.png"))).isEqualTo("asset-fixture");
    }

    @Test void changedSourceAssetCannotBeAdoptedByNewAttempt() throws Exception {
        var job = files.freeze("task", "default", write());
        Files.writeString(source.resolve("assets/picture.png"), "different-file");
        assertThatThrownBy(() -> files.prepare(job, run(1), () -> false)).isInstanceOfSatisfying(RenderExecutionException.class,
                error -> assertThat(error.code()).isEqualTo("ASSET_HASH_MISMATCH"));
    }

    @ParameterizedTest
    @ValueSource(strings={"../outside.png", ".env", "C:\\private.png", "/private.png", "assets//pic.png", "rendered/video.mp4", "input.json"})
    void rejectsPathsThatCannotBeCopiedAsLocalAssets(String path) throws Exception {
        ((ObjectNode) document.path("assets").get(0)).put("path", path);
        var job = files.freeze("task", "default", write());
        assertThatThrownBy(() -> files.prepare(job, run(1), () -> false)).isInstanceOf(RenderExecutionException.class);
    }

    @Test void cancellationBeforePreparationCreatesNoAttempt() throws Exception {
        var job = files.freeze("task", "default", write());
        assertThatThrownBy(() -> files.prepare(job, run(1), () -> true)).isInstanceOfSatisfying(RenderExecutionException.class,
                error -> assertThat(error.code()).isEqualTo("CANCELLED"));
        try (var paths = Files.list(root.resolve("run"))) { assertThat(paths.count()).isEqualTo(1); }
    }

    @Test void everyLeaseUsesIndependentAttemptAndIdentityHash() throws Exception {
        var job = files.freeze("task", "default", write());
        var first = files.prepare(job, run(1), () -> false);
        var second = files.prepare(job, run(2), () -> false);
        assertThat(first.input()).isNotEqualTo(second.input());
        assertThat(first.inputHash()).isNotEqualTo(second.inputHash());
        assertThat(renderContentHash(json, Files.readString(first.input())))
                .isEqualTo(renderContentHash(json, Files.readString(second.input())));
    }

    @Test void wrongSourceFingerprintIsRejected() throws Exception {
        var request = write();
        assertThatThrownBy(() -> files.freeze("task", "default", new FixedProjectRenderService.Request(request.input(),
                "run", "source", "board", "a".repeat(64)))).isInstanceOf(RenderExecutionException.class);
    }

    @Test void requestCannotReadAnotherLocalDirectory() throws Exception {
        var request = write();
        Path outside = root.resolve("elsewhere.json"); Files.copy(request.input(), outside);
        assertThatThrownBy(() -> files.freeze("task", "default", new FixedProjectRenderService.Request(outside,
                "run", "source", "board", request.inputHash()))).isInstanceOf(RenderExecutionException.class);
    }

    private FixedProjectRenderService.Request write() throws Exception {
        document.remove("inputHash"); String fingerprint = hash(renderDocument(json, document.toString()));
        document.put("inputHash", fingerprint); Path input = source.resolve("input.json"); json.writeValue(input.toFile(), document);
        return new FixedProjectRenderService.Request(input, "run", "source", "board", fingerprint);
    }

    private Run run(long generation) {
        var run = mock(Run.class); when(run.runId()).thenReturn("run"); when(run.taskId()).thenReturn("task");
        when(run.ownerId()).thenReturn("default"); when(run.leaseGeneration()).thenReturn(generation); return run;
    }
}
