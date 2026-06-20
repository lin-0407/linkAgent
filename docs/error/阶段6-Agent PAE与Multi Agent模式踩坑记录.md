# 阶段 6 - Agent PaE 与 Multi Agent 模式踩坑记录

## 1. AUTO 路由关键词不能太宽

**问题**：最初考虑用“先 / 再”判断复杂任务，但“优先用 Java 举例”也包含“先”，会把简单偏好误判为 PaE。

**处理**：收窄为“先做 / 再做 / 计划 / 步骤 / 拆解”等更明确的复杂任务词。

**原因**：自动模式应优先保持低成本和低打扰，复杂编排不能因为关键词过宽而硬凑。

## 2. 新 prompt key 必须有 SQL seed

**问题**：阶段 6 新增 Planner / Worker / Synthesizer prompt，如果只写代码调用 `PromptService`，数据库缺模板时会直接 500。

**处理**：在 `init.sql` 增加 5 条幂等 seed。

**原因**：阶段 5.5 已把提示词迁到 DB，阶段 6 继续沿用这条线，不能回到硬编码孤岛。

## 3. Multi Agent 不能伪装成一次 LLM 多角色 prompt

**问题**：如果只在一个 prompt 中写“你现在扮演 Planner、Worker、Synthesizer”，看起来像多 Agent，但工程上不可替换、不可测试、不可独立扩展。

**处理**：`MultiAgentPlanner`、`WorkerAgent`、`AgentAnswerSynthesizer` 都是独立 Bean，Worker 通过 `List<WorkerAgent>` 自动注入。

**原因**：多 Agent 的工程价值是模块化和可演化，不是把 prompt 写长。

## 4. runTask 不能被 AUTO 改行为

**问题**：发布前优化已依赖 `AgentExecutor.runTask(String)` 做内部 ReAct 取证。如果阶段 6 让它默认 AUTO，可能改变阶段 5.10 的链路成本和输出稳定性。

**处理**：保留 `runTask(String)` 原语义，新加 `runTask(String, AgentExecutionMode)`。

**原因**：公开聊天可以自动路由，内部业务链路必须显式选择，避免隐式行为漂移。
