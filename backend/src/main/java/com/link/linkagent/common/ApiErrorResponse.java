package com.link.linkagent.common;

/**
 * 统一错误响应。
 * 前端只读取 message 字段，是为了让接口错误能直接展示可理解的业务原因。
 */
public record ApiErrorResponse(
        int status,
        String message,
        String path
) {
}
