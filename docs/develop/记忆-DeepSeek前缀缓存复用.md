# 记忆：DeepSeek 前缀缓存复用（DSH 式）

> 对应使用/排查说明：`/docs/reference/记忆Token计量与上下文压缩说明.md`

## 1. 为什么改

摘要压缩是两次大模型调用（生成 + 复核）。改造前两次都用自己的独立 prompt：自己的 system 提示词、
自己拼的消息文本，和上一轮真实对话请求没有一段 token 前缀相同。DeepSeek 上下文缓存按请求前缀命中，
所以一次压缩的约 52 万 token 输入全部冷启动。

实测数据（2026-10-18，暂停前的 5 题 smoke）：每题压缩前 260,337 / 260,326 token，
生成一次约 26 万、复核一次 251,250 token，两次都是冷调用；`llm_api_call_log` 当时也没有缓存字段，命中率只能靠供应商后台看。

## 2. DSH 的做法（证据）

`@deepseek-ai/dsh-compaction-basic/lib/index.js`：

- 236–242 行注释写明：压缩指令作为**最后一条 user 消息**追加在重放的对话后面，而不是另起一个 summarizer system prompt，
  这样这次调用就是上一轮真实请求的真前缀，供应商 KV cache 命中而不是失效；
- 683–704 行 `buildSummarizationInput`：重放「surface 头部的 system 消息 + 请求头里的工具定义 + 被压缩区间的消息」；
- 305–322 行：消息列表末尾追加压缩指令，`tools` 也原样带上。

对应到 LinkAgent 的结论：

1. 摘要生成 = 上一轮真实请求的前缀（system + 摘要块 + 待压缩消息原文）+ 末尾压缩指令；
2. 摘要复核 = 在生成调用的消息列表后面追加一条复核要求，复用生成刚写进缓存的那段 prompt；
3. 普通对话轮要命中，拼接顺序必须「跨轮稳定的在前、每轮变化的在后」：长期记忆按当前问题检索、内容每轮都变，
   不能放在最前面。

## 3. 改了什么

### 3.1 上下文拼接顺序（`AgentExecutor.java`）

`assembleContext` 的顺序从「长期记忆 → 摘要 → 短期记忆 → 用户输入」改成
「摘要 → 短期记忆 → 长期记忆 → 用户输入」。长期记忆挪到 `appendMemory` 之后、`Human:` 之前。

### 3.2 记录并重放真实请求前缀（`AgentExecutor.java`）

- 新增会话级 `lastRequestPrefixes`（`Map<String, LastRequestPrefix>`）：每轮真正发请求前，
  在 `run()` / `runStreaming()` 的严格工具、结构化、文本三条路径上调用 `rememberRequestPrefix`，
  记下当次 system 提示词、前置文本（平台快照）和摘要正文；
- 压缩分支新增 `buildReplayPrefix`：摘要版本没变时，拼出
  `当时的 system + 当时的摘要块 + 本次待压缩消息原文`，交给摘要生成；摘要版本变过或没有记录时返回 null，
  退回原来的独立摘要 prompt；
- 日志：`上下文已按 token 压缩，... 摘要前缀复用=true/false`。

### 3.3 摘要生成走重放前缀（`SummaryMemory.java`、`SummaryReplayPrefix.java`）

`summarize(sessionId, messages, replay)` 新增重载：

- `replay != null`：消息 = `[SystemMessage(上一轮 system), UserMessage(前缀原文 + 压缩指令)]`；
  指令复用 `summary_memory.system` 的规则文本，放在末尾；
- `replay == null`：保持原来的冷启动 prompt（system 规则 + 已有摘要 + 消息）。
- 摘要生成不再绕过用量统计：新增 `LlmApiUsageService` 记录这次 ChatModel 调用的 prompt/completion/缓存命中 token。

### 3.4 摘要复核复用生成前缀（`SummaryReviewer.java`、`SummaryGeneration.java`）

