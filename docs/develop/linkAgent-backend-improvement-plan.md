# linkAgent 后端体验提升方案

> 本文档从原 `linkagent-improvement-plan.md` 拆分而来，聚焦后端能力。前端方案见 `linkAgent-frontend-improvement-plan.md`。

## 当前核心问题

从用户视角看，linkAgent 有三个体验断点：

1. **每次都是陌生人** — 不管你用了多少次，Agent 对你的风格、偏好、历史选择毫无记忆。上次采纳了 5 个标题，下次分析还是从零开始猜。
2. **过程是黑盒** — 前端只展示"分析完成"或一段流式文本，你看不到 Agent 在思考什么、调了什么工具、为什么给出这个建议。信任感差。
3. **一个 LLM 挂了全完** — 依赖单一模型服务，网络抖动或限流就直接报错，没有容错。

---

## 方案一：创作者记忆系统（P0 — 用户感知最强的改动）

### 目标

让 Agent 记住三件事：**你的风格、你的选择、你的观众**

### 与现有表的关系

项目已有 `creator_preference`（表 13.1）保存每期复盘提炼的偏好快照（per-task），以及 `creator_context_term`（表 13.2）保存按视频类型的语境词条。本方案新增的是 **用户级聚合画像**（跨任务汇总）和 **事件流水**（记录每一次采纳/拒绝操作），与现有表互补而非替代：

| 现有表 | 粒度 | 本方案新增 |
|--------|------|-----------|
| `creator_preference` | 每期任务快照 | `creator_profile`：跨任务的用户级聚合画像 |
| `creator_context_term` | 按视频类型的词条 | `creator_event`：操作流水，作为画像更新的触发源 |
| `creator_suggestion` | 单次建议结果 | 在建议上增加反馈记录能力 |

### 数据模型

```sql
-- 事件流水：记录用户的每一个有效操作
-- 与 creator_workflow_message 的区别：workflow_message 记录对话消息，
-- 而本表记录用户对建议的"采纳/拒绝/修改"等业务动作，是画像更新的信号源
CREATE TABLE creator_event (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    creator_id  VARCHAR(64)  NOT NULL COMMENT '用户标识，关联 t_conversation_session.user_id',
    event_type  VARCHAR(32)  NOT NULL COMMENT '事件类型：TITLE_ACCEPTED / TAG_REJECTED / FEEDBACK_INSIGHT_SAVED',
    task_id     VARCHAR(64)  COMMENT '关联 creator_task.task_id',
    payload     JSON         COMMENT '事件详情，如 { "title": "...", "tags": [...], "reason": "..." }',
    created_at  DATETIME     NOT NULL COMMENT '事件发生时间',
    INDEX idx_creator_time (creator_id, created_at),
    INDEX idx_creator_type (creator_id, event_type)
);

-- 创作者画像：定期从事件和 creator_preference 中推理生成
-- 与 creator_preference 的区别：preference 是每期任务的快照，profile 是跨任务的聚合画像
CREATE TABLE creator_profile (
    creator_id    VARCHAR(64) PRIMARY KEY COMMENT '用户标识',
    style_tags    JSON COMMENT '风格标签，如 ["理性分析型", "冷幽默", "数据驱动"]',
    tone_guide    TEXT COMMENT '语气指南，如 "标题偏好设问句式，排斥震惊体，标签控制在3-5个..."',
    audience_view TEXT COMMENT '受众认知，如 "观众以25-35岁技术从业者为主，对实操案例反应强于理论..."',
    updated_at    DATETIME NOT NULL COMMENT '画像最后更新时间'
);
```

### 执行流程

```
用户触发"发布前分析"
  │
  ├─ 1. 加载创作者画像 → 注入系统提示词
  │     "你是 XX 的创作助手。她的风格是理性分析型+冷幽默，
  │      标题偏好设问句式，不要用感叹号和震惊体..."
  │
  ├─ 2. Agent 生成建议 → 用户采纳/拒绝/修改
  │
  ├─ 3. 操作写入 creator_event
  │
  └─ 4. 事件数达到阈值（如累积 10 条新事件）→ 触发画像增量更新
        LLM: "根据最近事件，更新用户画像：她最近连续拒绝了3个'最强''必看'
             类标题，可能对夸张风格更排斥了。"
```

