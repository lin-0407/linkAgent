package com.link.linkagent.creator.feedback.model;

import java.util.List;

/**
 * 反馈追问证据检索结果（服务内部协作对象）。
 * <p>
 * 由 CreatorFeedbackEvidenceRetrievalService 产出、CreatorFeedbackService.chat 消费。
 * 把“证据记录 + 检索模式 + 是否启用 RAG”打包返回，是为了让 chat 方法只负责编排和构建 Prompt，
 * 不再关心证据是从 Milvus 还是 SQL 选出来的。
 * <p>
 * 注意：这里的 evidenceRecords 一律来自 MySQL 当前有效明细（即使先经过 Milvus 召回，也会回查 MySQL），
 * 保证向量库里的旧文档或脏数据不会直接进入回答。
 */
public record CreatorFeedbackEvidenceRetrievalResult(
        List<CreatorFeedbackItemRecord> evidenceRecords,
        String retrievalMode,
        boolean ragEnabled
) {

    /** 未启用 RAG 或向量不可用，使用现有 SQL 证据。 */
    public static final String MODE_SQL = "MYSQL_REPORT_AND_CLASSIFIED_ITEMS";

    /** Milvus 命中足够证据，并回查 MySQL。 */
    public static final String MODE_VECTOR = "MILVUS_VECTOR_AND_MYSQL_REPORT";

    /** Milvus 命中不足，合并 SQL 证据补足。 */
    public static final String MODE_VECTOR_WITH_SQL_FALLBACK = "MILVUS_VECTOR_WITH_SQL_FALLBACK";
}
