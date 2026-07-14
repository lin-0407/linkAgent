package com.link.linkagent.creator.media.access.service;

import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MediaAccessSessionService 的部署协议回归测试。
 * <p>
 * LinkAgent 默认是个人自托管工具，公网地址只用于演示，
 * 因此 HTTP 演示环境也必须能在口令验证成功后创建媒体会话。
 * 该测试防止后续开发再次把公网演示误当成多用户生产服务而强制 HTTPS。
 */
class MediaAccessSessionServiceTest {

    /**
     * 正确口令从 HTTP 演示地址提交时应创建 Redis 会话。
     * <p>
     * 请求来源使用保留的公网测试地址，确保该用例不因 localhost 的特殊处理而偶然通过。
     */
    @Test
    void shouldCreateSessionForHttpDemoRequestWithValidAccessCode() {
        CreatorMediaProperties properties = enabledProperties();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");
        when(request.isSecure()).thenReturn(false);

        MediaAccessSessionService service = new MediaAccessSessionService(properties, redisTemplate);

        MediaAccessSessionService.CreatedMediaSession session =
                service.createSession("demo-access-code", request);

        assertThat(session.ownerId()).isEqualTo("default");
        assertThat(session.rawSessionId()).isNotBlank();
        assertThat(session.expiresAt()).isNotNull();
        assertThat(service.buildSessionCookie(session.rawSessionId(), request).isSecure()).isFalse();
        verify(valueOperations).set(anyString(), eq("default"), eq(properties.getAccessSessionTtl()));
        verify(redisTemplate).delete(anyString());
    }

    /**
     * 构造已启用且满足配置校验的媒体属性。
     * 使用固定口令只服务于单元测试，不会进入运行时配置或日志。
     */
    private CreatorMediaProperties enabledProperties() {
        CreatorMediaProperties properties = new CreatorMediaProperties();
        properties.setEnabled(true);
        properties.setAccessCode("demo-access-code");
        return properties;
    }
}
