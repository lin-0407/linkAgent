package com.link.linkagent.creator.bilibili.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * B站公开视频同步脚本返回的单条视频数据。
 *
 * @param bvid 视频 BV 号
 * @param aid 视频 AV 号
 * @param title 视频标题
 * @param coverUrl 视频封面地址
 * @param publishTimestamp 发布时间戳（秒）
 * @param viewCount 播放量
 * @param likeCount 点赞量
 * @param coinCount 投币量
 * @param favoriteCount 收藏量
 * @param shareCount 分享量
 * @param ownerUid 视频所属 B站 UID，用于绑定归属校验
 * @param rawSnapshot B站接口原始条目 JSON，用于后续排错
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BilibiliVideoSyncItem(
        String bvid,
        Long aid,
        String title,
        String coverUrl,
        Long publishTimestamp,
        Long viewCount,
        Long likeCount,
        Long coinCount,
        Long favoriteCount,
        Long shareCount,
        String ownerUid,
        String rawSnapshot
) {
}
