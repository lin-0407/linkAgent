package com.link.linkagent.assistant;

/** 工具在线程池执行，显式传递本轮任务范围，防止模型用历史任务标识创建错误操作。 */
public final class AssistantInvocationContext {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();
    private AssistantInvocationContext() { }
    public record State(String taskId, boolean allowProposals) { }
    public static State current() { return CURRENT.get(); }
    public static boolean isToolVisible(String name) {
        // 已接入的 MCP 工具可能有写入能力，平台助手只公开已明确审核的只读工具与提议工具。
        if (current()==null) return true;
        return switch (name) {
            case "platform_guide", "creator_task_context", "knowledge_search", "web_search", "propose_creator_action",
                 "platform_operations", "platform_query", "propose_platform_plan", "platform_skill", "platform_run_context" -> true;
            default -> false;
        };
    }
    public static Scope open(State state) {
        State previous = CURRENT.get();
        if (state == null) CURRENT.remove(); else CURRENT.set(state);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    public static void requireTask(String taskId, boolean proposal) {
        State state=current();
        if (state==null || state.taskId()==null || !state.taskId().equals(taskId)
                || (proposal && !state.allowProposals())) {
            throw new IllegalArgumentException("请先在工作台选择对应任务；工具只能查询或提议操作当前任务");
        }
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
}
