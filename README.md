# LinkAgent Creator Copilot

LinkAgent Creator Copilot 是一个面向 B 站内容创作者的 AI 创作与复盘工作台。

项目基于 Spring Boot、Spring AI、Vue3 和 SSE 构建。它保留了 ReAct Agent 编排、工具调用、会话记忆、RAG 检索、LLM 用量追踪等底层能力，但对外主线聚焦在创作者工作流：从稿件准备、发布前优化，到评论弹幕分析和创作复盘。

## 项目定位

一句话说明：

```text
帮助 UP 主围绕单个创作任务完成稿件分析、标题简介标签建议、评论弹幕复盘、报告沉淀和偏好记忆。
```

目标用户：

| 角色 | 典型诉求 |
|---|---|
| B 站中小 UP 主 | 发布前希望快速优化标题、简介、标签和表达重点 |
| 内容运营学习者 | 希望用结构化方式复盘评论、弹幕和观众反馈 |
| AI 应用学习者 | 希望看到一个可演示、可观测、可评测的 Spring AI 项目 |

当前明确不做：

- 不做自动投稿。
- 不做批量或定时后台采集。
- 不模拟 B 站推荐算法。
- 不绕过平台限制获取数据。
- 后端部署后不内置任何定时采集任务。

允许的数据入口：

- 用户主动粘贴或上传的字幕、文稿、评论、弹幕样例。
- 用户输入单个 BV 后显式触发的限量样例采集。
- `scripts/` 下由作者本地离线运行的采集脚本，采集结果再通过导入接口合规入库。

## 核心能力

### 创作任务工作台

- 创建、编辑、删除创作任务。
- 保存标题草稿、简介草稿、字幕、文稿和其他创作材料。
- 支持材料文件导入，方便把分析入口集中到单个任务下。

### 发布前优化

- 分析字幕和文稿，提炼内容卖点。
- 生成标题、简介、标签建议。
- 给出建议理由、风险点和可执行修改方向。
- 通过 SSE 展示 Agent 工作流过程，让用户看到分析进度。

SSE 是服务器主动推送事件的机制，适合把模型分析过程一段段展示给前端。

### 评论弹幕分析

- 支持手动保存评论和弹幕样例。
- 支持文件导入评论和弹幕样例。
- 支持用户输入单个 BV 后显式触发样例采集。
- 输出高频观点、情绪倾向、争议点、误解点和下一期内容建议。
- 支持反馈追问，并可在开启 RAG 后结合证据回答。

RAG 是先检索相关材料，再让模型基于材料回答。它能减少模型脱离证据直接发挥的问题。

### 创作复盘报告

- 汇总发布前优化结果和发布后反馈分析。
- 生成结构化复盘报告。
- 支持 Markdown 导出，方便保存或二次整理。
- 报告和任务长期保存在 MySQL 中。

### 创作者偏好与语境记忆

- 记录创作者偏好，让后续建议更贴合历史风格。
- 维护创作者视频类型语境库，例如标题包装、表达禁忌、常见受众反馈。
- 区分短期任务上下文和长期创作者偏好，避免一次任务污染长期记忆。

### 知识库与高级检索

- 支持导入跨分区视频案例。
- 支持主题优先检索和分析上下文构建。
- 支持父级视频卡片、主题中块、评论弹幕子条目的多层索引。
- Milvus、Embedding、Hybrid 检索和 Rerank 都是默认关闭的可选能力，避免演示环境产生不必要成本。

### 可观测与评测

- 记录 LLM 调用模型、token、耗时和调用状态。
- 支持按任务查看 LLM API 开销。
- 支持 Prompt 版本评测、失败回放和分项评分。
- 保留 Agent 工作流步骤、原始输出和失败原因，方便复盘模型为什么输出不好。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端框架 | Spring Boot 3.5.11 |
| AI 框架 | Spring AI 1.1.4 |
| JDK | 21 |
| 模型接入 | OpenAI 兼容接口，默认 DeepSeek 配置 |
| 数据访问 | MyBatis 3.0.5 |
| 关系数据库 | MySQL 8.4 |
| 短期记忆 | Redis 7.4 |
| 向量库 | Milvus，可选开启 |
| 前端 | Vue 3.5、TypeScript 6、Vite 8 |
| 流式交互 | SSE |
| 部署 | Docker Compose、Nginx |

