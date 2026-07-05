# 阶段 5 - RAG 与 Agent 一体化

> 承接阶段 4（UP 主智能工作台）。阶段 5 把 RAG 从"反馈专用、默认关的最小闭环"升级为产品级能力，并把通用 ReAct Agent 内核与创作工作台合并为一体。

## 一、概述：RAG 体系要解决的业务问题

B 站中小 UP 主在发布前优化、竞品分析和复盘时，AI 给出的建议严重缺少高质量领域知识——Agent 只能凭模型先验回答，"不得编造具体竞品数据"的系统提示词等于主动把领域知识的门关上了。

阶段 5 构建的 RAG 体系以"跨分区优品 + 竞品视频案例库"为核心，注入同赛道被验证过的成功视频数据作为创作参考依据。同时，把通用 ReAct Agent 内核与创作工作台合并为一体，让 Agent 能自主调用知识库工具、以领域证据驱动建议生成，消灭"通用 Agent 与工作台两座孤岛"。

技术方向由四个决策确定：

| 维度 | 决策 |
|---|---|
| 一体化 | 后端以 Agent 为核心，通用 ReAct 内核 + ToolRegistry 编排创作能力 |
| RAG 知识范围 | 跨分区「优品标杆 + 竞品」视频案例库 |
| 检索策略 | 混合检索 + RRF、查询改写/多查询/HyDE、分块 + 父子召回 + 富元数据、Rerank |
| 基础设施 | 可提供但保持开关，默认可关，关掉时优雅降级到 SQL |

**数据边界红线**：部署后端不做任何批量/定时后台采集。知识库语料通过 `scripts/` 下离线采集脚本（本地 cron 跑天/周/月榜 top-N 或定向 BV）产出标准 JSON，再经导入接口合规入库。采集只发生在离线脚本、由作者运行。

## 二、跨分区视频案例知识库（存储底座）

### 2.1 数据来源

三条来源路径：

1. **离线采集脚本**产出的标准 JSON（`scripts/bilibili_reference_fetcher.py`，纯公开接口、自带 WBI 签名、礼貌节流），支持 `rank`（榜单批量）和 `bv`（单 BV 定向）两个子命令。
2. 项目内置 seed 案例，用于演示。
3. 单 BV 一键采集导入（前端输入 BV，后端显式调用脚本，清洗导入一步到位）。

### 2.2 数据清洗流程

导入时一次性做好清洗，成本集中在入库时刻：

- 对每条评论/弹幕做情绪分类（`POSITIVE/NEGATIVE/NEUTRAL`）与噪声判定（`is_noise`），复用反馈侧已有的情绪/噪声关键词分类能力。
- 只保留 `is_noise=0` 且 `sentiment ∈ {POSITIVE, NEGATIVE}` 的条目，中性灌水直接丢弃。
- 每视频通过一次 LLM 摘要调用生成 `highlight_summary`（优质评论/弹幕亮点摘要），失败兜底置空、不回滚整批。
- 单视频优质子条目上限 200，摘要输入预算 8000 字符。

### 2.3 存储模型

两张表构成"父-子"结构，为向量检索的父子召回（small-to-big）打底：

- **父表 `creator_reference_video`**：每视频一张案例卡片，承载标题、简介、标签、分区、层级（`BENCHMARK/COMPETITOR/OWN_HISTORY`）、热度指标、亮点摘要、质量分、向量索引状态。`embedding_status` 管理向量化生命周期（`PENDING/INDEXED/FAILED`）。
- **子表 `creator_reference_video_item`**：清洗后的优质正/负向评论与弹幕（`source_type=COMMENT/DANMAKU`），承载原始观众反馈的精确证据文本。外键是 `video_id`（跨任务），与反馈侧 `creator_feedback_item` 结构对标但维度不同。

导入按 BV 幂等去重（同 `bv_id` 且 `is_deleted=0` 已存在则跳过），去重放在业务逻辑层而非数据库唯一约束（`bv_id` 可空 + 需排除软删除）。

