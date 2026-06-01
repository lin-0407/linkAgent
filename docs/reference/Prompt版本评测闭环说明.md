# Prompt 版本评测闭环说明

## 功能定位

Prompt 版本评测闭环用于解决一个核心问题：模型输出结果必须能追溯到当时使用的 Prompt。

没有这层记录时，评测结果只能说明“这次输出好不好”，但不能说明“是哪一版 Prompt 导致它好或不好”。这对后续优化没有价值。

## 记录评测结果

接口：

```http
POST /api/creator/evaluations/cases/{caseId}/results
```

新增字段：

| 字段 | 说明 |
|---|---|
| `promptVersion` | Prompt 版本号，例如 `prepublish-v2` |
| `promptHash` | Prompt 快照 SHA-256 哈希，可选 |
| `promptSnapshot` | 本次评测使用的 Prompt 快照 |

如果 `promptHash` 为空，但 `promptSnapshot` 不为空，后端会自动计算 SHA-256。

## Prompt 版本统计

接口：

```http
GET /api/creator/evaluations/cases/{caseId}/prompt-version-stats
```

返回字段：

| 字段 | 说明 |
|---|---|
| `caseId` | 评测用例 ID |
| `promptVersion` | Prompt 版本 |
| `latestPromptHash` | 最近一次结果的 Prompt 哈希 |
| `resultCount` | 该版本评测次数 |
| `successCount` | 成功次数 |
| `successRatePercent` | 成功率百分比 |
| `scoreSampleCount` | 至少填写一项评分的样本数 |
| `averageScore` | 平均人工评分 |
| `scoreStandardDeviation` | 分数标准差，用于观察同一版本稳定性 |
| `averageAccuracyScore` | 准确性分项均值 |
| `averageRelevanceScore` | 贴合度分项均值 |
| `averageCompletenessScore` | 完整性分项均值 |
| `averageStabilityScore` | 稳定性分项均值 |
| `averageCostScore` | 成本分项均值 |
| `averageExplainabilityScore` | 可解释性分项均值 |
| `totalTokens` | 该版本已记录的总 token |
| `averagePromptTokens` | 平均输入 token |
| `averageCompletionTokens` | 平均输出 token |
| `averageTotalTokens` | 平均总 token |
| `averageElapsedMs` | 平均耗时毫秒 |
| `fullScoreCoverageRatePercent` | 七项评分都填写完整的样本占比 |
| `latestUpdateTime` | 最近更新时间 |

## 使用方式

1. 复制本轮分析真实使用的 system prompt 和 user prompt。
2. 在开发者测试弹窗填写 Prompt 版本和 Prompt 快照。
3. 记录模型输出、token、耗时和人工评分。
4. 对同一个样例用不同 Prompt 版本重复记录。
5. 在 Prompt 版本对比区查看哪一版成功率更高、准确性更高、token 更低、波动更小。

## 设计边界

当前只做人工记录和对比，不做自动批量评测。原因是这个项目的主线仍然是创作者工作台，不是独立 PromptOps 平台。
