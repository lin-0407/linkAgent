package com.link.linkagent.creator.creation.asset.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.render.FixedProjectRendererProperties;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 只准备已下载的本地素材；收费调用、存储归属和 READY 登记由上层处理。 */
@Component
public class AssetMediaPreparer {
    public static final long MAX_SOURCE_BYTES = 1024L * 1024 * 1024;
    public static final long MAX_DURATION_MS = 20 * 60 * 1000L;
    private static final long MAX_OUTPUT_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long MAX_PIXELS = 3840L * 2160;
    private static final int MAX_OUTPUT_TEXT = 256 * 1024;
    private static final Set<String> KINDS = Set.of("IMAGE", "NARRATION", "MUSIC", "VIDEO_CLIP");
    private static final Set<String> AUDIO_CODECS = Set.of("aac", "mp3", "pcm_u8", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_f64le");
    private static final Set<String> SYSTEM_ENV = Set.of("PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "LANG", "LD_LIBRARY_PATH");
    private final ObjectMapper json;
    private final FixedProjectRendererProperties properties;

    public AssetMediaPreparer(ObjectMapper json, FixedProjectRendererProperties properties) {
        this.json = json;
        this.properties = properties;
    }

    public record DerivedFile(String role, String fileName, String contentType, Path path) { }
    public record Result(JsonNode originalMetadata, JsonNode normalizedMetadata, List<DerivedFile> files,
                         Long durationMs, Integer width, Integer height, boolean hasAudio) { }

    public static final class AssetPreparationException extends RuntimeException {
        private final String code;
        public AssetPreparationException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    public Result prepare(Path source, Path outputDir, String kind, BooleanSupplier cancelled) {
        if (source == null || outputDir == null || cancelled == null || kind == null || !KINDS.contains(kind))
            throw failure("INVALID_MEDIA", "素材类型或准备参数无效");
        Duration timeout = properties.getTimeout();
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofHours(1)) > 0)
            throw failure("ENVIRONMENT_UNAVAILABLE", "素材准备超时配置无效");
        if (properties.getFfmpegPath() == null || properties.getFfmpegPath().isBlank()
                || properties.getFfprobePath() == null || properties.getFfprobePath().isBlank())
            throw failure("ENVIRONMENT_UNAVAILABLE", "未配置 FFmpeg/FFprobe");
        long deadline = System.nanoTime() + timeout.toNanos();
        Runnable check = () -> {
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
                throw failure("CANCELLED", "素材准备已取消");
            if (System.nanoTime() - deadline >= 0) throw failure("TIMEOUT", "素材准备超时");
        };
        List<Path> created = new ArrayList<>();
        boolean succeeded = false;
        try {
            check.run();
            source = source.toAbsolutePath().normalize();
            outputDir = outputDir.toAbsolutePath().normalize();
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || !source.toRealPath().equals(source))
                throw failure("INVALID_MEDIA", "素材必须是本次下载的普通本地文件");
            long bytes = Files.size(source);
            if (bytes <= 0) throw failure("INVALID_MEDIA", "素材内容为空");
            if (bytes > MAX_SOURCE_BYTES) throw failure("LIMIT_EXCEEDED", "素材超过 1 GiB 上限");
            if (!Files.isDirectory(outputDir, LinkOption.NOFOLLOW_LINKS) || !outputDir.toRealPath().equals(outputDir))
                throw failure("INVALID_MEDIA", "素材准备目录不存在或包含符号链接");
            try (var entries = Files.list(outputDir)) {
                if (entries.findAny().isPresent()) throw failure("INVALID_MEDIA", "素材准备目录必须为空");
            }
            if (Files.getFileStore(outputDir).getUsableSpace() < MAX_OUTPUT_BYTES + 128L * 1024 * 1024)
                throw failure("LIMIT_EXCEEDED", "素材准备可用磁盘不足 2.125 GiB");
            Container container = detect(source);
            if ("IMAGE".equals(kind) != container.image)
                throw failure("UNSUPPORTED_MEDIA", "素材内容与所选类型不符");
            if ("VIDEO_CLIP".equals(kind) && container != Container.MOV)
                throw failure("UNSUPPORTED_MEDIA", "视频片段仅接受可解码的 MP4/MOV");
            if (("NARRATION".equals(kind) || "MUSIC".equals(kind)) && container.image)
                throw failure("UNSUPPORTED_MEDIA", "录音或音乐必须提供 WAV、MP3 或 M4A");
            int orientation = container.image ? imageOrientation(source, container, check) : 1;
            if (container.image) validateImageHeader(source);
            JsonNode original = probe(source, container, outputDir, check);
            Media media = validate(original, kind);
            List<DerivedFile> files = new ArrayList<>();
            Path normalized;
            if (container.image) {
                normalized = outputDir.resolve("normalized.png");
                Path thumbnail = outputDir.resolve("thumbnail.jpg");
                created.add(normalized); created.add(thumbnail);
                decodeEntire(source, container, outputDir, check);
                normalizeImage(source, orientation, normalized, thumbnail, check);
                ((com.fasterxml.jackson.databind.node.ObjectNode)original).put("exifOrientation", orientation);
                files.add(new DerivedFile("NORMALIZED", "normalized.png", "image/png", normalized));
                files.add(new DerivedFile("THUMBNAIL", "thumbnail.jpg", "image/jpeg", thumbnail));
            } else {
                boolean video = "VIDEO_CLIP".equals(kind);
                normalized = outputDir.resolve(video ? "normalized.mp4" : "normalized.wav");
                created.add(normalized);
                normalizeAv(source, container, normalized, media.hasAudio, video, outputDir, check);
                files.add(new DerivedFile("NORMALIZED", normalized.getFileName().toString(), video ? "video/mp4" : "audio/wav", normalized));
                if (video) {
                    Path thumbnail = outputDir.resolve("thumbnail.jpg");
                    created.add(thumbnail);
                    List<String> command = inputCommand(properties.getFfmpegPath(), normalized, Container.MOV);
                    command.addAll(List.of("-map", "0:v:0", "-frames:v", "1", "-vf", "scale=320:320:force_original_aspect_ratio=decrease", "-an", "-map_metadata", "-1", "-q:v", "3", "-y", thumbnail.toString()));
                    run(command, outputDir, check);
                    files.add(new DerivedFile("THUMBNAIL", "thumbnail.jpg", "image/jpeg", thumbnail));
                }
            }
            JsonNode normalizedMetadata = probe(normalized, container.image ? Container.PNG : "VIDEO_CLIP".equals(kind) ? Container.MOV : Container.WAV, outputDir, check);
            Media prepared = validate(normalizedMetadata, kind);
            verifyNormalized(media, prepared, kind);
            if (!container.image) decodeEntire(normalized, "VIDEO_CLIP".equals(kind) ? Container.MOV : Container.WAV, outputDir, check);
            for (DerivedFile file : files)
                if (!Files.isRegularFile(file.path(), LinkOption.NOFOLLOW_LINKS) || Files.size(file.path()) == 0)
                    throw failure("INVALID_MEDIA", "规范化素材或缩略图为空");
            if (Files.size(source) != bytes) throw failure("INVALID_MEDIA", "素材准备期间源文件发生变化");
            check.run();
            succeeded = true;
            return new Result(original, normalizedMetadata, List.copyOf(files), container.image ? null : prepared.durationMs,
                    prepared.width, prepared.height, prepared.hasAudio);
        } catch (IOException exception) {
            throw failure("INVALID_MEDIA", "无法读取或生成本地素材");
        } finally {
            // 只删除本次固定命名的派生产物，源文件和既有目录由调用者管理。
            if (!succeeded) for (Path path : created) try { Files.deleteIfExists(path); } catch (IOException ignored) { }
        }
    }

