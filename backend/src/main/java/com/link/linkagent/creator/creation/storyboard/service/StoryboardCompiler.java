package com.link.linkagent.creator.creation.storyboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.service.CreationInputRules;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** 把业务故事板变为固定 V2 工程，实际录音、素材区间和字幕必须由已有事实支撑。 */
@Component
public class StoryboardCompiler {
    static final String PLACEHOLDER_ID = "system-static-placeholder";
    // 只满足渲染器的非空资产契约，不会作为任何场景画面或配音使用。
    static final byte[] PLACEHOLDER = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    private static final int FPS = 30;
    private static final long SINGLE_LIMIT = 32L * 1024 * 1024, MUSIC_LIMIT = 64L * 1024 * 1024, TOTAL_LIMIT = 256L * 1024 * 1024;
    private final ObjectMapper json;

    public StoryboardCompiler(ObjectMapper json) { this.json = json; }

    public Check check(JsonNode brief, Candidate candidate, List<Asset> assets) {
        return check("board-check", brief, candidate, assets);
    }

    public Check check(String storyboardId, JsonNode brief, Candidate candidate, List<Asset> assets) {
        requireId(storyboardId);
        List<Problem> issues = inspect(brief, candidate, assets);
        long duration = candidate == null || candidate.scenes() == null ? 0 : candidate.scenes().stream()
                .filter(Objects::nonNull).mapToLong(s -> frames(s.durationMs())).sum() * 1000 / FPS;
        if (!issues.isEmpty()) return new Check(false, List.copyOf(issues), duration, null);
        ObjectNode document = build(storyboardId, brief, candidate, assets, "check-run", "check-attempt");
        String hash = CreationInputRules.renderContentHash(json, document.toString());
        return new Check(true, List.of(), Math.round(document.path("video").path("frameCount").asLong() * 1000.0 / FPS), hash);
    }

    public ObjectNode compile(String storyboardId, JsonNode brief, Candidate candidate, List<Asset> assets,
                              String runId, String attemptId) {
        requireId(storyboardId); requireId(runId); requireId(attemptId);
        List<Problem> issues = inspect(brief, candidate, assets);
        if (!issues.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "故事板尚不可渲染：" + issues.getFirst().message());
        return build(storyboardId, brief, candidate, assets, runId, attemptId);
    }

