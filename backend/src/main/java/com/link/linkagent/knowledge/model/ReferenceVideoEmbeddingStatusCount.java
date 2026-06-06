package com.link.linkagent.knowledge.model;

/**
 * 案例向量索引状态计数投影（阶段 5.1c）。
 * <p>
 * 对应 {@code SELECT embedding_status AS status, COUNT(1) AS count ... GROUP BY embedding_status} 的一行，
 * 供 index/status 汇总各状态数量。COUNT 恒非空，故 count 用 long 承载。
 */
public class ReferenceVideoEmbeddingStatusCount {

    private String status;
    private long count;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }
}
