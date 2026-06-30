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
 * 视频案例主题中块生成服务 — Pipeline 中「三层分块」的中间层制造者。
 * <p>
 * <b>Pipeline 角色</b>：
 * 位于「清洗（{@link KnowledgeReferenceCleaningService}）→ 导入落库（{@link KnowledgeReferenceVideoService}）」内部，
 * 在父视频和子条目都落库后，将已入库的确定性材料重新组织成「中块（chunk）」。
 * 中块服务于后续「中块向量索引（{@link KnowledgeReferenceChunkIndexService}）」，构成三层检索结构：
 * <ol>
 *   <li>父卡片（video）——全局上下文、热身检索</li>
 *   <li><b>主题中块（chunk）——标题包装 / 内容定位 / 反馈主题，检索的主战场</b></li>
 *   <li>原始证据（item）——评论弹幕原文，精准证据召回</li>
 * </ol>
 * <p>
 * <b>核心设计决策：中块 = 重组，不是新生成</b>
 * 中块不是让模型再次”看懂视频”，而是把已入库的确定性材料整理成创作者会提问的主题：
 * 标题包装、内容定位、观众反馈。这样做能提升召回粒度，又不引入额外 LLM 编造风险。
 * 每个视频生成 0~3 个中块（三缺一可空），宁缺毋滥——空字段不产出空块，避免给检索塞无意义文本。
 */
