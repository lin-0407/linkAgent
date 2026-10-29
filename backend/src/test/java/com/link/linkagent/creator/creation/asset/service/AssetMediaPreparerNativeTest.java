package com.link.linkagent.creator.creation.asset.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.render.FixedProjectRendererProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "LINKAGENT_ASSET_NATIVE_TESTS", matches = "true")
class AssetMediaPreparerNativeTest {
    @TempDir Path directory;

    private AssetMediaPreparer preparer() {
        FixedProjectRendererProperties properties = new FixedProjectRendererProperties();
        properties.setTimeout(Duration.ofMinutes(2));
        return new AssetMediaPreparer(new ObjectMapper(), properties);
    }
    private Path output() throws Exception { return Files.createTempDirectory(directory, "prepared-"); }

    @ParameterizedTest @ValueSource(strings = {"wav", "mp3", "m4a"})
    void decodesWholeAudioAndCreatesRealPcm(String extension) throws Exception {
        Path source = directory.resolve("speech." + extension);
        String codec = switch (extension) { case "mp3" -> "libmp3lame"; case "m4a" -> "aac"; default -> "pcm_s16le"; };
        ffmpeg("-f", "lavfi", "-i", "sine=frequency=620:sample_rate=44100:duration=0.8", "-c:a", codec, source.toString());
        var result = preparer().prepare(source, output(), "NARRATION", () -> false);
        assertTrue(result.hasAudio()); assertNull(result.width()); assertNull(result.height());
        assertTrue(result.durationMs() >= 750 && result.durationMs() <= 1000);
        assertEquals("pcm_s16le", result.normalizedMetadata().path("streams").get(0).path("codec_name").asText());
        assertEquals(48000, result.normalizedMetadata().path("streams").get(0).path("sample_rate").asInt());
        assertEquals(2, result.normalizedMetadata().path("streams").get(0).path("channels").asInt());
        assertEquals("audio/wav", result.files().getFirst().contentType());
        assertTrue(Files.size(result.files().getFirst().path()) > 140000);
        assertFalse(result.originalMetadata().toString().contains(directory.toString()));
    }

