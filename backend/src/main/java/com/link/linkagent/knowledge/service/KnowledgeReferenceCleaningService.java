package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.model.ReferenceVideoImportRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoItemRecord;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 视频案例评论 / 弹幕清洗服务 — Pipeline 中「原始反馈 → 优质子条目 + 亮点摘要」的过滤器。
 * <p>
 * <b>Pipeline 角色</b>：
 * 位于「数据采集（脚本 / API）→ 导入落库（{@link KnowledgeReferenceVideoService}）」之间，
 * 在导入链路内部被调用。接收原始评论/弹幕，输出清洗后的优质子条目 + LLM 生成的亮点摘要。
 * 清洗结果随后由 {@link KnowledgeReferenceVideoService} 落库到子表 {@code creator_reference_video_item}。
 * <p>
 * <b>核心设计决策：规则分类 + 仅摘要走 LLM</b>
 * 复用反馈侧已有的「规则分类」思路（轻量、零 LLM 成本、确定可复现），但裁剪成知识库只需要的两件事：
 * 判定每条评论 / 弹幕的情绪（POSITIVE/NEGATIVE/NEUTRAL）与是否噪声，只保留「非噪声、非重复、且正 / 负向」的优质条目。
 * 唯一一次 LLM 调用用于把优质条目汇总成案例亮点摘要（highlight_summary），并做了输入截断 + 失败兜底，
 * 不让摘要拖垮整批导入。把分类做成规则、只让摘要走 LLM，是为了把成本压到「每视频最多一次调用」。
 * <p>
 * <b>清洗策略权衡</b>：
 * <ol>
 *   <li>保留负向反馈——创作者也需要知道「观众不喜欢什么」，纯正向量化会失掉改进信号</li>
 *   <li>中性内容全丢弃——「哈哈」「666」「来了」对案例学习无价值</li>
 *   <li>同一视频内去重——刷屏评论同一句话只留一条</li>
 *   <li>空语义过滤——纯标点、小于2字符、哈哈/666/233 等灌水模式直接丢弃</li>
 * </ol>
 */
