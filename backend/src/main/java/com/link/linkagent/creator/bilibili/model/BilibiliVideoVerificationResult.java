package com.link.linkagent.creator.bilibili.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 待绑定 BV 的公开信息校验结果。
 *
 * @param bvid 待校验 BV 号
 * @param status 校验状态：FOUND、UID_MISMATCH、VIDEO_NOT_FOUND、UNKNOWN
 * @param ownerUid B站接口返回的视频所属 UID，查询失败时为空
 * @param message 可展示给用户的校验说明
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BilibiliVideoVerificationResult(
        String bvid,
        String status,
        String ownerUid,
        String message
) {
}