    @Test void pngKeepsTransparencyAndCreatesARealThumbnail() throws Exception {
        Path source = directory.resolve("photo.png");
        BufferedImage image = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(20, 10, 0x80ff0000); ImageIO.write(image, "png", source.toFile());
        var result = preparer().prepare(source, output(), "IMAGE", () -> false);
        assertEquals(80, result.width()); assertEquals(40, result.height()); assertNull(result.durationMs());
        assertFalse(result.hasAudio()); assertEquals(2, result.files().size());
        BufferedImage normalized = ImageIO.read(result.files().getFirst().path().toFile());
        assertEquals(0x80ff0000, normalized.getRGB(20, 10));
        assertNotNull(ImageIO.read(result.files().get(1).path().toFile()));
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void appliesAllJpegExifDirectionsToPixels(int orientation) throws Exception {
        BufferedImage image = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 40; y++) for (int x = 0; x < 80; x++) image.setRGB(x, y, x < 40 ? Color.RED.getRGB() : Color.BLUE.getRGB());
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream(); ImageIO.write(image, "jpeg", jpeg);
        byte[] exif = new byte[32];
        ByteBuffer buffer = ByteBuffer.wrap(exif).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'E', 'x', 'i', 'f', 0, 0, 'I', 'I'}); buffer.putShort((short)42); buffer.putInt(8);
        buffer.putShort((short)1); buffer.putShort((short)274); buffer.putShort((short)3); buffer.putInt(1); buffer.putShort((short)orientation);
        byte[] raw = jpeg.toByteArray(); ByteArrayOutputStream tagged = new ByteArrayOutputStream();
        tagged.write(raw, 0, 2); tagged.write(255); tagged.write(225); tagged.write(0); tagged.write(exif.length + 2); tagged.write(exif); tagged.write(raw, 2, raw.length - 2);
        Path source = directory.resolve("oriented.jpg"); Files.write(source, tagged.toByteArray());
        var result = preparer().prepare(source, output(), "IMAGE", () -> false);
        assertEquals(orientation >= 5 ? 40 : 80, result.width()); assertEquals(orientation >= 5 ? 80 : 40, result.height());
        assertEquals(orientation, result.originalMetadata().path("exifOrientation").asInt());
        BufferedImage normalized = ImageIO.read(result.files().getFirst().path().toFile());
        int expectedRedX = switch (orientation) { case 2, 3 -> 69; case 6, 7 -> 29; default -> 10; };
        int expectedRedY = switch (orientation) { case 3, 4 -> 29; case 7, 8 -> 69; default -> 10; };
        Color red = new Color(normalized.getRGB(expectedRedX, expectedRedY));
        assertTrue(red.getRed() > 200 && red.getBlue() < 50);
    }

    @Test void videoKeepsOriginalSoundAndNormalizesFrameRate() throws Exception {
        Path source = directory.resolve("clip.mp4");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=size=160x90:rate=24:duration=1", "-f", "lavfi", "-i", "sine=frequency=880:duration=1", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", source.toString());
        var result = preparer().prepare(source, output(), "VIDEO_CLIP", () -> false);
        assertTrue(result.hasAudio()); assertEquals(160, result.width()); assertEquals(90, result.height());
        assertEquals("30/1", result.normalizedMetadata().path("streams").get(0).path("avg_frame_rate").asText());
        assertEquals(2, result.files().size()); assertNotNull(ImageIO.read(result.files().get(1).path().toFile()));
        assertTrue(result.durationMs() >= 950 && result.durationMs() <= 1100);
        Path decoded = directory.resolve("decoded-sound.wav");
        ffmpeg("-i", result.files().getFirst().path().toString(), "-vn", "-c:a", "pcm_s16le", decoded.toString());
        byte[] bytes = Files.readAllBytes(decoded); long energy = 0;
        // 检查实际解码后的 PCM 能量，避免只因存在音轨标签就声称原声保留。
        for (int i = 256; i + 1 < bytes.length; i += 2) energy += Math.abs((short)((bytes[i] & 255) | bytes[i + 1] << 8));
        assertTrue(energy > 1000000);
    }

    @Test void silentVideoRemainsSilentAndVfrBecomesConstantRate() throws Exception {
        Path source = directory.resolve("vfr.mp4");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=size=160x90:rate=30:duration=1.2", "-vf", "select='if(lt(n,15),not(mod(n,2)),1)'", "-fps_mode", "vfr", "-c:v", "libx264", "-an", source.toString());
        var result = preparer().prepare(source, output(), "VIDEO_CLIP", () -> false);
        assertFalse(result.hasAudio());
        assertEquals("30/1", result.normalizedMetadata().path("streams").get(0).path("avg_frame_rate").asText());
        assertEquals(1, result.normalizedMetadata().path("streams").size());
    }

    @Test void rotationMetadataBecomesUprightPixels() throws Exception {
        Path base = directory.resolve("base.mp4"), source = directory.resolve("rotated.mov");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=size=160x90:rate=30:duration=0.5", "-c:v", "libx264", "-an", base.toString());
        Path help = directory.resolve("ffmpeg-help.txt");
        runFixtureTool("ffmpeg", help, "-hide_banner", "-h", "full");
        if (Files.readString(help).contains("-display_rotation")) {
            ffmpeg("-display_rotation", "90", "-i", base.toString(), "-c", "copy", source.toString());
        } else {
            ffmpeg("-i", base.toString(), "-c", "copy", "-metadata:s:v:0", "rotate=90", source.toString());
        }
        Path probe = directory.resolve("rotation-fixture.json");
        runFixtureTool("ffprobe", probe, "-v", "error", "-select_streams", "v:0", "-show_streams", "-of", "json", source.toString());
        var stream = new ObjectMapper().readTree(Files.readString(probe)).path("streams").get(0);
        boolean hasRotation = false;
        for (var side : stream.path("side_data_list")) {
            if ("Display Matrix".equals(side.path("side_data_type").asText())
                    && Math.floorMod(side.path("rotation").asInt(), 360) == 90
                    && !side.path("displaymatrix").asText().isBlank()) hasRotation = true;
        }
        assertTrue(hasRotation, "测试输入必须先确认带有真实的 90 度 Display Matrix：" + stream);
        var result = preparer().prepare(source, output(), "VIDEO_CLIP", () -> false);
        assertEquals(90, result.width()); assertEquals(160, result.height());
        for (var side : result.normalizedMetadata().path("streams").get(0).path("side_data_list")) assertEquals(0, side.path("rotation").asInt());
    }

    @Test void rejectsHdrRatherThanChangingItsColors() throws Exception {
        Path source = directory.resolve("hdr.mp4");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=size=160x90:rate=30:duration=0.3", "-c:v", "libx264", "-color_primaries", "bt2020", "-color_trc", "arib-std-b67", "-colorspace", "bt2020nc", source.toString());
        assertEquals("UNSUPPORTED_MEDIA", assertThrows(AssetMediaPreparer.AssetPreparationException.class,
                () -> preparer().prepare(source, output(), "VIDEO_CLIP", () -> false)).code());
    }

    @Test void rejectsTwoAudioTracksWithoutChoosingOneSilently() throws Exception {
        Path source = directory.resolve("two-audio.mp4");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=size=160x90:rate=30:duration=0.3", "-f", "lavfi", "-i", "sine=frequency=440:duration=0.3", "-f", "lavfi", "-i", "sine=frequency=880:duration=0.3", "-map", "0:v", "-map", "1:a", "-map", "2:a", "-c:v", "libx264", "-c:a", "aac", source.toString());
        assertEquals("UNSUPPORTED_MEDIA", assertThrows(AssetMediaPreparer.AssetPreparationException.class,
                () -> preparer().prepare(source, output(), "VIDEO_CLIP", () -> false)).code());
    }

    @Test void rejectsUnsupportedCodecAndCleansAnyPartialFiles() throws Exception {
        Path source = directory.resolve("mpeg4.mp4");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=size=160x90:rate=30:duration=0.3", "-c:v", "mpeg4", source.toString());
        Path output = output();
        assertEquals("UNSUPPORTED_MEDIA", assertThrows(AssetMediaPreparer.AssetPreparationException.class,
                () -> preparer().prepare(source, output, "VIDEO_CLIP", () -> false)).code());
        try (var files = Files.list(output)) { assertEquals(0, files.count()); }
    }

    @Test void cancellationDuringPreparationReapsProcessesAndCleansPartialFiles() throws Exception {
        Path source = directory.resolve("speech.wav");
        ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:duration=2", source.toString());
        Path output = output(); AtomicInteger checks = new AtomicInteger();
        assertEquals("CANCELLED", assertThrows(AssetMediaPreparer.AssetPreparationException.class,
                () -> preparer().prepare(source, output, "MUSIC", () -> checks.incrementAndGet() >= 6)).code());
        try (var files = Files.list(output)) { assertEquals(0, files.count()); }
        assertTrue(Files.exists(source));
    }

    @Test void truncatedVideoNeverBecomesPrepared() throws Exception {
        Path good = directory.resolve("good.mp4"), source = directory.resolve("truncated.mp4");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=size=160x90:rate=30:duration=1", "-c:v", "libx264", "-movflags", "+faststart", good.toString());
        byte[] bytes = Files.readAllBytes(good); Files.write(source, java.util.Arrays.copyOf(bytes, bytes.length / 2));
        Path output = output();
        assertThrows(AssetMediaPreparer.AssetPreparationException.class,
                () -> preparer().prepare(source, output, "VIDEO_CLIP", () -> false));
        try (var files = Files.list(output)) { assertEquals(0, files.count()); }
    }

    private void ffmpeg(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-v", "error", "-nostdin", "-threads", "2", "-filter_threads", "2"));
        command.addAll(List.of(arguments));
        Path log = directory.resolve("fixture-" + System.nanoTime() + ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "生成原生测试素材超时");
            assertEquals(0, process.exitValue(), () -> { try { return Files.readString(log); } catch (Exception e) { return e.toString(); } });
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private void runFixtureTool(String executable, Path output, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(executable));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "检查原生测试夹具超时");
            assertEquals(0, process.exitValue(), () -> { try { return Files.readString(output); } catch (Exception e) { return e.toString(); } });
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