### 用户感知

| 之前 | 之后 |
|------|------|
| "分析字幕，生成标题建议" | "基于你的风格（理性分析+冷幽默），建议以下标题..." |
| 每次建议风格随机 | 越用越贴合你的表达习惯 |
| Agent 不理解为什么拒绝 | 拒绝 3 次震惊体后，自动不再生成类似标题 |

---

## 方案二：Agent 思考过程 SSE 事件协议（P0 — 信任感的后端支撑）

### 目标

在 `AgentExecutor` 中，每一步都向 SSE 推送标准化事件，让前端能够展示思考过程。

### 与现有表的关系

现有 `creator_workflow_step`（表 16）已经记录了步骤类型（`step_type`）、步骤名称（`step_name`）、状态（`status`）和输出摘要（`output_summary`）。本方案是在此基础上，**把步骤状态变化实时推送到 SSE**，并补充人类可读的阶段标签。

### SSE 事件序列

```
turn_start
  → message_start
  → chunk / chunk / chunk      ← 打字机效果
  → message_end
  → tool_execution_start       ← "正在搜索同类视频标题..."
  → tool_execution_end         ← "找到 4 个参考"
  → message_start
  → chunk / chunk / chunk
  → message_end
turn_end
```

### 事件数据格式

在后端推事件时加上**人类可读的阶段名和详情**，前端不需要理解业务逻辑：

```java
// 推送的事件结构中增加面向用户展示的字段
public class WorkflowStepEvent {
    private String type;           // step_started / step_completed / step_failed
    private String stepId;         // 与 creator_workflow_step.step_id 对应
    private String userLabel;      // 人类可读的阶段名，如 "提取内容要点"
    private String userDetail;     // 详情，如 "识别到 3 个核心论点"
    private String toolName;       // 调用的工具名（如有）
    private Long durationMs;       // 本步耗时
}
```

### 用户感知

| 之前 | 之后 |
|------|------|
| 等待 30 秒，一个结果 | 每一步都能看到，知道进度 |
| 不知道为什么推荐这个标题 | 每个建议都附带"为什么适合你" |
| 出错了不知道卡在哪 | 卡在"搜索同类视频"就是搜索工具挂了 |

---

## 方案三：分析策略系统提示词注入（P1 — 专业用户价值，后端部分）

### 目标

不同视频需要不同分析角度。不要让教程视频和 Vlog 走同一套分析模板。

### 与现有字段的关系

`creator_task.video_type` 已存储视频类型用于加载语境库。本方案新增的是 **分析策略** 概念——同一个视频类型可以选择不同的分析切入角度。

### 策略定义

```java
public enum AnalysisStrategy {
    TUTORIAL("教程分析", "重信息密度和知识点覆盖"),
    VLOG("Vlog分析", "重情感节奏和人物弧光"),
    REVIEW("测评分析", "重对比框架和购买建议"),
    COMMENTARY("评论分析", "重观点独特性和论据强度"),
    GENERAL("通用分析", "均衡覆盖所有维度");
}
```

### 系统提示词注入

每个策略对应一个 `AnalysisHint`，在构建 system prompt 时注入：

```java
// 教程策略注入:
// "用户制作的是教程类视频。分析时请侧重：
//  1. 知识点是否完整且逻辑递进
//  2. 标题应引导用户明确能学到什么
//  3. 标签应覆盖技能关键词方便搜索
//  不要过度关注情感表达和叙事节奏。"

// Vlog策略注入:
// "用户制作的是 Vlog。分析时请侧重：
//  1. 是否有清晰的情感起伏线
//  2. 标题应引发共鸣而非信息罗列
//  3. 人物弧光和场景转换是否自然"
```

策略提示词可存入 `llm_prompt_template` 表（表 22），按 `scene = 'analysis_strategy'` 管理，支持前端自定义。

---

## 方案四：LLM 多 Provider 容错（P1 — 可靠性）

### 目标

一个模型挂了自动切下一个，用户无感知。

### 配置

```yaml
llm:
  providers:
    - name: deepseek
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_KEY}
      priority: 1
    - name: openai
      base-url: https://api.openai.com
      api-key: ${OPENAI_KEY}
      priority: 2
    - name: local
      base-url: http://localhost:11434
      api-key: unused
      priority: 3
  fallback:
    enabled: true
    cooldown-seconds: 60  # 限流后冷却 60 秒再重试
```

