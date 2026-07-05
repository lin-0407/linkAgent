# 阶段 6 -- Multi-Agent 与前端体验优化

> 本文档为阶段 6 及其衍生子阶段（6.1-6.5）、P0 重构、前端体验优化和金标准评测集设计的整合版，聚焦架构决策与业务流程。
> 详细接口定义、代码片段和验证命令已从本文档移除，相关设计参见原分散文档及各阶段对应的 `/docs/reference/` 说明文档。

---

## 一、Multi-Agent 架构

### 1.1 执行模式体系

阶段 6 在原有单一 ReAct 循环基础上引入三种执行模式，由 `AgentExecutionMode` 枚举定义。

| 模式 | 说明 |
|---|---|
| `AUTO` | 默认模式，后端用轻量规则选择路径，不额外调用模型做路由。 |
| `REACT` | 保持现有结构化 ReAct / 文本 ReAct 兜底。 |
| `PLAN_EXECUTE` | 先生成结构化计划，再按计划执行工具，最后由 Synthesizer 合成回答。 |
| `MULTI_AGENT` | 先生成 Worker 调度计划，再由独立 Worker Agent 并发执行，最终合成回答。 |

`AUTO` 模式的路由规则：复杂关键词或长输入走 PaE；多视角、竞品、评论弹幕、复盘类请求走 Multi Agent；其余走 ReAct。

### 1.2 PaE 模式（Plan and Execute）

核心角色分离：

- **Planner**：只输出结构化计划（`AgentPlan`），包含 `AgentPlanStep` 列表，每个步骤指定 `action` 和 `actionInput`。
- **Executor**：只按计划调用工具，不自行决定下一步做什么。每步执行结果记录为 `PlanStepExecution`。
- **Synthesizer**：只基于计划和观察结果合成最终回答。

执行链路：

```
AgentExecutor → AgentExecutionModeRouter → PlanAndExecuteAgent
  → AgentPlanner.chatStructured → ToolExecutor.execute
  → AgentAnswerSynthesizer.chat → AgentChatResponse(planTrace)
```

计划轨迹通过 `AgentPlanTrace` 记录，包含所有步骤的执行状态、输入输出和时间线。

### 1.3 Multi Agent 模式

在 PaE 基础上引入角色分工更细的 Worker Agent 体系。

核心角色：

- **MultiAgentOrchestrator**：协调 Planner、Worker 和 Synthesizer 的执行。
- **MultiAgentPlanner**：分析用户请求，生成 `WorkerPlan`（包含 `WorkerCall` 列表和 `dependsOn` 依赖关系）。
- **WorkerAgent**：独立的可执行单元，第一版提供两种 Worker：
  - `plan_execute_worker`：内部复用 PaE，适合需要工具取证的子任务。
  - `direct_reasoning_worker`：直接 LLM 推理，适合解释、归纳、改写、创作建议。
- **AgentAnswerSynthesizer**：基于所有 Worker 结果合成最终回答。

### 1.4 Worker 并发执行

`MultiAgentOrchestrator` 按 `dependsOn` 构建依赖层级图，每轮找出所有依赖已完成的 Worker，使用 `CompletableFuture` 并行执行。同一轮内的 Worker 无依赖关系，因此可安全并发。

并发上限通过常量 `DEFAULT_MAX_PARALLEL_WORKERS = 4` 控制，避免一次性打满 LLM 并发配额。

依赖异常处理：依赖不存在、依赖失败或依赖形成循环时，后续 Worker 标记为 `SKIPPED`，不继续执行。

### 1.5 证据化引用审查

为解决 Synthesizer 输出缺少可追溯证据链的问题，引入结构化摘要、证据对象和引用合成机制。

**结构化摘要与证据**：

- `WorkerBrief`：Worker 执行后的核心结论、关键点、置信度、证据 ID、未解决问题。
- `AgentEvidence`：证据 ID、来源类型（`EvidenceSourceType`）、来源位置、内容摘录、置信度。
  - `EvidenceSourceType` 区分：工具观察、用户输入、上下文、Worker 推理、系统限制。
- `PlanExecuteWorkerAgent` 把 PaE 成功执行的 `PlanStepExecution.observation` 转为工具观察证据。
- `DirectReasoningWorkerAgent` 只能产出 `WORKER_REASONING` 类型证据，Synthesizer 将其视为建议依据而非外部事实。

**引用合成**：

