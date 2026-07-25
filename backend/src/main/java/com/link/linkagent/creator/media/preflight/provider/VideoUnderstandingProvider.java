package com.link.linkagent.creator.media.preflight.provider;

/** 发布前体检使用的视频理解边界。 */
public interface VideoUnderstandingProvider {

    AnalysisResult analyze(String videoUrl, String prompt);

    /**
     * 重点片段允许指定更强模型和更高抽帧率；默认实现保留测试替身与其它 Provider 的简单接入方式。
     */
    default AnalysisResult analyze(String videoUrl, String prompt, String model, double fps) {
        return analyze(videoUrl, prompt);
    }

    record AnalysisResult(String content, Long inputTokens, Long outputTokens) {
    }
}
