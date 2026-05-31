# Errors

Command failures and integration errors.

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