    private enum Container {
        PNG("png_pipe", true), JPEG("jpeg_pipe", true), WAV("wav", false), MP3("mp3", false), MOV("mov", false);
        final String demuxer; final boolean image;
        Container(String demuxer, boolean image) { this.demuxer = demuxer; this.image = image; }
    }

    private Container detect(Path source) throws IOException {
        byte[] head;
        try (var input = Files.newInputStream(source)) { head = input.readNBytes(32); }
        if (head.length >= 8 && Arrays.equals(Arrays.copyOf(head, 8), new byte[]{(byte)137, 80, 78, 71, 13, 10, 26, 10})) return Container.PNG;
        if (head.length >= 3 && (head[0] & 255) == 255 && (head[1] & 255) == 216 && (head[2] & 255) == 255) return Container.JPEG;
        if (head.length >= 12 && ascii(head, 0, 4).equals("RIFF") && ascii(head, 8, 4).equals("WAVE")) return Container.WAV;
        if (head.length >= 12 && ascii(head, 4, 4).equals("ftyp")) return Container.MOV;
        if (head.length >= 3 && ascii(head, 0, 3).equals("ID3")) return Container.MP3;
        if (head.length >= 2 && (head[0] & 255) == 255 && (head[1] & 0xe0) == 0xe0 && (head[1] & 6) != 0) return Container.MP3;
        throw failure("UNSUPPORTED_MEDIA", "仅支持 PNG/JPEG、WAV/MP3/M4A 和 MP4/MOV；裸 AAC 缺少可靠时长，请先封装为 M4A");
    }

