# 阶段3：工具系统与MCP

## 概述

阶段3解决的核心问题是工具层的安全边界与生态扩展。阶段1已实现ReAct主循环和基础工具调用，阶段2完成记忆系统，但工具注册、执行、扩展机制仍处于原始状态。随着工具数量增长以及MCP、并发调用等需求的逼近，必须在工具层建立以下三道防线：

1. 注册期安全基线：确保工具池在启动阶段就能暴露配置错误，而非运行时才失败。
2. 执行期边界保护：统一超时、异常、重试策略，避免工具层异常扩散到ReAct主循环。
3. 生态扩展骨架：将外部MCP Server工具纳入同一套注册与执行体系，降低后续接入成本。

## 安全基线设计

### 注册校验

工具注册属于基础设施层，失败必须尽可能早地暴露。在`ToolRegistry`中实施启动期三项校验：

- 工具名不能为空。空工具名会导致模型收到不可调用的工具描述，且排查困难。
- 工具名不能重复。重复工具名将导致后注册者覆盖前注册者，最终执行的工具行为不可预期。
- 工具列表按工具名字典序稳定排序。排序锁定了系统提示词中工具描述的顺序，使日志对比与测试复现具备确定性。

以上校验均在Spring Bean初始化阶段完成，如果校验失败则直接阻止应用启动。这种方式将工具配置错误从“运行期随机故障”转变为“启动期硬错误”，将排查成本降到最低。

当前版本不引入自定义注解或SPI机制，因为Spring Bean自动注入`List<Tool>`已满足阶段3的工具注册需求。后续工具来源多样化时再扩展注册来源更自然。

### 超时保护

工具执行边界从ReAct主循环中独立出来，由`ToolExecutor`统一管理。三大边界场景的规范化处理：

| 场景 | 策略 | 理由 |
|------|------|------|
| 工具不存在 | 返回错误Observation，不重试 | 这是配置问题而非瞬时故障，重试无意义 |
| 工具抛异常 | 返回错误Observation，按策略决定是否重试 | 异常信息需完整保留以便排查 |
| 工具超时 | 通过`CompletableFuture.orTimeout`截断，返回超时Observation | 保证主循环不会被阻塞 |

超时配置项暴露在`agent.tool.execution.timeout-seconds`，默认10秒，最小值兜底1秒以防止0或负数配置。

当前使用`CompletableFuture.orTimeout`做链路级超时保护，其语义是保证Agent不一直等待工具结果，但不会强制终止底层阻塞代码。这是有意的取舍：先保护ReAct主流程的响应边界，后续引入代码执行、爬虫等重型工具时再单独增加线程池隔离和取消策略。

### 重试策略

在工具层引入统一的最小重试机制，以区分瞬时故障与永久错误：

- 配置项`agent.tool.execution.max-retries`，默认值0，最小值兜底0。
- 仅对工具实际执行失败做重试。`tool not found`属于配置问题，不触发重试。
- 重试耗尽后返回最后一条错误信息，包含完整的异常链。

当前版本只做统一重试次数，不区分异常类型，不引入退避算法。阶段3的重点是先把"能恢复的失败"这一类场景的范围确认清楚，后续接入外部HTTP工具、MCP工具或并发执行时再根据工具类型细分重试条件、退避时长和幂等约束。

## 并发工具调用架构

在`ToolExecutor`层预先铺设批量执行能力，为后续MCP、Planner或模型原生parallel tool use做准备：

- 保留原有单工具入口`execute(ToolCall)`，保持向后兼容。
- 新增批量入口`executeAll(List<ToolCall>)`，内部使用`CompletableFuture`并发启动所有工具调用。
- 返回结果的顺序与输入`ToolCall`列表顺序一致，保证调用方可按索引匹配。
- 批量执行中某一工具失败不影响其他工具的结果收集，失败的ToolCall对应返回错误Observation。

这一轮只扩展工具执行器，不修改ReAct主循环的文本解析。原因是现有Prompt明确约束"每次只使用一个工具"，直接改动主循环将同时波及Prompt、解析器、前端步骤展示和模型行为，不适合一步完成。先把工具层能力补齐，后续做ReAct多工具解析或MCP适配时直接复用`executeAll`即可。

## MCP适配与接入方案

### 适配层

MCP（Model Context Protocol）工具接入的核心问题是协议对象与项目内部工具模型的边界。接入方案分为两步走：

第一步：适配。引入`SpringAiToolCallbackAdapter`，将Spring AI的`ToolCallback`适配为项目内部`Tool`。适配后的MCP工具与本地工具共用同一套`ToolRegistry`、`ToolExecutor`和系统提示词生成逻辑。这一步把"工具如何被看见、如何被调用"的内部抽象统一，避免MCP Server直接进入AgentExecutor。

第二步：注册。`ToolRegistry`增加`ToolCallbackProvider`注入入口，启动时把provider暴露的所有工具一并注册进内部工具池。

### 接入链路

Spring AI 1.1.4已内置MCP客户端自动配置（`McpClientAutoConfiguration`、`McpToolCallbackAutoConfiguration`、`SyncMcpToolCallbackProvider`），项目的接入链路如下：

```text
application.yml
  -> Spring AI MCP Client AutoConfiguration
  -> ToolCallbackProvider
  -> ToolRegistry
  -> SpringAiToolCallbackAdapter
  -> ToolExecutor
  -> AgentExecutor
```

关键设计原则：MCP Server的工具不直接进入AgentExecutor，而是先通过Spring AI自动配置变为`ToolCallback`，再经适配器转换为内部`Tool`，最终与本地工具在同一个执行器中统一调度。

### 配置入口

MCP客户端配置入口预留在`application.yml`中，默认关闭（`MCP_CLIENT_ENABLED=false`），避免本地无MCP Server时启动失败。以注释形式保留`stdio`和`streamable-http`两类连接示例，支持后续通过环境变量或配置文件快速开启。

## 关键设计决策及理由

| 决策 | 理由 |
|------|------|
| 工具注册失败在启动期硬阻止，而非运行期降级 | 工具是Agent能力的基础设施。空名、重复名等配置错误在运行时才暴露的排查成本远高于启动期直接失败。 |
| 超时保护先做链路级截断，不做线程级强制终止 | 先保证ReAct主流程不被阻塞是最高优先级。强制终止底层代码需要线程池隔离和取消策略，应在明确有重型工具需求时再引入，避免过度设计。 |
| 重试策略先统一次数，不区分异常类型和退避算法 | 阶段3的任务是收窄工具执行边界，先确认"可恢复失败"的场景范围。过早引入复杂重试策略会在场景不明确的阶段增加维护负担。 |
| 批量执行与单工具执行并存，不立即改动ReAct主循环 | 工具层能力前置铺设与ReAct主循环的解耦，使后续改动范围可控。一次性改Prompt、解析器和展示逻辑风险过高。 |
| MCP工具通过适配器进入内部工具池，而非直接对接AgentExecutor | 统一内部工具抽象使注册、执行、超时、重试等安全基线对所有工具来源生效，避免为MCP工具单独开辟一条不受保护的执行路径。 |
| MCP配置入口预置但默认关闭 | 当前项目未确定具体要接入的外部MCP Server，预置入口使后续"改配置即可用"，避免无server时启动报错。 |
