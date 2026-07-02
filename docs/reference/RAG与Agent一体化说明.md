# RAG 与 Agent 一体化说明

本文档汇总阶段 5 完成后的 RAG 知识库体系、高级检索链路、Agent 内核升级、提示词管理、成本统计与设置面板功能。各模块功能定位、数据模型要点和关键配置如下。

---

## 1. 知识库体系

### 1.1 跨分区视频案例知识库（阶段 5.1）

**功能定位**：构建跨分区、跨创作任务的高质量视频案例存储底座，覆盖优品标杆与竞品案例。数据经过清洗、结构化、落库、向量化后，支持语义检索，为 Agent 提供「同赛道被验证过的成功视频」参考。

**数据来源**：
- 离线采集脚本 `scripts/bilibili_reference_fetcher.py`，产出标准 JSON。
- 项目内置 seed 案例。
- 前端单 BV 一键采集导入（用户显式触发）。

**数据模型要点**：

`creator_reference_video`（案例主表）：
- `tier`：案例层级（`BENCHMARK` / `COMPETITOR` / `OWN_HISTORY`），默认 `BENCHMARK`。
- `category`：分区 / 主题，用于同赛道过滤与分区质量分归一化。
- 六项热度指标（view / like / coin / favorite / danmaku / reply）均可空。
- `raw_quality_score`：单视频独立原始质量分，低样本时兜底排序。
- `quality_score`：分区归一化相对质量分 0-100，样本不足时为空。
- `quality_score_reliable`：标记相对质量分是否可展示。

`creator_reference_video_item`（优质评论弹幕子表）：
- 外键 `video_id`（跨任务），只记录正向 / 负向非噪声条目。
- 为父子召回（small-to-big）打底：子表存可精确召回的短文本，父表提供扩展后完整案例。

**质量打分公式**：
1. 互动率：各指标除以播放量（用率而非绝对量）。
2. 加权对数互动分：`engagement = 0.40*coinRate + 0.25*favRate + 0.20*likeRate + 0.10*replyRate + 0.05*danmakuRate`，再取对数压制长尾。
3. 情绪因子（K=10 平滑）：`sentimentMul = 0.7 + 0.6*(0.5 + (posRate-0.5)*confidence)`，区间 0.7~1.3。
4. 分区 min-max 归一化到 0-100，仅在同分区有效样本 >= 最小可靠数且原始分有差异时生效。

**AI 清洗**：规则情绪分类（负向关键词优先）+ 保留规则（丢弃空语义 / 重复 / 中性）+ LLM 亮点摘要（每视频最多一次调用，失败不中断导入）。

**向量隔离**：`KnowledgeVectorStore` 是普通 Spring Bean（非 `VectorStore` 类型），内部持有 `MilvusVectorStore`（集合 `creator_reference_video`，维度 1024，`IVF_FLAT` + `COSINE`），避免与反馈侧出现多 `VectorStore` Bean 歧义。

**关键配置**：
```yaml
knowledge:
  rag:
    enabled: false                            # 默认关，与反馈 RAG 独立开关
    collection-name: creator_reference_video
    embedding-dimension: 1024
    index-batch-size: 10                      # Qwen 兼容模式单批上限
  quality:                                    # 质量公式可调参数
    coin-weight: 0.40 / favorite-weight: 0.25 / like-weight: 0.20
    reply-weight: 0.10 / danmaku-weight: 0.05
    log-scale: 1000 / sentiment-smoothing-k: 10
    sentiment-mul-base: 0.7 / sentiment-mul-span: 0.6
    min-reliable-sample-size: 5
```

### 1.2 RAG 主题中块与三层分块

**设计目的**：将案例库分块从"父块 + 子条目小块"两层升级为三层，解决检索粒度问题。主题中块让系统能匹配"标题包装""内容定位""观众反馈主题"这类创作者问题。

**数据结构**：新增表 `creator_reference_video_chunk`。
- `chunk_type`：`TITLE_PACKAGE` / `CONTENT_POSITIONING` / `AUDIENCE_FEEDBACK_SUMMARY`。
- `chunk_content`：确定性拼装的中块正文，非 LLM 生成。
- `source_item_ids`：观众反馈主题块关联的子条目 ID JSON。

**索引与检索**：中块索引为增量重建（只处理 `PENDING / FAILED`）。检索时，非 hybrid 向量路径同时使用父视频集合、主题中块集合和子条目集合，合并后按 `videoId` 回查父表，响应结构不变。hybrid 路径暂不混入中块 dense 集合。

### 1.3 主题优先检索与视频分析上下文

