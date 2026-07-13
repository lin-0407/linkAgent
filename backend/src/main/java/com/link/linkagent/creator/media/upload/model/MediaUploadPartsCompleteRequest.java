package com.link.linkagent.creator.media.upload.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量登记已上传分片请求。
 * <p>
 * 包含已完成分片列表，每个分片需要 @Valid 级联校验。
 * 单次最多登记 20 个分片，与签名批量上限保持一致。
 *
 * @param parts 已完成分片列表（含 partNumber、etag、partSize）
 */
public record MediaUploadPartsCompleteRequest(
        @NotEmpty(message = "已完成分片不能为空")
        @Size(max = 20, message = "单次最多登记20个已完成分片")
        List<@Valid CompletedMediaUploadPartRequest> parts // @Valid 触发级联校验
) {
}
