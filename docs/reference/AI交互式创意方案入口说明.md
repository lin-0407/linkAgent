# AI 交互式创意方案入口说明

## 1. 功能范围

本说明对应阶段 6.3 的 P0-1 落地范围。

已实现闭环：

```text
用户输入创作想法
  -> 后端创建标准 creator_task
  -> LLM 生成 3 张创意卡片
  -> 前端展示三张卡片
  -> 用户选择一张
  -> 后端保存选择结果，并把已选方向回写为任务材料
  -> 前端自动进入发布前优化
```

暂未实现：

1. 发布前优化交互台化。
2. 左侧阶段进度只读化。
3. BV 绑定、UID 绑定和视频分析页。
4. 全局开销统计入口。

## 2. 后端接口

### 2.1 创建交互式创作任务

```http
POST /api/creator/interactive/tasks
```

请求体：

```json
{
  "userId": "default",
  "idea": "我想做一期讲 Spring AI Agent 工作流的视频",
  "videoType": "技术分享"
}
```

校验规则：

1. `idea` 必填，长度 10 到 3000 字。
2. `userId` 可选，最长 64 字符。
3. `videoType` 可选，最长 64 字符。

响应包含 `taskId`、`sessionId`、会话状态和三张 `options`。

### 2.2 重新生成创意卡片

```http
POST /api/creator/interactive/tasks/{taskId}/creative-options/regenerate
```

请求体：

```json
{
  "extraRequirement": "更适合新手，标题别太像教程课"
}
```

后端会软删除旧卡片，基于原始想法和补充要求重新生成三张卡片。

### 2.3 确认创意卡片

```http
POST /api/creator/interactive/tasks/{taskId}/creative-options/{optionId}/confirm
```

确认后：

1. `creator_idea_option.selected` 标记当前卡片。
2. `creator_interactive_session.status` 更新为 `CREATIVE_CONFIRMED`。
3. 已选卡片会回写到 `creator_material`，让现有发布前优化继续读取任务上下文。

## 3. 数据表

新增两张表：

| 表 | 用途 |
|---|---|
| `creator_interactive_session` | 保存用户原始想法、交互式创作状态、LLM 原始输出和选中的卡片 ID。 |
| `creator_idea_option` | 保存 AI 生成的三张创意卡片，包括标题大纲、内容大纲、简介大纲、亮点、风险和推荐理由。 |

字段注释已写入 `backend/src/main/resources/sql/init.sql`。

## 4. 前端入口

新增组件：

```text
frontend/src/components/creator/AiCreationConsole.vue
```

接入位置：

```text
frontend/src/components/CreatorWorkspace.vue
```

首屏从“先选择视频任务”调整为 AI 创作台。用户确认创意卡片后，父组件复用原有 `selectTask(taskId)`，自动进入 `prePublish` 阶段。

## 5. 设计注意

1. P0-1 没有重写现有任务体系，而是创建正常 `creator_task`，保证发布前优化、反馈分析和复盘仍围绕同一个 `taskId` 扩展。
2. LLM 输出解析失败时，后端会记录 `parseStatus=RAW_ONLY` 并生成三张兜底卡片，避免首屏流程直接中断。
3. 兜底卡片只保证流程可继续，不代表模型结果质量合格；后续可以通过评测集和失败回放继续优化。
4. 当前确认卡片后仍进入旧的发布前优化页面。P0-2 再把该阶段改成 AI 主动追问。

## 6. 验证建议

开发者未执行构建、测试或启动命令。作者验证时建议执行：

```powershell
cd E:\linkAgent\linkAgent\backend
mvn test
```

```powershell
cd E:\linkAgent\linkAgent\frontend
npm run type-check
npm run build
```

手工联调：

1. 打开创作者工作台首屏。
2. 输入不少于 10 个字符的创作想法。
3. 点击“生成 3 个方向”。
4. 确认出现三张创意卡片。
5. 点击任意卡片的“选择这个方向”。
6. 确认页面进入发布前优化，任务材料中包含已选创意方向。
