package com.link.linkagent.creator.creation.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.asset.service.AssetMediaPreparer;
import com.link.linkagent.creator.creation.render.FixedProjectRenderService;
import com.link.linkagent.creator.creation.render.FixedProjectRendererProperties;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardCompiler;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardProjectFiles;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.storage.StoredObjectMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 真实 FFmpeg/Python 验证：配音规范化与含生成配音的故事板确实能出片。
 * 音频是受控测试音，不是真实供应商语音，也没有调用 TTS/ASR。
 */
@EnabledIfEnvironmentVariable(named = "LINKAGENT_VOICE_NATIVE_TESTS", matches = "true")
class CreationVoiceNativeTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();

    @Test
    void ttsShapedAudioIsNormalizedToWorkingPcmAndRendersWithTranscriptCues() throws Exception {
        Path source = root.resolve("tts-source.wav");
        command("ffmpeg", "-v", "error", "-nostdin", "-f", "lavfi", "-i", "sine=frequency=520:sample_rate=24000:duration=1.4",
                "-c:a", "pcm_s16le", "-ac", "1", "-y", source.toString());

        var properties = new FixedProjectRendererProperties();
        properties.setTimeout(Duration.ofMinutes(2));
        Path preparedDir = Files.createDirectory(root.resolve("prepared"));
        var prepared = new AssetMediaPreparer(json, properties).prepare(source, preparedDir, "NARRATION", () -> false);
        assertThat(prepared.durationMs()).isBetween(1300L, 1500L);
        assertThat(prepared.normalizedMetadata().path("streams").get(0).path("codec_name").asText()).isEqualTo("pcm_s16le");
        assertThat(prepared.normalizedMetadata().path("streams").get(0).path("sample_rate").asInt()).isEqualTo(48000);
        assertThat(prepared.normalizedMetadata().path("streams").get(0).path("channels").asInt()).isEqualTo(2);
        Path normalized = prepared.files().getFirst().path();

        String text = "生成配音的真实字幕时间";
        ObjectNode metadata = json.createObjectNode();
        metadata.set("original", prepared.originalMetadata());
        metadata.set("normalized", prepared.normalizedMetadata());
        metadata.putObject("generated").put("kind", "TTS").put("provider", "dashscope")
                .put("model", "cosyvoice-v2").put("voice", "longxiaochun_v2")
                .put("textSha256", StoryboardCodec.digest(text));
        Asset voice = new Asset("voice", "NARRATION", "persisted-native-bucket", "normalized/voice.wav",
                digest(normalized), Files.size(normalized), "audio/wav", metadata);
        var scene = new Scene("s1", "TITLE_CARD", "配音测试", List.of("仅验证工程与编码"), text, 2000,
                null, null, null, "CONTAIN", "TTS", "voice", List.of(new Cue(0, 900, text)), List.of("brief:user"));
        ObjectNode brief = json.createObjectNode().put("targetDurationMs", 2000).put("dialogueMode", "TTS");
        var compiler = new StoryboardCompiler(json);
        var check = compiler.check("voice-board", brief, new Candidate(List.of(scene)), List.of(voice));
        assertThat(check.problems()).isEmpty();
        var project = compiler.compile("voice-board", brief, new Candidate(List.of(scene)), List.of(voice),
                "voice-run", "attempt-1");

        var storage = mock(ObjectStorageService.class);
        Map<String, Path> objects = Map.of(voice.objectKey(), normalized);
        when(storage.headObject(eq("persisted-native-bucket"), anyString())).thenAnswer(invocation ->
                new StoredObjectMetadata(Files.size(objects.get(invocation.getArgument(1, String.class))), "audio/wav", "etag"));
        doAnswer(invocation -> {
            Files.copy(objects.get(invocation.getArgument(1, String.class)), invocation.getArgument(2, Path.class));
            return null;
        }).when(storage).downloadObject(eq("persisted-native-bucket"), anyString(), any(Path.class), any());

        var renderer = new FixedProjectRendererProperties();
        renderer.setEnabled(true);
        renderer.setWorkRoot(root.resolve("work").toString());
        renderer.setModuleRoot(System.getenv("LINKAGENT_RENDERER_MODULE_ROOT"));
        renderer.setPythonPath("python");
        var files = new StoryboardProjectFiles(renderer, storage, json);
        var request = files.materialize(project, List.of(voice), () -> false);
        var output = new FixedProjectRenderService(renderer, json).render(request, () -> false);
        assertThat(output.durationMs()).isEqualTo(2000);
        assertThat(output.frameCount()).isEqualTo(60);
        assertThat(Files.readString(output.files().get("subtitles.srt").path()))
                .contains("00:00:00,000 --> 00:00:00,900").contains(text);
        var manifest = json.readTree(output.files().get("manifest.json").path().toFile());
        assertThat(manifest.path("media").path("fullDecodePassed").asBoolean()).isTrue();
        assertThat(Files.size(output.files().get("video.mp4").path())).isGreaterThan(1000);
        System.out.println("S4_VOICE_NATIVE_OK frames=" + output.frameCount()
                + " audioMs=" + prepared.durationMs() + " providerCalled=false");
        files.discard(request);
    }

    private static String digest(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void command(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(root.resolve("ffmpeg.log").toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IllegalStateException("媒体命令失败：" + String.join(" ", command));
        }
    }
}
