# 阶段 0.5：最简 Agent 调用链路

## 上下文

项目目前是 Spring Boot + Spring AI 空骨架，所有包只有 `package-info.java`。目标是先跑通最简链路：**用户输入 → LLM 调用 → 返回结果**，验证配置和依赖正确，为后续 ReAct 多步迭代打好基础。

不做多步规划、不做工具调用、不做记忆——纯 Lean 验证 LLM 连通性。

---

## 实现步骤

### Step 0：版本适配

**背景：** Spring AI 1.1.4 官方依赖 Spring Boot 3.5.11，与项目初始选的 Spring Boot 4.0.5 不兼容。需降级 Spring Boot 并同步调整相关依赖版本。

**变更：**
- Spring Boot `4.0.5` → `3.5.11`
- MyBatis `4.0.1` → `3.0.5`（MyBatis 4.x 仅兼容 SB 4.x）
- 删除 `mvnw` / `mvnw.cmd`，改用系统安装的 `mvn` 命令
- 新增 `spring-boot-starter-validation` 依赖，支持 `@NotBlank` 等 Jakarta Validation 注解

### Step 1：LLM 服务层 — `llm/LLMService`

**文件：** `src/main/java/com/link/linkagent/llm/LLMService.java`

- 注入 Spring AI 的 `ChatClient.Builder`，在构造器中 `build()` 得到 `ChatClient` 实例
- 提供同步方法 `String chat(String userMessage)`，通过 Fluent API 链式调用
- 内置 `buildSystemPrompt()` 方法，设置 LLM 角色为"资深编程助手"
- 读取 `application.yml` 中的 model 配置（`spring.ai.openai.*`），不需要额外配置类

Spring AI 1.x 的 OpenAI starter 会根据 `spring.ai.openai.base-url` / `api-key` / `chat.options.model` 自动配置 `ChatClient.Builder`。

### Step 2：API 层 — Controller + DTO

**文件：** `api/dto/ChatRequest.java`、`api/dto/ChatResponse.java`
**文件：** `api/controller/ChatController.java`

- `ChatRequest`：`String message`（+ `@NotBlank` 校验），使用 Java `record`
- `ChatResponse`：`String reply`，使用 Java `record`
- `ChatController`：`POST /api/chat`，接收 `@Valid @RequestBody ChatRequest`，调 `LLMService`，返回 `ChatResponse`

### Step 3：验证

启动应用，`curl POST /api/chat` 发一条消息看能不能正常返回。

---

## 关键文件清单

| 文件 | 操作 |
|---|---|
| `pom.xml` | 修改版本 + 新增 validation 依赖 |
| `llm/LLMService.java` | 新增 |
| `api/dto/ChatRequest.java` | 新增 |
| `api/dto/ChatResponse.java` | 新增 |
| `api/controller/ChatController.java` | 新增 |

## 涉及知识点

- Spring AI 1.x `ChatClient.Builder` 注入与 `ChatClient` 构建
- Spring AI Fluent API：`prompt().system().user().call().content()`
- `@Valid` + `@NotBlank` 实现接口入参校验
- Java 21 `record` 作为 DTO 的简洁写法
- Spring Boot 与 Spring AI 的版本兼容性矩阵

## 验证方式

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "你好，请用一句话介绍你自己"}'
```
