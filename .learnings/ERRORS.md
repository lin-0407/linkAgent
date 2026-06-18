# Errors

Command failures and integration errors.

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

