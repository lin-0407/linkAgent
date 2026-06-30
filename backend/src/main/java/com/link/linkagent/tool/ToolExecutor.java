package com.link.linkagent.tool;

import com.link.linkagent.core.Observation;
import com.link.linkagent.core.ToolCall;
import com.link.linkagent.llm.usage.LlmUsageContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 工具执行器 —— 将工具调用从 ReAct 主循环中隔离出来，统一管理超时、重试和异常转换。
 * <p>
 * 在 Agent 架构中的位置：位于 ReAct 循环（{@code AgentExecutor}）和工具注册中心（{@code ToolRegistry}）之间。
 * Agent 只关心「调用工具、拿到结果」，不需要感知工具是否存在、是否有超时风险、外部 API 是否偶发失败——
 * 这些底层边界问题全部由 ToolExecutor 兜底，Agent 始终拿到一个可用的 {@link Observation}。
 * <p>
 * 核心设计决策：
 * <ul>
 *   <li><b>异步 + 超时</b>：每次工具执行通过 {@link CompletableFuture} 提交到公共线程池，并用
 *       {@link CompletableFuture#orTimeout orTimeout} 设置硬超时。这样即使工具内部卡死（阻塞 IO / 死循环），
 *       主 ReAct 循环也不会被拖垮——超时后自动抛出 {@link java.util.concurrent.TimeoutException}，
 *       进入重试或直接转为错误 Observation。</li>
 *   <li><b>重试策略</b>：通过 {@link ToolExecutionProperties#maxRetries()} 控制最大重试次数（默认 0，即不重试）。
 *       重试是「尽力而为」策略——捕获所有异常并重试，不区分是否可恢复，因为 LLM 后续可以通过 Observation
 *       中的错误信息自行调整参数或换用其他工具。如果所有重试都失败，取最后一次异常信息作为 Observation 返回。</li>
 *   <li><b>用量上下文传递</b>：工具执行和批量执行都运行在公共线程池的异步线程中，ThreadLocal 无法自动继承。
 *       因此每次异步执行前，必须显式捕获当前线程的 {@link LlmUsageContext} 并在新线程中恢复，
 *       否则工具内部若再调用 LLM（如 RAG 检索），用量记录将归属到错误的工作流步骤，影响 Langfuse 追踪。</li>
 * </ul>
 */
@Component
public class ToolExecutor {

    /** 工具注册中心，负责按名称查找工具实例。ToolExecutor 不关心工具从何而来（本地 / MCP），只做调用。 */
    private final ToolRegistry toolRegistry;
    /** 工具执行运行期配置：超时秒数、最大重试次数。由 Spring 配置属性注入，支持动态调整。 */
    private final ToolExecutionProperties properties;

    public ToolExecutor(ToolRegistry toolRegistry, ToolExecutionProperties properties) {
        this.toolRegistry = toolRegistry;
        this.properties = properties;
    }

    /**
     * 执行单个工具调用，含超时控制和最多 {@link ToolExecutionProperties#maxRetries()} 次重试。
     * <p>
     * 此方法是 Agent 最常用的工具调用入口。内部完成「查找工具 → 超时执行 → 重试 → 异常转 Observation」全流程，
     * 保证调用方始终拿到一个非 null 的 Observation（即使工具不存在或执行失败）。
     *
     * @param toolCall 工具调用请求，含工具名和参数字符串
     * @return 执行结果 Observation；成功时 result 为工具返回值，失败时 result 为 "Error: ..." 描述
     */
    public Observation execute(ToolCall toolCall) {
        return executeInternal(toolCall);
    }

    /**
     * 并发执行多个工具调用，等待全部完成后再返回结果列表。
     * <p>
     * 为什么用并发而非串行：在 ReAct 的一些衍生模式中（如并行取证的 Plan & Execute），LLM 可能同时需要
     * 多个独立工具的结果。串行等待所有工具会严重拖慢推理速度；并发执行将总耗时降低到最慢工具的耗时。
     * <p>
     * 注意：每个异步任务都会恢复主线程的用量上下文，确保所有工具的模型调用归属到同一个工作流步骤。
     *
     * @param toolCalls 工具调用列表
     * @return 与输入顺序一致的 Observation 列表（每个位置对应一个工具的 Result/FinalAnswer 之后的最终结果）
     */
    public List<Observation> executeAll(List<ToolCall> toolCalls) {
        // 提前捕获主线程的用量上下文——后续每个异步线程都需要恢复它，否则 ThreadLocal 丢失
        LlmUsageContext usageContext = LlmUsageContext.current();
        List<CompletableFuture<Observation>> futures = toolCalls.stream()
                .map(toolCall -> CompletableFuture.supplyAsync(() -> {
                    // 异步线程：恢复用量上下文（用 try-with-resources 保证 restore 后自动 restore 回原值）
                    try (LlmUsageContext.UsageScope ignored = LlmUsageContext.restore(usageContext)) {
                        return executeInternal(toolCall);
                    }
                }))
                .toList();
        // join() 会阻塞直到所有 future 完成（含超时），且会传播异常（含 TimeoutException）
        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    /**
     * 单次工具调用的内部实现：查找工具 → 带重试执行 → 异常转 Observation。
     * <p>
     * 重试循环的设计权衡：
     * <ul>
     *   <li>重试次数为 {@code 0}（默认）时仍执行一次（loop runs once），因为 attempt=0 走 first try，
     *      而不是「尝试 0 次即不执行」——语义是「最多重试 N 次」，而非「最多执行 N 次」。</li>
     *   <li>不区分异常类型：网络超时、服务拒绝、参数错误等所有异常统一捕获并重试。理由：LLM 看到 Observation
     *      中的错误信息后可以自行决定是否重试（换个参数）或换工具——如果 ToolExecutor 替 LLM 做了
     *      「可恢复性判断」，反而可能误判（例如参数错误本应失败让 LLM 修正，却被当作可重试重试了 N 次）。</li>
     *   <li>所有重试失败后，取最后一次异常的根因消息作为 Observation 的 result。
     *      这样 LLM 看到的是有意义的错误信息（如 "Connection refused"），而不是 "Exception"。</li>
     * </ul>
     *
     * @param toolCall 工具调用请求
     * @return 始终非 null 的 Observation；工具不存在时返回 "Error: tool 'xxx' not found"
     */
    private Observation executeInternal(ToolCall toolCall) {
        // 第一步：查找工具。如果不存在，直接返回错误 Observation，不触发重试（不存在就是不存在，重试无意义）
        Tool tool = toolRegistry.getTool(toolCall.name());
        if (tool == null) {
            return new Observation(toolCall.name(), "Error: tool '" + toolCall.name() + "' not found");
        }
        // 重试循环：attempt 从 0 开始，保证即使 maxRetries=0 也至少执行一次
        Exception lastException = null;
        for (int attempt = 0; attempt <= properties.maxRetries(); attempt++) {
            try {
                String result = executeOnce(tool, toolCall);
                // 成功即返回，不进入下一次循环
                return new Observation(toolCall.name(), result);
            } catch (Exception exception) {
                // 记录最后一次异常，用于重试耗尽后生成有意义的错误 Observation
                lastException = exception;
            }
        }
        // 重试耗尽：将最后一次异常转换为人类可读的错误消息，喂给 LLM
        return new Observation(toolCall.name(), "Error: " + resolveErrorMessage(lastException));
    }

    /**
     * 单次工具执行的原子操作：异步提交到公共线程池，设置硬超时，等待结果。
     * <p>
     * 为什么用 CompletableFuture + orTimeout 而非 {@link java.util.concurrent.ExecutorService#submit}
     * + {@link java.util.concurrent.Future#get(long, TimeUnit)}：
     * <ul>
     *   <li>{@code orTimeout} 是 Java 9+ 的原生 API，语义清晰（"等 N 秒，超时就抛异常"），
     *       比手动管理 future.cancel(true) + TimeoutException 更不易出错。</li>
     *   <li>超时后 CompletableFuture 自动标记为异常完成，调用链上的 {@code join()} 会直接抛出
     *       {@link java.util.concurrent.CompletionException} 包裹的 {@link java.util.concurrent.TimeoutException}，
     *       被外层重试循环统一捕获。</li>
     * </ul>
     * <p>
     * 用量上下文恢复的必要性：{@code supplyAsync} 将任务提交到公共 ForkJoinPool 或自定义线程池，
     * 线程切换后 ThreadLocal 丢失。如果工具内部又调用了 LLM（如 RAG 检索工具内部需要调用 Embedding 模型），
     * 没有用量上下文就无法追踪这次 LLM 调用归属到哪个工作流步骤——Langfuse 追踪链会断裂。
     *
     * @param tool     已查找到的工具实例
     * @param toolCall 工具调用请求
     * @return 工具执行的原始字符串结果
     */
    private String executeOnce(Tool tool, ToolCall toolCall) {
        // 在主线程捕获用量上下文——异步线程需要显式恢复
        LlmUsageContext usageContext = LlmUsageContext.current();
        return CompletableFuture
                .supplyAsync(() -> {
                    // 工具执行会切到公共线程池，必须显式恢复用量上下文，否则工具内部模型调用无法归属到工作流步骤。
                    try (LlmUsageContext.UsageScope ignored = LlmUsageContext.restore(usageContext)) {
                        return tool.execute(toolCall.arguments());
                    }
                })
                // 硬超时：超过配置秒数后 CompletableFuture 变为异常完成，join() 会抛出异常
                .orTimeout(properties.timeoutSeconds(), TimeUnit.SECONDS)
                // join() 阻塞等待结果；若超时或被工具执行本身抛出异常，join() 会传播异常（CompletionException 包裹）
                .join();
    }

    /**
     * 从异常中提取最有价值的错误消息，按优先级回落。
     * <p>
     * 提取策略（按优先级）：
     * <ol>
     *   <li>取根因（cause）的消息——通常是最底层的真实错误，如 "Connection refused: /127.0.0.1:8099"。
     *       如果异常被多层包装（如 CompletionException → TimeoutException），直接取原始异常的消息
     *       对 LLM 和人类都没有帮助（它不会说"超时了应该换参数"）。</li>
     *   <li>退而取异常自身的消息——当异常没有 cause 但自身有描述性消息时。</li>
     *   <li>最后兜底取类名（如 "NullPointerException"）——保证即使消息为 null，Observation 也有可读文本。</li>
     * </ol>
     *
     * @param exception 执行过程中捕获的异常（可能为多层包装异常）
     * @return 最适合展示给 LLM 的错误消息字符串
     */
    private String resolveErrorMessage(Exception exception) {
        // 优先取根因消息：因为工具异常大多被 CompletableFuture 的 CompletionException 包裹一层，
        // 真正有诊断价值的消息在 cause 链的末端（如网络超时、API 返回的错误码）
        Throwable cause = exception.getCause();
        if (cause != null && cause.getMessage() != null) {
            return cause.getMessage();
        }
        // 无 cause 时直接取异常自身的消息
        if (exception.getMessage() != null) {
            return exception.getMessage();
        }
        // 兜底：类名好歹比 null / 空串更可读
        return exception.getClass().getSimpleName();
    }
}
