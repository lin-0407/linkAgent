# 阶段 1：ReAct 最小闭环

## 上下文

阶段 0.5 已验证 LLM 连通性（用户输入 → LLM → 返回结果）。本阶段进入项目核心——**自主实现 ReAct 编排内核**，让 Agent 能自主思考、调用工具、观察结果、得出结论。

此阶段完成后 Agent 将具备多步推理能力，是后续所有阶段（记忆、工具生态、RAG 等）的基础。

---

## 整体架构

```
用户 POST /api/agent/chat
  → AgentController
    → AgentExecutor.run("北京今天天气如何")
      → buildSystemPrompt(toolRegistry) → system prompt
      → LLMService.chat(systemPrompt, conversationText) → LLM response
        → 解析: "Thought:..., Action: web_search, Action Input: Beijing"
          → ToolRegistry.getTool("web_search").execute("Beijing") → "25°C"
          → 拼接 Observation 到 conversation
      → LLMService.chat(...) → LLM response
        → 解析: "Final Answer: 北京25°C，即77°F"
          → 循环终止，返回结果
```

### 交互流程示例

```
Iteration 1:
  LLM → "Thought: 需要查北京当前天气
          Action: web_search
          Action Input: Beijing"
  Tool → web_search("Beijing") → "25°C Partly cloudy"

Iteration 2:
  LLM → "Thought: 北京25°C，需要转华氏度
          Action: calculator
          Action Input: 25 * 9/5 + 32"
  Tool → calculator("25 * 9/5 + 32") → "77"

Iteration 3:
  LLM → "Thought: 已经拿到两个温度
          Final Answer: 北京今天气温 25°C，相当于 77°F。"
  → 退出循环，返回 AgentChatResponse
```

---

## 文件清单

### 新增 12 个文件

| 文件 | 包 | 说明 |
|---|---|---|
| `core/ToolCall.java` | `com.link.linkagent.core` | Tool 调用请求 record |
| `core/Observation.java` | `com.link.linkagent.core` | Tool 执行结果 record |
| `core/AgentStep.java` | `com.link.linkagent.core` | 单步 ReAct 迭代 record |
| `core/AgentExecutor.java` | `com.link.linkagent.core` | **ReAct 主循环，内联 system prompt 构建与循环状态管理** |
| `tool/Tool.java` | `com.link.linkagent.tool` | Tool 接口 |
| `tool/ToolRegistry.java` | `com.link.linkagent.tool` | Tool 注册中心 |
| `tool/builtin/DateTimeTool.java` | `com.link.linkagent.tool.builtin` | 当前日期时间 |
| `tool/builtin/CalculatorTool.java` | `com.link.linkagent.tool.builtin` | 数学表达式求值（SpEL） |
| `tool/builtin/WebSearchTool.java` | `com.link.linkagent.tool.builtin` | 天气查询（wttr.in） |
| `api/dto/AgentChatRequest.java` | `com.link.linkagent.api.dto` | Agent 聊天请求 DTO |
| `api/dto/AgentChatResponse.java` | `com.link.linkagent.api.dto` | Agent 聊天响应 DTO（含完整步骤追踪） |
| `api/controller/AgentController.java` | `com.link.linkagent.api.controller` | `POST /api/agent/chat` |

### 修改 1 个文件

| 文件 | 变更 |
|---|---|
| `llm/LLMService.java` | 新增 `chat(systemPrompt, userMessage)` 重载 |

---

## 核心设计

### 1. Core 抽象

**ToolCall（record）：**
- `String name` — 工具名
- `String arguments` — 参数字符串

**Observation（record）：**
- `String toolName` — 工具名
- `String result` — 执行结果或错误信息

**AgentStep（record）：**
- `int stepNumber` — 迭代序号
- `String thought` — LLM 推理过程
- `String action` — 动作名
- `String actionInput` — 动作参数
- `String observation` — 结果

### 2. System Prompt

`AgentExecutor.buildSystemPrompt()` 组织 prompt，列出可用工具，定义输出格式。DeepSeek 对中文 prompt 响应更稳定，故用中文：

```
你是一个乐于助人的助手，可以使用以下工具:
  - calculator: ...
  - datetime: ...
  - web_search: ...

请使用以下格式回复:
  Thought: 你对接下来要做什么的推理
  Action: 工具名称
  Action Input: 工具的输入内容

或者当你已经获得最终答案时:
  Thought: 我现在已经掌握了所需信息
  Final Answer: 你对Human的最终回复

规则:
  - 每次只使用一个工具。
  - 始终以"Thought:"开头来解释你的推理。
  - 使用工具时，必须同时包含"Action:"和"Action Input:"。
  - 当你掌握了足够的信息，就输出"Final Answer:"。
```