**设计目的**：RAG 只负责找相关主题，视频候选排序交给质量信号。可靠相对分优先，样本不足时用原始分兜底但前端不展示具体分数。

**检索流程**：
1. 查询增强后检索主题中块集合。
2. 中块异常 / 零命中时退回 SQL 关键词兜底（query 拆成关键词片段）。
3. 去重取最多 20 个视频，MySQL 二次过滤 category/tier。
4. 按 `quality_score DESC, raw_quality_score DESC` 形成候选池。
5. 在 top20 内检索相关评论弹幕，rerank 开启时结合证据精排。
6. 分页返回，`hasMore` 标识是否还有后续批次。

**视频分析上下文**：点击卡片后调用，返回父视频卡片、该视频所有主题中块和前 30 条评论 / 弹幕证据，直接查 MySQL 不走向量检索。

### 1.4 创作者视频类型语境库（阶段 5.7）

**功能定位**：保存"某创作者在某类视频里常用或慎用的表达"，服务发布前优化，让 LLM 生成建议时更贴近当前账号与视频类型。

**数据模型**：表 `creator_context_term`。
- `termType`：`KEYWORD` / `SLANG` / `MEME` / `TABOO` / `TITLE_PATTERN` / `AUDIENCE_CONCERN`。
- `weight`：决定提示词注入优先级，用户保存 / 接受提高权重，拒绝降低权重。
- `GLOBAL` 标记：全局通用语境，跨类型复用。

**与发布前优化的接入**：
- `USE_HISTORY` 模式：同时带入创作者历史偏好和当前视频类型语境库。
- `IGNORE_HISTORY` 模式：两者都不带入。
- `EXPERIMENT` 模式：两者只作为避坑参考。

---

## 2. 高级检索链路（阶段 5.2）

**功能定位**：在 5.1 存储底座之上构建完整检索链路，支持语义检索、混合检索、查询增强、父子召回、Rerank 精排和 SQL 关键词兜底。全部开关默认关，零回归。

**检索全链路**（各开关全开时）：

```
入参归一 -> 门控 -> 查询增强 -> 父召回 + 子召回 -> 合并去重 -> SQL 兜底 -> Rerank 精排 -> 证据回查
```

**五刀切片**：

| 刀 | 能力 | 核心产出 |
|---|---|---|
| 5.2a | 最小检索闭环：dense 语义检索 + SQL 关键词兜底 | `POST /search` + 三态 mode 回显 |
| 5.2b | 查询增强：查询改写 / 多查询 / HyDE 三策略可切换 | 多路召回 LinkedHashSet 去重 |
| 5.2c | 父子召回：子表向量化 + small-to-big + 证据回显 | 父子双向召回 + 观众原话证据 |
| 5.2d | 原生混合检索：dense + BM25 + RRF | MilvusClientV2 自建 schema 整库重灌 |
| 5.2e | Rerank 精排：qwen3-rerank 精排候选池 | retrieve-wide -> rerank -> 截 topK |

**核心组件**：
- `KnowledgeReferenceRetrievalService`：检索编排核心，五刀统一在此。
- `KnowledgeQueryEnhancer`：组合 Spring AI 原生 QueryTransformer 组件 + 自定义 HyDE。
- `KnowledgeHybridStore`：内部持有 `MilvusClientV2`，自建 dense + sparse + BM25 Function 的 hybrid 集合。
- `KnowledgeRerankClient`：RestClient 直连 qwen3-rerank 兼容端点。

**双开关与降级**：

| RAG | hybrid | rerank | 父召回 | mode |
|---|---|---|---|---|
| 关 | -- | -- | 不召回 | `SQL` |
| 开 | 关 | 关 | Spring AI dense | `VECTOR` / `VECTOR_WITH_SQL_FALLBACK` |
| 开 | 关 | 开 | dense + rerank | 同上 + `reranked=true` |
| 开 | 开 | 关/开 | dense + BM25 + RRF | `HYBRID` / `HYBRID_WITH_SQL_FALLBACK` |

四层降级同构：hybrid 失败退 dense、子召回失败退父-only、向量失败退 SQL、rerank 失败保原序。`HYBRID` 与 `VECTOR` 物理隔离（独立 MilvusClientV2 + 独立集合），迁移期并存。

**检索响应结构**：`mode`（实际检索模式）、`strategy`（实际生效策略）、`enhancedQueries`（增强后查询列表）、`items`（案例卡片）、`evidence`（按 videoId 分组子条目证据）、`reranked`（是否精排）。

