# 反馈追问与 RAG 预留说明

## 功能定位

反馈追问用于让创作者围绕当前任务的评论弹幕报告继续提问，例如“为什么认为观众误解了这个点”“哪些评论支撑这个结论”“下一期应该怎么回应”。

当前版本不接向量库，先基于 MySQL 中已经保存的反馈报告和评论弹幕明细回答。响应中保留 `retrievalMode`、`ragEnabled` 和 `evidenceItems`，后续接 Milvus 时可以复用前端展示结构。

## API

```http
POST /api/creator/tasks/{taskId}/feedback/chat
Content-Type: application/json
```

请求体：

```json
{
  "question": "为什么你认为观众误解了 Agent 工具调用？"
}
```

字段规则：

| 字段 | 规则 |
|---|---|
| `taskId` | 路径参数，必填，最长 64 个字符 |
| `question` | 必填，最长 1000 个字符 |

响应字段：

| 字段 | 说明 |
|---|---|
| `taskId` | 当前创作任务 ID |
| `question` | 本次追问问题 |
| `answer` | LLM 基于报告和证据生成的回答 |
| `evidenceItems` | 本次回答参考的评论弹幕证据 |
| `reportUsed` | 是否使用了已保存反馈报告 |
| `retrievalMode` | 当前证据来源，第一版为 MySQL |
| `ragEnabled` | 当前是否启用向量检索，第一版为 `false` |
| `createTime` | 回答生成时间 |

## 证据来源

第一版证据来源固定为当前任务：

1. `creator_llm_feedback_report` 中的结构化反馈报告。
2. `creator_feedback_item` 中的单条评论和弹幕明细。

后端不会信任前端传来的证据文本。这样做是为了保证追问回答只能基于当前任务已经入库的数据，避免页面参数伪造“用户评论”。

## 前端入口

入口位于反馈分析报告独立弹窗内：

1. 评论弹幕阶段点击“分析反馈”。
2. 成功后打开“反馈分析报告”弹窗。
3. 在弹窗内的“反馈追问”区域输入问题。
4. 回答下方展示本次引用的证据明细。

这条入口不放在评论弹幕输入表单下方，原因是追问属于结果阅读和复盘动作，不属于样例录入动作。

## RAG 预留方式

后续接入 Milvus 时，建议只替换服务层证据检索：

```text
用户问题
  -> 按 taskId 检索 Milvus
  -> 回填 evidenceItems
  -> LLM 基于报告和证据回答
```

前端不需要知道证据来自 SQL 还是向量库，只根据 `evidenceItems` 展示证据列表即可。
