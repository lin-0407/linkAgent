package com.link.linkagent.creator.media.upload.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量申请分片短时上传 URL 的请求。
 * <p>
 * partNumbers 是分片序号列表，每个序号单独校验范围（1–10000）。
 * 单次最多申请 20 个（由 maxSignBatch 配置控制），防止响应体过大。
 *
 * @param partNumbers 需要签名的分片序号列表；不能重复，不能为空
 */
public record MediaUploadPartSignRequest(
        @NotEmpty(message = "分片序号不能为空")
        @Size(max = 20, message = "单次最多申请20个分片签名") // 与 maxSignBatch 默认值一致
        List<
                @NotNull(message = "分片序号不能为空")
                @Min(value = 1, message = "分片序号不能小于1")          // S3 分片序号从 1 开始
                @Max(value = 10000, message = "分片序号不能超过10000")  // S3 最大分片数
                Integer
                > partNumbers
) {
}
