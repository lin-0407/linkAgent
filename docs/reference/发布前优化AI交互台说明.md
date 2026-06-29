# 发布前优化 AI 交互台说明

## 1. 功能范围

本说明对应阶段 6.3 的 P0-2 落地范围。

已实现闭环：

```text
用户确认创意卡片
  -> 自动进入发布前优化
  -> 页面以 AI 交互台展示工作流消息
  -> AI 提醒当前缺少文稿或字幕
  -> 用户继续补充要求，或让 AI 生成一版可编辑文稿草稿
  -> 草稿写回任务材料
  -> 用户生成发布方案
  -> 结果弹窗中查看并确认方案
```

暂未实现：

1. BV 绑定和 `WAITING_BV_BINDING` 状态。
2. B 站 UID 绑定。
3. 独立视频分析页。
4. 全局开销统计入口。

## 2. 后端接口

### 2.1 生成可编辑文稿草稿

```http
POST /api/creator/tasks/{taskId}/workflow/sessions/{sessionId}/pre-publish/manuscript-draft
```

请求体：

```json
{
  "extraRequirement": "口播更自然，适合 5 分钟以内的视频"
}
```

校验规则：

1. `taskId` 必填，最长 64 字符。
2. `sessionId` 必填，最长 64 字符。
3. `extraRequirement` 可选，最长 1000 字符。

处理规则：

1. 只允许发布前优化工作流会话调用。
2. 会话为运行中、已确认、已取消时拒绝调用。
3. 任务已有较完整文稿或字幕时拒绝自动覆盖。
4. AI 生成结果保存为 `creator_material.MANUSCRIPT`。
5. 生成过程写入 `creator_workflow_step`，LLM 调用纳入步骤级开销。
6. 成功或失败都会追加工作流消息，前端以对话形式展示。

### 2.2 继续复用的接口

发布前优化对话继续复用：

```http
POST /api/creator/tasks/{taskId}/workflow/sessions/{sessionId}/messages
```

发布方案生成继续复用：

```http
POST /api/creator/tasks/{taskId}/workflow/sessions/{sessionId}/pre-publish/analyze
```

发布方案确认继续复用：

```http
POST /api/creator/tasks/{taskId}/workflow/sessions/{sessionId}/pre-publish/confirm
```

## 3. 前端行为

调整文件：

```text
frontend/src/components/creator/PrePublishTab.vue
frontend/src/components/CreatorWorkspace.vue
frontend/src/api/creator.ts
frontend/src/types/creator.ts
frontend/src/styles/theme.css
```

主要变化：

1. `PrePublishTab.vue` 从固定表单页改为 AI 对话台。
2. 工作流消息在主页面展示，不再只放在开发者弹窗里。
3. 缺少完整文稿或字幕时，右侧动作区优先显示“我来补充”和“让 AI 补一版”。
4. 生成发布方案后，仍复用已有结果弹窗让用户查看并确认。
5. 偏好记忆、类型语境库和额外要求收进辅助区域，不再抢占主流程。
6. 左侧竖向进度条变为只读展示，不再点击切换阶段。
7. 开销统计从左侧阶段栏移除，后续在 P0-5 做全局入口。

## 4. 文稿缺失判断

P0-1 会把“已确认创意方向”临时写入 `MANUSCRIPT`，但这不是完整文稿。

因此 P0-2 用内容长度做保护：

```text
MANUSCRIPT 或 SUBTITLE 内容长度 >= 800 字
  -> 认为已有较完整文稿或字幕
  -> 不自动覆盖

MANUSCRIPT 或 SUBTITLE 内容长度 < 800 字
  -> 视为大纲或短素材
  -> 允许 AI 扩写成可编辑草稿
```

这个规则用于避免误把创意大纲当成完整文稿，同时避免覆盖用户已经写好的长文稿。

## 5. 验证建议

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

手工联调路径：

1. 输入创作想法并生成三张创意卡片。
2. 选择任意创意卡片。
3. 进入发布前优化页，确认页面显示 AI 对话台。
4. 在没有完整文稿或字幕时点击“让 AI 补一版”。
5. 确认任务材料中出现 AI 生成的 `MANUSCRIPT`。
6. 补充一条修改要求，确认消息流新增用户消息。
7. 点击“生成发布方案”，确认结果弹窗出现。
8. 确认左侧阶段栏不可点击切换。
