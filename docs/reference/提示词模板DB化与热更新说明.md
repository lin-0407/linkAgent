# 提示词模板 DB 化与热更新说明（阶段 5.5）

> 对应开发文档 `/docs/develop/阶段5.5-提示词模板DB化与热更新.md`。本文件记录阶段 5.5 完成后的功能边界、表结构、接口、调用迁移范围和维护注意事项。

> 2026-07-05 已做一次提示词模板资产治理。当前全量模板、调用处、状态分类和初始化乱码修复策略见 `/docs/reference/提示词模板资产治理说明.md`。

## 1. 功能定位

阶段 5.5 把原本散落在 Java 代码里的大模型提示词搬到数据库表 `llm_prompt_template`。

这样做解决的是一个很实际的问题：以前改一句提示词，需要改代码、编译、打包、重启服务；现在只要通过接口更新数据库正文，下一次模型调用就会读到新内容。

本阶段只做后端底座和调用点迁移，不做前端编辑界面。前端入口留给阶段 5.6 设置面板继续接入。

## 2. 核心设计

### 2.1 数据库表

表名：`llm_prompt_template`

位置：`backend/src/main/resources/sql/init.sql`

关键字段：

| 字段 | 说明 |
|---|---|
| `prompt_key` | 提示词唯一键，例如 `pre_publish.system` |
| `prompt_type` | 提示词类型，`SYSTEM` 或 `USER` |
| `scene` | 所属业务场景，供前端分组展示 |
| `content` | 提示词正文 |
| `description` | 提示词用途说明 |

本表只保存「当前生效版本」。不做版本表和 AB 实验表，因为阶段 4.9 的评测结果表已经保存 `prompt_hash` 和 `prompt_snapshot`，评测时会自己快照当时用到的提示词。

### 2.2 `PromptService`

核心方法：

| 方法 | 用途 |
|---|---|
| `get(key)` | 按 key 查询提示词正文 |
| `render(key, vars)` | 查询模板后，把 `{varName}` 占位符替换为变量值 |
| `listAll()` | 列出全部未删除提示词 |
| `update(key, content)` | 更新一条提示词正文 |

本阶段明确不做内存缓存。原因是本项目当前是单用户、非公网部署，一次大模型调用本身是秒级，而按唯一键查一行提示词是毫秒级。缓存收益很低，却会带来「改库后如何刷新缓存」的一致性问题。

所以热更新的实现很直接：每次模型调用前都查库。只要 `PUT` 写入成功，下一次调用就会读到最新正文。

### 2.3 失败策略

`get(key)` 查不到提示词时会直接抛错。

这叫 fail-loud，意思是让错误尽早、明显地暴露。因为数据库已经是提示词唯一来源，查不到通常说明 `init.sql` 没执行、种子数据缺失，或者这条提示词被误删。静默兜底反而会让模型用错提示词，问题更难排查。

## 3. 对外接口

接口前缀：`/api/prompt-templates`

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/prompt-templates` | 列出全部提示词 |
| `GET` | `/api/prompt-templates/{key}` | 按 key 查询正文 |
| `PUT` | `/api/prompt-templates/{key}` | 更新正文，下一次调用即时生效 |

`PUT` 请求体：

```json
{
  "content": "新的提示词正文"
}
```

校验规则：

| 参数 | 校验 |
|---|---|
| `key` | `@NotBlank`，最长 128 |
| `content` | `@NotBlank`，最长 20000 |

不存在的 key 更新时返回 404。接口只允许修改已有提示词，不允许凭空新建 key，因为 key 必须和代码调用点一一对应。

## 4. 已迁移的提示词

### 4.1 SYSTEM 提示词

| key | 场景 |
|---|---|
| `pre_publish.system` | 发布前优化 |
| `feedback_analyze.system` | 评论弹幕分析 |
| `feedback_chat.system` | 评论弹幕追问 |
| `competitor.system` | 竞品分析 |
| `report.system` | 创作复盘 |
| `hyde.system` | 高级检索 HyDE 查询变换 |
| `reference_cleaning.system` | 案例库清洗 |
| `long_term_memory.system` | 长期记忆抽取 |
| `summary_memory.system` | 会话摘要 |
| `agent_executor.system` | 文本 ReAct 内核 |
| `agent_executor_structured.system` | 结构化 ReAct 内核 |

### 4.2 USER 提示词

| key | 场景 |
|---|---|
| `pre_publish.user` | 发布前优化 |
| `feedback_analyze.user` | 评论弹幕分析 |
| `feedback_chat.user` | 评论弹幕追问 |
| `competitor.user` | 竞品分析 |
| `report.user` | 创作复盘 |
| `long_term_memory.user` | 长期记忆抽取 |

USER 模板统一使用 `{varName}` 命名占位符，不再使用 `%s` 这种位置占位符。这样前端编辑时更容易看懂每个变量的含义，也不会因为用户在提示词里输入 `%` 导致 `String.format` 抛异常。

## 5. 已迁移调用点

| 模块 | 调用方式 |
|---|---|
| `PrePublishSuggestionService` | `get(pre_publish.system)` + `render(pre_publish.user)` |
| `CreatorFeedbackService` | `get / render` 覆盖分析与追问 |
| `CreatorCompetitorService` | `get(competitor.system)` + `render(competitor.user)` |
| `CreatorReportService` | `get(report.system)` + `render(report.user)` |
| `KnowledgeReferenceCleaningService` | `get(reference_cleaning.system)` |
| `HydeQueryTransformer` | `get(hyde.system)` |
| `LongTermMemoryExtractor` | `get(long_term_memory.system)` + `render(long_term_memory.user)` |
| `SummaryMemory` | `get(summary_memory.system)` |
| `AgentExecutor` | `render(agent_executor.system)` 与 `render(agent_executor_structured.system)` |

测试侧新增 `StubPromptService`，让手动 `new Service(...)` 的单测不用连接数据库。它返回 `[test-prompt:key]`，便于测试断言调用传入了正确的 key。

## 6. 验证结论

作者已在阶段 5.5 自测通过后通知收尾。

建议后续回归仍保留以下检查：

```bash
cd backend
mvn -q -DskipTests compile
```

接口检查：

1. `GET /api/prompt-templates` 能列出种子数据。
2. `GET /api/prompt-templates/pre_publish.system` 能返回正文。
3. `PUT /api/prompt-templates/pre_publish.system` 后，再次 GET 能立即读到新正文。
4. 发布前优化、评论弹幕分析 / 追问、竞品分析、创作复盘、长期记忆、摘要记忆、`/api/agent/chat` 的模型调用路径正常。

## 7. 维护注意

- 新增提示词时必须先在 `init.sql` 增加种子数据，再在代码里按稳定 key 调用。
- 不要在业务 Service 里重新写大段硬编码提示词。
- USER 模板新增变量时，必须同步检查模板里的 `{varName}` 和 `render(key, Map)` 里的 key 是否一致。
- 不要轻易加缓存。只有出现多实例部署或高并发下的明确性能问题，再讨论缓存与刷新机制。
- 直接改数据库也会即时生效，但推荐通过接口修改，至少能走入参校验。
