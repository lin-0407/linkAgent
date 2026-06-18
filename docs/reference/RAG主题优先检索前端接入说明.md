# RAG 主题优先检索前端接入说明

## 页面入口

位置：案例库页面的“案例检索”区。

用户输入问题后，前端调用：

```text
POST /api/knowledge/reference-videos/topic-search
```

请求固定带 `size=5`。`page=1` 是第一批，点击“换一批”后依次请求 `page=2`、`page=3`、`page=4`。

## 卡片点击

点击视频卡片后，前端调用：

```text
GET /api/knowledge/reference-videos/{videoId}/analysis-context
```

响应里的 `video`、`topics`、`evidenceItems` 会传给 AI 交互台。

## AI 交互台

AI 交互台收到视频上下文后会：

- 自动打开浮窗。
- 新建一个 Agent 会话。
- 在顶部展示已加载的视频标题、质量分、主题和部分评论弹幕。
- 预填一个默认问题。
- 发送时把视频上下文拼到实际请求里。

用户界面只显示用户自己的问题。大段上下文不会刷屏，但会进入 Agent 请求。

## 前端事件

事件名：

```text
link-agent:knowledge-video-context
```

事件内容：

```ts
{
  query: string
  context: {
    video: ReferenceVideo
    topics: ReferenceVideoMatchedTopic[]
    evidenceItems: ReferenceVideoEvidenceItem[]
  }
}
```

这个事件只在前端组件间传递后端事实源数据，不负责采集、索引或重新检索。
