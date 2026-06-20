# 第 7 章 多 Agent 协作

## 7.0 概述

把 V22 单 Agent 内挤在一起的 Planner/Executor/Synthesizer 三角色拆成独立 Agent，用结构化 record 互相通信，按 5 种 Effective Agents 模式组合。演化路线：V23 Chain → V24 Routing → V25 Parallelization → V26 Orchestrator-Workers（核心）→ V27 Evaluator-Optimizer。

**什么叫"多 Agent"**：每个 Agent 是 Spring 的一等公民——独立 bean、独立 ChatClient、独立 prompt、独立 advisor 链、可单测、可被其他 Agent 注入。同一 ChatClient 多次 `.call()` 串联不是多 Agent。

**铁律：Agent 之间通信永远是结构化 record 对象，不是自由文本拼接。**

---

## 7.1 V23：Chain——最简的多 Agent 串联

把售后客服回答拆成三步——IntentAgent（意图分类）→ KnowledgeAgent（政策检索）→ ResponseAgent（生成答复）。三个 Agent 用 record 串联。

关键设计决策：
- IntentAgent：独立 ChatClient，不挂 memory 不挂 RAG（无状态分类任务）
- KnowledgeAgent：只挂 RAG 不挂 memory。传给 RAG 的检索文本是结构化 intent，不是用户原话（用户原话带情绪污染向量检索）
- ResponseAgent：挂 memory（多轮对话需要历史话术），不挂 RAG（政策已由 KnowledgeAgent 显式注入）

V23 看起来 token 更贵（3 次调用 vs 1 次），但每次 prompt 长度大幅缩短——总 token 常比合并版少 30~50%。

---

## 7.2 V24：Routing——专家分发

IntentAgent 保留，但根据 `intent.category` 路由到不同 Specialist Agent。每个 Specialist 内部结构可完全不同。

```java
public interface SpecialistAgent {
    Intent.Category category();  // 自报家门
    String handle(String userQuestion, Intent intent);
}
```

Router 靠 Spring 自动注入 `List<SpecialistAgent>` → 构建 `Map<Category, SpecialistAgent>`。加新 Specialist 只需加 `@Component` Bean——V24Agent 0 改动。

---

## 7.3 V25：Parallelization——多视角并行分析

ProfileAnalyst（用户画像）、HistoryAnalyst（历史会话）、EligibilityAnalyst（优惠资格）三个独立 LLM Agent 同时审视同一份输入，结果由 Aggregator 综合。

**并发引擎**：`Executors.newVirtualThreadPerTaskExecutor()` + `CompletableFuture.supplyAsync(...).completeOnTimeout(fallback, 3s).exceptionally(...)`。每个 Report 必须带 `unknown()` 静态工厂——让 Aggregator 一眼看出"这个视角没拿到"。

**反直觉点**：V25 和 V22 并发可叠加——V22 并发加速同 plan 内独立工具调用，V25 加速不同视角的 LLM 推理。

---

## 7.4 V26：Orchestrator-Workers——本章核心

V22 的 `PlanStep.action` 是工具名（执行单元 = ToolCallback），V26 升级成 **Worker 名**（执行单元 = 完整 Agent）。

### 通信契约

```java
public record WorkerCall(int id, String workerName, String subTask,
        Map<String, Object> sharedInputs, List<Integer> dependsOn) {}

public record WorkerPlan(String objective, List<WorkerCall> calls, String rationale) {}

public record WorkerResult(int callId, String workerName, Status status,
        String summary, Map<String, Object> structuredOutput, String errorMessage) {}
```

### Worker 接口

```java
public interface WorkerAgent {
    String name();       // Bean 名
    String capability(); // 能力声明——给 Orchestrator 看，决定要不要调它
    WorkerResult execute(String subTask, Map<String, Object> sharedInputs, Long userId);
}
```

每个 Worker 自己声明"能干什么"。Orchestrator 启动时收集所有 Worker 的 capability 注入 prompt，LLM 看着"能力清单"决定调度。**新增 Worker 0 改 Orchestrator 代码**。

