package com.dobeshadow.mineleaderboards.util;

import org.bukkit.ChatColor;

/**
 * 数值解析工具。
 * PAPI / 经济插件 / 统计接口返回的字符串经常带颜色代码、千分位逗号、
 * 单位后缀，甚至占位符没解析时原样返回 "%xxx%"，统一在这里清洗。
 */
public final class NumberUtil {

    private NumberUtil() {
    }

    /**
     * 把任意字符串解析成有限 double。
     * 解析失败、占位符未展开、NaN、Infinity 一律返回 null。
     */
    public static Double parse(String raw) {
        if (raw == null) {
            return null;
        }
        // 占位符没被解析时 PAPI 会原样返回 %xxx%
        if (raw.indexOf('%') >= 0) {
            return null;
        }
        String s = ChatColor.stripColor(raw).replace(",", "").trim();
        // 去掉除数字、小数点、负号之外的一切字符（单位、货币符号等）
        s = s.replaceAll("[^0-9.\\-]", "");
        if (s.isEmpty() || s.equals("-") || s.equals(".") || s.equals("-.")) {
            return null;
        }
        try {
            double d = Double.parseDouble(s);
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析并过滤掉负数（用于财富、时长、积分这类不应为负的指标）。
     */
    public static Double parseNonNegative(String raw) {
        Double d = parse(raw);
        if (d == null || d < 0) {
            return null;
        }
        return d;
    }
}