`AgentAnswerSynthesizer` 要求模型输出 `CitedAnswer`（结构化回答，每条 `statement` 带 `evidenceIds`），后端将结构化答案渲染为旧字段 `finalAnswer`（如 `[W1-P2-E1]` 引用标记），保持与旧调用方兼容。

### 1.6 回答完备性审查

新增 `AgentAnswerAuditor`，在 Synthesizer 完成后审查回答质量。审查维度：

- 是否完整回答用户问题。
- 是否自相矛盾。
- 是否有未引用的事实性断言。
- 是否把 Worker 推理当成外部事实。

审查不通过时，Synthesizer 最多重写 2 轮。仍不通过时保留保守答案并在末尾追加审查提示。审查失败不影响主回答返回。

### 1.7 PaE 重规划

阶段 6.2 在 PaE 中增加受控 Replanner，解决一次性计划僵硬执行的问题。

**触发条件**：工具不存在、工具调用失败或返回结果不足以支撑后续步骤。

**核心机制**：

- 新增 `PlanExecutionState`，包含原始目标、已执行步骤事实、剩余步骤和失败指纹（失败的 `action + actionInput` 列表）。
- 新增 `AgentReplanner`，输入用户请求、对话上下文、执行状态和工具清单，只重规划剩余步骤，不重写已成功的步骤。
- 重规划步骤通过 `AgentPlanNormalizer.reindexRemainingSteps` 重新编号。
- 最多重规划 2 次，防止来回振荡。Replanner 失败时保留原剩余计划，不成为主链路单点。

---

## 二、AI 交互式创作与工作流重构

阶段 6.3 将原有"用户手动填表驱动流程"改为"用户输入想法、AI 主动推进任务"的交互式创作工作台。

### 2.1 三阶段主流程

```
用户输入创作想法
  → AI 生成 3 个创意卡片
  → 用户选择并确认一个创意卡片
  → AI 进入发布前优化，主动追问缺失信息
  → AI 生成发布方案
  → 用户确认发布方案
  → 任务进入等待绑定 BV 号
  → 用户绑定 B 站 UID 和任务 BV 号
  → 视频分析页同步账号视频
  → 只展示已和任务绑定的公开视频卡片
  → 用户选择视频卡片
  → 自动采集评论、弹幕和基础指标
  → LLM 生成完整视频分析报告
  → 导出复盘报告
```

### 2.2 第一阶段：创意方案

用户输入自然语言想法，AI 返回 3 个结构化创意卡片，每张卡片包含创意名称、适合人群、标题大纲、内容大纲、简介大纲、亮点、风险和 AI 建议。

卡片交互支持"选择这个方向""让 AI 微调""重新生成"三种操作。确认后系统保存原始想法、三张候选卡片和用户选择的卡片。

### 2.3 第二阶段：发布前优化

用户确认创意卡片后自动进入发布前优化。AI 主动追问缺失信息，把固定表单字段转为自然语言追问，不再要求用户填固定表单。

文稿/字幕缺失时，AI 先询问用户是否继续补充已有素材；用户明确表示没有时，基于第一阶段创意卡片生成一版可编辑文稿草稿（标记为"AI 草稿"）。

发布方案生成后通过确认层承载，包含最终标题建议、简介正文、内容结构建议、标签和分区建议、发布前风险和发布检查清单。确认后任务进入 `WAITING_BV_BINDING` 状态。

### 2.4 第三阶段：视频分析与复盘

独立完整页面，不复用 AI 交互台作为主体。页面核心区域：

- 顶部：B 站 UID 绑定状态、同步按钮、最近同步时间。
- 主体：已绑定任务视频卡片列表，只展示 `creator_task_video_binding` 中 `binding_status = 'BOUND'` 且属于当前 UID 公开视频列表的条目。
- 详情：完整视频分析报告、证据列表、追问入口、导出入口。

评论弹幕分析和复盘报告统一为"视频分析与复盘"阶段，不再拆分。采集沿用限量样例思路（评论上限约 300 条、弹幕上限约 500 条），避免成本失控。

### 2.5 BV 绑定与 UID 绑定

- BV 号必须符合 B 站 BV 格式，一个任务第一版只绑定一个 BV。
- UID 绑定用于记录创作者 B 站公开 UID，校验绑定 BV 是否属于该创作者公开列表。
- 绑定异常（UID 与 BV 不匹配）时展示为绑定异常状态，不进入正常视频卡片列表。

### 2.6 开销统计全局化

开销统计从任务阶段栏移除，改为两个独立入口：

- 全局任务统计：顶部导航"开销"入口，查看所有任务的总调用、总 token、总耗时和失败次数。
- 单任务统计：任务详情高级区，查看当前任务的成本分布和失败明细。

