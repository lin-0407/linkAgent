# 发布前优化 Agent 接口说明

## 功能定位

发布前优化 Agent 是创作者在发稿前使用的辅助模块。

当前接口是阶段 4.2 的同步 LLM 基线，负责把任务材料生成结构化建议并保存。阶段 4.4.1 会在此基础上新增工作流会话、SSE 消息流和用户确认机制。

它基于阶段 4.1 保存的创作任务和材料，生成：

1. 内容摘要。
2. 目标受众判断。
3. 核心卖点。
4. 风险点。
5. 标题建议。
6. 简介建议。
7. 标签建议。
8. 分区建议。

第一版不接入真实平台数据，不做评论分析，不做自动投稿。

## 表结构

### creator_suggestion

用途：保存一次发布前优化结果。

关键字段：

| 字段 | 说明 |
|---|---|
| suggestion_id | 建议唯一标识 |
| task_id | 关联创作任务 |
| content_summary | 内容摘要 |
| audience_profile | 目标受众判断 |
| selling_points | 核心卖点 JSON |
| risk_points | 风险点 JSON |
| title_suggestions | 标题建议 JSON |
| description_suggestion | 简介建议 |
| tag_suggestions | 标签建议 JSON |
| partition_suggestion | 分区建议 |
| raw_output | LLM 原始输出 |
| parse_status | 解析状态 |

当前只保留每个任务一份最新建议。

## API

### 发布前分析

```http
POST /api/creator/tasks/{taskId}/pre-publish/analyze
Content-Type: application/json
```

请求体：

```json
{
  "customGuidance": "可选，标题语气、建议风格和分析顺序等业务指导",
  "creatorPreference": "偏好理性一点的表达，不要太夸张",
  "titleStyle": "偏经验分享和结果导向",
  "extraRequirement": "标题尽量短，简介要清楚"
}
```

说明：

1. 主要材料从任务里读取，不需要重复提交。
2. `customGuidance` 为空时不添加本次业务指导，最长 2000 个字符。
3. 后端系统提示词固定维护角色、平台数据边界和 JSON 输出结构，前端不能覆盖。
4. 三个补充字段都是可选项。
5. 任务没有材料时返回 400。

### 查询建议

```http
GET /api/creator/tasks/{taskId}/pre-publish/suggestions
```

说明：

1. 任务不存在时返回 404。
2. 没有建议时返回 404。
3. 返回中同时包含结构化字段和 `rawOutput`，便于人工检查。

## 设计取舍

当前同步基线没有上 SSE。

原因是当前目标是先把“任务材料 -> LLM 建议 -> 数据库存档”这条线跑通。SSE 更适合放在下一步做过程可视化，不应该把它和建议生成逻辑混在一起。

阶段 4.4.1 的升级方式：

1. 当前 `POST /pre-publish/analyze` 保留为简单联调入口。
2. 新增 `/workflow/pre-publish/start` 创建或恢复任务级工作流会话。
3. 新增 `/workflow/sessions/{sessionId}/events` 通过 SSE 推送上下文装载、步骤执行和结果生成事件。
4. 新增 `/workflow/sessions/{sessionId}/pre-publish/confirm`，用户确认后再推进阶段状态。
5. 工作流消息和步骤会落库，SSE 断开后前端可以恢复历史消息。

阶段 4.4.1.2 已完成第 3、4、5 点。工作流分析接口会复用发布前优化生成逻辑，但不会直接更新任务状态；确认接口才会把任务推进到 `PRE_PUBLISH_ANALYZED`。

本阶段没有拆多个建议子表。

原因是 MVP 先要简单。结构化字段先放一个表里，足够支撑演示和简历展示；等后面真的出现版本管理、人工批注、多轮对比，再拆更细。

本阶段保留 `rawOutput`。

原因是 LLM 输出不稳定，保留原文可以做失败回放，也能方便调整业务指导。

## 后续接入点

阶段 4.3 会新增评论弹幕输入和分析接口。

阶段 4.4 会把发布前建议、反馈分析和复盘报告串成完整闭环。

阶段 4.4.1 会把同步建议生成升级为 Agent 工作流消息流和建议确认。

阶段 4.6 会用 `rawOutput`、耗时、token 和人工评分做评测集。
