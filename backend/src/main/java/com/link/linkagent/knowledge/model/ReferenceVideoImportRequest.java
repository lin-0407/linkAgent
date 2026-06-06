package com.link.linkagent.knowledge.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 案例库导入请求，对应离线采集脚本 / seed 产出的 JSON 契约（见阶段 5.1 设计 §2.2）。
 * <p>
 * 父表字段（标题/简介/标签/热度）直接落库；评论 / 弹幕（comments / danmaku）从 5.1b 起在导入时清洗——
 * 用规则判定情绪与噪声，只保留「非噪声且正 / 负向」的优质条目写入子表，再汇总成案例亮点摘要。
 */
public record ReferenceVideoImportRequest(

        @NotBlank(message = "数据来源 source 不能为空")
        @Size(max = 64, message = "数据来源长度不能超过64个字符")
        String source,

        // 可选：当 source=manual_bv 时显式指定层级（如 COMPETITOR）。
        // 放在请求级而非每条视频上，是因为一次导入通常同源同层级，逐条指定徒增复杂度；为空时由 source 推导。
        @Size(max = 16, message = "案例层级长度不能超过16个字符")
        String tier,

        // 可选：整批默认分区。单条 video 未显式给 category 时回退到这里，便于榜单 JSON 在顶层统一标注分区。
        @Size(max = 64, message = "默认分区长度不能超过64个字符")
        String category,

        @NotEmpty(message = "导入的视频列表不能为空")
        @Valid
        List<VideoItem> videos
) {

    /**
     * 单条视频案例，对应父表 creator_reference_video 的一行，外加待清洗的评论 / 弹幕原始全量。
     */
    public record VideoItem(

            @Size(max = 20, message = "BV号长度不能超过20个字符")
            String bvId,

            @NotBlank(message = "视频标题不能为空")
            @Size(max = 255, message = "视频标题长度不能超过255个字符")
            String title,

            @Size(max = 20000, message = "视频简介长度不能超过20000个字符")
            String description,

            // 标签数组，落库时由服务序列化成 JSON 字符串存入 tags 列。
            List<String> tags,

            @Size(max = 64, message = "分区长度不能超过64个字符")
            String category,

            @Size(max = 64, message = "发布时间文本长度不能超过64个字符")
            String publishTimeText,

            @Valid
            VideoStats stats,

            // 评论 / 弹幕原始全量，不做长度校验：它们是机器采集的批量数据，过度校验反而让整批导入因个别脏数据失败。
            // 真正的筛选交给导入时的清洗逻辑（空内容、噪声、重复、中性都会被丢弃）。
            List<Comment> comments,

            List<Danmaku> danmaku
    ) {
    }

    /**
     * 视频热度指标，全部可空：离线脚本未取到时为空，质量打分（5.1c）会对缺失值做兜底，不在导入期强制要求。
     */
    public record VideoStats(
            Long view,
            Long like,
            Long coin,
            Long favorite,
            Long danmaku,
            Long reply
    ) {
    }

    /**
     * 一条评论原文及其互动量，来自离线脚本产出的原始全量。
     */
    public record Comment(
            String content,
            Long like,
            Integer reply
    ) {
    }

    /**
     * 一条弹幕原文及其出现时间文本。
     */
    public record Danmaku(
            String content,
            String timeText
    ) {
    }
}
