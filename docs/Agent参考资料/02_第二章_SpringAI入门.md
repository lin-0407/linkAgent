# 第 2 章 用 Spring AI 重写第一章——ChatModel、ChatClient、Prompt、Message

> Spring AI 版本基线: **1.1.4**。
> - **1.0.5** — LTS 维护分支
> - **1.1.4** — 本书锁定
> - **2.0.0-M4** — Milestone，生产勿用。2.0 变化: Jackson 2→3、MCP 注解包重命名、`ToolContext` 移除 conversation history

**1.1 相对 1.0 的关键演进**:
1. 完整 MCP 支持（Client + Server）
2. 结构化 Advisors API: `AdvisedRequest/Response` → `ChatClientRequest/ChatClientResponse`
3. `ChatClient.entity(Class)` 原生结构化输出
4. JSpecify null 注解（Kotlin 互操作）
5. 20+ 模型供应商
6. `ThinkingConfig` / `ThinkingLevel` 控制推理深度
7. Claude Skills API + Files API 集成（1.1.4 backport）

---

## 2.1 核心抽象

| 抽象 | 对应第 1 章 |
|---|---|
| `ChatModel` | 调一次 LLM — 封装 HTTP/鉴权/序列化/错误处理 |
| `Prompt` / `Message` | 手写的 messages 数组（Java 对象封装） |
| `ChatClient` | fluent API 门面，ChatModel + Prompt 模板 + Advisor 链 |
| `ChatResponse` | `choices[0].message.content` 的 Java 对象版 |
| `Advisor` | 切面链，调 LLM 前后插入逻辑（记忆/RAG/日志） |
| `ToolCallback` | 工具的标准化封装 |

---

## 2.2 项目搭建

### 依赖 (pom.xml)

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.4</version>
</parent>

<properties>
    <java.version>17</java.version>
    <spring-ai.version>1.1.4</spring-ai.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- starter 命名: ≤1.0.0-M6 用 spring-ai-openai-spring-boot-starter(已废弃);
         ≥1.0.0-M7 用 spring-ai-starter-model-openai -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-openai</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
</dependencies>
```

### 配置 (application.yml)

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.openai.com        # 换供应商只改此行
      chat:
        options:
          model: gpt-4o-mini
          temperature: 0.2
```

> 设计决策: `base-url` 可切换——同一份代码，线上百炼/本地DeepSeek/CI Ollama，只换 yaml。

Starter 自动注册 `ChatModel`（具体类型 `OpenAiChatModel`）和 `ChatClient.Builder`，直接 `@Autowired` 即可。

---

## 2.3 V3: ChatModel 重写 V1（最底层）

```java
@Component
public class V3SingleShot implements CommandLineRunner {

    private final ChatModel chatModel;

    public V3SingleShot(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public void run(String... args) {
        UserMessage userMsg = new UserMessage("用一句话解释什么是 Agent");
        Prompt prompt = new Prompt(userMsg);
        ChatResponse response = chatModel.call(prompt);
        // ⚠️ 历史版本用过 getContent()，1.0 GA 后统一为 getText()
        String reply = response.getResult().getOutput().getText();
        System.out.println("模型说: " + reply);
    }
}
```

### V3 vs V1 对照

| 步骤 | V1（裸 HTTP） | V3（Spring AI） |
|---|---|---|
| 拼请求体 | 手动 `ObjectNode` | `new Prompt(new UserMessage(...))` |
| 发请求 | `HttpClient.send(...)` | `chatModel.call(prompt)` |
| 解响应 | `tree.path("choices").get(0)...` | `response.getResult().getOutput().getText()` |
| 鉴权 | 手写 `Authorization: Bearer` | yaml 自动注入 |
| 错误处理 | try/catch HTTP 异常 | 标准化 Spring AI 异常 |

---

## 2.4 V4: ChatClient fluent API

```java
@Component
public class V4ChatClientDemo implements CommandLineRunner {

    private final ChatClient chatClient;

    public V4ChatClientDemo(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultSystem("你是一个简洁的 Java 后端导师，回答控制在 30 字以内。")
                .build();
    }

    @Override
    public void run(String... args) {
        String reply = chatClient
                .prompt()
                .user("用一句话解释什么是 Agent")
                .call()
                .content();
    }
}
```

### 取结果方式速查

```java
// 1) 纯文本
String text = chatClient.prompt().user("...").call().content();

// 2) 完整响应（含 token 消耗、finishReason）
ChatResponse resp = chatClient.prompt().user("...").call().chatResponse();
int tokens = resp.getMetadata().getUsage().getTotalTokens();

// 3) 流式输出
Flux<String> stream = chatClient.prompt().user("...").stream().content();

// 4) 结构化输出 — 直接映射 Java 对象
record Joke(String setup, String punchline) {}
Joke j = chatClient.prompt().user("讲一个 Java 笑话").call().entity(Joke.class);
```

### PromptTemplate

```java
PromptTemplate tpl = new PromptTemplate("""
    你是 {role}，请用 {style} 风格回答:
    {question}
""");

String reply = chatClient.prompt(tpl.create(Map.of(
        "role", "Java 后端导师",
        "style", "简洁",
        "question", "什么是 Agent"
))).call().content();
```

