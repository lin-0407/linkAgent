# 第 3 章 工具调用（Tool Calling）

> Spring AI 1.1.4。Tool Calling 用模型原生 `tool_calls` 协议替代手写正则解析 + switch 分发 + while 循环。Spring AI 自动完成 schema 生成、反射调用、多轮循环。

---

## 3.1 原生 Tool Calling 协议：4 个 HTTP 阶段

**阶段1 - 客户端请求**：POST `/chat/completions`，`messages` 数组外附加 `tools` 字段（JSON Schema 数组，每个工具含 `type:"function"`、`function.name`、`function.description`、`function.parameters`）。

**阶段2 - 模型回 tool_calls**：响应 `choices[0].message` 中 `content` 为 null，`tool_calls` 数组每项含 `id`（如 `call_abc123`）、`type:"function"`、`function.name`、`function.arguments`（JSON 字符串）。

**阶段3 - 客户端执行并回传**：本地执行工具后，将结果作为 `role:"tool"` 消息追加到 messages，必须带 `tool_call_id` 与原调用配对。多工具并行调用时 id 是唯一关联线索。

**阶段4 - 模型给最终答案**：模型综合工具返回后输出 `content`。

关键点：模型只输出"想调哪个工具、参数是什么"，**不真正执行**。执行永远在服务端。多步任务会在阶段 2-3 之间反复循环。工具执行失败也要回传——把错误字符串作为 tool 消息 content，模型会自行决定换路径或放弃。

---

## 3.2 Spring AI 替你做的事

| 你写的 | Spring AI 内部 |
|---|---|
| `@Tool` 注解 + 普通方法 | 反射读签名 + Javadoc + `@ToolParam`，生成 OpenAI JSON Schema |
| `chatClient.prompt().tools(bean).call()` | 阶段1: 把 tools 塞进请求 → 阶段2: 收 tool_calls → 阶段3: 反射调方法，返回值序列化为 tool 消息 → 阶段4: 再请求，循环直到无 tool_calls → 返回最终 content |

---

## 3.3 @Tool 注解

### 工具类

```java
@Component
public class WeatherTools {
    @Tool(description = "返回当前服务器时间，ISO-8601 字符串格式。无参。")
    public String getCurrentTime() {
        return LocalDateTime.now().toString();
    }

    @Tool(description = "查询指定中国城市的实时天气，返回中文摘要。")
    public String getWeather(
            @ToolParam(description = "城市中文名，例如 北京、上海、杭州") String city
    ) {
        return city + " 当前晴，22℃，东南风 3 级";
    }
}
```

设计要点：
- `@Tool(description=...)` 会原样进 JSON Schema，模型靠它决定是否调工具。
- 方法参数用基本类型/String/简单 record。`@ToolParam(description=...)` 强烈建议写。
- 方法可抛异常——Spring AI 默认捕获并回传给模型。

### Agent 入口（无 while 循环）

```java
@Component
public class V6ToolAgent {
    private final ChatClient chatClient;
    private final WeatherTools tools;

    public V6ToolAgent(ChatClient.Builder builder, WeatherTools tools) {
        this.chatClient = builder.build();
        this.tools = tools;
    }

    public String run(String question) {
        return chatClient.prompt()
                .tools(tools)
                .user(question)
                .call()
                .content();
    }
}
```

一次 `.call()` 内部可能跑多轮：模型决定调工具 → Spring AI 反射调用 → 结果塞回 → 再请求 → 直到模型给最终答案。

---

## 3.4 异常处理与 ToolContext

### 3.4.1 异常处理：永远返回字符串，不抛异常

**硬规则：`@Tool` 方法必须 try/catch 兜底，永远返回 String。** 抛异常会导致堆栈进 prompt——模型读不懂、行为不稳定、泄露内部路径/类名/数据库信息给第三方 LLM。

模板：

```java
@Tool(description = "查询指定中国城市的实时天气")
public String getWeather(@ToolParam(description = "城市中文名") String city) {
    try {
        if (!isSupportedCity(city)) {
            return "未支持的城市：" + city + "。当前仅支持中国大陆地级市以上。";
        }
        return weatherClient.fetch(city);
    } catch (TimeoutException e) {
        log.warn("getWeather timeout, city={}", city);
        return "天气服务超时，请稍后重试。";
    } catch (Exception e) {
        log.error("getWeather unexpected, city={}", city, e);
        return "天气服务暂时不可用。";
    }
}
```

原则：**异常给运维看（日志），字符串给模型看（返回值）。**

### 3.4.2 ToolContext：让工具拿到隐含上下文

**场景**：工具方法需要 userId/tenantId 等上下文，但**绝对不能**让模型看见（防注入、防越权、省 token）。不能写成 `@ToolParam`（会进 schema 暴露给模型），不能放 Bean 字段（单例并发覆盖），不能用 ThreadLocal（异步/流式不安全）。

**正确方案：`ToolContext`** —— Spring AI 专为此设计。`ToolContext` 类型参数被反射时跳过，不进 schema 不进 messages，框架内部绑定到调用链路（不依赖 ThreadLocal），异步/流式/跨线程安全。

```java
import org.springframework.ai.chat.model.ToolContext;

@Tool(description = "查询当前登录用户最近 N 条订单")
public List<Order> myRecentOrders(
        @ToolParam(description = "条数，1-20") int limit,
        ToolContext ctx
) {
    Long userId = (Long) ctx.getContext().get("userId");
    return repo.findRecent(userId, limit);
}
```

调用方塞值：

```java
chatClient.prompt()
    .tools(orderTools)
    .toolContext(Map.of("userId", currentUser.getId(), "tenantId", currentTenant.getId()))
    .user(question)
    .call()
    .content();
```

