# MVC 架构说明

## 1. 当前约定

- `controller` 负责接收请求和返回响应。
- `service` 负责业务规则、状态流转和流程编排。
- `mapper` 负责数据库读写。
- `dto` 和 `model` 负责接口传输和业务数据结构。

## 2. 创作者业务包

创作者相关功能按业务域继续拆分：

- `creator.task`
- `creator.suggestion`

每个业务域内部再按 MVC 分层，避免把所有类堆到一个目录。

## 3. 适用范围

这套分层主要用于 Web 接口和创作者业务模块。
`core`、`memory`、`tool`、`llm` 属于 Agent 底座能力，不按传统 MVC 强行重排。

## 4. 查看入口

- [阶段 4.0 - MVC 架构重整](../develop/阶段4.0-MVC架构重整.md)
- [阶段 4 - UP 主智能工作台总流程大纲](../develop/阶段4-UP主智能工作台总流程大纲.md)
