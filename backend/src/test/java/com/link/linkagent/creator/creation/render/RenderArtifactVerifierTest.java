package com.link.linkagent.creator.creation.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** 清单协议反例测试使用占位文件；真实媒体行为另由 NativeTest 验证。 */
class RenderArtifactVerifierTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private RenderArtifactVerifier verifier;
    private ObjectNode manifest;
    private JsonNode input;
    private FixedProjectRenderService.Request request;

    @BeforeEach void setup() throws Exception {
        verifier = new RenderArtifactVerifier(json);
        request = new FixedProjectRenderService.Request(root.resolve("input.json"), "run", "attempt", "board", "a".repeat(64));
        input = json.readTree("""
                {"video":{"width":1280,"height":720,"fps":24,"frameCount":48}}
                """);
        manifest = (ObjectNode) json.readTree("""
                {"schemaVersion":1,"rendererVersion":"0.1.0","runId":"run","attemptId":"attempt","storyboardId":"board",
                 "inputHash":"","fontSha256":"","durationMs":2000,"elapsedMs":100,
                 "media":{"width":1280,"height":720,"fps":24,"frameCount":48,"videoDurationMs":2000,
                 "containerDurationMs":2000,"videoCodec":"h264","audioCodec":"aac","audioSampleRate":48000,
                 "fullDecodePassed":true,"clippedSamples":0,"decodedPeak":0.4,"decodedRms":0.1,"sourcePeak":0.4,"sourceRms":0.1},"files":{}}
                """);
        manifest.put("inputHash", request.inputHash()).put("fontSha256", "b".repeat(64));
        for (String name : List.of("video.mp4", "subtitles.srt", "cover.png", "frame-start.png", "frame-middle.png", "frame-end.png", "render.log")) {
            Path file = root.resolve(name); Files.writeString(file, "fixture-" + name);
            ((ObjectNode) manifest.path("files")).putObject(name).put("name", name).put("bytes", Files.size(file))
                    .put("sha256", RenderArtifactVerifier.digest(file, () -> { }));
        }
    }

    @Test void acceptsCompleteProtocolAndReturnsManifestDigest() throws Exception {
        RenderArtifacts result = verify();
        assertThat(result.files()).hasSize(8);
        assertThat(result.files().get("manifest.json").sha256()).isEqualTo(RenderArtifactVerifier.digest(root.resolve("manifest.json"), () -> { }));
        assertThat(result.files().get("video.mp4").contentType()).isEqualTo("video/mp4");
    }

    @ParameterizedTest @ValueSource(strings = {"runId", "attemptId", "storyboardId", "inputHash"})
    void rejectsAnotherIdentity(String field) { manifest.put(field, "different"); assertRejected(); }

    @ParameterizedTest @ValueSource(strings = {"width", "height", "fps", "frameCount"})
    void rejectsWrongMediaParameters(String field) { media().put(field, 1); assertRejected(); }

    @ParameterizedTest @ValueSource(strings = {"video.mp4", "subtitles.srt", "cover.png", "frame-start.png", "frame-middle.png", "frame-end.png", "render.log"})
    void rejectsMissingArtifacts(String name) throws Exception { Files.delete(root.resolve(name)); assertRejected(); }

    @Test void rejectsSameLengthTampering() throws Exception {
        Path video = root.resolve("video.mp4"); Files.writeString(video, "x".repeat(Math.toIntExact(Files.size(video)))); assertRejected();
    }

    @Test void rejectsPartialFileList() { ((ObjectNode) manifest.path("files")).remove("subtitles.srt"); assertRejected(); }
    @Test void rejectsManifestPathInjection() { ((ObjectNode) manifest.path("files").path("video.mp4")).put("name", "../video.mp4"); assertRejected(); }
    @Test void rejectsMissingFullDecodeFact() { media().put("fullDecodePassed", false); assertRejected(); }
    @Test void rejectsClippedAudio() { media().put("clippedSamples", 1); assertRejected(); }
    @Test void rejectsSilentAudio() { media().put("decodedPeak", 0); assertRejected(); }
    @Test void rejectsUnreasonableSourcePeak() { media().put("sourcePeak", .99); assertRejected(); }
    @Test void rejectsTruncatedAudioContainer() { media().put("containerDurationMs", 1900); assertRejected(); }
    @Test void rejectsWrongCodec() { media().put("audioCodec", "mp3"); assertRejected(); }
    @Test void rejectsNumericStrings() { media().put("width", "1280"); assertRejected(); }
    @Test void rejectsDurationOverflow() { media().put("containerDurationMs", Long.MIN_VALUE + 2000); assertRejected(); }

    @Test void v2ManifestMustMatchInputVersion() {
        ((ObjectNode) input).put("schemaVersion", 2); assertRejected();
        manifest.put("inputSchemaVersion", 1); assertRejected();
    }

    @Test void v1CannotAcceptV2ManifestAsSameProject() {
        ((ObjectNode) input).put("schemaVersion", 1); manifest.put("inputSchemaVersion", 2); assertRejected();
    }

    @Test void onlyExplicitlySilentV2ScenesCanAcceptZeroAudio() throws Exception {
        ((ObjectNode) input).put("schemaVersion", 2);
        ((ObjectNode) input).putArray("scenes").addObject().put("layout", "VIDEO_CLIP").putNull("voice")
                .putObject("clip").put("audioMode", "MUTE");
        manifest.put("inputSchemaVersion", 2);
        for (String field : List.of("decodedPeak", "decodedRms", "sourcePeak", "sourceRms")) media().put(field, 0);
        assertThat(verify().files()).hasSize(8);
        ((ObjectNode) input.path("scenes").get(0).path("clip")).put("audioMode", "ORIGINAL"); assertRejected();
    }

    @Test void explicitlySilentProjectCannotHideUnexpectedSound() {
        ((ObjectNode) input).put("schemaVersion", 2);
        ((ObjectNode) input).putArray("scenes").addObject().putNull("voice");
        manifest.put("inputSchemaVersion", 2); assertRejected();
    }

    @Test void rejectsSymlinkedArtifactEvenIfHashMatches() throws Exception {
        Path outside = Files.createTempFile(root.getParent(), "render-external-", ".mp4");
        try {
            Files.copy(root.resolve("video.mp4"), outside, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.delete(root.resolve("video.mp4")); Files.createSymbolicLink(root.resolve("video.mp4"), outside);
            assertRejected();
        } finally { Files.deleteIfExists(outside); }
    }

    @Test void rejectsDuplicateJsonKeys() throws Exception {
        String original = json.writeValueAsString(manifest);
        Files.writeString(root.resolve("manifest.json"), original.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"));
        assertThatThrownBy(() -> verifier.verify(root, input, request, () -> { })).isInstanceOf(java.io.IOException.class);
    }

    @Test void rejectsTrailingJson() throws Exception {
        Files.writeString(root.resolve("manifest.json"), json.writeValueAsString(manifest) + " {}");
        assertThatThrownBy(() -> verifier.verify(root, input, request, () -> { })).isInstanceOf(java.io.IOException.class);
    }

    @Test void defaultConfigurationCannotLaunchProcesses() {
        var service = new FixedProjectRenderService(new FixedProjectRendererProperties(), json);
        assertThatThrownBy(() -> service.render(request, () -> false))
                .isInstanceOfSatisfying(RenderExecutionException.class, error -> assertThat(error.code()).isEqualTo("DISABLED"));
    }

    private ObjectNode media() { return (ObjectNode) manifest.path("media"); }
    private void assertRejected() {
        assertThatThrownBy(this::verify).isInstanceOf(RenderExecutionException.class);
    }
    private RenderArtifacts verify() throws Exception {
        json.writeValue(root.resolve("manifest.json").toFile(), manifest);
        return verifier.verify(root, input, request, () -> { });
    }
}
