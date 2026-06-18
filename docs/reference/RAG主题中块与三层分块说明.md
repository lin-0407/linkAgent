# RAG 主题中块与三层分块说明

本次优化把案例库分块从“两层”升级为“三层”：

```text
视频案例父块 -> 主题中块 -> 评论弹幕原始证据小块
```

主题中块解决的是召回粒度问题。它让系统能匹配“标题包装”“内容定位”“观众反馈主题”这类创作者问题，而不是只能在整张视频卡片和单条评论之间选择。

## 数据结构

新增表：`creator_reference_video_chunk`。

关键字段：

- `chunk_id`：中块唯一标识，也是中块向量文档 ID。
- `video_id`：回到父视频案例的关键键。
- `chunk_type`：`TITLE_PACKAGE` / `CONTENT_POSITIONING` / `AUDIENCE_FEEDBACK_SUMMARY`。
- `chunk_content`：确定性拼装的中块正文。
- `source_item_ids`：观众反馈主题块关联的子条目 ID JSON。
- `embedding_*`：中块向量索引状态。

## 索引接口

```text
POST /api/knowledge/reference-videos/index/chunks/rebuild
GET  /api/knowledge/reference-videos/index/chunks/status
```

`rebuild` 是增量索引，只处理 `PENDING / FAILED`。索引前会补齐历史案例缺失且可生成的中块。

观众反馈中块依赖已清洗的评论或弹幕。没有反馈素材的视频不会生成空的观众反馈中块。

## 检索行为

在非 hybrid 向量路径下，检索会同时使用：

- 父视频集合。
- 主题中块集合。
- 子条目集合。

合并后仍按 `videoId` 回查父表，最终响应结构不变。中块只影响召回候选，不改变前端卡片数据结构。

hybrid 路径暂时不混入中块 dense 集合，避免 `HYBRID` 模式语义不清。后续可单独扩展中块 hybrid 集合。
