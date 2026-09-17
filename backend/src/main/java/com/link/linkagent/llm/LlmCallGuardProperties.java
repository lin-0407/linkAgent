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
     * 请求入口的硬上限，单位是 token，不是字符。
     * <p>
     * 2026-09 由字符上限改为 token 上限：字符数与 token 数不成比例（中文约 0.55 token/字符），
     * 用字符数既说不清还剩多少 1M 窗口，也没法和 256k 压缩阈值放在一起比较。
     * 这里量的是 LLMService 收到的全部文本（系统提示词 + 全部消息，含历史与工具结果），
     * 工具定义（约 160 token）不在其中。
     * <p>
     * 768k 是入口兜底：正常请求在 256k 就已被压缩，只有压缩失效或业务直接塞入超长文本时才会走到这条线。
     */
    private int maxPromptTokens = 768000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxPromptTokens() {
        return maxPromptTokens;
    }

    public void setMaxPromptTokens(int maxPromptTokens) {
        this.maxPromptTokens = maxPromptTokens;
    }
}