### Worker vs Tool 判据

| 维度 | Tool (V22) | Worker (V26) |
|---|---|---|
| 内部实现 | 一个 `@Tool` 方法 | 整个 Agent |
| 输入 | 严格 schema | 自然语言子任务 + 结构化 sharedInputs |
| LLM 调用 | 0（反射） | N 次 |
| 何时引入 | 边界清晰的原子操作 | 边界清晰但内部复杂的业务能力 |

**判据**：这件事内部需不需要 LLM 推理——需要就是 Worker，不需要就是 Tool。

---

## 7.5 V27：Evaluator-Optimizer——给答复加质检

在 V26 Synthesizer 后加 Critic（审稿评分+列具体问题）和 Optimizer（针对性修复）。Critic 不通过 → Optimizer 改 → 再交 Critic → 直到通过或超上限（MAX_CRITIQUE_ROUNDS=3）。

```java
public record CritiqueReport(int overallScore, boolean passed,
        List<CritiqueIssue> issues, String generalNotes) {}
```

**三个反直觉点**：
1. Critic 不能是 Synthesizer 本人——必须独立 ChatClient + 独立角色 prompt，最好不同模型
2. 不是重新生成，是针对性修复——整段重写会破坏收敛
3. `needsCritique()` 只对高敏感场景（投诉/愤怒用户）开启——开 Critic 意味着 2~4 倍 LLM 成本

### 五版本能力层总结

| 版本 | 填的"层" |
|---|---|
| V23 Chain | 可演化层——多 Agent 单线串联，可独立测/替 |
| V24 Routing | 分发层——按类分发到专家 |
| V25 Parallelization | 多视角层——独立 LLM 视角并行 |
| V26 Orchestrator | 调度层——动态多 Agent DAG 调度 |
| V27 Evaluator | 质量层——高敏感场景的输出打磨 |

---

## 7.6 多 Agent 落地必踩的坑

1. **Record 字段腐烂**：契约 record 单独放 `agent-contracts` module；字段变更 PR 必须 @ 所有 Agent 模块负责人
2. **结构化输出偶发解析失败**：`BeanOutputConverter` 抛异常时重试，把失败原因反馈给 LLM
3. **Agent 间共享 ChatMemory 导致污染**：绝大多数 Agent 不该共享 ChatMemory。只挂到直接和用户交互的 Agent（ResponseAgent/Synthesizer）
4. **Worker 边界蔓延——"上帝 Worker"**：每个 Worker 的 `capability()` ≤ 100 字；内部工具 ≥ 10 个强制拆
5. **Plan 循环依赖/死锁**：允许任意 DAG 必加拓扑校验 `validateAcyclic`
6. **调用链可观测性缺失**：MDC.put("traceId", ...) + logback `%X{traceId}` + OpenTelemetry
7. **token 成本失控**：开 prompt caching、分 Agent 监控 token、非关键 Agent 用便宜模型
8. **Agent 数量爆炸**：单业务领域 ≤ 10 个 Agent 硬上限

---

## 7.7 什么时候根本不该上多 Agent

- 业务总共就一两类请求 → 单 Agent + RAG 够了
- 拆出的 Agent 没有真正独立的角色 → V22 Planner+Synthesizer 即可
- V22 已 95% 正确率时，V26 推到 100% 可能要 3 倍 token
- 团队不能负担工程加固成本（7.6 那 8 个坑）

**真实生产的混搭**：入口 IntentAgent 根据意图路由 → ~80% 简单查询走 V20 单 Agent + 工具 → ~15% 复合任务走 V26 → ~5% 高敏感走 V26 + V27。

### 核心要点

1. **多 Agent 的本质是把角色做成独立 bean**
2. **Agent 间通信永远是结构化 record，不是拼字符串**
3. **多 Agent 不解锁能力，解锁的是工程价值**——模块化、可演化、可独立替换
4. **V22 → V26 是抽象升级**——执行单元从工具升级到 Agent，调度结构（plan、state、replan 循环）同构
5. **学完最重要的能力是判断什么时候不该用**——80% 业务单 Agent 就够