    private String ascii(byte[] bytes, int start, int count) { return new String(bytes, start, count, StandardCharsets.US_ASCII); }

    private JsonNode probe(Path source, Container container, Path outputDir, Runnable check) throws IOException {
        List<String> command = new ArrayList<>(List.of(properties.getFfprobePath(), "-v", "error", "-max_alloc", "67108864", "-threads", "2", "-protocol_whitelist", "file", "-f", container.demuxer));
        if (container == Container.MOV) command.addAll(List.of("-enable_drefs", "0", "-use_absolute_path", "0"));
        if (container.image) command.add("-count_frames");
        // 只取媒体技术字段，避免把原始文件路径、标签里的密钥或用户备注写入持久化元数据。
        command.addAll(List.of("-show_entries", "format=format_name,duration,start_time,size:stream=index,codec_type,codec_name,width,height,duration,start_time,sample_rate,channels,sample_aspect_ratio,avg_frame_rate,r_frame_rate,pix_fmt,color_space,color_transfer,color_primaries,color_range,nb_read_frames:stream_disposition=attached_pic:stream_side_data=rotation", "-of", "json", source.toString()));
        String text = run(command, outputDir, check);
        JsonNode result = json.readTree(text);
        if (result == null || !result.isObject()) throw failure("INVALID_MEDIA", "素材探测未返回有效元数据");
        return result;
    }

    private record Media(long durationMs, Integer width, Integer height, boolean hasAudio, JsonNode video, JsonNode audio) { }

