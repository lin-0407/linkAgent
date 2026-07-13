package com.link.linkagent.creator.media.storage;

/**
 * 完成上传后通过 HeadObject 获取的事实元数据。
 * <p>
 * 这些值来自对象存储的实际记录，优先级高于客户端的声明。
 * 上传完成校验时以这些值为准，不一致则标记失败。
 *
 * @param contentLength 实际对象字节数；必须与客户端声明的 expectedSize 一致
 * @param contentType   对象存储记录的媒体类型；P0 必须为 video/mp4
 * @param etag          对象 ETag；OSS 与 S3 的 ETag 算法不同，因此只作观测值，不参与业务判断
 */
public record StoredObjectMetadata(
        long contentLength,
        String contentType,
        String etag
) {
}
