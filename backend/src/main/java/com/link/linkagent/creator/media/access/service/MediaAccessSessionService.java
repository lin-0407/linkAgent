package com.link.linkagent.creator.media.access.service;

import com.link.linkagent.creator.media.access.model.MediaAccessIdentity;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * P0 单部署媒体访问会话服务。
 * <p>
 * 通过共享访问口令 + HttpOnly Cookie 为个人自托管和演示环境
 * 提供最低访问边界。
 * <p>
 * 安全设计原则：
 * <ul>
 *   <li>Redis 只保存 sessionId 的 SHA-256 摘要，不保存原始 sessionId</li>
 *   <li>浏览器只保存 HttpOnly、SameSite=Strict 的 Cookie；HTTPS 请求额外设置 Secure</li>
 *   <li>即使 Redis 数据被读取，也不能直接拿其中的摘要冒充浏览器会话</li>
 *   <li>访问口令比对使用恒定时间比较，防止时序攻击推断口令长度</li>
 *   <li>失败次数限流：同一 IP 在配置窗口内超过上限后锁定</li>
 * </ul>
 */
@Service
public class MediaAccessSessionService {

    /**
     * request 属性名，Filter 解析身份后写入，Controller 通过该属性读取 ownerId。
     * 使用全限定类名作为属性名，避免与其它 Filter 的属性名冲突。
     */
    public static final String REQUEST_OWNER_ATTRIBUTE =
            MediaAccessSessionService.class.getName() + ".ownerId";

    // Redis key 前缀：会话存储（SHA-256 摘要 → ownerId）
    private static final String SESSION_KEY_PREFIX = "linkagent:media:session:";
    // Redis key 前缀：访问失败计数器（IP SHA-256 → 失败次数）
    private static final String FAILURE_KEY_PREFIX = "linkagent:media:access-failure:";
    // P0 固定归属标识，未来账号系统接入后替换为真实 userId
    private static final String DEFAULT_OWNER_ID = "default";
    // 会话随机字节数：32 字节 → Base64Url 无填充编码后约 43 字符
    private static final int SESSION_RANDOM_BYTES = 32;

    private final CreatorMediaProperties properties;
    private final StringRedisTemplate redisTemplate;
    // SecureRandom 用于生成不可预测的会话 ID，每次调用 nextBytes 都是密码学安全的
    private final SecureRandom secureRandom = new SecureRandom();

    public MediaAccessSessionService(CreatorMediaProperties properties,
                                     StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 校验共享口令并创建会话。
     * <p>
     * 安全约束：
     * <ul>
     *   <li>同一 IP 在配置窗口内失败超过上限后锁定</li>
     *   <li>口令比对使用恒定时间 SHA-256 比较，防止时序攻击</li>
     * </ul>
     *
     * @param accessCode 浏览器提交的访问口令
     * @param request    HTTP 请求（用于获取客户端 IP）
     * @return 创建结果（含原始 sessionId，由 Controller 设置 Cookie）
     */
    public CreatedMediaSession createSession(String accessCode, HttpServletRequest request) {
        // 校验媒体能力配置是否就绪（口令非空、Redis 连接等）
        ensureFeatureReady();
        // 基于客户端 IP 的失败次数限流 key（IP 经过 SHA-256 摘要，不存原始 IP）
        String failureKey = failureKey(request.getRemoteAddr());
        ensureNotRateLimited(failureKey); // 检查是否已被锁定

        // 恒定时间比较访问口令（即使长度不同也走完整 SHA-256 流程）
        if (!constantTimeEquals(properties.getAccessCode(), accessCode)) {
            registerFailure(failureKey); // 记录失败次数
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "媒体访问口令不正确");
        }

        // 口令正确：生成随机会话 ID 并写入 Redis
        String rawSessionId = newSessionId(); // 42 字符的 URL 安全随机串
        String sessionKey = sessionKey(rawSessionId); // SHA-256 摘要作为 Redis key
        try {
            // Redis 存储：key=SHA-256(rawSessionId), value=ownerId, TTL=12h（可配置）
            redisTemplate.opsForValue().set(
                    sessionKey,
                    DEFAULT_OWNER_ID,                    // P0 固定归属
                    properties.getAccessSessionTtl()     // 默认 12 小时
            );
            // 认证成功后清除该 IP 的失败计数
            redisTemplate.delete(failureKey);
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Redis 不可用，无法创建媒体访问会话");
        }

        // 计算过期时间并返回；原始 sessionId 由 Controller 设置到 HttpOnly Cookie
        Instant expiresAt = Instant.now().plus(properties.getAccessSessionTtl());
        return new CreatedMediaSession(rawSessionId, DEFAULT_OWNER_ID, expiresAt);
    }

