package com.link.linkagent.core.multi;

import java.util.concurrent.atomic.AtomicBoolean;

/** 将节点停止信号传给内部模型和工具，避免超时后继续发起后续调用。 */
public final class MultiAgentExecutionContext {
    private static final ThreadLocal<AtomicBoolean> CURRENT = new ThreadLocal<>();

    private MultiAgentExecutionContext() { }

    public static AtomicBoolean current() { return CURRENT.get(); }
    public static boolean active() { return CURRENT.get() != null; }

    public static void check() {
        AtomicBoolean cancelled = CURRENT.get();
        if (cancelled != null && cancelled.get()) {
            throw new OutcomeUnknownException("节点已停止等待，外部调用结果可能尚未确认");
        }
    }

    public static Scope open(AtomicBoolean state) {
        AtomicBoolean previous = CURRENT.get();
        if (state == null) CURRENT.remove(); else CURRENT.set(state);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }

    public interface Scope extends AutoCloseable { @Override void close(); }

    public static class OutcomeUnknownException extends RuntimeException {
        public OutcomeUnknownException(String message) { super(message); }
        public OutcomeUnknownException(String message, Throwable cause) { super(message, cause); }
    }
}
