# Multi-Agent 与交互式创作说明

阶段6引入了Multi-Agent编排、PaE重规划、并发审查机制以及交互式创作工作流，将原有ReAct单一执行模式扩展为多模式Agent体系，并构建了从创意输入到发布前优化的完整闭环链路。

---

## 一、Multi-Agent 执行模式

### 1.1 模式概览

通用Agent支持四种执行模式：

| 模式 | 说明 |
|---|---|
| `AUTO` | 默认。后端根据任务复杂度自动选择执行模式 |
| `REACT` | 强制使用原有ReAct内核 |
| `PLAN_EXECUTE` | 强制使用Plan-and-Execute模式 |
| `MULTI_AGENT` | 强制使用多Agent编排 |

### 1.2 响应结构扩展

原有响应字段（sessionId、finalAnswer、stopReason、totalSteps、steps）保持不变。新增字段：

- `executionMode`：实际采用的执行模式
- `planTrace`：PaE或Multi-Agent的计划与执行回放
- `workerTraces`：Multi-Agent中各Worker的执行结果

### 1.3 回退策略

`AUTO`模式下，若PaE或Multi-Agent链路异常，AgentExecutor会记录日志并回退至ReAct执行。显式选择`PLAN_EXECUTE`或`MULTI_AGENT`时不吞异常，便于排查问题。

原有单参数`runTask(String)`保持ReAct语义不变，避免影响发布前优化内部取证链路。需要使用新模式时调用重载方法并显式传入执行模式。

---

## 二、Plan-and-Execute（PaE）模式

### 2.1 核心流程

PaE采用"先计划、再执行、再总结"三段式结构：

```text
Planner -> Executor -> Synthesizer
```

- Planner使用LLM结构化输出生成AgentPlan，将用户任务分解为有序步骤列表
- Executor按计划步骤依次调用ToolExecutor，不自行决策下一步动作
- Synthesizer汇总计划结构与各步骤观察结果，生成最终回答

该架构避免ReAct在复杂任务中每步临时决策可能导致的步骤遗漏或目标偏离问题。

### 2.2 重规划机制

步骤执行失败时触发Replanner尝试重规划剩余步骤。

**触发条件**：步骤状态为`FAILED`时触发。典型场景包括计划引用了不存在的工具、工具返回Error、工具返回为空但计划声明了expectedObservation、Replanner重复已失败的工具方案。

**重规划上限**：每次PaE最多重规划2次，避免在两个失败方案间反复切换。

**失败指纹**：格式为`action::actionInput`。Replanner的prompt和执行器均阻止重复执行同一失败方案。

**编号策略**：Replanner返回的新步骤从当前最大stepId后继续编号，确保前端trace能同时展示原失败步骤和新路线，不会出现重复stepId。

---

## 三、Multi-Agent 编排与并发审查

### 3.1 架构模型

Multi-Agent采用"Orchestrator调度多个独立Worker"的三段式：

```text
Orchestrator Planner -> WorkerAgent（并发） -> Synthesizer
```

当前内置两种Worker：

| Worker | 说明 |
|---|---|
| `plan_execute_worker` | 内部执行PaE，适用于需要工具取证的子任务 |
| `direct_reasoning_worker` | 直接LLM推理，适用于归纳、解释和创作建议 |

Worker均为Spring Bean实现，非prompt中的角色扮演。新增Worker时实现WorkerAgent接口并注册为Bean即可。

### 3.2 Worker并发执行

WorkerCall的`dependsOn`字段定义依赖关系。执行器并发执行所有依赖已满足的Worker。依赖不存在、依赖失败或依赖无法满足时，Worker标记为`SKIPPED`。并发上限为4。

### 3.3 引用答案与Worker输出

AgentWorkerTrace新增两个输出层：

- `brief`：供Synthesizer使用的摘要层
- `evidences`：可引用证据列表

工具取证Worker优先将工具观察结果转化为证据。直接推理Worker的结果标记为`WORKER_REASONING`，用于建议和保守判断。

Synthesizer内部使用`CitedAnswer`结构：
- `statements`：每条回答绑定evidenceIds
- `limitations`：证据不足和执行限制说明

对外仍返回`finalAnswer`字符串兼容旧接口，引用ID直接渲染在句尾。

### 3.4 答案审查器

AgentAnswerAuditor在Synthesizer完成后执行检查：

- 是否完整回答了用户问题
- 是否存在自相矛盾
- 是否存在无引用事实
- 是否错误使用了Worker推理证据

审查失败时最多重写2轮。审查器自身异常不会中断主链路。

---

## 四、交互式创作工作流

### 4.1 功能范围

交互式创作工作流构建了从创作灵感到发布前优化的完整闭环：

```text
用户输入创作想法
  -> 创建标准creator_task
  -> LLM生成3张创意卡片
  -> 前端展示三张卡片
  -> 用户选择一张
  -> 保存选择结果，回写任务材料
  -> 自动进入发布前优化（AI交互台）
```

### 4.2 创意卡片生成

创意卡片管理功能包括：

- **生成创意卡片**：基于用户输入的想法和视频类型，LLM生成三张创意卡片，包含标题大纲、内容大纲、简介大纲、亮点、风险和推荐理由
- **重新生成**：可附带额外需求重新生成卡片，旧卡片软删除
- **确认选择**：用户确认后更新会话状态为`CREATIVE_CONFIRMED`，并将已选卡片回写至creator_material，确保后续发布前优化可读取任务上下文