**该放 ToolContext 的**：用户身份（userId/tenantId/roleId）、会话/追踪 ID、区域/语言/时区、资源句柄引用（非密钥本身）。
**不该放的**：模型应自己决策的业务参数（用 `@ToolParam`）、大对象（放 ID 方法内查）、模型需要看见的信息（放 prompt）。

---

## 3.5 动态工具选择：三种注册方式

### 底层统一接口：`ToolCallback`

```java
public interface ToolCallback {
    ToolDefinition getToolDefinition();   // name + description + JSON Schema
    String call(String toolInput);        // 输入参数 JSON，返回字符串
}
```

| 注册方式 | Schema 来源 | 执行逻辑来源 | 确定时机 | 适用场景 |
|---|---|---|---|---|
| `tools(bean)` | 反射 `@Tool` 方法签名 | 反射调 Java 方法 | 编译期 | 99% 业务 |
| `toolNames("x")` | 同上（按名查 `@Bean ToolCallback`） | 同上 | 编译期 | 跨 Agent 共享工具集 |
| `toolCallbacks(cb)` | **你自己构造** | **你自己写** | **运行时** | 工具由数据驱动 |

### 三种写法

```java
// 方式 A：传 Bean 实例（最常用）
chatClient.prompt().tools(weatherTools, orderTools)...

// 方式 B：toolNames，按名字引用
chatClient.prompt().toolNames("getWeather")...

// 方式 C：toolCallbacks，运行时构造
ToolCallback dynamic = new ToolCallback() {
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("query_users").description("按条件查询用户表")
            .inputSchema("""
                { "type":"object",
                  "properties":{
                     "name":{"type":"string"},
                     "age":{"type":"integer"} } }
                """).build();
    }
    public String call(String toolInput) {
        Map<String,Object> p = parseJson(toolInput);
        return jdbc.queryForList(
            "SELECT * FROM users WHERE name=? AND age=?",
            p.get("name"), p.get("age")).toString();
    }
};
chatClient.prompt().toolCallbacks(dynamic)...
```

辅助 builder：
- `MethodToolCallback.builder()` — 执行体是 Java 方法，schema 自己拼
- `FunctionToolCallback.builder()` — 执行体是 `Function<I,O>` / `BiFunction` lambda

判定规则：工具集被业务数据驱动（用户配置、外部协议、表结构）→ 必须用 `toolCallbacks`。`@Tool` 是声明式（工具是源代码一部分），`ToolCallback` 是命令式（工具是数据一部分）。

> M7→M8 变更：旧版 `.tools(toolCallback)` / `.tools("name")` 已拆分为 `.toolCallbacks(...)` 和 `.toolNames(...)`。

---

## 3.6 自动循环边界

Spring AI 默认 `internalToolExecutionEnabled = true`：收 tool_calls → 反射调用 → 再请求，循环直到无 tool_calls。

### 关闭自动执行

```java
import org.springframework.ai.model.tool.ToolCallingChatOptions;

ChatResponse resp = chatClient.prompt()
        .tools(myTools)
        .options(ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build())
        .user(question)
        .call()
        .chatResponse();

List<AssistantMessage.ToolCall> calls = resp.getResult().getOutput().getToolCalls();
// 自行决定：弹窗确认？写工单？发 MQ？确认后手动构造 ToolResponseMessage 发起下一轮
```

自动循环的本质：`ChatClient.prompt().call()` 内部等价于手写 while 循环——收到 tool_calls 就执行、塞回历史、再请求，退出条件是响应里无 tool_calls。Spring AI 1.1.x 未将最大轮数做成顶层配置，可通过 Advisor 拦截器计数，建议硬上限 6-8 轮。

---

## 3.7 常见坑

1. **description 太抽象**：模型乱调或漏调。写清楚"什么时候用、不能用什么、参数边界"。
2. **返回 Java 对象序列化失败**：加 `@JsonProperty`，避免循环引用。
3. **List 参数模型易传错格式**：能用单参数就不用 List，必要时用 record 包一层。
4. **方法重载**：`@Tool` 不支持同名重载，用 `@Tool(name="...")` 显式指定。
5. **Schema 不带 description**：每个参数务必加 `@ToolParam(description=...)`，否则模型只能猜。
6. **工具返回值过长**：超几 KB 会让下轮 token 暴涨。返回摘要 + 资源 ID，需要时让模型再调工具按 ID 取详情。
7. **忘关 internalToolExecutionEnabled** 就自己做循环 → 双重执行。
8. **ToolContext 里放大对象** → 只放 ID，方法内查详情。

---

## 3.8 API 速查

| API | 说明 |
|---|---|
| `@Tool(description="...")` | 标记工具方法，description 进 schema |
| `@ToolParam(description="...")` | 参数说明，进 schema |
| `ChatClient.prompt().tools(Object... beans)` | 传 Bean，反射扫描 `@Tool` 方法 |
| `.toolNames(String... names)` | 按 Bean 名引用 ToolCallback |
| `.toolCallbacks(ToolCallback... cbs)` | 运行时构造的工具 |
| `.toolContext(Map<String,Object>)` | 注入隐式上下文，不进 schema |
| `ToolContext.getContext().get("key")` | 工具方法内取值 |
| `ToolCallingChatOptions.builder().internalToolExecutionEnabled(false)` | 关闭自动循环 |
| `ChatResponse.getResult().getOutput().getToolCalls()` | 手动获取 tool_calls |
| `ToolDefinition.builder().name().description().inputSchema()` | 程序化构造工具定义 |
| `ToolCallback.getToolDefinition()` / `.call(String)` | 自定义工具接口 |
| `MethodToolCallback.builder()` | 执行体=Java方法，schema 自拼 |
| `FunctionToolCallback.builder()` | 执行体=Function/BiFunction lambda |
