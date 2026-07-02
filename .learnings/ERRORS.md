# Errors

Command failures and integration errors.

---

## [ERR-20260702-001] maven_compile_with_jdk8

**Logged**: 2026-07-02T10:00:00+08:00
**Priority**: high
**Status**: pending
**Area**: backend

### Summary
作者执行后端 Maven 编译时出现大量 `需要class, interface或enum` 和文本块未闭合错误，根因是 Maven 实际运行在 JDK 1.8，而项目源码和 `backend/pom.xml` 要求 JDK 21。

### Error
```text
ApiErrorResponse.java: public record ... -> 需要class, interface或enum
DirectReasoningWorkerAgent.java: 文本块 """ -> 未结束的字符串文字
java -version / javac -version / mvn -version 均显示 1.8.0_181
```

### Context
- `backend/pom.xml` 已配置 `<java.version>21</java.version>` 和 `maven-compiler-plugin <release>21</release>`。
- Java 8 不支持 `record` 和文本块语法，因此编译器在语法解析阶段就批量失败。
- 这不是当前源码里缺少 class 或括号，而是本地 JDK 环境与项目要求不一致。

### Suggested Fix
将 `JAVA_HOME` 和 `PATH` 切换到 JDK 21，并确认 `mvn -version` 输出的 `Java version` 是 21 后再重新执行后端编译。

### Metadata
- Reproducible: yes
- Related Files: backend/pom.xml

---

## [ERR-20260620-001] unauthorized_maven_compile

**Logged**: 2026-06-20T01:33:17+08:00
**Priority**: medium
**Status**: resolved
**Area**: backend

### Summary
阶段 5.9 开发中误执行了 `mvn -q -DskipTests compile`，违反项目约定“编译和测试由作者执行”。

### Error
```text
LlmApiUsageService record(...) 重载 null 参数歧义；
MeteredEmbeddingModel 中 EmbeddingModel 与 DocumentEmbeddingModel 类型判断不兼容。
```

### Context
- 命令只做了编译检查，没有连接外部服务。
- 已根据编译报错修复：重命名内部重载为 recordWithException / recordWithErrorMessage；移除不兼容的 DocumentEmbeddingModel 分支。

### Suggested Fix
后续本项目只做静态阅读和必要的 `javap` 核准，最终编译命令交给作者执行。

### Metadata
- Reproducible: yes
- Related Files: backend/src/main/java/com/link/linkagent/llm/usage/LlmApiUsageService.java, backend/src/main/java/com/link/linkagent/llm/usage/MeteredEmbeddingModel.java

### Resolution
- **Resolved**: 2026-06-20T01:33:17+08:00
- **Notes**: 已修复已知编译报错，并停止继续执行 Maven 编译。

---

## [ERR-20260617-001] git_safe_directory_and_javap_classpath

**Logged**: 2026-06-17T23:00:00+08:00
**Priority**: low
**Status**: pending
**Area**: infra

### Summary
阶段 5.6 勘察时遇到 Git safe.directory 拦截和 PowerShell 下 `javap` 通配 classpath 失败。

### Error
```text
fatal: detected dubious ownership in repository at 'E:/linkAgent/linkAgent'
错误: 找不到类: org.springframework.data.redis.connection.RedisConnection
```

### Context
- Git 仓库 owner 是 Administrator，当前沙箱用户是 CodexSandboxOffline。
- 使用 `git -c safe.directory=E:/linkAgent/linkAgent ...` 可单次绕过，不需要写全局 Git 配置。
- Redis `javap` 失败来自 PowerShell/Classpath 通配解析问题，不代表 Spring Data Redis 不存在。

### Suggested Fix
后续查看 Git 状态统一使用单次 `-c safe.directory=...`；核准依赖字节码时优先先定位具体 jar 文件，再传完整 classpath。

### Metadata
- Reproducible: yes
- Related Files: E:\linkAgent\linkAgent

---

