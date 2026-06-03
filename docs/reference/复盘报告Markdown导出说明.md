# 复盘报告 Markdown 导出说明

## 功能定位

复盘报告 Markdown 导出用于把已生成的创作复盘结果沉淀为可保存、可复制、可展示的文档。

它解决的是两个问题：

1. 创作者可以把复盘结果保存到自己的知识库或选题档案。
2. 项目演示时可以展示一份完整 AI 复盘产物，而不是只展示接口 JSON。

## 接口

```http
GET /api/creator/tasks/{taskId}/report/markdown
```

响应：

```http
Content-Type: text/markdown;charset=UTF-8
Content-Disposition: attachment; filename="creator-report-{taskId}.md"
```

说明：

1. `taskId` 必须是已有创作任务 ID。
2. 任务没有复盘报告时返回 404。
3. 导出只读取已保存的 `creator_report`，不会重新调用 LLM。
4. 导出不会新增数据库记录，也不会更新任务状态。

## Markdown 结构

导出文件包含：

1. 任务 ID、报告 ID、解析状态、生成时间、更新时间。
2. 内容摘要。
3. 核心卖点。
4. 标题简介复盘。
5. 观众反馈摘要。
6. 竞品对照结论。
7. 争议与误解。
8. 下一步动作建议。
9. 创作者偏好洞察。
10. 复盘总判断。

如果报告解析状态不是 `PARSED`，文件末尾会附带原始输出，方便排查 LLM 返回格式问题。

## 前端入口

创作工作台 Step 4 顶部提供“导出复盘 Markdown”按钮。

前端只负责触发下载，不在浏览器里重新拼接报告内容。这样可以保证导出格式以服务端为准，后续如果调整 Markdown 章节，只需要改后端转换逻辑。

## 设计取舍

本阶段只做 Markdown，不做 PDF。

原因是 Markdown 不需要额外依赖，适合先验证导出闭环；PDF 涉及字体、分页、中文渲染和样式一致性，应该在报告格式稳定后再做。