`review(SummaryGeneration, ...)` 新增：消息 = 生成调用的消息列表 + `UserMessage(复核要求)`，
system 与生成调用完全一致（复核规则放进追加的那条消息）。生成结果与调用信封由 `SummaryGeneration` 一起返回。

### 3.5 缓存命中可量化

- `llm_api_call_log` 新增 `cached_prompt_tokens`（DeepSeek 的 `prompt_tokens_details.cached_tokens`），
  `init.sql` 含旧库幂等 ALTER；
- `LlmUsageFields` 统一从 Spring AI `Usage` 取 prompt/completion/total/cached，`LLMService` 与摘要链路共用同一口径；
- 后端日志每次调用打 `cachedPromptTokens=... cacheHitRate=...`（供应商没返回时是 `unknown`，不是 0%）。

### 3.6 评测环境

`evals/Start-Evaluation.ps1` 每次启动都从 `backend/src/main/resources/sql/init.sql` 生成评测库初始化文件，
只改库名；新列会随下次启动自动补上，不需要手工改两份 Schema。

## 4. 预期命中率

| 场景 | 期望 |
|---|---|
| 生产普通对话轮 | 摘要 + 短期记忆这段前缀命中；只有新增消息、长期记忆、本轮输入是冷的，长会话命中率随轮次升高 |
| 生产摘要压缩 | 生成调用重放上一轮真实请求前缀，约 99% 命中；复核复用生成前缀，同样约 99% |
| 评测 summary 策略 | 先灌历史再压缩，没有上一轮真实请求：**生成仍冷启动**，只有复核能命中生成前缀。评测里的命中率不代表生产 |

## 5. 验证

- 单测：
  - `SummaryMemoryTest`：前缀重放时 system 与上一轮一致、压缩指令只追加在末尾；
  - `SummaryReviewerTest`：复核消息 = 生成消息 + assistant 摘要 + 复核要求，system 不变；
  - `AgentExecutorCacheReplayTest`：两轮真实 `run()`，断言摘要生成收到的消息是上一轮真实请求文本的**逐字节前缀**（含长期记忆在短期记忆之后）；
  - `AgentExecutorRetainedTokenBudgetTest`、`LLMServiceTest` 等；
- 全量：排除 5 个环境依赖用例（Testcontainers 无 Docker、参考视频采集无 python）后 **577 个测试全绿**；
- DeepSeek 真实接口实测（2026-10-18，curl 同一段 4048 token 前缀连发两次）：第二次
  `prompt_cache_hit_tokens=3840`、`prompt_cache_miss_tokens=208`；带不带 `tools` 不影响消息前缀命中；
- 评测环境 1 题 smoke（native 25 题子集，修复复核消息后的最新一次）：
  - 摘要生成 `244,608 / 244,745 = 99.9%` 命中（评测里同一数据集重跑/重试共享前缀，生产里来自上一轮真实请求）；
  - 摘要复核 `245,248 / 249,260 = 98.4%`、`245,248 / 250,449 = 97.9%`、`254,208 / 257,372 = 98.8%`（每次都复用了对应生成调用的前缀）；
  - 压缩本身正常：269,003 → 18,298 token，摘要 3,979 字，`compression_count=1`；
  - 压缩后的回答调用命中 `0.2%`，符合预期：摘要换版后前缀本来就会变。
- 未验证：生产真实对话的命中率数字要等后端重启、`llm_api_call_log` 积累后看；25 题正式 A/B 未跑。

## 6. 边界与取舍

- 不改 256k 触发阈值，不改 768k guard；
- 摘要版本变了（上一轮之后又压缩过一次）时放弃复用，宁可这一轮冷启动也不重放错版本的前缀；
- 记录的前缀放在进程内存里，进程重启后第一轮压缩退回冷启动；
- 事实点抽取、长期记忆提取仍是独立 prompt，没有做前缀复用（它们不参与每轮大上下文）；
- 平台快照（`requestContext`）会被记进前缀；非 REACT 模式每轮快照都变时，那一轮复用会失效，属于预期。
