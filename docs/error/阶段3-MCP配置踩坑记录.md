# 阶段 3：MCP 配置踩坑记录

## 问题：误写 Spring AI MCP 客户端配置前缀

### 错误写法

本轮最初把 MCP 客户端基础配置写成了：

```yaml
spring:
  ai:
    mcp:
      client:
        common:
          enabled: false
          type: sync
```

这个 `common` 层级不是 Spring AI 1.1.4 的官方配置项。

### 正确写法

通过本地 Spring AI 1.1.4 jar 中的 `@ConfigurationProperties` 元信息确认，真实配置前缀是：

```text
spring.ai.mcp.client
spring.ai.mcp.client.stdio
spring.ai.mcp.client.streamable-http
spring.ai.mcp.client.sse
```

所以基础配置应该直接挂在 `client` 下：

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: false
        type: sync
```

## 根因

错误来自对自动配置包名的误读。Spring AI 的类包名里有 `common.autoconfigure`，但这只是 Java 包结构，不代表 Spring Boot 配置属性里也有 `common` 层级。

判断 Spring Boot 配置项时，不能看包名猜测，必须看：

- `@ConfigurationProperties` 的 `value`
- 配置类里的 `CONFIG_PREFIX`
- jar 内的 `META-INF/spring-configuration-metadata.json`

## 修正

- 已将 `application.yml` 中的 `spring.ai.mcp.client.common` 修正为 `spring.ai.mcp.client`
- 已将阶段 3.7 文档中的示例同步修正
- 已确认 `spring.ai.mcp.client.common.type` 中的 `common` 是错误层级
- 已确认 `spring.ai.mcp.client.type` 的枚举值是 `sync` / `async`

## 后续要求

后续凡是接入 Spring AI、MCP、Milvus、Langfuse 等第三方配置或 API，必须先核验真实配置项，再写代码或文档。

推荐核验顺序：

1. 优先查官方文档。
2. 再查本地依赖 jar 的 `spring-configuration-metadata.json`。
3. 最后用 `javap -verbose` 核对 `CONFIG_PREFIX` 和方法签名。

不能根据包名、类名或经验猜配置路径。

## 问题：ToolRegistry 多构造器导致 Spring 上下文启动失败

### 错误表现

`LinkAgentApplicationTests.contextLoads` 启动失败，核心错误是：

```text
Failed to instantiate [com.link.linkagent.tool.ToolRegistry]: No default constructor found
```

### 根因

为兼容单元测试和 MCP `ToolCallbackProvider` 注入，`ToolRegistry` 同时保留了：

```java
public ToolRegistry(List<Tool> tools)
public ToolRegistry(List<Tool> tools, List<ToolCallbackProvider> toolCallbackProviders)
```

Spring 在存在多个构造器时没有明确注入目标，导致 Bean 实例化失败。

### 修正

在双参数构造器上显式添加 `@Autowired`，让 Spring 使用包含 `ToolCallbackProvider` 的构造器；单参数构造器继续保留给单元测试快速构造。

## 问题：长期记忆 key 测试未同步协议变更
    
### 错误表现

`AgentExecutorLongTermMemoryTest.shouldExtractAndSaveLongTermMemoryAfterFinalAnswer` 期望：

```text
user.preference.language
```

但当前长期记忆协议实际保存：

```text
user.preference.example_language
```

### 根因

`LongTermMemoryExtractor` 已经将长期记忆 key 收敛到 5 个固定值，其中语言偏好对应 `user.preference.example_language`。测试仍使用旧 key，导致断言失败。

### 修正

将测试中的固定 extractor 返回值和断言期望统一改为 `user.preference.example_language`。