### 2.4 质量打分模型 v1

分四步计算，权重/常数集中在 `KnowledgeQualityProperties`，公式形态固定、数值可调：

**第 1 步（互动率）**：六项热度指标（`view/like/coin/favorite/danmaku/reply`）分别除以播放量，用"率"而非绝对量，避免大播放量碾压视频。`view` 缺失或为 0 时直接不打分（`raw_quality_score=NULL`）。

**第 2 步（加权互动分）**：五项互动率按权重（投币 0.40 / 收藏 0.25 / 点赞 0.20 / 回复 0.10 / 弹幕 0.05）加权求和，取对数压制 B 站长尾爆款：`ln(1 + 1000 * engagement)`。

**第 3 步（情绪因子）**：基于清洗后子表正负向比例计算，含小样本置信度衰减（平滑常数 K=10），避免"2 条评论全是好评"导致极端比例。最终情绪乘子区间 0.7（全差评）~ 1.0（中性）~ 1.3（全好评）。

**第 4 步（分区归一化）**：按 `category` 分组对 `rawScore` 做 min-max 归一化到 0–100。只有同分区有效样本数达到 5 且 `maxRaw > minRaw` 时才产出归一化质量分；样本不足或原始分全等时只保留 `raw_quality_score`，`quality_score=NULL`，通过 `quality_score_reliable` 标记供前端区分展示。

打分时机为"导入后对受影响分区重算"。新增案例会改变该分区 min/max，因此每次导入后对该分区所有视频重算归一化。

### 2.5 向量化策略

**Embedding 选型**：Qwen `text-embedding-v4`，OpenAI 兼容端点（`https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings`），维度 1024（官方推荐平衡点）。批次上限 10（v3/v4 OpenAI 兼容模式硬限制）。

**专用 Milvus 集合隔离**：知识库需要不同集合 + 不同维度（1024），且不能干扰反馈 RAG 的 `ObjectProvider<VectorStore>` 自动配置。设计为在 `KnowledgeVectorStore` 内部自行持有 `MilvusVectorStore` 实例（集合 `creator_reference_video`、维度 1024），不注册为 `VectorStore` 类型的 Spring Bean，从根上避免多 Bean 歧义。通过 `knowledge.rag.enabled` + `EmbeddingModel` 是否存在两层判断门控启用。

**索引模式**：增量索引（只索引 `embedding_status IN ('PENDING','FAILED')` 的条目），已 `INDEXED` 的不重建以节省 Embedding 成本。批大小默认 10，单次上限默认 200 + 接口 @Max(1000) 三重防御。`index/rebuild` 不加 `@Transactional`（Milvus 写入不归 DB 事务管），按批写入 + 按批回写 `embedding_status`，部分失败写 warnings、不整体回滚。

**三层分块体系**（后续扩展）：

| 层级 | 数据表 | 作用 |
|---|---|---|
| 父视频块 | `creator_reference_video` | 回到完整案例上下文 |
| 主题中块 | `creator_reference_video_chunk` | 承载标题包装、内容定位、观众反馈三个主题粒度的检索单元 |
| 原始证据小块 | `creator_reference_video_item` | 承载评论/弹幕原文证据，支持精确 small-to-big 召回 |

主题中块从已入库材料确定性拼装（不调用 LLM 生成新结论），类型为 `TITLE_PACKAGE` / `CONTENT_POSITIONING` / `AUDIENCE_FEEDBACK_SUMMARY`，无反馈素材的视频只生成前两类。中块集合独立于父/子集合，各自拥有独立的 `index/rebuild` 和 `index/status` 端点。

## 三、高级检索链路

检索链路以"先最小闭环，再逐刀叠加"的方式分片落地：5.2a 最小 dense 检索闭环 → 5.2b 查询理解三策略 → 5.2c 子表向量化 + 父子召回 → 5.2d 原生 dense+BM25 hybrid + RRF 融合 → 5.2e qwen3-rerank 精排。

