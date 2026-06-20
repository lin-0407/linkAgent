# Agent PaE 与 Multi Agent 模式说明

阶段 6 后，通用 Agent 不再只有 ReAct 一种执行方式。

## 1. 执行模式

`/api/agent/chat` 支持可选字段：

```json
{
  "sessionId": "可选",
  "userId": "可选",
  "message": "用户问题",
  "executionMode": "AUTO"
}
```

可选值：

- `AUTO`：默认。后端根据任务复杂度选择。
- `REACT`：强制使用原 ReAct 内核。
- `PLAN_EXECUTE`：强制使用 Plan-and-Execute。
- `MULTI_AGENT`：强制使用多 Agent 编排。

## 2. 响应结构

旧字段保持：

- `sessionId`
- `finalAnswer`
- `stopReason`
- `totalSteps`
- `steps`

新增字段：

- `executionMode`：实际执行模式。
- `planTrace`：PaE 或 Multi Agent 的计划和执行回放。
- `workerTraces`：Multi Agent 的 Worker 执行结果。

## 3. PaE 如何工作

PaE 是“先计划，再执行，再总结”。

```text
Planner -> Executor -> Synthesizer
```

- Planner 使用 `LLMService.chatStructured(...)` 生成 `AgentPlan`。
- Executor 只按 `AgentPlanStep.action` 调用现有 `ToolExecutor`。
- Synthesizer 根据计划和 Observation 生成最终回答。

这样做避免 ReAct 在复杂任务里每一步临时想，导致漏步骤或忘记用户原始目标。

## 4. Multi Agent 如何工作

Multi Agent 是“Orchestrator 调度多个独立 Worker”。

```text
Orchestrator Planner -> WorkerAgent -> Synthesizer
```

当前内置两个 Worker：

- `plan_execute_worker`：内部跑 PaE，适合需要工具取证的子任务。
- `direct_reasoning_worker`：直接 LLM 推理，适合归纳、解释和创作建议。

Worker 是 Spring Bean，不是 prompt 里的角色扮演。新增 Worker 时实现 `WorkerAgent` 即可。

## 5. 回退策略

`AUTO` 下，如果 PaE 或 Multi Agent 链路异常，`AgentExecutor` 会记录日志并回退 ReAct。

显式选择 `PLAN_EXECUTE` 或 `MULTI_AGENT` 时不吞异常，方便排障。

`runTask(String)` 保持原 ReAct 语义，避免影响发布前优化内部取证链路。需要新模式时使用 `runTask(String, AgentExecutionMode)`。