@Service
public class KnowledgeReferenceChunkService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReferenceChunkService.class);

    /**
     * 单个主题中块文本上限。
     * 控制 Embedding 成本：1800 字对 Qwen text-embedding-v4 约 2.2K tokens，单块成本约 0.0003 元。
     * 同时避免一个中块退化成父卡片大文档，保证中块检索有明确的主题粒度（标题/内容/反馈是三个独立聚类中心）。
     */
    private static final int CHUNK_CONTENT_MAX_CHARS = 1800;

    /**
     * 每个反馈中块最多吸收的原始条目数。
     * 爆款视频可能有数百条评论，全塞进一个中块会让文本跨度过大（语义漂移），
     * 且 Embedding 向量无法精确表征「哪条评论相关」。12 条恰好覆盖一个主题面，不贪多。
     */
    private static final int MAX_FEEDBACK_ITEMS_IN_CHUNK = 12;

    /** 中块类型：标题包装与点击承诺 */
    private static final String TYPE_TITLE_PACKAGE = "TITLE_PACKAGE";
    /** 中块类型：内容定位与差异化 */
    private static final String TYPE_CONTENT_POSITIONING = "CONTENT_POSITIONING";
    /** 中块类型：观众反馈主题汇总 */
    private static final String TYPE_AUDIENCE_FEEDBACK_SUMMARY = "AUDIENCE_FEEDBACK_SUMMARY";

    private final ObjectMapper objectMapper;

    public KnowledgeReferenceChunkService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 基于父视频和清洗后的子条目生成主题中块（0~3 个）。
     * <p>
     * <b>算法思路</b>：三个独立 builder 各自评估是否有足够信息产出中块。
     * 标题包装中块依赖标题/标签/分区/简介摘录；内容定位中块依赖完整简介/亮点摘要/数据表现；
     * 观众反馈中块依赖清洗后的优质子条目（正/负向评论弹幕）。任一 builder 产出的文本全空时返回 null，
     * addIfPresent 会安全跳过——这样做是为了让「字段极少的视频」不出空块，保证检索结果质量。
     *
     * @param video 已入库的父视频记录，携带标题/标签/分区/简介/数据等字段
     * @param items 清洗后的优质子条目（评论/弹幕），用于构造观众反馈主题中块
     * @return 0~3 个主题中块记录，不会返回 null 但可能为空列表
     */
    public List<ReferenceVideoChunkRecord> buildChunks(ReferenceVideoRecord video,
                                                       List<ReferenceVideoItemRecord> items) {
        List<ReferenceVideoChunkRecord> chunks = new ArrayList<>();
        addIfPresent(chunks, buildTitlePackageChunk(video));
        addIfPresent(chunks, buildContentPositioningChunk(video));
        addIfPresent(chunks, buildAudienceFeedbackChunk(video, items));
        return chunks;
    }

    /**
     * 构造「标题包装与点击承诺」中块。
     * 只取标题/标签/分区/层级/简介前500字——创作者问「这个选题的标题怎么包装」「什么标签点击率高」时，
     * 向量检索能直接命中这个主题中块，而不是在父卡片的完整简介（可能几千字）里大海捞针。
     */
    private ReferenceVideoChunkRecord buildTitlePackageChunk(ReferenceVideoRecord video) {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "标题", video.getTitle());
        appendLine(builder, "标签", video.getTags());
        appendLine(builder, "分区", video.getCategory());
        appendLine(builder, "层级", tierLabel(video.getTier()));
        appendLine(builder, "简介摘录", TextUtil.preview(video.getDescription(), 500, ""));
        return toChunk(video.getVideoId(), TYPE_TITLE_PACKAGE, "标题包装与点击承诺", builder.toString(), null);
    }

    /**
     * 构造「内容定位与差异化」中块。
     * 取完整简介/亮点摘要/分区/标签/热度数据，回答创作者问「这个赛道有什么差异化切入点」「同分区高播放案例怎么做的」。
     * 亮点摘要是 LLM 在清洗阶段生成的，放在这里是确定性材料（不需再次调用 LLM）。
     */
    private ReferenceVideoChunkRecord buildContentPositioningChunk(ReferenceVideoRecord video) {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "内容定位", video.getDescription());
        appendLine(builder, "亮点摘要", video.getHighlightSummary());
        appendLine(builder, "分区", video.getCategory());
        appendLine(builder, "标签", video.getTags());
        appendLine(builder, "数据表现", buildStatsText(video));
        return toChunk(video.getVideoId(), TYPE_CONTENT_POSITIONING, "内容定位与差异化", builder.toString(), null);
    }

    /**
     * 构造「观众反馈主题」中块。
     * <p>
     * <b>算法设计</b>：
     * 父亮点摘要作为锚点开头，然后按序吸收最多 12 条优质子条目（正/负向各不超过整体 12 条上限），
     * 每条只取前 180 字的预览——这样做既让中块有足够的反馈视角，又防止爆款视频的几百条评论把中块稀释成无意义堆砌。
     * 同时记录 sourceItemIds，让后续排查时可溯源到具体子条目。
     * <p>
     * 无子条目时直接返回 null（不造空气块），这在「手动导入无评论的种子案例」场景常见。
     */
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

    /**
     * 将构造好的文本转成中块记录对象。
     * content 全空时返回 null——调用方 addIfPresent 会安全跳过，保证不出空气块。
     * chunkContent 会截断到 {@link #CHUNK_CONTENT_MAX_CHARS}，防止超长文本撑爆 Embedding 输入。
     *
     * @param videoId       所属视频 ID
     * @param chunkType     中块类型（TITLE_PACKAGE / CONTENT_POSITIONING / AUDIENCE_FEEDBACK_SUMMARY）
     * @param chunkTitle    中块标题（前端展示用）
     * @param content       中块正文（可能很长，会被截断）
     * @param sourceItemIds 来源子条目 ID 列表（观众反馈中块才非空，用于溯源）
     * @return 中块记录，内容全空时返回 null
     */
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

    /**
     * 将 sourceItemIds 序列化为 JSON 字符串存入数据库。
     * 这是一个解释性追踪字段（非核心检索键），序列化失败只记日志、置空，绝不能中断导入链路。
     */
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

    /**
     * 安全追加非空中块：null 时跳过不添加，保证列表里没有空记录。
     * 这让三个 builder 可以放心返回 null 而不需调用方做判空。
     */
    private void addIfPresent(List<ReferenceVideoChunkRecord> chunks, ReferenceVideoChunkRecord chunk) {
        if (chunk != null) {
            chunks.add(chunk);
        }
    }

    /**
     * 按「标签：值」格式追加一行文本，value 空（trim 后 null）时跳过。
     * 这保证了中块正文不会出现「标题：」后接空白的断行。
     */
    private void appendLine(StringBuilder builder, String label, String value) {
        String text = TextUtil.trimToNull(value);
        if (text != null) {
            builder.append(label).append("：").append(text).append('\n');
        }
    }

    /**
     * 拼接热度指标文本：播放/点赞/投币/收藏/弹幕/评论，用中文逗号分隔。
     * 各指标可独立缺失（null 时跳过），适应不同脚本采集覆盖度。
     */
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

    /**
     * 将层级枚举转中文标签。BENCHMARK → 「优品榜样」、COMPETITOR → 「竞品」、OWN_HISTORY → 「自己历史」。
     * 这些中文词会出现在向量文档里，帮助语义检索匹配「给我看竞品案例」「我自己历史数据」这样的问法。
     */
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
