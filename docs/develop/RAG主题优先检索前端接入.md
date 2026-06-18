# RAG 主题优先检索前端接入

> 本文只记录前端接入。阶段编号暂不写死，等作者确认命名后再补入 `AGENTS.md` 文档表。

## 1. 需求分析

目标用户是 B 站内容创作者。

用户先输入一个创作问题，例如“标题怎么包装”。前端不再直接展示旧的父块检索结果，而是调用主题优先检索接口，让后端先找到相关主题中块，再展示按质量分排序的视频卡片。

用户点击某张卡片后，前端自动加载该视频的主题、评论和弹幕上下文，并打开 AI 交互台。用户不需要手动复制材料，也不需要手动开启 RAG。

本轮只改案例库检索和 AI 交互台上下文接入，不改采集导入、不改后台索引、不新增定时任务。

## 2. 设计方案

前端新增两个接口封装：

```text
POST /api/knowledge/reference-videos/topic-search
GET  /api/knowledge/reference-videos/{videoId}/analysis-context
```

案例库页面流程：

```text
输入 query
-> 调 topic-search
-> 展示 5 张视频卡片
-> 点击“换一批”请求下一页
-> 点击卡片加载 analysis-context
-> 通过前端事件把上下文交给 AI 交互台
```

AI 交互台接入方式：

- 使用 `link-agent:knowledge-video-context` 浏览器事件传递已选视频上下文。
- 接到事件后新开 Agent 会话，避免不同视频材料混在同一会话。
- 用户看到的是自己的原问题。
- 真正发给 Agent 的消息会附带视频、主题中块和评论弹幕证据。

## 3. 实现范围

改动文件：

- `link-agent-frontend/src/types/knowledge.ts`
- `link-agent-frontend/src/api/knowledge.ts`
- `link-agent-frontend/src/utils/agentContext.ts`
- `link-agent-frontend/src/components/KnowledgeWorkspace.vue`
- `link-agent-frontend/src/components/AgentFloatingWindow.vue`
- `link-agent-frontend/src/composables/useAgentChat.ts`
- `link-agent-frontend/src/App.vue`
- `link-agent-frontend/src/styles/theme.css`

## 4. 边界

- “换一批”只按后端 `page` 请求下一批，不在前端自行排序。
- 每批固定请求 5 张，最多由后端控制到 4 批。
- 卡片里的主题解释只展示当前卡片对应的 `matchedTopics`。
- AI 交互台只接收 `analysis-context` 返回的事实源，不在前端重新拼检索结果。
- 前端保留旧 `/search` 封装，避免影响其他潜在调用；案例库页面已切到 `topic-search`。

## 5. 验证点

编译和运行由作者执行：

```bash
cd link-agent-frontend
npm run build
```

手动验证：

- 案例库输入 query 后，页面调用 `topic-search` 并展示 5 张卡片。
- 点击“换一批”后，展示下一批卡片。
- 最后一批时按钮显示没有更多。
- 点击视频卡片后，AI 交互台自动打开。
- AI 交互台顶部出现已加载视频上下文。
- 发送默认问题时，Agent 能围绕该视频主题和评论弹幕回答。
