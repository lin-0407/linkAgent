package com.link.linkagent.creator.competitor.model;

import java.time.LocalDateTime;

/**
 * 竞品视频数据库记录。
 * 第一版只保留一个主竞品视频的 BV 号、名称和对照文本，避免过早拆成多条明细记录。
 */
public class CreatorCompetitorSampleRecord {

    private Long id;
    private String competitorBvId;
    private String competitorVideoName;
    private String taskId;
    private String category;
    private String competitorSamples;
    private String compareDimension;
    private String extraContext;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCompetitorBvId() {
        return competitorBvId;
    }

    public void setCompetitorBvId(String competitorBvId) {
        this.competitorBvId = competitorBvId;
    }

    public String getCompetitorVideoName() {
        return competitorVideoName;
    }

    public void setCompetitorVideoName(String competitorVideoName) {
        this.competitorVideoName = competitorVideoName;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getCompetitorSamples() {
        return competitorSamples;
    }

    public void setCompetitorSamples(String competitorSamples) {
        this.competitorSamples = competitorSamples;
    }

    public String getCompareDimension() {
        return compareDimension;
    }

    public void setCompareDimension(String compareDimension) {
        this.compareDimension = compareDimension;
    }

    public String getExtraContext() {
        return extraContext;
    }

    public void setExtraContext(String extraContext) {
        this.extraContext = extraContext;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