@Service
public class KnowledgeReferenceCleaningService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReferenceCleaningService.class);

    /**
     * 单视频最多保留的优质子条目数。
     * 200 条对绝大多数视频足够——前 200 条优质反馈已覆盖所有情绪面；
     * 爆款视频即使有上千条评论，取前 200 条够用，避免子表被单一视频撑大。
     */
    private static final int MAX_QUALITY_ITEMS_PER_VIDEO = 200;

    /**
     * 喂给摘要 LLM 的优质条目文本预算。
     * 8000 字远低于 LLM_GUARD 的 30000 字限制，保证摘要调用不会被拦截。
     * 同时也控制摘要 Prompt 的 token 开销：8000 字 ≈ 4K~6K tokens，一次摘要调用约 0.01 元。
     */
    private static final int SUMMARY_INPUT_CHAR_BUDGET = 8000;

    /**
     * 负向情绪关键词，优先于正向判断。
     * 「负向优先」策略：一旦命中负向词就判负向，避免「不实用」「不太清楚」这类含正向词（「实用」「清楚」）
     * 但实为负向的内容被误判为正向。
     */
    private static final List<String> NEGATIVE_KEYWORDS = List.of(
            "不对", "看不懂", "听不清", "差", "错误", "质疑", "离谱", "不是", "问题",
            "太快", "催更", "劝退", "失望", "无聊", "标题党", "水", "坑");

    /**
     * 正向情绪关键词。
     * 覆盖 B 站常见正向反馈用语：内容质量（干货/实用/清楚）、认可（赞/支持/谢谢/收藏）、
     * 学习效果（懂了/学会/受用/解惑）、技术赞赏（牛/厉害/优质）。
     */
    private static final List<String> POSITIVE_KEYWORDS = List.of(
            "有用", "清楚", "学会", "感谢", "谢谢", "赞", "支持", "懂了", "实用", "确实",
            "真实", "赞同", "收藏", "牛", "厉害", "通俗", "干货", "受用", "解惑", "优质");

    private final LLMService llmService;
    private final PromptService promptService;

    public KnowledgeReferenceCleaningService(LLMService llmService, PromptService promptService) {
        this.llmService = llmService;
        this.promptService = promptService;
    }

    /**
     * 清洗一个视频的评论 / 弹幕，产出优质子条目 + 亮点摘要。
     * <p>
     * <b>处理流程</b>：
     * <ol>
     *   <li>先洗评论（comment），再洗弹幕（danmaku），共用同一个去重 Map 和条数上限</li>
     *   <li>逐条过 classify()：空语义/重复/中性丢弃；只保留正/负向优质条目</li>
     *   <li>用清洗出的优质条目让 LLM 生成亮点摘要（最多一次调用）</li>
     * </ol>
     * <p>
     * comments / danmaku 为空时返回空结果（父表照常落库、子表无记录、摘要为空），与 5.1a 行为一致。
     *
     * @param videoId  视频 ID（用于关联子条目）
     * @param title    视频标题（用于摘要 Prompt）
     * @param comments 原始评论列表，可为 null
     * @param danmaku  原始弹幕列表，可为 null
     * @return 清洗结果：优质子条目列表 + LLM 生成的亮点摘要
     */
    public CleaningResult clean(String videoId,
                                String title,
                                List<ReferenceVideoImportRequest.Comment> comments,
                                List<ReferenceVideoImportRequest.Danmaku> danmaku) {
        List<ReferenceVideoItemRecord> quality = new ArrayList<>();
        // 同一视频内做去重，避免刷屏评论重复占满子表
        Map<String, Integer> seenContent = new LinkedHashMap<>();

        if (comments != null) {
            for (ReferenceVideoImportRequest.Comment comment : comments) {
                if (quality.size() >= MAX_QUALITY_ITEMS_PER_VIDEO) {
                    break;
                }
                ReferenceVideoItemRecord item = classify(
                        videoId, "COMMENT", comment.content(), null, comment.like(), comment.reply(), seenContent);
                if (item != null) {
                    quality.add(item);
                }
            }
        }
        if (danmaku != null) {
            for (ReferenceVideoImportRequest.Danmaku barrage : danmaku) {
                if (quality.size() >= MAX_QUALITY_ITEMS_PER_VIDEO) {
                    break;
                }
                ReferenceVideoItemRecord item = classify(
                        videoId, "DANMAKU", barrage.content(), barrage.timeText(), null, null, seenContent);
                if (item != null) {
                    quality.add(item);
                }
            }
        }

        String highlightSummary = buildHighlightSummary(title, quality);
        return new CleaningResult(quality, highlightSummary);
    }

    /**
     * 规则清洗单条内容。
     * <p>
     * <b>判定流水线（顺序执行，短路丢弃）</b>：
     * <ol>
     *   <li>trim 后为空 → 丢弃</li>
     *   <li>去空格转小写后为空，或命中空语义模式（哈哈/666/纯标点等）→ 丢弃（噪声）</li>
     *   <li>同一视频内已出现过相同内容 → 丢弃（去重）</li>
     *   <li>关键词情绪判定为 NEUTRAL → 丢弃（灌水不进案例库）</li>
     *   <li>通过以上全部 → 创建子条目记录，标记为正/负向优质</li>
     * </ol>
     * <p>
     * <b>设计权衡</b>：为什么不直接用 LLM 判断情绪？
     * 规则分类是确定性的——「有用」「清楚」「看不懂」「差」这些词在 B 站语境下的情绪几乎不会变化，
     * 规则分类零 LLM 成本、毫秒级完成；LLM 用在「需要理解上下文才能总结」的亮点摘要才划算。
     *
     * @return 清洗后的子条目记录；被丢弃时返回 null
     */
    private ReferenceVideoItemRecord classify(String videoId,
                                              String sourceType,
                                              String rawContent,
                                              String occurTimeText,
                                              Long likeCount,
                                              Integer replyCount,
                                              Map<String, Integer> seenContent) {
        String content = TextUtil.trimToNull(rawContent);
        if (content == null) {
            return null;
        }
        String normalized = normalizeForDuplicate(content);
        // 空语义（「哈哈」「666」等）直接当噪声丢弃
        if (normalized.isBlank() || isEmptyMeaning(content)) {
            return null;
        }
        // 同一视频内重复内容只留第一条
        int seenCount = seenContent.getOrDefault(normalized, 0);
        seenContent.put(normalized, seenCount + 1);
        if (seenCount > 0) {
            return null;
        }
        String sentiment = classifySentiment(content);
        // 只要优质正 / 负向，中性灌水不进案例库
        if (!"POSITIVE".equals(sentiment) && !"NEGATIVE".equals(sentiment)) {
            return null;
        }

        ReferenceVideoItemRecord item = new ReferenceVideoItemRecord();
        item.setItemId(UUID.randomUUID().toString());
        item.setVideoId(videoId);
        item.setSourceType(sourceType);
        item.setContent(content);
        item.setSentiment(sentiment);
        item.setLikeCount(likeCount);
        item.setReplyCount(replyCount);
        item.setOccurTimeText(TextUtil.trimToNull(occurTimeText));
        item.setReason("规则清洗：判为" + ("POSITIVE".equals(sentiment) ? "优质正向" : "优质负向") + "，非噪声、非重复。");
        return item;
    }

    /**
     * 把优质条目让 LLM 汇总成一段亮点摘要。
     * <p>
     * 无优质条目时返回 null（不调用 LLM，节省成本）。
     * 摘要属于锦上添花——失败只记日志、返回 null，绝不中断导入或回滚已清洗的子条目。
     * 这是整个清洗流程中<b>唯一一次 LLM 调用</b>，每视频最多一次。
     *
     * @param title   视频标题
     * @param quality 清洗出的优质子条目列表
     * @return LLM 生成的亮点摘要文本，失败或无条目时返回 null
     */
    private String buildHighlightSummary(String title, List<ReferenceVideoItemRecord> quality) {
        if (quality.isEmpty()) {
            return null;
        }
        try {
            String userPrompt = buildSummaryUserPrompt(title, quality);
            String summary = llmService.chat(promptService.get("reference_cleaning.system"), userPrompt);
            return TextUtil.trimToNull(summary);
        } catch (Exception exception) {
            log.warn("生成案例亮点摘要失败，highlight_summary 置空（不影响导入与子表落库）。title={}", title, exception);
            return null;
        }
    }

    private String buildSummaryUserPrompt(String title, List<ReferenceVideoItemRecord> quality) {
        StringBuilder builder = new StringBuilder();
        for (ReferenceVideoItemRecord item : quality) {
            String tag = "POSITIVE".equals(item.getSentiment()) ? "[正向]" : "[负向]";
            String line = tag + item.getContent() + "\n";
            // 累计超过预算就停止，保证整段输入安全落在 LLM_GUARD 限制内
            if (builder.length() + line.length() > SUMMARY_INPUT_CHAR_BUDGET) {
                break;
            }
            builder.append(line);
        }
        return """
                视频标题：%s

                已筛选出的优质观众反馈（[正向] / [负向] + 原文）：
                %s
                请用一段不超过 200 字的话，总结这些反馈反映出的该视频亮点，以及值得其他创作者借鉴或改进之处。
                """.formatted(TextUtil.trimToDefault(title, "未提供"), builder.toString().trim());
    }

    /**
     * 去重用的归一化：转小写 + 去所有空白符。
     * 「UP 主加油」和「up 主 加油」归一化后相同，视为重复。
     */
    private String normalizeForDuplicate(String content) {
        return content.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /**
     * 判定内容是否属于空语义（灌水/纯情绪表达/无信息量）。
     * 规则：<ol>
     *   <li>去掉标点和空格后长度不足 2 字符 → 空语义</li>
     *   <li>全由「哈/啊」组成 → 纯情绪</li>
     *   <li>全由英文语气词（ha/haha/hhh）+ 数字网络用语（233/666/www）组成 → 灌水</li>
     * </ol>
     * 这些内容对案例学习没有任何价值，直接丢弃。
     */
    private boolean isEmptyMeaning(String content) {
        String normalized = content.replaceAll("[\\p{P}\\s]+", "");
        if (normalized.length() < 2) {
            return true;
        }
        String lowerValue = normalized.toLowerCase(Locale.ROOT);
        return lowerValue.matches("(ha|haha|hhh|233|666|www)+") || normalized.matches("[哈啊]+");
    }

    /**
     * 基于关键词的情绪分类器。
     * <b>负向优先</b>：先判负向词，避免「不实用」「不太清楚」这类含正向词但实为负向的内容被误判。
     * 负向/正向都不命中则判 NEUTRAL（中性灌水不进案例库）。
     *
     * @return POSITIVE / NEGATIVE / NEUTRAL
     */
    private String classifySentiment(String content) {
        // 负向优先：先判负向词，避免含正向词但实为负向的内容被误判
        if (containsAny(content, NEGATIVE_KEYWORDS)) {
            return "NEGATIVE";
        }
        if (containsAny(content, POSITIVE_KEYWORDS)) {
            return "POSITIVE";
        }
        return "NEUTRAL";
    }

    /**
     * 检查 content 是否包含关键词列表中的任意一个。
     * 大小写不敏感，采用 contains 子串匹配（非精确词边界），
     * 因为 B 站评论常将关键词嵌在长句中（如「这个教程真的太有用了」包含「有用」）。
     */
    private boolean containsAny(String content, List<String> keywords) {
        String lowerValue = content.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lowerValue.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 清洗结果：优质子条目列表 + LLM 亮点摘要。
     *
     * @param items             清洗后的优质子条目（正/负向，去重，去噪声）
     * @param highlightSummary  LLM 生成的亮点摘要，可为 null（无优质条目或 LLM 调用失败）
     */
    public record CleaningResult(
            List<ReferenceVideoItemRecord> items,
            String highlightSummary
    ) {
    }
}