    private Media validate(JsonNode root, String kind) {
        JsonNode streams = root.path("streams");
        if (!streams.isArray() || streams.isEmpty() || streams.size() > 16)
            throw failure("INVALID_MEDIA", "素材没有可识别的媒体流");
        List<JsonNode> videos = new ArrayList<>(), audios = new ArrayList<>();
        for (JsonNode stream : streams) {
            String type = stream.path("codec_type").asText();
            if ("video".equals(type) && stream.path("disposition").path("attached_pic").asInt() != 1) videos.add(stream);
            else if ("audio".equals(type)) audios.add(stream);
            else if ("subtitle".equals(type)) throw failure("UNSUPPORTED_MEDIA", "片段含独立字幕轨，请先导出字幕并选择要保留的画面与声音");
        }
        if (audios.size() > 1 || videos.size() > 1)
            throw failure("UNSUPPORTED_MEDIA", "首发暂不支持多画面或多音轨素材，请先选择需要的音轨导出");
        boolean image = "IMAGE".equals(kind), video = "VIDEO_CLIP".equals(kind);
        if ((image || video) && videos.size() != 1 || !image && !video && (audios.size() != 1 || !videos.isEmpty()) || image && !audios.isEmpty())
            throw failure("UNSUPPORTED_MEDIA", "素材中的画面或声音与所选用途不符");
        JsonNode visual = videos.isEmpty() ? null : videos.getFirst();
        JsonNode audio = audios.isEmpty() ? null : audios.getFirst();
        Integer width = visual == null ? null : visual.path("width").asInt();
        Integer height = visual == null ? null : visual.path("height").asInt();
        if (visual != null) {
            checkDimensions(width, height);
            if (image && visual.path("nb_read_frames").asInt() != 1)
                throw failure("UNSUPPORTED_MEDIA", "图片必须是单帧静态图片，请将动图或图片序列转为视频片段");
            String codec = visual.path("codec_name").asText();
            if (image ? !Set.of("png", "mjpeg").contains(codec) : !Set.of("h264", "hevc").contains(codec))
                throw failure("UNSUPPORTED_MEDIA", "素材画面编码不在已支持范围内");
            if (!image) {
                String transfer = visual.path("color_transfer").asText();
                String primaries = visual.path("color_primaries").asText();
                String space = visual.path("color_space").asText();
                if (Set.of("smpte2084", "arib-std-b67").contains(transfer) || primaries.equals("bt2020") || space.startsWith("bt2020"))
                    throw failure("UNSUPPORTED_MEDIA", "HDR/BT.2020 片段需要先转换为 SDR，平台不会静默改变颜色");
                if (!Set.of("yuv420p", "yuvj420p", "yuv422p", "yuvj422p", "yuv444p", "yuvj444p", "nv12", "nv21", "gray", "rgb24", "bgr24", "gbrp").contains(visual.path("pix_fmt").asText()))
                    throw failure("UNSUPPORTED_MEDIA", "暂不支持高位深或无法确认的像素格式，请先转换为明确的 SDR 8-bit 片段");
                double sar = rational(visual.path("sample_aspect_ratio").asText("1:1"), 1);
                if (!(sar > 0) || sar > 16 || width * sar > 4096 || width * sar * height > MAX_PIXELS)
                    throw failure("LIMIT_EXCEEDED", "素材显示尺寸超过 4K 工作上限");
                for (JsonNode side : visual.path("side_data_list")) {
                    double rotation = side.path("rotation").asDouble(0);
                    if (!Double.isFinite(rotation) || Math.abs(rotation / 90 - Math.rint(rotation / 90)) > .001)
                        throw failure("UNSUPPORTED_MEDIA", "暂不支持非直角旋转的视频，请先校正方向");
                }
            }
        }
        if (audio != null && !AUDIO_CODECS.contains(audio.path("codec_name").asText()))
            throw failure("UNSUPPORTED_MEDIA", "素材音轨编码不支持，请转换为 AAC、MP3 或 PCM；原声不会被静默丢弃");
        if (audio != null && (audio.path("channels").asInt() < 1 || audio.path("channels").asInt() > 2))
            throw failure("UNSUPPORTED_MEDIA", "首发仅支持单声道或立体声音轨，请先明确下混方案");
        long duration = image ? 0 : duration(root);
        if (!image && duration <= 0) throw failure("INVALID_MEDIA", "素材实际时长无法可靠识别");
        if (duration > MAX_DURATION_MS) throw failure("LIMIT_EXCEEDED", "素材时长超过 20 分钟上限");
        return new Media(duration, width, height, audio != null, visual, audio);
    }

