# Errors

Command failures and integration errors.

---

## [ERR-20260601-001] inspection_path_and_sandbox_warning

**Logged**: 2026-06-01T00:00:00+08:00
**Priority**: low
**Status**: pending
**Area**: infra

### Summary
阶段 4.11 自检时出现非业务阻塞的路径和工具环境警告。

### Error
```text
rg: E:\linkAgent\linkAgent\AGENTS.md: 系统找不到指定的文件。
windows sandbox: setup refresh failed with status exit code: 1
```

### Context
- `AGENTS.md` 位于 `E:\linkAgent\AGENTS.md`，不在 `E:\linkAgent\linkAgent` 项目根内。
- 一次并行读取前端类型片段时出现 sandbox refresh 警告，单独重试同一读取命令成功。
- 影响范围：只影响检查命令，不影响源代码修改。

### Suggested Fix
后续检查项目协作文件时直接读取 `E:\linkAgent\AGENTS.md`；遇到单次 sandbox refresh 警告可先单独重试原命令，再判断是否需要升级处理。

### Metadata
- Reproducible: unknown
- Related Files: AGENTS.md

---

## [ERR-20260531-002] wrong_backend_model_path

**Logged**: 2026-05-31T00:00:00+08:00
**Priority**: low
**Status**: pending
**Area**: backend

### Summary
查询创建任务请求模型时误用了不存在的 `dto` 目录。

### Error
```text
Cannot find path ...\creator\task\dto\CreatorTaskCreateRequest.java because it does not exist.
```

### Context
- Command/operation attempted: read `backend/src/main/java/.../creator/task/dto/CreatorTaskCreateRequest.java`.
- Actual project path: `backend/src/main/java/.../creator/task/model/CreatorTaskCreateRequest.java`.
- Impact: only affected inspection command, not source code.

### Suggested Fix
后端请求/响应类当前集中在 `creator/task/model`，不要按常见 `dto` 包结构猜路径。

### Metadata
- Reproducible: yes
- Related Files: backend/src/main/java/com/link/linkagent/creator/task/model/CreatorTaskCreateRequest.java

---

## [ERR-20260531-001] rg_pattern_quoting

**Logged**: 2026-05-31T00:00:00+08:00
**Priority**: low
**Status**: pending
**Area**: frontend

### Summary
PowerShell 下使用包含 Vue 属性引号的 `rg` 正则时转义失败。

### Error
```text
rg: regex parse error: unclosed group
```

### Context
- Command/operation attempted: search `v-if="taskManageMode === 'edit'"` with a quoted regex.
- Environment: Windows PowerShell.
- Impact: only affected locating line numbers, not source code.

### Suggested Fix
在 PowerShell 中优先使用更简单的关键词搜索，或拆分为多个固定字符串查询。

### Metadata
- Reproducible: yes
- Related Files: link-agent-frontend/src/components/CreatorWorkspace.vue

---
