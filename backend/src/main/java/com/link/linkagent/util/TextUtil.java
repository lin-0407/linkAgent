package com.link.linkagent.util;

/**
 * 文本通用工具。
 * 只沉淀跨模块反复出现的基础文本规则，避免业务服务类各自维护一份空白判断和截断逻辑。
 */
public final class TextUtil {

    private TextUtil() {
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public static boolean isBlank(String value) {
        return !hasText(value);
    }

    public static String trimToDefault(String value, String defaultValue) {
        if (isBlank(value)) {
            return defaultValue;
        }
        return value.trim();
    }

    public static String trimToNull(String value) {
        return trimToDefault(value, null);
    }

    public static String abbreviate(String value, int maxLength) {
        validateMaxLength(maxLength);
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public static String abbreviateWithSuffix(String value, int maxLength, String suffix) {
        validateMaxLength(maxLength);
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + (suffix == null ? "" : suffix);
    }

    public static String collapseWhitespace(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    public static String preview(String value, int maxLength, String blankText) {
        String normalized = collapseWhitespace(value);
        if (isBlank(normalized)) {
            return blankText;
        }
        return abbreviateWithSuffix(normalized, maxLength, "...");
    }

    /**
     * 三明治截断：保留开头和结尾，中间按段落均匀抽样。
     * 适用于视频文稿/字幕等长文本 —— 开头（钩子）和结尾（总结）对 AI 分析最关键，
     * 硬截断容易丢掉结尾，导致标题和简介建议缺少完整语境。
     *
     * @param value       原始文本
     * @param headChars   保留开头字符数
     * @param tailChars   保留结尾字符数
     * @param middleChars 中间部分抽样总字符数
     * @param suffix      截断后缀
     * @return 三明治截断后的文本，若原文不超总上限则原样返回
     */
    public static String abbreviateSandwich(String value, int headChars, int tailChars, int middleChars, String suffix) {
        if (value == null || value.length() <= headChars + middleChars + tailChars) {
            return value;
        }
        // 按段落拆分，避免在句子中间截断
        String[] paragraphs = value.split("\\n");
        StringBuilder head = new StringBuilder();
        StringBuilder tail = new StringBuilder();
        int headCount = 0;
        int tailCount = 0;

        // 从开头收集段落直到满足 headChars
        for (String paragraph : paragraphs) {
            if (headCount >= headChars) {
                break;
            }
            head.append(paragraph).append("\n");
            headCount += paragraph.length() + 1;
        }

        // 从结尾收集段落直到满足 tailChars
        for (int i = paragraphs.length - 1; i >= 0; i--) {
            if (tailCount >= tailChars) {
                break;
            }
            tail.insert(0, "\n").insert(0, paragraphs[i]);
            tailCount += paragraphs[i].length() + 1;
        }

        // 中间部分：按段落均匀抽样，确保信息密度
        int headParagraphs = countParagraphsUsed(headChars, paragraphs, true);
        int tailParagraphs = countParagraphsUsed(tailChars, paragraphs, false);
        int middleParagraphCount = paragraphs.length - headParagraphs - tailParagraphs;
        StringBuilder middle = new StringBuilder();
        if (middleParagraphCount > 0 && middleChars > 0) {
            int sampleInterval = Math.max(1, middleParagraphCount / Math.max(1, middleChars / 200));
            // 每个抽样段至少保留一个完整段落
            for (int i = headParagraphs; i < paragraphs.length - tailParagraphs; i++) {
                if ((i - headParagraphs) % sampleInterval == 0) {
                    String para = paragraphs[i];
                    int remaining = middleChars - middle.length();
                    if (remaining <= 0) {
                        break;
                    }
                    if (para.length() > remaining) {
                        middle.append(para, 0, remaining).append("\n");
                        break;
                    }
                    middle.append(para).append("\n");
                }
            }
        }

        return head.toString().trim()
                + "\n\n[..." + suffix + "...]\n\n"
                + (middle.isEmpty() ? "" : middle.toString().trim() + "\n\n")
                + tail.toString().trim();
    }

    /**
     * 计算从开头或结尾收集段落时使用了多少个段落。
     */
    private static int countParagraphsUsed(int targetChars, String[] paragraphs, boolean fromStart) {
        int count = 0;
        int collected = 0;
        if (fromStart) {
            for (String paragraph : paragraphs) {
                if (collected >= targetChars) {
                    break;
                }
                collected += paragraph.length() + 1;
                count++;
            }
        } else {
            for (int i = paragraphs.length - 1; i >= 0; i--) {
                if (collected >= targetChars) {
                    break;
                }
                collected += paragraphs[i].length() + 1;
                count++;
            }
        }
        return count;
    }

    private static void validateMaxLength(int maxLength) {
        if (maxLength < 0) {
            throw new IllegalArgumentException("maxLength不能小于0");
        }
    }
}