所有循环状态（conversation StringBuilder、steps 列表、iteration 计数器、finalAnswer）直接作为 `run()` 方法局部变量，不额外抽象。

### 3. ReAct 主循环

`AgentExecutor.run()` 实现的循环流程：

1. 构建系统提示词
2. conversation 以 `"Human:{userMessage}\n\n"` 开头
3. 进入 while(true) 循环：
   - `iteration++` → 若 `> MAX_ITERATIONS(10)` 则返回错误
   - 调用 LLM，传入完整对话历史
   - 解析 Thought → 为空则返回错误
   - 解析 Final Answer → 找到则返回成功
   - 解析 Action + Action Input → 失败则反馈错误让 LLM 重试
   - 执行工具得到 Observation → 将 Thought/Action/Observation 结构化拼回 conversation

### 4. 正则解析

四个正则 Pattern，用于从 LLM 自由文本中提取结构化信息：

| Pattern | 匹配内容 | 特殊标记 |
|---|---|---|
| `Final Answer:\s*(.*)` | 最终答案（跨行捕获） | `Pattern.DOTALL` |
| `Action:\s*(\w+)` | 工具名（字母/数字/下划线） | — |
| `Action Input:\s*(.+?)(?:\\n\|$)` | 工具入参（非贪婪到行尾） | — |
| `Thought:\s*(.*?)(?:\\n\|$)` | 推理过程 | — |

解析优先级：**Final Answer > Action > 无法解析**（找到 Final Answer 时即使同时有 Action 也忽略后者）。

### 5. Tool 接口与注册

```java
public interface Tool {
    String getName();
    String getDescription();
    String execute(String input);
}
```

Tool 实现用 `@Component` 标注，`ToolRegistry` 通过构造器注入 `List<Tool>` 自动收集，`@PostConstruct init()` 注册到 Map。

### 6. 三个内置工具

| 工具 | 实现 | 特点 |
|---|---|---|
| `datetime` | `LocalDateTime.now()` | 纯 JDK 无依赖 |
| `calculator` | `SpelExpressionParser` | Spring 自带表达式引擎 |
| `web_search` | `wttr.in` 免费 API | 无 Key 需求，返回纯文本 |

---

## Edge Cases 处理

| 场景 | 处理方式 |
|---|---|
| 迭代达到 MAX_ITERATIONS（10） | 返回 `stopReason="迭代次数超过上限"`，finalAnswer 置空 |
| Thought 解析为空 | 返回 `stopReason="LLM思考返回结果为空"`，终止循环 |
| Action 解析失败（缺失 Action Input 或格式错误） | 向 conversation 追加错误提示 `"ERROR：输出格式错误，请严格按照格式输出！"`，LLM 重试 |
| Tool 名称不存在 | 返回 `Observation("Error: tool 'xxx' not found")` |
| Tool 执行异常 | 捕获并返回 `Observation("Error: 异常信息")` |
| 网络请求失败 | 返回 `"Error: ..."` 给 LLM，由其决定后续 |

---

## 🎯 Learn by Doing 完成

**最大迭代终止策略**（由作者实现）：
- 选择方案A：达到 `MAX_ITERATIONS` 限制后直接返回错误提示
- `stopReason="迭代次数超过上限"`，`finalAnswer` 为 null
- 迭代上限检查放在循环开头（LLM 调用前），避免超限后还调一次 LLM

作者额外决策：
- Thought 为空时直接终止（不做重试），因为正则已经宽松到难以遗漏
- 格式错误时 `continue` 进入下一轮而非终止，给 LLM 修正机会

---

## 关键决策记录

1. **经典 ReAct 文本解析**，非 Spring AI 原生 Tool Calling — 体现原理掌握，模型无关
2. **Conversation-as-text**，非消息列表 — 每次迭代将完整历史作为单条消息发送
3. **Tool 自动注册** — `@Component` + `List<Tool>` 构造器注入
4. **System prompt 与循环状态均内联在 AgentExecutor** — 不提前抽象独立类
5. **步骤追踪** — `AgentChatResponse` 返回完整 `List<AgentStep>` 便于调试
6. **@Slf4j 日志** — 每轮迭代记录进度，便于排查问题

---

## 涉及知识点

- ReAct 循环原理：Thought → Action → Observation 迭代
- Java `record` 作为领域值对象
- 正则表达式解析非结构化 LLM 输出
- Spring `@Component` + `@PostConstruct` 自动注册模式
- SpEL 表达式求值（`CalculatorTool`）
- JDK `HttpClient` 调用外部 API（`WebSearchTool`）
- Spring AI `ChatClient` 多参数重载

## 验证方式

```bash
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "北京今天天气如何，37摄氏度转华氏度是多少"}'
```

预期：Agent 调用 `web_search` → `calculator` → 返回最终答案，`totalSteps >= 2`。
