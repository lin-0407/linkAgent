package com.link.linkagent.creator.creation.voice.service;

import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.Cue;
import com.link.linkagent.creator.media.preflight.provider.SpeechRecognitionProvider.Segment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 文稿与转写文本只做确定性比对，不使用模型评分。
 * 字幕时间全部来自转写结果；文本不一致或超出容量时明确交给作者处理，不挪动时间硬凑。
 */
@Component
public class VoiceScriptMatcher {

    public record Match(double ratio, List<Cue> cues, String transcript, String problemCode, String message) { }

    public Match compare(String expected, List<Segment> segments, long audioDurationMs, long sceneDurationMs,
                         int maxCues) {
        if (segments == null || segments.isEmpty())
            return new Match(0, List.of(), "", "VOICE_AUDIO_NO_SPEECH", "实际音频没有可用于字幕的语音片段");
        List<Segment> ordered = new ArrayList<>(segments);
        ordered.sort(Comparator.comparingLong(Segment::startMs));
        List<Cue> cues = new ArrayList<>();
        StringBuilder transcript = new StringBuilder();
        for (Segment segment : ordered) {
            long start = Math.max(0, segment.startMs()), end = Math.min(sceneDurationMs, segment.endMs());
            if (end <= start || start >= audioDurationMs) continue;
            end = Math.min(end, audioDurationMs);
            if (end <= start) continue;
            String text = segment.text() == null ? "" : segment.text().strip();
            if (text.isEmpty()) continue;
            transcript.append(text);
            if (!cues.isEmpty() && cues.getLast().endMs() > start) {
                // 供应商段落重叠时取较晚起点，避免字幕时间倒置或互相覆盖。
                start = cues.getLast().endMs();
                if (end <= start) continue;
            }
            // 只合并紧邻且总长不超限的片段，避免把长静音算进同一句字幕。
            if (!cues.isEmpty() && start - cues.getLast().endMs() <= 400
                    && text.length() + cues.getLast().text().length() <= 120) {
                Cue previous = cues.removeLast();
                cues.add(new Cue(previous.startMs(), end, previous.text() + text));
            } else {
                cues.add(new Cue(start, end, text));
            }
        }
        if (cues.isEmpty())
            return new Match(0, List.of(), transcript.toString(), "VOICE_AUDIO_NO_SPEECH", "实际音频没有可绑定到镜头的字幕时间");
        // 超出条数时不删词也不挪时间，交给作者缩短文稿后重新生成。
        if (cues.size() > maxCues)
            return new Match(0, List.of(), transcript.toString(), "VOICE_CUE_COUNT_EXCEEDED", "单场景字幕条数超过上限，请缩短文稿后重新生成");
        for (Cue cue : cues)
            if (cue.text().length() > 120)
                return new Match(0, List.of(), transcript.toString(), "VOICE_CUE_TEXT_TOO_LONG", "单条字幕超过 120 字符，请拆分文稿后重新生成");
        double ratio = similarity(normalize(expected), normalize(transcript.toString()));
        return new Match(ratio, List.copyOf(cues), transcript.toString(), null, null);
    }

    /** 只保留文字与数字，忽略标点、空白和大小写，避免格式差异被当成内容不一致。 */
    static String normalize(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(value.length());
        value.codePoints().filter(Character::isLetterOrDigit).forEach(result::appendCodePoint);
        return result.toString().toLowerCase();
    }

    static double similarity(String left, String right) {
        if (left.isEmpty() && right.isEmpty()) return 0;
        if (left.isEmpty() || right.isEmpty()) return 0;
        if (left.equals(right)) return 1;
        int[] previous = new int[right.length() + 1], current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            char a = left.charAt(i - 1);
            for (int j = 1; j <= right.length(); j++) {
                int cost = a == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous; previous = current; current = swap;
        }
        int distance = previous[right.length()];
        return 1 - (double) distance / Math.max(left.length(), right.length());
    }
}