    private List<Problem> inspect(JsonNode brief, Candidate candidate, List<Asset> supplied) {
        var issues = new ArrayList<Problem>();
        if (brief == null || !brief.isObject() || !brief.path("targetDurationMs").canConvertToLong()
                || brief.path("targetDurationMs").asLong() < 1000 || brief.path("targetDurationMs").asLong() > 180000) {
            add(issues, null, "BRIEF_INVALID", "简报必须明确 1 至 180 秒的目标时长"); return issues;
        }
        // 简报选择 TTS 时不再整体拒绝；每个场景在下方场景循环里必须具备真实生成的配音音频。
        if (candidate == null || candidate.scenes() == null || candidate.scenes().isEmpty() || candidate.scenes().size() > 48) {
            add(issues, null, "SCENES_INVALID", "故事板需要 1 至 48 个场景"); return issues;
        }
        Map<String, Asset> assets = new HashMap<>();
        if (supplied != null) for (Asset asset : supplied) {
            if (asset == null || asset.assetId() == null || assets.putIfAbsent(asset.assetId(), asset) != null)
                add(issues, null, "ASSET_INVALID", "素材快照为空或身份重复");
        }
        Set<String> sceneIds = new HashSet<>(), usedAssets = new LinkedHashSet<>();
        long frameCount = 0, cues = 0;
        for (Scene scene : candidate.scenes()) {
            if (scene == null) { add(issues, null, "SCENE_INVALID", "场景不能为空"); continue; }
            String sid = scene.id();
            if (!identifier(sid) || !sceneIds.add(sid)) add(issues, sid, "SCENE_ID_INVALID", "场景身份无效或重复");
            int count = frames(scene.durationMs());
            if (scene.durationMs() < 1 || scene.durationMs() > 30000 || count < 1 || count > FPS * 30)
                add(issues, sid, "SCENE_DURATION_INVALID", "场景须为 1 帧至 30 秒");
            double duration = count * 1000.0 / FPS;
            frameCount += count;
            if (!text(scene.title(), 60) || scene.body() == null || scene.body().size() > 4
                    || scene.body().stream().anyMatch(s -> !text(s, 90)))
                add(issues, sid, "SCENE_TEXT_INVALID", "标题或正文为空、含控制字符或超出长度");
            if (!Set.of("TITLE_CARD", "IMAGE_CARD", "SPLIT_CARD", "COMPARE_CARD", "VIDEO_CLIP", "AI_VIDEO").contains(Objects.toString(scene.layout(), "")))
                add(issues, sid, "LAYOUT_INVALID", "场景布局不受支持");
            boolean aiVideo = "AI_VIDEO".equals(scene.layout());
            // AI 画面在工程里仍按片段渲染，业务层保留 AI_VIDEO 来源以便核对提示词与生成事实。
            boolean video = aiVideo || "VIDEO_CLIP".equals(scene.layout()), image = Set.of("IMAGE_CARD", "SPLIT_CARD").contains(Objects.toString(scene.layout(), ""));
            Asset visual = null;
            if (video || image) {
                visual = assets.get(scene.visualAssetId());
                if (visual == null) add(issues, sid, aiVideo ? "AI_VIDEO_ASSET_MISSING" : "VISUAL_MISSING",
                        aiVideo ? "AI 视频场景需要已生成并保存的真实片段，不能用图文或占位画面代替" : "场景引用的素材不在已冻结的 READY 素材中");
                else {
                    usedAssets.add(visual.assetId());
                    if (!(video ? "VIDEO_CLIP" : "IMAGE").equals(visual.kind())) add(issues, sid, "VISUAL_KIND_INVALID", "画面素材类型与布局不符");
                    validateVisual(visual, video, sid, issues);
                    if (aiVideo) validateGeneratedVideo(visual, scene, sid, issues);
                }
            } else if (scene.visualAssetId() != null || scene.sourceInMs() != null || scene.sourceOutMs() != null)
                add(issues, sid, "VISUAL_UNUSED", "此布局不接受画面素材或视频区间");
            if ("IMAGE_CARD".equals(scene.layout()) && scene.body() != null && !scene.body().isEmpty()
                    || video && scene.body() != null && !scene.body().isEmpty())
                add(issues, sid, "BODY_NOT_VISIBLE", "全图和视频场景不能叠加图文正文，请另建文字场景");
            if ("COMPARE_CARD".equals(scene.layout()) && (scene.body() == null || scene.body().size() != 2))
                add(issues, sid, "COMPARE_ITEMS_INVALID", "对比场景需要两个对比项");
            if (!Set.of("CONTAIN", "COVER").contains(Objects.toString(scene.fit(), ""))) add(issues, sid, "FIT_INVALID", "画面适配方式无效");
            if (!video && (scene.sourceInMs() != null || scene.sourceOutMs() != null)) add(issues, sid, "CLIP_RANGE_UNUSED", "图文场景不接受视频截取区间");
            if (video) {
                long begin = scene.sourceInMs() == null ? -1 : scene.sourceInMs(), finish = scene.sourceOutMs() == null ? -1 : scene.sourceOutMs();
                if (begin < 0 || finish <= begin || finish > 1800000 || Math.abs((finish - begin) - duration) > 1.000001)
                    add(issues, sid, "CLIP_DURATION_INVALID", "视频截取区间必须与量化后的场景时长相同，不会循环或变速补齐");
                if (visual != null && finish > streamDuration(visual, "video") * 1000 + .001)
                    add(issues, sid, "CLIP_OUT_OF_RANGE", "视频截取区间超过素材的实际画面时长");
            }
            String audio = Objects.toString(scene.audioMode(), "");
            if (!Set.of("NO_DIALOGUE", "ORIGINAL", "NARRATION", "TTS").contains(audio)) add(issues, sid, "AUDIO_MODE_INVALID", "声音方案无效");
            if (("NO_DIALOGUE".equals(brief.path("dialogueMode").asText()) && !"NO_DIALOGUE".equals(audio))
                    || ("TTS".equals(brief.path("dialogueMode").asText()) && !"TTS".equals(audio)))
                add(issues, sid, "DIALOGUE_CHOICE_CONFLICT", "场景声音方案与简报选择不一致，不能自动替换用户选择");
            double audioDuration = 0, audioStart = 0;
            if ("ORIGINAL".equals(audio)) {
                if (!video || visual == null) add(issues, sid, "ORIGINAL_AUDIO_INVALID", "原声仅用于真实视频片段");
                else {
                    JsonNode track = stream(visual, "audio"), picture = stream(visual, "video");
                    double origin = number(picture.path("start_time"), 0), begin = (scene.sourceInMs() == null ? 0 : scene.sourceInMs()) / 1000.0 + origin;
                    double end = (scene.sourceOutMs() == null ? 0 : scene.sourceOutMs()) / 1000.0 + origin;
                    double trackStart = number(track.path("start_time"), 0), trackEnd = trackStart + number(track.path("duration"), 0);
                    if (track.isMissingNode() || trackEnd <= begin || trackStart >= end)
                        add(issues, sid, "CLIP_AUDIO_MISSING", "视频截取区间没有可保留的原声音轨");
                    audioStart = Math.max(0, trackStart - begin) * 1000;
                    audioDuration = Math.min(end - begin, trackEnd - begin) * 1000;
                }
                if (scene.narrationAssetId() != null) add(issues, sid, "AUDIO_CONFLICT", "原声场景不能同时绑定旁白");
            } else if ("NARRATION".equals(audio)) {
                Asset voice = assets.get(scene.narrationAssetId());
                if (voice == null || !"NARRATION".equals(voice.kind()) || !"audio/wav".equals(voice.contentType()))
                    add(issues, sid, "NARRATION_MISSING", "旁白场景需要已准备完成的用户录音 WAV");
                else {
                    usedAssets.add(voice.assetId());
                    // 用户录音不能被系统生成的配音悄悄替换；生成音频只允许出现在 TTS 场景。
                    if ("TTS".equals(generatedKind(voice)))
                        add(issues, sid, "NARRATION_ASSET_GENERATED", "用户录音场景不能引用系统生成的配音音频");
                    JsonNode track = stream(voice, "audio");
                    audioDuration = Math.max(streamDuration(voice, "audio"), voice.metadata() == null ? 0 : number(voice.metadata().path("normalized").path("format").path("duration"), 0)) * 1000;
                    if (!"pcm_s16le".equals(track.path("codec_name").asText()) || track.path("channels").asInt() < 1
                            || track.path("channels").asInt() > 2 || track.path("sample_rate").asInt() < 8000 || track.path("sample_rate").asInt() > 48000
                            || !Double.isFinite(audioDuration) || audioDuration <= 0 || audioDuration > 30000.001)
                        add(issues, sid, "NARRATION_FORMAT_INVALID", "旁白需要不超过 30 秒的真实 PCM16 WAV 及可靠时长");
                    if (audioDuration > duration + .001) add(issues, sid, "AUDIO_OVERFLOW", "实际录音超出场景，不会截断或加速，请调整场景时长或重新录音");
                }
                if (scene.cues() == null || scene.cues().isEmpty()) add(issues, sid, "NARRATION_CUES_MISSING", "旁白需要用户确认的相对录音字幕时间，不能由脚本文字伪造对齐");
            } else if ("TTS".equals(audio)) {
                Asset voice = assets.get(scene.narrationAssetId());
                if (scene.narration() == null || scene.narration().isBlank())
                    add(issues, sid, "TTS_TEXT_MISSING", "配音场景需要用户确认的场景文稿");
                if (voice == null || !"NARRATION".equals(voice.kind()) || !"audio/wav".equals(voice.contentType()))
                    add(issues, sid, "TTS_AUDIO_MISSING", "配音场景需要已生成并保存的真实配音音频，不能用静音或测试音代替");
                else {
                    usedAssets.add(voice.assetId());
                    JsonNode generated = voice.metadata() == null ? json.missingNode() : voice.metadata().path("generated");
                    // 文稿摘要绑定音频与文字，改词后必须重新生成，不能沿用旧配音。
                    if (!"TTS".equals(generated.path("kind").asText()) || !StoryboardCodec.digest(Objects.toString(scene.narration(), ""))
                            .equals(generated.path("textSha256").asText()))
                        add(issues, sid, "TTS_TEXT_MISMATCH", "配音音频不对应当前场景文稿，修改文稿后必须重新生成");
                    JsonNode track = stream(voice, "audio");
                    audioDuration = Math.max(streamDuration(voice, "audio"), voice.metadata() == null ? 0 : number(voice.metadata().path("normalized").path("format").path("duration"), 0)) * 1000;
                    if (!"pcm_s16le".equals(track.path("codec_name").asText()) || track.path("channels").asInt() < 1
                            || track.path("channels").asInt() > 2 || track.path("sample_rate").asInt() < 8000 || track.path("sample_rate").asInt() > 48000
                            || !Double.isFinite(audioDuration) || audioDuration <= 0 || audioDuration > 30000.001)
                        add(issues, sid, "TTS_AUDIO_INVALID", "配音音频需要不超过 30 秒的真实 PCM16 WAV 及可靠时长");
                    if (audioDuration > duration + .001) add(issues, sid, "AUDIO_OVERFLOW", "实际配音超出场景，不会截断或加速，请调整场景时长或缩短文稿后重新生成");
                }
                // 字幕时间来自对实际音频的转写，缺失时不能按字数估算。
                if (scene.cues() == null || scene.cues().isEmpty()) add(issues, sid, "TTS_CUES_MISSING", "配音场景需要来自实际音频的字幕时间");
            } else if (scene.narrationAssetId() != null) add(issues, sid, "NARRATION_UNUSED", "当前声音方案不接受旁白录音");
            if (scene.cues() == null) add(issues, sid, "CUES_INVALID", "字幕列表不能为空值");
            else {
                long previous = 0;
                // 全局时间必须用未取整的场景起点；用 round 后的起点会与未取整的片尾比较，产生最多 0.5ms 漂移。
                double actualSceneStart = (frameCount - count) * 1000.0 / FPS, sceneEnd = frameCount * 1000.0 / FPS;
                for (Cue cue : scene.cues()) {
                    cues++;
                    if (cue == null || cue.startMs() < previous || cue.endMs() <= cue.startMs() || !text(cue.text(), 120)
                            || cue.startMs() < Math.floor(audioStart) || cue.endMs() > Math.ceil(Math.min(audioDuration, duration)) + .001
                            || actualSceneStart + cue.startMs() < Math.floor(actualSceneStart + audioStart)
                            || actualSceneStart + cue.endMs() > Math.ceil(actualSceneStart + audioDuration) + .001
                            || actualSceneStart + cue.endMs() > sceneEnd + .001
                            || !("ORIGINAL".equals(audio) || "NARRATION".equals(audio) || "TTS".equals(audio)))
                        add(issues, sid, "CUE_OUT_OF_RANGE", "字幕须按顺序绑定当前真实音轨范围，不能重叠或超出场景");
                    if (cue != null) previous = cue.endMs();
                }
            }
        }
        long target = brief.path("targetDurationMs").asLong();
        if (frameCount < FPS || frameCount > FPS * 180 || Math.abs(frameCount * 1000.0 / FPS - target) > 1000.0 / FPS + .000001)
            add(issues, null, "TARGET_DURATION_MISMATCH", "场景总时长必须与用户目标时长一致，最多允许一帧量化误差");
        if (cues > 200) add(issues, null, "CUES_LIMIT", "整片最多支持 200 条真实字幕");
        String musicId = inspectMusic(brief.path("backgroundMusic"), assets, usedAssets, frameCount, issues);
        String ambienceId = inspectAmbience(brief.path("ambience"), assets, usedAssets, frameCount, issues, musicId);
        long total = 0;
        for (String id : usedAssets) {
            Asset asset = assets.get(id);
            if (PLACEHOLDER_ID.equals(id)) add(issues, null, "ASSET_ID_RESERVED", "该素材身份为内部无画面占位保留标识");
            total += asset.bytes();
            long limit = (id.equals(musicId) || id.equals(ambienceId)) && "MUSIC".equals(asset.kind()) ? MUSIC_LIMIT : SINGLE_LIMIT;
            if (!identifier(id) || asset.bytes() <= 0 || asset.bytes() > limit || asset.sha256() == null || !asset.sha256().matches("[0-9a-f]{64}")
                    || asset.bucketName() == null || asset.bucketName().isBlank() || asset.objectKey() == null || asset.objectKey().isBlank())
                add(issues, null, "ASSET_RENDER_LIMIT", "引用素材须有可靠摘要；背景音乐不超过 64 MiB，其他素材不超过 32 MiB，请先裁剪或压缩后重新导入");
        }
        if (total > TOTAL_LIMIT) add(issues, null, "ASSET_TOTAL_LIMIT", "本工程引用素材合计超过 256 MiB");
        return issues;
    }

