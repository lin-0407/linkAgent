# 第 1 章 最简 Agent 模型：从 HTTP 调用到 ReAct 循环

> 本章故意不用 Spring AI，用最朴素的 HTTP 调用展示 Agent 骨骼。第 2 章再用 Spring AI 重写。

---

## 1.1 Agent 与"调用 LLM"的本质区别

- **普通调用**：用户问一句，模型答一句，结束。
- **Agent**：模型可以"决定下一步做什么"——调工具、再推理、查资料——直到任务完成。

Agent 的本质是一个 while 循环：把当前对话发给 LLM → 解析输出（是要工具还是最终回答）→ 执行动作 → 把结果塞回对话 → 下一轮。

---

## 1.2 V1：单次问答（非 Agent，仅展示 LLM HTTP 接口）

依赖：Jackson 2.17.0（`com.fasterxml.jackson.core:jackson-databind`），HTTP 用 Java 11+ 自带的 `java.net.http.HttpClient`。

```java
public class V1SingleShot {
    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String API_KEY = System.getenv("OPENAI_API_KEY");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public static void main(String[] args) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", "gpt-4o-mini");
        var messages = body.putArray("messages");
        messages.addObject().put("role", "user").put("content", "用一句话解释什么是 Agent");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        String reply = MAPPER.readTree(resp.body())
                .path("choices").get(0).path("message").path("content").asText();
    }
}
```

**关键点**：messages 是对话历史数组，模型每次调用无状态——不传历史就不记得。`choices[0].message.content` 是模型回复文本。V1 不是 Agent 因为模型只能"说话"不能"做事"。

---

## 1.3 V2：手写最简 ReAct 循环

ReAct（Yao et al. 2022）：让模型交替输出"思考（Thought）"和"行动（Action）"，每次行动后把观察结果（Observation）塞回去。

### 文本协议

需要工具时：
```
Thought: <思考>
Action: <工具名>
Action Input: <JSON参数>
```

任务完成时：
```
Thought: <思考>
Final Answer: <给用户的最终回答>
```

### 核心实现

Agent 拥有两个工具：`get_weather(city)`（假数据）、`get_time()`（系统时间）。

关键方法：
- `run(String question, int maxSteps)` — 主循环
- `callLlm(ArrayNode messages)` — HTTP 调用，temperature=0
- `parseFinalAnswer(String text)` — 正则 `(?s)Final Answer:\s*(.+)$`
- `parseAction(String text)` — 正则 `(?s)Action:\s*(\w+)\s*\n\s*Action Input:\s*(\{.*?\}|\{\s*\})`
- `executeTool(String name, String inputJson)` — switch 分发

### 记忆原理

LLM HTTP 接口无状态——"记忆"就是客户端维护对话数组 + 每轮整个重发。

| 方案 | 写法 | 序列化 | 安全性 |
|---|---|---|---|
| 字符串手拼 JSON | `"[{\"role\":\"user\",...}]"` | 不需要 | 极差 |
| `List<Map<String,Object>>` | 简洁 | 需 `mapper.writeValueAsString()` | OK |
| ArrayNode（本书选择） | `messages.addObject().put("role","user")` | 本身是 JSON 树节点 | 自动转义 |

第 N 轮发 [m1,m2,m3]，第 N+1 轮 add assistant+obs 后发 [m1,m2,m3,assistant,obs]。若每轮只发最后一条则模型看不到 system prompt 和历史，立刻瘫痪。Spring AI 的 `ChatMemory`/`MessageWindowChatMemory` 本质相同，额外提供 conversationId 分组、超长截断、持久化。

---

## 1.4 V2 暴露的问题

| # | 问题 | 后果 | 解决章节 |
|---|---|---|---|
| ① | 靠正则解析模型输出 | 鲁棒性差 | 第 3 章（原生 Tool Calling） |
| ② | 裸 HttpClient，无重试/超时分级/流式 | 生产不可用 | 第 2 章（ChatClient）+ 第 9 章 |
| ③ | 工具 switch 硬编码 | 难维护 | 第 3 章（`@Tool` 注解） |
| ④ | 没有记忆 | 不能多轮 | 第 4 章（ChatMemory） |
| ⑤ | 无知识库 | 幻觉严重 | 第 5 章（RAG） |
| ⑥ | 无规划 | 失败率高 | 第 6 章（Plan-and-Execute） |
| ⑦ | 无可观测性 | 无法排障 | 第 8 章（Observation） |
| ⑧ | 超最大步数硬终止 | 体验差 | 第 9 章 |

---

## 1.5 本章要点

- Agent 本质 = "调 LLM → 解析 → 执行 → 结果回填 → 再调 LLM" 的循环。
- ReAct 是最经典范式：模型同时输出思考和行动。
- 纯文本 + 正则的 ReAct 是教学版，生产用模型原生 tool_calling 字段（第 3 章）。
- **必备保险**：最大迭代次数、低 temperature、工具异常 catch 后以 Observation 形式回传模型。
- **记忆原理**：客户端持有对话数组 + 每轮原样重发。LLM 无状态。
