package com.link.linkagent.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.Set;

/**
 * 全局异常处理器 —— Spring MVC 统一异常翻译层。
 * <p>
 * 核心职责：将 Controller / Service 层抛出的各类异常转换为结构化的 {@link ApiErrorResponse}，
 * 使前端无需解析 Spring Boot 默认的 HTML 错误页或 Whitelabel Error JSON。
 * <p>
 * 设计决策：
 * <ul>
 *   <li><b>区分 4xx 与 5xx</b>：客户端问题（入参校验、格式错误）返回 400，服务端问题（DB、未知异常）返回 500，
 *       让前端能根据状态码做差异化处理（如 400 提示用户修正输入，500 提示联系管理员）。</li>
 *   <li><b>前端可读信息</b>：message 字段包含中文业务描述而非技术堆栈，前端可直接展示给用户。</li>
 *   <li><b>日志分级</b>：5xx 打 ERROR 便于告警，4xx 打 WARN 避免噪音，SSE 超时静默处理。</li>
 *   <li><b>兜底处理器</b>：最下层的 {@link Exception} 处理器捕获所有未显式处理的异常，防止异常泄漏到 Servlet 容器。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理 {@link ResponseStatusException}（Spring 5 引入的携带 HTTP 状态码的通用异常）。
     * <p>
     * 场景：Controller 中通过 {@code throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在")} 主动抛出，
     * 这里直接按异常携带的状态码和 reason 构造响应。
     * <p>
     * 日志策略：5xx 打 WARN 级别，因为这是业务层主动抛出的"已知错误"，不一定是系统 bug；
     * 4xx 不打日志（前端已能展示），避免日志被客户端输入错误淹没。
     *
     * @param exception 携带 HTTP 状态码和可选 reason 的异常
     * @param request   HTTP 请求，用于提取路径放入响应
     * @return 状态码与异常一致、body 包含 reason 或标准状态短语的错误响应
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> handleResponseStatusException(ResponseStatusException exception,
                                                           HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        // reason 为 null 时回退到 HTTP 标准状态短语（如 404 → "Not Found"），保证前端始终有文本可展示
        String message = exception.getReason() == null ? status.getReasonPhrase() : exception.getReason();
        if (status.is5xxServerError()) {
            log.warn("业务接口返回服务端错误: path={}, message={}", request.getRequestURI(), message, exception);
        }
        if (isEventStreamRequest(request)) {
            return ResponseEntity.status(status).build();
        }
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(status.value(), message, request.getRequestURI()));
    }

    /**
     * 请求方法不匹配属于客户端调用错误，必须保留 405 和 Allow 响应头，不能落入兜底处理器变成 500。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {
        Set<HttpMethod> supportedMethods = exception.getSupportedHttpMethods();
        String supportedMethodText = supportedMethods == null
                ? ""
                : String.join("、", supportedMethods.stream().map(HttpMethod::name).toList());
        String message = supportedMethodText.isEmpty()
                ? "请求方法 " + request.getMethod() + " 不受支持。"
                : "请求方法 " + request.getMethod() + " 不受支持，请使用 " + supportedMethodText + "。";

        log.warn("请求方法不受支持: path={}, method={}, supportedMethods={}",
                request.getRequestURI(), request.getMethod(), supportedMethodText);
        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(exception.getHeaders());
        if (isEventStreamRequest(request)) {
            return responseBuilder.build();
        }
        return responseBuilder.body(new ApiErrorResponse(
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                message,
                request.getRequestURI()
        ));
    }

    /**
     * 处理 {@link MethodArgumentNotValidException}（Controller 方法参数 @Valid 校验失败）。
     * <p>
     * 触发条件：DTO 字段上的验证注解（@NotBlank、@Size 等）在参数绑定时校验失败。
     * 从 BindingResult 中提取第一个字段错误的信息回传给前端，避免返回 Spring 默认的冗长 JSON。
     * <p>
     * 为什么只取第一个错误：对用户来说逐字段提示更友好，但 Spring 默认会列出全部错误，
     * 这里简化处理，前端可以根据单条提示逐步修正。
     *
     * @param exception 包含所有字段校验错误的异常
     * @param request   HTTP 请求
     * @return 400 + 第一个字段的错误描述
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
                                                          HttpServletRequest request) {
        FieldError fieldError = exception.getBindingResult().getFieldError();
        // 理论上不会为 null，但防御性编程以防 BindingResult 为空
        String message = fieldError == null ? "请求参数不合法" : fieldError.getDefaultMessage();
        if (isEventStreamRequest(request)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse(HttpStatus.BAD_REQUEST.value(), message, request.getRequestURI()));
    }

    /**
     * 处理 {@link ConstraintViolationException}（方法级 @Validated 校验失败，如 @RequestParam 上的约束）。
     * <p>
     * 与 MethodArgumentNotValidException 的区别：
     * <ul>
     *   <li>后者是 Controller 方法参数的 @Valid DTO 绑定失败（Spring MVC 内建机制）</li>
     *   <li>前者是 Service 层或方法级 @Validated 约束失败（JSR-380 Bean Validation 机制）</li>
     * </ul>
     * 两者都映射为 400，因为都是客户端输入不合法。
     *
     * @param exception 违反约束时抛出的异常，message 包含具体校验信息
     * @param request   HTTP 请求
     * @return 400 + 校验失败详情
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<?> handleConstraintViolation(ConstraintViolationException exception,
                                                       HttpServletRequest request) {
        if (isEventStreamRequest(request)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse(
                        HttpStatus.BAD_REQUEST.value(),
                        exception.getMessage(),
                        request.getRequestURI()
                ));
    }

    /**
     * 处理 {@link HttpMessageNotReadableException}（请求体 JSON 反序列化失败）。
     * <p>
     * 典型原因：
     * <ul>
     *   <li>JSON 字段名或字符串值未用双引号包裹（单引号或裸字符串）</li>
     *   <li>Content-Type 不是 application/json</li>
     *   <li>请求体为空或格式非 JSON（如纯文本、XML）</li>
     * </ul>
     * 返回 400 并附带明确的修正指引，让前端开发者无需翻看后端日志就能定位问题。
     *
     * @param exception 包含 Jackson 解析失败细节的异常
     * @param request   HTTP 请求
     * @return 400 + 格式修正指引
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
                                                          HttpServletRequest request) {
        // 请求体解析失败通常是调用方 JSON 写法或 Content-Type 不正确，返回 400 能让前端明确这是入参问题。
        log.warn("请求体解析失败: path={}, message={}", request.getRequestURI(), exception.getMessage());
        if (isEventStreamRequest(request)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse(
                        HttpStatus.BAD_REQUEST.value(),
                        "请求体格式不正确，请确认使用合法 JSON：字段名和字符串必须使用双引号，并设置 Content-Type=application/json。",
                        request.getRequestURI()
                ));
    }

    /**
     * 处理 {@link DataAccessException}（Spring 数据库访问底层统一异常）。
     * <p>
     * 捕获范围：涵盖 JDBC、JPA、MyBatis 等所有数据访问层的异常（连接失败、SQL 语法错误、约束冲突等）。
     * 统一返回 500，并提示作者查看后端日志中的根异常。
     * DataAccessException 既可能是表或字段缺失，也可能是 Mapper 映射、连接或约束问题，
     * 不能再把所有情况误导为 init.sql 未执行。
     *
     * @param exception 数据访问异常（具体子类由 ORM 框架决定）
     * @param request   HTTP 请求
     * @return 500 + 数据库排查指引
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> handleDataAccess(DataAccessException exception,
                                              HttpServletRequest request) {
        // 前端不回显底层 SQL 和堆栈，避免把内部实现暴露到页面；具体根因保留在后端日志中。
        log.error("数据库访问失败: path={}", request.getRequestURI(), exception);
        if (isEventStreamRequest(request)) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "数据库操作失败，请查看后端日志中的 Caused by 定位具体原因。",
                        request.getRequestURI()
                ));
    }

    /**
     * 处理 {@link AsyncRequestTimeoutException}（SSE / 异步请求空闲超时）。
     * <p>
     * SSE 长连接在无数据交互时会在容器配置的超时时间后触发此异常。
     * 这属于正常的连接生命周期事件而非错误：前端 SSE EventSource 收到 onerror 后
     * 会按内置重试间隔自动发起重连，后端无需额外处理。
     * <p>
     * 返回 200 OK 而非错误码：让 HTTP 层面正常结束，
     * 避免异步超时在访问日志中产生 500 误报或触发监控告警。
     *
     * @param exception 异步超时异常（不含业务信息）
     * @return 200 OK，空 body
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Void> handleAsyncTimeout(AsyncRequestTimeoutException exception) {
        return ResponseEntity.ok().build();
    }

    /**
     * 客户端或反向代理断开 SSE 后，响应流已经不可写，不能再尝试生成错误响应。
     * Spring 会负责结束异步请求；这里仅阻止正常断连落入兜底处理器并产生 500 误报。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException exception) {
        // 响应已不可用，任何写回或再次完成都会触发新的 I/O 异常。
    }

    /**
     * 兜底异常处理器 —— 捕获所有未被上述处理器匹配的异常。
     * <p>
     * 这是最后一道防线，确保任何未预期的异常都不会以 Spring Boot 默认的
     * Whitelabel Error Page / JSON 返回，而是统一包装为 {@link ApiErrorResponse}。
     * <p>
     * 日志级别 ERROR：因为走到这里说明异常未在业务层被正确分类处理，
     * 应引起开发者注意并补充对应的异常映射。
     *
     * @param exception 未被其他 handler 匹配的任意异常
     * @param request   HTTP 请求
     * @return 500 + 通用错误提示
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(Exception exception, HttpServletRequest request) {
        log.error("未处理的服务端异常: path={}", request.getRequestURI(), exception);
        if (isEventStreamRequest(request)) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "服务内部异常，请查看后端日志定位具体原因。",
                        request.getRequestURI()
                ));
    }

    /**
     * SSE 接口在异常分派时仍可能保留 text/event-stream 响应类型，不能再写 JSON 错误体。
     * 这里同时检查 Spring MVC 匹配出的 produces 和请求头，避免 @ExceptionHandler 二次序列化失败。
     */
    private static boolean isEventStreamRequest(HttpServletRequest request) {
        Object producibleMediaTypes = request.getAttribute(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE);
        if (producibleMediaTypes instanceof Collection<?> mediaTypes) {
            boolean producesEventStream = mediaTypes.stream()
                    .filter(MediaType.class::isInstance)
                    .map(MediaType.class::cast)
                    .anyMatch(mediaType -> mediaType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM));
            if (producesEventStream) {
                return true;
            }
        }
        String acceptHeader = request.getHeader(HttpHeaders.ACCEPT);
        return acceptHeader != null && acceptHeader.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }
}