> 工程建议: prompt 模板放 `src/main/resources/prompts/*.st`，用 `@Value("classpath:prompts/xxx.st") Resource r` 读入构造 `new PromptTemplate(r)`，改 prompt 不动 Java 代码。

---

## 2.5 V5: 用 Spring AI 重写 ReAct 循环

保留正则解析方案，底层换 Spring AI。ChatClient 只解决"调一次模型"，不解决自定义文本协议解析——第 3 章换原生 tool_calls。

```java
@Component
public class V5ReActAgent {

    private static final String SYSTEM_PROMPT = """
        你是一个能调用工具的助手。可用工具：
          - get_weather(city: string)
          - get_time()

        每一步必须严格按以下格式之一输出：
        【调工具】
        Thought: <思考>
        Action: <工具名>
        Action Input: <JSON>
        【可回答】
        Thought: <思考>
        Final Answer: <最终回答>
        """;

    private final ChatClient chatClient;

    public V5ReActAgent(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public String run(String question, int maxSteps) {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(SYSTEM_PROMPT));
        history.add(new UserMessage(question));

        for (int step = 1; step <= maxSteps; step++) {
            String llmOutput = chatClient.prompt().messages(history).call().content();
            history.add(new AssistantMessage(llmOutput));

            String finalAnswer = parseFinalAnswer(llmOutput);
            if (finalAnswer != null) return finalAnswer;

            String[] action = parseAction(llmOutput);
            if (action == null) {
                history.add(new UserMessage("你的输出格式不正确，请重新输出。"));
                continue;
            }

            String observation = executeTool(action[0], action[1]);
            history.add(new UserMessage("Observation: " + observation));
        }
        return "[Agent 终止]：超过最大步数 " + maxSteps;
    }

    private String executeTool(String name, String inputJson) {
        try {
            return switch (name) {
                case "get_time" -> LocalDateTime.now().toString();
                case "get_weather" -> {
                    String city = inputJson.replaceAll(".*\"city\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                    yield city + " 当前晴，22℃，东南风 3 级";
                }
                default -> "Error: 未知工具 " + name;
            };
        } catch (Exception e) {
            return "Error: 工具执行异常 " + e.getMessage();
        }
    }

    private String parseFinalAnswer(String text) {
        Matcher m = Pattern.compile("(?s)Final Answer:\\s*(.+)$").matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private String[] parseAction(String text) {
        Matcher m = Pattern.compile(
                "(?s)Action:\\s*(\\w+)\\s*\\n\\s*Action Input:\\s*(\\{.*?\\}|\\{\\s*\\})"
        ).matcher(text);
        if (!m.find()) return null;
        return new String[]{ m.group(1).trim(), m.group(2).trim() };
    }
}
```

V5 与 V2 核心结构一致（持有历史 + 每轮全发 + 正则解析 + 执行工具），只是 HTTP/序列化换成了 Spring AI。

---

## 2.6 ChatClient vs ChatModel

ChatClient（高层 fluent API，默认 system/options、挂载 Advisor 链、注入 ToolCallback）底层调 ChatModel（底层引擎: `call(Prompt) → ChatResponse`, `stream(Prompt) → Flux<ChatResponse>`）。

**经验法则**: 业务代码 99% 用 `ChatClient`；写框架级代码（自定义 Advisor、底层埋点）才直接用 `ChatModel`。

### 版本 API 迁移速查

| 旧 API | 新 API（1.0 GA / 1.1） | 版本 | 影响 |
|---|---|---|---|
| `spring-ai-openai-spring-boot-starter` | `spring-ai-starter-model-openai` | M7 | 全书 |
| `chatClient...tools(toolCallback)` | `chatClient...toolCallbacks(toolCallback)` | M8 | 第3章 |
| `chatClient...tools("name")` | `chatClient...toolNames("name")` | M8 | 第3章 |
| `AdvisedRequest` / `AdvisedResponse` | `ChatClientRequest` / `ChatClientResponse` | 1.0 GA | 第4章 |
| `getContent()` on AssistantMessage | `getText()` | 1.0 GA | 全书 |
| `spring.ai.chat.memory.jdbc.initialize-schema` | `spring.ai.chat.memory.repository.jdbc.initialize-schema` | 1.1 | 第4章 |
| `DocumentCompressor` / `DocumentRanker` | `DocumentPostProcessor` | 1.0 GA | 第5章 |

> Arconia 维护了 OpenRewrite 配方可自动迁移，搜索 `arconia spring-ai migrations`。

---

## 2.7 V5 暴露的问题（引出第 3 章）

| 问题 | 现状 | 第 3 章方案 |
|---|---|---|
| 正则解析模型输出 | 模型抽风→Agent 挂 | OpenAI 原生 `tool_calls` 字段，框架自动解析 |
| 工具 switch 硬编码 | 加工具改三处 | `@Tool` 注解 + Spring AI 反射注册 |

Spring AI ChatClient 原生支持 tool calling 自动多轮循环。

---

## 2.8 本章小结

- Spring AI 推荐入口 `ChatClient`，底层 `ChatModel`。
- `Prompt`/`Message` 是第 1 章 messages 数组的对象化。
- 切换模型供应商只需改 `application.yml` 的 `base-url` 和 `model`。
- V5 核心循环结构（持有历史 + 每轮全发 + 解析 + 执行工具）完全保留——Spring AI 只替你做"调一次模型"，不替你做循环。
