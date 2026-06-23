# Multi Agent 并发与引用审查说明

阶段 6.1 后，Multi Agent 不再把 Worker 结果当作普通文本直接交给 Synthesizer。

## 1. Worker 并发

`WorkerCall.dependsOn` 仍是依赖契约。

执行器会并发执行所有依赖已成功的 Worker。依赖不存在、依赖失败或依赖无法满足时，Worker 会被标记为 `SKIPPED`。

当前并发上限是 4。

## 2. Worker 输出

`AgentWorkerTrace` 新增：

- `brief`：给最终合成使用的摘要层。
- `evidences`：可引用证据列表。

工具取证 Worker 会优先把 PaE 工具观察结果变成证据。直接推理 Worker 的结果会标成 `WORKER_REASONING`，用于建议和保守判断。

## 3. 引用答案

Synthesizer 内部使用 `CitedAnswer`：

- `statements`：每条回答都带 evidenceIds。
- `limitations`：证据不足和执行限制。

对外仍返回 `finalAnswer` 字符串，兼容旧接口。引用 ID 会直接渲染在句尾。

## 4. 审查器

`AgentAnswerAuditor` 会检查：

- 是否回答完用户问题。
- 是否有自相矛盾。
- 是否有无引用事实。
- 是否错误使用 Worker 推理证据。

审查失败时最多重写 2 轮。审查器自身异常不会打断主链路。
