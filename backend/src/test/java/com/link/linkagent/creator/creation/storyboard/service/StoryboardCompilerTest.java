package com.link.linkagent.creator.creation.storyboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.service.CreationInputRules;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class StoryboardCompilerTest {
    final ObjectMapper json = new ObjectMapper();
    final StoryboardCompiler compiler = new StoryboardCompiler(json);

    @Test void explicitSilentBoardHasNoFabricatedVoiceAndStableContentHashAcrossAttempts() {
        var candidate = new Candidate(List.of(scene("s1", "TITLE_CARD", 2000, null, null, null, "NO_DIALOGUE", null, List.of())));
        var first = compiler.compile("board", brief(2000), candidate, List.of(), "run1", "attempt1");
        var second = compiler.compile("board", brief(2000), candidate, List.of(), "run2", "attempt2");
        assertThat(first.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(first.path("video").path("frameCount").asInt()).isEqualTo(60);
        assertThat(first.path("scenes").get(0).path("voice").isNull()).isTrue();
        assertThat(first.path("subtitles")).isEmpty();
        assertThat(first.path("assets").get(0).path("id").asText()).isEqualTo(StoryboardCompiler.PLACEHOLDER_ID);
        assertThat(first.path("inputHash").asText()).isNotEqualTo(second.path("inputHash").asText());
        assertThat(CreationInputRules.renderContentHash(json, first.toString()))
                .isEqualTo(CreationInputRules.renderContentHash(json, second.toString()))
                .isEqualTo(compiler.check("board", brief(2000), candidate, List.of()).renderContentHash());
    }

    @ParameterizedTest @ValueSource(strings={"TTS", "AI_VIDEO"})
    void unavailableCapabilitiesRemainExplicitProblemsAndCompileRefuses(String feature) {
        var candidate = new Candidate(List.of(scene("s1", feature.equals("AI_VIDEO") ? feature : "TITLE_CARD", 2000,
                null, null, null, feature.equals("TTS") ? feature : "NO_DIALOGUE", null, List.of())));
        // TTS 不再整体拒绝，但缺少真实生成音频时仍然不可渲染；AI 视频仍无供应商。
        assertThat(compiler.check(brief(2000), candidate, List.of()).problems()).extracting(Problem::code)
                .contains("TTS".equals(feature) ? "TTS_AUDIO_MISSING" : "AI_VIDEO_NOT_CONFIGURED");
        assertThatThrownBy(() -> compiler.compile("board", brief(2000), candidate, List.of(), "run", "attempt")).hasMessageContaining("故事板尚不可渲染");
    }

    @Test void selectedTtsCannotBecomeSilentlyReadyByChangingScenesToMute() {
        ObjectNode brief = brief(2000); brief.put("dialogueMode", "TTS");
        var candidate = new Candidate(List.of(scene("s1", "TITLE_CARD", 2000, null, null, null, "NO_DIALOGUE", null, List.of())));
        assertThat(compiler.check(brief, candidate, List.of()).problems()).extracting(Problem::code)
                .contains("DIALOGUE_CHOICE_CONFLICT");
    }

    @Test void selectedNoDialogueCannotAcquireNarration() {
        ObjectNode brief = brief(2000); brief.put("dialogueMode", "NO_DIALOGUE");
        var candidate = new Candidate(List.of(scene("s1", "TITLE_CARD", 2000, null, null, null, "NARRATION", "voice", List.of(new Cue(0, 1000, "用户字幕")))));
        assertThat(compiler.check(brief, candidate, List.of(voice(1)) ).problems()).extracting(Problem::code).contains("DIALOGUE_CHOICE_CONFLICT");
    }

    @Test void actualRecordingNeedsCuesAndMayNotOverflowOrExceedThirtySeconds() {
        var noCues = new Candidate(List.of(scene("s1", "TITLE_CARD", 2000, null, null, null, "NARRATION", "voice", List.of())));
        assertThat(compiler.check(brief(2000), noCues, List.of(voice(2.1))).problems()).extracting(Problem::code)
                .contains("NARRATION_CUES_MISSING", "AUDIO_OVERFLOW");
        var withCue = new Candidate(List.of(scene("s1", "TITLE_CARD", 30000, null, null, null, "NARRATION", "voice", List.of(new Cue(0, 1000, "用户字幕")))));
        assertThat(compiler.check(brief(30000), withCue, List.of(voice(31))).problems()).extracting(Problem::code).contains("NARRATION_FORMAT_INVALID", "AUDIO_OVERFLOW");
    }

    @Test void userCueTimesAreTranslatedFromRecordingToGlobalTimelineWithoutInventingAlignment() {
        var candidate = new Candidate(List.of(scene("s1", "TITLE_CARD", 1000, null, null, null, "NO_DIALOGUE", null, List.of()),
                scene("s2", "TITLE_CARD", 2000, null, null, null, "NARRATION", "voice", List.of(new Cue(100, 1500, "用户给定时间")))));
        var document = compiler.compile("board", brief(3000), candidate, List.of(voice(1.5)), "run", "attempt");
        assertThat(document.path("subtitles").get(0).path("startMs").asInt()).isEqualTo(1100);
        assertThat(document.path("subtitles").get(0).path("endMs").asInt()).isEqualTo(2500);
        assertThat(document.path("assets")).hasSize(1);
        assertThat(document.path("assets").get(0).path("path").asText()).isEqualTo("assets/voice.wav");
    }

    @Test void rangeBeyondActualVideoAndMissingOriginalAudioAreRejected() {
        var candidate = new Candidate(List.of(scene("s1", "VIDEO_CLIP", 2000, "clip", 1000L, 3000L, "ORIGINAL", null, List.of())));
        assertThat(compiler.check(brief(2000), candidate, List.of(clip(2, false))).problems()).extracting(Problem::code)
                .contains("CLIP_OUT_OF_RANGE", "CLIP_AUDIO_MISSING");
    }

    @Test void actualOriginalAudioMayHaveNoSubtitlesButCannotAlsoCarryNarration() {
        var candidate = new Candidate(List.of(scene("s1", "VIDEO_CLIP", 2000, "clip", 0L, 2000L, "ORIGINAL", null, List.of())));
        var document = compiler.compile("board", brief(2000), candidate, List.of(clip(3, true)), "run", "attempt");
        assertThat(document.path("scenes").get(0).path("clip").path("audioMode").asText()).isEqualTo("ORIGINAL");
        assertThat(document.path("subtitles")).isEmpty();
        var conflict = new Candidate(List.of(scene("s1", "VIDEO_CLIP", 2000, "clip", 0L, 2000L, "ORIGINAL", "voice", List.of())));
        assertThat(compiler.check(brief(2000), conflict, List.of(clip(3, true), voice(1))).problems()).extracting(Problem::code).contains("AUDIO_CONFLICT");
    }

    @Test void videoWithNarrationMutesSourceInsteadOfImplicitMixing() {
        var candidate = new Candidate(List.of(scene("s1", "VIDEO_CLIP", 2000, "clip", 0L, 2000L, "NARRATION", "voice", List.of(new Cue(0, 1000, "用户字幕")))));
        var document = compiler.compile("board", brief(2000), candidate, List.of(clip(3, true), voice(1)), "run", "attempt");
        assertThat(document.path("scenes").get(0).path("clip").path("audioMode").asText()).isEqualTo("MUTE");
        assertThat(document.path("scenes").get(0).path("voice").path("assetId").asText()).isEqualTo("voice");
    }

    @Test void noLoopOrSpeedChangeIsUsedToReconcileClipLength() {
        var candidate = new Candidate(List.of(scene("s1", "VIDEO_CLIP", 2000, "clip", 0L, 1500L, "NO_DIALOGUE", null, List.of())));
        assertThat(compiler.check(brief(2000), candidate, List.of(clip(3, false))).problems()).extracting(Problem::code).contains("CLIP_DURATION_INVALID");
    }

    @Test void imageRequiresRealFrozenImageAndWholeImageCannotHideBodyCopy() {
        var candidate = new Candidate(List.of(scene("s1", "IMAGE_CARD", 2000, "photo", null, null, "NO_DIALOGUE", null, List.of())));
        assertThat(compiler.check(brief(2000), candidate, List.of()).problems()).extracting(Problem::code).contains("VISUAL_MISSING");
        assertThat(compiler.check(brief(2000), candidate, List.of(image())).renderable()).isTrue();
        var original = candidate.scenes().getFirst();
        var hiddenBody = new Candidate(List.of(new Scene(original.id(), original.layout(), original.title(), List.of("会被遮住的正文"), "", original.durationMs(),
                original.visualAssetId(), null, null, "CONTAIN", "NO_DIALOGUE", null, List.of(), List.of())));
        assertThat(compiler.check(brief(2000), hiddenBody, List.of(image())).problems()).extracting(Problem::code).contains("BODY_NOT_VISIBLE");
    }

    @Test void targetDurationIsNotSilentlyRewritten() {
        var candidate = new Candidate(List.of(scene("s1", "TITLE_CARD", 2000, null, null, null, "NO_DIALOGUE", null, List.of())));
        assertThat(compiler.check(brief(4000), candidate, List.of()).problems()).extracting(Problem::code).contains("TARGET_DURATION_MISMATCH");
    }

    @Test void unusedLargeAssetDoesNotBecomeRenderInputButUsedLargeAssetIsExplained() {
        var big = new Asset("photo", "IMAGE", "private", "normalized/photo", "a".repeat(64), 32L * 1024 * 1024 + 1, "image/png", image().metadata());
        var title = new Candidate(List.of(scene("s1", "TITLE_CARD", 2000, null, null, null, "NO_DIALOGUE", null, List.of())));
        assertThat(compiler.check(brief(2000), title, List.of(big)).renderable()).isTrue();
        var image = new Candidate(List.of(scene("s1", "IMAGE_CARD", 2000, "photo", null, null, "NO_DIALOGUE", null, List.of())));
        assertThat(compiler.check(brief(2000), image, List.of(big)).problems()).extracting(Problem::code).contains("ASSET_RENDER_LIMIT");
    }

    @Test void subtitleMustNotRunPastRealAudioOrOverlap() {
        var candidate = new Candidate(List.of(scene("s1", "TITLE_CARD", 2000, null, null, null, "NARRATION", "voice",
                List.of(new Cue(0, 800, "第一条"), new Cue(700, 1100, "第二条")))));
        assertThat(compiler.check(brief(2000), candidate, List.of(voice(1))).problems()).extracting(Problem::code).contains("CUE_OUT_OF_RANGE");
    }

    @Test void malformedMetadataReturnsProblemsRatherThanNullPointerException() {
        var badVoice = new Asset("voice", "NARRATION", "private", "normalized/voice", "a".repeat(64), 100, "audio/wav", null);
        var candidate = new Candidate(List.of(scene("s1", "TITLE_CARD", 2000, null, null, null, "NARRATION", "voice", List.of(new Cue(0, 1000, "用户字幕")))));
        assertThat(compiler.check(brief(2000), candidate, List.of(badVoice)).problems()).extracting(Problem::code).contains("NARRATION_FORMAT_INVALID");
    }

    @Test void selectedMusicIsFrozenInProjectAndChangesContentHashWithoutInventingVoice() {
        var candidate = silent(2000); var music = music(4, 1000);
        var plain = compiler.compile("board", brief(2000), candidate, List.of(music), "run", "attempt");
        var withNull = brief(2000); withNull.putNull("backgroundMusic");
        assertThat(compiler.compile("board", withNull, candidate, List.of(music), "run", "attempt")).isEqualTo(plain);
        ObjectNode selected = musicBrief(2000, 1000, 3000);
        var result = compiler.compile("board", selected, candidate, List.of(music), "run", "attempt");
        assertThat(result.path("backgroundMusic").path("sourceStartMs").asLong()).isEqualTo(1000);
        assertThat(result.path("backgroundMusic").path("sourceEndMs").asLong()).isEqualTo(3000);
        assertThat(result.path("backgroundMusic").path("gainDb").decimalValue()).isEqualByComparingTo("-12");
        assertThat(result.path("backgroundMusic").path("ducking").asBoolean()).isTrue();
        assertThat(result.path("assets")).hasSize(1);
        assertThat(result.path("assets").get(0).path("id").asText()).isEqualTo("music");
        assertThat(result.path("scenes").get(0).path("voice").isNull()).isTrue();
        assertThat(result.path("subtitles")).isEmpty();
        assertThat(CreationInputRules.renderContentHash(json, result.toString())).isNotEqualTo(CreationInputRules.renderContentHash(json, plain.toString()));
        ((ObjectNode)selected.path("backgroundMusic")).put("gainDb", -18);
        var quieter = compiler.compile("board", selected, candidate, List.of(music), "run", "attempt");
        assertThat(CreationInputRules.renderContentHash(json, quieter.toString())).isNotEqualTo(CreationInputRules.renderContentHash(json, result.toString()));
    }

    @Test void musicMustBeReadyMusicRoleAndCannotBorrowNarration() {
        var selected = musicBrief(2000, 0, 2000);
        assertThat(compiler.check(selected, silent(2000), List.of()).problems()).extracting(Problem::code).contains("BGM_MISSING");
        var voice = voice(4);
        var wrongRole = new Asset("music", voice.kind(), voice.bucketName(), voice.objectKey(), voice.sha256(), voice.bytes(), voice.contentType(), voice.metadata());
        assertThat(compiler.check(selected, silent(2000), List.of(wrongRole)).problems()).extracting(Problem::code).contains("BGM_MISSING");
    }

    @Test void musicNeedsActualNormalizedPcmStereoAndReliableDuration() {
        var music = music(4, 1000);
        ObjectNode metadata = music.metadata().deepCopy();
        ((ObjectNode)metadata.path("normalized").path("streams").get(0)).put("codec_name", "aac");
        var wrongCodec = new Asset(music.assetId(), music.kind(), music.bucketName(), music.objectKey(), music.sha256(), music.bytes(), music.contentType(), metadata);
        assertThat(compiler.check(musicBrief(2000, 0, 2000), silent(2000), List.of(wrongCodec)).problems()).extracting(Problem::code).contains("BGM_FORMAT_INVALID");
        var noMetadata = new Asset(music.assetId(), music.kind(), music.bucketName(), music.objectKey(), music.sha256(), music.bytes(), music.contentType(), null);
        assertThat(compiler.check(musicBrief(2000, 0, 2000), silent(2000), List.of(noMetadata)).problems()).extracting(Problem::code).contains("BGM_FORMAT_INVALID");
        ((ObjectNode)metadata.path("normalized").path("streams").get(0)).put("codec_name", "pcm_s16le").put("channels", 1);
        assertThat(compiler.check(musicBrief(2000, 0, 2000), silent(2000), List.of(wrongCodec)).problems()).extracting(Problem::code).contains("BGM_FORMAT_INVALID");
    }

    @Test void musicIsNeverLoopedSpedUpOrExtendedToMeetDuration() {
        assertThat(compiler.check(musicBrief(2000, 0, 1500), silent(2000), List.of(music(4, 1000))).problems())
                .extracting(Problem::code).contains("BGM_DURATION_MISMATCH");
        assertThat(compiler.check(musicBrief(2000, 3000, 5000), silent(2000), List.of(music(4, 1000))).problems())
                .extracting(Problem::code).contains("BGM_OUT_OF_RANGE");
        var withinFrame = musicBrief(2000, 0, 2033);
        assertThat(compiler.check(withinFrame, silent(2000), List.of(music(4, 1000))).renderable()).isTrue();
        ((ObjectNode)withinFrame.path("backgroundMusic")).put("sourceOutMs", 2034);
        assertThat(compiler.check(withinFrame, silent(2000), List.of(music(4, 1000))).problems()).extracting(Problem::code).contains("BGM_DURATION_MISMATCH");
    }

    @Test void shorterOfStreamAndContainerDurationProtectsMusicTail() {
        var music = music(4, 1000);
        ((ObjectNode)music.metadata().path("normalized").path("format")).put("duration", 2);
        assertThat(compiler.check(musicBrief(2000, 1000, 3000), silent(2000), List.of(music)).problems()).extracting(Problem::code).contains("BGM_OUT_OF_RANGE");
    }

    @ParameterizedTest @ValueSource(strings={"gain", "fade", "ducking", "fractional-range", "negative-range"})
    void invalidMusicMixAndRangesAreExplicitProblems(String invalid) {
        var selected = musicBrief(2000, 0, 2000); var config = (ObjectNode)selected.path("backgroundMusic");
        switch (invalid) {
            case "gain" -> config.put("gainDb", .1);
            case "fade" -> config.put("fadeInMs", 1500).put("fadeOutMs", 501);
            case "ducking" -> config.remove("ducking");
            case "fractional-range" -> config.put("sourceInMs", .1);
            default -> config.put("sourceInMs", -1);
        }
        assertThat(compiler.check(selected, silent(2000), List.of(music(4, 1000))).renderable()).isFalse();
    }

    @Test void musicAloneCanUseSixtyFourMibAndStillCountsTowardGlobalLimit() {
        assertThat(compiler.check(musicBrief(2000, 0, 2000), silent(2000), List.of(music(4, 64L * 1024 * 1024))).renderable()).isTrue();
        assertThat(compiler.check(musicBrief(2000, 0, 2000), silent(2000), List.of(music(4, 64L * 1024 * 1024 + 1))).problems())
                .extracting(Problem::code).contains("ASSET_RENDER_LIMIT");
        var assets = new ArrayList<Asset>(); assets.add(music(7, 64L * 1024 * 1024)); var scenes = new ArrayList<Scene>();
        for (int index = 0; index < 7; index++) {
            String id = "image-" + index; var image = image();
            assets.add(new Asset(id, "IMAGE", image.bucketName(), image.objectKey(), image.sha256(), 32L * 1024 * 1024, image.contentType(), image.metadata()));
            scenes.add(scene("scene-" + index, "IMAGE_CARD", 1000, id, null, null, "NO_DIALOGUE", null, List.of()));
        }
        assertThat(compiler.check(musicBrief(7000, 0, 7000), new Candidate(scenes), assets).problems()).extracting(Problem::code).contains("ASSET_TOTAL_LIMIT");
    }

    @ParameterizedTest @ValueSource(strings={"fadeInMs", "fadeOutMs"})
    void everyMusicFadeHasItsOwnTenSecondLimitEvenWhenWholeRangeIsLonger(String field) {
        var selected = musicBrief(30000, 0, 30000);
        var music = (ObjectNode)selected.path("backgroundMusic");
        music.put("fadeInMs", 0).put("fadeOutMs", 0).put(field, 10000);
        assertThat(compiler.check(selected, silent(30000), List.of(music(30, 1000))).renderable()).isTrue();
        music.put(field, 10001);
        assertThat(compiler.check(selected, silent(30000), List.of(music(30, 1000))).problems())
                .extracting(Problem::code).contains("BGM_MIX_INVALID");
    }

    @Test void ttsAudioMustMatchCurrentScriptAndCarryRealSubtitleTimes() {
        String text = "用户确认的配音文稿";
        var generate = brief(2000).put("dialogueMode", "TTS");
        var withCues = new Candidate(List.of(new Scene("s1", "TITLE_CARD", "开场", List.of(), text, 2000,
                null, null, null, "CONTAIN", "TTS", "voice", List.of(new Cue(0, 1200, text)), List.of())));
        var generated = ttsVoice(text, 1.5);
        assertThat(compiler.check(generate, withCues, List.of(generated)).problems()).isEmpty();

        var changed = new Candidate(List.of(new Scene("s1", "TITLE_CARD", "开场", List.of(), text + "改过", 2000,
                null, null, null, "CONTAIN", "TTS", "voice", List.of(new Cue(0, 1200, text)), List.of())));
        assertThat(compiler.check(generate, changed, List.of(generated)).problems()).extracting(Problem::code)
                .contains("TTS_TEXT_MISMATCH");

        var noCues = new Candidate(List.of(new Scene("s1", "TITLE_CARD", "开场", List.of(), text, 2000,
                null, null, null, "CONTAIN", "TTS", "voice", List.of(), List.of())));
        assertThat(compiler.check(generate, noCues, List.of(generated)).problems()).extracting(Problem::code)
                .contains("TTS_CUES_MISSING");
    }

    @Test void generatedVoiceCannotReplaceUserRecording() {
        String text = "用户自己的录音";
        var userNarration = brief(2000).put("dialogueMode", "USER_NARRATION");
        var scene = new Candidate(List.of(new Scene("s1", "TITLE_CARD", "开场", List.of(), text, 2000,
                null, null, null, "CONTAIN", "NARRATION", "voice", List.of(new Cue(0, 900, text)), List.of())));
        assertThat(compiler.check(userNarration, scene, List.of(ttsVoice(text, 1.0))).problems())
                .extracting(Problem::code).contains("NARRATION_ASSET_GENERATED");
    }

    @Test void ambienceIsAnIndependentBedWithItsOwnVolumeAndNoDuckingField() {
        var document = compiler.compile("board", ambienceBrief(2000, 500, 2500), silent(2000),
                List.of(music(4, 1000), ambience(4)), "run", "attempt");
        assertThat(document.path("ambience").path("assetId").asText()).isEqualTo("ambience");
        assertThat(document.path("ambience").path("sourceStartMs").asLong()).isEqualTo(500);
        assertThat(document.path("ambience").path("sourceEndMs").asLong()).isEqualTo(2500);
        assertThat(document.path("ambience").path("gainDb").asDouble()).isEqualTo(-24.0);
        // 环境声没有压低开关：它不是配乐，不随对白起伏。
        assertThat(document.path("ambience").has("ducking")).isFalse();
        assertThat(document.path("backgroundMusic").isMissingNode()).isTrue();
        assertThat(document.path("assets")).hasSize(1);
    }

    @Test void ambienceMustMatchTheWholeFilmAndStayInsideRealSource() {
        assertThat(compiler.check(ambienceBrief(2000, 0, 1000), silent(2000), List.of(ambience(4))).problems())
                .extracting(Problem::code).contains("AMB_DURATION_MISMATCH");
        assertThat(compiler.check(ambienceBrief(2000, 0, 2000), silent(2000), List.of(ambience(1.5))).problems())
                .extracting(Problem::code).contains("AMB_OUT_OF_RANGE");
        assertThat(compiler.check(ambienceBrief(2000, 0, 2000), silent(2000), List.of()).problems())
                .extracting(Problem::code).contains("AMB_MISSING");
        // 环境声必须是长音频素材，不能拿用户录音或图片顶替。
        assertThat(compiler.check(ambienceBrief(2000, 0, 2000), silent(2000), List.of(voice(4))).problems())
                .extracting(Problem::code).contains("AMB_MISSING");
    }

    @Test void ambienceAndBackgroundMusicCannotShareOneSource() {
        var brief = ambienceBrief(2000, 0, 2000);
        ((ObjectNode)brief.path("ambience")).put("assetId", "music");
        brief.putObject("backgroundMusic").put("assetId", "music").put("sourceInMs", 0).put("sourceOutMs", 2000)
                .put("gainDb", -12).put("fadeInMs", 0).put("fadeOutMs", 0).put("ducking", true);
        assertThat(compiler.check(brief, silent(2000), List.of(music(4, 1000))).problems())
                .extracting(Problem::code).contains("AMB_DUPLICATE_SOURCE");
    }

    @ParameterizedTest @ValueSource(strings={"gain", "fade", "fractional", "negative"})
    void invalidAmbienceMixAndRangeAreExplicitProblems(String invalid) {
        var brief = ambienceBrief(2000, 0, 2000); var ambience = (ObjectNode)brief.path("ambience");
        switch (invalid) {
            case "gain" -> ambience.put("gainDb", .5);
            case "fade" -> ambience.put("fadeInMs", 1500).put("fadeOutMs", 501);
            case "fractional" -> ambience.put("sourceInMs", .1);
            default -> ambience.put("sourceInMs", -1);
        }
        assertThat(compiler.check(brief, silent(2000), List.of(ambience(4))).problems())
                .extracting(Problem::code).contains("AMB_MIX_INVALID");
    }

    @Test void ambienceUsesTheSameSixtyFourMibLongAudioRole() {
        assertThat(compiler.check(ambienceBrief(2000, 0, 2000), silent(2000), List.of(ambience(4, 64L * 1024 * 1024))).renderable()).isTrue();
        assertThat(compiler.check(ambienceBrief(2000, 0, 2000), silent(2000), List.of(ambience(4, 64L * 1024 * 1024 + 1))).problems())
                .extracting(Problem::code).contains("ASSET_RENDER_LIMIT");
    }

    Candidate silent(long duration) { return new Candidate(List.of(scene("silent", "TITLE_CARD", duration, null, null, null, "NO_DIALOGUE", null, List.of()))); }
    Asset ttsVoice(String text, double seconds) {
        var voice = voice(seconds);
        ObjectNode metadata = voice.metadata().deepCopy();
        metadata.putObject("generated").put("kind", "TTS").put("provider", "dashscope")
                .put("textSha256", StoryboardCodec.digest(text));
        return new Asset(voice.assetId(), voice.kind(), voice.bucketName(), voice.objectKey(), voice.sha256(),
                voice.bytes(), voice.contentType(), metadata);
    }
    ObjectNode musicBrief(long duration, long start, long end) {
        var brief = brief(duration); brief.putObject("backgroundMusic").put("assetId", "music").put("sourceInMs", start).put("sourceOutMs", end)
                .put("gainDb", -12).put("fadeInMs", 100).put("fadeOutMs", 200).put("ducking", true); return brief;
    }
    ObjectNode ambienceBrief(long duration, long start, long end) {
        var brief = brief(duration);
        brief.putObject("ambience").put("assetId", "ambience").put("sourceInMs", start).put("sourceOutMs", end)
                .put("gainDb", -24).put("fadeInMs", 100).put("fadeOutMs", 200);
        return brief;
    }
    Asset ambience(double seconds) { return ambience(seconds, 4000); }
    Asset ambience(double seconds, long bytes) {
        var voice = voice(seconds);
        return new Asset("ambience", "MUSIC", voice.bucketName(), "normalized/ambience.wav",
                "e".repeat(64), bytes, "audio/wav", voice.metadata());
    }
    Asset music(double seconds, long bytes) {
        var voice = voice(seconds);
        return new Asset("music", "MUSIC", voice.bucketName(), "normalized/music.wav", "d".repeat(64), bytes, "audio/wav", voice.metadata());
    }

    static Scene scene(String id, String layout, long duration, String visual, Long begin, Long end, String audio, String voice, List<Cue> cues) {
        return new Scene(id, layout, "用户主题", List.of(), "", duration, visual, begin, end, "CONTAIN", audio, voice, cues, List.of());
    }
    ObjectNode brief(long duration) { return json.createObjectNode().put("targetDurationMs", duration).put("dialogueMode", "USER_NARRATION"); }
    Asset voice(double seconds) {
        ObjectNode metadata = json.createObjectNode(); var normalized = metadata.putObject("normalized");
        normalized.putObject("format").put("duration", seconds);
        normalized.putArray("streams").addObject().put("codec_type", "audio").put("codec_name", "pcm_s16le").put("sample_rate", "48000").put("channels", 2).put("duration", seconds);
        return new Asset("voice", "NARRATION", "private-historical", "normalized/voice.wav", "a".repeat(64), 1000, "audio/wav", metadata);
    }
    Asset image() {
        ObjectNode metadata = json.createObjectNode(); metadata.putObject("normalized").putArray("streams").addObject().put("codec_type", "video").put("codec_name", "png").put("width", 1280).put("height", 720);
        return new Asset("photo", "IMAGE", "private-historical", "normalized/photo.png", "b".repeat(64), 2000, "image/png", metadata);
    }
    Asset clip(double seconds, boolean audio) {
        ObjectNode metadata = json.createObjectNode(); var streams = metadata.putObject("normalized").putArray("streams");
        streams.addObject().put("codec_type", "video").put("codec_name", "h264").put("width", 1280).put("height", 720).put("duration", seconds).put("start_time", 0);
        if (audio) streams.addObject().put("codec_type", "audio").put("codec_name", "aac").put("duration", seconds).put("start_time", 0);
        return new Asset("clip", "VIDEO_CLIP", "private-historical", "normalized/clip.mp4", "c".repeat(64), 3000, "video/mp4", metadata);
    }
}