LLM输出解析失败时，后端记录`parseStatus=RAW_ONLY`并生成三张兜底卡片，避免流程中断。兜底卡片仅保证流程可继续，不代表模型结果质量合格。

### 4.3 发布前优化AI交互台

用户确认创意卡片后自动进入发布前优化，页面以AI对话台形式展示工作流消息。

**文稿生成**：支持"让AI补一版"功能，可附带额外要求（如口播风格、时长偏好）生成可编辑文稿草稿。生成结果保存为MANUSCRIPT类型任务材料，生成过程记录至工作流步骤并纳入开销统计。

**文稿缺失判定**：采用内容长度保护机制：
- MANUSCRIPT或SUBTITLE内容长度不低于800字时，视为已有较完整文稿或字幕，不自动覆盖
- 低于800字时视为大纲或短素材，允许AI扩写成可编辑草稿

此规则避免将创意大纲误认为完整文稿，同时防止覆盖用户已编写的长文稿。

**交互体验**：
- 工作流消息在主页面以对话形式展示
- 缺少完整文稿或字幕时，右侧动作区优先展示补充入口
- 偏好记忆、类型语境库和额外要求收进辅助区域
- 左侧竖向进度条变为只读展示，不可点击切换阶段

### 4.4 数据模型

新增两张数据表：

| 表 | 用途 |
|---|---|
| `creator_interactive_session` | 保存用户原始想法、交互式创作状态、LLM原始输出和选中卡片ID |
| `creator_idea_option` | 保存AI生成的三张创意卡片，包含标题大纲、内容大纲、简介大纲、亮点、风险和推荐理由 |

交互式创作未重写现有任务体系，而是创建标准creator_task，保证发布前优化、反馈分析和复盘均围绕同一taskId扩展。

---

## 五、证据化建议链路

### 5.1 功能定位

发布前优化在生成建议前先收集证据，将建议从"模型认为应如此修改"升级为"基于具体材料、偏好或案例提出修改建议"。

证据化解决可追溯性问题：后续若建议不准，可回溯当时使用的依据。证据化不是审查器，不判断建议是否正确。

### 5.2 核心链路

```text
任务材料 + 创作者偏好 + 类型语境 + 案例库
  -> PrePublishEvidenceCollector
  -> evidenceRefs
  -> 发布前优化Prompt
  -> CreatorSuggestionRecord
```

直连LLM和Agent路径均共用证据包。Agent路径可继续调用knowledge_search，但最终回答优先引用已收集证据。

### 5.3 证据类型

| 类型 | 来源 |
|---|---|
| `TASK_MATERIAL` | 标题草稿、简介草稿、文稿、字幕 |
| `CREATOR_PREFERENCE` | 用户本次手动填写的偏好 |
| `CREATOR_CONTEXT` | 历史偏好、创作者画像和视频类型语境 |
| `REFERENCE_CASE` | 案例库检索命中的同类视频 |
| `SYSTEM_LIMITATION` | 检索不可用、缺少主题等限制说明 |

### 5.4 响应与输出约束

响应新增evidenceRefs（本次收集的证据）、missingInfo（模型声明的缺失信息）、generationMode（DIRECT_LLM_EVIDENCE或AGENT_RAG_EVIDENCE）、qualityStatus（EVIDENCE_COLLECTED）等字段。

Prompt要求标题建议和高优先级修改计划尽量携带evidenceIds、confidence和assumption，便于前端展示推荐理由和依据来源，评测系统也可检查建议是否有依据。

### 5.5 建议审查器

PrePublishSuggestionAuditor在建议生成后执行规则审查，检查AI建议的基础可信度。

**审查状态**：

| 状态 | 说明 |
|---|---|
| `AUDIT_PASSED` | 未发现明显问题 |
| `AUDIT_WARNED` | 存在需人工复核的问题，但不一定是严重错误 |
| `AUDIT_FAILED` | 存在明确错误，如引用了不存在的证据编号 |
| `AUDIT_SKIPPED` | 结构化解析失败或审查器未注入，无法完整审查 |

**审查规则**：

- **证据编号检查**：建议中的evidenceIds必须存在于当前evidenceRefs中，不存在则记录INVALID_EVIDENCE_ID
- **标题建议检查**：标题建议需引用证据，无证据标题可信度较低，记录TITLE_WITHOUT_EVIDENCE
- **高优先级动作检查**：HIGH优先级修改动作必须带证据，避免误导作者对关键方向的投入
- **夸大承诺检查**：标记"必爆""爆款保证""完播率翻倍"等无法保证的表达，当前系统没有真实平台算法和发布后数据支撑此类承诺

**设计边界**：审查器是规则系统而非第二个LLM裁判。不会判断标题是否足够有创意或风格是否完全符合某个账号。规则审查稳定、低成本、可解释，适合作为建议生成后的第一道质量门。

---

## 相关文档

- PaE重规划说明：`docs/reference/PaE重规划说明.md`
- Multi-Agent并发与引用审查：`docs/reference/Multi Agent并发与引用审查说明.md`
- AI交互式创意方案入口：`docs/reference/AI交互式创意方案入口说明.md`
- 发布前优化AI交互台：`docs/reference/发布前优化AI交互台说明.md`
- 发布前优化证据化建议链路：`docs/reference/发布前优化证据化建议链路说明.md`
- 发布前优化建议审查器：`docs/reference/发布前优化建议审查器说明.md`
