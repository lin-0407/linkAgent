package com.link.linkagent.creator.media.preflight.provider;

import java.util.List;

/**
 * 文件语音转写边界。
 * Provider 任务 ID 与时间戳结果分开读取，服务重启后才能继续查询而不重复提交计费任务。
 */
public interface SpeechRecognitionProvider {

    String submit(String audioUrl);

    QueryResult query(String providerTaskId);

    TranscriptionResult loadResult(String transcriptionUrl);

    enum Status {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    record QueryResult(Status status, String transcriptionUrl, Long usageSeconds, String errorMessage) {
    }

    record TranscriptionResult(List<Segment> segments) {
    }

    record Segment(long startMs,
                   long endMs,
                   String text,
                   Double confidence,
                   String speaker,
                   String language) {
    }
}
