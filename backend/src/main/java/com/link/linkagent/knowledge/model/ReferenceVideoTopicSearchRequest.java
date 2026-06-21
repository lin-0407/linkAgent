package com.link.linkagent.knowledge.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 主题优先案例检索请求。
 * <p>
 * 先用用户问题命中主题中块，再把命中的视频按质量信号分页展示，避免父块检索把“整体相似”误当成“主题相关”。
 */
public record ReferenceVideoTopicSearchRequest(

        @NotBlank(message = "检索查询不能为空")
        @Size(max = 500, message = "检索查询长度不能超过500个字符")
        String query,

        @Size(max = 64, message = "分区过滤长度不能超过64个字符")
        String category,

        @Size(max = 16, message = "层级过滤长度不能超过16个字符")
        String tier,

        // page 表示第几轮展示：1=top1-5，2=top6-10，最多 4 轮覆盖 top20。
        @Min(value = 1, message = "页码最小为1")
        @Max(value = 4, message = "最多刷新4轮")
        Integer page,

        // 每轮默认 5 张卡片，上限保持 5，避免刷新机制一次吐太多案例。
        @Min(value = 1, message = "每轮数量最小为1")
        @Max(value = 5, message = "每轮数量最大为5")
        Integer size,

        @Size(max = 16, message = "查询增强策略长度不能超过16个字符")
        String strategy
) {
}