**关键配置**：
```yaml
knowledge:
  rag:
    query-enhancement:
      strategy: REWRITE
      multi-query-count: 3
    rerank:
      enabled: false
      model: qwen3-rerank
      candidate-pool-size: 20
      timeout-ms: 10000
    hybrid:
      enabled: false                                  # 需 Milvus >= 2.5
      collection-name: creator_reference_video_hybrid
      child-collection-name: creator_reference_video_item_hybrid
      analyzer-type: chinese
```

**索引接口**（三对 rebuild/status）：
- `index/items/*`：子条目增量索引，走子 dense 集合。
- `index/hybrid/*`：父表整库重灌 hybrid 集合（drop -> 自建 schema -> 全量灌）。
- `index/hybrid/items/*`：子条目 hybrid 集合整库重灌。

---

## 3. Agent 内核升级（阶段 5.4）

**功能定位**：将自研 ReAct 内核从"自由文本 + 正则解析"升级为"结构化每步输出"。模型每步输出 `ReActStep` 对象（`thought` / `action` / `actionInput` / `finalAnswer`），避免格式漂移导致的解析失败。

**核心实现**：
- `LLMService.chatStructured`：统一的 `response_format=json_object` 结构化调用入口，解析失败最多重试 3 次。
- `ReActStep.isFinal()`：`finalAnswer` 非空时结束循环。
- `ReActStep.hasAction()`：非终止步且存在工具名时调用工具。

**双路并存**：`agent.kernel.structured.enabled` 开关（默认 true，可运行期通过设置面板动态切换）。关闭时回退文本 ReAct 作为兜底。通用 Agent 和任务内部推理（`runTask()`）均支持结构化内核。

**维护要点**：
- 文本 ReAct 路径不可删除，是结构化异常的兜底。
- 新业务如只需内部取证，优先用 `runTask()` 而非 `run()`，避免污染会话记忆。
- 结构化输出失败最终应暴露给调用方，便于定位模型或 schema 问题。

---

## 4. 提示词管理（阶段 5.5）

**功能定位**：将散落于 Java 代码中的大模型提示词迁移至数据库表 `llm_prompt_template`，实现热更新（改正文无需重启）。

**数据模型**：
- `prompt_key`：唯一键，与代码调用点一一对应。
- `prompt_type`：`SYSTEM` / `USER`。
- `scene`：所属业务场景，供前端分组展示。
- `content`：提示词正文。
- 只保存当前生效版本，不做版本表（评测结果表已保存 `prompt_hash` / `prompt_snapshot`）。

**`PromptService`**：
- `get(key)`：查库返回正文，查不到直接抛错（fail-loud）。
- `render(key, vars)`：查询模板并替换 `{varName}` 占位符。
- 不做内存缓存（单用户部署，模型调用本身秒级，缓存收益低且引入一致性问题）。

**已迁移的 SYSTEM 提示词**（11 条）：`pre_publish.system`、`feedback_analyze.system`、`feedback_chat.system`、`competitor.system`、`report.system`、`hyde.system`、`reference_cleaning.system`、`long_term_memory.system`、`summary_memory.system`、`agent_executor.system`、`agent_executor_structured.system`。

**已迁移的 USER 模板**（6 条）：对应发布前优化、反馈分析、反馈追问、竞品分析、创作复盘、长期记忆。统一使用 `{varName}` 命名占位符，替代 `%s` 位置占位符。

---

## 5. 成本统计与全链路追溯

### 5.1 LLM API 开销统计（阶段 5.9）

**功能定位**：查看一个创作者任务里所有模型 API 调用的事实记录，服务排查与评测。非账单系统（不做供应商价格换算）。

**统计分类**：`TEXT`（文本大模型）、`EMBEDDING`（向量化模型）、`RERANK`（重排序模型）。

**数据模型**：表 `llm_api_call_log`。
- token 只来自供应商返回的 usage，未返回时保存 null（前端显示"未返回"）。
- 耗时使用后端发起调用到收到响应的时间（毫秒）。
- 状态：`SUCCESS` / `FAILED` / `SKIPPED`（失败和跳过也记录，保证链路完整）。

### 5.2 发布前优化 Agent 化与步骤级开销追溯

**功能定位**：发布前优化主流程默认通过 Agent 执行。Agent 会读取任务材料、创作者偏好和视频类型语境，并在需要时调用 `knowledge_search` 检索案例库。Agent 失败时自动回退直连 LLM，保证用户仍能获得建议。

**开关**：`creator.pre-publish.agent.enabled`（运行期可动态切换，默认 true）。

**工作流步骤新增类型**：`AGENT_REASONING`、`TOOL_CALL`。常见步骤链：`LOAD_CONTEXT -> AGENT_REASONING -> SAVE_RESULT`。