    private String inspectMusic(JsonNode music, Map<String, Asset> assets, Set<String> used, long frames, List<Problem> issues) {
        if (music.isMissingNode() || music.isNull()) return null;
        String id = music.path("assetId").asText();
        Asset asset = assets.get(id);
        if (!music.isObject() || !identifier(id) || asset == null || !"MUSIC".equals(asset.kind()) || !"audio/wav".equals(asset.contentType())) {
            add(issues, null, "BGM_MISSING", "背景音乐须引用已冻结的 MUSIC 类型 WAV 素材"); return id;
        }
        used.add(id);
        JsonNode track = stream(asset, "audio");
        double trackDuration = streamDuration(asset, "audio"), formatDuration = asset.metadata() == null ? 0
                : number(asset.metadata().path("normalized").path("format").path("duration"), 0);
        // WAV 的 ffprobe 时长可能只在 format 中；两个来源同时存在时取较短值，避免越过真实尾部。
        double actualDuration = trackDuration > 0 && formatDuration > 0 ? Math.min(trackDuration, formatDuration)
                : Math.max(trackDuration, formatDuration);
        if (!"pcm_s16le".equals(track.path("codec_name").asText()) || track.path("channels").asInt() != 2
                || track.path("sample_rate").asInt() != 48000 || actualDuration <= 0 || actualDuration > 1200.001)
            add(issues, null, "BGM_FORMAT_INVALID", "背景音乐需要真实 48 kHz 立体声 PCM16 WAV 及可靠时长");
        JsonNode start = music.path("sourceInMs"), end = music.path("sourceOutMs");
        boolean range = start.isIntegralNumber() && start.canConvertToLong() && end.isIntegralNumber() && end.canConvertToLong()
                && start.asLong() >= 0 && end.asLong() > start.asLong() && end.asLong() <= 1200000;
        if (!range || Math.abs((end.asLong() - start.asLong()) - frames * 1000.0 / FPS) > 1000.0 / FPS + .000001)
            add(issues, null, "BGM_DURATION_MISMATCH", "背景音乐区间须与全片帧长一致，最多一帧误差，不会自动循环、变速或补齐");
        if (range && end.asLong() > actualDuration * 1000 + .001)
            add(issues, null, "BGM_OUT_OF_RANGE", "背景音乐区间超过素材实际时长");
        JsonNode gain = music.path("gainDb"), fadeIn = music.path("fadeInMs"), fadeOut = music.path("fadeOutMs");
        if (!gain.isNumber() || !Double.isFinite(gain.asDouble()) || gain.asDouble() < -36 || gain.asDouble() > 0
                || !fadeIn.isIntegralNumber() || !fadeIn.canConvertToInt() || fadeIn.asInt() < 0 || fadeIn.asInt() > 10000
                || !fadeOut.isIntegralNumber() || !fadeOut.canConvertToInt() || fadeOut.asInt() < 0 || fadeOut.asInt() > 10000
                || (long)fadeIn.asInt() + fadeOut.asInt() > (range ? end.asLong() - start.asLong() : 0)
                || !music.path("ducking").isBoolean())
            add(issues, null, "BGM_MIX_INVALID", "背景音乐音量须为 -36 至 0 dB，单项淡入淡出不超过 10 秒且总长不得超过区间，压低设置须明确");
        return id;
    }

