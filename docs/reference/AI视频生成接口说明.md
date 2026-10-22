# AI 视频生成接口说明

对应实现：[S4-4 可配置 AI 视频生成](../develop/视频自动制作-S4-4可配置AI视频生成.md)。所有接口都在 `/api/creator/tasks/{taskId}/creation` 下，`taskId` 由服务端归属校验。

## 1. 能力查询

```http
GET /api/creator/tasks/{taskId}/creation/ai-video:capability
```

只读、不产生运行或费用。返回脱敏状态，不含服务地址与密钥：

```json
{
  "configured": true,
  "provider": "compatible",
  "model": "your-video-model",
  "durationsSeconds": [5, 10],
  "width": 1280,
  "height": 720,
  "usdPerSecond": 0.10,
  "usdToCny": 7.20,
  "priceBasis": "CONFIGURED_ESTIMATE",
  "protocolVersion": "async-task.v1",
  "blockers": []
}
```

`configured=false` 时 `blockers` 说明缺少开关、地址、密钥、模型还是时长。页面应直接展示原因，不要提供点了才失败的入口。

## 2. 估算

```http
POST /api/creator/tasks/{taskId}/creation/storyboards/{storyboardId}/ai-video:estimate
Content-Type: application/json

{"expectedWorkspaceRevision": 12, "scope": "SELECTED", "regenerate": false}
```

`scope` 取值：

| 值 | 含义 |
| --- | --- |
| `SELECTED` | 只处理故事板中已经是 `AI_VIDEO` 的镜头 |
| `ALL` | 全片采用 AI：所有镜头切换为 `AI_VIDEO`，未填提示词的镜头由标题与可见正文确定 |

`regenerate=true` 强制重新生成，不复用旧片段。

只读：不创建运行、不消耗修订、不产生费用。响应：

```json
{
  "storyboardId": "board-1",
  "briefId": "brief-1",
  "scope": "SELECTED",
  "configured": true,
  "regenerate": false,
  "provider": "compatible",
  "model": "your-video-model",
  "durationsSeconds": [5, 10],
  "width": 1280,
  "height": 720,
  "usdPerSecond": 0.10,
  "usdToCny": 7.20,
  "priceBasis": "CONFIGURED_ESTIMATE",
  "sceneCount": 1,
  "generateCount": 1,
  "reuseCount": 0,
  "switchCount": 0,
  "estimatedMaxCostCny": 3.600000,
  "scenes": [
    {
      "sceneId": "s1",
      "layout": "AI_VIDEO",
      "action": "GENERATE",
      "switchesToAi": false,
      "bodyCleared": false,
      "durationMs": 2000,
      "requestedSeconds": 5,
      "prompt": "海边日落，缓慢推进",
      "promptSha256": "…",
      "reuseAssetId": null,
      "estimatedUsd": 0.50,
      "estimatedCny": 3.600000,
      "blockers": []
    }
  ],
  "blockers": []
}
```

`action`：

| 值 | 含义 |
| --- | --- |
| `GENERATE` | 需要提交新的生成任务 |
| `REUSE` | 已有提示词一致、时长覆盖的 READY 片段，费用为 0 |
| `BLOCKED` | 当前不能生成，原因在该场景 `blockers` |

`blockers` 已聚合所有场景级原因；非空时 `generate` 会直接 409，不会先受理再失败。

`bodyCleared=true` 表示该镜头切换为 AI 画面后，原可见正文不会再出现在画面里；正文会进入提示词，确认页应明确展示这一点。

常见的场景级阻断：

- 画面描述为空或超过 `max-prompt-characters`；
- 场景需要 N 秒，超过 `supported-durations-seconds` 的最大值；
- `dialogueMode=SOURCE` 的简报整体阻断：AI 片段没有可保留的原声轨；即使当前是 `NO_DIALOGUE` 的图文镜头，切换后也会与简报来源冲突。

## 3. 受理

```http
POST /api/creator/tasks/{taskId}/creation/storyboards/{storyboardId}/ai-video
Idempotency-Key: video-20261022-1
Content-Type: application/json

{
  "expectedWorkspaceRevision": 12,
  "maxAdditionalCostCny": 10.00,
  "scope": "SELECTED",
  "regenerate": false
}
```

- 必须先估算，授权额度不得低于 `estimatedMaxCostCny`；
- 同一幂等键重放返回原运行；同键换范围、换提示词或改额度会被拒绝；
- 受理生成 `PREPARE` 运行和独立 `PREPARATION` 授权，不占用文稿生成的授权；
- 并发同键由唯一键兜底，只产生一份任务。

响应 `202`：

```json
{
  "runId": "run-1",
  "status": "QUEUED",
  "stage": "PREPARE",
  "storyboardId": "board-1",
  "storyboardResultId": null,
  "requiresUserDecision": false,
  "scenes": [
    {
      "sceneId": "s1", "position": 0, "status": "QUEUED",
      "prompt": "海边日落，缓慢推进", "promptSha256": "…",
      "sceneDurationMs": 2000, "requestedSeconds": 5,
      "videoAssetId": null, "videoDurationMs": null,
      "width": null, "height": null,
      "providerTaskId": null, "errorCode": null, "proposals": []
    }
  ],
  "proposals": []
}
```

## 4. 查询

```http
GET /api/creator/tasks/{taskId}/creation/ai-video-preparations/{runId}
```

`status` 为该准备运行的状态（`QUEUED` / `RUNNING` / `COMPLETED` / `FAILED` / `CANCELLED`）。场景 `status`：

