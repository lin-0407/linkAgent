# Learnings

Corrections, insights, and knowledge gaps captured during development.

**Categories**: correction | insight | knowledge_gap | best_practice

---

## [LRN-20260620-001] correction

**Logged**: 2026-06-20T02:20:00+08:00
**Priority**: critical
**Status**: promoted
**Area**: tests

### Summary
linkAgent 项目中开发者绝对不能代跑编译、测试、构建、运行或启动命令。

### Details
作者明确要求所有前后端编译、测试和运行任务必须由作者执行。开发者即使只是为了验证，也不能执行 `mvn compile`、`mvn test`、`mvn spring-boot:run`、`npm run build`、`npm run type-check`、`npm run dev` 等命令。开发者只能给出命令、预期结果、判断标准和排错建议，等待作者反馈执行结果。

### Suggested Action
每次准备验证前，先检查命令是否属于编译、测试、构建、运行或启动；如果是，停止执行并把命令交给作者。

### Metadata
- Source: user_feedback
- Related Files: E:\linkAgent\linkAgent\AGENTS.md
- Tags: project-rule, verification, collaboration
- Promoted: E:\linkAgent\linkAgent\AGENTS.md

---


