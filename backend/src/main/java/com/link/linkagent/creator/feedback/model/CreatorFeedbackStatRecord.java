package com.link.linkagent.creator.feedback.model;

/**
 * 分类统计记录。
 * Mapper 只返回最小聚合结果，展示标签在 Service 层转换，避免 SQL 里混入前端文案。
 */
public class CreatorFeedbackStatRecord {

    private String name;
    private Long count;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getCount() {
        return count;
    }

    public void setCount(Long count) {
        this.count = count;
    }
}