    /**
     * 从 HttpOnly Cookie 恢复身份。
     * <p>
     * 未找到 Cookie、Redis key 不存在、已过期或 Redis 不可用时都不信任客户端。
     * 这是媒体 Filter 在每个受保护请求上都会调用的方法，必须轻量且快速。
     *
     * @param request HTTP 请求
     * @return 解析出的身份；Cookie 无效时返回 empty
     */
    public Optional<MediaAccessIdentity> resolveIdentity(HttpServletRequest request) {
        // 先从 Cookie 中提取原始 sessionId
        Optional<String> rawSessionId = readSessionCookie(request);
        if (rawSessionId.isEmpty()) {
            return Optional.empty(); // 没有媒体会话 Cookie
        }
        try {
            // 用 SHA-256 摘要查 Redis
            String key = sessionKey(rawSessionId.get());
            String ownerId = redisTemplate.opsForValue().get(key);
            if (ownerId == null || ownerId.isBlank()) {
                return Optional.empty(); // 会话不存在或已过期
            }
            // 获取剩余 TTL，用于响应中显示过期时间
            Long ttlSeconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            if (ttlSeconds == null || ttlSeconds <= 0) {
                return Optional.empty(); // key 存在但无 TTL（异常情况）
            }
            return Optional.of(new MediaAccessIdentity(ownerId, Instant.now().plusSeconds(ttlSeconds)));
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Redis 不可用，无法校验媒体访问会话");
        }
    }

    /**
     * 从 Redis 中删除会话，实现"登出"。
     * 未找到 Cookie 时静默返回（幂等）。
     */
    public void deleteSession(HttpServletRequest request) {
        Optional<String> rawSessionId = readSessionCookie(request);
        if (rawSessionId.isEmpty()) {
            return; // 没有 Cookie 无需删除
        }
        try {
            redisTemplate.delete(sessionKey(rawSessionId.get()));
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Redis 不可用，无法注销媒体访问会话");
        }
    }

    /**
     * 构建认证成功的 Set-Cookie 响应头。
     * <p>
     * Cookie 属性：
     * <ul>
     *   <li>HttpOnly=true → JavaScript 无法读取，防止 XSS 窃取</li>
     *   <li>Secure=request.isSecure() → HTTPS 环境自动启用</li>
     *   <li>SameSite=Strict → 完全阻止跨站请求携带 Cookie</li>
     *   <li>Path=/api → 只对 API 路径发送，减少不必要传输</li>
     * </ul>
     */
    public ResponseCookie buildSessionCookie(String rawSessionId, HttpServletRequest request) {
        return ResponseCookie.from(properties.getAccess().getCookieName(), rawSessionId)
                .httpOnly(true)                                    // 防 XSS
                .secure(request.isSecure())                        // 仅 HTTPS 传输
                .sameSite("Strict")                                // 防 CSRF
                .path("/api")                                      // 仅 API 路径
                .maxAge(properties.getAccessSessionTtl())          // 与 Redis TTL 一致
                .build();
    }

    /**
     * 构建清除 Cookie 的响应头（用于登出）。
     * maxAge=0 告诉浏览器立即删除该 Cookie。
     */
    public ResponseCookie buildExpiredCookie(HttpServletRequest request) {
        return ResponseCookie.from(properties.getAccess().getCookieName(), "")
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Strict")
                .path("/api")
                .maxAge(Duration.ZERO) // maxAge=0 表示立即过期，浏览器会删除
                .build();
    }

    /**
     * 查询媒体能力是否启用，供 Filter 判断是否需要拦截。
     */
    public boolean isFeatureEnabled() {
        return properties.isEnabled();
    }

    // ========== 私有辅助方法 ==========

