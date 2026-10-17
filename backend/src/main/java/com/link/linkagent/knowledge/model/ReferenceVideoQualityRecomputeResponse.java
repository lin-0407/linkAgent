package com.link.linkagent.knowledge.model;

import java.time.LocalDateTime;

/**
 * 分区互动分重算结果，沿用接口字段以兼容已有刷新入口。
 * 这个接口主要服务表结构或公式口径调整后的历史数据刷新，不绑定某个导入批次。
 */
public record ReferenceVideoQualityRecomputeResponse(
        int categoryCount,
        LocalDateTime createTime
) {
}