---

## 三、证据化建议链路与审查器

### 3.1 证据化建议链路（阶段 6.4）

发布前优化建议从"直接给结论"升级为"带证据回查的建议"。在建议生成前通过 `PrePublishEvidenceCollector` 收集证据包，来源包括：

- 任务材料（字幕、文稿、标题草稿、简介草稿）。
- 手动偏好（创作者当前填写的风格偏好和创作目标）。
- 历史偏好与类型语境（`creator_preference`、`creator_context_term`）。
- 同类案例检索结果。

证据对象 `PrePublishEvidenceRef` 包含证据 ID、类型、来源名、来源标识、原文摘录、摘要和置信度。

`creator_suggestion` 表新增 `evidence_refs`、`missing_info`、`generation_mode`、`quality_status` 四个字段，分别记录可引用证据、缺失信息、生成模式和质量状态。

直连 LLM 路径和 Agent 路径共用同一份证据包，保证两种建议生成方式的一致性。

### 3.2 建议审查器（阶段 6.5）

新增 `PrePublishSuggestionAuditor`，使用确定性规则（非 LLM 调用）审查建议质量。

审查维度：

- `titleSuggestions` 每条带 `evidenceIds`。
- `actionableRevisionPlan` 的 `HIGH` 优先级动作带 `evidenceIds`。
- 所有 `evidenceIds` 来自当前证据包，不出现编造编号。
- 建议文本不出现"必爆""爆款保证""完播率翻倍""推荐算法会"等夸大承诺。
- 结构化字段必须是合法 JSON 数组。

审查报告结构包括 `status`（`AUDIT_PASSED`/`AUDIT_WARNED`/`AUDIT_FAILED`/`AUDIT_SKIPPED`）、`score`、`summary` 和 `issues` 列表。

第一版不自动重写，不阻断建议入库。原因是规则可能存在误判，先把问题暴露出来比直接影响用户结果更稳妥。

---

## 四、P0 主链路验收与代码审计

### 4.1 主链路验收清单

P0 主链路覆盖从创作想法到复盘结论的最小可用闭环，共 9 个节点：

1. 创意输入 -- 创建创作任务与交互会话。
2. 生成方案 -- 返回 3 个结构化创意卡片。
3. 选择方向 -- 标记已选方案并进入发布前优化。
4. 发布前优化 -- 生成标题、简介、标签、风险点和修改计划。
5. 确认方案 -- 固化发布方案。
6. 绑定视频 -- 绑定任务与已发布视频（BV 号 + B 站 UID）。
7. 视频展示 -- 视频分析页只展示已绑定任务的视频卡片。
8. 反馈复盘 -- 输出高频观点、情绪、争议点和下一期建议。
9. 偏好沉淀 -- 下一次创作能读取偏好与语境。

每个节点均定义了预期输出、后端模块、前端入口、数据落点和必验失败态。

### 4.2 代码审计核心发现

对前端 26 个源文件（约 10,577 行）进行了只读审计，关键发现：

- `CreatorWorkspace.vue` 以 5,331 行占前端总代码量的 50.4%，包含约 90 个响应式变量、约 120 个方法和约 45 个计算属性，是前端瓶颈节点。
- 22 个 `isLoading*` / `isXxxing*` 变量全部手动管理，容易出现"加载态忘记重置"的 Bug。
- `requestJson` / `readErrorMessage` 在 3 个 API 模块中重复实现，缺失统一 base URL、请求超时、Token 注入和重试逻辑等能力。
- SSE 连接与组件紧耦合，无自动重连机制。
- 路由实例已创建但未使用，页面切换通过 `v-if` 实现。

### 4.3 P0+P1 修复技术方案

识别了 6 个需修复的问题：

| 优先级 | 问题 | 方案要点 |
|---|---|---|
| P0 | 结构化路径 token 丢失 | 新增 `chatStructuredWithUsage()` 方法，使用 `.chatResponse()` 替代 `.entity(type)`，从 `ChatResponse` 提取 usage，通过 `StructuredCallResult` 泛型记录返回。 |
| P0 | Agent 持久化表零代码访问 | 新增 4 个 Record 模型 + 2 个 Mapper，在 `AgentExecutor` 的 5 个关键节点（入口、每步、对话、成功、失败）插入持久化调用，全部 DB 写入 try-catch 包裹。 |
| P1 | 竞品分析前端无入口 | 融入参考案例体系，通过 BV 导入自动抓取，竞品卡片一键触发对比。复用已有 `KnowledgeReferenceFetchService` 的 BV 导入管道。 |
| P1 | 长期记忆前端无入口 | 新增独立管理页面 + DELETE 端点，支持搜索、筛选和软删除。 |
| P1 | 创作者画像前端无入口 | 新增顶部全局头像 Popover，展示风格标签、语气偏好和受众认知。 |
| P1 | 用户 API Key 配置缺失 | 新增 `user_llm_config` 表 + AES-256-GCM 加密，设置面板支持多 Provider Key 管理。 |

