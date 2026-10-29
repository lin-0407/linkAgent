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
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldPreserveClientErrorStatusDuringActualMvcBinding() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RequestBindingController())
                .setControllerAdvice(handler).build();

        mvc.perform(get("/binding/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("message").value("缺少必需请求头：Idempotency-Key"));
        mvc.perform(get("/binding/limit").param("limit", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("message").value("请求参数 limit 的类型不正确。"));
        mvc.perform(post("/binding/body").contentType(MediaType.APPLICATION_XML).content("<body/>"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Accept", MediaType.APPLICATION_JSON_VALUE));
        mvc.perform(get("/binding/header").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isBadRequest()).andExpect(content().string(""));
        mvc.perform(get("/binding/limit").param("limit", "abc").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isBadRequest()).andExpect(content().string(""));
        mvc.perform(post("/binding/body").contentType(MediaType.APPLICATION_XML)
                        .accept(MediaType.TEXT_EVENT_STREAM).content("<body/>"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(content().string(""));
    }

    @RestController
    static class RequestBindingController {
        @GetMapping("/binding/header")
        String header(@RequestHeader("Idempotency-Key") String key) { return key; }
        @GetMapping("/binding/limit")
        int limit(@RequestParam("limit") int limit) { return limit; }
        @PostMapping(value = "/binding/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        String body(@RequestBody String body) { return body; }
    }

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

    @Test
    void shouldReturnNotFoundForMissingResource() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/missing");
        NoResourceFoundException exception =
                new NoResourceFoundException(HttpMethod.GET, "/api/missing");

        ResponseEntity<?> response = handler.handleNoResourceFound(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isEqualTo(new ApiErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "请求地址不存在。",
                "/api/missing"
        ));
    }
}
