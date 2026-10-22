package com.link.linkagent.creator.creation.storyboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.render.*;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.media.storage.*;
import com.link.linkagent.creator.creation.service.FixedProjectJobFiles;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.Run;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

    @Test void actualBackgroundMusicSurvivesRecoveryAndDucksUnderOriginalAndUserRecording() throws Exception {
        // 此处混合声音是编译器技术夹具，不替代公开简报 SOURCE/USER_NARRATION 的互斥业务校验。
        Path source = Files.createDirectory(root.resolve("bgm-source"));
        Path clip = source.resolve("clip.mp4"), voice = source.resolve("voice.wav"), music = source.resolve("music.wav");
        command("ffmpeg", "-v", "error", "-nostdin", "-threads", "2", "-f", "lavfi", "-i", "color=c=navy:size=240x160:rate=30:duration=1.2",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1.2", "-map", "0:v:0", "-map", "1:a:0", "-c:v", "libx264", "-threads", "2",
                "-pix_fmt", "yuv420p", "-c:a", "aac", "-ac", "2", "-shortest", "-y", clip.toString());
        command("ffmpeg", "-v", "error", "-nostdin", "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000:duration=0.9", "-c:a", "pcm_s16le", "-ac", "2", "-y", voice.toString());
        command("ffmpeg", "-v", "error", "-nostdin", "-f", "lavfi", "-i", "sine=frequency=220:sample_rate=48000:duration=6", "-c:a", "pcm_s16le", "-ac", "2", "-y", music.toString());
        Asset video = asset("clip", "VIDEO_CLIP", "video/mp4", clip), narration = asset("voice", "NARRATION", "audio/wav", voice), bgm = asset("music", "MUSIC", "audio/wav", music);
        List<Asset> assets = List.of(video, narration, bgm);
        var scenes = List.of(StoryboardCompilerTest.scene("solo", "TITLE_CARD", 1000, null, null, null, "NO_DIALOGUE", null, List.of()),
                StoryboardCompilerTest.scene("original", "VIDEO_CLIP", 1000, "clip", 0L, 1000L, "ORIGINAL", null, List.of()),
                StoryboardCompilerTest.scene("recording", "TITLE_CARD", 1000, null, null, null, "NARRATION", "voice", List.of(new Cue(0, 900, "用户给定真实音轨时间"))),
                StoryboardCompilerTest.scene("ending", "TITLE_CARD", 1000, null, null, null, "NO_DIALOGUE", null, List.of()));
        ObjectNode brief = json.createObjectNode().put("targetDurationMs", 4000).put("dialogueMode", "USER_NARRATION");
        brief.putObject("backgroundMusic").put("assetId", "music").put("sourceInMs", 1000).put("sourceOutMs", 5000)
                .put("gainDb", -12).put("fadeInMs", 100).put("fadeOutMs", 100).put("ducking", true);
        var project = new StoryboardCompiler(json).compile("bgm-board", brief, new Candidate(scenes), assets, "bgm-run", "source-attempt");
        var storage = mock(ObjectStorageService.class); Map<String, Path> objects = Map.of(video.objectKey(), clip, narration.objectKey(), voice, bgm.objectKey(), music);
        when(storage.headObject(eq("persisted-native-bucket"), anyString())).thenAnswer(i -> new StoredObjectMetadata(Files.size(objects.get(i.getArgument(1, String.class))), "application/octet-stream", "etag"));
        doAnswer(i -> { Files.copy(objects.get(i.getArgument(1, String.class)), i.getArgument(2, Path.class)); return null; }).when(storage)
                .downloadObject(eq("persisted-native-bucket"), anyString(), any(Path.class), any());
        var properties = new FixedProjectRendererProperties(); properties.setEnabled(true); properties.setWorkRoot(root.resolve("bgm-work").toString());
        properties.setModuleRoot(System.getenv("LINKAGENT_RENDERER_MODULE_ROOT")); properties.setPythonPath("python");
        var files = new StoryboardProjectFiles(properties, storage, json); var sourceRequest = files.materialize(project, assets, () -> false);
        var recovery = new FixedProjectJobFiles(properties, json); var job = recovery.freeze("native-task", "default", sourceRequest);
        Run run = mock(Run.class); when(run.runId()).thenReturn("bgm-run"); when(run.taskId()).thenReturn("native-task"); when(run.ownerId()).thenReturn("default"); when(run.leaseGeneration()).thenReturn(1L);
        var request = recovery.prepare(job, run, () -> false);
        var output = new FixedProjectRenderService(properties, json).render(request, () -> false);
        assertThat(output.durationMs()).isEqualTo(4000); assertThat(output.frameCount()).isEqualTo(120);
        Path decoded = root.resolve("bgm-decoded.pcm");
        command("ffmpeg", "-v", "error", "-nostdin", "-i", output.files().get("video.mp4").path().toString(), "-map", "0:a:0", "-ac", "1", "-ar", "48000", "-f", "s16le", "-y", decoded.toString());
        byte[] audio = Files.readAllBytes(decoded);
        double soloMusic = amplitude(audio, .4, .7, 220), originalMusic = amplitude(audio, 1.4, 1.7, 220), narrationMusic = amplitude(audio, 2.4, 2.7, 220);
        assertThat(soloMusic).isGreaterThan(.005);
        assertThat(originalMusic).isGreaterThan(.001).isLessThan(soloMusic * .8);
        assertThat(narrationMusic).isGreaterThan(.001).isLessThan(soloMusic * .8);
        assertThat(amplitude(audio, 1.4, 1.7, 440)).isGreaterThan(.02);
        assertThat(amplitude(audio, 2.4, 2.7, 660)).isGreaterThan(.02);
        assertThat(amplitude(audio, 3.5, 3.8, 220)).isGreaterThan(soloMusic * .8);
        assertThat(json.readTree(output.files().get("manifest.json").path().toFile()).path("media").path("fullDecodePassed").asBoolean()).isTrue();
        System.out.println("S4_BGM_NATIVE_OK frames=120 music=" + soloMusic + " originalDucked=" + originalMusic + " recordingDucked=" + narrationMusic + " providerCalled=false");
        files.discard(sourceRequest);
    }

    @Test void noDialogueWithOnlyMusicPassesRealRenderingAndRecoveredArtifactVerification() throws Exception {
        Path musicFile = root.resolve("music-only.wav");
        command("ffmpeg", "-v", "error", "-nostdin", "-f", "lavfi", "-i", "sine=frequency=220:sample_rate=48000:duration=2",
                "-c:a", "pcm_s16le", "-ac", "2", "-y", musicFile.toString());
        Asset music = asset("music", "MUSIC", "audio/wav", musicFile);
        var brief = json.createObjectNode().put("targetDurationMs", 1000).put("dialogueMode", "NO_DIALOGUE");
        brief.putObject("backgroundMusic").put("assetId", "music").put("sourceInMs", 250).put("sourceOutMs", 1250)
                .put("gainDb", -12).put("fadeInMs", 100).put("fadeOutMs", 100).put("ducking", false);
        var candidate = new Candidate(List.of(StoryboardCompilerTest.scene("title", "TITLE_CARD", 1000, null, null, null, "NO_DIALOGUE", null, List.of())));
        var project = new StoryboardCompiler(json).compile("only-music-board", brief, candidate, List.of(music), "only-music-run", "source-attempt");
        assertThat(project.path("assets")).hasSize(1);
        assertThat(project.path("scenes").get(0).path("voice").isNull()).isTrue();
        assertThat(project.path("scenes").get(0).has("clip")).isFalse();
        var storage = mock(ObjectStorageService.class);
        when(storage.headObject(music.bucketName(), music.objectKey())).thenReturn(new StoredObjectMetadata(music.bytes(), "audio/wav", "etag"));
        doAnswer(i -> { Files.copy(musicFile, i.getArgument(2, Path.class)); return null; }).when(storage)
                .downloadObject(eq(music.bucketName()), eq(music.objectKey()), any(Path.class), any());
        var properties = new FixedProjectRendererProperties(); properties.setEnabled(true); properties.setWorkRoot(root.resolve("only-music-work").toString());
        properties.setModuleRoot(System.getenv("LINKAGENT_RENDERER_MODULE_ROOT")); properties.setPythonPath("python");
        var files = new StoryboardProjectFiles(properties, storage, json);
        var source = files.materialize(project, List.of(music), () -> false);
        var recovery = new FixedProjectJobFiles(properties, json); var job = recovery.freeze("native-task", "default", source);
        Run run = mock(Run.class); when(run.runId()).thenReturn("only-music-run"); when(run.taskId()).thenReturn("native-task"); when(run.ownerId()).thenReturn("default"); when(run.leaseGeneration()).thenReturn(1L);
        var request = recovery.prepare(job, run, () -> false); var renderer = new FixedProjectRenderService(properties, json);
        var output = renderer.render(request, () -> false);
        assertThat(output.durationMs()).isEqualTo(1000); assertThat(output.frameCount()).isEqualTo(30);
        var manifest = json.readTree(output.files().get("manifest.json").path().toFile());
        assertThat(manifest.path("media").path("decodedPeak").asDouble()).isGreaterThan(.005);
        assertThat(manifest.path("media").path("fullDecodePassed").asBoolean()).isTrue();
        var verified = renderer.verifyExisting(request, () -> false);
        assertThat(verified.artifacts().inputHash()).isEqualTo(request.inputHash());
        assertThat(verified.artifacts().files()).hasSize(8);
        assertThat(Files.readString(output.files().get("subtitles.srt").path())).isBlank();
        System.out.println("S4_BGM_ONLY_NATIVE_OK frames=30 dialogue=NO_DIALOGUE voice=false clip=false artifactVerified=true providerCalled=false");
        files.discard(source);
    }

    @Test void extendedSceneReordersGlobalSubtitlesAndKeepsMusicCoverage() throws Exception {
        // 工程对应“场景由 1 秒延展到 2 秒、视频选区同步 0→2000、音乐区间 1→4 秒”的重排结果。
        Path source = Files.createDirectory(root.resolve("timeline-source"));
        Path clip = source.resolve("clip.mp4"), voice = source.resolve("voice.wav"), music = source.resolve("music.wav");
        command("ffmpeg", "-v", "error", "-nostdin", "-threads", "2", "-f", "lavfi", "-i", "color=c=navy:size=240x160:rate=30:duration=3",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=3", "-map", "0:v:0", "-map", "1:a:0",
                "-c:v", "libx264", "-threads", "2", "-pix_fmt", "yuv420p", "-c:a", "aac", "-ac", "2", "-shortest", "-y", clip.toString());
        command("ffmpeg", "-v", "error", "-nostdin", "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000:duration=0.9",
                "-c:a", "pcm_s16le", "-ac", "2", "-y", voice.toString());
        command("ffmpeg", "-v", "error", "-nostdin", "-f", "lavfi", "-i", "sine=frequency=220:sample_rate=48000:duration=5",
                "-c:a", "pcm_s16le", "-ac", "2", "-y", music.toString());
        Asset video = asset("clip", "VIDEO_CLIP", "video/mp4", clip), narration = asset("voice", "NARRATION", "audio/wav", voice),
                bgm = asset("music", "MUSIC", "audio/wav", music);
        List<Asset> assets = List.of(video, narration, bgm);
        var scenes = List.of(
                StoryboardCompilerTest.scene("opening", "VIDEO_CLIP", 2000, "clip", 0L, 2000L, "NARRATION", "voice",
                        List.of(new Cue(0, 900, "延展后的旁白"))),
                StoryboardCompilerTest.scene("closing", "TITLE_CARD", 1000, null, null, null, "NARRATION", "voice",
                        List.of(new Cue(0, 900, "重排后的旁白"))));
        ObjectNode brief = json.createObjectNode().put("targetDurationMs", 3000).put("dialogueMode", "USER_NARRATION");
        brief.putObject("backgroundMusic").put("assetId", "music").put("sourceInMs", 1000).put("sourceOutMs", 4000)
                .put("gainDb", -12).put("fadeInMs", 100).put("fadeOutMs", 100).put("ducking", false);
        var project = new StoryboardCompiler(json).compile("timeline-board", brief, new Candidate(scenes), assets,
                "timeline-run", "source-attempt");
        assertThat(project.path("video").path("frameCount").asInt()).isEqualTo(90);
        // 工程字段名是 sourceEndMs；简报字段名 sourceOutMs，两者不能混用。
        assertThat(project.path("backgroundMusic").path("sourceEndMs").asLong()).isEqualTo(4000);
        assertThat(project.path("scenes").get(0).path("clip").path("sourceEndMs").asLong()).isEqualTo(2000);
        var storage = mock(ObjectStorageService.class);
        Map<String, Path> objects = Map.of(video.objectKey(), clip, narration.objectKey(), voice, bgm.objectKey(), music);
        when(storage.headObject(eq("persisted-native-bucket"), anyString())).thenAnswer(i ->
                new StoredObjectMetadata(Files.size(objects.get(i.getArgument(1, String.class))), "application/octet-stream", "etag"));
        doAnswer(i -> { Files.copy(objects.get(i.getArgument(1, String.class)), i.getArgument(2, Path.class)); return null; })
                .when(storage).downloadObject(eq("persisted-native-bucket"), anyString(), any(Path.class), any());
        var properties = new FixedProjectRendererProperties(); properties.setEnabled(true);
        properties.setWorkRoot(root.resolve("timeline-work").toString());
        properties.setModuleRoot(System.getenv("LINKAGENT_RENDERER_MODULE_ROOT")); properties.setPythonPath("python");
        var files = new StoryboardProjectFiles(properties, storage, json);
        var request = files.materialize(project, assets, () -> false);
        var output = new FixedProjectRenderService(properties, json).render(request, () -> false);
        assertThat(output.durationMs()).isEqualTo(3000);
        assertThat(output.frameCount()).isEqualTo(90);
        // 第一段延展 1 秒后，第二段的全局字幕起点必须随重排后移，而不是沿用旧时间码。
        String srt = Files.readString(output.files().get("subtitles.srt").path());
        assertThat(srt).contains("00:00:00,000 --> 00:00:00,900", "00:00:02,000 --> 00:00:02,900", "重排后的旁白");
        Path decoded = root.resolve("timeline-decoded.pcm");
        command("ffmpeg", "-v", "error", "-nostdin", "-i", output.files().get("video.mp4").path().toString(),
                "-map", "0:a:0", "-ac", "1", "-ar", "48000", "-f", "s16le", "-y", decoded.toString());
        byte[] audio = Files.readAllBytes(decoded);
        // 音乐延展后应覆盖到片尾；旁白在两段各自的实际位置出现。
        assertThat(amplitude(audio, .2, .5, 220)).isGreaterThan(.005);
        assertThat(amplitude(audio, 2.5, 2.8, 220)).isGreaterThan(.005);
        assertThat(amplitude(audio, .2, .5, 660)).isGreaterThan(.02);
        assertThat(amplitude(audio, 2.2, 2.5, 660)).isGreaterThan(.02);
        assertThat(json.readTree(output.files().get("manifest.json").path().toFile())
                .path("media").path("fullDecodePassed").asBoolean()).isTrue();
        System.out.println("S4_TIMELINE_NATIVE_OK frames=90 extendedVideoMs=2000 musicEndMs=4000 subtitles=2 providerCalled=false");
        files.discard(request);
    }

    @Test void realAmbienceKeepsItsLevelWhileMusicDucksUnderNarration() throws Exception {
        Path source = Files.createDirectory(root.resolve("ambience-source"));
        Path voice = source.resolve("voice.wav"), music = source.resolve("music.wav"), bed = source.resolve("ambience.wav");
        command("ffmpeg", "-v", "error", "-nostdin", "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000:duration=0.9",
                "-c:a", "pcm_s16le", "-ac", "2", "-y", voice.toString());
        command("ffmpeg", "-v", "error", "-nostdin", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=4",
                "-c:a", "pcm_s16le", "-ac", "2", "-y", music.toString());
        command("ffmpeg", "-v", "error", "-nostdin", "-f", "lavfi", "-i", "sine=frequency=220:sample_rate=48000:duration=4",
                "-c:a", "pcm_s16le", "-ac", "2", "-y", bed.toString());
        Asset narration = asset("voice", "NARRATION", "audio/wav", voice), bgm = asset("music", "MUSIC", "audio/wav", music),
                ambience = asset("ambience", "MUSIC", "audio/wav", bed);
        List<Asset> assets = List.of(narration, bgm, ambience);
        var scenes = List.of(StoryboardCompilerTest.scene("narrated", "TITLE_CARD", 3000, null, null, null,
                "NARRATION", "voice", List.of(new Cue(0, 900, "环境声与压低对白测试"))));
        ObjectNode brief = json.createObjectNode().put("targetDurationMs", 3000).put("dialogueMode", "USER_NARRATION");
        brief.putObject("backgroundMusic").put("assetId", "music").put("sourceInMs", 0).put("sourceOutMs", 3000)
                .put("gainDb", -6).put("fadeInMs", 0).put("fadeOutMs", 0).put("ducking", true);
        brief.putObject("ambience").put("assetId", "ambience").put("sourceInMs", 0).put("sourceOutMs", 3000)
                .put("gainDb", -24).put("fadeInMs", 0).put("fadeOutMs", 0);
        var project = new StoryboardCompiler(json).compile("ambience-board", brief, new Candidate(scenes), assets,
                "ambience-run", "source-attempt");
        assertThat(project.path("ambience").path("gainDb").asDouble()).isEqualTo(-24.0);
        assertThat(project.path("ambience").has("ducking")).isFalse();
        var storage = mock(ObjectStorageService.class);
        Map<String, Path> objects = Map.of(narration.objectKey(), voice, bgm.objectKey(), music, ambience.objectKey(), bed);
        when(storage.headObject(eq("persisted-native-bucket"), anyString())).thenAnswer(i ->
                new StoredObjectMetadata(Files.size(objects.get(i.getArgument(1, String.class))), "application/octet-stream", "etag"));
        doAnswer(i -> { Files.copy(objects.get(i.getArgument(1, String.class)), i.getArgument(2, Path.class)); return null; })
                .when(storage).downloadObject(eq("persisted-native-bucket"), anyString(), any(Path.class), any());
        var properties = new FixedProjectRendererProperties(); properties.setEnabled(true);
        properties.setWorkRoot(root.resolve("ambience-work").toString());
        properties.setModuleRoot(System.getenv("LINKAGENT_RENDERER_MODULE_ROOT")); properties.setPythonPath("python");
        var files = new StoryboardProjectFiles(properties, storage, json);
        var request = files.materialize(project, assets, () -> false);
        var output = new FixedProjectRenderService(properties, json).render(request, () -> false);
        assertThat(output.durationMs()).isEqualTo(3000);
        assertThat(output.frameCount()).isEqualTo(90);
        Path decoded = root.resolve("ambience-decoded.pcm");
        command("ffmpeg", "-v", "error", "-nostdin", "-i", output.files().get("video.mp4").path().toString(),
                "-map", "0:a:0", "-ac", "1", "-ar", "48000", "-f", "s16le", "-y", decoded.toString());
        byte[] audio = Files.readAllBytes(decoded);
        // 0.1–0.4 秒有旁白，1.5–1.8 秒没有：环境声两段一致，背景音乐在后一段恢复。
        double quietBed = amplitude(audio, .1, .4, 220), laterBed = amplitude(audio, 1.5, 1.8, 220);
        assertThat(quietBed).isGreaterThan(.002);
        assertThat(laterBed).isCloseTo(quietBed, org.assertj.core.data.Percentage.withPercentage(25));
        assertThat(amplitude(audio, 1.5, 1.8, 440)).isGreaterThan(amplitude(audio, .1, .4, 440) * 1.5);
        assertThat(amplitude(audio, .1, .4, 660)).isGreaterThan(.02);
        assertThat(json.readTree(output.files().get("manifest.json").path().toFile())
                .path("media").path("fullDecodePassed").asBoolean()).isTrue();
        System.out.println("S4_AMBIENCE_NATIVE_OK frames=90 bedQuiet=" + quietBed + " bedLater=" + laterBed
                + " musicRecovers=true providerCalled=false");
        files.discard(request);
    }

    private double amplitude(byte[] pcm, double startSeconds, double endSeconds, int frequency) {
        int start = (int)Math.round(startSeconds * 48000), end = (int)Math.round(endSeconds * 48000);
        var samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN); double sine = 0, cosine = 0;
        for (int index = start; index < end; index++) {
            double value = samples.getShort(index * 2) / 32768.0, phase = 2 * Math.PI * frequency * index / 48000;
            sine += value * Math.sin(phase); cosine += value * Math.cos(phase);
        }
        return 2 * Math.hypot(sine, cosine) / (end - start);
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