### 3.1 双层检索：通用检索与主题优先检索

系统提供两条独立的检索入口，服务不同场景：

**通用检索**（`POST /search`）：父块 dense + 子块 small-to-big 并行召回，支持 hybrid 增强。适用于 Agent 工具调用等需要全面召回的场景。

**主题优先检索**（`POST /topic-search`）：先 RAG 检索主题中块集合 → 从命中中块聚合 `videoId` → 按质量信号排序形成 top20 候选 → 在候选视频范围内检索相关评论弹幕证据 → rerank 精排。适用于前端用户交互式探索同赛道案例的场景。响应分页（每页 5 张，最多 4 页），用户点击卡片后通过 `GET /{videoId}/analysis-context` 加载该视频的完整主题和评论弹幕上下文。

### 3.2 混合检索（dense + BM25 hybrid）

基于 Milvus 2.5+ 服务端原生 BM25 能力，由新增的 `KnowledgeHybridStore` 实现。该组件内部私有持有 `MilvusClientV2`，自建 schema（含 `text` 字段 + 中文分析器 + `SparseFloatVector` + BM25 Function + dense `FloatVector`），手动 `EmbeddingModel.embed` 算 dense、sparse 由服务端 Function 自动生成。

父/子两集合各自拥有独立 hybrid 集合（`creator_reference_video_hybrid` / `creator_reference_video_item_hybrid`），通过 `knowledge.rag.hybrid.enabled` 开关（默认 false）门控。开关关闭时所有逻辑走 Spring AI 老路径（零回归），打开后检索的父召回收为 dense+BM25+RRF 的 hybridSearch，子召回的 small-to-big 也走 hybrid 子集合。与 rerank / 查询增强自动兼容。

中文分析器设为 `chinese` 类型，对无空格中文分词保证 BM25 召回质量。

### 3.3 重排序（Rerank）

使用 DashScope 的 `qwen3-rerank` 模型（OpenAI 兼容端点 `/compatible-api/v1/reranks`），`gte-rerank` 已于 2026-05-30 下线。调用方式为 RestClient 直连，不引入 DashScope SDK。

默认关闭（`knowledge.rag.rerank.enabled=false`），开启后检索按候选池大小（默认 20）召回更宽候选，精排后截 topK。精排只重排不扩召回，候选越宽精排空间越大。query 用原始 query（非改写/HyDE 扩展文本），保证精排贴近用户真实意图。失败（无 Key/候选小于等于 1/异常）时返回空序、保持原检索顺序，不中断检索。

通用检索和主题优先检索均可叠加 rerank，`reranked` 字段独立回显。

### 3.4 父子召回（small-to-big）

设计原则：父集合零改动、零回归，子召回是附加召回源，子集合建库失败可独立降级。

- **子表向量化**：子条目 `content` 嵌入到独立 child 集合（`creator_reference_video_item`），metadata 含 `videoId`（small-to-big 回父表的关键）、`sentiment`、`sourceType`，并反范式带 `category/tier` 以支持同款过滤。
- **召回链**：父-dense（或 hybrid）与子-dense（或 child hybrid）并行召回 → 按 `videoId` 去重合并（`LinkedHashSet`，父在前子在后）→ 回查父表事实源 → 继续已有尾段（SQL 兜底 / rerank）。子召回失败退父-only、不报错。
- **证据回显**：命中的子条目按 `itemId` 回查 MySQL（is_deleted=0，事实源铁律），挂到对应父卡片作为"召回证据·观众原话"。每卡最多 3 条证据，正/负向用不同色标区分。子召回为空的卡片不显示证据块。

### 3.5 查询理解（查询改写/多查询/HyDE）

三策略统一收敛为"把原始 query → 1~N 条用于 dense 检索的文本"，检索层只拿文本列表、不感知策略。

