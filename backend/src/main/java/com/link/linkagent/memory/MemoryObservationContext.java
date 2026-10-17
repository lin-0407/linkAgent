package com.link.linkagent.memory;

import java.time.LocalDate;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * 回放历史时携带观察日期，避免把旧对话里的“昨天”按执行评测当天解释。
 * 元数据只参与模型输入排版，不修改消息正文、数据库结构或正常对话的默认行为。
 */
public final class MemoryObservationContext {
    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    private MemoryObservationContext() {
    }

    public record Location(String sessionId, String date) {
    }

    public record Snapshot(Map<Long, Location> locations, LocalDate observationDate, PendingCalls pendingCalls, Trace trace) {
        public Snapshot {
            locations = Map.copyOf(locations);
        }

        public Snapshot(Map<Long, Location> locations, LocalDate observationDate) {
            this(locations, observationDate, new PendingCalls(), null);
        }

        public Snapshot(Map<Long, Location> locations, LocalDate observationDate, PendingCalls pendingCalls) {
            this(locations, observationDate, pendingCalls, null);
        }
    }

    /** 仅隔离评测显式开启阶段记录，用于确定事实在提取还是写入时丢失，普通生产请求不收集原文。 */
    public static final class Trace {
        private final List<Map<String, Object>> entries = new ArrayList<>();

        public synchronized void add(String stage, Object data) {
            entries.add(Map.of("stage", stage, "data", data));
        }

        public synchronized List<Map<String, Object>> snapshot() {
            return List.copyOf(entries);
        }
    }

    public static void trace(String stage, Object data) {
        Snapshot snapshot = CURRENT.get();
        if (snapshot != null && snapshot.trace() != null) {
            snapshot.trace().add(stage, data);
        }
    }

    public static boolean isTracing() {
        Snapshot snapshot = CURRENT.get();
        return snapshot != null && snapshot.trace() != null;
    }

    /** Future 被取消不等于供应商 HTTP 已退出，评测收尾须等待实际工作线程结束。 */
    public static final class PendingCalls {
        private int active;

        public synchronized void begin() {
            active++;
        }

        public synchronized void end() {
            active--;
            notifyAll();
        }

        public synchronized int activeCount() {
            return active;
        }

        public synchronized boolean awaitIdle(long timeoutMillis) throws InterruptedException {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (active > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                wait(Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)));
            }
            return true;
        }
    }

    public static Snapshot current() {
        return CURRENT.get();
    }

    public static Scope restore(Snapshot snapshot) {
        Snapshot previous = CURRENT.get();
        if (snapshot == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(snapshot);
        }
        return new Scope(previous);
    }

    public static LocalDate observationDate() {
        Snapshot snapshot = CURRENT.get();
        return snapshot == null || snapshot.observationDate() == null ? LocalDate.now() : snapshot.observationDate();
    }

    public static String prefix(MemoryMessage message) {
        Snapshot snapshot = CURRENT.get();
        Location location = snapshot == null || message.messageId() == null ? null : snapshot.locations().get(message.messageId());
        if (location == null) {
            return "";
        }
        return "[历史会话=" + location.sessionId() + "; 对话发生时间=" + location.date() + "]\n";
    }

    public static final class Scope implements AutoCloseable {
        private final Snapshot previous;

        private Scope(Snapshot previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
