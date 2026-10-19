package com.link.linkagent.creator.bilibili.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.bilibili.mapper.CreatorBilibiliMapper;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoRecord;
import com.link.linkagent.creator.bilibili.model.TaskVideoBindingRecord;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.util.TextUtil;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 为发布后分析读取已核验 BV 的公开资料快照。
 * 只读取现有缓存，不发网络请求、不读取发布前草稿，也不把整份原始接口响应交给模型。
 * 竞品和总体复盘共用同一读法，入口与保存前重读都能发现资料刷新。
 */
@Service
public class CreatorPublishedVideoContextService {

    private static final int TEXT_LIMIT = 4000;
    private final CreatorBilibiliMapper bilibiliMapper;
    private final ObjectMapper objectMapper;

    public CreatorPublishedVideoContextService(CreatorBilibiliMapper bilibiliMapper, ObjectMapper objectMapper) {
        this.bilibiliMapper = bilibiliMapper;
        this.objectMapper = objectMapper;
    }

    public String buildPrompt(CreatorTaskRecord task) {
        TaskVideoBindingRecord binding = bilibiliMapper.findBindingByTaskId(task.getTaskId()).orElse(null);
        if (binding == null || (!"BOUND".equals(binding.getBindingStatus())
                    && !(TextUtil.hasText(binding.getPublishedVersionId()) && TextUtil.hasText(binding.getPublishedSnapshotJson())))
                || !Objects.equals(task.getTaskId(), binding.getTaskId())
                || !Objects.equals(task.getUserId(), binding.getUserId())
                || TextUtil.isBlank(binding.getBvid()) || TextUtil.isBlank(binding.getBilibiliUid())) {
            return "未提供（当前任务没有归属核验通过的 BV，不能将草稿或管理名称作为正式发布资料）";
        }
        BilibiliVideoRecord video = bilibiliMapper.findVideoByBvidAndUid(binding.getBvid(), binding.getBilibiliUid())
                .orElse(null);
        boolean versioned = TextUtil.hasText(binding.getPublishedVersionId());
        JsonNode frozen = versioned ? readSnapshot(binding.getPublishedSnapshotJson()) : null;
        if (versioned && (frozen == null
                || !binding.getBvid().equals(frozen.path("bvid").asText())
                || !binding.getBilibiliUid().equals(frozen.path("uid").asText())
                || !binding.getPublishedVersionId().equals(frozen.path("versionId").asText()))) {
            return "未提供（已记录发布版本，但正式发布资料快照缺失或不匹配；不能用当前缓存或草稿反推历史文案）";
        }
        if (video != null && (!"SYNCED".equals(video.getSyncStatus())
                || !Objects.equals(binding.getBvid(), video.getBvid())
                || !Objects.equals(binding.getBilibiliUid(), video.getBilibiliUid()))) video = null;
        if (video == null && frozen == null) {
            return "未提供（已核验 BV 的匹配公开资料缓存缺失，需重新同步）";
        }
        JsonNode live = video == null ? null : readSnapshot(video.getRawSnapshot());
        if (live != null && (conflicts(live.get("bvid"), binding.getBvid())
                || conflicts(live.path("owner").get("mid"), binding.getBilibiliUid())
                || conflicts(live.get("mid"), binding.getBilibiliUid()))) {
            if (frozen == null) return "未提供（公开资料原始快照与已核验 BV 或 UID 不一致，需重新同步）";
            // 冻结文案仍可信，但冲突缓存的实时指标不能混入同一视频的复盘。
            video = null;
            live = null;
        }
        JsonNode snapshot = frozen == null ? live : frozen.path("raw");
        // 旧缓存的补充字段可能不完整；明确的归属/BV冲突则整份缓存都不能当作当前视频证据。
        if (snapshot != null && (conflicts(snapshot.get("bvid"), binding.getBvid())
                || conflicts(snapshot.path("owner").get("mid"), binding.getBilibiliUid())
                || conflicts(snapshot.get("mid"), binding.getBilibiliUid()))) {
            return "未提供（公开资料原始快照与已核验 BV 或 UID 不一致，需重新同步）";
        }
        StringBuilder result = new StringBuilder(frozen == null
                ? "来源：B站公开视频缓存；当前任务 BV 归属核验通过。\n"
                : "来源：归属核验通过时保存的不可变发布资料；以下文案与后续实时指标分开读取。\n");
        append(result, "BV", binding.getBvid());
        append(result, "所属 UID", binding.getBilibiliUid());
        if (frozen != null) append(result, "当前归属校验状态", binding.getBindingStatus());
        if (versioned) append(result, "实际发布版本", binding.getPublishedVersionId());
        append(result, "正式标题", frozen == null ? text(video.getTitle()) : textField(frozen, "title", null));
        append(result, "正式简介", textField(snapshot, "desc", "description"));
        append(result, "分区名称", textField(snapshot, "tname", null));
        append(result, "分区 ID", integerField(snapshot, "tid", "typeid"));
        // 实测详情同时返回新版分区ID，名称可能仍为空；保留两套原值，不自行查表补名。
        append(result, "新版分区名称", textField(snapshot, "tname_v2", null));
        append(result, "新版分区 ID", integerField(snapshot, "tid_v2", null));
        append(result, "标签", tags(snapshot));
        append(result, "发布时间（东八区）", frozen == null ? time(video.getPublishTime()) : textField(frozen, "publishTime", null));
        if (frozen != null) append(result, "发布资料保存时间（东八区）", textField(frozen, "capturedAt", null));
        append(result, "公开资料及指标同步时间（东八区）", video == null ? "未提供" : time(video.getLastSyncTime()));
        append(result, "播放量", metric(video == null ? null : video.getViewCount()));
        append(result, "点赞数", metric(video == null ? null : video.getLikeCount()));
        append(result, "投币数", metric(video == null ? null : video.getCoinCount()));
        append(result, "收藏数", metric(video == null ? null : video.getFavoriteCount()));
        append(result, "分享数", metric(video == null ? null : video.getShareCount()));
        JsonNode stats = live == null ? null : live.get("stat");
        append(result, "平台评论总量", integerField(stats, "reply", null));
        append(result, "平台弹幕总量", integerField(stats, "danmaku", null));
        result.append("以上是公开接口的历史快照，未读取已发布视频本体；标题、简介不能证明实际画面、节奏或创作者意图。\n")
                .append("未提供表示接口或缓存缺失；接口返回空文本/空列表与未提供不同；零是明确计数。\n")
                .append("平台评论/弹幕总量不等于本次采集到的正文样本数。反馈导入指标另列，时间或来源不一致时不得拼成同一时点。\n");
        if (snapshot == null) result.append("原始补充资料缺失或不可解析，简介、分区、标签和平台评论/弹幕总量不得补造。\n");
        return result.toString();
    }