## 架构概览

```text
Vue3 创作工作台
  -> Nginx 同源代理
  -> REST API / SSE
  -> Creator 业务层
  -> Agent 编排层
  -> Tool Registry
  -> Memory
  -> MySQL / Redis / Milvus
  -> LLM API
```

后端分层重点：

| 模块 | 说明 |
|---|---|
| `creator.task` | 创作任务、稿件材料和任务列表 |
| `creator.suggestion` | 发布前标题、简介、标签建议 |
| `creator.feedback` | 评论弹幕保存、分析、追问和证据索引 |
| `creator.report` | 创作复盘报告与 Markdown 导出 |
| `creator.preference` | 创作者偏好记忆 |
| `creator.context` | 创作者语境库 |
| `creator.workflow` | 发布前工作流、消息流和 SSE 事件 |
| `knowledge` | 跨分区视频案例知识库和 RAG 检索 |
| `core` / `tool` / `memory` | 底层 Agent、工具调用和记忆能力 |
| `llm.usage` | LLM 调用记录、成本统计和链路追踪 |

## 目录结构

```text
linkAgent/
├── backend/                         # Spring Boot 后端
│   ├── src/main/java/com/link/linkagent
│   │   ├── creator/                 # 创作者工作台业务模块
│   │   ├── knowledge/               # 视频案例知识库与 RAG
│   │   ├── core/                    # ReAct Agent 执行内核
│   │   ├── tool/                    # 工具注册、执行和 MCP 适配
│   │   ├── memory/                  # 短期记忆和长期记忆
│   │   ├── llm/                     # LLM 调用与用量统计
│   │   └── common/                  # 通用异常与响应结构
│   └── src/main/resources/sql/      # 数据库初始化脚本
├── link-agent-frontend/             # Vue3 前端
│   ├── src/api/                     # 前端 API 封装
│   ├── src/components/              # 工作台组件
│   ├── src/composables/             # SSE 和 Agent 会话逻辑
│   └── deploy/nginx/                # 前端容器 Nginx 配置
├── docs/                            # 阶段文档、参考说明和踩坑记录
├── scripts/                         # 作者本地离线采集和导入辅助脚本
├── docker-compose.yml               # 本地演示编排
├── .env.example                     # 环境变量示例
└── README.md                        # 项目入口说明
```

## 快速体验

以下命令需要作者在本机执行。开发协作过程中，AI 助手不会执行编译、测试、构建、运行或启动命令。

### 1. 准备环境

需要提前安装：

- Docker
- Docker Compose

如果要在本地开发而不是只跑演示容器，还需要：

- JDK 21
- Maven
- Node.js `^20.19.0 || >=22.12.0`
- MySQL、Redis，可使用 Linux 虚拟机中的 Docker 容器提供

### 2. 配置环境变量

复制环境变量示例：

```bash
cp .env.example .env
```

最小演示可以先不填写 `LLM_API_KEY`，前端仍能查看初始化样例数据。

如果要调用真实模型分析，请至少配置：

```text
LLM_API_KEY=你的模型服务密钥
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-chat
```

默认演示数据库：

```text
MYSQL_ROOT_PASSWORD=link_agent_root_password
MYSQL_USER=link_agent
MYSQL_PASSWORD=link_agent_password
FRONTEND_PORT=8088
```

这些默认值只适合本地演示。部署到公网或服务器时必须改成自己的强密码。

### 3. 启动演示环境

在 `linkAgent/` 项目目录执行：

```bash
docker compose up -d --build
```

容器会启动：

- MySQL
- Redis
- Spring Boot 后端
- Vue 前端 + Nginx

访问地址：

```text
http://localhost:8088
```

### 4. 查看日志

如果页面打不开，优先查看容器状态和日志：

```bash
docker compose ps
docker compose logs -f backend
docker compose logs -f frontend
```

常见排查点：