    /** 环境声只做独立音量铺底，不带压低开关；与背景音乐共用素材类型但必须是不同素材。 */
    private String inspectAmbience(JsonNode ambience, Map<String, Asset> assets, Set<String> used, long frames,
            List<Problem> issues, String musicId) {
        if (ambience.isMissingNode() || ambience.isNull()) return null;
        String id = ambience.path("assetId").asText();
        Asset asset = assets.get(id);
        if (!ambience.isObject() || !identifier(id) || asset == null || !"MUSIC".equals(asset.kind()) || !"audio/wav".equals(asset.contentType())) {
            add(issues, null, "AMB_MISSING", "环境声须引用已冻结的长音频 WAV 素材，不能与背景音乐混用同一素材"); return id;
        }
        if (id.equals(musicId)) add(issues, null, "AMB_DUPLICATE_SOURCE", "环境声与背景音乐不能引用同一素材");
        used.add(id);
        JsonNode track = stream(asset, "audio");
        double trackDuration = streamDuration(asset, "audio"), formatDuration = asset.metadata() == null ? 0
                : number(asset.metadata().path("normalized").path("format").path("duration"), 0);
        double actualDuration = trackDuration > 0 && formatDuration > 0 ? Math.min(trackDuration, formatDuration)
                : Math.max(trackDuration, formatDuration);
        if (!"pcm_s16le".equals(track.path("codec_name").asText()) || track.path("channels").asInt() != 2
                || track.path("sample_rate").asInt() != 48000 || actualDuration <= 0 || actualDuration > 1200.001)
            add(issues, null, "AMB_FORMAT_INVALID", "环境声需要真实 48 kHz 立体声 PCM16 WAV 及可靠时长");
        JsonNode start = ambience.path("sourceInMs"), end = ambience.path("sourceOutMs");
        boolean range = start.isIntegralNumber() && start.canConvertToLong() && end.isIntegralNumber() && end.canConvertToLong()
                && start.asLong() >= 0 && end.asLong() > start.asLong() && end.asLong() <= 1200000;
        if (!range || Math.abs((end.asLong() - start.asLong()) - frames * 1000.0 / FPS) > 1000.0 / FPS + .000001)
            add(issues, null, "AMB_DURATION_MISMATCH", "环境声区间须与全片帧长一致，最多一帧误差，不会自动循环、变速或补齐");
        if (range && end.asLong() > actualDuration * 1000 + .001)
            add(issues, null, "AMB_OUT_OF_RANGE", "环境声区间超过素材实际时长");
        JsonNode gain = ambience.path("gainDb"), fadeIn = ambience.path("fadeInMs"), fadeOut = ambience.path("fadeOutMs");
        if (!gain.isNumber() || !Double.isFinite(gain.asDouble()) || gain.asDouble() < -36 || gain.asDouble() > 0
                || !fadeIn.isIntegralNumber() || !fadeIn.canConvertToInt() || fadeIn.asInt() < 0 || fadeIn.asInt() > 10000
                || !fadeOut.isIntegralNumber() || !fadeOut.canConvertToInt() || fadeOut.asInt() < 0 || fadeOut.asInt() > 10000
                || (long)fadeIn.asInt() + fadeOut.asInt() > (range ? end.asLong() - start.asLong() : 0))
            add(issues, null, "AMB_MIX_INVALID", "环境声音量须为 -36 至 0 dB，单项淡入淡出不超过 10 秒且总长不得超过区间");
        return id;
    }

