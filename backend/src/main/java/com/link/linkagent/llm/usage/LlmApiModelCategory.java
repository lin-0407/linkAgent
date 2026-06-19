package com.link.linkagent.llm.usage;

/**
 * 模型 API 分类。
 * 用固定枚举统一前后端口径，避免页面统计时把“文本模型”和“向量模型”混在一起。
 */
public enum LlmApiModelCategory {
    TEXT,
    EMBEDDING,
    RERANK
}
