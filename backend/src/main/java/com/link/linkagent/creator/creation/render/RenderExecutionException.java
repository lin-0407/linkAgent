package com.link.linkagent.creator.creation.render;

/** 对外只保留稳定错误码，不把子进程输出和部署路径带入业务错误。 */
public class RenderExecutionException extends RuntimeException {
    private final String code;

    public RenderExecutionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
