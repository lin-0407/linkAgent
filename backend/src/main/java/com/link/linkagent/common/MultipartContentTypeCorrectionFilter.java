package com.link.linkagent.common;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * 修正前端因 axios 默认 Content-Type: application/json 导致 multipart 上传
 * 被 Spring 拒绝（415 Unsupported Media Type）的问题。
 *
 * 背景：axios 实例设了默认 Content-Type: application/json，发送 FormData 时
 * 部分版本不会自动覆盖为 multipart/form-data，导致后端 consumes 条件匹配失败。
 * 前端代码修复后，本 Filter 作为后端兜底，确保无论前端是否已更新都能正常处理。
 *
 * 触发条件：POST + 路径匹配 /context-documents + Content-Type 不以 multipart/ 开头
 * 处理方式：缓存请求体，从第一行提取 boundary，包装请求改写 Content-Type。
 *
 * @author link-agent
 */
@Component
// @Order(-10000) 使用极高的负值确保本 Filter 在 Spring 的 MultipartFilter 之前执行。
// Spring Boot 内置的 OrderedHiddenHttpMethodFilter 优先级为 -10000（同值），
// OrderedFormContentFilter 优先级为 -9900，因此 -10000 能保证本 Filter 最先介入请求处理链。
// 只有先修正 Content-Type，后续的 multipart 解析器才能正确识别 body 格式。
@Order(-10000)
public class MultipartContentTypeCorrectionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MultipartContentTypeCorrectionFilter.class);

    /**
     * 匹配 /creator/interactive/tasks/{taskId}/context-documents 路径
     */
    private static final java.util.regex.Pattern CONTEXT_DOCUMENTS_PATH =
            java.util.regex.Pattern.compile(".*/creator/interactive/tasks/[^/]+/context-documents");

    /**
     * Filter 入口：检测请求是否需要修正 Content-Type。
     * <p>
     * 拦截条件：POST 方法 + 匹配 context-documents 路径 + Content-Type 不是 multipart/ 开头。
     * 满足条件时，用 {@link CachedBodyRequestWrapper} 包装请求（缓存 body、从 body 提取 boundary），
     * 使下游的 Spring MultipartResolver 能正确解析 multipart 数据。
     * <p>
     * 不满足拦截条件时直接放行，保证其他 API 路径不受影响。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String contentType = request.getContentType();
        String method = request.getMethod();
        String uri = request.getRequestURI();

        // 仅拦截：POST 请求 + 匹配的路径 + Content-Type 不是 multipart 开头
        if (!"POST".equalsIgnoreCase(method)
                || !CONTEXT_DOCUMENTS_PATH.matcher(uri).matches()
                || contentType == null
                || contentType.startsWith("multipart/")) {
            chain.doFilter(request, response);
            return;
        }

        log.info("检测到 context-documents 上传使用了非 multipart Content-Type: {}，自动修正", contentType);

        // 包装请求：缓存 body → 提取 boundary → 改写 Content-Type
        CachedBodyRequestWrapper wrapper = new CachedBodyRequestWrapper(request);
        chain.doFilter(wrapper, response);
    }

    /**
     * 缓存请求体的包装器：在构造时读取全部 body 到内存，
     * 同时从第一行提取 multipart boundary 用于修正 getContentType()。
     *
     * 适用于 context-documents 上传场景（文件大小受 Controller 限制 ≤10MB），
     * 不会造成内存压力。
     */
    private static class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] cachedBody;
        private final String correctedContentType;

        CachedBodyRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            // 缓存整个请求体
            this.cachedBody = request.getInputStream().readAllBytes();
            // 从请求体中提取 boundary 并构造正确的 Content-Type
            String boundary = extractBoundary(cachedBody);
            if (boundary != null && !boundary.isEmpty()) {
                this.correctedContentType = "multipart/form-data; boundary=" + boundary;
                log.info("已从请求体提取 multipart boundary，修正 Content-Type 为: {}", correctedContentType);
            } else {
                // 如果提取失败，回退为普通 multipart/form-data（不含 boundary）
                // 部分 Servlet 容器可以自动扫描 body 找到 boundary
                this.correctedContentType = "multipart/form-data";
                log.warn("无法从请求体提取 boundary，使用无 boundary 的 Content-Type 兜底");
            }
        }

        @Override
        public String getContentType() {
            return correctedContentType;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ByteArrayServletInputStream(cachedBody);
        }

        @Override
        public BufferedReader getReader() {
            ByteArrayInputStream bais = new ByteArrayInputStream(cachedBody);
            return new BufferedReader(new InputStreamReader(bais, StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return cachedBody.length;
        }

        @Override
        public long getContentLengthLong() {
            return cachedBody.length;
        }

        /**
         * 从 multipart 请求体的第一行提取 boundary 字符串。
         * multipart 体格式第一行：------WebKitFormBoundaryXXXX\r\n
         * boundary = 去掉开头两个 "--" 后的剩余部分。
         *
         * @param body 请求体字节数组
         * @return 提取到的 boundary 字符串，失败返回 null
         */
        private static String extractBoundary(byte[] body) {
            if (body == null || body.length == 0) {
                return null;
            }
            // 读取第一行（以 \r\n 或 \n 结尾）
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(body.length, 200); i++) { // 只检查前 200 字节足够
                byte b = body[i];
                if (b == '\r' || b == '\n') {
                    break;
                }
                sb.append((char) b);
            }
            String firstLine = sb.toString();
            if (firstLine.isEmpty()) {
                return null;
            }
            // 去掉开头的两个 "--" 得到 boundary
            if (firstLine.startsWith("--")) {
                return firstLine.substring(2);
            }
            return null;
        }
    }

    /**
     * 将字节数组包装为 ServletInputStream，供 downstream（如 StandardServletMultipartResolver）读取。
     */
    private static class ByteArrayServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream delegate;

        ByteArrayServletInputStream(byte[] data) {
            this.delegate = new ByteArrayInputStream(data);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        /**
         * 字节数组输入流始终立即可读（数据已在内存中），因此永远返回 true。
         * Servlet 3.1+ 非阻塞 I/O 要求实现此方法，但这里是同步流不需要阻塞。
         */
        @Override
        public boolean isReady() {
            return true;
        }

        /**
         * 同步流无需注册异步读取回调，空实现即可。
         * 如果下游组件需要非阻塞读取，会主动检查 {@link #isReady()} 返回值。
         */
        @Override
        public void setReadListener(ReadListener listener) {
            // 同步流无需实现异步监听器
        }
    }
}