实施顺序：P0-A（token 追踪）→ P0-B（Agent 持久化）为串行依赖；P1-2/P1-3/P1-4/P1-1 可并行推进。

---

## 五、前端体验优化

### 5.1 用户化重构（P0）

将前端从"开发者验证台"改为"面向 B 站 UP 主的视频发布与复盘助手"，核心改动：

- 一级导航调整为：创作台、参考案例、项目列表、设置。
- 普通模式隐藏工作流消息、执行步骤、API 开销、评测样例、检索策略、索引状态等工程信息。
- 开发者模式使用 `localStorage` 保存前端开关，不改变后端响应结构。
- 术语替换：Agent → AI 助手、工作流 → 处理流程、Token → 开发者模式显示等。
- 创作台主流程固定为四步：视频资料 → 发布方案 → 观众反馈 → 复盘报告。
- 卡片样式统一 8px 圆角，禁止卡片套卡片。

### 5.2 前端体验提升方案

**建议卡片化与即时反馈**：

- 新增 `SuggestionCard.vue` 可复用建议卡片，支持 type（title/tag/desc）切样式，含采纳/复制/不太好按钮。
- 新增 `SuggestionRejectPanel.vue` 原因单选面板（6 个预设原因 + 其他自定义）。
- 反馈事件通过 `creator_event` 表持久化，触发创作者画像增量更新。

**Agent 思考过程时间轴**：

- 新增 `AnalysisProgress.vue` 组件，渲染完成/运行中（脉冲动画）/等待/失败四态步骤列表。
- 修正 `useWorkflowSSE` 的 step handler 签名，从无参改为接收 `WorkflowStepEvent`，消费后端已推送的 `userLabel`/`userDetail`/`durationMs` 字段。
- 对普通用户也可见，建立分析过程信任感。

**运行时状态栏**：

- 新增 `SystemStatusBar.vue` 底部固定状态栏，三组指示灯（LLM/向量库/实时通道）。
- 轮询 `/api/settings/connectivity/check`（60 秒间隔），不展示无后端接口支撑的"本月用量"数据。

### 5.3 CreatorWorkspace 拆分（阶段二）

`CreatorWorkspace.vue` 从约 4,800 行拆分为：

- 主壳：保留 composable 实例化、SSE 编排、弹窗（结果弹窗/工作流消息/过程/语境库/开发者测试）、tab 导航和生命周期管理。
- 6 个子组件：`TaskListPanel.vue`、`MaterialsTab.vue`、`PrePublishTab.vue`、`FeedbackTab.vue`、`ReportTab.vue`、`UsageTab.vue`。
- 1 个工具文件：`creatorWorkspaceUtils.ts`（分类格式化、标签翻译、状态判断等纯函数）。
- 1 个 context 文件：`useCreatorWorkspaceContext.ts`（provide/inject 注入入口）。

拆分策略：module 提升 + template 下沉。所有 composable 在主壳实例化并 provide，子组件 inject 同一实例，保证 SSE 单连接、跨 tab 数据共享和任务恢复逻辑不变。

### 5.4 视觉与响应式收口（P0-4）

- 桌面端保留左侧流程栏，移动端新增 `.creator-mobile-progress`（"第 N/4 步 + 当前步骤名称 + 进度条"）。
- 主要按钮和交互入口统一 `min-height: 44px`，满足移动端最小触控目标。
- 创作台主容器、结果块、表单网格和卡片补充 `min-width: 0`，消除横向溢出。
- 验收宽度：375px、768px、1440px 三档。

---

## 六、金标准评测集设计

金标准集 `PRE_PUBLISH_GOLDEN_V1` 用于评测发布前优化阶段的 Agent 输出质量，不追求唯一标准答案。

### 6.1 设计原则

