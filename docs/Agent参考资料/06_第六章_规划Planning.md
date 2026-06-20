# 第 6 章 规划（Planning）：从隐式 ReAct 到 Plan-and-Execute

> 从 V19 演化到 V22：让 Agent 在调工具之前先想清楚步骤。核心收获是学会在 ReAct 和 Plan-and-Execute 之间选型。
> 版本基线：Spring AI **1.1.4**。

---

## 6.1 问题：复合任务暴露的规划缺失

用户一句话塞进 5 件事：拒收退款 + 退款到账后重购 + 无货则预约 + 短信通知。V18 自动循环跑 6 轮 LLM、5 次工具执行——结果**漏了 sendSMS**，并且忽略了"退款到账后再下单"这个异步业务约束。

**三个先天问题**：

| # | 问题 | 表现 |
|---|---|---|
| ① | 无全局视图——只看"上一步"决定"下一步" | 注意力散失，漏 sendSMS |
| ② | 无完成度校验 | 模型自认"做完了"，无人对照用户原话打勾 |
| ③ | 无业务约束注入点 | "退款到账后再下单"无处安放 |

**ReAct vs Plan-and-Execute**：ReAct 每步现想、适合边界不清需要试错；PaE 先一次性吐完整计划再逐步执行、适合边界清晰可枚举多步。

### 本章演化路径

| 版本 | 改动 | 暴露的新问题 |
|---|---|---|
| **V19** | 朴素 ReAct——复合任务交 ChatClient 自动循环 | 步数失控、中间步看不到、无法干预 |
| **V20** | 手写 ReAct 循环——`internalToolExecutionEnabled(false)` + `ToolCallingManager` | 每步自由发挥，复杂任务跑偏 |
| **V21** | Plan-and-Execute——`BeanOutputConverter` 让模型先吐 JSON 计划 | 计划僵化，中途出错全卡死 |
| **V22** | Re-Planning——执行中允许重规划 | 本章收口 |

---

## 6.2 V19 暴露的四个问题

三层分类：**循环控制层**（②③→V20）、**全局视图层**（①→V21）、**约束注入层**（④→V21+V22）

- **问题① 完成度无校验——漏步全凭"模型自觉"**：解法是让模型在动手前先吐一份结构化计划，把这份计划绑成 Java 对象，执行器拿到一份业务代码能遍历的清单逐条打勾
- **问题② 循环上限不在业务手里**：解法是 `internalToolExecutionEnabled(false)` 关闭框架自动循环，业务代码自己写 while
- **问题③ 中间步对业务不可见、不可干预**：解法是 V20 手写循环 + `ToolCallAdvisor` 做切面化
- **问题④ 跨工具业务约束无处安放**：解法是在计划里用 `dependsOn`/`blockUntil` 表达依赖

---

## 6.3 V20：手写 ReAct 循环

### 五个关键 API

```java
// ① 关掉框架自动循环
ToolCallingChatOptions options = ToolCallingChatOptions.builder()
        .toolCallbacks(toolCallbacks)
        .internalToolExecutionEnabled(false)
        .build();

// ② 执行工具调用的标准组件（Spring Boot autoconfig 自动注入）
ToolCallingManager toolCallingManager;

// ③ 判断本轮是否继续
chatResponse.hasToolCalls();
chatResponse.getResult().getOutput().getToolCalls(); // List<AssistantMessage.ToolCall>

// ④ 执行结果——自动拼好 AssistantMessage(toolCalls) 和 ToolResponseMessage
ToolExecutionResult execResult = toolCallingManager.executeToolCalls(prompt, chatResponse);
execResult.conversationHistory();        // List<Message>，record 风格访问器（无 get 前缀）
execResult.returnDirect();               // 工具是否要求绕过模型直接返回

// ⑤ 拼下一轮 Prompt
new Prompt(execResult.conversationHistory(), options);
```

### @Tool bean 打包成 ToolCallback

```java
// 方式 A：ad-hoc 转换（开发期）
ToolCallback[] callbacks = ToolCallbacks.from(orderTools, refundTools, notificationTools);

// 方式 B：标准 SPI（生产推荐）——对 CGLIB 代理友好
ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
        .toolObjects(orderTools, refundTools, notificationTools)
        .build().getToolCallbacks();
```

### V20 完整的 while 循环

用 `ChatModel.call(prompt)` + `ToolCallingManager` + 业务侧 while。每轮判断 `response.hasToolCalls()`，有则 `toolCallingManager.executeToolCalls()` → 拼下一轮 Prompt → 再调 ChatModel。`MAX_ITERATIONS = 10` 兜底。