    private void verifyNormalized(Media original, Media normalized, String kind) {
        if ("IMAGE".equals(kind)) return;
        if (Math.abs(original.durationMs - normalized.durationMs) > 250)
            throw failure("INVALID_MEDIA", "素材规范化后时长不一致，可能存在截断或异常时间戳");
        if (original.hasAudio != normalized.hasAudio) throw failure("INVALID_MEDIA", "素材规范化后原声音轨不完整");
        if (normalized.audio != null && (normalized.audio.path("sample_rate").asInt() != 48000 || normalized.audio.path("channels").asInt() != 2))
            throw failure("INVALID_MEDIA", "规范化音轨未达到 48 kHz 立体声工作格式");
        if ("VIDEO_CLIP".equals(kind) && (!"h264".equals(normalized.video.path("codec_name").asText())
                || Math.abs(rational(normalized.video.path("avg_frame_rate").asText(), 0) - 30) > .001
                || !"yuv420p".equals(normalized.video.path("pix_fmt").asText())
                || normalized.hasAudio && !"aac".equals(normalized.audio.path("codec_name").asText())))
            throw failure("INVALID_MEDIA", "规范化视频未达到 H.264/AAC、30 fps、SDR 8-bit 工作格式");
        if (!"VIDEO_CLIP".equals(kind) && !"pcm_s16le".equals(normalized.audio.path("codec_name").asText()))
            throw failure("INVALID_MEDIA", "规范化音频未达到 PCM 16-bit 工作格式");
    }

    private long duration(JsonNode root) {
        double seconds = number(root.path("format").path("duration").asText());
        for (JsonNode stream : root.path("streams")) if (stream.path("disposition").path("attached_pic").asInt() != 1)
            seconds = Math.max(seconds, number(stream.path("duration").asText()));
        if (!Double.isFinite(seconds) || seconds > Long.MAX_VALUE / 1000.0) throw failure("INVALID_MEDIA", "素材时长元数据无效");
        return Math.round(seconds * 1000);
    }
    private double number(String value) { try { return Double.parseDouble(value); } catch (NumberFormatException e) { return 0; } }
    private double rational(String text, double fallback) {
        try {
            String[] parts = text.split("[:/]", 2);
            double result = Double.parseDouble(parts[0]) / (parts.length == 2 ? Double.parseDouble(parts[1]) : 1);
            return Double.isFinite(result) ? result : fallback;
        } catch (RuntimeException e) { return fallback; }
    }

