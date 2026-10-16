package com.link.linkagent.llm;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/** 模型的非流式响应需要完整消费分块正文，不能因类路径变化自动换成不兼容的传输客户端。 */
@Configuration(proxyBeanMethods = false)
public class LlmHttpTransportConfiguration {

    @Bean
    RestClientCustomizer llmHttpTransportCustomizer() {
        // 实测默认 Apache 客户端只读到 DeepSeek 正文的首字符；JDK 客户端可完整读取同一响应。
        // Spring AI 正式客户端使用 Boot 提供的 builder，strict 与备用客户端另行显式设置同一传输。
        return builder -> builder.requestFactory(new JdkClientHttpRequestFactory());
    }
}
