package com.link.linkagent.creator.feedback.util;

/**
 * 评论弹幕枚举值的中文标签工具。
 * <p>
 * 抽出来共享，是因为阶段 4.13 的向量索引服务在拼接被 Embedding 的文档文本时，也需要把 COMMENT/QUESTION/POSITIVE
 * 这类编码翻译成“评论/提问/正向”，否则向量库里只有英文编码，语义检索命中率会下降。和 CreatorFeedbackService
 * 共用同一份映射，避免两处各维护一份 switch 导致标签漂移。
 */
public final class CreatorFeedbackLabelUtil {

    private CreatorFeedbackLabelUtil() {
    }

    /**
     * 把来源/分类/情绪等枚举编码翻译成中文标签；未知值原样返回，null 返回“未分类”。
     */
    public static String labelFor(String value) {
        if (value == null) {
            return "未分类";
        }
        return switch (value) {
            case "COMMENT" -> "评论";
            case "DANMAKU" -> "弹幕";
            case "APPROVAL" -> "认可";
            case "QUESTION" -> "提问";
            case "DOUBT" -> "质疑";
            case "SUGGESTION" -> "建议";
            case "EMOTION" -> "情绪表达";
            case "INTERACTION" -> "互动";
            case "KNOWLEDGE_REACTION" -> "知识点反应";
            case "QUESTION_POINT" -> "时间点疑问";
            case "EMOTION_PEAK" -> "情绪高峰";
            case "RESONANCE" -> "共鸣";
            case "COMPLAINT" -> "吐槽不满";
            case "EMPTY_MEANING" -> "无意义内容";
            case "DUPLICATE" -> "重复内容";
            case "POSITIVE" -> "正向";
            case "NEGATIVE" -> "负向";
            case "NEUTRAL" -> "中性";
            case "OTHER" -> "其他";
            default -> value;
        };
    }
}