- 评测创作判断而非固定答案，使用期望能力点和负向约束评分。
- 优先覆盖中小 UP 主的真实痛点：选题灵感、表达角度、标题包装、开头留人和平台语境。
- 纳入当代互联网语境：抽象表达、梗文化、反差、自嘲和弹幕语感。
- 网感不是硬套热梗，而是判断内容是否适合玩梗、适合轻度还是重度抽象。
- 保留风险边界：涉及严肃科普、法律、健康、财经时，不能为了网感牺牲准确性。

### 6.2 V1 规模与覆盖

共 36 条样例（已沉淀为 `docs/develop/pre_publish_golden_v1_cases.jsonl`），覆盖 8 个类型：

| 类型 | 数量 | 评测重点 |
|---|---|---|
| 灵感枯竭/选题角度 | 6 | 从普通素材提炼传播价值 |
| 标题包装/点击动机 | 6 | 提升点击欲但不标题党 |
| 开头与结构优化 | 5 | 降低前 30 秒流失风险 |
| 专业内容大众化 | 5 | 硬内容讲给普通观众 |
| 同质化赛道差异化 | 4 | 避免"别人都讲过" |
| 风险与争议边界 | 4 | 识别夸大、误导、敏感表达 |
| 标签/简介/分区 | 3 | 发布包装 |
| 历史反馈转下一期 | 3 | 评论弹幕转新视频灵感 |

### 6.3 评分体系

| 评分项 | 发布前优化语境下的含义 |
|---|---|
| `readabilityScore` | 建议是否清楚好懂 |
| `relevanceScore` | 是否贴合材料、视频类型和目标观众 |
| `completenessScore` | 是否覆盖标题、开头、标签、风险、修改计划 |
| `accuracyScore` | 是否基于材料，不编造事实 |
| `stabilityScore` | 同类输入下是否稳定输出有用建议 |
| `costScore` | 输出是否克制，不堆长文 |
| `explainabilityScore` | 是否说明为什么这样改 |
| `inspirationQuality` | 是否给出可拍、可点、有差异化的角度（人工项） |
| `internetSense` | 是否具备 B 站语境下的网感和抽象表达适配（人工项） |
| `actionability` | 建议是否可直接改标题、改开头、改脚本（人工项） |

配套测试类 `PrePublishAgentGoldenQualityTest` 使用真实 `AgentExecutor` 调用发布前优化 Agent，通过 JVM 参数 `-Dlinkagent.golden.prepublish.enabled=true` 显式启用，避免日常单测产生模型调用成本。

---

## 七、关键设计决策汇总

| 决策 | 原因 |
|---|---|
| `AUTO` 模式不调用模型做路由 | 避免每次聊天先产生一次额外 LLM 成本。 |
| Planner/Executor/Synthesizer 角色完全分离 | 每个角色职责单一，便于独立调优和替换。 |
| Worker 并发上限设为 4 | 避免一次请求把 LLM 并发配额打满，后续可外置到配置。 |
| 证据引用用 `evidenceIds` 而非全文摘录 | 引用可追溯但不膨胀 Synthesizer 上下文。 |
| 审查器不自动重写建议 | 第一版规则可能有误判，先暴露问题比直接影响用户结果更稳妥。 |
| Replanner 只重规划剩余步骤 | 不重写已成功的步骤，避免有效工作被丢弃。 |
| 重规划最多 2 次 | 防止在失败路径上来回振荡。 |
| 视频分析页是独立完整页面 | 该阶段用户关注信息密度高的分析结果，弹窗承载不足。 |
| 文稿缺失时 AI 先生成可编辑草稿 | Agent 应先帮用户把想法落成可修改内容，而非把流程卡死在材料缺失上。 |
| 开销统计从任务阶段栏移除 | 开销统计是全局/单任务监控能力，不应抢占创作流程。 |
| 普通模式隐藏工程词 | 面向 B 站 UP 主的创作助手不需要理解 Agent/SSE/RAG 等概念。 |
| 开发者模式使用 localStorage 前端开关 | 不改变后端响应结构，避免重构误伤已有调试能力。 |
| CreatorWorkspace 拆分用 provide/inject 而非 Pinia | 保证 SSE 单连接和跨 tab 数据共享，不引入额外的持久化层。 |
| 金标准集不追求唯一标准答案 | 视频创作建议是开放式创意任务，评分应为期望能力点覆盖和负向约束。 |
| Token 追踪用 `.chatResponse()` 替代 `.entity(type)` | `.entity()` 丢弃了 `ChatResponse.usage`，结构化路径的 token 不可追溯。 |
| Agent 持久化写入用 try-catch 包裹 | 与 `saveLongTermMemory` 的异常策略一致，不因 DB 写入失败影响回答返回。 |
