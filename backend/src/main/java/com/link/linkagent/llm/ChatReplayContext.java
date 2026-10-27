package com.link.linkagent.llm;

/**
 * 当前对话轮次的「真实请求信封」采集作用域。
 *
 * <h3>为什么用 ThreadLocal</h3>
 * 一次对话请求内部会多次调用模型（ReAct 每一步一次），但只有第一次调用带着完整的组装上下文；
 * 摘要压缩要复用的正是这一次的请求（system/tools/thinking/消息整体）。
 * AgentExecutor 在轮次开始打开作用域，LLMService 在真正发请求时把第一个可重放的信封记进来，
 * 轮次结束由 AgentExecutor 取走并按会话保存。
 *
 * <p>并发请求各自在自己的线程里执行，互不干扰；作用域关闭时清理 ThreadLocal，避免线程复用串会话。
 */
public final class ChatReplayContext {

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private ChatReplayContext() {
    }

    /** 开启一个轮次作用域；必须在同一线程内调用 {@link Scope#close()}。 */
    public static Scope begin() {
        Scope scope = new Scope();
        CURRENT.set(scope);
        return scope;
    }

    /** 记录一次真实请求的信封；只保留本轮第一个可重放的请求，后续 ReAct 步的信封不再覆盖。 */
    public static void record(ChatRequestEnvelope envelope) {
        Scope scope = CURRENT.get();
        if (scope == null || envelope == null || !envelope.replayable()) {
            return;
        }
        if (scope.envelope == null) {
            scope.envelope = envelope;
        }
    }

    /**
     * 一次对话轮次的作用域。
     */
    public static final class Scope implements AutoCloseable {

        private ChatRequestEnvelope envelope;
        /** 这次请求组装时使用的摘要版本；下一轮压缩前用来判断信封是否已过期。 */
        private String summary;

        private Scope() {
        }

        public ChatRequestEnvelope envelope() {
            return envelope;
        }

        /** 记录本次请求使用的摘要（由 AgentExecutor 在上下文组装后调用）。 */
        public void summary(String summary) {
            this.summary = summary;
        }

        public String usedSummary() {
            return summary;
        }

        /** 关闭作用域并清理 ThreadLocal；不会清空已采集的信封，调用方可先读取再关闭。 */
        @Override
        public void close() {
            if (CURRENT.get() == this) {
                CURRENT.remove();
            }
        }
    }
}