| 策略 | 产出 | 说明 |
|---|---|---|
| `NONE` | 1 条（原 query） | 显式关闭 / 对照保留 |
| `REWRITE` | 1 条 | 规范化/补词后查询，默认策略 |
| `HYDE` | 1 条 | 假设案例描述（只描述方法共性、禁编造具体 UP 主/播放量） |
| `MULTI_QUERY` | N+1 条 | 多角度查询 + 原 query（N 默认 3） |

基于 Spring AI 原生 Modular RAG 查询组件（`spring-ai-rag`）：`RewriteQueryTransformer` / `MultiQueryExpander` + 自定义 `HydeQueryTransformer`。统一由 `KnowledgeQueryEnhancer` 封装，任一策略异常退回 `[原始 query]`。SQL 兜底始终用原始 query（扩展文本不适合 LIKE 命中）。

### 3.6 优雅降级体系

检索链路内置多层降级屏障，保证演示环境低成本、生产环境可在外部依赖故障时仍返回可用结果：

- `knowledge.rag.enabled=false` 或向量库未就绪 → 直接走 SQL 关键词兜底（整串 LIKE + 质量分排序），不连 Milvus、不调 Embedding、不报错。
- 向量检索运行期异常（连接/维度/超时） → 降级 SQL，记录 warn 日志。
- 查询增强失败 → 退回原始 query 单路检索。
- 子召回异常 → 退父-only。
- Rerank 不可用 → 保持原检索顺序。
- 主题优先检索中：hybrid 任一路失败只记录 warn，继续使用中块 dense / 子向量 / SQL 兜底。

检索模式通过 `mode` 字段回显（`VECTOR` / `SQL` / `VECTOR_WITH_SQL_FALLBACK` / `HYBRID` / `HYBRID_WITH_SQL_FALLBACK` / `TOPIC_VECTOR` / `TOPIC_HYBRID`），前端可直观确认当前走了哪条路。

## 四、Agent 工具化与内核统一

### 4.1 知识库检索工具化

新增 `KnowledgeSearchTool`（`@Component implements Tool`），把 5.2 的检索能力封装为通用 Agent 可调用的领域工具。工具名 `knowledge_search`，输入为纯查询串，输出为格式化可读 Observation（检索模式 + 命中案例摘要 + 最具代表性观众原话）。通过 `@ConditionalOnProperty(prefix="knowledge.rag", name="enabled", havingValue="true")` 门控：RAG 默认关时工具 Bean 不存在，通用 Agent 工具集与之前字节级一致。

### 4.2 发布前优化 Agent 化（两段式 C 方案）

发布前优化从"单次直连 LLM 生成建议"升级为 Agent 驱动：

- **第一段（Agent 自主取证）**：走自研 ReAct 内核的 `AgentExecutor.runTask`（任务维度、不碰会话记忆的轻量循环），工具集含 `knowledge_search`，Agent 自主决定查什么、查几次，产出一段自由文本"检索发现"。
- **第二段（结构化合成）**：把"检索发现"作为新增上下文注入现有固定 JSON prompt，由 11 字段 schema 产出结构化建议（输出 JSON 结构不变，前端零破坏）。

Agent 失败时自动回退旧直连 LLM 链路（创建 `LLM_CALL` 回退步骤），用户主流程不因工具或结构化输出异常完全不可用。

通过 `creator.pre-publish.agent.enabled` 配置开关控制，默认开启，可在运行期切换。

### 4.3 Agent 内核结构化输出升级

自研 ReAct 循环原本以自由文本 + 正则解析每步决策（`Thought/Action/Action Input/Final Answer`），模型格式漂移则解析失败。升级为"json_object + 自校验"：

