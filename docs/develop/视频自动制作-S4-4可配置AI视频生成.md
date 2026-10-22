# S4-4 可配置 AI 视频生成

更新时间：2026-10-22。分支 `feature/creator-workspace-s1`，本轮基线 `b237cc1`。上一步见 [S4-3d 环境声独立混音](视频自动制作-S4-3d环境声独立混音.md)。

## 1. 本轮范围

S4-4 把 `AI_VIDEO` 场景从"必然拒绝"变成一条可配置、可授权、可恢复的生成链路：

```text
故事板含 AI_VIDEO 场景（或明确选择全片切换）
    → 能力查询：是否配置、模型、支持时长、画幅、单价上界
    → 只读估算：每个镜头是生成、复用还是阻断，请求秒数与费用
    → 明确授权受理：PREPARE 运行 + 预算预留
    → 提交异步任务并保存任务标识
    → 轮询/重启续查 → 下载结果 → 真实解码规范化
    → 私有 READY 视频素材 + 提示词摘要
    → 新故事板版本（AI_VIDEO + 生成片段）→ 现有编译器出片
```

不做的事：不写死具体厂商、不做"任意 URL 都兼容"、不把工作流枚举 `AI_GENERATION` 当成已开通付费调用、不用测试视频冒充真实供应商结果。

## 2. 配置入口

```yaml
creator:
  creation:
    video:
      enabled: false
      provider: compatible
      base-url: https://your-video-gateway.example/api
      api-key: ${CREATOR_CREATION_VIDEO_API_KEY:}
      model: your-video-model
      width: 1280
      height: 720
      supported-durations-seconds: 5,10
      usd-per-second: 0.10
      task-timeout: 10m
      poll-interval: 5s
      download-timeout: 5m
      max-scenes: 24
      max-prompt-characters: 600
      max-normalized-bytes: 33554432
```

- 默认 `enabled=false`，不产生任何视频模型调用，也不读新表。
- 启用时缺少服务地址、密钥、模型或时长会直接启动失败，不会带病运行。
- `api-key` 只保存在后端；能力接口返回的结构里没有地址与密钥字段。
- 单价是按秒计价的**配置上界**，写入价格快照，不是供应商真实账单。

## 3. 兼容协议

部署者需要提供一个符合以下异步语义的服务地址；业务只依赖"提交 → 任务标识 → 查询 → 结果地址"，不绑定品牌。

```http
POST {base-url}/video/generations
Authorization: Bearer <api-key>
Content-Type: application/json

{"model":"...","prompt":"...","duration_seconds":5,"size":"1280x720","seed":7}

2xx → {"id":"task-1","request_id":"req-1"}      // 也接受 task_id
```

```http
GET {base-url}/video/generations/{taskId}
Authorization: Bearer <api-key>

{"status":"queued|running|succeeded|failed",
 "video_url":"https://...","duration_seconds":5.0,
 "width":1280,"height":720,"usage_seconds":5,
 "error":{"code":"..."}}
```

同时兼容把状态放在 `output.task_status`、结果放在 `output.video_url`、用量放在 `usage.duration` 的返回。`succeeded` 但没有 `video_url` 视为失败，不会登记空片段。

结果下载使用**不带密钥**的独立客户端，避免结果地址指向第三方时泄露凭据；下载内容必须小于 `max-normalized-bytes` 的 8 倍上界，规范化后再按 32 MiB 限制复核。

## 4. 业务规则

### 4.1 只处理明确要求生成的镜头

- `scope=SELECTED`：只处理故事板里 `layout=AI_VIDEO` 的镜头。素材合成镜头不会因为存在该能力而被自动切换。
- `scope=ALL`："全片采用 AI"的快捷选择。所有镜头切换为 `AI_VIDEO`，提示词优先取场景显式 `visualPrompt`，否则由标题与可见正文确定生成。
- 切换会清空场景正文（视频画面不叠加图文），因此提示词会**显式写回**场景的 `visualPrompt`，避免正文被清空后摘要漂移；估算阶段会列出哪些镜头将切换。
- 不修改声音方案：`ORIGINAL` 镜头不会被静默改成配音。
- `dialogueMode=SOURCE` 的简报整体阻断：这类简报的画面与声音都来自用户视频，AI 片段没有可保留的原声轨；即使某个图文镜头当前是 `NO_DIALOGUE`，切换后也会与简报来源冲突。必须在付费前改用配音或无对白简报。

### 4.2 复用未变镜头

估算与受理前按以下条件查找可复用片段：

- 提示词摘要一致；
- 素材仍是本任务的 `READY` `VIDEO_CLIP`；
- 素材实际时长覆盖当前场景帧长；
- `regenerate=false`。

命中复用时费用为 0，不会再次提交供应商任务。改标题、正文或 `visualPrompt` 会改变摘要，旧片段自然失效。

