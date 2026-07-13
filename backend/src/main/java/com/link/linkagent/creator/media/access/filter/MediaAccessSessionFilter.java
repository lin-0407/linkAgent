package com.link.linkagent.creator.media.access.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.common.ApiErrorResponse;
import com.link.linkagent.creator.media.access.model.MediaAccessIdentity;
import com.link.linkagent.creator.media.access.service.MediaAccessSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 只保护阶段 7 私有媒体接口的最小会话过滤器。
 * <p>
 * 设计原则：
 * <ul>
 *   <li><b>最小拦截：</b>只拦截媒体相关的 API 路径，不触碰现有创作接口</li>
 *   <li><b>OPTIONS 放行：</b>预检请求不拦截，确保 CORS 正常工作</li>
 *   <li><b>身份注入：</b>解析成功后把 ownerId 写入 request 属性，Controller 零信任客户端传入</li>
 *   <li><b>可替换：</b>完整网关和账号系统完成后，本过滤器应被统一认证授权层替换</li>
 * </ul>
 */
@Component
// 优先级设为最高 +20，确保在 Spring Security（若有）之后但在业务过滤器之前执行
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class MediaAccessSessionFilter extends OncePerRequestFilter {

    private final MediaAccessSessionService sessionService;
    // Jackson ObjectMapper 用于将错误响应序列化为 JSON
    private final ObjectMapper objectMapper;

    public MediaAccessSessionFilter(MediaAccessSessionService sessionService, ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    /**
     * 判断此请求是否应跳过过滤器。
     * <p>
     * 跳过条件：
     * <ul>
     *   <li>OPTIONS 请求：CORS 预检请求不拦截，让浏览器正常完成预检</li>
     *   <li>非媒体保护路径：不在此过滤器管辖范围内的路径放行</li>
     * </ul>
     *
     * @return true 表示跳过（不拦截），false 表示需要检查会话
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // OPTIONS 预检请求不拦截，CORS 由 Spring 的 CorsFilter 处理
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 非媒体保护路径：不拦截
        return !isProtectedMediaPath(request.getRequestURI());
    }

    /**
     * 对受保护路径执行会话校验。
     * <p>
     * 校验流程：
     * <ol>
     *   <li>媒体能力未启用 → 503</li>
     *   <li>Cookie 中无有效会话 → 401</li>
     *   <li>会话有效 → 将 ownerId 注入 request 属性，继续过滤器链</li>
     * </ol>
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 检查媒体能力是否启用
        if (!sessionService.isFeatureEnabled()) {
            writeError(response, request, HttpStatus.SERVICE_UNAVAILABLE, "媒体能力尚未启用");
            return; // 直接返回，不继续过滤器链
        }
        try {
            // 从 Cookie 解析身份
            Optional<MediaAccessIdentity> identity = sessionService.resolveIdentity(request);
            if (identity.isEmpty()) {
                // 无有效会话：返回 401，前端收到后引导用户输入访问口令
                writeError(response, request, HttpStatus.UNAUTHORIZED, "请先通过媒体访问口令认证");
                return;
            }
            // 将 ownerId 注入 request 属性，后续 Controller 通过该属性获取（不信任客户端参数）
            request.setAttribute(MediaAccessSessionService.REQUEST_OWNER_ATTRIBUTE, identity.get().ownerId());
            // 继续执行后续过滤器和 Controller
            filterChain.doFilter(request, response);
        } catch (ResponseStatusException exception) {
            // 捕获 Service 层抛出的异常（如 Redis 不可用），转为 JSON 错误响应
            HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
            String message = exception.getReason() == null ? status.getReasonPhrase() : exception.getReason();
            writeError(response, request, status, message);
        }
    }

    /**
     * 判断请求路径是否为需要媒体会话保护的路径。
     * <p>
     * 保护范围：/api/creator/tasks/{taskId} 下的媒体相关子路径
     * - /draft-video / /draft-videos → 成片上传
     * - /production-plan / /production-plans → 制作计划
     * - /preflight → 发布前试映
     */
    private boolean isProtectedMediaPath(String path) {
        // 空路径不拦截
        if (path == null || !path.startsWith("/api/creator/tasks/")) {
            return false;
        }
        // 检查路径中是否包含媒体相关的子路径标识
        return path.contains("/draft-video")
                || path.contains("/draft-videos")
                || path.contains("/production-plan")
                || path.contains("/production-plans")
                || path.contains("/preflight");
    }

    /**
     * 将错误信息以 JSON 格式写入 HTTP 响应。
     * <p>
     * 不抛出异常而是直接写响应，是因为 Filter 层抛出异常可能被容器默认错误页
     * 覆盖，导致前端收到 HTML 而非 JSON。
     */
    private void writeError(HttpServletResponse response,
                            HttpServletRequest request,
                            HttpStatus status,
                            String message) throws IOException {
        response.setStatus(status.value());                         // HTTP 状态码
        response.setCharacterEncoding(StandardCharsets.UTF_8.name()); // UTF-8 编码
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);    // JSON 格式
        // 使用统一错误响应格式，与 Controller 层的错误格式保持一致
        objectMapper.writeValue(
                response.getOutputStream(),
                new ApiErrorResponse(status.value(), message, request.getRequestURI())
        );
    }
}