### 回退链实现

```java
// 按优先级依次尝试，遇限流/异常自动切换
public String chat(List<Message> messages) {
    for (Provider p : providersByPriority()) {
        if (p.isOnCooldown()) continue;       // 限流冷却中，跳过
        try {
            return p.chat(messages);
        } catch (RateLimitException e) {
            p.markCooldown(60);               // 冷却这个 provider
            continue;                         // 试下一个
        } catch (Exception e) {
            log.warn("{} failed: {}", p.name(), e.getMessage());
            continue;                         // 试下一个
        }
    }
    throw new AllProvidersFailedException();  // 全挂了才报错
}
```

### 与现有表的关系

现有 `llm_api_call_log`（表 24）已记录每次模型调用的 `model_name`、`status`、`error_message`。回退链每次尝试都写入一条调用日志，`status = FAILED` + `error_message` 记录失败原因，方便排查"为什么切到了备用模型"。

### 用户感知

| 之前 | 之后 |
|------|------|
| DeepSeek 限流 → 白屏报错 | 自动切 OpenAI，用户不感知 |
| 完全依赖一个 Key | 配多个 Key 互为备份 |
| 不知道哪个模型在跑 | 设置页可以看到当前使用的 provider |

---

## 方案五：建议质量反馈收集（P1 — 持续进化，后端部分）

### 目标

用户每次采纳/拒绝/修改建议 → 系统记录 → 画像更新 → 下次更好

### 后端处理

反馈事件复用方案一的 `creator_event` 表：

```java
// 记录拒绝事件
creatorEvent.record(REJECTED, taskId, Map.of(
    "title", rejectedTitle,
    "reason", "风格不符合定位",
    "accepted_alternative", null
));

// 事件累积到阈值 → 触发画像微调
// "用户连续3次拒绝震惊体标题，画像中 tone_guide 更新为：
//  '强烈排斥震惊体和感叹号句式，偏好克制、准确、信息密度高的标题'"
```

反馈原因枚举（供前端展示，后端只存储原始字符串）：

```
风格不符合我的定位 / 太长或太短 / 太夸张或震惊体 / 不够吸引人 / 偏离视频主题 / 其他
```

### 与现有表的关系

现有 `creator_suggestion`（表 8）已保存每次建议的结构化结果。反馈事件通过 `task_id` 关联到对应建议，画像更新时可以从 `creator_suggestion` 回查当时的具体建议内容做对比分析。

---

## 后端实施路线

```
第 1 周（P0）
  ├─ creator_event 表 + 记录逻辑
  ├─ creator_profile 表 + 静态画像（首次 init 时 LLM 从 creator_preference 汇总生成）
  └─ 系统提示词注入画像

第 2 周（P0）
  └─ AgentExecutor 每步推送标准化 SSE 事件（含 userLabel / userDetail）

第 3–4 周（P1）
  ├─ AnalysisStrategy 枚举 + 策略提示词注入
  ├─ LLM 多 Provider 回退链
  └─ 建议反馈事件记录 API

第 5–6 周（P1）
  ├─ 事件触发的画像增量更新
  └─ 基于反馈的建议质量自动优化（利用 creator_context_term 的权重机制）
```

---

## 核心数据流

```
       用户交互层（前端负责）
    ┌───────────────────────────────┐
    │ 采纳/拒绝/修改  │  策略选择    │
    │ 思考过程时间轴  │  LLM状态    │
    └───────┬───────────────────────┘
            │ 写入事件
            ▼
    ┌───────────────┐
    │  creator_event │  ← 所有操作流水（本方案新增）
    └───────┬───────┘
            │ 触发分析
            ▼
    ┌────────────────┐      ┌─────────────────────┐
    │ creator_profile │ ←── │ creator_preference   │（已有）
    └───────┬────────┘      │ creator_context_term │（已有）
            │               └─────────────────────┘
            │ 注入提示词
            ▼
    ┌────────────────┐
    │  AgentExecutor  │  ← 每次分析都带着对你的理解
    └────────────────┘

    越用越懂你 ← 飞轮闭环
```

核心思路：**不是加更多功能，而是让现有功能越用越聪明。** 用户第一次用和第十次用，体验应该有质的区别。
