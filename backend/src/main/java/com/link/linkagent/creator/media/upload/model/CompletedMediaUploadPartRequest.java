package com.link.linkagent.creator.media.upload.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 浏览器完成单个分片 PUT 后登记的事实。
 * <p>
 * etag 来自 PUT 响应的 ETag 头，由 OSS 生成，不得自行计算或修改。
 * partSize 是浏览器确认的分片实际字节数，Service 层会与预期值对账。
 *
 * @param partNumber 分片序号（1-based）
 * @param etag       OSS 返回的分片 ETag（不透明字符串，长度不超过 255）
 * @param partSize   浏览器确认的分片实际字节数（必须与预期一致，最后一片除外）
 */
public record CompletedMediaUploadPartRequest(
        @Min(value = 1, message = "分片序号不能小于1")
        @Max(value = 10000, message = "分片序号不能超过10000")
        int partNumber,

        @NotBlank(message = "分片 ETag 不能为空")
        @Size(max = 255, message = "分片 ETag 长度不能超过255个字符")
        String etag,

        @Positive(message = "分片大小必须大于0")
        long partSize
) {
}