**V20 真正的胜利**：输出和 V19 几乎没区别——但业务代码里能加审批卡点、审计日志、强制上限了。代价是临时丢掉三 Advisor 链（记忆/RAG/摘要）。

---

## 6.4 V20 暴露的问题——丢掉 advisor 链怎么办

V20 直接用 `ChatModel.call(prompt)`——绕过 ChatClient = 绕过整条 Advisor 链。三 Advisor 链全断、while 体越塞越胖、跨 Agent 复用难。

### ToolCallAdvisor：1.1.4 的折中款

Spring AI 1.1 新增的 `ToolCallAdvisor`（`org.springframework.ai.chat.client.advisor`），属于 Recursive Advisor 家族——把工具调用循环搬进 advisor 链，等效于"每跑完一轮工具就再过一次 advisor 链"。

```java
ToolCallAdvisor toolCallAdvisor = ToolCallAdvisor.builder()
        .toolCallingManager(toolCallingManager)
        .advisorOrder(BaseAdvisor.HIGHEST_PRECEDENCE + 300)
        .disableMemory()  // 链里已有 MessageChatMemoryAdvisor 时必开
        .build();
```

**选型判据**：工具循环中途要不要等外部回调（人工审批、支付回调）？不要 → `ToolCallAdvisor`（切面式，保留 advisor 链完整）；要 → V20 完整手写（流程式）。

---

## 6.5 V21：Plan-and-Execute——先规划、再执行

把"想"和"做"拆成两个角色：**Planner**（一次性吐完整计划，1 次 LLM 调用）→ **Executor**（按计划逐步调工具，纯反射 0 次 LLM）→ **Synthesizer**（综合成自然语言回答，1 次 LLM）。

### Plan 数据结构

```java
public record AgentPlan(String objective, List<PlanStep> steps, String rationale) {
    public record PlanStep(int id, String description, String action,
            Map<String, Object> args, List<Integer> dependsOn, String blockUntil) {}
}
```

设计要点：字段命名要自解释（`description` 而非 `desc`、`dependsOn` 而非 `dep`）——`BeanOutputConverter` 把字段名注入 prompt 让模型对照生成，这是结构化输出场景里最廉价的优化点。

Planner 核心：`.entity(AgentPlan.class)` 拿到强类型对象（`BeanOutputConverter` 自动把 JSON Schema 拼到 user 消息末尾），`internalToolExecutionEnabled(false)` 让模型"看见"工具清单做规划但绝不允许真调。

Executor 核心：`tool.call(argsJson, new ToolContext(toolContext))`——外面套的不是"再调一次 LLM 看下一步"，而是 for 循环下一次迭代。**这就是 PaE 相对 ReAct 的本质：执行序列由计划决定，不由模型自由发挥**。

### V21 vs V19/V20 对照

| 维度 | V19/V20 | V21 PaE |
|---|---|---|
| LLM 调用次数 | 5~6 轮 | **2 轮** |
| 漏 sendSMS? | **是** | **否** |
| 计划在哪 | 模型内部隐式 | **显式 JSON 对象** |
| 试错能力 | **强**（自动改路） | **弱**（计划僵化） |

---

## 6.6 V22：Re-Planning——给计划以韧性

V21 单向 Plan → Execute 崩盘即死；V22 回路失败返回 Re-Planner，已成功的步保留，只重规划剩余部分。

### 状态对象

```java
public record StepExecution(int stepId, String action, Map<String, Object> args,
        Status status, String result, String errorMessage) {
    public enum Status { SUCCESS, FAILED, SKIPPED }
}

public record ExecutionState(String objective, List<StepExecution> executed,
        List<AgentPlan.PlanStep> remaining) {
    public ExecutionState advance(StepExecution exec) { ... }
    public ExecutionState recordFailure(StepExecution exec) { ... }
    public ExecutionState replaceRemaining(List<AgentPlan.PlanStep> newRemaining) { ... }
}
```

### Re-Planner 核心

看着已发生的事重新规划：`REPLAN_PROMPT` 含 5 条规则——基于已成功步的真实返回值决策、失败原因如指出业务约束换同义工具、不要重试相同 action+相同 args、必须覆盖还能满足的诉求。

### V22Agent 主循环

```java
while (!state.remaining().isEmpty()) {
    StepExecution exec = executeOne(nextStep, tools, toolContext);
    if (exec.status() == SUCCESS) { state = state.advance(exec); continue; }
    state = state.recordFailure(exec);
    if (++replanAttempts > MAX_REPLAN_ATTEMPTS) break;
    AgentPlan newPlan = replanner.replan(question, state, tools);
    state = state.replaceRemaining(newPlan.steps());
}
```

