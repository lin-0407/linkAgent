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
        assertThat(compiler.check(brief(2000), candidate, List.of()).problems()).extracting(Problem::code).contains(feature + "_NOT_CONFIGURED");
        assertThatThrownBy(() -> compiler.compile("board", brief(2000), candidate, List.of(), "run", "attempt")).hasMessageContaining("故事板尚不可渲染");
    }

    @Test void selectedTtsCannotBecomeSilentlyReadyByChangingScenesToMute() {
        ObjectNode brief = brief(2000); brief.put("dialogueMode", "TTS");
        var candidate = new Candidate(List.of(scene("s1", "TITLE_CARD", 2000, null, null, null, "NO_DIALOGUE", null, List.of())));
        assertThat(compiler.check(brief, candidate, List.of()).problems()).extracting(Problem::code).contains("TTS_NOT_CONFIGURED");
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