    private void normalizeAv(Path source, Container container, Path target, boolean hasAudio, boolean video, Path outputDir, Runnable check) throws IOException {
        List<String> command = inputCommand(properties.getFfmpegPath(), source, container);
        if (video) {
            command.addAll(List.of("-map", "0:v:0", "-vf", "fps=30:start_time=0,scale=trunc(iw*sar/2)*2:trunc(ih/2)*2:in_range=auto:out_range=tv,setsar=1,format=yuv420p",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-threads", "2", "-pix_fmt", "yuv420p", "-color_range", "tv"));
            if (hasAudio) command.addAll(List.of("-map", "0:a:0", "-af", "aresample=48000:async=1:first_pts=0", "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2"));
            else command.add("-an");
            command.addAll(List.of("-movflags", "+faststart", "-metadata:s:v:0", "rotate=0"));
        } else command.addAll(List.of("-map", "0:a:0", "-vn", "-af", "aresample=48000:async=1:first_pts=0", "-c:a", "pcm_s16le", "-ar", "48000", "-ac", "2"));
        command.addAll(List.of("-map_metadata", "-1", "-sn", "-dn", "-fs", Long.toString(MAX_OUTPUT_BYTES - 1024 * 1024), "-y", target.toString()));
        run(command, outputDir, check);
        // -fs 会让 FFmpeg 以成功退出而提前截断；接近截断线必须失败，不能靠时长容差放过残片。
        if (Files.size(target) >= MAX_OUTPUT_BYTES - 2L * 1024 * 1024)
            throw failure("LIMIT_EXCEEDED", "素材规范化达到 2 GiB 工作上限，请先缩短片段或降低源分辨率");
    }

    private List<String> inputCommand(String binary, Path source, Container container) {
        List<String> command = new ArrayList<>(List.of(binary, "-hide_banner", "-v", "error", "-nostdin", "-xerror", "-max_alloc", "67108864", "-filter_threads", "2", "-threads", "2", "-err_detect", "explode", "-protocol_whitelist", "file", "-f", container.demuxer));
        if (container == Container.MOV) command.addAll(List.of("-enable_drefs", "0", "-use_absolute_path", "0"));
        command.addAll(List.of("-copyts", "-start_at_zero", "-i", source.toString()));
        return command;
    }

    private void decodeEntire(Path source, Container container, Path outputDir, Runnable check) throws IOException {
        List<String> command = inputCommand(properties.getFfmpegPath(), source, container);
        command.addAll(List.of("-map", "0:v?", "-map", "0:a?", "-f", "null", "-"));
        run(command, outputDir, check);
    }

    private void validateImageHeader(Path source) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(source.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw failure("INVALID_MEDIA", "图片格式无法识别");
            ImageReader reader = readers.next();
            try { reader.setInput(input, true, true); checkDimensions(reader.getWidth(0), reader.getHeight(0)); }
            finally { reader.dispose(); }
        }
    }

    private void normalizeImage(Path source, int orientation, Path target, Path thumbnail, Runnable check) throws IOException {
        BufferedImage original;
        try (ImageInputStream input = ImageIO.createImageInputStream(source.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw failure("INVALID_MEDIA", "图片无法完整解码");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                checkDimensions(reader.getWidth(0), reader.getHeight(0));
                check.run();
                original = reader.read(0);
            } finally { reader.dispose(); }
        }
        if (original == null) throw failure("INVALID_MEDIA", "图片无法完整解码");
        boolean swap = orientation >= 5;
        BufferedImage upright = new BufferedImage(swap ? original.getHeight() : original.getWidth(), swap ? original.getWidth() : original.getHeight(), BufferedImage.TYPE_INT_ARGB);
        // 显式应用 EXIF 的全部八种方向，避免某些部署版本只旋转视频、漏掉手机照片方向。
        for (int y = 0; y < original.getHeight(); y++) {
            if (y % 32 == 0) check.run();
            for (int x = 0; x < original.getWidth(); x++) {
                int dx, dy;
                switch (orientation) {
                    case 2 -> { dx = original.getWidth() - 1 - x; dy = y; }
                    case 3 -> { dx = original.getWidth() - 1 - x; dy = original.getHeight() - 1 - y; }
                    case 4 -> { dx = x; dy = original.getHeight() - 1 - y; }
                    case 5 -> { dx = y; dy = x; }
                    case 6 -> { dx = original.getHeight() - 1 - y; dy = x; }
                    case 7 -> { dx = original.getHeight() - 1 - y; dy = original.getWidth() - 1 - x; }
                    case 8 -> { dx = y; dy = original.getWidth() - 1 - x; }
                    default -> { dx = x; dy = y; }
                }
                upright.setRGB(dx, dy, original.getRGB(x, y));
            }
        }
        if (!ImageIO.write(upright, "png", target.toFile())) throw failure("ENVIRONMENT_UNAVAILABLE", "未安装 PNG 编码器");
        double scale = Math.min(1.0, 320.0 / Math.max(upright.getWidth(), upright.getHeight()));
        BufferedImage small = new BufferedImage(Math.max(1, (int)Math.round(upright.getWidth() * scale)), Math.max(1, (int)Math.round(upright.getHeight() * scale)), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = small.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, small.getWidth(), small.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(upright, 0, 0, small.getWidth(), small.getHeight(), null);
        } finally { graphics.dispose(); }
        if (!ImageIO.write(small, "jpeg", thumbnail.toFile())) throw failure("ENVIRONMENT_UNAVAILABLE", "未安装 JPEG 编码器");
        check.run();
    }

    private int imageOrientation(Path source, Container container, Runnable check) throws IOException {
        try (DataInputStream input = new DataInputStream(Files.newInputStream(source))) {
            if (container == Container.JPEG) {
                input.readUnsignedShort();
                while (true) {
                    check.run();
                    int prefix = input.readUnsignedByte();
                    if (prefix != 255) throw failure("INVALID_MEDIA", "JPEG 段结构无效");
                    int marker; do { marker = input.readUnsignedByte(); } while (marker == 255);
                    if (marker == 218 || marker == 217) return 1;
                    if (marker == 1 || marker >= 208 && marker <= 215) continue;
                    int length = input.readUnsignedShort() - 2;
                    if (length < 0) throw failure("INVALID_MEDIA", "JPEG 段长度无效");
                    byte[] segment = input.readNBytes(length);
                    if (segment.length != length) throw failure("INVALID_MEDIA", "JPEG 数据不完整");
                    if (marker == 225 && length >= 6 && ascii(segment, 0, 6).equals("Exif\0\0"))
                        return tiffOrientation(Arrays.copyOfRange(segment, 6, length));
                }
            }
            input.skipNBytes(8);
            int orientation = 1;
            while (true) {
                check.run();
                long length = Integer.toUnsignedLong(input.readInt());
                byte[] type = input.readNBytes(4);
                if (type.length != 4 || length > MAX_SOURCE_BYTES) throw failure("INVALID_MEDIA", "PNG 块结构无效");
                String name = ascii(type, 0, 4);
                if ("acTL".equals(name)) throw failure("UNSUPPORTED_MEDIA", "暂不支持 APNG 动图，请选择静态图片或视频片段");
                if ("eXIf".equals(name)) {
                    if (length > 1024 * 1024) throw failure("LIMIT_EXCEEDED", "图片方向元数据过大");
                    byte[] exif = input.readNBytes((int)length);
                    if (exif.length != length) throw failure("INVALID_MEDIA", "PNG 方向元数据不完整");
                    orientation = tiffOrientation(exif);
                } else input.skipNBytes(length);
                input.skipNBytes(4);
                if ("IEND".equals(name)) return orientation;
            }
        }
    }

    private int tiffOrientation(byte[] bytes) {
        if (bytes.length < 8) throw failure("INVALID_MEDIA", "图片 EXIF 方向元数据损坏");
        ByteOrder order;
        if (bytes[0] == 'I' && bytes[1] == 'I') order = ByteOrder.LITTLE_ENDIAN;
        else if (bytes[0] == 'M' && bytes[1] == 'M') order = ByteOrder.BIG_ENDIAN;
        else throw failure("INVALID_MEDIA", "图片 EXIF 字节序无效");
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(order);
        if (Short.toUnsignedInt(buffer.getShort(2)) != 42) throw failure("INVALID_MEDIA", "图片 EXIF 标识无效");
        long offset = Integer.toUnsignedLong(buffer.getInt(4));
        if (offset > bytes.length - 2) throw failure("INVALID_MEDIA", "图片 EXIF 索引越界");
        int count = Short.toUnsignedInt(buffer.getShort((int)offset));
        if (offset + 2L + count * 12L > bytes.length) throw failure("INVALID_MEDIA", "图片 EXIF 条目不完整");
        for (int i = 0; i < count; i++) {
            int position = (int)offset + 2 + i * 12;
            if (Short.toUnsignedInt(buffer.getShort(position)) == 274) {
                if (Short.toUnsignedInt(buffer.getShort(position + 2)) != 3 || buffer.getInt(position + 4) != 1)
                    throw failure("INVALID_MEDIA", "图片 EXIF 方向类型无效");
                int orientation = Short.toUnsignedInt(buffer.getShort(position + 8));
                if (orientation < 1 || orientation > 8) throw failure("INVALID_MEDIA", "图片 EXIF 方向值无效");
                return orientation;
            }
        }
        return 1;
    }

    private static void checkDimensions(int width, int height) {
        if (width <= 0 || height <= 0) throw failure("INVALID_MEDIA", "素材画面尺寸无效");
        if (width > 4096 || height > 4096 || (long)width * height > MAX_PIXELS)
            throw failure("LIMIT_EXCEEDED", "素材画面超过 4K 像素上限");
    }

    private String run(List<String> command, Path outputDir, Runnable check) throws IOException {
        if (command.getFirst() == null || command.getFirst().isBlank()) throw failure("ENVIRONMENT_UNAVAILABLE", "未配置 FFmpeg/FFprobe");
        ProcessBuilder builder = new ProcessBuilder(command).directory(outputDir.toFile()).redirectErrorStream(true);
        builder.environment().keySet().removeIf(key -> !SYSTEM_ENV.contains(key.toUpperCase(Locale.ROOT)));
        builder.environment().put("LC_ALL", "C");
        Process process;
        check.run();
        try { process = builder.start(); }
        catch (IOException e) { throw failure("ENVIRONMENT_UNAVAILABLE", "无法启动 FFmpeg/FFprobe，请检查部署依赖"); }
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        AtomicBoolean overflow = new AtomicBoolean(), readFailure = new AtomicBoolean();
        Set<ProcessHandle> children = new HashSet<>();
        Thread reader = Thread.ofVirtual().name("creation-asset-output").start(() -> {
            try (var stream = process.getInputStream()) {
                byte[] buffer = new byte[4096]; int size;
                while ((size = stream.read(buffer)) >= 0) {
                    int remaining = MAX_OUTPUT_TEXT - capture.size();
                    if (size > remaining) overflow.set(true);
                    if (remaining > 0) capture.write(buffer, 0, Math.min(size, remaining));
                }
            } catch (IOException e) { readFailure.set(true); }
        });
        try {
            process.getOutputStream().close();
            while (true) {
                process.descendants().forEach(children::add);
                check.run();
                if (overflow.get()) throw failure("LIMIT_EXCEEDED", "本地媒体进程输出超过限制");
                checkOutputSize(outputDir);
                if (process.waitFor(100, TimeUnit.MILLISECONDS)) break;
            }
            while (reader.isAlive()) { check.run(); reader.join(100); }
            check.run();
            if (overflow.get()) throw failure("LIMIT_EXCEEDED", "本地媒体进程输出超过限制");
            if (readFailure.get()) throw failure("INVALID_MEDIA", "无法读取本地媒体处理结果");
            if (process.exitValue() != 0) throw failure("INVALID_MEDIA", "媒体无法完整解码或规范化，请检查文件完整性及部署解码支持");
            if (children.stream().anyMatch(ProcessHandle::isAlive)) throw failure("INVALID_MEDIA", "媒体进程未完整退出");
            checkOutputSize(outputDir);
            return capture.toString(StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("CANCELLED", "素材准备已中断");
        } finally {
            stop(process, children);
            if (reader.isAlive()) reader.interrupt();
        }
    }

    private void checkOutputSize(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            long bytes = 0;
            for (Path path : paths.toList()) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw failure("INVALID_MEDIA", "素材准备目录出现异常文件");
                bytes += Files.size(path);
                if (bytes >= MAX_OUTPUT_BYTES) throw failure("LIMIT_EXCEEDED", "素材派生文件超过 2 GiB 工作上限");
            }
        }
    }

    private void stop(Process process, Set<ProcessHandle> children) {
        boolean interrupted = Thread.interrupted();
        try {
            process.descendants().forEach(children::add);
            children.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
            // 让包装进程先回收自己的子进程，避免同时强杀父子后遗留无人回收的僵尸。
            if (process.isAlive() && !children.isEmpty()) {
                try { process.waitFor(200, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
            if (process.isAlive()) process.destroyForcibly();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) {
                try { process.waitFor(100, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
            for (ProcessHandle child : children) if (child.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) try { child.onExit().get(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) { interrupted = true; }
                catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) { }
            }
        } finally {
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static AssetPreparationException failure(String code, String message) { return new AssetPreparationException(code, message); }
}
