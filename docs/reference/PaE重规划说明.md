# PaE 重规划说明

阶段 6.2 后，Plan-and-Execute 在失败时会尝试重规划剩余步骤。

## 1. 触发条件

当前只在步骤 `FAILED` 时触发 Replanner。

典型原因：

- 计划引用了不存在的工具。
- 工具返回 `Error:`。
- 工具返回为空，但计划声明了 `expectedObservation`。
- Replanner 重复了已失败的工具方案。

## 2. 重规划上限

每次 PaE 最多重规划 2 次。

这样做是为了避免 Replanner 在两个失败方案之间反复切换。

## 3. 失败指纹

失败指纹格式：

```text
action::actionInput
```

Replanner prompt 和执行器都会阻止重复执行同一个失败方案。

## 4. 编号策略

Replanner 返回的新步骤会重新编号，从当前最大 stepId 后继续。

这样前端 trace 能同时展示原失败步骤和新路线，不会出现重复 stepId。
