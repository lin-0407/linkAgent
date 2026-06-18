package com.link.linkagent.knowledge.model;

/**
 * 主题中块向量索引输入行。
 * <p>
 * 不直接复用 {@link ReferenceVideoChunkRecord}：索引时额外需要父表的 category / tier 写入 metadata，
 * 让主题中块召回能和父卡片、子条目保持同一套分区与层级过滤口径。
 */
public class ReferenceVideoChunkIndexRow {

    private String chunkId;
    private String videoId;
    private String chunkType;
    private String chunkTitle;
    private String chunkContent;
    private String sourceItemIds;
    private String category;
    private String tier;

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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }
}
