package com.link.linkagent.llm;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;

/**
 * 一次真实模型请求的「信封」：决定供应商渲染出哪段 prompt 的全部请求形状与消息。
 *
 * <h3>为什么需要它</h3>
 * DeepSeek 的前缀缓存按「渲染后的 token 序列的连续前缀」命中，不是按对话文本命中。
 * 真实请求除了消息正文，还会把 tools 声明、thinking 开关、response_format 等一起渲染进 prompt；
 * 摘要生成如果只复述「system + 对话文本」，不带同样的工具/思考形状，前缀在正文之前就分叉，25 万 token 全部 miss。
 * 所以压缩时要用这份信封把上一轮的请求原样重放，只在末尾追加消息。
 *
 * <h3>为什么要把 messages 一起存下来</h3>
 * 真实请求的用户消息 = 历史消息块 + 本轮用户输入；压缩发生时本轮用户输入还没进短期记忆，
 * 如果按「被压缩消息」重新拼一遍前缀，历史块与用户输入之间的分隔/空行必然对不上，缓存同样会断。
 * 只有逐字节保存当时真正发出去的消息列表，重放才是严格前缀；代价是一个会话占一份请求文本的内存。
 *
 * <h3>为什么只有两种传输</h3>
 * 对话链路实际只有两条出口：普通 ChatClient（正式端点）和 strict Beta 客户端。
 * 结构化输出（strict 结果函数）和 json_object 会改写请求形状、改变模型输出格式，不适合当摘要调用的信封，
 * 因此 {@link #replayable()} 只认「没有强制函数选择」的请求。
 *
 * @param transport       出口：普通 ChatClient 或 strict Beta
 * @param systemPrompt    系统提示词（必须与真实请求逐字节一致）
 * @param messages        真实请求发出的消息列表（不含 system，system 由出口单独传）
 * @param tools           strict 出口的工具声明；普通出口为 null（工具在 options 里）
 * @param toolChoice      strict 出口的 tool_choice；摘要重放要求为 null（不强制函数）
 * @param disableThinking strict 出口是否关思考（strict 对话链路固定关）
 * @param maxTokens       strict 出口的输出上限；null 表示不限
 * @param options         普通出口的请求选项（含 thinking、tools、responseFormat 等），copy 后保存防止被调用方改动
 */
public record ChatRequestEnvelope(
        Transport transport,
        String systemPrompt,
        List<Message> messages,
        List<OpenAiApi.FunctionTool> tools,
        Object toolChoice,
        boolean disableThinking,
        Integer maxTokens,
        OpenAiChatOptions options
) {

    /** 请求出口。 */
    public enum Transport {
        /** 普通 ChatClient（正式端点，chat/chatWithUsage/streamWithToolsWithUsage 都走这里）。 */
        MAIN,
        /** DeepSeek strict Function Calling 客户端（Beta 端点）。 */
        STRICT_BETA
    }

    public ChatRequestEnvelope {
        transport = transport == null ? Transport.MAIN : transport;
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        // 消息列表必须立刻复制：真实请求发完后调用方还会往同一个 List 里追加 ReAct 工具消息，
        // 不复制的话「上一轮真实前缀」会被后续步骤改掉。
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        // options 是可变对象：复制一份，防止后续调用修改了缓存起来的请求形状。
        options = options == null ? null : options.copy();
    }

    /** 普通 ChatClient 出口（工具/思考等形状都在 options 里）。 */
    public static ChatRequestEnvelope main(String systemPrompt, List<Message> messages, OpenAiChatOptions options) {
        return new ChatRequestEnvelope(Transport.MAIN, systemPrompt, messages, null, null, false, null, options);
    }

    /** strict Beta 出口。 */
    public static ChatRequestEnvelope strict(String systemPrompt, List<Message> messages,
                                             List<OpenAiApi.FunctionTool> tools, Object toolChoice,
                                             boolean disableThinking, Integer maxTokens) {
        return new ChatRequestEnvelope(Transport.STRICT_BETA, systemPrompt, messages, tools, toolChoice,
                disableThinking, maxTokens, null);
    }

    public boolean strictTransport() {
        return transport == Transport.STRICT_BETA;
    }

    /**
     * 是否可用于摘要重放。
     * <p>
     * strict 结构化输出用 tool_choice 强制「结果函数」，模型只会返回函数参数、不会输出摘要正文；
     * 这种请求形状不能当摘要信封。普通出口的 json_object 由调用方保证不会进信封（见 AgentExecutor 捕获点）。
     */
    public boolean replayable() {
        return !strictTransport() || toolChoice == null;
    }
}
