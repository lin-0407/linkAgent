package com.link.linkagent.knowledge.model;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 案例库封面地址的持久化口径。
 * 只接受 B 站视频详情接口使用的公开图片 CDN，并在入库和响应阶段复用同一规则，避免旧数据或手工导入绕过限制。
 */
public final class BilibiliCoverUrlPolicy {

    private static final int MAX_URL_LENGTH = 500;
    private static final Set<String> TRUSTED_IMAGE_HOSTS = Set.of(
            "i0.hdslb.com",
            "i1.hdslb.com",
            "i2.hdslb.com",
            "archive.biliimg.com"
    );

    private BilibiliCoverUrlPolicy() {
    }

    /**
     * 把公开封面地址统一为 HTTPS；无法确认长期有效的地址返回 {@code null}，由页面按需重新回源。
     */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.startsWith("//")) {
            candidate = "https:" + candidate;
        }

        try {
            URI uri = URI.create(candidate);
            if ("http".equalsIgnoreCase(uri.getScheme())) {
                candidate = "https" + candidate.substring(candidate.indexOf(':'));
                uri = URI.create(candidate);
            }
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || host == null
                    || !TRUSTED_IMAGE_HOSTS.contains(host.toLowerCase(Locale.ROOT))
                    || uri.getUserInfo() != null
                    || uri.getPort() != -1
                    || uri.getFragment() != null
                    || uri.getRawPath() == null
                    || uri.getRawPath().isBlank()
                    || containsTemporarySignature(uri.getRawQuery())) {
                return null;
            }

            String normalized = "https://" + host.toLowerCase(Locale.ROOT) + uri.getRawPath();
            if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
                normalized += "?" + uri.getRawQuery();
            }
            return normalized.length() <= MAX_URL_LENGTH ? normalized : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean containsTemporarySignature(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return false;
        }
        for (String parameter : rawQuery.split("[&;]")) {
            String rawKey = parameter.split("=", 2)[0];
            String key;
            try {
                key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException exception) {
                return true;
            }
            if (key.equals("sign")
                    || key.endsWith("_sign")
                    || key.endsWith("-sign")
                    || key.contains("signature")
                    || key.contains("token")
                    || key.contains("secret")
                    || key.contains("hmac")
                    || key.contains("expires")
                    || key.contains("expiry")
                    || key.contains("credential")
                    || key.contains("accesskey")
                    || key.equals("policy")
                    || key.equals("auth_key")
                    || key.equals("authkey")) {
                return true;
            }
        }
        return false;
    }
}
