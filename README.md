# LinkAgent Creator Copilot

面向 B 站内容创作者的 AI 创作与复盘工作台。

## AI 工程化亮点

- Prompt 版本评测闭环：评测结果绑定版本、快照和 SHA-256 哈希，支持按版本对比成功率、分项评分、token 和耗时。
- LLM 用量采集：基于 Spring AI 响应 usage 返回模型名称、prompt token、completion token、total token 和调用耗时。
- 失败回放：保留工作流步骤、原始输出、失败原因和评测备注，便于复盘模型为什么输出不好。
- 创作者场景闭环：覆盖稿件输入、发布前优化、评论弹幕分析、创作复盘和创作者偏好记忆。

## 演示环境

1. 准备好 Docker 和 Docker Compose。
2. 按需补齐根目录 `.env`，或者直接使用默认值启动。
3. 执行：

```bash
docker compose up -d --build
```

4. 如果要演示完整分析能力，再补上 `LLM_API_KEY`。
5. 打开：

```text
http://localhost:8088
```

## 演示数据

- MySQL 会在首次启动时自动执行 `backend/src/main/resources/sql/init.sql`。
- 这份脚本里已经包含创作任务、发布前建议、评论弹幕分析、复盘报告和评测样例的演示数据。
- 演示库名固定为 `link_agent`，这样初始化脚本和后端连接串能保持一致。
- 首屏默认会选中最新的完整复盘样例：`sample-task-report-001`。

## 环境变量

- `LLM_API_KEY`：模型服务密钥。
- `LLM_BASE_URL`：模型服务地址。
- `LLM_MODEL`：模型名称。
- `LLM_GUARD_ENABLED`：是否开启单次 LLM 输入规模保护，默认 `true`。
- `LLM_GUARD_MAX_PROMPT_CHARS`：单次 LLM 请求的 system prompt + user prompt 最大字符数，默认 `30000`。
- `MYSQL_ROOT_PASSWORD`：MySQL root 密码。
- `MYSQL_USER` / `MYSQL_PASSWORD`：业务库账号。
- `FRONTEND_PORT`：前端访问端口，默认 `8088`。

## 本地开发

- 后端继续按 Spring Boot 方式启动。
- 前端继续按 Vite 方式启动。
- 演示容器只负责把前后端和基础设施串起来。