    private JsonNode readSnapshot(String value) {
        if (TextUtil.isBlank(value)) return null;
        try {
            JsonNode node = objectMapper.readTree(value);
            return node != null && node.isObject() ? node : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            // 老缓存损坏时仍保留核验后的结构化标题和指标，不让可选补充资料阻塞复盘。
            return null;
        }
    }

    private boolean conflicts(JsonNode value, String expected) {
        return value != null && !value.isNull() && value.isValueNode()
                && !value.asText().isBlank() && !expected.equals(value.asText());
    }

    private JsonNode field(JsonNode node, String key, String alternate) {
        if (node == null || !node.isObject()) return null;
        JsonNode value = node.get(key);
        return (value == null || value.isNull()) && alternate != null ? node.get(alternate) : value;
    }

    private String textField(JsonNode node, String key, String alternate) {
        JsonNode value = field(node, key, alternate);
        return value != null && value.isTextual() ? text(value.textValue()) : "未提供";
    }

    private String integerField(JsonNode node, String key, String alternate) {
        JsonNode value = field(node, key, alternate);
        return value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0
                ? value.asText() : "未提供";
    }

    private String tags(JsonNode node) {
        JsonNode values = field(node, "tags", "tag");
        if (values == null || values.isNull()) return "未提供";
        if (values.isTextual()) return text(values.textValue());
        if (!values.isArray()) return "未提供";
        if (values.isEmpty()) return "接口返回空列表";
        List<String> names = new ArrayList<>();
        for (JsonNode value : values) {
            JsonNode name = value.isTextual() ? value : value.get("tag_name");
            if (name != null && name.isTextual()) names.add(name.textValue());
        }
        return names.isEmpty() ? "未提供" : text(String.join("、", names));
    }

    private String text(String value) {
        if (value == null) return "未提供";
        if (value.isBlank()) return "接口返回空文本";
        return value.length() <= TEXT_LIMIT ? value : value.substring(0, TEXT_LIMIT) + "（原文过长，后续已截断）";
    }

    private String metric(Long value) { return value == null || value < 0 ? "未提供" : value.toString(); }
    private String time(LocalDateTime value) { return value == null ? "未提供" : value.toString(); }
    private void append(StringBuilder builder, String label, String value) {
        builder.append(label).append("：").append(value).append('\n');
    }
}
