package com.link.linkagent.creator.media.probe.model;

import java.math.BigDecimal;

/**
 * ffprobe 读取到的确定性媒体元信息。
 * <p>
 * 这些字段来自视频文件本身，不经过大模型推断，用于判断成片是否能进入后续试映链路。
 *
 * @param durationMs 视频时长毫秒
 * @param width      视频宽度
 * @param height     视频高度
 * @param frameRate  平均帧率
 * @param videoCodec 视频编码
 * @param audioCodec 音频编码，无音轨时为空
 * @param hasAudio   是否存在音轨
 */
public record MediaProbeResult(
        long durationMs,
        int width,
        int height,
        BigDecimal frameRate,
        String videoCodec,
        String audioCodec,
        boolean hasAudio
) {
}
