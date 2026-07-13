package com.link.linkagent.creator.media.storage;

/**
 * 对象存储调用失败异常。
 * <p>
 * 业务层只依赖该异常，不直接识别 AWS SDK 异常类型（如 SdkException、NoSuchUploadException），
 * 避免厂商细节扩散到上传状态机和 Controller 层。
 * <p>
 * 异常消息使用固定中文摘要（如"创建媒体分片上传失败"），不拼接 Endpoint、
 * 对象键或签名参数，防止敏感信息通过异常消息泄露。
 * <p>
 * 原始 SDK 异常通过 cause 保留，供日志框架按需记录堆栈（ERROR 级别），
 * 但不会出现在 API 响应或用户可见的错误信息中。
 */
public class MediaStorageException extends RuntimeException {

    /**
     * @param message 中文错误摘要，不含敏感信息
     * @param cause   原始 SDK 异常（仅用于日志，不对外暴露）
     */
    public MediaStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
