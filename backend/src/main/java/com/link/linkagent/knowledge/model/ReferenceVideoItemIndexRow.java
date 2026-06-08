package com.link.linkagent.knowledge.model;

/**
 * 子条目向量索引输入行（阶段 5.2c-1）。
 * <p>
 * 不直接复用 {@link ReferenceVideoItemRecord}：索引只关心「拿什么文本去嵌入、往 metadata 里写哪些键」，
 * 不需要点赞数 / 回复数 / 清洗原因等展示字段；而它<b>额外</b>需要父表的 {@code category}/{@code tier}
 * （子表本身没有这两列），故单独定义一个贴合索引用途的窄行对象（简单优先、字段即用途）。
 * <p>
 * {@code category}/{@code tier} 由 {@code listIndexableItems} JOIN 父表反范式带出，
 * 写进子向量文档 metadata，供 5.2c-2 子召回复用与父检索同款的 category/tier 元数据过滤。
 * 用普通 POJO 而非 record：MyBatis @Results 通过 setter 回填字段。
 */
public class ReferenceVideoItemIndexRow {

    /** 子条目唯一标识（UUID）；作为向量文档 id，且回写 embedding_status 的定位键。 */
    private String itemId;
    /** 关联父表 video_id；small-to-big 扩展回父表案例卡片的关键键，必写入 metadata。 */
    private String videoId;
    /** 评论 / 弹幕原文，作为 small 端的向量文档文本本体。 */
    private String content;
    /** 情绪倾向（POSITIVE / NEGATIVE）；写入 metadata 供排查与后续按情绪取证。 */
    private String sentiment;
    /** 来源类型（COMMENT / DANMAKU）；写入 metadata。 */
    private String sourceType;
    /** 父表分区（反范式带入），写入 metadata 供子召回过滤。 */
    private String category;
    /** 父表案例层级（反范式带入），写入 metadata 供子召回过滤。 */
    private String tier;

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public String getVideoId() {
        return videoId;
    }

    public void setVideoId(String videoId) {
        this.videoId = videoId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSentiment() {
        return sentiment;
    }

    public void setSentiment(String sentiment) {
        this.sentiment = sentiment;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
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