V22 = V21 + 状态对象化 + 失败回流到 Planner + 重规划上限。让 PaE 在"8 成确定性 + 2 成需要试错"的中间地带胜过纯 ReAct。

---

## 6.7 V21/V22 落地必踩的坑

| 坑 | 解法 |
|---|---|
| ① 工具名幻觉（LLM 编不存在的 action） | action 用 Java enum + Executor 硬检查 |
| ② 参数类型错（String/Number/Boolean 分不清） | `inputSchema()` 注入 Planner prompt |
| ③ Plan 漏覆盖（任务部分诉求被沉默丢失） | 加 `coverageCheck` 字段强制逐条声明 |
| ④ 工具返回"成功"但业务实际失败 | 异步工具返回结构化 JSON 带业务状态字段 |
| ⑤ 串行执行的延迟（明明可并行） | 拓扑并发执行——`CompletableFuture.allOf(deps)` |
| ⑥ 重规划振荡（在两个失败方案间来回切） | `failedFingerprints` 集合 + prompt 端双重防振荡 |
| ⑦ Re-Planner 把已成功的 step 重新规划 | 三道防线：prompt 显式禁止 + Executor 指纹拒绝 + 工具层幂等键 |
| ⑧ token 成本爆炸 | prompt 缓存 + 工具路由（先用轻量 LLM 筛选相关工具） |
| ⑨ 多轮对话下 plan 状态不持久 | `ExecutionStateRepository` 持久化（第 7 章多 Agent 协作的铺垫） |

---

## 6.8 选型指南

决策树——从根节点往下走，第一次能停就停：
1. ≤ 3 步能完成？→ **ReAct (V20 + ToolCallAdvisor)**
2. 工具数 ≤ 10？→ 否 → **ReAct 起步 + 工具路由加固**
3. 某步需要看运行时数据再决策？→ **V22 PaE + Re-Plan**
4. 有跨工具约束或异步等待？→ **V22 PaE + Re-Plan**
5. 以上全否 → **V21 PaE**

**业务场景推荐**：在线客服 FAQ 为主 → V18 装配 + ToolCallAdvisor；复合任务客服 → V22 + 复杂度路由；报表批处理 → V22 + 并行执行；编程助手 IDE 集成 → V19 ReAct + 丰富工具。

### 四个版本横向对比

| 维度 | V19 自动 ReAct | V20 手写 ReAct | V21 PaE | V22 PaE+RePlan |
|---|---|---|---|---|
| LLM 调用次数 | N（步数） | N | **2** | 2 + R（重规划次数） |
| 漏步风险 | **高** | **高** | 极低 | 极低 |
| 试错能力 | **强** | **强** | 弱 | 中 |
| 工程加固成本 | 低 | 中 | 高（9 个坑） | **极高** |

### 核心要点

1. 隐式 ReAct 在 80% 简单任务上效率比 PaE 高一个数量级
2. 手写 while = `internalToolExecutionEnabled(false)` + `ToolCallingManager`——这 25 行代码就是 ChatClient 自动循环的"成人版"
3. PaE 的核心是把"想"和"做"切成两个角色、用结构化对象（不是自由文本）做契约
4. Re-Planning 把"试错"以受控的方式装回 PaE
5. 选型是工程问题不是技术问题——9 个坑里 7 个是"模型自觉 → 代码硬约束"的工程加固

### 桥到第 7 章

V22 的 `AgentPlan` 和 `ExecutionState` 不只是 Agent 的内部状态——它们是天然的"多 Agent 协作契约"。一个 Agent 当 Planner、另一个当 Executor、第三个当 Critic——三者间用 `AgentPlan` JSON 通信，这就是第 7 章 **Orchestrator-Workers 模式**的起点。

## Spring AI 1.1.4 关键 API（本章用到）

| API | 用途 |
|---|---|
| `ToolCallingChatOptions.builder().internalToolExecutionEnabled(false)` | 关闭框架自动循环 |
| `ToolCallingManager.executeToolCalls(Prompt, ChatResponse)` | 反射执行工具 |
| `ToolExecutionResult.conversationHistory()` / `.returnDirect()` | record 风格访问器（无 get 前缀） |
| `MethodToolCallbackProvider.builder().toolObjects(...).build().getToolCallbacks()` | @Tool bean → ToolCallback[] |
| `ToolCallAdvisor` | 1.1 新增，切面化工具循环 |
| `BeanOutputConverter<T>` / `ChatClient.entity(Class<T>)` | 结构化输出 |
| Java enum 作为 schema 约束 | BeanOutputConverter 自动转 `"enum": [...]` |
