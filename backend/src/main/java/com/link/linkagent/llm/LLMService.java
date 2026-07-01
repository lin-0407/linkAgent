package com.link.linkagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.llm.usage.LlmApiUsageService;
import com.link.linkagent.util.LlmJsonUtil;
import com.link.linkagent.settings.service.RuntimeSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.concurrent.TimeUnit;

/**
 * LLM 调用服务层 —— 封装与 DeepSeek 模型的所有交互。
 * <p>
 * 在架构中的位置：位于业务层（AgentExecutor / 上层工具）与 Spring AI ChatClient 之间，
 * 承担三层核心职责：①统一调用入口（文本/结构化/指定模型）；②成本保护（Prompt 长度校验）；
 * ③失败回退（主 Provider 失败时自动对接 {@link LlmProviderManager} 的备用链）。
 * <p>
 * 核心设计决策：
 * <ul>
 *   <li><b>单一 ChatClient 实例</b>：所有调用复用同一个 Spring AI ChatClient，不同模型只通过
 *       options.model 覆盖，避免创建多余连接池和客户端对象。</li>
 *   <li><b>结构化输出归 LLM 层</b>：{@link #chatStructured} 放在本服务而非上层，因为结构化输出
 *       本质是"调模型"能力（利用 API 级 JSON 约束 + BeanOutputConverter schema 解析），
 *       归 LLM 层最自然，并复用这里的成本 guard（{@link #validatePromptLength}），不让上层各自持有 ChatClient 选项细节。</li>
 *   <li><b>流式/非流式分离</b>：当前阶段统一使用非流式同步调用（{@code .call().chatResponse()}），
 *       流式 SSE 响应在 Controller 层通过 ChatClient.stream() 直接处理，不在本服务封装。</li>
 *   <li><b>耗时统计</b>：每次 LLM 调用均以 {@code System.nanoTime()} 计时，用于后续成本分析和性能监控。</li>
 * </ul>
 */
@Service
public class LLMService {

    private static final Logger log = LoggerFactory.getLogger(LLMService.class);

    /**
     * 结构化解析失败的最大尝试次数。
     * <p>
     * 为什么是 3：DeepSeek 的 JSON 输出偶尔会出现字段名漂移或嵌套层级异常，这类问题是概率性的——
     * 单次重试有高概率恢复；连续 3 次失败说明 prompt 或 schema 本身有问题，继续重试只是浪费 Token。
     * 先用常量硬编码，等后续接入 Langfuse 观测真实重试率后再考虑外置为配置项。
     */
    private static final int STRUCTURED_MAX_ATTEMPTS = 3;

    /** Spring AI 的 ChatClient 实例，所有 LLM 调用（文本/结构化/指定模型）共用同一客户端连接池。 */
    private final ChatClient chatClient;

    /** Prompt 长度限制配置：在调用模型前短路超限请求，避免产生无效 Token 消耗。 */
    private final LlmCallGuardProperties guardProperties;

    /** 运行期设置服务：用于读取"成本保护开关"等可动态调整的配置，生产环境实时生效无需重启。 */
    private final RuntimeSettingService runtimeSettingService;

    /** LLM API 用量统计服务：记录每次调用的耗时、Token 消耗、成功/失败状态，供成本分析和告警。 */
    private final LlmApiUsageService llmApiUsageService;

    /** Jackson 反序列化器：结构化调用改为手动解析后，需要复用 Spring Boot 的全局 JSON 配置。 */
    private final ObjectMapper objectMapper;

    /**
     * 多 Provider 回退链管理器：当主 ChatClient 调用失败时，按配置顺序依次尝试备用 Provider。
     * 允许为 null——单测或不需要回退链的场景不注入此依赖。
     */
    private final LlmProviderManager llmProviderManager;

    /**
     * 无参构造器：供 Spring 框架代理（CGLIB）使用，不用于生产环境的实际注入。
     * 所有字段置为 null/默认值，防止误用——真正的初始化由主构造器完成。
     */
    protected LLMService() {
        this.chatClient = null;
        this.guardProperties = new LlmCallGuardProperties();
        this.runtimeSettingService = null;
        this.llmApiUsageService = null;
        this.objectMapper = new ObjectMapper();
        this.llmProviderManager = null;
    }

