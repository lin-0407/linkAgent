package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.model.ReferenceVideoImportRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/** 固定公式与自然月窗口同时供导入、历史回填和统计刷新使用，避免三个入口口径不同。 */
public final class ReferenceVideoCollectionPolicy {
    public static final String VERSION = "public-stat-saturation-v1";
    public static final String AUTO_SOURCE = "bilibili_auto";
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private ReferenceVideoCollectionPolicy() { }

    public static Double score(ReferenceVideoImportRequest.VideoStats stats) {
        if (stats == null) return null;
        Long[] counts = {stats.view(), stats.like(), stats.coin(), stats.favorite(), stats.reply(), stats.danmaku()};
        double[] weights = {25, 15, 30, 25, 3, 2};
        double[] scales = {100000, 1000, 2000, 1000, 1000, 1000};
        double sum = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == null || counts[i] < 0) return null;
            double count = counts[i].doubleValue();
            sum += weights[i] * count / (count + scales[i]);
        }
        return Math.min(100, Math.max(0, sum * stats.view().doubleValue() / (stats.view().doubleValue() + 5000)));
    }

    public static BigDecimal storedScore(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    public static LocalDateTime epochTime(Long value) {
        if (value == null || value <= 0) return null;
        try {
            LocalDateTime parsed = LocalDateTime.ofInstant(Instant.ofEpochSecond(value), ZONE);
            return parsed.getYear() >= 1000 && parsed.getYear() <= 9999 ? parsed : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static LocalDateTime publishedAt(ReferenceVideoImportRequest.VideoItem video) {
        if (video.publishedAtEpochSeconds() != null) {
            LocalDateTime parsed = epochTime(video.publishedAtEpochSeconds());
            if (parsed == null) throw badRequest("结构化发布时间无效");
            return parsed;
        }
        return parseLegacyTime(video.publishTimeText());
    }

    public static LocalDateTime parseLegacyTime(String value) {
        if (value == null) return null;
        String text = value.trim();
        // 严格解析年月日；不把“去年”“2026-02-30”或入库时间猜成发布时间。
        for (String pattern : new String[]{"uuuu-MM-dd HH:mm:ss", "uuuu-MM-dd HH:mm"}) {
            try {
                LocalDateTime parsed = LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT));
                if (parsed.getYear() < 1000 || parsed.getYear() > 9999) return null;
                // 旧文本可能只有分钟精度，取该分钟的末尾，避免不足一年时提早删除。
                return pattern.endsWith("ss") ? parsed : parsed.withSecond(59);
            } catch (DateTimeParseException ignored) { }
        }
        try {
            LocalDate parsed = LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
            if (parsed.getYear() < 1000 || parsed.getYear() > 9999) return null;
            return parsed.atTime(23, 59, 59);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    public static void validateAutomatic(ReferenceVideoImportRequest.VideoItem video, double threshold, LocalDateTime now) {
        if (video.bvId() == null || !video.bvId().matches("BV[0-9A-Za-z]{10}")) throw badRequest("自动采集必须提供有效 BV");
        LocalDateTime published = publishedAt(video);
        LocalDateTime fetched = epochTime(video.statsFetchedAtEpochSeconds());
        if (video.publishedAtEpochSeconds() == null || published == null || published.isBefore(now.minusMonths(6)) || published.isAfter(now)) {
            throw badRequest("自动采集只接受近六个自然月发布的视频");
        }
        if (fetched == null || fetched.isAfter(now) || fetched.isBefore(published) || fetched.isBefore(now.minusDays(2))) {
            throw badRequest("自动采集必须提供最近两天内的有效统计采集时间");
        }
        Double value = score(video.stats());
        // 先用未舍入的值判断，数据库的六位小数只用于展示与排序。
        if (value == null || value <= threshold) throw badRequest("视频指数未严格超过采集阈值，或六项统计不完整");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