估算对每个镜头返回 `bodyCleared`：为真表示切换后原可见正文不会再出现在画面里，调用方应在确认页明确展示。

### 4.3 时长与片段

- 请求时长取"能覆盖场景帧长的最小支持秒数"。例如场景 2 秒、支持 5/10 秒时请求 5 秒。
- 生成片段比场景长：按场景帧长从 0 开始裁切，属于正常区间选择。
- 生成片段比场景短：不循环、不定格、不变速，返回 `SHORTEN_SCENE` 提案（`proposedDurationMs` 为片段向下量化到帧的可用长度），不保存新故事板。片段本身仍登记为私有 READY 素材；作者把场景缩短后再次生成时，估算按提示词与时长命中复用，费用为 0。
- 场景超过单次生成上限：估算阶段阻断，提示拆分或缩短场景。

### 4.4 编译与来源

- 业务故事板保留 `layout=AI_VIDEO`；V2 工程输出 `VIDEO_CLIP`，渲染器无需新增布局。
- 编译器要求 AI 场景引用 `VIDEO_CLIP` 素材，且 `metadata.generated.kind=AI_VIDEO`、`promptSha256` 等于当前场景有效提示词的摘要。
- 用户上传的片段不能冒充生成结果（`AI_VIDEO_ASSET_INVALID`）；改词后旧片段会触发 `AI_VIDEO_PROMPT_MISMATCH`。
- 生成片段只能进入 `AI_VIDEO` 场景；`codec.policy` 拒绝把它用于其他布局。

## 5. 异步任务与费用

| 阶段 | 持久化事实 | 费用处理 |
| --- | --- | --- |
| 受理 | 运行、授权、任务行、场景行、提示词摘要、请求秒数、价格快照 | 未预留 |
| 提交前 | `SUBMITTING` + 供应商与模型 | 按秒预留 |
| 发送 | 发送标记 `sent_at` | 只有第一次发送成功 |
| 已提交 | 供应商任务标识 `provider_task_id` | 只能查询，不能重复提交 |
| 成功 | 片段摘要、字节、时长、宽高、素材身份 | 按供应商用量结算，缺失时按请求秒数估算 |
| 明确 4xx 拒绝 | 场景失败 | 按 0 结算，不阻塞后续调用 |
| 结果不明/超时/5xx | 场景失败，保留任务标识 | `UNKNOWN`，不自动重发 |
| 取消 | 场景收口为 `CANCELLED` | 已发送的调用转 `UNKNOWN`，未发送的释放 |

重启与接管：

- `SUBMITTING` 且没有发送事实 → 回到 `QUEUED`；
- `SUBMITTING` 且已经发送 → `UNKNOWN` + 运行失败，不重发；
- `SUBMITTED` → 按已保存的任务标识继续查询，不重新提交；
- 取消或失败运行的遗留行由独立扫描收口，不会长期停留在提交中。

## 6. 新版本与旧版本

生成完成后：

- 以来源故事板为父版本，新建故事板版本；
- 目标场景改为 `AI_VIDEO`，写入生成素材、`sourceInMs=0`、`sourceOutMs=场景帧长`、显式提示词；
- 继承父版本冻结快照中的全部素材（已有 TTS 配音、其他 AI 片段、用户素材），不会因为一次视频生成丢引用；
- 字幕来源沿用父版本（用户提供 / ASR 对齐 / 未提供），不把 ASR 结果改写成用户提供；
- 其他场景、简报、素材快照保持不变；
- 旧故事板、旧简报、旧片段全部保留；
- 保存前再次运行完整编译器检查，不可渲染时不写版本。

## 7. 当前边界

- 未接入任何真实视频供应商，也未验收真实账单、限流、内容审核与失败码。
- 未提供模型配置页面、参考图/首尾帧、镜头级重做按钮、样片范围和页面交互。
- 一个部署只接一个兼容供应商；多供应商与能力矩阵留给后续。
- 未实现局部编辑、人物一致性、片段拼接或跨镜头连续性。
- 生成片段不自动进入发布包；仍需后续真实试映、导出与投稿流程。
- `UNKNOWN` 费用目前没有核对/结清接口，会暂停该任务后续付费调用（沿用既有运行台账限制），需要后续管理入口或人工核对。
- 结果下载先整包读入内存再校验上限；供应商返回的地址若指向内网，存在服务端请求风险。当前只接受 http/https 且下载不带密钥，部署时应只配置可信供应商。

## 8. 验证口径

最终 Java 选定套件结果、真实 MySQL 用例与协议替身覆盖见 [S4-4 验证记录](../error/视频生成S4-4验证记录.md)。协议与持久化由本地替身证明，**不代表真实供应商已验收**。
