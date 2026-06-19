package com.link.linkagent.llm.usage;

/**
 * 模型 API 调用状态。
 * SKIPPED 用来表达“开关未启用或候选不足导致没有真实外部调用”，方便和真实失败区分。
 */
public enum LlmApiCallStatus {
    SUCCESS,
    FAILED,
    SKIPPED
}
