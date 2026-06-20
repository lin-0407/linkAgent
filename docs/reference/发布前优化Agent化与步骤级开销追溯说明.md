# 发布前优化 Agent 化与步骤级开销追溯说明

## 功能定位

发布前优化主流程现在默认通过 Agent 执行。Agent 会读取任务材料、创作者偏好和视频类型语境，并在需要时调用 `knowledge_search` 检索案例库。

旧直连 LLM 链路仍保留。Agent 结构化输出失败、工具链路失败或模型异常时，工作流会记录 Agent 步骤失败，然后自动创建直连 LLM 回退步骤，保证用户仍能获得发布前优化建议。

## 开关

配置项：

```yaml
creator:
  pre-publish:
    agent:
      enabled: true
```

环境变量：

```text
PRE_PUBLISH_AGENT_ENABLED=true
```

运行期设置 key：

```text
creator.pre-publish.agent.enabled
```

默认开启。关闭后发布前优化工作流直接走旧直连 LLM。

## 工作流步骤

新增步骤类型：

| 类型 | 说明 |
|---|---|
| `AGENT_REASONING` | 发布前优化 Agent 推理 |
| `TOOL_CALL` | 预留工具调用业务步骤 |

当前发布前优化常见步骤：

```text
LOAD_CONTEXT
AGENT_REASONING
SAVE_RESULT
```

Agent 失败回退时：

```text
LOAD_CONTEXT
AGENT_REASONING(FAILED)
LLM_CALL(SUCCESS)
SAVE_RESULT
```

## 用量归属

`LlmUsageContext.openWorkflowStep(...)` 会把当前工作流 session 和 step 写入线程上下文。文本模型、Embedding 和 Rerank 的统计写入时会读取该上下文。

记录字段：

- `workflow_session_id`
- `workflow_step_id`
- `workflow_step_name`
- `workflow_stage`

注意：`knowledge_search` 内部可能触发 Embedding 和 Rerank。工具执行器使用异步线程，所以 `ToolExecutor` 会显式恢复 `LlmUsageContext`，否则工具内部模型调用无法挂到当前步骤。

## 接口

```http
GET /api/creator/tasks/{taskId}/workflow/sessions/{sessionId}/usage
```

用途：按工作流 session 查询模型调用开销，并按 step 分组。

响应字段：

- `totalCalls`
- `successCalls`
- `failedCalls`
- `skippedCalls`
- `totalTokens`
- `totalElapsedMs`
- `steps[].calls[]`

该接口是过程弹窗专用接口，不替代 `/api/llm-usage/tasks/{taskId}/summary`。任务级接口适合总览，工作流接口适合步骤级排查。

## 前端展示

主页面只展示过程摘要、“查看过程”按钮，以及顶部“查看消息流”入口。消息流和材料详情不默认展开，避免创作者在生成建议时被上下文调试信息干扰。

过程弹窗展示：

- 顶部汇总。
- 步骤状态。
- 输入摘要和输出摘要。
- 错误信息。
- 步骤下 API 开销。
- 默认隐藏的 rawOutput。

开销接口失败时，弹窗仍展示步骤，并提示“开销统计暂不可用”。

消息流弹窗展示：

- 工作流消息列表。
- 当前消息详情或材料全文。
- 补充发布前优化要求的输入框。
- 刷新消息和关闭按钮。

## 数据库更新

执行 `backend/src/main/resources/sql/init.sql` 会补齐表字段和索引。已有本地库会通过脚本中的 `ALTER TABLE` 兼容补丁补字段。

新增索引：

```sql
idx_llm_api_workflow_step (workflow_session_id, workflow_step_id, create_time)
```

这个索引用于弹窗打开时按 session 查询，再快速挂载到对应步骤。
