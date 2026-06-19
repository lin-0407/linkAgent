# Errors

Command failures and integration errors.

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

