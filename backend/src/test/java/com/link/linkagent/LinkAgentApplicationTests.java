package com.link.linkagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 上下文启动测试。
 * <p>
 * 激活 test 概要，是为了读取 src/test/resources/application-test.properties 里的占位配置：
 * 主 application.yml 的 DB_URL、LLM_MODEL 等占位符没有默认值，测试环境必须有替代值才能启动上下文。
 */
@SpringBootTest
@ActiveProfiles("test")
class LinkAgentApplicationTests {

    @Test
    void contextLoads() {
    }

}
