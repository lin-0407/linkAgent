# LLM API 开销统计与全链路追溯说明

> 对应开发文档：`docs/develop/阶段5.9-LLM API开销统计与全链路追溯.md`

## 1. 功能定位

本功能用于查看一个创作者任务里所有模型 API 调用的事实记录。

它统计三类模型：

| 分类 | 说明 |
|---|---|
| `TEXT` | 文本大模型，例如发布前优化、评论弹幕分析、反馈追问回答 |
| `EMBEDDING` | 向量化模型，例如证据索引、向量检索、知识库索引 |
| `RERANK` | 重排序模型，例如知识库检索候选精排 |

统计结果服务排查和评测。它不是账单系统，因为本阶段不做供应商价格换算。

## 2. 统计口径

token 只来自模型供应商或 Spring AI SDK 返回的 usage。

如果 usage 没返回，后端保存 `null`，前端显示“未返回”。

这样做是为了避免把未知值误写成 0。0 会让人以为这次调用没有成本，但真实情况通常是供应商没有返回 usage。

耗时使用后端发起调用到拿到响应或异常的时间，单位是毫秒。

## 3. 状态说明

| 状态 | 含义 |
|---|---|
| `SUCCESS` | API 调用成功返回 |
| `FAILED` | API 调用或解析过程抛异常 |
| `SKIPPED` | 没有发起外部调用，例如 rerank 未启用、未配置 Key 或候选不足 |

失败和跳过也会记录。这样能让一次任务的链路更完整。

## 4. 前端入口

创作工作台顶部有“开销统计”入口。

页面包含：

- 总 Token。
- 总耗时和平均耗时。
- 失败调用数。
- 文本 LLM、向量化模型、Rerank 模型分类统计。
- 每次调用明细。

明细中可以看到：

- 调用场景。
- 模型分类。
- 模型名称。
- token。
- 耗时。
- 输入条数。
- 调用时间。
- `callId`、`traceId`、`requestId`。
- 失败原因摘要。

## 5. API

### 任务总览

```http
GET /api/llm-usage/tasks/{taskId}/summary
```

### 调用明细

```http
GET /api/llm-usage/tasks/{taskId}/calls?page=1&pageSize=20&modelCategory=TEXT
```

`modelCategory` 可选。

允许值：

- `TEXT`
- `EMBEDDING`
- `RERANK`

## 6. 数据库

表：`llm_api_call_log`

字段注释在 `backend/src/main/resources/sql/init.sql` 中维护。

部署或本地库升级时，需要让 MySQL 执行新增表 DDL。

## 7. 常见问题

### 为什么有些 token 显示“未返回”？

因为部分调用路径或供应商响应没有给出 usage。

例如 `ChatClient.entity(...)` 可以拿到结构化对象，但不暴露完整 `ChatResponse usage`。这种场景后端会记录耗时，但 token 保持未知。

### 为什么有 `SKIPPED`？

`SKIPPED` 表示系统判断本次不需要或不能发起外部调用。

典型例子是 rerank 未启用、没有 API Key，或者候选文档少于 2 条。

### 为什么统计失败不能影响主流程？

统计属于可观测辅助能力。

如果统计表异常导致发布前优化或反馈分析失败，用户会无法完成核心创作流程。

所以后端写统计失败只记录日志，不中断主业务。