- `frontend` 没起来：检查前端构建日志。
- `backend` 没起来：检查数据库连接、Redis 连接和环境变量。
- LLM 分析失败：检查 `LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL`。
- 样例数据没出现：确认 MySQL 是否是首次启动，初始化脚本只会在数据库卷第一次创建时执行。

如果要重置演示数据，可以删除 Docker 数据卷后重新启动：

```bash
docker compose down -v
docker compose up -d --build
```

注意：`docker compose down -v` 会删除本项目 Docker Compose 创建的 MySQL 和 Redis 数据卷。

## 本地开发

### 后端

后端目录：

```text
backend/
```

本地运行后端前，需要在 IDE 运行配置或系统环境变量中配置 `.env.example` 里的变量，至少包括：

```text
DB_URL=jdbc:mysql://localhost:3306/link_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
DB_USERNAME=link_agent
DB_PASSWORD=你的数据库密码
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=
LLM_API_KEY=你的模型服务密钥
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-chat
VECTOR_STORE_TYPE=none
EMBEDDING_MODEL_TYPE=none
```

作者可执行的后端启动命令：

```bash
cd backend
mvn spring-boot:run
```

后端默认端口：

```text
http://localhost:8080
```

### 前端

前端目录：

```text
link-agent-frontend/
```

作者可执行的前端开发命令：

```bash
cd link-agent-frontend
npm ci
npm run dev
```

前端开发端口以 Vite 输出为准。

### 本地验证命令

作者在修改后可以执行：

```bash
cd backend
mvn test
```

```bash
cd link-agent-frontend
npm run type-check
npm run build
```

预期结果：

- 后端测试通过，没有编译错误。
- 前端类型检查通过，构建产物正常生成。
- 页面可以创建任务、查看演示任务、触发发布前分析、查看评论弹幕复盘和导出报告。

## 主要环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `LLM_API_KEY` | 空 | 模型服务密钥，真实分析必须配置 |
| `LLM_BASE_URL` | `https://api.deepseek.com` | OpenAI 兼容模型服务地址 |
| `LLM_MODEL` | `deepseek-chat` | 聊天模型名称 |
| `LLM_GUARD_ENABLED` | `true` | 是否开启单次 LLM 输入规模保护 |
| `LLM_GUARD_MAX_PROMPT_CHARS` | `30000` | 单次 LLM 请求最大 prompt 字符数 |
| `DB_URL` | 无 | 后端连接 MySQL 的 JDBC 地址 |
| `DB_USERNAME` | 无 | MySQL 业务账号 |
| `DB_PASSWORD` | 无 | MySQL 业务账号密码 |
| `REDIS_HOST` | 无 | Redis 地址 |
| `REDIS_PORT` | 无 | Redis 端口 |
| `VECTOR_STORE_TYPE` | `none` | 向量库开关，启用 Milvus 时设为 `milvus` |
| `EMBEDDING_MODEL_TYPE` | `none` | Embedding 模型开关，启用时设为 `openai` |
| `CREATOR_FEEDBACK_RAG_ENABLED` | `false` | 评论弹幕追问是否走 RAG |
| `KNOWLEDGE_RAG_ENABLED` | `false` | 视频案例知识库是否启用 RAG |
| `FRONTEND_PORT` | `8088` | Docker Compose 前端访问端口 |

## RAG 与 Milvus

RAG 和 Milvus 默认关闭。这样做是为了让演示环境先稳定跑起来，避免没有向量库或 Embedding Key 时启动失败。

默认关闭配置：

```text
VECTOR_STORE_TYPE=none
EMBEDDING_MODEL_TYPE=none
CREATOR_FEEDBACK_RAG_ENABLED=false
KNOWLEDGE_RAG_ENABLED=false
```

启用视频案例知识库 RAG 时，需要准备：

- Milvus 服务。
- Embedding 模型 Key 和 Base URL。
- 与模型输出一致的向量维度。
- 对应的业务开关。

可参考：

- `docs/reference/反馈追问证据链与RAG最小闭环说明.md`
- `docs/reference/跨分区视频案例知识库说明.md`
- `docs/reference/高级检索链路说明.md`

