package com.link.linkagent.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 调用保护配置。
 * 演示环境可能暴露给面试官或测试用户，所以这里用统一配置限制单次输入规模，避免超长内容直接放大模型成本。
 */
@Component
@ConfigurationProperties(prefix = "agent.llm.guard")
public class LlmCallGuardProperties {

    /**
     * 默认开启保护，是为了让 Docker 演示环境即使忘记配置也具备基础成本边界。
     */
    private boolean enabled = true;

    /**
     * 这里先用字符数做粗粒度保护，原因是项目当前没有统一 tokenizer，过早引入 token 计算会增加依赖和维护成本。
     */
    private int maxPromptChars = 30000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxPromptChars() {
        return maxPromptChars;
    }

    public void setMaxPromptChars(int maxPromptChars) {
        this.maxPromptChars = maxPromptChars;
    }
}