- 新增结构化原语 `LLMService.chatStructured(system, user, type)`：`response_format=json_object`（API 级保证合法 JSON）+ `BeanOutputConverter`（按目标类型生成 schema 指令并解析为强类型）+ 失败重试（最多 3 次）。
- 定义 `ReActStep`（record）：`thought/action/actionInput/finalAnswer` 四字段，语义与文本版一致但强类型。
- `AgentExecutor` 新增结构化循环（`runStructuredLoop`），与现有文本循环双路并存，由开关 `agent.kernel.structured.enabled`（默认 true，可在设置面板运行时切换）控制。`runStructuredLoop` 为 `run()` 和 `runTask()` 共享，记忆写回抽成 `persistChatTurn` 复用。
- 自研循环骨架、`ToolRegistry`、`ToolExecutor`、记忆模块全部不变，只把"每步从 LLM 获取决策的方式"从正则换成结构化。

## 五、结构化输出升级

除 Agent 内核外，新增的 `LLMService.chatStructured` 泛型原语（`<T> chatStructured(system, user, type)`）作为全应用唯一的结构化输出出口，可被所有需要生成固定 JSON 的业务 Service 复用（发布前优化 11 字段建议等），收敛原先散落各处的手搓 `extractJsonObject` 解析逻辑。

`chatStructured` 复用 `LLMService` 的成本 guard（`validatePromptLength`），设 `responseFormat=JSON_OBJECT`，经 `.entity(type)` 隐式调用 `BeanOutputConverter` 生成 schema 指令并解析强类型 `T`。泛型设计不是过早抽象，已有 5 个以上手搓解析点等着收敛。

## 六、提示词模板 DB 化与热更新

### 6.1 设计目标

把全后端约 17 条硬编码的 Java 文本块提示词搬进数据库，实现修改即时生效（热更新），无需重新编译和重启。提示词按 `prompt_key`（如 `pre_publish.system`）唯一标识，通过读写接口供前端编辑。

### 6.2 表结构与存取

新增 `llm_prompt_template` 表（`prompt_key` / `prompt_type(SYSTEM/USER)` / `scene` / `content` / `description` + 标准四件套），种子数据灌入所有提示词原文。

`PromptService` 提供 `get(key)`（每次直接查库，热更新天然成立，不做内存缓存）、`render(key, Map)`（用户提示词用，命名占位符替换）、`update(key, content)`、`listAll()`。取不到 key 时 fail-loud 抛异常（不静默兜底，确保问题可见）。

### 6.3 占位符：从位置 `%s` 到命名 `{varName}`

用户提示词模板改为命名占位符（如 `{taskName}`），`render` 时传 `Map.of("taskName", 实际值)` 做朴素字符串替换。优势：编辑者看到有意义的名字；不再依赖顺序；不碰 `String.format`（用户文本含 `%` 不炸）；某个变量未传时原样暴露（好排查）。

系统提示词（约 9 条，纯静态文本）直接 `get(key)` 即可；用户提示词（约 6 条）和 `AgentExecutor` 带工具列表占位符的系统提示词（2 条）走 `render(key, map)`。

### 6.4 与评测闭环解耦

提示词版本追踪由评测侧独立承担（`creator_eval_result` 的 `prompt_version/prompt_hash/prompt_snapshot` 在评测时快照），本表只存当前生效版，不做版本/AB 平台。

## 七、创作者语境库（视频类型维度）

### 7.1 定位

区别于 RAG 案例库（同赛道"别人的成功视频"），语境库是"创作者自己的表达规则表"——按视频类型隔离的关键词、黑话、标题套路和慎用表达。不是全网热梗爬虫，也不是替代评论弹幕证据的 RAG。

### 7.2 数据结构

创作任务增加 `video_type` 字段作为语境库隔离维度。新增 `creator_context_term` 表，支持六种词条类型（`KEYWORD/SLANG/MEME/TABOO/TITLE_PATTERN/AUDIENCE_CONCERN`）和五种来源（`USER_SAVE/AI_ACCEPTED/COMMENT_EXTRACTED/USER_REJECTED/VIDEO_SUCCESS`），含 `weight/usage_count/accept_count/reject_count` 字段支撑后续反馈闭环和自动权重调整。