    /** AI 画面必须由本系统生成并核验，提示词摘要绑定当前场景文字，改词后旧片段不能继续使用。 */
    private void validateGeneratedVideo(Asset asset, Scene scene, String sid, List<Problem> issues) {
        JsonNode generated = asset.metadata() == null ? json.missingNode() : asset.metadata().path("generated");
        if (!"AI_VIDEO".equals(generated.path("kind").asText()))
            add(issues, sid, "AI_VIDEO_ASSET_INVALID", "AI 视频场景需要本系统生成并保存的真实片段，不能引用用户上传或占位视频");
        else if (!StoryboardCodec.digest(scene.effectiveVisualPrompt()).equals(generated.path("promptSha256").asText()))
            add(issues, sid, "AI_VIDEO_PROMPT_MISMATCH", "生成片段不对应当前场景提示词，修改标题、正文或提示词后必须重新生成");
    }

    private void validateVisual(Asset asset, boolean video, String sid, List<Problem> issues) {        JsonNode picture = stream(asset, "video");
        long width = picture.path("width").asLong(), height = picture.path("height").asLong();
        if (width <= 0 || height <= 0 || width > 8000000 || height > 8000000 || width * height > 8000000 || !(video ? "video/mp4" : "image/png").equals(asset.contentType())
                || !(video ? "h264" : "png").equals(picture.path("codec_name").asText()))
            add(issues, sid, "VISUAL_METADATA_INVALID", "画面须引用已规范化且解码尺寸不超过 800 万像素的素材");
        if (video && (streamDuration(asset, "video") <= 0 || streamDuration(asset, "video") > 1800))
            add(issues, sid, "VIDEO_METADATA_INVALID", "视频缺少可靠实际时长");
    }

