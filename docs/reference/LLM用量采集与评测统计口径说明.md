# LLM 用量采集与评测统计口径说明

## 功能定位

本说明回答两个问题：

1. token 消耗从哪里来。
2. 评分准确性和合理性如何解释。

当前项目不把评测做成独立平台，而是在创作者工作台里补足 AI 工程化证据：真实调用有 usage，Prompt 版本对比有统计口径，坏结果可以回放。

## LLM 用量采集

统一入口：

```text
LLMService.chatWithUsage(systemPrompt, userMessage)
```

返回：

| 字段 | 说明 |
|---|---|
| `content` | 模型输出文本 |
| `modelName` | 模型响应元数据里的模型名称 |
| `promptTokens` | 输入 token |
| `completionTokens` | 输出 token |
| `totalTokens` | 总 token |
| `elapsedMs` | 本次调用耗时毫秒 |

旧方法 `chat(...)` 保留，只返回文本，适合暂时不需要统计的旧调用方。

注意：如果模型供应商没有返回 usage，token 字段保持 `null`。这里不能填 0，因为 0 表示真的没有消耗，`null` 才表示“供应商没有给出统计值”。

Spring AI 的空 usage 不是业务上的“0 token 成本”。当前实现会把 prompt token、completion token 和 total token 都为 0 的 usage 归一成 `null`，这样前端和评测统计不会把未知用量误展示成零消耗。

## 反馈追问返回用量

接口：

```http
POST /api/creator/tasks/{taskId}/feedback/chat
```

响应新增字段：

| 字段 | 说明 |
|---|---|
| `modelName` | 本次追问使用的模型名称 |
| `promptTokens` | 输入 token |
| `completionTokens` | 输出 token |
| `totalTokens` | 总 token |
| `elapsedMs` | 调用耗时毫秒 |

前端在反馈报告弹窗的追问回答下方展示模型、token 和耗时。

## Prompt 版本统计口径

接口：

```http
GET /api/creator/evaluations/cases/{caseId}/prompt-version-stats
```

核心字段：

| 字段 | 说明 |
|---|---|
| `resultCount` | 该 Prompt 版本的评测次数 |
| `successCount` | 成功次数 |
| `successRatePercent` | 成功率百分比 |
| `scoreSampleCount` | 至少填写一项评分的样本数 |
| `averageScore` | 每条结果先求分项均分，再按样本求平均 |
| `scoreStandardDeviation` | 每条结果均分的标准差，用于观察稳定性 |
| `averageReadabilityScore` | 可读性分项均值 |
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
| `averageElapsedMs` | 平均耗时 |
| `fullScoreCoverageRatePercent` | 七个评分维度都填写完整的样本占比 |

统计接口会读取当前评测用例下的全部评测结果。页面里的“最近结果”列表仍然使用分页查询，两者不要混用；否则 `resultCount`、成功率、token 总量和标准差都会被最近 N 条结果截断。

记录评测结果时，token 入参必须大于 0；不传表示未知。如果请求没有传 `totalTokens`，服务端只会在 `promptTokens` 和 `completionTokens` 都存在时自动相加。缺任意一侧就保留 `null`，避免把未知部分当成 0。

## 评分准确性

这里的“准确性”不是自动客观准确率，而是人工评测维度 `accuracyScore`。

它的判断依据来自评测用例：

1. `inputSnapshot`：固定输入。
2. `expectedPoints`：期望命中要点。
3. `scoringRubric`：评分说明。
4. `rawOutput`：模型原始输出。
5. `reviewerNote`：人工评分备注。

换句话说，项目现在能证明的是“按固定样例和评分说明进行可回放人工评分”，不能吹成“自动精准评测模型准确率”。

## 评分合理性

合理性通过组合证据解释：

1. 分项评分避免一个总分掩盖问题。
2. `fullScoreCoverageRatePercent` 判断样本评分是否完整。
3. `scoreStandardDeviation` 判断同一 Prompt 版本是否稳定。
4. `promptHash` 判断多次评测是否真的是同一份 Prompt。
5. `rawOutput` 和 `reviewerNote` 支持人工复核。

后续如果要更硬，可以继续加多评审人一致性、LLM-as-judge 辅助评分和独立调用流水表。