### 7.3 发布前优化接入

`PrePublishSuggestionService` 构建用户提示词时同时读取两类长期上下文：历史偏好（`CreatorPreferenceService`）和视频类型语境（`CreatorContextService.buildPromptContext`）。偏好模式为 `USE_HISTORY` 时正常注入语境，`IGNORE_HISTORY` 时不注入，`EXPERIMENT` 时注入但提示 LLM 只作避坑参考。

### 7.4 为什么不引入向量检索

语境词条不是大规模文档，按 `userId + videoType + weight` 排序的 MySQL 查询即可满足需求。后续可扩展：按场景过滤、按词条类型配额、从评论弹幕候选半自动抽取、失败复盘后自动降权。

## 八、LLM 成本统计与全链路追溯

### 8.1 三个调用维度统一记录

新增 `llm_api_call_log` 流水表，覆盖三类模型调用：

| 类型 | 记录方式 |
|---|---|
| 文本 LLM（TEXT） | `LLMService` 在每次 `chatWithUsage` 后写入（成功/失败均记录） |
| 向量化（EMBEDDING） | `MeteredEmbeddingModel` 代理包装 `EmbeddingModel`，由 `BeanPostProcessor` 自动生效 |
| 重排序（RERANK） | `KnowledgeRerankClient` 在成功/失败/跳过时写入 |

### 8.2 上下文设计

`LlmUsageContext` 以 `ThreadLocal` 保存当前任务追踪元数据（`taskId` / `traceId` / `requestId` / `scene`），避免每层业务方法都传统计参数。提供 `open(taskId, scene)` 用于任务链路、`scene(scene)` 用于无任务 ID 的通用知识库链路。

发布前优化 Agent 化后新增工作流级字段（`workflow_session_id` / `workflow_step_id` / `workflow_step_name` / `workflow_stage`），使模型调用可精准归属到工作流的具体步骤。`ToolExecutor` 在异步工具线程恢复 `LlmUsageContext`（`ThreadLocal` 不跨线程）。

### 8.3 统计接口

任务级总览（`GET /tasks/{taskId}/summary`）返回总调用次数、成功/失败/跳过计数、总 token、总耗时、分类汇总。调用明细（`GET /tasks/{taskId}/calls`）支持分页和按 `modelCategory` 过滤。

工作流级开销（`GET /tasks/{taskId}/workflow/sessions/{sessionId}/usage`）按步骤分组返回模型调用明细，供过程弹窗展示。

### 8.4 边界异常机制

- 写统计失败只打日志，不影响主业务。
- 供应商 usage 缺失保持 `null`，不伪造 0。
- Rerank 未启用/未配置 Key/候选不足时记录 `SKIPPED`。
- 统计不接入价格换算（不同供应商定价变动较快，本阶段只做事实记录）。
- 前端刷新统计失败时不覆盖主流程成功提示。

## 九、设置面板

### 9.1 定位

把运行期开关、启动期配置状态、基础设施连通性检测、知识库索引运维集中进全局设置弹窗。服务开发者 / 运维者 / 演示者，不改变创作者主流程。

### 9.2 动态开关与只读状态

五组可通过设置面板运行时切换的开关（写入 `app_runtime_setting` 表，重启后仍保留覆盖值）：

| 开关 | 接入点 | 默认值 |
|---|---|---|
| `agent.llm.guard.enabled` | `LLMService.validatePromptLength` | true |
| `agent.memory.summary.enabled` | `SummaryMemory` 摘要判断/保存 | false |
| `agent.kernel.structured.enabled` | `AgentExecutor` 结构化/文本循环切换 | true |
| `knowledge.rag.rerank.enabled` | 检索服务与 Rerank 客户端 | false |
| `creator.feedback.rag.enabled` | 反馈证据检索与索引 | false |

