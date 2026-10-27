package com.link.linkagent.creator.creation.aivideo.provider;

import java.math.BigDecimal;
import java.util.List;

/**
 * 视频生成供应商适配协议。
 * <p>
 * 业务只依赖异步任务语义：提交后拿到任务标识，随后查询状态与结果；
 * 业务不写死厂商请求格式，也不把任意地址当成兼容协议。真实供应商仍需在部署中单独验收。
 */
public interface VideoGenerationProvider {

    String provider();

    String model();

    Capabilities capabilities();

    TaskRef submit(Submit request);

    QueryResult query(String taskId);

    /** 结果地址通常短期有效，必须立即下载并保存到私有存储，不能作为永久资产。 */
    byte[] download(String url, long maxBytes);

    record Capabilities(List<Integer> durationsSeconds, int width, int height, BigDecimal usdPerSecond) { }

    record Submit(String prompt, int seconds, int width, int height, long seed) { }

    record TaskRef(String taskId, String requestId) { }

    enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED }

    /**
     * usageSeconds 只在供应商明确回报用量时填写；缺失时不能按请求时长冒充账单。
     */
    record QueryResult(Status status, String videoUrl, Long usageSeconds, Long durationMs,
                       Integer width, Integer height, String failureCode, String requestId) { }

    class VideoProviderException extends RuntimeException {
        public VideoProviderException(String message) { super(message); }
    }

    /** 明确的 4xx 拒绝：可以确认没有创建任务，不能把额度长期挂成未知。 */
    class VideoRejectedException extends VideoProviderException {
        public VideoRejectedException(String message) { super(message); }
    }

    /** 部署配置缺失：请求没有发出，也不能计入未知费用。 */
    class VideoNotConfiguredException extends VideoProviderException {
        public VideoNotConfiguredException(String message) { super(message); }
    }

    /** 提交结果不明：可能已经创建任务，只能保留未知费用并停止自动重试。 */
    class VideoUncertainException extends VideoProviderException {
        public VideoUncertainException(String message) { super(message); }
    }
}
