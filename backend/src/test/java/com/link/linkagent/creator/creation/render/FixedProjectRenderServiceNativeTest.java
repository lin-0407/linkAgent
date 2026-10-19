package com.link.linkagent.creator.creation.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

/** 启用时必须有真实 Python/FFmpeg/字体；固定测试音只验证媒体协议，不代表旁白语义。 */
@EnabledIfEnvironmentVariable(named = "LINKAGENT_RENDERER_NATIVE_TESTS", matches = "true")
class FixedProjectRenderServiceNativeTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private FixedProjectRendererProperties properties;
    private FixedProjectRenderService.Request request;

    @BeforeEach void setup() throws Exception {
        properties = new FixedProjectRendererProperties();
        properties.setEnabled(true);
        properties.setModuleRoot(System.getenv("LINKAGENT_RENDERER_MODULE_ROOT"));
        properties.setPythonPath("python");
        properties.setWorkRoot(root.toString());
        properties.setTimeout(Duration.ofSeconds(60));
        Path attempt = Files.createDirectories(root.resolve("native-run/native-attempt"));
        ByteBuffer wave = ByteBuffer.allocate(44 + 24000 * 2).order(ByteOrder.LITTLE_ENDIAN);
        wave.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + 48000)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                .putShort((short) 1).putInt(24000).putInt(48000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(48000);
        for (int i = 0; i < 24000; i++) wave.putShort((short) (Math.sin(i * 2 * Math.PI * 440 / 24000) * 12000));
        Files.write(attempt.resolve("voice.wav"), wave.array());
        ObjectNode document = (ObjectNode) json.readTree("""
                {"schemaVersion":1,"runId":"native-run","attemptId":"native-attempt","storyboardId":"native-board",
                 "seed":7,"video":{"width":1280,"height":720,"fps":24,"frameCount":48,"codec":"h264",
                 "audioSampleRate":48000,"fontId":"cjk_sans"},
                 "assets":[{"id":"voice","path":"voice.wav","sha256":"","mime":"audio/wav"}],
                 "scenes":[{"id":"scene","startFrame":0,"endFrame":48,"layout":"TITLE_CARD","background":"#102030",
                 "title":"后端真实渲染","body":["Java 调用固定工程"],"voice":{"assetId":"voice","offsetMs":100,"gainDb":-1}}],
                 "subtitles":[{"id":"cue","sceneId":"scene","startMs":100,"endMs":1100,
                 "text":"固定测试音仅验证媒体协议","audioAssetId":"voice"}]}
                """);
        ((ObjectNode) document.path("assets").get(0)).put("sha256", sha(wave.array()));
        ObjectMapper canonical = json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        String hash = sha(canonical.writeValueAsBytes(json.convertValue(document, Map.class)));
        document.put("inputHash", hash);
        Path input = attempt.resolve("input.json"); json.writeValue(input.toFile(), document);
        request = new FixedProjectRenderService.Request(input, "native-run", "native-attempt", "native-board", hash);
    }

    @Test void rendersRealVideoAndReceivesAllVerifiedArtifacts() throws Exception {
        RenderArtifacts result = service().render(request, () -> false);
        assertThat(result.files()).hasSize(8).containsKeys("manifest.json", "video.mp4", "subtitles.srt", "cover.png");
        assertThat(result.durationMs()).isEqualTo(2000);
        assertThat(result.frameCount()).isEqualTo(48);
        assertThat(Files.readString(result.files().get("subtitles.srt").path())).contains("00:00:00,100 --> 00:00:01,100");
        assertThat(result.files().get("video.mp4").bytes()).isGreaterThan(1000);
        JsonNode manifest = json.readTree(result.files().get("manifest.json").path().toFile());
        assertThat(manifest.path("media").path("fullDecodePassed").booleanValue()).isTrue();
        assertThat(manifest.path("media").path("clippedSamples").asInt(-1)).isZero();
        assertThatThrownBy(() -> service().render(request, () -> false))
                .isInstanceOfSatisfying(RenderExecutionException.class, error -> assertThat(error.code()).isEqualTo("OUTPUT_EXISTS"));
    }

    @Test void cancellationTerminatesRealRenderingAndLeavesNoSuccessManifest() {
        AtomicInteger checks = new AtomicInteger();
        assertThatThrownBy(() -> service().render(request, () -> checks.incrementAndGet() >= 12))
                .isInstanceOfSatisfying(RenderExecutionException.class, error -> assertThat(error.code()).isEqualTo("CANCELLED"));
        assertThat(request.input().getParent().resolve("rendered/manifest.json")).doesNotExist();
        assertNoRenderChildren();
    }

    @Test void timeoutTerminatesRealRenderingAndLeavesNoSuccessManifest() {
        properties.setTimeout(Duration.ofMillis(800));
        assertThatThrownBy(() -> service().render(request, () -> false))
                .isInstanceOfSatisfying(RenderExecutionException.class, error -> assertThat(error.code()).isEqualTo("TIMEOUT"));
        assertThat(request.input().getParent().resolve("rendered/manifest.json")).doesNotExist();
        assertNoRenderChildren();
    }

    @Test void actualCodecFailureCannotBecomeSuccessfulOutput() {
        properties.setFfmpegPath("/bin/false");
        assertThatThrownBy(() -> service().render(request, () -> false))
                .isInstanceOfSatisfying(RenderExecutionException.class, error -> assertThat(error.code()).isEqualTo("ENCODING_FAILED"));
        assertThat(request.input().getParent().resolve("rendered/manifest.json")).doesNotExist();
        assertNoRenderChildren();
    }

    @Test void missingFontFailsWithoutRegisteringAnOutput() {
        properties.setFontPath(root.resolve("absent.ttc").toString());
        assertThatThrownBy(() -> service().render(request, () -> false)).isInstanceOf(RenderExecutionException.class);
        assertThat(request.input().getParent().resolve("rendered/manifest.json")).doesNotExist();
    }

    @Test void cancellationBeforeLaunchDoesNotCreateOutput() {
        assertThatThrownBy(() -> service().render(request, () -> true))
                .isInstanceOfSatisfying(RenderExecutionException.class, error -> assertThat(error.code()).isEqualTo("CANCELLED"));
        assertThat(request.input().getParent().resolve("rendered")).doesNotExist();
    }

    @Test void mismatchedExpectedIdentityCannotLaunchAnotherRunsProject() {
        var other = new FixedProjectRenderService.Request(request.input(), request.runId(), request.attemptId(), "other-board", request.inputHash());
        assertThatThrownBy(() -> service().render(other, () -> false)).isInstanceOf(RenderExecutionException.class);
        assertThat(request.input().getParent().resolve("rendered")).doesNotExist();
    }

    @Test void rejectsInputOutsideItsAttempt() throws Exception {
        Path other = root.resolve("outside.json"); Files.copy(request.input(), other);
        var outside = new FixedProjectRenderService.Request(other, request.runId(), request.attemptId(), request.storyboardId(), request.inputHash());
        assertThatThrownBy(() -> service().render(outside, () -> false))
                .isInstanceOfSatisfying(RenderExecutionException.class, error -> assertThat(error.code()).isEqualTo("INVALID_INPUT"));
    }

    @Test void rejectsSymlinkToAnotherAttemptEvenWithinWorkRoot() throws Exception {
        Path target = Files.createDirectories(root.resolve("different"));
        Path original = request.input().getParent();
        Path link = root.resolve("native-run/link-attempt"); Files.createSymbolicLink(link, target);
        Files.copy(request.input(), target.resolve("input.json"));
        var linked = new FixedProjectRenderService.Request(link.resolve("input.json"), request.runId(), "link-attempt", request.storyboardId(), request.inputHash());
        assertThatThrownBy(() -> service().render(linked, () -> false)).isInstanceOf(RenderExecutionException.class);
        assertThat(original.resolve("rendered")).doesNotExist();
    }

    @Test void threadInterruptionIsPreservedAndDoesNotStartRenderer() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> service().render(request, () -> false))
                    .isInstanceOfSatisfying(RenderExecutionException.class, error -> assertThat(error.code()).isEqualTo("CANCELLED"));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void childDoesNotInheritDeploymentSecretsOrPythonImportSettings() throws Exception {
        assertThat(System.getenv("LINKAGENT_RENDER_TEST_SECRET")).isNotBlank();
        fakeModule("""
                import os, sys
                assert 'LINKAGENT_RENDER_TEST_SECRET' not in os.environ
                assert 'PYTHONPATH' not in os.environ
                assert os.environ['OPENBLAS_NUM_THREADS'] == '1'
                print('{"status":"FAILED","code":"INVALID_INPUT"}')
                sys.exit(2)
                """);
        assertThatThrownBy(() -> service().render(request, () -> false))
                .isInstanceOfSatisfying(RenderExecutionException.class, error -> assertThat(error.code()).isEqualTo("INVALID_INPUT"));
    }

    @Test void excessiveProcessOutputIsBoundedAndTerminated() throws Exception {
        fakeModule("""
                import sys, time
                sys.stdout.write('x' * 100000)
                sys.stdout.flush()
                time.sleep(30)
                """);
        assertThatThrownBy(() -> service().render(request, () -> false))
                .isInstanceOfSatisfying(RenderExecutionException.class, error -> assertThat(error.code()).isEqualTo("OUTPUT_TOO_LARGE"));
        assertNoRenderChildren();
    }

    @Test void zeroExitWithoutArtifactsCannotBecomeSuccessfulRendering() throws Exception {
        fakeModule("print('{\"status\":\"COMPLETED\"}')");
        assertThatThrownBy(() -> service().render(request, () -> false))
                .isInstanceOfSatisfying(RenderExecutionException.class, error -> assertThat(error.code()).isEqualTo("INVALID_ARTIFACT"));
    }

    @Test void rejectsInputChangedWhileProcessWasRunning() throws Exception {
        fakeModule("""
                import sys
                from pathlib import Path
                Path(sys.argv[sys.argv.index('--input') + 1]).write_text('{}')
                """);
        assertThatThrownBy(() -> service().render(request, () -> false))
                .isInstanceOfSatisfying(RenderExecutionException.class, error -> assertThat(error.code()).isEqualTo("INPUT_CHANGED"));
    }

    @Test void interruptedWaitTerminatesTheLaunchedProcess() throws Exception {
        fakeModule("""
                import time
                time.sleep(30)
                """);
        AtomicBoolean observed = new AtomicBoolean();
        Thread caller = Thread.ofPlatform().start(() -> {
            try { service().render(request, () -> false); }
            catch (RenderExecutionException error) { observed.set("CANCELLED".equals(error.code()) && Thread.currentThread().isInterrupted()); }
        });
        Thread.sleep(300); caller.interrupt(); caller.join(5000);
        assertThat(caller.isAlive()).isFalse();
        assertThat(observed).isTrue();
        assertNoRenderChildren();
    }

    @Test @EnabledIfEnvironmentVariable(named = "LINKAGENT_RENDERER_SAMPLE_ROOT", matches = ".+")
    void rendersExistingSpokenProjectThroughJavaBridge() throws Exception {
        Path source = Path.of(System.getenv("LINKAGENT_RENDERER_SAMPLE_ROOT"));
        JsonNode document = json.readTree(source.resolve("input.json").toFile());
        Path attempt = Files.createDirectories(root.resolve(document.path("runId").asText()).resolve(document.path("attemptId").asText()));
        for (JsonNode asset : document.path("assets")) {
            String name = asset.path("path").asText();
            Path target = attempt.resolve(name).normalize();
            assertThat(target.startsWith(attempt)).isTrue();
            Files.createDirectories(target.getParent()); Files.copy(source.resolve(name), target);
        }
        Path inputFile = attempt.resolve("input.json"); Files.copy(source.resolve("input.json"), inputFile);
        var spoken = new FixedProjectRenderService.Request(inputFile, document.path("runId").asText(), document.path("attemptId").asText(),
                document.path("storyboardId").asText(), document.path("inputHash").asText());
        properties.setTimeout(Duration.ofSeconds(120));
        RenderArtifacts result = service().render(spoken, () -> false);
        assertThat(result.durationMs()).isEqualTo(84167);
        assertThat(result.frameCount()).isEqualTo(2020);
        assertThat(result.files()).hasSize(8);
    }

    private void fakeModule(String script) throws Exception {
        Path module = Files.createDirectories(root.resolve("fake-module/linkagent_renderer"));
        Files.writeString(module.resolve("__init__.py"), ""); Files.writeString(module.resolve("__main__.py"), script);
        properties.setModuleRoot(module.getParent().toString());
    }

    private void assertNoRenderChildren() {
        assertThat(ProcessHandle.current().descendants().filter(handle -> handle.info().commandLine()
                .orElse("").contains(root.toString())).toList()).isEmpty();
    }

    private FixedProjectRenderService service() { return new FixedProjectRenderService(properties, json); }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

}
