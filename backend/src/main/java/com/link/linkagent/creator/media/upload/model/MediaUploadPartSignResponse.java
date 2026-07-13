package com.link.linkagent.creator.media.upload.model;

import java.util.List;

/**
 * 批量分片签名 API 响应。
 * <p>
 * 包装多个 PresignedMediaUploadPartResponse，前端遍历 parts 列表逐个上传。
 *
 * @param parts 分片签名列表（每个分片一个预签名 URL）
 */
public record MediaUploadPartSignResponse(
        List<PresignedMediaUploadPartResponse> parts
) {
}
