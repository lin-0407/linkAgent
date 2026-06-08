package com.link.linkagent.knowledge.mapper;

import com.link.linkagent.knowledge.model.ReferenceVideoEmbeddingStatusCount;
import com.link.linkagent.knowledge.model.ReferenceVideoItemIndexRow;
import com.link.linkagent.knowledge.model.ReferenceVideoItemRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoScoringRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 跨分区视频案例主表访问层。
 * 5.1a 只需要「落库 + 分页列表」两类操作：插入按条进行（与反馈明细 insertItem 一致，避免 foreach 批量 SQL 的复杂度），
 * 列表用 (#{x} IS NULL OR col = #{x}) 处理可选过滤，免去动态 SQL 脚本，保持注解式 Mapper 的简单可读。
 * 向量索引相关字段不在本阶段写入，依赖 DDL 默认值（embedding_status=PENDING），留给 5.1c 的索引服务回写。
 */
@Mapper
public interface KnowledgeReferenceVideoMapper {

    /**
     * 插入一条视频案例。
     * 故意不写入 quality_score / highlight_summary / embedding_*：前两者分别由 5.1c、5.1b 计算，后者用 DDL 默认值，
     * 这样导入链路完全不依赖向量库或打分逻辑，可在 RAG 关闭时独立跑通。
     */
    @Insert("""
            INSERT INTO creator_reference_video (
                video_id,
                bv_id,
                tier,
                category,
                title,
                description,
                tags,
                view_count,
                like_count,
                coin_count,
                favorite_count,
                danmaku_count,
                reply_count,
                highlight_summary,
                source,
                publish_time_text
            )
            VALUES (
                #{videoId},
                #{bvId},
                #{tier},
                #{category},
                #{title},
                #{description},
                #{tags},
                #{viewCount},
                #{likeCount},
                #{coinCount},
                #{favoriteCount},
                #{danmakuCount},
                #{replyCount},
                #{highlightSummary},
                #{source},
                #{publishTimeText}
            )
            """)
    int insertReferenceVideo(ReferenceVideoRecord record);

    /**
     * 逐条插入清洗后的优质评论 / 弹幕（5.1b）。
     * 故意不写 is_noise（DB 默认 0，本表只存非噪声条目）与 embedding_*（默认 PENDING，留给 5.2 子表向量化）。
     */
    @Insert("""
            INSERT INTO creator_reference_video_item (
                item_id,
                video_id,
                source_type,
                content,
                sentiment,
                like_count,
                reply_count,
                occur_time_text,
                reason
            )
            VALUES (
                #{itemId},
                #{videoId},
                #{sourceType},
                #{content},
                #{sentiment},
                #{likeCount},
                #{replyCount},
                #{occurTimeText},
                #{reason}
            )
            """)
    int insertReferenceVideoItem(ReferenceVideoItemRecord record);

    /**
     * 分页查询案例列表，支持按分区、层级可选过滤。
     * 按 id 倒序让最近导入的案例排在前面，便于导入后立即在列表确认结果。
     */
    @Select("""
            SELECT id,
                   video_id,
                   bv_id,
                   tier,
                   category,
                   title,
                   description,
                   tags,
                   view_count,
                   like_count,
                   coin_count,
                   favorite_count,
                   danmaku_count,
                   reply_count,
                   highlight_summary,
                   quality_score,
                   source,
                   publish_time_text,
                   embedding_status,
                   create_time,
                   update_time
            FROM creator_reference_video
            WHERE is_deleted = 0
              AND (#{category} IS NULL OR category = #{category})
              AND (#{tier} IS NULL OR tier = #{tier})
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    @Results(id = "ReferenceVideoRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "video_id", property = "videoId"),
            @Result(column = "bv_id", property = "bvId"),
            @Result(column = "tier", property = "tier"),
            @Result(column = "category", property = "category"),
            @Result(column = "title", property = "title"),
            @Result(column = "description", property = "description"),
            @Result(column = "tags", property = "tags"),
            @Result(column = "view_count", property = "viewCount"),
            @Result(column = "like_count", property = "likeCount"),
            @Result(column = "coin_count", property = "coinCount"),
            @Result(column = "favorite_count", property = "favoriteCount"),
            @Result(column = "danmaku_count", property = "danmakuCount"),
            @Result(column = "reply_count", property = "replyCount"),
            @Result(column = "highlight_summary", property = "highlightSummary"),
            @Result(column = "quality_score", property = "qualityScore"),
            @Result(column = "source", property = "source"),
            @Result(column = "publish_time_text", property = "publishTimeText"),
            @Result(column = "embedding_status", property = "embeddingStatus"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    List<ReferenceVideoRecord> listReferenceVideos(@Param("category") String category,
                                                   @Param("tier") String tier,
                                                   @Param("limit") int limit,
                                                   @Param("offset") int offset);

    /**
     * 统计满足同一过滤条件的案例总数，供分页器计算总页数。
     */
    @Select("""
            SELECT COUNT(*)
            FROM creator_reference_video
            WHERE is_deleted = 0
              AND (#{category} IS NULL OR category = #{category})
              AND (#{tier} IS NULL OR tier = #{tier})
            """)
    long countReferenceVideos(@Param("category") String category,
                              @Param("tier") String tier);

    /**
     * 按 video_id 批量回查父表案例（5.2a 检索回查事实源）。
     * 向量库只返回 videoId，必须用 is_deleted=0 回查父表拿真身：向量库里的旧批次/已删案例自然被过滤掉。
     * 顺序由调用方按向量相似度重排（MySQL IN 不保证顺序），故这里不加 ORDER BY。调用方需保证 videoIds 非空。
     * 复用 listReferenceVideos 的 ReferenceVideoRecordMap，故 SELECT 列须与之保持一致。
     */
    @Select("""
            <script>
            SELECT id, video_id, bv_id, tier, category, title, description, tags,
                   view_count, like_count, coin_count, favorite_count, danmaku_count, reply_count,
                   highlight_summary, quality_score, source, publish_time_text,
                   embedding_status, create_time, update_time
            FROM creator_reference_video
            WHERE is_deleted = 0
              AND video_id IN
              <foreach item='vid' collection='videoIds' open='(' separator=',' close=')'>
                  #{vid}
              </foreach>
            </script>
            """)
    @ResultMap("ReferenceVideoRecordMap")
    List<ReferenceVideoRecord> listByVideoIds(@Param("videoIds") List<String> videoIds);

    /**
     * SQL 关键词兜底检索父表（5.2a）：RAG 关闭或向量库不可用时走这里。
     * keyword 为空则不加关键词条件（退化为「按质量分取前 N」）；title/description/highlight_summary 任一命中即返回。
     * 排序 quality_score DESC：5.1c 的归一化质量分让兜底也优先高质量案例；MySQL 中 NULL 在 DESC 下排最后（未打分案例垫底）。
     * 刻意不做中文分词打分：父表是「案例卡片」粒度、量级小，整串 LIKE + 质量分排序已够；真正的关键词召回留给 5.2d 原生 BM25。
     * 复用 listReferenceVideos 的 ReferenceVideoRecordMap，故 SELECT 列须与之保持一致。
     */
    @Select("""
            SELECT id, video_id, bv_id, tier, category, title, description, tags,
                   view_count, like_count, coin_count, favorite_count, danmaku_count, reply_count,
                   highlight_summary, quality_score, source, publish_time_text,
                   embedding_status, create_time, update_time
            FROM creator_reference_video
            WHERE is_deleted = 0
              AND (#{category} IS NULL OR category = #{category})
              AND (#{tier} IS NULL OR tier = #{tier})
              AND (#{keyword} IS NULL
                   OR title LIKE CONCAT('%', #{keyword}, '%')
                   OR description LIKE CONCAT('%', #{keyword}, '%')
                   OR highlight_summary LIKE CONCAT('%', #{keyword}, '%'))
            ORDER BY quality_score DESC, id DESC
            LIMIT #{limit}
            """)
    @ResultMap("ReferenceVideoRecordMap")
    List<ReferenceVideoRecord> searchByKeyword(@Param("category") String category,
                                               @Param("tier") String tier,
                                               @Param("keyword") String keyword,
                                               @Param("limit") int limit);

    /**
     * 按 BV 号统计未删除的案例数，用于导入时按 BV 去重（>0 即已存在，跳过本次导入）。
     */
    @Select("""
            SELECT COUNT(*)
            FROM creator_reference_video
            WHERE bv_id = #{bvId}
              AND is_deleted = 0
            """)
    long countByBvId(@Param("bvId") String bvId);

    /**
     * 取出某分区下全部未删除视频的打分输入：父表 6 项热度 + 子表正/负向条数（5.1c 质量打分）。
     * 用一个 LEFT JOIN + 条件 SUM 一次查齐，避免「先查视频再逐条查子表」的 N+1；
     * 子表无记录时 SUM(CASE...) 自然得 0（非 null）。GROUP BY 主键 v.id：id 是主键，
     * 其余 v.* 列对它函数依赖，从而在 ONLY_FULL_GROUP_BY 下也能直接 SELECT。
     */
    @Select("""
            SELECT v.video_id       AS video_id,
                   v.view_count     AS view_count,
                   v.like_count     AS like_count,
                   v.coin_count     AS coin_count,
                   v.favorite_count AS favorite_count,
                   v.danmaku_count  AS danmaku_count,
                   v.reply_count    AS reply_count,
                   SUM(CASE WHEN i.sentiment = 'POSITIVE' THEN 1 ELSE 0 END) AS pos_count,
                   SUM(CASE WHEN i.sentiment = 'NEGATIVE' THEN 1 ELSE 0 END) AS neg_count
            FROM creator_reference_video v
            LEFT JOIN creator_reference_video_item i
                   ON i.video_id = v.video_id AND i.is_deleted = 0
            WHERE v.is_deleted = 0
              AND v.category = #{category}
            GROUP BY v.id
            """)
    @Results(id = "ReferenceVideoScoringRowMap", value = {
            @Result(column = "video_id", property = "videoId"),
            @Result(column = "view_count", property = "viewCount"),
            @Result(column = "like_count", property = "likeCount"),
            @Result(column = "coin_count", property = "coinCount"),
            @Result(column = "favorite_count", property = "favoriteCount"),
            @Result(column = "danmaku_count", property = "danmakuCount"),
            @Result(column = "reply_count", property = "replyCount"),
            @Result(column = "pos_count", property = "posCount"),
            @Result(column = "neg_count", property = "negCount")
    })
    List<ReferenceVideoScoringRow> listScoringRowsByCategory(@Param("category") String category);

    /**
     * 回写单个视频的归一化质量分（5.1c）。
     * qualityScore 允许为 null（view 缺失 / 分区样本不足时不打分），故显式声明 jdbcType=DECIMAL，
     * 避免标量 null 参数让 MyBatis 无法推断 JDBC 类型而报错。
     */
    @Update("""
            UPDATE creator_reference_video
            SET quality_score = #{qualityScore,jdbcType=DECIMAL}
            WHERE video_id = #{videoId}
              AND is_deleted = 0
            """)
    int updateQualityScore(@Param("videoId") String videoId,
                           @Param("qualityScore") BigDecimal qualityScore);

    /**
     * 查询待索引的案例（5.1c 向量索引）。
     * 只取尚未成功索引的（embedding_status PENDING / FAILED）：知识库是全局且持续增长的语料，
     * 全量重嵌入既费 Embedding 又无必要，故做成增量——已 INDEXED 的不再重复嵌入（相对反馈侧按 task 全量重建的有意差异）。
     * 复用 listReferenceVideos 的 ReferenceVideoRecordMap，故 SELECT 列须与之保持一致。
     */
    @Select("""
            SELECT id,
                   video_id,
                   bv_id,
                   tier,
                   category,
                   title,
                   description,
                   tags,
                   view_count,
                   like_count,
                   coin_count,
                   favorite_count,
                   danmaku_count,
                   reply_count,
                   highlight_summary,
                   quality_score,
                   source,
                   publish_time_text,
                   embedding_status,
                   create_time,
                   update_time
            FROM creator_reference_video
            WHERE is_deleted = 0
              AND embedding_status IN ('PENDING', 'FAILED')
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    @ResultMap("ReferenceVideoRecordMap")
    List<ReferenceVideoRecord> listIndexableVideos(@Param("limit") int limit);

    /**
     * 标记某案例已成功写入向量库：embedding_id 复用 video_id，让向量文档与父表案例一一对应、回查免映射。
     */
    @Update("""
            UPDATE creator_reference_video
            SET embedding_id = #{embeddingId},
                embedding_status = 'INDEXED',
                embedding_error = NULL,
                embedding_update_time = CURRENT_TIMESTAMP
            WHERE video_id = #{videoId}
              AND is_deleted = 0
            """)
    int updateVideoEmbeddingIndexed(@Param("videoId") String videoId,
                                    @Param("embeddingId") String embeddingId);

    /**
     * 标记某案例索引失败，保存截断后的失败原因摘要，便于排查 Embedding / Milvus 异常。
     */
    @Update("""
            UPDATE creator_reference_video
            SET embedding_status = 'FAILED',
                embedding_error = #{errorMessage},
                embedding_update_time = CURRENT_TIMESTAMP
            WHERE video_id = #{videoId}
              AND is_deleted = 0
            """)
    int updateVideoEmbeddingFailed(@Param("videoId") String videoId,
                                   @Param("errorMessage") String errorMessage);

    /**
     * 按向量索引状态分组计数，供 index/status 汇总各状态数量。
     */
    @Select("""
            SELECT embedding_status AS status,
                   COUNT(1) AS count
            FROM creator_reference_video
            WHERE is_deleted = 0
            GROUP BY embedding_status
            """)
    @Results(id = "ReferenceVideoEmbeddingStatusCountMap", value = {
            @Result(column = "status", property = "status"),
            @Result(column = "count", property = "count")
    })
    List<ReferenceVideoEmbeddingStatusCount> countEmbeddingStatus();

    /**
     * 最近一次成功索引时间（仅看 INDEXED），index/status 的 lastIndexedAt；无成功索引时返回 null。
     */
    @Select("""
            SELECT MAX(embedding_update_time)
            FROM creator_reference_video
            WHERE embedding_status = 'INDEXED'
              AND is_deleted = 0
            """)
    LocalDateTime findLastEmbeddingUpdateTime();

    // ============================ 子表向量索引（5.2c-1：子条目向量化） ============================

    /**
     * 查询待索引的优质子条目（5.2c 子表向量化）。
     * 只取尚未成功索引的（embedding_status PENDING / FAILED）做增量；JOIN 父表只索引「父表存活」的子条目，
     * 过滤掉孤儿 / 父已删的条目（父删了它的评论弹幕案例也就无意义）；并把父表 category/tier 反范式带出——
     * 写进子向量文档 metadata，供 5.2c-2 子召回复用与父检索同款的元数据过滤（子表本身没有这两列）。
     * 命中子表 idx_video_embedding_status；ORDER BY i.id 让批次稳定可复现。
     */
    @Select("""
            SELECT i.item_id     AS item_id,
                   i.video_id    AS video_id,
                   i.content     AS content,
                   i.sentiment   AS sentiment,
                   i.source_type AS source_type,
                   v.category    AS category,
                   v.tier        AS tier
            FROM creator_reference_video_item i
            JOIN creator_reference_video v
                 ON v.video_id = i.video_id AND v.is_deleted = 0
            WHERE i.is_deleted = 0
              AND i.embedding_status IN ('PENDING', 'FAILED')
            ORDER BY i.id
            LIMIT #{limit}
            """)
    @Results(id = "ReferenceVideoItemIndexRowMap", value = {
            @Result(column = "item_id", property = "itemId"),
            @Result(column = "video_id", property = "videoId"),
            @Result(column = "content", property = "content"),
            @Result(column = "sentiment", property = "sentiment"),
            @Result(column = "source_type", property = "sourceType"),
            @Result(column = "category", property = "category"),
            @Result(column = "tier", property = "tier")
    })
    List<ReferenceVideoItemIndexRow> listIndexableItems(@Param("limit") int limit);

    /**
     * 取全部未删除优质子条目（5.2d-3 子集合 hybrid 整库重灌）。
     * 与 {@link #listIndexableItems} 的差异：hybrid 是<b>整库重灌</b>、不看 embedding_status（那套状态属于 Spring AI 子集合），
     * 只按 is_deleted=0 + 父表存活取全量（受 limit 二次收敛）。JOIN 父表反范式带出 category/tier 供 hybrid 过滤。
     * 复用 listIndexableItems 的 ReferenceVideoItemIndexRowMap，故 SELECT 列须与之保持一致。
     */
    @Select("""
            SELECT i.item_id     AS item_id,
                   i.video_id    AS video_id,
                   i.content     AS content,
                   i.sentiment   AS sentiment,
                   i.source_type AS source_type,
                   v.category    AS category,
                   v.tier        AS tier
            FROM creator_reference_video_item i
            JOIN creator_reference_video v
                 ON v.video_id = i.video_id AND v.is_deleted = 0
            WHERE i.is_deleted = 0
            ORDER BY i.id
            LIMIT #{limit}
            """)
    @ResultMap("ReferenceVideoItemIndexRowMap")
    List<ReferenceVideoItemIndexRow> listAllItemsForHybrid(@Param("limit") int limit);

    /**
     * 统计可灌入子 hybrid 的子条目总数（与 {@link #listAllItemsForHybrid} 同源：未删子条目 + 父表存活），供子 hybrid status 展示。
     */
    @Select("""
            SELECT COUNT(*)
            FROM creator_reference_video_item i
            JOIN creator_reference_video v
                 ON v.video_id = i.video_id AND v.is_deleted = 0
            WHERE i.is_deleted = 0
            """)
    long countItemsForHybrid();

    /**
     * 标记某子条目已成功写入子向量库：embedding_id 复用 item_id，让子向量文档与子表条目一一对应、回查证据免映射。
     */
    @Update("""
            UPDATE creator_reference_video_item
            SET embedding_id = #{embeddingId},
                embedding_status = 'INDEXED',
                embedding_error = NULL,
                embedding_update_time = CURRENT_TIMESTAMP
            WHERE item_id = #{itemId}
              AND is_deleted = 0
            """)
    int updateItemEmbeddingIndexed(@Param("itemId") String itemId,
                                   @Param("embeddingId") String embeddingId);

    /**
     * 标记某子条目索引失败，保存截断后的失败原因摘要，便于排查 Embedding / Milvus 异常。
     */
    @Update("""
            UPDATE creator_reference_video_item
            SET embedding_status = 'FAILED',
                embedding_error = #{errorMessage},
                embedding_update_time = CURRENT_TIMESTAMP
            WHERE item_id = #{itemId}
              AND is_deleted = 0
            """)
    int updateItemEmbeddingFailed(@Param("itemId") String itemId,
                                  @Param("errorMessage") String errorMessage);

    /**
     * 按向量索引状态分组计数子条目，供子索引 status 汇总各状态数量。复用父侧同形的 ReferenceVideoEmbeddingStatusCountMap。
     */
    @Select("""
            SELECT embedding_status AS status,
                   COUNT(1) AS count
            FROM creator_reference_video_item
            WHERE is_deleted = 0
            GROUP BY embedding_status
            """)
    @ResultMap("ReferenceVideoEmbeddingStatusCountMap")
    List<ReferenceVideoEmbeddingStatusCount> countItemEmbeddingStatus();

    /**
     * 子条目最近一次成功索引时间（仅看 INDEXED），子索引 status 的 lastIndexedAt；无成功索引时返回 null。
     */
    @Select("""
            SELECT MAX(embedding_update_time)
            FROM creator_reference_video_item
            WHERE embedding_status = 'INDEXED'
              AND is_deleted = 0
            """)
    LocalDateTime findLastItemEmbeddingUpdateTime();

    /**
     * 按 item_id 批量回查子条目证据（5.2c-2 small-to-big 证据回显）。
     * 子向量库只给 itemId，子表才是真身；is_deleted=0 过滤掉索引后被软删的子条目（不再作为证据展示）。
     * 顺序由调用方按子召回相似度/最终卡片顺序重排（IN 不保证顺序），故不加 ORDER BY；调用方保证 itemIds 非空。
     * 只取证据展示所需 5 列，复用同形可借 ReferenceVideoItemRecord 承载（其余字段留空）。
     */
    @Select("""
            <script>
            SELECT item_id, video_id, content, sentiment, source_type
            FROM creator_reference_video_item
            WHERE is_deleted = 0
              AND item_id IN
              <foreach item='id' collection='itemIds' open='(' separator=',' close=')'>
                  #{id}
              </foreach>
            </script>
            """)
    @Results(id = "ReferenceVideoItemEvidenceMap", value = {
            @Result(column = "item_id", property = "itemId"),
            @Result(column = "video_id", property = "videoId"),
            @Result(column = "content", property = "content"),
            @Result(column = "sentiment", property = "sentiment"),
            @Result(column = "source_type", property = "sourceType")
    })
    List<ReferenceVideoItemRecord> listItemsByItemIds(@Param("itemIds") List<String> itemIds);
}
