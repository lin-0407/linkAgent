package com.link.linkagent.knowledge.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 「输入 BV → 后端调脚本采集 → 自动清洗导入」一键接口的请求体。
 * <p>
 * 单 BV 是项目约束里明确允许的「用户显式触发限量采集」，所以走前端→后端→脚本这条显式链路；
 * 榜单批量仍只能在离线脚本里跑（不进后端）。tier / category 可选，留空时由脚本与导入服务按 manual_bv 兜底。
 */
public record ReferenceVideoFetchImportRequest(

        @NotBlank(message = "BV 号不能为空")
        @Size(max = 255, message = "BV 号 / 链接长度不能超过255个字符")
        String bvInput,

        // 可选：案例层级（BENCHMARK/COMPETITOR/OWN_HISTORY）。非法值会在后续导入校验时被拒，这里只限长度。
        @Size(max = 16, message = "案例层级长度不能超过16个字符")
        String tier,

        // 可选：分区标注，留空则用视频自身分区名。
        @Size(max = 64, message = "分区长度不能超过64个字符")
        String category
) {
}