    private ObjectNode build(String storyboardId, JsonNode brief, Candidate candidate, List<Asset> supplied, String runId, String attemptId) {
        ObjectNode document = json.createObjectNode();
        document.put("schemaVersion", 2).put("runId", runId).put("attemptId", attemptId).put("storyboardId", storyboardId).put("seed", 7);
        ObjectNode video = document.putObject("video");
        video.put("width", 1280).put("height", 720).put("fps", FPS).put("frameCount", candidate.scenes().stream().mapToInt(s -> frames(s.durationMs())).sum())
                .put("codec", "h264").put("audioSampleRate", 48000).put("fontId", "cjk_sans");
        Map<String, Asset> available = new HashMap<>(); if (supplied != null) supplied.forEach(a -> available.put(a.assetId(), a));
        ArrayNode assets = document.putArray("assets"), scenes = document.putArray("scenes"), subtitles = document.putArray("subtitles");
        Set<String> used = new LinkedHashSet<>();
        int cursor = 0, cueId = 0;
        for (Scene scene : candidate.scenes()) {
            int end = cursor + frames(scene.durationMs());
            ObjectNode output = scenes.addObject();
            // 渲染器只认 VIDEO_CLIP；AI 来源在业务故事板与素材事实中保留。
            boolean renderClip = "VIDEO_CLIP".equals(scene.layout()) || "AI_VIDEO".equals(scene.layout());
            output.put("id", scene.id()).put("startFrame", cursor).put("endFrame", end)
                    .put("layout", renderClip ? "VIDEO_CLIP" : scene.layout())
                    .put("background", "#102030").put("title", scene.title());
            output.set("body", json.valueToTree(scene.body())); output.putNull("voice");
            if (Set.of("IMAGE_CARD", "SPLIT_CARD").contains(scene.layout())) {
                output.put("imageAssetId", scene.visualAssetId()); used.add(scene.visualAssetId());
            }
            if (renderClip) {
                used.add(scene.visualAssetId());
                output.putObject("clip").put("assetId", scene.visualAssetId()).put("sourceStartMs", scene.sourceInMs())
                        .put("sourceEndMs", scene.sourceOutMs()).put("fit", scene.fit())
                        .put("audioMode", "ORIGINAL".equals(scene.audioMode()) ? "ORIGINAL" : "MUTE").put("gainDb", 0);
            }
            if ("NARRATION".equals(scene.audioMode()) || "TTS".equals(scene.audioMode())) {
                used.add(scene.narrationAssetId());
                output.putObject("voice").put("assetId", scene.narrationAssetId()).put("offsetMs", 0).put("gainDb", 0);
            }
            long startMs = Math.round(cursor * 1000.0 / FPS);
            for (Cue cue : scene.cues()) subtitles.addObject().put("id", "cue-" + ++cueId).put("sceneId", scene.id())
                    .put("startMs", startMs + cue.startMs()).put("endMs", startMs + cue.endMs()).put("text", cue.text())
                    .put("audioAssetId", "NARRATION".equals(scene.audioMode()) || "TTS".equals(scene.audioMode()) ? scene.narrationAssetId() : scene.visualAssetId());
            cursor = end;
        }
        JsonNode music = brief.path("backgroundMusic");
        if (music.isObject()) {
            String musicId = music.path("assetId").asText(); used.add(musicId);
            document.putObject("backgroundMusic").put("assetId", musicId)
                    .put("sourceStartMs", music.path("sourceInMs").asLong()).put("sourceEndMs", music.path("sourceOutMs").asLong())
                    .set("gainDb", music.path("gainDb"));
            ObjectNode outputMusic = (ObjectNode)document.path("backgroundMusic");
            outputMusic.put("fadeInMs", music.path("fadeInMs").asInt()).put("fadeOutMs", music.path("fadeOutMs").asInt())
                    .put("ducking", music.path("ducking").asBoolean());
        }
        JsonNode ambience = brief.path("ambience");
        if (ambience.isObject()) {
            String ambienceId = ambience.path("assetId").asText(); used.add(ambienceId);
            document.putObject("ambience").put("assetId", ambienceId)
                    .put("sourceStartMs", ambience.path("sourceInMs").asLong())
                    .put("sourceEndMs", ambience.path("sourceOutMs").asLong())
                    .put("gainDb", ambience.path("gainDb").asDouble())
                    .put("fadeInMs", ambience.path("fadeInMs").asInt())
                    .put("fadeOutMs", ambience.path("fadeOutMs").asInt());
        }
        for (String id : used) {
            Asset asset = available.get(id);
            assets.addObject().put("id", id).put("path", assetPath(id, asset.contentType())).put("mime", asset.contentType()).put("sha256", asset.sha256());
        }
        if (assets.isEmpty()) assets.addObject().put("id", PLACEHOLDER_ID).put("path", assetPath(PLACEHOLDER_ID, "image/png"))
                .put("mime", "image/png").put("sha256", sha256(PLACEHOLDER));
        document.put("inputHash", CreationInputRules.renderInputHash(json, document));
        return document;
    }

