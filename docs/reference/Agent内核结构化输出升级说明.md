# Agent 内核结构化输出升级说明（阶段 5.4）

> 对应开发文档 `/docs/develop/阶段5.4-Agent内核结构化输出升级.md`。本文件记录阶段 5.4 落地后的实现结构、开关方式、调用链路和验证重点。

## 1. 功能定位

阶段 5.4 把自研 ReAct 内核从「自由文本 + 正则解析」升级为「结构化每步输出」。

原来的 ReAct 循环要求模型按 `Thought / Action / Action Input / Final Answer` 文本格式输出，再由正则表达式解析。这个方式能跑通，但模型一旦格式漂移，就会解析失败。

新的结构化路径让模型每一步输出 `ReActStep` 对象：

| 字段 | 含义 |
|---|---|
| `thought` | 当前步骤的推理说明 |
| `action` | 要调用的工具名；如果已经能回答则为空 |
| `actionInput` | 工具输入；如果已经能回答则为空 |
| `finalAnswer` | 最终答案；非空时结束循环 |

这样做的价值是：Agent 仍然是项目自研循环和自研 `ToolRegistry` 编排，但每一步的模型输出不再依赖脆弱的文本正则。

## 2. 核心实现

### 2.1 `LLMService.chatStructured`

新增统一结构化调用入口：

```java
public <T> T chatStructured(String systemPrompt, String userMessage, Class<T> type)
```

它做三件事：

- 复用 `validatePromptLength`，继续受演示环境成本保护约束。
- 使用 `response_format=json_object`，让兼容 OpenAI 的模型返回合法 JSON。
- 使用 Spring AI 的 `.entity(type)` 把 JSON 转成强类型对象，解析失败最多重试 3 次。

这里没有走 Spring AI 原生 tool calling，因为本项目要保留自研 ReAct 循环和自研工具注册表。模型只负责产出下一步决策，真正的工具执行仍由 `ToolExecutor` 完成。

### 2.2 `ReActStep`

`core.ReActStep` 是阶段 5.4 的结构化步骤载体。

它提供两个判断方法：

- `isFinal()`：`finalAnswer` 非空时结束循环。
- `hasAction()`：非终止步且存在工具名时调用工具。

### 2.3 `AgentExecutor` 双路并存

`AgentExecutor` 使用结构化内核开关：

```properties
agent.kernel.structured.enabled=true
```

默认 `true`，通用 Agent 和任务内部推理优先走结构化 ReAct。这个开关已经接入设置面板运行期开关，修改后下一次 Agent 调用生效。

设置为 `true` 时：

1. `run()` 和 `runTask()` 进入结构化路径。
2. 每轮通过 `chatStructured(..., ReActStep.class)` 获取下一步。
3. 若有 `finalAnswer` 则结束。
4. 若有 `action` 则用同一套 `ToolExecutor` 执行工具。
5. 工具结果作为 `Observation` 追加回对话，供下一轮模型参考。

设置为 `false` 时，后端回退到原文本 ReAct 路径，作为结构化输出兼容性异常时的兜底。

## 3. 与阶段 5.3 的关系

阶段 5.3 已经新增 `runTask()`，为发布前优化 Agent 化准备「不读写会话记忆」的任务级推理入口。

阶段 5.4 让 `runTask()` 也能走结构化 ReAct。后续回到 5.3b 时，发布前优化可以基于更稳定的结构化内核做「先 Agent 取证，再结构化生成建议」。

## 4. 验证结论

根据阶段 5.5 文档记录，作者已在 2026-06-09 完成阶段 5.4 的编译与开关两态自测。后续已把结构化内核改为默认启用，并接入设置面板动态开关。

建议回归命令仍然是：

```bash
cd backend
mvn -q -DskipTests compile
```

判断标准：

- 默认不设置 `agent.kernel.structured.enabled` 时，`/api/agent/chat` 走结构化 ReAct 路径。
- 在设置面板关闭 `agent.kernel.structured.enabled` 后，`/api/agent/chat` 回退文本 ReAct 路径。
- 成本护栏仍然生效，超长输入会在调用模型前被拦截。

## 5. 维护注意

- 不要删除文本 ReAct 路径。它仍是结构化输出异常时的兜底路径。
- 新业务如果只需要内部取证，优先用 `runTask()`，避免污染用户会话记忆。
- 结构化输出失败不要在 `AgentExecutor` 里吞掉。`chatStructured` 已做重试，最终失败应暴露给调用方，方便定位模型或 schema 问题。
