package com.link.linkagent.knowledge.model;

import java.util.List;

/**
 * 案例库分页列表响应。
 * 返回当前页数据加总数，便于前端做分页器；total 用单独 count 查询得到，与数据查询共用同一组过滤条件。
 */
public record ReferenceVideoPageResponse(
        List<ReferenceVideoResponse> items,
        long total,
        int page,
        int size
) {
}
