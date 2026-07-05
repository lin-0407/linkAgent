# 阶段 0-2：基础 Agent 与记忆系统

## 概述

本阶段解决的核心业务问题：构建一个具备自主推理能力的 Agent 内核，使其能够调用工具完成多步问题求解，并在此基础上建立分层记忆系统（短期记忆、摘要记忆、长期记忆），让 Agent 在跨会话、跨轮次的对话中保持上下文连贯性。

整体技术路径为三个阶段：
1. 验证 LLM 连通性，建立最简调用链路。
2. 实现 ReAct 编排内核，使 Agent 具备 Thought-Action-Observation 多步推理能力。
3. 围绕 Agent 内核搭建记忆系统：进程内短期记忆 -> Redis 持久化短期记忆 -> 摘要触发与裁剪 -> 长期记忆 MySQL 读写 -> 长期记忆自动抽取。

---

## 核心架构决策

### 决策 1：经典 ReAct 文本解析，而非 Spring AI 原生 Tool Calling

Spring AI 提供了 `@Tool` 注解和自动 Function Calling 机制，但本项目选择手动实现 ReAct 循环。理由：
- 体现 Agent 编排原理的完整掌握，不依赖框架黑盒。
- 正则解析 LLM 自由文本输出，使 Agent 与具体模型解耦（DeepSeek、OpenAI 等均可复用同一套循环逻辑）。
- 便于后续升级为结构化输出（JSON 模式）或 Plan-and-Execute 等更高级编排策略时，保留可控的解析层。

### 决策 2：Conversation-as-Text 通信模式

每次 LLM 调用将完整对话历史作为单条文本消息发送，而非维护消息列表（List<Message>）。理由：
- 对话历史拼接方式完全由 AgentExecutor 控制，不依赖框架的 Message 抽象。
- 经典 ReAct 论文中的通信方式即为文本拼接，与正则解析天然配合。
- 短期记忆、摘要记忆的注入位置和格式完全由业务层决定。

### 决策 3：记忆分层架构

记忆系统分为三层，各司其职：

| 记忆层 | 存储后端 | 生命周期 | 职责 |
|--------|----------|----------|------|
| 短期记忆 | 进程内 Map / Redis List | 单会话 | 保留最近 N 条消息，为 LLM 提供对话上下文 |
| 摘要记忆 | 短期记忆存储内 | 单会话 | 当短期消息量超阈值时，将历史压缩为摘要，承接更早上下文 |
| 长期记忆 | MySQL | 跨会话、按用户维度 | 存储用户偏好、身份、项目约束等跨会话复用的事实 |

三层记忆的分工逻辑：短期记忆保证当前对话的即时连贯性；摘要记忆解决长对话的 Token 膨胀问题；长期记忆让 Agent 在不同会话间记住用户偏好。

### 决策 4：滑动窗口策略 — 按消息数裁剪，不做 Token 级裁剪

短期记忆的淘汰策略是按消息数（默认 10 条）执行滑动窗口裁剪，而非按 Token 数。理由：
- Token 估算依赖具体模型的分词算法，在记忆系统早期引入会增加复杂度。
- 消息数裁剪足够验证滑窗语义和 Prompt 拼接方式。
- 后续接入 Token 计数能力后，可从消息数裁剪平滑升级为 Token 级裁剪。

### 决策 5：摘要触发策略 — 消息数阈值 + 摘要后裁剪

摘要触发条件：摘要记忆功能开启，且当前会话短期消息数超过配置阈值时，调用 LLM 生成摘要，保存到当前会话。

摘要生成后执行短期记忆裁剪：只保留最近 N 条消息（默认 2 条），其余由摘要承接。默认保留 2 条的设定原因是一次完整问答追加 Human 和 AI 两条消息，保留最近一轮原文使对话衔接更自然。

### 决策 6：长期记忆自动抽取 — 同步调用 + 保守策略

长期记忆抽取在 Agent 每轮回答后同步执行：将"用户消息 + Agent 最终回答"提交给 LLM，由 LLM 判断是否值得长期保存。理由：
- 同步调用使链路最简单、最容易观察和调试。
- 后续若发现响应耗时明显增加，再改为异步任务。

抽取策略必须保守：宁可漏掉可记内容，也不把临时问题和噪声写入长期记忆。只保存以下类型：
- 用户长期偏好（示例语言、解释风格）
- 用户稳定身份（职业方向、学习目标）
- 项目长期信息（技术栈、定位、固定约束）
- 用户明确要求后续遵守的规则

不保存：临时问题、一次性报错、天气时间、普通闲聊、工具调用结果。

---

## 关键实现逻辑

### ReAct 主循环设计

AgentExecutor.run() 实现的核心流程：

1. 构建系统提示词（列出可用工具、定义 Thought/Action/Action Input/Final Answer 输出格式）。
2. conversation 以 "Human:{userMessage}" 开头。
3. 进入循环：
   - 迭代计数递增，超过 MAX_ITERATIONS（10）则终止并返回错误。
   - 拼接当前对话上下文（短期记忆、摘要记忆、长期记忆）到 Prompt。
   - 调用 LLM，获取完整响应文本。
   - 正则解析：优先匹配 Final Answer，其次 Action，最后失败则反馈格式错误让 LLM 重试。
   - 若为 Action：从 ToolRegistry 获取工具并执行，将 Observation 拼回 conversation。
   - 若为 Final Answer：退出循环，返回最终答案。
4. 最终答案生成后，触发长期记忆自动抽取。

### 正则解析优先级

