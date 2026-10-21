package com.link.linkagent.creator.creation.storyboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.render.*;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.media.storage.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 候选、图片和测试音由测试控制，真实 Java→Python→FFmpeg 出片，不代表模型生成或供应商试映验收。 */
@EnabledIfEnvironmentVariable(named="LINKAGENT_STORYBOARD_NATIVE_TESTS", matches="true")
class StoryboardCompilerNativeTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    static final Set<String> VIDEOS = new HashSet<>(), FRAMES = new HashSet<>();

    @ParameterizedTest
    @CsvSource({"reading,阅读计划,red,220,30,30", "cooking,家常菜步骤,green,30,120,30", "travel,周末出行,blue,30,30,220"})
    void threeDistinctBusinessBoardsBecomeActualVideosThroughTheSameCompilerAndRenderer(
            String theme, String title, String color, int red, int green, int blue) throws Exception {
        Path source = Files.createDirectory(root.resolve("source"));
        Path image = source.resolve("photo.png"), clip = source.resolve("clip.mp4"), voice = source.resolve("voice.wav");
        BufferedImage bitmap = new BufferedImage(240, 160, BufferedImage.TYPE_INT_RGB);
        var graphics = bitmap.createGraphics(); graphics.setColor(new Color(red, green, blue)); graphics.fillRect(0, 0, 240, 160); graphics.dispose();
        ImageIO.write(bitmap, "png", image.toFile());
        command("ffmpeg", "-v", "error", "-nostdin", "-threads", "2", "-f", "lavfi", "-i", "color=c=" + color + ":size=240x160:rate=30:duration=1.2",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1.2", "-map", "0:v:0", "-map", "1:a:0", "-c:v", "libx264", "-threads", "2",
                "-pix_fmt", "yuv420p", "-c:a", "aac", "-ac", "2", "-shortest", "-y", clip.toString());
        command("ffmpeg", "-v", "error", "-nostdin", "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000:duration=0.9", "-c:a", "pcm_s16le", "-ac", "2", "-y", voice.toString());
        Asset photo = asset("photo", "IMAGE", "image/png", image), video = asset("clip", "VIDEO_CLIP", "video/mp4", clip), narration = asset("voice", "NARRATION", "audio/wav", voice);
        List<Asset> assets = List.of(photo, video, narration);
        var scenes = List.of(
                new Scene("opening", "TITLE_CARD", title, List.of("本片仅验证工程编译与真实编码"), "", 1000, null, null, null, "CONTAIN", "NO_DIALOGUE", null, List.of(), List.of("user-brief")),
                new Scene("picture", "IMAGE_CARD", title + "：自有图片", List.of(), "", 1000, "photo", null, null, "CONTAIN", "NO_DIALOGUE", null, List.of(), List.of("asset:photo")),
                new Scene("recording", "VIDEO_CLIP", title + "：原声片段", List.of(), "", 1000, "clip", 0L, 1000L, "COVER", "ORIGINAL", null, List.of(new Cue(0, 900, "原声音轨测试，非真实对白")), List.of("asset:clip")),
                new Scene("closing", "TITLE_CARD", title + "：字幕收尾", List.of(), "", 1000, null, null, null, "CONTAIN", "NARRATION", "voice", List.of(new Cue(0, 900, "协议测试音，并非真实旁白")), List.of("asset:voice")));
        ObjectNode brief = json.createObjectNode().put("topic", title).put("targetDurationMs", 4000).put("dialogueMode", "USER_NARRATION");
        var compiler = new StoryboardCompiler(json);
        Candidate candidate = new Candidate(scenes);
        assertThat(compiler.check("board-" + theme, brief, candidate, assets).problems()).isEmpty();
        ObjectNode project = compiler.compile("board-" + theme, brief, candidate, assets, "run-" + theme, "source-attempt");
        var storage = mock(ObjectStorageService.class);
        Map<String, Path> objects = Map.of(photo.objectKey(), image, video.objectKey(), clip, narration.objectKey(), voice);
        when(storage.headObject(eq("persisted-native-bucket"), anyString())).thenAnswer(i -> new StoredObjectMetadata(Files.size(objects.get(i.getArgument(1, String.class))), "application/octet-stream", "etag"));
        doAnswer(i -> { Files.copy(objects.get(i.getArgument(1, String.class)), i.getArgument(2, Path.class)); return null; }).when(storage)
                .downloadObject(eq("persisted-native-bucket"), anyString(), any(Path.class), any());
        var properties = new FixedProjectRendererProperties(); properties.setEnabled(true); properties.setWorkRoot(root.resolve("work").toString());
        properties.setModuleRoot(System.getenv("LINKAGENT_RENDERER_MODULE_ROOT")); properties.setPythonPath("python");
        var files = new StoryboardProjectFiles(properties, storage, json);
        var request = files.materialize(project, assets, () -> false);
        var output = new FixedProjectRenderService(properties, json).render(request, () -> false);
        JsonNode manifest = json.readTree(output.files().get("manifest.json").path().toFile());
        assertThat(output.durationMs()).isEqualTo(4000); assertThat(output.frameCount()).isEqualTo(120);
        assertThat(manifest.path("media").path("fullDecodePassed").asBoolean()).isTrue();
        String srt = Files.readString(output.files().get("subtitles.srt").path());
        assertThat(srt).contains("00:00:02,000 --> 00:00:02,900", "00:00:03,000 --> 00:00:03,900", "协议测试音，并非真实旁白");
        BufferedImage actual = ImageIO.read(output.files().get("frame-middle.png").path().toFile());
        Color center = new Color(actual.getRGB(640, 360));
        assertThat(switch (color) { case "red" -> center.getRed() > center.getBlue() + 100; case "green" -> center.getGreen() > center.getRed() + 60; default -> center.getBlue() > center.getRed() + 100; }).isTrue();
        assertThat(VIDEOS.add(output.files().get("video.mp4").sha256())).isTrue();
        assertThat(FRAMES.add(output.files().get("frame-middle.png").sha256())).isTrue();
        System.out.println("S4_STORYBOARD_NATIVE_OK theme=" + theme + " frames=" + output.frameCount() + " subtitles=2 sourceBucket=persisted-native-bucket modelCalled=false");
        files.discard(request);
        assertThat(request.input().getParent()).doesNotExist();
    }

    @AfterAll static void distinctOutputs() {
        assertThat(VIDEOS).hasSize(3); assertThat(FRAMES).hasSize(3);
    }

    @Test void explicitSilentCardUsesSystemPlaceholderAndActuallyEncodesSilentAudio() throws Exception {
        var properties = new FixedProjectRendererProperties(); properties.setEnabled(true); properties.setWorkRoot(root.resolve("silent-work").toString());
        properties.setModuleRoot(System.getenv("LINKAGENT_RENDERER_MODULE_ROOT")); properties.setPythonPath("python");
        var compiler = new StoryboardCompiler(json);
        var brief = json.createObjectNode().put("targetDurationMs", 1000).put("dialogueMode", "NO_DIALOGUE");
        var candidate = new Candidate(List.of(StoryboardCompilerTest.scene("silent", "TITLE_CARD", 1000, null, null, null, "NO_DIALOGUE", null, List.of())));
        var project = compiler.compile("silent-board", brief, candidate, List.of(), "silent-run", "attempt");
        var files = new StoryboardProjectFiles(properties, mock(ObjectStorageService.class), json);
        var request = files.materialize(project, List.of(), () -> false);
        var output = new FixedProjectRenderService(properties, json).render(request, () -> false);
        var manifest = json.readTree(output.files().get("manifest.json").path().toFile());
        assertThat(output.durationMs()).isEqualTo(1000);
        assertThat(manifest.path("media").path("decodedPeak").asDouble()).isLessThanOrEqualTo(.0001);
        assertThat(manifest.path("media").path("fullDecodePassed").asBoolean()).isTrue();
        assertThat(Files.readString(output.files().get("subtitles.srt").path())).isBlank();
        files.discard(request);
    }
    private Asset asset(String id, String kind, String mime, Path file) throws Exception {
        String probe = command("ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", file.toString());
        ObjectNode metadata = json.createObjectNode(); metadata.set("normalized", json.readTree(probe));
        return new Asset(id, kind, "persisted-native-bucket", "normalized/" + file.getFileName(), StoryboardCompiler.sha256(Files.readAllBytes(file)), Files.size(file), mime, metadata);
    }
    private String command(String... command) throws Exception {
        Path log = Files.createTempFile(root, "storyboard-native-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(Files.size(log)).isLessThanOrEqualTo(256 * 1024);
            byte[] output = Files.readAllBytes(log);
            assertThat(process.exitValue()).as(new String(output, java.nio.charset.StandardCharsets.UTF_8)).isZero();
            return new String(output, java.nio.charset.StandardCharsets.UTF_8);
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } Files.deleteIfExists(log); }
    }
}