    static String assetPath(String id, String mime) {
        requireId(id);
        String extension = switch (Objects.toString(mime, "")) { case "image/png" -> "png"; case "audio/wav" -> "wav"; case "video/mp4" -> "mp4"; default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "规范化素材类型不受渲染器支持"); };
        return "assets/" + id + "." + extension;
    }
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private JsonNode stream(Asset asset, String kind) {
        if (asset != null && asset.metadata() != null) for (JsonNode stream : asset.metadata().path("normalized").path("streams"))
            if (kind.equals(stream.path("codec_type").asText()) && stream.path("disposition").path("attached_pic").asInt() != 1) return stream;
        return json.missingNode();
    }
    private double streamDuration(Asset asset, String kind) { return number(stream(asset, kind).path("duration"), 0); }
    /** 只认系统登记的生成来源标记；用户上传素材没有该字段。 */
    private static String generatedKind(Asset asset) {
        return asset == null || asset.metadata() == null ? "" : asset.metadata().path("generated").path("kind").asText("");
    }
    private static double number(JsonNode node, double fallback) {
        try { double value = Double.parseDouble(node.asText()); return Double.isFinite(value) ? value : fallback; }
        catch (RuntimeException exception) { return fallback; }
    }
    private static int frames(long millis) { return (int)Math.round(millis * (double)FPS / 1000); }
    private static boolean text(String value, int maximum) { return value != null && !value.isBlank() && value.length() <= maximum && value.codePoints().allMatch(c -> c >= 32 || c == '\n'); }
    private static boolean identifier(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,64}"); }
    private static void requireId(String value) { if (!identifier(value)) throw new ResponseStatusException(HttpStatus.CONFLICT, "工程身份无效"); }
    private static void add(List<Problem> issues, String scene, String code, String message) { issues.add(new Problem(scene, code, message)); }
}
