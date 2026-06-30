package com.link.linkagent.util;

/**
 * 数值通用工具。
 * <p>
 * 职责：沉淀跨模块反复出现的数值校验与兜底逻辑（分页 limit 钳位、负数/null 归一化），
 * 保证所有查询接口对非法数值的防御策略一致 —— 不传/传负数时走默认值，传超大值时触发上限保护。
 * <p>
 * 设计约束：工具类不可实例化，仅提供纯函数式的 int 变换，不涉及 BigDecimal/浮点运算。
 */
public final class NumberUtil {

    /** 私有构造器，防止外部实例化工具类。 */
    private NumberUtil() {
    }

    /**
     * 将可选的查询数量参数归一化为有效值，并钳位到安全上限。
     * <p>
     * <b>为什么需要钳位上限：</b>
     * 查询接口的 limit 若不加约束，恶意或误传入 Integer.MAX_VALUE 会导致 MySQL 全表扫描 +
     * JVM 内存暴涨，甚至 OOM。maxValue 作为安全阀，保证单次查询的数据量可控。
     * <p>
     * <b>边界条件：</b>
     * <ul>
     *   <li>value 为 null → 返回 defaultValue</li>
     *   <li>value &lt;= 0 → 返回 defaultValue（负数/零语义上无意义）</li>
     *   <li>value &gt; maxValue → 返回 maxValue（触发上限保护）</li>
     *   <li>0 &lt; value &lt;= maxValue → 返回 value 原值</li>
     * </ul>
     *
     * @param value        调用方传入的 limit 值，可为 null（表示未传）
     * @param defaultValue 当 value 无效时使用的默认值
     * @param maxValue     允许的最大值（安全钳位上限）
     * @return 归一化后的有效 limit 值，范围 [1, maxValue]
     */
    public static int limitOrDefault(Integer value, int defaultValue, int maxValue) {
        if (value == null || value <= 0) {
            return defaultValue;
        }
        return Math.min(value, maxValue);
    }
}