## 主要 API

| API | 说明 |
|---|---|
| `POST /api/creator/tasks` | 创建创作任务 |
| `GET /api/creator/tasks` | 查询创作任务列表 |
| `GET /api/creator/tasks/{taskId}` | 查询任务详情 |
| `POST /api/creator/tasks/{taskId}/materials/import` | 导入任务材料文件 |
| `POST /api/creator/tasks/{taskId}/workflow/pre-publish/start` | 启动发布前工作流会话 |
| `GET /api/creator/tasks/{taskId}/workflow/sessions/{sessionId}/events` | 订阅工作流 SSE 事件 |
| `POST /api/creator/tasks/{taskId}/pre-publish/analyze` | 生成发布前优化建议 |
| `POST /api/creator/tasks/{taskId}/feedback/analyze` | 分析评论弹幕 |
| `POST /api/creator/tasks/{taskId}/feedback/fetch` | 显式触发单 BV 样例采集 |
| `POST /api/creator/tasks/{taskId}/feedback/chat` | 基于反馈证据追问 |
| `POST /api/creator/tasks/{taskId}/report/analyze` | 生成创作复盘报告 |
| `GET /api/creator/tasks/{taskId}/report/markdown` | 导出 Markdown 复盘报告 |
| `GET /api/creator/preferences` | 查询创作者偏好 |
| `GET /api/creator/context/bundle` | 查询创作者语境包 |
| `POST /api/knowledge/reference-videos/import` | 导入视频案例知识 |
| `POST /api/knowledge/reference-videos/search` | 检索视频案例 |
| `GET /api/llm-usage/tasks/{taskId}/summary` | 查询任务 LLM 用量汇总 |
| `GET /api/settings/status` | 查询运行时设置状态 |

## 演示数据

MySQL 首次启动时会执行：

```text
backend/src/main/resources/sql/init.sql
```

初始化脚本包含：

- 创作任务样例。
- 发布前建议样例。
- 评论弹幕分析样例。
- 创作复盘报告样例。
- Prompt 评测样例。
- LLM 用量追踪样例。

演示库名固定为：

```text
link_agent
```

首屏默认会选中最新的完整复盘样例：

```text
sample-task-report-001
```

## 文档入口

项目文档集中在 `docs/` 目录：

| 目录 | 说明 |
|---|---|
| `docs/develop/` | 阶段开发方案和功能设计 |
| `docs/reference/` | 功能详细说明和接口说明 |
| `docs/error/` | 阶段性踩坑记录和排查经验 |
| `docs/README.md` | 文档索引入口 |

建议优先阅读：

- `docs/develop/阶段4-UP主智能工作台总流程大纲.md`
- `docs/reference/创作工作台前端说明.md`
- `docs/reference/发布前优化Agent接口说明.md`
- `docs/reference/评论弹幕分析接口说明.md`
- `docs/reference/创作复盘报告接口说明.md`
- `docs/reference/LLM API开销统计与全链路追溯说明.md`

## 开发约定

- 新功能必须优先服务创作者工作流，不能退回通用 Agent 展示。
- 对外 API 使用 Spring Boot 自带的 Jakarta Validation / Hibernate Validator 做入参校验。
- 数据库 Schema 统一维护在 `backend/src/main/resources/sql/`。
- 注释必须使用中文，并解释为什么这样做。
- 新增功能时同步补充 `docs/develop/`、`docs/reference/`、`docs/error/` 中的对应文档。
- 前端相关变更不得修改 `package.json` 里的 Node 版本约束。
- 开发协作中，AI 助手只提供需要作者执行的验证命令，不直接执行编译、测试、构建、运行或启动命令。

## 当前状态

项目已经从通用 Agent 框架收敛为 UP 主智能工作台。

底层 Agent 能力仍然保留，但它们的价值需要落到具体创作者场景里：

- 工具调用解决资料整理和检索问题。
- 记忆解决创作者偏好沉淀问题。
- RAG 解决建议缺少证据的问题。
- SSE 解决长流程分析不可见的问题。
- 用量追踪和失败回放解决 AI 输出不可评估的问题。
