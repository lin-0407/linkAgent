# SSE 与发布前建议确认接口说明

## 功能定位

本接口组用于把发布前优化从“同步生成建议”升级为“工作流执行 + SSE 过程展示 + 用户确认”。

核心约束：

1. 生成建议不等于采用建议。
2. 只有用户确认后，任务状态才更新为 `PRE_PUBLISH_ANALYZED`。
3. SSE 只是实时通道，历史恢复仍以数据库消息为准。

## 数据表

| 表 | 用途 |
|---|---|
| `creator_workflow_session` | 保存工作流会话状态和确认结果 |
| `creator_workflow_message` | 保存用户可见的消息流 |
| `creator_workflow_step` | 保存执行步骤，用于失败回放 |
| `creator_suggestion` | 保存发布前优化建议 |

## 订阅工作流事件

```http
GET /api/creator/tasks/{taskId}/workflow/sessions/{sessionId}/events
Accept: text/event-stream
```

事件载荷：

```json
{
  "eventId": "event-id",
  "sessionId": "workflow-session-id",
  "taskId": "creator-task-id",
  "eventType": "message_created",
  "sequenceNo": 6,
  "payload": {},
  "createTime": "2026-05-30T12:00:00"
}
```

事件类型：

| eventType | 说明 |
|---|---|
| `message_created` | 新消息已创建 |
| `session_status` | 会话状态变化 |
| `step_started` | 步骤开始 |
| `step_completed` | 步骤成功 |
| `step_failed` | 步骤失败 |
| `result_ready` | 发布前建议已生成 |
| `heartbeat` | 连接心跳 |

## 执行发布前工作流分析

```http
POST /api/creator/tasks/{taskId}/workflow/sessions/{sessionId}/pre-publish/analyze
Content-Type: application/json
```

请求体：

```json
{
  "customGuidance": "标题表达克制，先总结卖点",
  "creatorPreference": "面向 Java 后端学习者",
  "titleStyle": "经验分享",
  "extraRequirement": "标题不要夸张"
}
```

说明：

1. 请求体字段都可选。
2. 工作流消息流里的 `USER` 消息会合并进本轮 `customGuidance`。
3. 分析开始后会话状态变为 `RUNNING`。
4. 分析成功后会话状态变为 `WAITING_CONFIRMATION`。
5. 该接口不会更新任务状态。

响应：

```json
{
  "suggestionId": "suggestion-id",
  "taskId": "creator-task-id",
  "contentSummary": "内容摘要",
  "parseStatus": "PARSED"
}
```

## 确认发布前建议

```http
POST /api/creator/tasks/{taskId}/workflow/sessions/{sessionId}/pre-publish/confirm
Content-Type: application/json
```

请求体：

```json
{
  "suggestionId": "suggestion-id"
}
```

校验规则：

1. `suggestionId` 必填，最长 64。
2. 建议必须属于当前任务。
3. 会话必须处于 `WAITING_CONFIRMATION`。
4. 重复确认同一个建议时返回当前会话。

确认成功后：

1. `creator_workflow_session.status` 更新为 `CONFIRMED`。
2. `creator_workflow_session.confirmed_result_id` 保存 `suggestionId`。
3. `creator_task.status` 更新为 `PRE_PUBLISH_ANALYZED`。
4. 消息流追加确认消息。

## 前端处理规则

1. 创建或恢复会话后立即建立 `EventSource`。
2. 收到 `message_created` 后按 `messageId` 去重。
3. 收到 `session_status` 后更新本地会话状态。
4. 收到 `result_ready` 后查询最新发布前建议。
5. 评论弹幕阶段只在 `CONFIRMED` 或任务已是发布前优化完成状态后开放。

## 与旧接口的关系

旧接口仍保留：

```http
POST /api/creator/tasks/{taskId}/pre-publish/analyze
```

区别：

| 接口 | 是否写消息流 | 是否写 step | 是否需要确认 | 是否直接推进任务状态 |
|---|---|---|---|---|
| 旧同步接口 | 否 | 否 | 否 | 是 |
| 工作流分析接口 | 是 | 是 | 是 | 否 |
| 工作流确认接口 | 是 | 是 | 已确认 | 是 |

