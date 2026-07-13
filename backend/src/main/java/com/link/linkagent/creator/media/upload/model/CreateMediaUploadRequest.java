package com.link.linkagent.creator.media.upload.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 创建成片分片上传会话请求。
 * <p>
 * 所有字段均来自浏览器（客户端），因此必须做严格校验。
 * P0 只允许 video/mp4 格式，通过 @Pattern 正则强制校验。
 * fileSize 上限与 @Max 注解值保持一致，同时 Service 层有二次校验（使用配置值）。
 *
 * @param versionName  成片版本名称（用户自定义，如"初剪版"、"最终版"）
 * @param fileName     视频原文件名（仅用于展示，不参与对象路径生成）
 * @param fileSize     文件字节数（客户端声明，完成后以 HeadObject 结果为准）
 * @param contentType  媒体类型（P0 固定为 video/mp4）
 * @param lastModified 文件最后修改时间毫秒戳（参与文件指纹计算）
 */
public record CreateMediaUploadRequest(
        @NotBlank(message = "成片版本名称不能为空")
        @Size(max = 128, message = "成片版本名称长度不能超过128个字符")
        String versionName,

        @NotBlank(message = "视频文件名不能为空")
        @Size(max = 255, message = "视频文件名长度不能超过255个字符")
        String fileName,

        @Min(value = 1, message = "视频文件大小必须大于0")
        @Max(value = 1_500_000_000L, message = "视频文件不能超过1.5GB") // 与默认 maxFileBytes 一致
        long fileSize,

        @NotBlank(message = "视频媒体类型不能为空")
        @Pattern(regexp = "(?i)^video/mp4$", message = "P0 只支持 video/mp4") // 大小写不敏感
        String contentType,

        @Positive(message = "文件最后修改时间必须大于0")
        Long lastModified // 使用包装类型，允许为 null（某些环境可能不提供）
) {
}
