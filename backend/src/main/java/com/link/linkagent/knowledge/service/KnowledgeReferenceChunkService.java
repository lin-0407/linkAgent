package com.link.linkagent.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.knowledge.model.ReferenceVideoChunkRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoItemRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoRecord;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 视频案例主题中块生成服务。
 * <p>
 * 中块不是让模型再次“看懂视频”，而是把已入库的确定性材料整理成创作者会提问的主题：
 * 标题包装、内容定位、观众反馈。这样做能提升召回粒度，又不引入额外 LLM 编造风险。
 */
@Service
public class KnowledgeReferenceChunkService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReferenceChunkService.class);

    /** 单个主题中块文本上限，控制 Embedding 成本，也避免一个中块退化成父卡片大文档。 */
    private static final int CHUNK_CONTENT_MAX_CHARS = 1800;

    /** 每个反馈中块最多吸收的原始条目数，避免爆款视频的评论弹幕把中块撑得过长。 */
    private static final int MAX_FEEDBACK_ITEMS_IN_CHUNK = 12;

    private static final String TYPE_TITLE_PACKAGE = "TITLE_PACKAGE";
    private static final String TYPE_CONTENT_POSITIONING = "CONTENT_POSITIONING";
    private static final String TYPE_AUDIENCE_FEEDBACK_SUMMARY = "AUDIENCE_FEEDBACK_SUMMARY";

    private final ObjectMapper objectMapper;

    public KnowledgeReferenceChunkService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 基于父视频和清洗后的子条目生成主题中块。
     * 返回值允许为空：如果某个视频只有极少字段，宁可不造空块，也不要给检索塞无意义文本。
     */
    public List<ReferenceVideoChunkRecord> buildChunks(ReferenceVideoRecord video,
                                                       List<ReferenceVideoItemRecord> items) {
        List<ReferenceVideoChunkRecord> chunks = new ArrayList<>();
        addIfPresent(chunks, buildTitlePackageChunk(video));
        addIfPresent(chunks, buildContentPositioningChunk(video));
        addIfPresent(chunks, buildAudienceFeedbackChunk(video, items));
        return chunks;
    }

    private ReferenceVideoChunkRecord buildTitlePackageChunk(ReferenceVideoRecord video) {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "标题", video.getTitle());
        appendLine(builder, "标签", video.getTags());
        appendLine(builder, "分区", video.getCategory());
        appendLine(builder, "层级", tierLabel(video.getTier()));
        appendLine(builder, "简介摘录", TextUtil.preview(video.getDescription(), 500, ""));
        return toChunk(video.getVideoId(), TYPE_TITLE_PACKAGE, "标题包装与点击承诺", builder.toString(), null);
    }

    private ReferenceVideoChunkRecord buildContentPositioningChunk(ReferenceVideoRecord video) {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "内容定位", video.getDescription());
        appendLine(builder, "亮点摘要", video.getHighlightSummary());
        appendLine(builder, "分区", video.getCategory());
        appendLine(builder, "标签", video.getTags());
        appendLine(builder, "数据表现", buildStatsText(video));
        return toChunk(video.getVideoId(), TYPE_CONTENT_POSITIONING, "内容定位与差异化", builder.toString(), null);
    }

    private ReferenceVideoChunkRecord buildAudienceFeedbackChunk(ReferenceVideoRecord video,
                                                                List<ReferenceVideoItemRecord> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "亮点摘要", video.getHighlightSummary());
        List<String> sourceItemIds = new ArrayList<>();
        int count = 0;
        for (ReferenceVideoItemRecord item : items) {
            if (count >= MAX_FEEDBACK_ITEMS_IN_CHUNK) {
                break;
            }
            String content = TextUtil.trimToNull(item.getContent());
            if (content == null) {
                continue;
            }
            sourceItemIds.add(item.getItemId());
            String label = "POSITIVE".equals(item.getSentiment()) ? "正向反馈" : "负向反馈";
            appendLine(builder, label, TextUtil.preview(content, 180, ""));
            count++;
        }
        return toChunk(video.getVideoId(), TYPE_AUDIENCE_FEEDBACK_SUMMARY, "观众反馈主题", builder.toString(), sourceItemIds);
    }

    private ReferenceVideoChunkRecord toChunk(String videoId,
                                             String chunkType,
                                             String chunkTitle,
                                             String content,
                                             List<String> sourceItemIds) {
        String normalized = TextUtil.trimToNull(content);
        if (normalized == null) {
            return null;
        }
        ReferenceVideoChunkRecord record = new ReferenceVideoChunkRecord();
        record.setChunkId(UUID.randomUUID().toString());
        record.setVideoId(videoId);
        record.setChunkType(chunkType);
        record.setChunkTitle(chunkTitle);
        record.setChunkContent(TextUtil.abbreviateWithSuffix(normalized, CHUNK_CONTENT_MAX_CHARS, "..."));
        record.setSourceItemIds(toJson(sourceItemIds));
        return record;
    }

    private String toJson(List<String> sourceItemIds) {
        if (sourceItemIds == null || sourceItemIds.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(sourceItemIds);
        } catch (JsonProcessingException exception) {
            // source_item_ids 只是解释性追踪字段，序列化失败不应影响核心导入链路。
            log.warn("主题中块来源条目序列化失败，source_item_ids 置空。", exception);
            return null;
        }
    }

    private void addIfPresent(List<ReferenceVideoChunkRecord> chunks, ReferenceVideoChunkRecord chunk) {
        if (chunk != null) {
            chunks.add(chunk);
        }
    }

    private void appendLine(StringBuilder builder, String label, String value) {
        String text = TextUtil.trimToNull(value);
        if (text != null) {
            builder.append(label).append("：").append(text).append('\n');
        }
    }

    private String buildStatsText(ReferenceVideoRecord video) {
        List<String> parts = new ArrayList<>();
        if (video.getViewCount() != null) {
            parts.add("播放 " + video.getViewCount());
        }
        if (video.getLikeCount() != null) {
            parts.add("点赞 " + video.getLikeCount());
        }
        if (video.getCoinCount() != null) {
            parts.add("投币 " + video.getCoinCount());
        }
        if (video.getFavoriteCount() != null) {
            parts.add("收藏 " + video.getFavoriteCount());
        }
        if (video.getDanmakuCount() != null) {
            parts.add("弹幕 " + video.getDanmakuCount());
        }
        if (video.getReplyCount() != null) {
            parts.add("评论 " + video.getReplyCount());
        }
        return String.join("，", parts);
    }

    private String tierLabel(String tier) {
        if (tier == null) {
            return "未知";
        }
        return switch (tier) {
            case "BENCHMARK" -> "优品标杆";
            case "COMPETITOR" -> "竞品";
            case "OWN_HISTORY" -> "自己历史";
            default -> tier;
        };
    }
}
