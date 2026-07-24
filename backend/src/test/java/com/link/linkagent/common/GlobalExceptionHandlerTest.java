package com.link.linkagent.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldHandleDisconnectedAsyncResponseWithoutFallingBackToServerError() {
        ExceptionHandlerMethodResolver resolver = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

        assertThat(resolver.resolveMethod(new AsyncRequestNotUsableException("Broken pipe")))
                .isNotNull()
                .extracting(method -> method.getName())
                .isEqualTo("handleAsyncRequestNotUsable");
    }

    @Test
    void shouldNotWriteJsonErrorBodyForEventStreamRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/creator/tasks/task-1/workflow/sessions/session-1/events");
        request.setAttribute(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE, Set.of(MediaType.TEXT_EVENT_STREAM));

        ResponseEntity<?> response = handler.handleException(new RuntimeException("boom"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void shouldKeepJsonErrorBodyForNormalApiRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/sessions");

        ResponseEntity<?> response = handler.handleException(new RuntimeException("boom"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isEqualTo(new ApiErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "服务内部异常，请查看后端日志定位具体原因。",
                "/api/agent/sessions"
        ));
    }

    @Test
    void shouldReturnMethodNotAllowedForUnsupportedRequestMethod() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/settings/connectivity/check");
        HttpRequestMethodNotSupportedException exception =
                new HttpRequestMethodNotSupportedException("GET", List.of("POST"));

        ResponseEntity<?> response = handler.handleHttpRequestMethodNotSupported(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).containsExactly(HttpMethod.POST);
        assertThat(response.getBody()).isEqualTo(new ApiErrorResponse(
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                "请求方法 GET 不受支持，请使用 POST。",
                "/api/settings/connectivity/check"
        ));
    }
}