三组启动期只读状态（必须改配置 + 重启，不在运行期切换）：

| 配置 | 原因 |
|---|---|
| `knowledge.rag.enabled` | 影响 `KnowledgeSearchTool` Bean 是否创建 |
| `knowledge.rag.hybrid.enabled` | hybrid 客户端和集合连接在启动期初始化 |
| `agent.memory.short-term.store-type` | 启动期 Bean 二选一（memory/redis） |

### 9.3 连通性检测

按需检测 MySQL、Redis、知识库父/子向量库、hybrid 向量库、Embedding Bean、ChatModel Bean。检测不发起真实 LLM/Embedding 请求（避免设置页产生模型费用）。状态枚举为 `UP/DOWN/DISABLED/UNKNOWN`。

### 9.4 索引运维收口

将案例库页面原有的 4 个索引运维面板（父向量索引、子条目索引、原生 hybrid 索引、子 hybrid 索引）迁入设置弹窗。案例库页面不再保留索引入口，保持日常浏览信息层级清晰。

## 十、关键设计决策汇总

| 决策 | 选择 | 理由 |
|---|---|---|
| 一体化路线 | Agent 中心（β 激进路线） | 通用 ReAct 内核 + ToolRegistry 编排创作能力，消灭两座孤岛 |
| RAG 知识范围 | 跨分区优品 + 竞品案例库 | 给 Agent 注入同赛道领域知识，不只检索创作者自己的历史 |
| 数据采集 | 离线脚本 + 作者本地 cron | 部署后端零采集（合规红线）。离线脚本用系统 cron 跑天/周/月榜 top-N 或定向 BV |
| 混合检索 | Milvus 原生 dense+BM25 hybid（v2 client） | 走服务端正统路线，非 Java 层 RRF 拼接 |
| 父子召回 | 独立 child 集合 + small-to-big | 精确评论命中上卷回父卡片，子集合失败独立降级 |
| Rerank | qwen3-rerank（OpenAI 兼容端点） | 替代已下线的 `gte-rerank`，默认关，失败降级不中断检索 |
| 主题中块 | 确定性拼装，不调 LLM 生成 | 只解决召回粒度问题，不引入新的可编造摘要层 |
| 检索入口 | 通用 `/search` + 主题优先 `/topic-search` | 面向 Agent 工具调用 vs 用户交互式探索，两条独立链路 |
| Agent 结构化输出 | json_object + 自校验（非 strict function-calling） | 保留自研循环可移植性，每步决策强类型化 |
| 发布前优化 Agent 化 | 两段式 C 方案（Agent 取证 + 结构化合成） | Agent 真正自主调用工具取证，输出 JSON 零风险、前端零破坏 |
| 提示词存储 | DB 热更新（非缓存） | 每次查库即天然热更新，不做内存缓存杜绝一致性负担 |
| 用户提示词占位符 | 命名占位符 `{varName}` | 前端编辑安全，不依赖 `String.format` 顺序和转义 |
| 提示词版本 | 不做版本/AB 平台 | 版本追踪已由评测侧独立快照闭环 |
| 配置开关体系 | 三元：启动期 Bean 条件 + yml 默认 + 运行期 DB 覆盖 | 不破坏 Bean 装配的前提下可运行时调整 |
| 成本统计 | ThreadLocal 上下文 + `BeanPostProcessor` Embedding 代理 | 最大程度减少业务方法签名污染，覆盖三类模型调用 |
| 基础设施默认策略 | 默认可关 + 优雅降级到 SQL | 演示成本可控，对外部依赖故障容忍 |
| 降级哲学 | 降级 = 正常路径，非错误 | RAG 关 → SQL 正常返回，向量失败 → SQL 兜底，子召回失败 → 退父-only，rerank 失败 → 保持原序 |
| 创作者语境库 | MySQL 直查，不引入向量检索 | 词条量小、非文档语义召回型数据，"创作者自己的表达规则表" |
