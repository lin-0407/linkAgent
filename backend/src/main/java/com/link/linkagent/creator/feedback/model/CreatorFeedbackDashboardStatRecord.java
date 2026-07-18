package com.link.linkagent.creator.feedback.model;

/**
 * 反馈仪表盘聚合统计记录。
 * 一次 SQL 同时返回总量、分类和情绪分布，减少页面打开时的数据库往返次数。
 */
public class CreatorFeedbackDashboardStatRecord {

    private String statScope;
    private String name;
    private Long count;

    public String getStatScope() {
        return statScope;
    }

    public void setStatScope(String statScope) {
        this.statScope = statScope;
    }

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
