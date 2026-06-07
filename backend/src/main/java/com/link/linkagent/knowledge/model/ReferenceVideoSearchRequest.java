package com.link.linkagent.knowledge.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 案例库检索请求（阶段 5.2a）。
 * <p>
 * query 必填：检索必须有查询词，空查询直接 400，避免误把「列表」当「检索」用。
 * category / tier 为可选过滤（tier 在 Service 内大写归一并校验白名单）；topK 可空，空时回落 knowledge.rag.top-k（默认 8）。
 * 用 record 承载入参，配合控制器 @Valid 完成 Jakarta 校验，非法入参在进入 Service 前即被拦下。
 */
public record ReferenceVideoSearchRequest(

        @NotBlank(message = "检索查询不能为空")
        @Size(max = 500, message = "检索查询长度不能超过500个字符")
        String query,

        @Size(max = 64, message = "分区过滤长度不能超过64个字符")
        String category,

        @Size(max = 16, message = "层级过滤长度不能超过16个字符")
        String tier,

        // 接口层 [1,50] 兜底；Service 再按配置默认值与硬上限二次兜底，防止单次检索候选过多。
        @Min(value = 1, message = "topK 最小为1")
        @Max(value = 50, message = "topK 最大为50")
        Integer topK,

        // 查询增强策略（5.2b，可选）：NONE/REWRITE/HYDE/MULTI_QUERY；为空用配置默认（默认 REWRITE）。
        // 仅做长度校验，合法值在 Service 内大写归一 + 枚举校验（非法 400），与 tier 同口径。
        @Size(max = 16, message = "查询增强策略长度不能超过16个字符")
        String strategy
) {
}
