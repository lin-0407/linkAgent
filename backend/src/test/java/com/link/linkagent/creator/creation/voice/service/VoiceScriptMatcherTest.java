package com.link.linkagent.creator.creation.voice.service;

import com.link.linkagent.creator.media.preflight.provider.SpeechRecognitionProvider.Segment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 字幕只使用转写时间；文本比对是确定性算法，不依赖模型。 */
class VoiceScriptMatcherTest {

    private final VoiceScriptMatcher matcher = new VoiceScriptMatcher();

    @Test
    void cuesComeFromTranscriptTimestampsAndMergedOnlyWhenAdjacent() {
        var match = matcher.compare("今天先分类再收纳", List.of(
                new Segment(100, 900, "今天先分类", null, null, "zh"),
                new Segment(950, 1800, "再收纳", null, null, "zh")), 2000, 3000, 100);

        assertThat(match.problemCode()).isNull();
        assertThat(match.cues()).hasSize(1);
        assertThat(match.cues().getFirst().startMs()).isEqualTo(100);
        assertThat(match.cues().getFirst().endMs()).isEqualTo(1800);
        assertThat(match.cues().getFirst().text()).isEqualTo("今天先分类再收纳");
        assertThat(match.ratio()).isGreaterThan(0.99);
    }

    @Test
    void longSilenceDoesNotGetFoldedIntoOneCue() {
        var match = matcher.compare("第一段落第二段落", List.of(
                new Segment(0, 500, "第一段落", null, null, "zh"),
                new Segment(2000, 2600, "第二段落", null, null, "zh")), 2600, 3000, 100);

        assertThat(match.cues()).hasSize(2);
        assertThat(match.cues().get(1).startMs()).isEqualTo(2000);
    }

    @Test
    void transcriptOutsideAudioOrSceneIsDroppedNotMoved() {
        var match = matcher.compare("真实内容", List.of(
                new Segment(100, 900, "真实内容", null, null, "zh"),
                new Segment(4000, 5000, "越界内容", null, null, "zh")), 1000, 3000, 100);

        assertThat(match.cues()).hasSize(1);
        assertThat(match.transcript()).isEqualTo("真实内容");
    }

    @Test
    void noSpeechIsAnExplicitProblemInsteadOfEmptySubtitles() {
        var match = matcher.compare("文稿", List.of(), 1000, 1000, 100);
        assertThat(match.problemCode()).isEqualTo("VOICE_AUDIO_NO_SPEECH");
        assertThat(match.cues()).isEmpty();
    }

    @Test
    void textMismatchIsMeasuredDeterministically() {
        var match = matcher.compare("今天先分类再收纳", List.of(
                new Segment(0, 1200, "完全不同的另一段话", null, null, "zh")), 1200, 2000, 100);
        assertThat(match.problemCode()).isNull();
        assertThat(match.ratio()).isLessThan(0.5);
        assertThat(VoiceScriptMatcher.normalize("今天，先分类！")).isEqualTo("今天先分类");
        assertThat(VoiceScriptMatcher.similarity("abc", "abc")).isEqualTo(1.0);
    }

    @Test
    void tooManySegmentsOrTooLongCueRequiresAuthorAction() {
        List<Segment> many = new ArrayList<>();
        // 间隔超过合并阈值，确保确实是 120 条独立字幕而不是被合并成一条。
        for (int index = 0; index < 120; index++) many.add(new Segment(index * 1000L, index * 1000L + 300, "字", null, null, "zh"));
        assertThat(matcher.compare("字", many, 120000, 200000, 100).problemCode()).isEqualTo("VOICE_CUE_COUNT_EXCEEDED");

        String longText = "一".repeat(150);
        var tooLong = matcher.compare(longText, List.of(new Segment(0, 1000, longText, null, null, "zh")), 1000, 2000, 100);
        assertThat(tooLong.problemCode()).isEqualTo("VOICE_CUE_TEXT_TOO_LONG");
    }
}