    /**
     * 主构造器：Spring 自动装配入口，注入所有 LLM 调用所需的依赖。
     *
     * @param builder Spring AI 提供的 ChatClient.Builder，此处调用 .build() 创建单例 ChatClient
     * @param guardProperties Prompt 长度限制等成本保护配置
     * @param runtimeSettingService 运行期设置服务（成本保护开关可在线启停）
     * @param llmApiUsageService API 用量统计服务
     * @param objectMapper Spring Boot 共享 JSON 解析器
     * @param llmProviderManager 多 Provider 回退链管理器，可为 null
     */
    @Autowired
    public LLMService(ChatClient.Builder builder,
                      LlmCallGuardProperties guardProperties,
                      RuntimeSettingService runtimeSettingService,
                      LlmApiUsageService llmApiUsageService,
                      ObjectMapper objectMapper,
                      LlmProviderManager llmProviderManager) {
        this.chatClient = builder.build();
        this.guardProperties = guardProperties;
        this.runtimeSettingService = runtimeSettingService;
        this.llmApiUsageService = llmApiUsageService;
        this.objectMapper = objectMapper;
        this.llmProviderManager = llmProviderManager;
    }

    /**
     * 测试用构造器：仅注入 guardProperties，其他依赖置为 null。
     * 用于单元测试中验证 Prompt 长度校验逻辑，无需启动完整的 Spring 上下文。
     *
     * @param guardProperties 成本保护配置（通常用 mock 或测试值）
     */
    LLMService(LlmCallGuardProperties guardProperties) {
        this.chatClient = null;
        this.guardProperties = guardProperties;
        this.runtimeSettingService = null;
        this.llmApiUsageService = null;
        this.objectMapper = new ObjectMapper();
        this.llmProviderManager = null;
    }

    /**
     * 最简文本对话：使用内置的默认 system prompt（编程助手角色），仅返回文本内容。
     * 适用于无需自定系统提示词的简单问答场景（如 /agent/chat 的兜底路径）。
     *
     * @param userMessage 用户输入文本
     * @return LLM 生成的文本回复
     */
    public String chat(String userMessage) {
        String systemPrompt = buildSystemPrompt();
        return chatWithUsage(systemPrompt, userMessage).content();
    }

    /**
     * 文本对话（使用外部构建的 system prompt），仅返回文本内容。
     * <p>
     * ReAct 专用重载：AgentExecutor 需要注入工具描述和格式约定到 system prompt，
     * 走此重载而非默认的 {@link #chat(String)}，让调用方完全控制 system prompt 的构建逻辑。
     *
     * @param systemPrompt 由调用方构建的完整系统提示词（含工具描述、格式约定等）
     * @param userMessage 用户输入文本
     * @return LLM 生成的文本回复
     */
    public String chat(String systemPrompt, String userMessage) {
        return chatWithUsage(systemPrompt, userMessage).content();
    }

    /**
     * 带用量统计的最简文本对话：使用内置 system prompt，返回含 Token 消耗和耗时的 {@link LlmCallResult}。
     * <p>
     * 适用于前端调用需要展示 Token 消耗信息的场景（如创作者工作台的成本透明展示）。
     *
     * @param userMessage 用户输入文本
     * @return 包含回复内容、模型名称、Token 消耗和耗时的完整结果
     */
    public LlmCallResult chatWithUsage(String userMessage) {
        String systemPrompt = buildSystemPrompt();
        return chatWithUsage(systemPrompt, userMessage);
    }

