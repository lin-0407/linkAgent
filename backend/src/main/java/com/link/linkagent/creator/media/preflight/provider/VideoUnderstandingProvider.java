package com.link.linkagent.creator.media.preflight.provider;

/** 发布前体检使用的视频理解边界。 */
public interface VideoUnderstandingProvider {

    AnalysisResult analyze(String videoUrl, String prompt);

    record AnalysisResult(String content, Long inputTokens, Long outputTokens) {
    }
}
