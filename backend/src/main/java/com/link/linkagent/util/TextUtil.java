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

    private static void validateMaxLength(int maxLength) {
        if (maxLength < 0) {
            throw new IllegalArgumentException("maxLength不能小于0");
        }
    }
}
