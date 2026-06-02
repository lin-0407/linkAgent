# 反馈追问证据链与 RAG 最小闭环说明

## 接口列表

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/creator/tasks/{taskId}/feedback/evidence-index/rebuild` | 重建当前任务证据索引 |
| GET | `/api/creator/tasks/{taskId}/feedback/evidence-index/status` | 查询索引状态 |
| POST | `/api/creator/tasks/{taskId}/feedback/chat` | 反馈追问（原接口不变） |

## 重建证据索引

**请求体（两字段均可空，空时用配置默认值）：**

```json
{
  "maxItems": 300,
  "includeNoise": false
}
```

**响应体：**

```json
{
  "taskId": "...",
  "ragEnabled": true,
  "vectorStoreReady": true,
  "requestedCount": 300,
  "indexedCount": 286,
  "skippedCount": 0,
  "failedCount": 14,
  "warnings": ["第 276-300 条索引失败：..."],
  "createTime": "2026-06-02T21:30:00"
}
```

**错误场景：**

| 场景 | HTTP 状态 |
|---|---|
| 任务不存在 | 404 |
| RAG 业务开关未启用 | 400 |
| Milvus 未就绪 | 400 |
| 没有可索引明细 | 400 |
| Embedding/Milvus 部分失败 | 200（写入 failedCount + warnings） |

## 查询索引状态

```json
{
  "taskId": "...",
  "ragEnabled": false,
  "vectorStoreReady": false,
  "totalItems": 16,
  "indexedCount": 0,
  "pendingCount": 16,
  "failedCount": 0,
  "lastIndexedAt": null,
  "retrievalMode": "MYSQL_REPORT_AND_CLASSIFIED_ITEMS"
}
```

`retrievalMode` 是"如果现在追问预计走哪种检索"的预测值：

| 值 | 含义 |
|---|---|
| `MYSQL_REPORT_AND_CLASSIFIED_ITEMS` | SQL 证据检索（默认） |
| `MILVUS_VECTOR_AND_MYSQL_REPORT` | 向量检索命中充足 |
| `MILVUS_VECTOR_WITH_SQL_FALLBACK` | 向量命中不足，SQL 证据补足 |

## 反馈追问响应变化

原接口请求体不变，响应的 `retrievalMode` 和 `ragEnabled` 现在真实反映本次检索方式：

```json
{
  "retrievalMode": "MILVUS_VECTOR_WITH_SQL_FALLBACK",
  "ragEnabled": true,
  ...
}
```

## 重要约束

1. **索引必须显式触发**：不在导入评论弹幕后自动索引，避免隐藏 Embedding 成本。
2. **MySQL 始终是事实来源**：向量检索返回的 `itemId` 必须回查 MySQL，旧批次或已删除明细不进入回答。
3. **默认全部关闭**：演示环境无需配置 Milvus 即可使用追问，走 SQL 检索模式。