    /**
     * 带用量统计的文本对话（使用外部 system prompt）——LLM 调用的核心入口方法。
     * <p>
     * 所有 {@code chat} 和 {@code chatWithUsage} 重载最终都汇聚到此方法。保留旧的仅返回 String 的
     * chat 方法，是为了不强迫所有调用方一次性改造（渐进式升级策略）；新增的 chatWithUsage 系列
     * 先服务于评测系统和成本统计模块，等上游全部改造完成后再考虑废弃旧接口。
     * <p>
     * 故障转移机制：主 ChatClient 调用失败时，自动查询 {@link #llmProviderManager} 是否有可用备用
     * Provider——若有则执行回退链，若备用链也全挂才抛出原始异常（保持主 Provider 的失败原因供排查）。
     * 这个"不吞掉原始异常"的设计是为了：①日志中能看到主 Provider 为什么失败；②上游可以根据
     * 异常类型做差异化处理（如 400 Bad Request 可能是 Prompt 超长，不应重试）。
     *
     * @param systemPrompt 系统提示词（由调用方控制内容）
     * @param userMessage 用户输入文本
     * @return 包含回复内容、模型名称、Token 消耗和耗时的完整结果
     * @throws RuntimeException 主 Provider 失败且无备用 Provider 可用（或备用链全挂）
     */
    public LlmCallResult chatWithUsage(String systemPrompt, String userMessage) {
        validatePromptLength(systemPrompt, userMessage);
        long startNanos = System.nanoTime();
        try {
            ChatResponse chatResponse = chatClient
                    .prompt()
                    .system(systemPrompt)
                    .user(userMessage)
                    .call()
                    .chatResponse();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            LlmCallResult result = toCallResult(chatResponse, elapsedMs);
            recordTextSuccess(result);
            return result;
        } catch (RuntimeException exception) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            recordTextFailure(elapsedMs, exception);
            // 方案四：主 Provider 失败时尝试备用 Provider 回退链
            if (llmProviderManager != null && llmProviderManager.hasAvailableProvider()) {
                log.warn("主 LLM Provider 调用失败，尝试备用 Provider 回退链：{}", exception.getMessage());
                try {
                    LlmCallResult fallbackResult = llmProviderManager.tryFallback(systemPrompt, userMessage);
                    log.info("备用 Provider 回退成功，使用模型：{}", fallbackResult.modelName());
                    return fallbackResult;
                } catch (RuntimeException fallbackException) {
                    log.error("备用 Provider 回退链也全部失败", fallbackException);
                    // 抛出原始异常，保留主 Provider 的失败原因供排查
                }
            }
            throw exception;
        }
    }

    /**
     * 指定模型名称的文本对话（带用量统计），仅返回文本内容。
     * <p>
     * 用于自动补全、快速分类等场景调用轻量模型（如 dpv4flash），与主分析模型（如 deepseek-chat）区分。
     * 复用同一个 ChatClient 实例，只通过 options 覆盖 model 字段——不额外创建连接池或客户端对象，
     * 因为 Spring AI 的 ChatClient 本身是无状态的，.options() 只是做请求级覆盖，不会影响其他调用。
     * <p>
     * 注意：此方法不经过 Provider 回退链（llmProviderManager），因为轻量调用如果主 Provider 挂了，
     * 说明基础设施有问题，重试备用 Provider 大概率也是同样结果，直接快速失败让上游处理更合适。
     *
     * @param modelName 要使用的模型名称（如 "deepseek-chat"、"dpv4flash"），覆盖全局默认模型
     * @param systemPrompt 系统提示词
     * @param userMessage 用户输入文本
     * @return LLM 生成的文本回复
     * @throws RuntimeException 模型调用失败时直接向上抛出，不在此处吞掉
     */
    public String chatWithModel(String modelName, String systemPrompt, String userMessage) {
        validatePromptLength(systemPrompt, userMessage);
        long startNanos = System.nanoTime();
        try {
            ChatResponse chatResponse = chatClient
                    .prompt()
                    .system(systemPrompt)
                    .user(userMessage)
                    .options(OpenAiChatOptions.builder()
                            .model(modelName)
                            .build())
                    .call()
                    .chatResponse();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            LlmCallResult result = toCallResult(chatResponse, elapsedMs);
            recordTextSuccess(result);
            return result.content();
        } catch (RuntimeException exception) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            recordTextFailure(elapsedMs, exception);
            throw exception;
        }
    }

    /**
     * 流式文本对话：返回 Flux&lt;String&gt;，每个元素是一个增量文本块（token 级）。
     * <p>
     * 调用方通过订阅 Flux 获取逐步生成的文本，用于 SSE 实时推送场景。
     * 与 {@link #chat(String, String)} 的区别是使用 {@code .stream().content()} 而非 {@code .call().chatResponse()}，
     * 让文本以 token 粒度逐块产出，不必等待完整响应。
     * <p>
     * <b>注意：</b>此方法不经过 Provider 回退链（llmProviderManager），因为流式调用的回退逻辑
     * 需要更复杂的流合并处理，当前阶段先在主 Provider 失败时直接向上抛异常。
     *
     * @param systemPrompt 系统提示词
     * @param userMessage 用户输入文本
     * @return 流式的文本增量 Flux
     */
    public Flux<String> chatStreamContent(String systemPrompt, String userMessage) {
        validatePromptLength(systemPrompt, userMessage);
        return chatClient
                .prompt()
                .system(systemPrompt)
                .user(userMessage)
                .stream()
                .content();
    }

    /**
     * 结构化对话：让 LLM 产出受目标类型 schema 约束的强类型对象，替代「提示词哄 JSON + 正则/字符串截取」的低可靠方案。
     * <p>
     * <b>为什么需要结构化输出（阶段 5.4）</b>
     * 传统做法是在 prompt 里说"请返回 JSON 格式"然后用正则提取，但 DeepSeek 偶尔会：
     * ①在 JSON 外包裹解释文字；②字段名漂移（如 thought 写成 Thinking）；③嵌套层级异常。
     * 结构化输出从 API 级消除这些不确定性。
     * <p>
     * <b>确定性来自两层叠加</b>
     * <ol>
     *   <li>{@code response_format=json_object}：由 DeepSeek 在 API 级保证返回合法 JSON 语法
     *       （也满足 DeepSeek「prompt 必须含 json 关键字」的硬性要求，因为 {@code .entity(type)} 内部
     *       会把目标类型的 schema 指令注入 prompt——不额外要求调用方手动写 json）。</li>
     *   <li>{@code .entity(type)}：Spring AI 内部用 {@code BeanOutputConverter} 依据目标类型的字段
     *       生成 schema 指令追加到 prompt 末尾，并将 LLM 返回的 JSON 反序列化为强类型对象。</li>
     * </ol>
     * json_object 只保证语法、不保证字段——仍可能出现字段缺失或类型不匹配，因此解析失败时
     * 自动重试 {@link #STRUCTURED_MAX_ATTEMPTS} 次，每次都是新的 LLM 调用（独立采样，概率独立）。
     * <p>
     * <b>为什么放在 LLMService 而非 AgentExecutor</b>
     * 结构化输出本质是"调模型"能力，归 LLM 层最自然，并自然复用这里的成本 guard
     *（{@link #validatePromptLength}）；不让上层（AgentExecutor / 业务工具）各自持有 ChatClient
     * 的选项细节，保持关注点分离。泛型设计使 ReAct 步（{@link com.link.linkagent.core.ReActStep}）
     * 与业务 JSON（如建议 record）共用同一出口，无需维护两套结构化调用逻辑。
     * <p>
     * <b>Token 用量说明</b>
     * 当前实现不再直接调用 {@code .entity(type)}，而是显式注入 schema 格式要求后拿完整的
     * {@link ChatResponse}。这样既保留结构化解析的稳定性，也能从 metadata 中提取 usage。
     *
     * @param systemPrompt 系统提示词（由调用方构建，不含 schema 描述——schema 由 BeanOutputConverter 自动追加）
     * @param userMessage 用户输入文本
     * @param type 目标类型的 Class 对象（如 {@code ReActStep.class} 或业务 record.class）
     * @param <T> 目标类型泛型
     * @return 解析后的强类型对象，字段按目标类型定义
     * @throws RuntimeException 连续重试 {@link #STRUCTURED_MAX_ATTEMPTS} 次仍解析失败时抛出最后一次异常，
     *                          调用方应按场景兜底（如切文本 ReAct 或返回错误给用户）
     */
    public <T> T chatStructured(String systemPrompt, String userMessage, Class<T> type) {
        return chatStructuredWithUsage(systemPrompt, userMessage, type).entity();
    }

    /**
     * 带用量统计的结构化对话。
     * <p>
     * 为什么不用 Spring AI 的 {@code .entity(type)}：它会把响应转换成目标对象后只返回 entity，
     * 调用方拿不到 {@link ChatResponse} metadata，导致结构化 ReAct 的 token 用量无法追踪。
     * 本方法显式使用 {@link BeanOutputConverter#getFormat()} 注入格式要求，再通过
     * {@code .chatResponse()} 获取完整响应，最后由 Jackson 反序列化为目标类型。
     *
     * @param systemPrompt 系统提示词
     * @param userMessage 用户输入文本
     * @param type 目标结构化类型
     * @param <T> 目标结构化类型泛型
     * @return 结构化实体和本次调用 usage
     */
    public <T> StructuredCallResult<T> chatStructuredWithUsage(String systemPrompt, String userMessage, Class<T> type) {
        validatePromptLength(systemPrompt, userMessage);
        // 仅设 responseFormat；model 等默认项由 Spring AI 在模型层合并保留（见 5.4 文档 §9 运行期待确认）
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, null))
                .build();
        BeanOutputConverter<T> outputConverter = new BeanOutputConverter<>(type);
        String structuredUserMessage = appendStructuredFormat(userMessage, outputConverter.getFormat());
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= STRUCTURED_MAX_ATTEMPTS; attempt++) {
            long startNanos = System.nanoTime();
            try {
                ChatResponse chatResponse = chatClient
                        .prompt()
                        .system(systemPrompt)
                        .user(structuredUserMessage)
                        .options(options)
                        .call()
                        .chatResponse();
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                LlmCallResult callResult = toCallResult(chatResponse, elapsedMs);
                T entity = parseStructuredEntity(callResult.content(), type);
                recordTextSuccess(callResult);
                return new StructuredCallResult<>(
                        entity,
                        callResult.promptTokens(),
                        callResult.completionTokens(),
                        callResult.totalTokens(),
                        elapsedMs
                );
            } catch (RuntimeException ex) {
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                recordTextFailure(elapsedMs, ex);
                // json_object 只保证语法、不保证字段，偶发字段不符或空内容时重试；保留最后一次异常向上抛
                lastError = ex;
                log.warn("结构化输出第 {}/{} 次解析失败：{}", attempt, STRUCTURED_MAX_ATTEMPTS, ex.getMessage());
            }
        }
        throw lastError;
    }

    /**
     * 给结构化调用追加 schema 格式约束。
     * <p>
     * 这里把格式约束拼到 user message 末尾，是为了保持调用方已有 system prompt 不变，同时确保 DeepSeek
     * 明确看到 JSON 输出要求；如果用户消息为空，也会单独发送格式要求，避免缺少 json 关键字导致兼容接口拒绝。
     */
    private String appendStructuredFormat(String userMessage, String format) {
        String normalizedUserMessage = userMessage == null ? "" : userMessage;
        return normalizedUserMessage + "\n\n请严格按照以下 JSON schema 返回，不要输出 Markdown 或额外解释：\n" + format;
    }

    /**
     * 将模型返回文本解析为目标结构化对象。
     * <p>
     * 解析失败统一转为 IllegalArgumentException，是为了复用结构化调用已有的重试机制；
     * 对上层来说，字段不匹配和非 JSON 输出都属于“本次结构化响应不可用”。
     */
    private <T> T parseStructuredEntity(String content, Class<T> type) {
        try {
            return objectMapper.readValue(LlmJsonUtil.extractJsonObject(content), type);
        } catch (Exception exception) {
            throw new IllegalArgumentException("结构化输出解析失败：" + exception.getMessage(), exception);
        }
    }

    /**
     * 校验 Prompt 总长度是否超出限制——在调用模型前短路，把超限问题控制在业务层。
     * <p>
     * <b>为什么在调用前校验而非事后检查</b>
     * DeepSeek 的 API 在 prompt 超长时仍可能接受请求但返回截断结果，或者在中间件层返回 400——
     * 两种情况都浪费了网络 IO 和部分 Token。业务层提前校验可以把问题暴露给用户（"请精简输入"），
     * 而非让用户得到一个不完整的回答或神秘的 400 错误。
     * <p>
     * <b>安全下限保护</b>
     * Math.max(1, ...)：当 guardProperties.getMaxPromptChars() 被误配为 0 时（配置错误或未初始化），
     * 不会让所有请求都绕过校验——至少保留 1 个字符的下限，但实际场景中 1 字符意味着几乎所有请求
     * 都会被拒，这是一种"fail-safe"设计：配错配置宁可全部拒绝，也不悄无声息地放行成本。
     * <p>
     * 可见性：包级别（default）——仅 LLMService 自身和同包测试可见，外部不允许绕过校验直接调模型。
     *
     * @param systemPrompt 系统提示词
     * @param userMessage 用户输入文本
     * @throws ResponseStatusException (HTTP 400) 当总 prompt 长度超过限制时
     */
    void validatePromptLength(String systemPrompt, String userMessage) {
        if (!isGuardEnabled()) {
            return;
        }
        // 关闭保护必须显式设置 enabled=false，避免字符上限误配成 0 时反而绕过成本保护。
        int maxPromptChars = Math.max(1, guardProperties.getMaxPromptChars());
        int promptChars = safeLength(systemPrompt) + safeLength(userMessage);
        if (promptChars <= maxPromptChars) {
            return;
        }
        // 在调用模型前短路，是为了把超限问题控制在业务层，避免已经产生模型请求后才失败。
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "本次 AI 分析输入过长，当前限制为 " + maxPromptChars + " 个字符，请先精简文稿、评论或弹幕样例后重试。"
        );
    }

    /**
     * 判断成本保护（Prompt 长度校验）是否开启。
     * <p>
     * 遵循双层决策：生产环境走 RuntimeSettingService（在线热更无需重启），
     * 测试构造器未注入设置服务时回退到 guardProperties 的静态配置值。
     */
    private boolean isGuardEnabled() {
        // 测试构造器不注入设置服务时，回退原配置值，避免单元测试必须感知设置模块。
        return runtimeSettingService == null
                ? guardProperties.isEnabled()
                : runtimeSettingService.isLlmGuardEnabled();
    }

    /**
     * 安全取字符串长度：null 视为 0 长度，避免 NPE。
     * 用在 Prompt 长度校验中，因为 systemPrompt 和 userMessage 理论上不应为 null，
     * 但防御性编程接受任何输入（网关/前端可能传 null）。
     */
    private int safeLength(String text) {
        return text == null ? 0 : text.length();
    }

    /**
     * 将 Spring AI 的 ChatResponse 转换为本项目的统一结果对象 {@link LlmCallResult}。
     * <p>
     * 处理三个边缘情况：
     * <ol>
     *   <li>chatResponse 为 null：可能是 Spring AI 内部异常前的半成品，兜底返回空结果。</li>
     *   <li>metadata/model 为 null：不同供应商返回的 model 字段完整度不同，trimToNull 将空白转为 null。</li>
     *   <li>usage 为 EmptyUsage（token 全 0）：Spring AI 内部用 EmptyUsage 表示"未得到 usage"，
     *       这里转回 null，避免把"供应商未返回"误统计成"真实 Token 消耗为 0"（差额会在成本报表中产生误导）。</li>
     * </ol>
     *
     * @param chatResponse Spring AI 的原始响应对象
     * @param elapsedMs 从调用到收到响应的耗时（毫秒）
     * @return 统一格式的调用结果
     */
    private LlmCallResult toCallResult(ChatResponse chatResponse, long elapsedMs) {
        ChatResponseMetadata metadata = chatResponse == null ? null : chatResponse.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        // 不同模型供应商返回 usage 的完整度不一致；缺失时保留 null，比伪造 0 更利于后续排查统计口径。
        return new LlmCallResult(
                extractContent(chatResponse),
                metadata == null ? null : trimToNull(metadata.getModel()),
                extractPromptTokens(usage),
                extractCompletionTokens(usage),
                extractTotalTokens(usage),
                elapsedMs
        );
    }

    /**
     * 安全提取 Prompt Token 数量：Usage 缺失或为 EmptyUsage 时返回 null。
     * 包级可见：同包测试可以验证 Usage 提取逻辑的正确性。
     */
    Integer extractPromptTokens(Usage usage) {
        return isMissingUsage(usage) ? null : usage.getPromptTokens();
    }

    /**
     * 安全提取 Completion Token 数量：Usage 缺失或为 EmptyUsage 时返回 null。
     */
    Integer extractCompletionTokens(Usage usage) {
        return isMissingUsage(usage) ? null : usage.getCompletionTokens();
    }

    /**
     * 安全提取 Total Token 数量：Usage 缺失或为 EmptyUsage 时返回 null。
     */
    Integer extractTotalTokens(Usage usage) {
        return isMissingUsage(usage) ? null : usage.getTotalTokens();
    }

    /**
     * 判断 Usage 是否缺失：null 或 Spring AI 的 EmptyUsage（三项 token 全为 0/null）。
     * <p>
     * 为什么不能简单地检查 usage.getTotalTokens() > 0：
     * EmptyUsage 是 Spring AI 在未收到供应商返回 usage 时创建的特殊实现，其 getPromptTokens()
     * 等方法返回 0（而非 null）——如果只检查 total > 0，会把 EmptyUsage 视为”消耗了 0 个 Token”
     * 的真实记录，在后续统计中污染平均值和总额。这里转回 null，让上游消费方能准确区分
     * “供应商确实返回了 0”（几乎不可能）和”供应商未返回”（正常情况）。
     *
     * @param usage Spring AI 的 Usage 对象
     * @return true 表示 Usage 不可用，应视为 null
     */
    private boolean isMissingUsage(Usage usage) {
        if (usage == null) {
            return true;
        }
        // Spring AI 的 EmptyUsage 会把未知 usage 表达成 0；这里转回 null，避免把”供应商未返回”误统计成”真实消耗为 0”。
        return isZeroOrNull(usage.getPromptTokens())
                && isZeroOrNull(usage.getCompletionTokens())
                && isZeroOrNull(usage.getTotalTokens());
    }

    /**
     * 判断 Integer 值是否为 null 或 0——两者都视为”缺失”。
     */
    private boolean isZeroOrNull(Integer value) {
        return value == null || value == 0;
    }

    /**
     * 从 ChatResponse 中安全提取文本内容。
     * <p>
     * 防御性编程：ChatResponse → Generation → AssistantMessage 的链条中任一环节可能为 null
     *（如模型返回空响应、Spring AI 的流式中断场景等），逐级判空比直接链式调用更安全。
     *
     * @param chatResponse Spring AI 的响应对象，允许为 null
     * @return 提取的文本内容，空响应或 null 时返回空字符串 “”
     */
    private String extractContent(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null) {
            return "";
        }
        return chatResponse.getResult().getOutput().getText();
    }

    /**
     * 将空白的字符串转为 null：null 或 isBlank() 时返回 null，否则返回 trim 后的值。
     * <p>
     * 用在 model 名称字段：Spring AI 的 metadata.getModel() 可能返回空串而非 null，
     * 统一转为 null 使下游无需区分”空串”和”null”两种缺失语义。
     */
    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 记录文本调用的成功指标：模型名称、Token 消耗、耗时。
     * <p>
     * 调用前判空（llmApiUsageService、result）：测试构造器可能不注入用量服务，
     * 防御性检查避免 NPE 污染主调用链路——用量统计是旁路关注点，不应该影响 LLM 调用本身。
     */
    private void recordTextSuccess(LlmCallResult result) {
        if (llmApiUsageService == null || result == null) {
            return;
        }
        llmApiUsageService.recordTextSuccess(
                result.modelName(),
                result.promptTokens(),
                result.completionTokens(),
                result.totalTokens(),
                result.elapsedMs()
        );
    }

    /**
     * 记录 LLM 调用失败指标：耗时 + 异常。
     * <p>
     * 只在 llmApiUsageService 已注入时执行——测试场景可选择性注入 mock 用量服务来验证失败记录逻辑。
     */
    private void recordTextFailure(long elapsedMs, RuntimeException exception) {
        if (llmApiUsageService == null) {
            return;
        }
        llmApiUsageService.recordTextFailure(elapsedMs, exception);
    }

    /**
     * 构建默认的 system prompt（编程助手角色）。
     * <p>
     * 仅在调用方未显式提供 system prompt 时作为兜底使用。内容是固定的通用编程助手角色设定，
     * 适用于 /agent/chat 的非 ReAct 简单问答路径。ReAct 场景走 AgentExecutor 自定义的 system prompt。
     *
     * @return 默认的编程助手 system prompt 文本
     */
    private String buildSystemPrompt() {
        return """
                
                你是一名资深编程助手，精通 Java、Spring Boot 及主流技术栈。
                你的职责是帮助开发者解决技术问题、编写高质量代码、解释复杂概念。
                回答应准确、简洁、实用，在不确定时主动告知用户你的局限性。
                所有回答必须遵纪守法，拒绝生成恶意代码或协助非法行为。
                """;
    }
}
