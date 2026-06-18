package com.link.linkagent.knowledge.model;

import java.time.LocalDateTime;

/**
 * 跨分区视频案例主题中块表（creator_reference_video_chunk）的数据库记录对象。
 * <p>
 * 主题中块位于「整条视频案例父块」和「评论弹幕原文小块」之间，用来承载标题包装、内容定位、观众反馈等
 * 创作者真正会提问的中等粒度语义。这样检索不必只在一整张大卡片和一条原始评论之间二选一。
 */
public class ReferenceVideoChunkRecord {

    private Long id;
    private String chunkId;
    private String videoId;
    private String chunkType;
    private String chunkTitle;
    private String chunkContent;
    private String sourceItemIds;
    private String embeddingStatus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getChunkId() {
        return chunkId;
    }

    public void setChunkId(String chunkId) {
        this.chunkId = chunkId;
    }

    public String getVideoId() {
        return videoId;
    }

    public void setVideoId(String videoId) {
        this.videoId = videoId;
    }

    public String getChunkType() {
        return chunkType;
    }

    public void setChunkType(String chunkType) {
        this.chunkType = chunkType;
    }

    public String getChunkTitle() {
        return chunkTitle;
    }

    public void setChunkTitle(String chunkTitle) {
        this.chunkTitle = chunkTitle;
    }

    public String getChunkContent() {
        return chunkContent;
    }

    public void setChunkContent(String chunkContent) {
        this.chunkContent = chunkContent;
    }

    public String getSourceItemIds() {
        return sourceItemIds;
    }

    public void setSourceItemIds(String sourceItemIds) {
        this.sourceItemIds = sourceItemIds;
    }

    public String getEmbeddingStatus() {
        return embeddingStatus;
    }

    public void setEmbeddingStatus(String embeddingStatus) {
        this.embeddingStatus = embeddingStatus;
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
