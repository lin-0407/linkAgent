package com.link.linkagent.util;

/**
 * 数值通用工具。
 * 当前主要服务分页和列表查询上限，保证不同模块对非法 limit 的兜底行为一致。
 */
public final class NumberUtil {

    private NumberUtil() {
    }

    public static int limitOrDefault(Integer value, int defaultValue, int maxValue) {
        if (value == null || value <= 0) {
            return defaultValue;
        }
        return Math.min(value, maxValue);
    }
}