四个正则 Pattern 按优先级解析 LLM 自由文本输出：
- `Final Answer:\s*(.*)` — 最高优先级，匹配到即终止循环（使用 `Pattern.DOTALL` 跨行捕获）。
- `Action:\s*(\w+)` — 匹配工具名。
- `Action Input:\s*(.+?)(?:\\n|$)` — 匹配工具入参（非贪婪到行尾）。
- `Thought:\s*(.*?)(?:\\n|$)` — 匹配推理过程。

解析失败时的容错策略：Thought 为空直接终止（正则已足够宽松）；Action/Action Input 格式错误则向 conversation 追加错误提示并 continue 进入下一轮，给 LLM 修正机会。

### 工具注册与执行

Tool 接口定义三个方法：`getName()`、`getDescription()`、`execute(String input)`。Tool 实现类通过 `@Component` 标注，由 `ToolRegistry` 通过构造器注入 `List<Tool>` 自动收集所有实现，在 `@PostConstruct` 中注册到内部 Map。

阶段 1 实现三个内置工具：
- `datetime`：纯 JDK `LocalDateTime.now()`，查询当前日期时间。
- `calculator`：Spring 自带 `SpelExpressionParser` 进行数学表达式求值。
- `web_search`：调用 `wttr.in` 免费天气 API，无需 API Key，返回纯文本。

Tool 执行异常时捕获并返回 `"Error: 异常信息"` 给 LLM，由 LLM 决定后续策略。

### 短期记忆数据流

1. 前端请求携带可选 `sessionId`，若为空则后端生成 UUID 作为新会话标识。
2. AgentExecutor 调用 LLM 前，从 ShortTermMemory 读取该会话的最近消息，拼接为对话历史上下文。
3. Agent 成功返回 Final Answer 后，追加 Human 和 AI 两条消息到 ShortTermMemory。
4. 追加后若消息数超过上限，从队头删除最旧消息（滑动窗口）。

ShortTermMemoryStore 接口定义了 `append()`、`getRecentMessages()`、`replaceMessages()` 等操作方法，ShortTermMemory 作为业务入口负责窗口大小控制和调用委托。

### 存储实现切换

默认使用进程内 Map（InMemoryShortTermMemoryStore），通过配置 `agent.memory.short-term.store-type=redis` 切换到 RedisShortTermMemoryStore。切换对 AgentExecutor 完全透明。

Redis 实现使用 List 数据结构：写入时 `RPUSH` 后执行 `LTRIM key -maxMessages -1` 实现滑动窗口裁剪。

### 长期记忆数据流

1. AgentExecutor 执行前，从 LongTermMemory 读取当前用户的最近 10 条长期记忆，注入到 Prompt 的 `Long-term memory` 区块。
2. Agent 成功返回 Final Answer 后，LongTermMemoryExtractor 调用 LLM 判断本轮对话是否包含值得长期保存的信息。
3. 若 LLM 返回 `shouldRemember=true`，将候选写入 MySQL（同一用户同一 memory_key 覆盖更新）。
4. 抽取失败、LLM 异常、候选为空时，主对话不受影响，只跳过长期记忆保存。

长期记忆按用户维度存储（userId），与按会话维度存储的短期记忆（sessionId）分开，保证跨会话复用。

### 配置安全基线

运行配置（数据库密码、API Key、Langfuse Secret Key 等）从 `application.yml` 剥离，改用环境变量占位符 `${ENV_NAME:defaultValue}`：
- 公开配置模板（端口、默认模型名、localhost 连接信息）保留在 `application.yml`。
- `.env.example` 只表达配置契约，不包含真实密钥。
- 真实值由本地 `.env`、IDE Run Configuration、Docker Compose 或 CI/CD Secret 注入。

默认关闭 Vector Store 自动配置（`spring.ai.vectorstore.type=none`），避免尚未开发 RAG 时强制连接 Milvus 导致启动失败。

---

## 数据库表设计要点

### t_conversation_session（会话表）
- `session_id`：会话唯一标识（UUID），唯一键。
- `user_id`：用户标识，与会话分离，支撑跨会话的长期记忆。
- `status`：0=进行中，1=已归档。
- `title`：首条消息自动截取，用于会话列表识别。

### t_conversation_message（消息表）
- 按 `session_id` 存储完整对话历史，作为长期记忆的原始数据来源。
- `role` 枚举：user / assistant / system / tool。
- `tool_name`：role=tool 时记录工具名称。
- `token_count`：每条消息的 Token 消耗。

### t_long_term_memory（长期记忆表）
- `user_id + memory_key` 为唯一键，同一用户同一记忆键覆盖更新。
- `memory_key` 使用点分隔命名（如 `user.preference.language`），保持可解释、可调试。
- `source_session_id`：来源会话，支持追溯记忆产生的对话上下文。
- `embedding_id`：预留 Milvus 向量 ID，后续接入向量检索时使用。
- 当前不做事实合并，遇到同一 key 直接覆盖。

### 唯一键设计原则
- 会话表：`session_id` 唯一键，同一会话不允许重复记录。
- 消息表：无唯一键，允许多条消息属于同一会话。
- 长期记忆表：`(user_id, memory_key)` 联合唯一键，同一用户同一记忆键只保留一条记录，后写入覆盖先写入。

---

## 前端配套变更

阶段 2.8 和 2.9 在不改动后端接口的前提下，对前端 Agent 控制台进行了工程化重构：
- 从单文件页面拆分为组件化结构（AgentSidebar、MessageList、ChatComposer、ReActTimeline 等）。
- 统一前端类型定义（`src/types/agent.ts`）和 API 调用封装（`src/api/agent.ts`）。
- 采用 composable（`useAgentChat.ts`）管理聊天页状态。
- 全局主题抽离到 `src/styles/theme.css`，采用石榴红与雾粉桃碰撞色方案。
- 未引入 UI 组件库或 Pinia，保持前端依赖最小化，等业务页面增多后再按需引入。
