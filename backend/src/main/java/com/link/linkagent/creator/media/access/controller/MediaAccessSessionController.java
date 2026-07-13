package com.link.linkagent.creator.media.access.controller;

import com.link.linkagent.creator.media.access.model.MediaAccessIdentity;
import com.link.linkagent.creator.media.access.model.MediaAccessSessionCreateRequest;
import com.link.linkagent.creator.media.access.model.MediaAccessSessionResponse;
import com.link.linkagent.creator.media.access.service.MediaAccessSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P0 单部署媒体访问会话接口。
 * <p>
 * 完整账号系统后置，因此本接口只为个人作品集和受控演示提供最低可信边界。
 * 通过共享访问口令认证后，服务端设置 HttpOnly Cookie，后续请求自动携带。
 * <p>
 * 接口说明：
 * <ul>
 *   <li>POST /api/media-access/session → 提交口令创建会话（返回 Set-Cookie）</li>
 *   <li>GET  /api/media-access/session → 查询当前会话状态</li>
 *   <li>DELETE /api/media-access/session → 登出（清除 Cookie 和 Redis 会话）</li>
 * </ul>
 */
@Validated
@RestController
@RequestMapping("/api/media-access/session")
public class MediaAccessSessionController {

    private final MediaAccessSessionService sessionService;

    public MediaAccessSessionController(MediaAccessSessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * 提交访问口令，创建媒体访问会话。
     * <p>
     * 成功后通过 Set-Cookie 响应头下发 HttpOnly Cookie，
     * Cookie 不进入响应 body，也不出现在 JavaScript 可访问的范围。
     */
    @PostMapping
    public ResponseEntity<MediaAccessSessionResponse> createSession(
            @Valid @RequestBody MediaAccessSessionCreateRequest request,
            HttpServletRequest servletRequest) {
        // 调用 Service 校验口令并创建 Redis 会话
        MediaAccessSessionService.CreatedMediaSession session =
                sessionService.createSession(request.accessCode(), servletRequest);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())  // 禁止缓存会话状态
                .header(
                        HttpHeaders.SET_COOKIE,       // 手动设置 Cookie（而非用 spring-session）
                        sessionService.buildSessionCookie(session.rawSessionId(), servletRequest).toString()
                )
                .body(new MediaAccessSessionResponse(true, true, session.expiresAt()));
    }

    /**
     * 查询当前会话状态。
     * <p>
     * 媒体能力未启用时返回 enabled=false。
     * 启用但未认证时返回 enabled=true, authenticated=false。
     * 已认证时返回完整会话信息。
     */
    @GetMapping
    public ResponseEntity<MediaAccessSessionResponse> getSession(HttpServletRequest request) {
        // 媒体能力未开启：返回 disabled 状态
        if (!sessionService.isFeatureEnabled()) {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new MediaAccessSessionResponse(false, false, null));
        }
        // 尝试从 Cookie 解析身份
        MediaAccessSessionResponse response = sessionService.resolveIdentity(request)
                .map(this::toResponse) // 有会话 → 已认证
                .orElseGet(() -> new MediaAccessSessionResponse(true, false, null)); // 无会话 → 未认证
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    /**
     * 登出：清除 Redis 会话 + 清除浏览器 Cookie。
     * <p>
     * 返回 204 No Content，Set-Cookie 头设置 maxAge=0 告知浏览器删除 Cookie。
     */
    @DeleteMapping
    public ResponseEntity<Void> deleteSession(HttpServletRequest request) {
        sessionService.deleteSession(request); // 从 Redis 中删除会话
        return ResponseEntity.noContent()       // 204 No Content
                .cacheControl(CacheControl.noStore())
                // 设置空值 + maxAge=0，浏览器会立即删除该 Cookie
                .header(HttpHeaders.SET_COOKIE, sessionService.buildExpiredCookie(request).toString())
                .build();
    }

    /**
     * 将 MediaAccessIdentity 转为 API 响应。
     */
    private MediaAccessSessionResponse toResponse(MediaAccessIdentity identity) {
        return new MediaAccessSessionResponse(true, true, identity.expiresAt());
    }
}
