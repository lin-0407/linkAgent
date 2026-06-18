# RAG 主题优先检索与视频分析上下文说明

主题优先检索的核心是：RAG 只负责找“相关主题”，视频展示顺序交给质量分。

## 接口

```text
POST /api/knowledge/reference-videos/topic-search
GET  /api/knowledge/reference-videos/{videoId}/analysis-context
```

## topic-search

请求字段：

- `query`：必填。
- `category`：可选分区过滤。
- `tier`：可选层级过滤。
- `page`：可选，1 到 4。
- `size`：可选，1 到 5，默认 5。
- `strategy`：可选，`NONE` / `REWRITE` / `HYDE` / `MULTI_QUERY`。

处理流程：

```text
query
-> 查询增强
-> 检索主题中块集合
-> 读取 metadata.videoId
-> 去重取最多 20 个视频
-> MySQL 二次过滤 category / tier
-> 按 quality_score 降序分页
```

返回字段：

- `mode`：`TOPIC_VECTOR` 或 `SQL`。
- `matchedTopics`：当前页卡片对应的命中主题。
- `cards`：当前页视频卡片。
- `hasMore`：是否还能刷新下一页。

## analysis-context

点击视频卡片后调用。

返回：

- `video`：父视频卡片。
- `topics`：该视频所有主题中块。
- `evidenceItems`：该视频前 30 条评论 / 弹幕证据。

这个接口直接查 MySQL，不要求再次向量检索。
