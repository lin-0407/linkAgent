package com.link.linkagent.creator.interactive.model;

/**
 * 已成功上传并提取的补充资料元数据。
 * 这里只恢复展示所需信息，浏览器本地 File 对象不会也不应该持久化到服务端。
 */
public record InteractiveDocumentResponse(
        String documentId,
        String fileName,
        long fileSize,
        String contentType
) {
}