| 状态 | 含义 |
| --- | --- |
| `QUEUED` | 排队，尚未提交 |
| `SUBMITTING` | 已开始提交，是否已发送以费用记录为准 |
| `SUBMITTED` | 供应商已受理，按任务标识查询 |
| `READY` | 片段已下载、复核并登记为私有素材 |
| `NEEDS_REVIEW` | 需要作者处理，见 `proposals` |
| `FAILED` | 受控失败，见 `errorCode` |
| `CANCELLED` | 取消收口 |

成功后 `storyboardResultId` 是新故事板版本；`requiresUserDecision=true` 时它为空。

## 5. 提案

| code | 含义 |
| --- | --- |
| `SHORTEN_SCENE` | 生成片段实际短于场景。`proposedDurationMs` 是片段向下量化到帧的可用长度；作者需缩短场景并重新确认全片时长，或重新生成更长片段。片段本身已登记为私有 READY 素材，缩短场景后再次生成会按提示词与时长复用，费用为 0。 |

片段比场景长属于正常裁切，不会产生提案。系统不会循环、定格或变速补齐。

## 6. 受控错误码

| code | 含义 | 费用 |
| --- | --- | --- |
| `VIDEO_PROVIDER_NOT_CONFIGURED` | 供应商适配未加载或凭据缺失 | 未发送；已预留按 0 结算 |
| `VIDEO_DURATION_INVALID` | 请求秒数无效 | 未发送 |
| `VIDEO_SUBMIT_NOT_SENT` | 发送标记未取得，未调用供应商 | 未发送 |
| `VIDEO_GENERATION_REJECTED` | 供应商明确 4xx 拒绝 | 按 0 结算 |
| `VIDEO_RESULT_UNKNOWN` | 已发送但结果不明，或中断后无法确认 | `UNKNOWN`，不自动重发 |
| `VIDEO_SUBMIT_UNCERTAIN` | 提交响应丢失或任务标识保存失败 | `UNKNOWN` |
| `VIDEO_GENERATION_FAILED` | 供应商任务明确失败 | 有用量按用量结算，否则 `UNKNOWN` |
| `VIDEO_GENERATION_TIMEOUT` | 超过任务等待窗口 | `UNKNOWN` |
| `VIDEO_RESULT_UNAVAILABLE` | 结果地址下载失败或已过期 | 已按成功结算的成本保留 |
| `VIDEO_FORMAT_UNSUPPORTED` | 下载内容不是有效 MP4 | 成本已结算，不登记素材 |
| `VIDEO_FORMAT_INVALID` | 规范化失败或缺少画面/时长 | 成本已结算，不登记素材 |
| `VIDEO_TOO_LARGE` | 规范化片段超过 32 MiB | 成本已结算，不登记素材 |
| `VIDEO_RESOLUTION_INVALID` | 规范化片段超过 800 万像素 | 成本已结算，不登记素材 |
| `VIDEO_STORAGE_*` | 私有存储未配置、上传校验失败或不可用 | 成本已结算 |
| `VIDEO_BUDGET_BLOCKED` | 预留被预算、未知费用或授权限制拒绝 | 未发送 |
| `VIDEO_BOARD_NOT_RENDERABLE` | 收尾时故事板仍不可渲染 | 已发生的调用保留 |
| `VIDEO_ASSET_MISSING` | 结果素材缺失或不属于本任务 | 已发生的调用保留 |
| `VIDEO_PREPARATION_INCOMPLETE` | 存在未到终态的场景 | — |
| `VIDEO_RUN_ABORTED` | 轮询/结算中出现非受控异常，运行已中止 | 已发生的调用按台账保留 |
| `VIDEO_RUN_FAILED` | 失败运行遗留行统一收口 | — |
| `VIDEO_CHARGE_MISSING` / `VIDEO_PRICE_SNAPSHOT_INVALID` | 费用预留或价格快照缺失、损坏 | 不猜测金额 |
| `VIDEO_CANCELLED` / `VIDEO_INTERRUPTED` | 取消或执行线程中断 | 已发送的调用转 `UNKNOWN` |

编译器相关：

| code | 含义 |
| --- | --- |
| `AI_VIDEO_ASSET_MISSING` | AI 场景还没有生成片段 |
| `AI_VIDEO_ASSET_INVALID` | 引用的不是本系统生成的视频 |
| `AI_VIDEO_PROMPT_MISMATCH` | 片段摘要不对应当前场景提示词 |

## 7. 与其他流程的顺序

1. 保存/确认故事板（`AI_VIDEO` 场景可先无素材）；
2. 能力查询与估算；
3. 明确授权受理；
4. 查询到 `storyboardResultId`；
5. 如需调整时长，再使用 [时间线调整采纳](时间线调整采纳接口说明.md)；
6. 使用新故事板进入现有准备/整片流程。

配音与视频可以同时存在于一个故事板：配音运行只改 `TTS` 场景的配音与字幕，视频运行只改 `AI_VIDEO` 场景的画面；两边都会保留对方的素材与提示词。

取消使用 `POST /api/creator/tasks/{taskId}/creation/runs/{runId}:cancel`，不承诺供应商退款。

超时、断连或取消产生的 `UNKNOWN` 会暂停该任务后续付费调用；当前接口只提供查询，没有结清/撤销入口，需要后续管理能力或人工核对。

## 8. 未提供

- 参考图、首尾帧、镜头运动参数与负面提示词；
- 模型列表/多供应商切换与每家能力矩阵；
- 局部重绘、人物一致性、跨镜头连续性；
- 生成片段的预览地址、下载与发布包接入；
- 页面、样片范围、自动重试与账单对账；
- `UNKNOWN` 费用的核对/结清入口；
- 多供应商切换、参考图与局部重绘。
