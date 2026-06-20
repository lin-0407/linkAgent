package com.link.linkagent.settings.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * 运行期可写设置白名单。
 * 只有这里列出的 key 才允许通过设置面板修改，避免前端传任意配置键误伤启动期装配项。
 */
public enum RuntimeSettingKey {

    LLM_GUARD_ENABLED(
            "agent.llm.guard.enabled",
            "LLM 成本保护",
            "限制单次模型输入规模，避免演示环境成本失控"),
    SUMMARY_MEMORY_ENABLED(
            "agent.memory.summary.enabled",
            "摘要记忆",
            "对话过长时压缩历史消息，减少后续上下文长度"),
    AGENT_STRUCTURED_KERNEL_ENABLED(
            "agent.kernel.structured.enabled",
            "结构化 Agent 内核",
            "通用 Agent 每步使用 ReActStep 结构化输出，减少对文本格式提示词的依赖"),
    PRE_PUBLISH_AGENT_ENABLED(
            "creator.pre-publish.agent.enabled",
            "发布前优化 Agent",
            "发布前优化优先走 AgentExecutor.runTask，失败时保留旧直连链路回退能力"),
    KNOWLEDGE_RAG_RERANK_ENABLED(
            "knowledge.rag.rerank.enabled",
            "案例库 rerank 精排",
            "案例检索候选召回后调用 rerank 模型重新排序"),
    CREATOR_FEEDBACK_RAG_ENABLED(
            "creator.feedback.rag.enabled",
            "反馈追问 RAG",
            "评论弹幕追问优先使用向量证据检索，基础设施不可用时仍会降级 SQL");

    private final String key;
    private final String name;
    private final String description;

    RuntimeSettingKey(String key, String name, String description) {
        this.key = key;
        this.name = name;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public String displayName() {
        return name;
    }

    public String description() {
        return description;
    }

    public static Optional<RuntimeSettingKey> fromKey(String key) {
        return Arrays.stream(values())
                .filter(item -> item.key.equals(key))
                .findFirst();
    }
}
