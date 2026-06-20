# 阶段 6 - Agent PaE 与 Multi Agent 模式

## 一、需求分析

本阶段把通用 Agent 从单一 ReAct 循环升级为可按任务复杂度选择执行模式的 Agent 内核。

目标用户仍是 B 站内容创作者、内容运营者和本项目开发者。他们需要的不只是“Agent 能聊天”，而是复杂任务能先规划、能分角色协作、能把中间过程解释清楚。

本阶段做：

- `/api/agent/chat` 增加执行模式：`AUTO`、`REACT`、`PLAN_EXECUTE`、`MULTI_AGENT`。
- `PLAN_EXECUTE` 实现 Planner → Executor → Synthesizer。
- `MULTI_AGENT` 实现 Orchestrator Planner → Worker Agent → Synthesizer。
- PaE 和 Multi Agent 都复用现有 `ToolRegistry`、`ToolExecutor`、`LLMService`、`PromptService`。
- 前端 AI 交互台增加模式切换、执行模式标识、计划轨迹和 Worker 轨迹展示。

本阶段不做：

- 不替换创作者工作台已有发布前优化工作流。
- 不删除 ReAct；ReAct 仍是简单任务和异常回退路径。
- 不做计划状态持久化。
- 不做 DAG 并发执行。当前按依赖顺序执行，为后续并发保留字段。

## 二、设计方案

### 2.1 执行模式

新增 `AgentExecutionMode`：

| 模式 | 说明 |
|---|---|
| `AUTO` | 默认模式，后端用轻量规则选择路径 |
| `REACT` | 保持现有结构化 ReAct / 文本 ReAct 兜底 |
| `PLAN_EXECUTE` | 先生成结构化计划，再按计划执行工具 |
| `MULTI_AGENT` | 先生成 Worker 调度计划，再由独立 Worker Agent 执行 |

`AUTO` 不额外调用模型做路由，避免每次聊天先产生一次额外 LLM 成本。复杂关键词或长输入走 PaE，多视角 / 竞品 / 评论弹幕 / 复盘类请求走 Multi Agent。

### 2.2 PaE 契约

新增结构：

- `AgentPlan`
- `AgentPlanStep`
- `PlanStepExecution`
- `AgentPlanTrace`

执行链路：

```text
AgentExecutor
  -> AgentExecutionModeRouter
  -> PlanAndExecuteAgent
  -> AgentPlanner.chatStructured(...)
  -> ToolExecutor.execute(...)
  -> AgentAnswerSynthesizer.chat(...)
  -> AgentChatResponse(planTrace)
```

Planner 只输出计划。Executor 只按计划调工具。Synthesizer 只基于计划和观察结果合成回答。

### 2.3 Multi Agent 契约

新增结构：

- `WorkerPlan`
- `WorkerCall`
- `WorkerAgent`
- `AgentWorkerTrace`

第一版 Worker：

| Worker | 职责 |
|---|---|
| `plan_execute_worker` | 内部复用 PaE，适合需要工具取证的子任务 |
| `direct_reasoning_worker` | 直接 LLM 推理，适合解释、归纳、改写、创作建议 |

执行链路：

```text
AgentExecutor
  -> MultiAgentOrchestrator
  -> MultiAgentPlanner.chatStructured(...)
  -> WorkerAgent.execute(...)
  -> AgentAnswerSynthesizer.chat(...)
  -> AgentChatResponse(planTrace + workerTraces)
```

多 Agent 的重点不是把一次 prompt 拆成几段，而是 Planner、Worker、Synthesizer 都是独立 Bean，并通过 record 对象通信。

### 2.4 兼容策略

- 旧请求不传 `executionMode` 时默认 `AUTO`。
- 旧响应字段 `sessionId`、`finalAnswer`、`stopReason`、`totalSteps`、`steps` 保持不变。
- 新增响应字段 `executionMode`、`planTrace`、`workerTraces`。
- `AgentExecutor.runTask(String)` 保持原 ReAct 语义，避免阶段 5.10 发布前优化链路被自动路由改变。
- 新增 `runTask(String, AgentExecutionMode)` 给后续内部任务显式选择 PaE / Multi Agent。

## 三、实现范围

后端：

- 新增 `core.AgentExecutionMode`、`AgentExecutionModeRouter`、`AgentRunResult`。
- 新增 `core.plan` 包，实现 PaE Planner、Executor、Synthesizer 和计划轨迹。
- 新增 `core.multi` 包，实现 Worker 契约、Orchestrator、PaE Worker 和 Direct Worker。
- `AgentExecutor` 接入执行模式和自动回退。
- `AgentChatRequest` 增加 `executionMode`。
- `AgentChatResponse` 增加模式、计划轨迹和 Worker 轨迹。
- `init.sql` 增加阶段 6 prompt seed。

前端：

- `agent.ts` 增加执行模式、计划轨迹和 Worker 轨迹类型。
- `sendAgentMessage` 发送 `executionMode`。
- `useAgentChat` 保存当前模式并挂载响应轨迹。
- `AgentFloatingWindow` 增加模式切换。
- `MessageBubble` 增加模式标识。
- 新增 `PlanTracePanel.vue` 展示 PaE / Multi Agent 过程。

## 四、验证清单

后端：

- `AUTO` 简单问题仍能走 ReAct。
- 显式 `PLAN_EXECUTE` 时响应包含 `executionMode=PLAN_EXECUTE` 和 `planTrace`。
- 显式 `MULTI_AGENT` 时响应包含 `executionMode=MULTI_AGENT`、`planTrace` 和 `workerTraces`。
- Planner 编造不存在工具时，步骤标记为失败，不直接抛空指针。
- `AUTO` 下规划链路异常能回退 ReAct。
- `runTask(String)` 不因阶段 6 自动切换模式。

前端：

- AI 交互台默认选中 `Auto`。
- 切换 `PaE` 后发送消息，请求体带 `PLAN_EXECUTE`。
- 切换 `Multi` 后发送消息，请求体带 `MULTI_AGENT`。
- 助手消息展示执行模式 badge。
- 有 `planTrace` 时展示计划执行轨迹。
- 有 `workerTraces` 时展示 Worker 轨迹。

作者执行验证命令：

```powershell
cd E:\linkAgent\linkAgent\backend
mvn test
```

```powershell
cd E:\linkAgent\linkAgent\link-agent-frontend
npm run type-check
npm run build
```

本轮开发者未执行上述命令，因为项目规则禁止开发者执行编译、测试、构建、运行或启动命令。