**用量归属**：`LlmUsageContext.openWorkflowStep(...)` 将当前工作流 session 和 step 写入线程上下文。`ToolExecutor` 内部异步线程会显式恢复上下文，确保工具内模型调用能挂到当前步骤。

**步骤级接口**：按工作流 session 查询模型开销，按 step 分组，返回 `totalCalls`、`totalTokens`、`totalElapsedMs` 和 `steps[].calls[]`。

---

## 6. 发布前优化证据化建议链路

**功能定位**：发布前优化在生成建议前先收集证据，让建议从"模型认为应该这样改"升级为"基于具体材料、偏好或案例这样改"，解决可追溯问题。

**证据类型**：
- `TASK_MATERIAL`：标题草稿、简介草稿、文稿、字幕。
- `CREATOR_PREFERENCE`：用户本次手动填写的偏好。
- `CREATOR_CONTEXT`：历史偏好、创作者画像和视频类型语境。
- `REFERENCE_CASE`：案例库检索命中的同类视频。
- `SYSTEM_LIMITATION`：检索不可用、缺少主题等限制说明。

**证据收集组件**：`PrePublishEvidenceCollector`，直连 LLM 和 Agent 路径共用同一证据包。Agent 路径仍可继续调用 `knowledge_search`，但最终回答优先引用已收集证据。

**输出约束**：Prompt 要求建议尽量携带 `evidenceIds`、`confidence`、`assumption`，便于前端展示推荐理由和评测系统检查建议是否有依据。

---

## 7. 设置面板（阶段 5.6）

**功能定位**：面向开发者 / 运维者的全局弹窗，集中处理运行期动态开关、启动期只读配置、基础设施连通性检测和知识库索引运维。

**动态开关**（存 `app_runtime_setting` 表，修改后下一次调用生效）：

| 开关 | key | 影响范围 |
|---|---|---|
| LLM 成本保护 | `agent.llm.guard.enabled` | 调用模型前检查输入长度 |
| 摘要记忆 | `agent.memory.summary.enabled` | 是否读取 / 生成 / 保存摘要 |
| 结构化 Agent 内核 | `agent.kernel.structured.enabled` | 是否用 ReActStep 结构化输出 |
| 案例库 rerank 精排 | `knowledge.rag.rerank.enabled` | 案例检索是否调用 rerank |
| 反馈追问 RAG | `creator.feedback.rag.enabled` | 反馈追问证据是否走向量检索 |
| 发布前优化 Agent | `creator.pre-publish.agent.enabled` | 发布前优化是否走 Agent 路径 |

**只读配置**（需改配置文件并重启）：`knowledge.rag.enabled`、`knowledge.rag.hybrid.enabled`、`agent.memory.short-term.store-type`（影响启动期 Bean 装配）。

**连通性检测**：MySQL、Redis、ChatModel Bean、EmbeddingModel Bean、知识库父/子/hybrid 向量库。检测不主动调用 LLM 或 Embedding，避免产生模型成本。

**前端结构**：`SettingsDrawer.vue`（设置弹窗主体） + `KnowledgeIndexPanels.vue`（知识库索引运维面板，从案例库页面移至设置面板）。

---

## 8. 模块间关系总览

```
数据底座（5.1 知识库 + 5.7 语境库）
    |
    +-- 检索链路（5.2）: 主题优先检索 / 高级检索 / 视频分析上下文
    |       |
    |       +-- 三层分块: 父块 + 主题中块 + 子条目小块
    |       +-- 查询增强 + 混合检索 + Rerank 精排 + SQL 兜底
    |
    +-- Agent 内核（5.4）: 结构化 ReActStep 输出
    |       |
    |       +-- runTask() 任务级推理（不污染会话记忆）
    |       +-- 双路并存（结构化 / 文本），开关可动态切换
    |
    +-- 提示词管理（5.5）: DB 化 + 热更新
    |
    +-- 发布前优化 Agent 化 + 证据化
    |       |
    |       +-- evidenceCollector 收集证据包
    |       +-- Agent 执行（可调用 knowledge_search 检索案例库）
    |       +-- Agent 失败回退直连 LLM
    |
    +-- 成本统计（5.9）: 全链路 API 调用记录 + 步骤级追溯
    |
    +-- 设置面板（5.6）: 动态开关 + 只读配置 + 连通性检测 + 索引运维
```

---

> 以上功能均已完成编译验证与开关两态自测。默认全关时只验证降级、校验与回显链路；启用 RAG 需配置 Embedding/Milvus/LLM Key 后端到端验证。
