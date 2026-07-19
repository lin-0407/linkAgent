package com.link.linkagent.creator.media.probe.service;

import org.springframework.http.HttpStatus;

/**
 * 媒体探测失败异常。
 * <p>
 * 单独携带 HTTP 状态，是为了把文件损坏、格式不支持和运行环境不可用区分返回给前端。
 */
public class MediaProbeException extends RuntimeException {

    private final HttpStatus status;

    public MediaProbeException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public MediaProbeException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
