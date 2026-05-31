# 上线演示与 Docker Compose 部署说明

## 部署目标

这套部署不是生产级集群，而是面向简历演示的最小可用环境。目标是让面试官一眼看到：

1. 前端能打开。
2. 后端能连数据库和 Redis。
3. 初始化脚本能提供样例数据。
4. 工作台的 SSE 和 API 都能走通。

## 服务说明

| 服务 | 作用 |
|---|---|
| `mysql` | 存储创作任务、评测样例和复盘数据 |
| `redis` | 存储短期记忆和运行期状态 |
| `backend` | Spring Boot 后端 |
| `frontend` | Nginx 托管的 Vue3 静态站点 |

## 启动方式

```bash
docker compose up -d --build
```

默认访问地址：

```text
http://localhost:8088
```

## 环境变量

| 变量 | 说明 |
|---|---|
| `LLM_API_KEY` | 模型服务密钥 |
| `LLM_BASE_URL` | 模型服务地址 |
| `LLM_MODEL` | 模型名称 |
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 |
| `MYSQL_USER` | 业务库账号 |
| `MYSQL_PASSWORD` | 业务库密码 |
| `FRONTEND_PORT` | 前端对外端口 |

说明：没有 `LLM_API_KEY` 时，容器仍然可以启动，但依赖模型的分析链路只能用于页面和连通性演示，不能算完整功能演示。

## 演示数据

数据库首次初始化时会自动执行：

```text
backend/src/main/resources/sql/init.sql
```

这份脚本里已经包含：

1. 创作任务样例。
2. 发布前优化样例。
3. 评论弹幕分析样例。
4. 创作复盘与偏好样例。
5. 评测样例。

说明：演示库名固定为 `link_agent`，这样 `init.sql` 和后端连接串不会出现库名不一致的问题。
首页默认会先看到 `sample-task-report-001`，它带着完整工作流、反馈仪表盘和复盘结果，最适合演示。

## 说明

1. 前端容器内置 Nginx，因此浏览器只需要访问一个端口。
2. `/api` 走反向代理，避免 CORS 问题。
3. SSE 路径关闭了 buffering，保证流式消息能实时展示。
