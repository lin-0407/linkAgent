package com.link.linkagent.util;

/**
 * 文本通用工具。
 * <p>
 * 职责：沉淀跨模块反复出现的基础文本处理规则（空白判断、截断、压缩空白、预览），
 * 避免业务服务类各自维护一份重复逻辑，保证所有模块对"空白""截断"的语义一致。
 * <p>
 * 设计约束：仅放纯函数式的字符串变换，不涉及 I/O、国际化、业务上下文。
 * 工具类不可实例化。
 */
public final class TextUtil {

    /** 私有构造器，防止外部实例化工具类。 */
    private TextUtil() {
    }

    /**
     * 判断字符串是否包含有效文本（非 null 且非空白）。
     * <p>
     * 为什么用 {@link String#isBlank()} 而非 {@code isEmpty()}：
     * {@code isBlank()} 能过滤仅含空格/制表符/换行符的字符串，
     * 这在 LLM 输出清洗场景中很关键 —— 模型偶尔会输出纯空白行作为分隔。
     *
     * @param value 待检查的字符串，可为 null
     * @return true 如果字符串非 null 且包含非空白字符
     */
    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 判断字符串是否为空白（null 或不含有效字符）。
     * 直接取反 {@link #hasText(String)}，保证两个方法的语义严格互补。
     *
     * @param value 待检查的字符串
     * @return true 如果字符串为 null 或不含非空白字符
     */
    public static boolean isBlank(String value) {
        return !hasText(value);
    }

    /**
     * 去除字符串首尾空白，若结果为空则返回默认值。
     * <p>
     * 典型场景：API 参数中允许不传的字符串字段，不传时用配置默认值。
     *
     * @param value        原始字符串，可为 null
     * @param defaultValue 当原始字符串为空白时返回的兜底值
     * @return 非空白原始字符串的 trim 结果，或 defaultValue
     */
    public static String trimToDefault(String value, String defaultValue) {
        if (isBlank(value)) {
            return defaultValue;
        }
        return value.trim();
    }

    /**
     * 去除字符串首尾空白，若结果为空则返回 null。
     * 用于数据库写操作：空白字符串统一存为 null，避免占空间且方便索引。
     *
     * @param value 原始字符串，可为 null
     * @return trim 后的非空字符串，或 null
     */
    public static String trimToNull(String value) {
        return trimToDefault(value, null);
    }

    /**
     * 按最大长度截断字符串（硬截断，不带后缀）。
     * <p>
     * 边界条件：若 value 为 null 或长度不超过 maxLength，原样返回。
     * 如果 maxLength 为负数，抛出 {@link IllegalArgumentException}。
     *
     * @param value     原始字符串，可为 null
     * @param maxLength 允许的最大字符数
     * @return 截断后的字符串
     * @throws IllegalArgumentException 如果 maxLength 小于 0
     */
    public static String abbreviate(String value, int maxLength) {
        validateMaxLength(maxLength);
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * 按最大长度截断字符串并追加后缀。
     * <p>
     * 与 {@link #abbreviate(String, int)} 的区别：截断后在末尾追加 suffix（如 "..."），
     * 给读者明确的"内容被截断"信号。suffix 为 null 时等价于无后缀截断。
     *
     * @param value     原始字符串，可为 null
     * @param maxLength 截断后的前缀最大字符数（不含后缀长度）
     * @param suffix    截断后追加的后缀，可为 null
     * @return 截断后的字符串（可能带后缀）
     * @throws IllegalArgumentException 如果 maxLength 小于 0
     */
    public static String abbreviateWithSuffix(String value, int maxLength, String suffix) {
        validateMaxLength(maxLength);
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + (suffix == null ? "" : suffix);
    }

    /**
     * 压缩连续空白字符为单个空格，并去除首尾空白。
     * <p>
     * 为什么用 {@code \s+} 正则而非逐字符遍历：
     * LLM 输出常见多余换行和缩进，正则一次处理比手动循环更简洁且性能差异可忽略。
     *
     * @param value 原始字符串，可为 null
     * @return 压缩并 trim 后的字符串，若 value 为 null 则返回 null
     */
    public static String collapseWhitespace(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    /**
     * 归一化异常消息：消息为空时回退到异常类名，并压缩连续空白。
     * 统一该规则是为了避免索引、用量统计等旁路能力各自维护一份相同实现。
     *
     * @param exception 异常，可为 null
     * @return 适合记录或展示的单行消息，exception 为 null 时返回 null
     */
    public static String normalizeExceptionMessage(Exception exception) {
        if (exception == null) {
            return null;
        }
        return collapseWhitespace(trimToDefault(
                exception.getMessage(), exception.getClass().getSimpleName()));
    }

    /**
     * 归一化并截断异常消息，统一数据库错误字段和批处理告警的长度控制。
     *
     * @param exception 异常，可为 null
     * @param maxLength 保留的消息字符数，不包含截断后缀
     * @return 归一化后的异常消息，超长时追加省略号
     */
    public static String normalizeExceptionMessage(Exception exception, int maxLength) {
        return abbreviateWithSuffix(normalizeExceptionMessage(exception), maxLength, "...");
    }

    /**
     * 生成文本预览：压缩空白后截断并加省略号，空白时返回占位文本。
     * <p>
     * 主要用于 UI 列表的预览列，如会话列表中的最后一条消息预览。
     * 先压缩空白是为了避免预览中出现无意义的大量空白字符。
     *
     * @param value     原始文本，可为 null
     * @param maxLength 预览最大字符数
     * @param blankText 当文本为空时显示的占位文本（如 "（空）"）
     * @return 预览文本，非空
     */
    public static String preview(String value, int maxLength, String blankText) {
        String normalized = collapseWhitespace(value);
        if (isBlank(normalized)) {
            return blankText;
        }
        return abbreviateWithSuffix(normalized, maxLength, "...");
    }

    /**
     * 三明治截断：保留开头和结尾，中间按段落均匀抽样。
     * <p>
     * <b>为什么需要三明治截断：</b>
     * 视频文稿/字幕等长文本 —— 开头（钩子）对标题和热度预估最关键，
     * 结尾（总结/CTA）对完整性分析同样关键。
     * 普通硬截断（只取前 N 字）会丢掉结尾信息，导致 AI 分析缺少完整语境。
     * <p>
     * <b>算法思路：</b>
     * <ol>
     *   <li>按换行符拆分为段落，保证不在句子中间截断</li>
     *   <li>从头尾分别收集段落，直到各自满足 headChars / tailChars 配额</li>
     *   <li>中间段落按采样间隔均匀抽取（间隔 = 中间段落数 / (middleChars / 200)），
     *       保证信息密度而不丢失关键转折</li>
     *   <li>三部分用 "[...suffix...]" 分隔符拼接</li>
     * </ol>
     *
     * @param value       原始文本
     * @param headChars   保留开头字符数上限
     * @param tailChars   保留结尾字符数上限
     * @param middleChars 中间部分抽样总字符数上限
     * @param suffix      截断标记文本（如 "中间省略"）
     * @return 三明治截断后的文本；若原文长度不超过 headChars + middleChars + tailChars 则原样返回
     */
    public static String abbreviateSandwich(String value, int headChars, int tailChars, int middleChars, String suffix) {
        if (value == null || value.length() <= headChars + middleChars + tailChars) {
            return value;
        }
        // 按段落拆分，避免在句子中间截断，保证可读性
        String[] paragraphs = value.split("\\n");
        StringBuilder head = new StringBuilder();
        StringBuilder tail = new StringBuilder();
        int headCount = 0;
        int tailCount = 0;

        // 从开头收集段落直到满足 headChars 配额
        for (String paragraph : paragraphs) {
            if (headCount >= headChars) {
                break;
            }
            head.append(paragraph).append("\n");
            headCount += paragraph.length() + 1; // +1 用于换行符
        }

        // 从结尾反向收集段落直到满足 tailChars 配额
        for (int i = paragraphs.length - 1; i >= 0; i--) {
            if (tailCount >= tailChars) {
                break;
            }
            tail.insert(0, "\n").insert(0, paragraphs[i]);
            tailCount += paragraphs[i].length() + 1;
        }

        // 中间部分：按段落均匀抽样，确保信息密度不过低
        int headParagraphs = countParagraphsUsed(headChars, paragraphs, true);
        int tailParagraphs = countParagraphsUsed(tailChars, paragraphs, false);
        int middleParagraphCount = paragraphs.length - headParagraphs - tailParagraphs;
        StringBuilder middle = new StringBuilder();
        if (middleParagraphCount > 0 && middleChars > 0) {
            // 采样间隔：中间段落数除以期望的采样点数（每 200 字符约 1 个采样点）
            int sampleInterval = Math.max(1, middleParagraphCount / Math.max(1, middleChars / 200));
            // 遍历中间段落，按采样间隔取段落，直到 middleChars 配额用完
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
     * 计算从开头或结尾按字符配额收集段落时，实际消耗了多少个段落。
     * <p>
     * 这是 {@link #abbreviateSandwich} 的辅助方法，用于确定中间部分的起始/结束段落索引。
     * +1 用于补齐每个段落末尾的换行符开销。
     *
     * @param targetChars 目标收集字符数
     * @param paragraphs  全部段落数组
     * @param fromStart   true 从开头计数，false 从结尾计数
     * @return 满足 targetChars 配额所消耗的段落数
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

    /**
     * 校验 maxLength 参数合法性。
     * 负数在 substring 中会抛 {@link StringIndexOutOfBoundsException}，
     * 提前校验给出更明确的错误信息。
     *
     * @param maxLength 待校验的最大长度值
     * @throws IllegalArgumentException 如果 maxLength 小于 0
     */
    private static void validateMaxLength(int maxLength) {
        if (maxLength < 0) {
            throw new IllegalArgumentException("maxLength不能小于0");
        }
    }
}