    /**
     * 校验媒体能力配置就绪，配置不完整时拒绝操作。
     */
    private void ensureFeatureReady() {
        try {
            properties.validateEnabledConfiguration();
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    /**
     * 从 HttpServletRequest 的 Cookie 数组中提取媒体会话 Cookie。
     *
     * @return Cookie 值；未找到或值为空时返回 empty
     */
    private Optional<String> readSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty(); // 请求没有携带任何 Cookie
        }
        for (Cookie cookie : cookies) {
            // 按名称匹配，且值不能为空白
            if (properties.getAccess().getCookieName().equals(cookie.getName())
                    && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * 检查 IP 失败次数是否超过上限。
     * <p>
     * 失败计数 key 的 TTL 由配置的 failureWindow 决定（默认 10 分钟），
     * 过期后自动清零，实现滑动窗口限流效果。
     */
    private void ensureNotRateLimited(String failureKey) {
        try {
            String currentValue = redisTemplate.opsForValue().get(failureKey);
            if (currentValue != null
                    && Integer.parseInt(currentValue) >= properties.getAccess().getMaxFailures()) {
                // 已达上限，拒绝（窗口过期后自动恢复）
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "媒体访问口令尝试次数过多，请稍后再试");
            }
        } catch (NumberFormatException exception) {
            // 旧的异常值（非数字）不应永久锁死访问；删除后重新按计数器记录
            redisTemplate.delete(failureKey);
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Redis 不可用，无法校验访问频率");
        }
    }

    /**
     * 记录一次失败尝试，首次失败时设置过期时间（滑动窗口起点）。
     * 达到上限时立即抛出 429。
     */
    private void registerFailure(String failureKey) {
        try {
            // INCR 是原子操作，并发安全
            Long failures = redisTemplate.opsForValue().increment(failureKey);
            if (failures != null && failures == 1L) {
                // 首次失败：设置过期时间，启动滑动窗口
                redisTemplate.expire(failureKey, properties.getAccess().getFailureWindow());
            }
            if (failures != null && failures >= properties.getAccess().getMaxFailures()) {
                // 达到上限，立即拒绝
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "媒体访问口令尝试次数过多，请稍后再试");
            }
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Redis 不可用，无法记录访问频率");
        }
    }

    /**
     * 生成 32 字节随机数 + Base64 URL 安全编码的会话 ID。
     * 长度约 43 字符，满足 Cookie 值的长度限制。
     */
    private String newSessionId() {
        byte[] randomBytes = new byte[SESSION_RANDOM_BYTES];
        secureRandom.nextBytes(randomBytes); // 密码学安全的随机数
        // Base64 URL 安全编码：无填充，URL 和 Cookie 中无需转义
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * 将原始 sessionId 转为 Redis key（SHA-256 摘要）。
     * Redis 中不保存原始 sessionId，即使数据泄露也无法直接冒用。
     */
    private String sessionKey(String rawSessionId) {
        return SESSION_KEY_PREFIX + sha256Hex(rawSessionId);
    }

    /**
     * 将 IP 地址转为失败计数器的 Redis key（SHA-256 摘要）。
     * 不保存原始 IP，降低日志和数据泄露的隐私风险。
     */
    private String failureKey(String remoteAddress) {
        return FAILURE_KEY_PREFIX + sha256Hex(remoteAddress == null ? "unknown" : remoteAddress);
    }

    /**
     * 恒定时间比较两个字符串的 SHA-256 摘要。
     * <p>
     * 先对双方做 SHA-256（长度不同也会被哈希掩盖），
     * 再用 MessageDigest.isEqual 做恒定时间字节比较。
     * 这样即使攻击者逐字符探测，也无法从响应时间推断口令内容。
     */
    private boolean constantTimeEquals(String expected, String actual) {
        byte[] expectedHash = sha256(expected == null ? "" : expected);
        byte[] actualHash = sha256(actual == null ? "" : actual);
        // MessageDigest.isEqual 是恒定时间比较，不会在第一个不同字节处提前返回
        return MessageDigest.isEqual(expectedHash, actualHash);
    }

    /**
     * 计算字符串的 SHA-256 十六进制摘要。
     */
    private String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    /**
     * 计算字符串的 SHA-256 原始字节。
     * JDK 21 保证 SHA-256 算法可用，因此 NoSuchAlgorithmException 实际不会发生。
     */
    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }

    /**
     * 会话创建结果。
     * rawSessionId 只在 Controller 设置 Cookie 的瞬间存在，不进入响应 body 和日志。
     */
    public record CreatedMediaSession(
            String rawSessionId,   // 原始 sessionId，仅在 Set-Cookie 头中出现一次
            String ownerId,        // P0 固定为 default
            Instant expiresAt      // 会话过期时间，供前端显示
    ) {
    }
}
